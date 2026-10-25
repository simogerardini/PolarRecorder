package com.wboelens.polarrecorder.biosleep.garmin

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.wboelens.polarrecorder.R
import com.wboelens.polarrecorder.biosleep.NightSummary
import com.wboelens.polarrecorder.biosleep.SleepDb
import com.wboelens.polarrecorder.biosleep.auto.NightNotifier
import com.wboelens.polarrecorder.biosleep.cervello.AttesaTag
import com.wboelens.polarrecorder.biosleep.cervello.CoachWorker
import com.wboelens.polarrecorder.biosleep.cervello.ModalitaCoach
import com.wboelens.polarrecorder.biosleep.cervello.NotificheCoach
import com.wboelens.polarrecorder.biosleep.demo.Demo
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsClient
import com.wboelens.polarrecorder.biosleep.watchdog.AutoStopLog
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Giorni (mattine) che hanno gia' una notte nel database, di qualunque sorgente o di una sola. */
internal fun mattineConNotte(context: Context, sorgente: Int? = null): Set<String> =
    SleepDb.get(context).listNights()
        .filter { sorgente == null || it.summary.sorgente == sorgente }
        .map { IntervalsClient.morningDate(it.summary) }.toSet()

/**
 * Dopo la notte Garmin di oggi. Niente Intervals.icu (Parte 4: le notti Garmin restano sul
 * telefono): il coach parte come dopo l'invio della fascia e legge le notti del telefono
 * ("notti_locali", Cervello.esegui).
 */
internal fun avviaCoach(context: Context, giorno: String) {
  if (giorno != LocalDate.now().toString() || !ModalitaCoach.attivo(context)) return
  val attesaMs = AttesaTag.ms(context)
  CoachWorker.dopoNotte(context, giorno, attesaMs)
  if (attesaMs > 0) NotificheCoach.chiediTag(context, giorno)
}

private fun JsonElement?.oggetto(): JsonObject? = this?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.numero(k: String): Double? =
    get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

/** Avviso unico 14 giorni prima della scadenza della chiave (durata scelta dall'utente). */
private fun avvisoScadenza(ctx: Context) {
  val s = GarminSettings(ctx)
  val scadenza = s.scadenzaMs ?: return
  val giorni = ((scadenza - System.currentTimeMillis()) / 86_400_000L).toInt()
  if (giorni in 0..14 && s.primoAvvisoScadenza()) {
    NightNotifier.notifyAlert(ctx, ctx.getString(R.string.garmin_avviso_scadenza_titolo),
        ctx.getString(R.string.garmin_avviso_scadenza_testo, giorni))
  }
}

/**
 * Parte 11: la notte di oggi da Garmin Connect. Gira ogni ora e all'apertura dell'app; dalle 5
 * alle 13, finche' Garmin non ha la notte. Fino alle 9 aspetta anche l'HRV.
 */
class GarminNotteWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val ctx = applicationContext
    if (!GarminScelta.attiva(ctx) || Demo.attiva(ctx)) return Result.success()
    avvisoScadenza(ctx)
    val ora = LocalTime.now()
    if (ora.hour < 5 || ora.hour >= 13) return Result.success()
    val oggi = LocalDate.now()
    return withContext(Dispatchers.IO) {
      if (oggi.toString() in mattineConNotte(ctx, NightSummary.SORGENTE_GARMIN)) return@withContext Result.success()
      when (val e = GarminNotte.scarica(ctx, oggi, aspettaHrv = ora.hour < 9)) {
        is GarminNotte.Esito.Salvata -> {
          GarminScelta.salvaEsito(ctx, "salvata:$oggi")
          AutoStopLog.write(ctx, "Notte Garmin $oggi salvata (sessione ${e.sessionId}), solo sul telefono")
          // attribuzione: la riga della notifica dice da dove vengono i valori
          NightNotifier.notifySummary(ctx, e.notte.summary.copy(sessionId = e.sessionId), e.notte.stages,
              ctx.getString(R.string.garmin_fonte_breve))
          avviaCoach(ctx, oggi.toString())
        }
        GarminNotte.Esito.NonAncora -> GarminScelta.salvaEsito(ctx, "attesa")
        GarminNotte.Esito.TroppeRichieste -> GarminScelta.salvaEsito(ctx, "limite")
        GarminNotte.Esito.ChiaveNonValida -> {
          GarminScelta.salvaEsito(ctx, "chiave")
          if (GarminScelta.primoAvvisoDelGiorno(ctx, oggi.toString())) {
            NightNotifier.notifyAlert(ctx, ctx.getString(R.string.garmin_avviso_chiave_titolo), ctx.getString(R.string.garmin_chiave_non_valida))
          }
        }
        is GarminNotte.Esito.Errore -> {
          GarminScelta.salvaEsito(ctx, "errore")
          AutoStopLog.write(ctx, "Notte Garmin $oggi: ${e.messaggio}")
        }
      }
      Result.success()
    }
  }
}

/**
 * Parte 11: storico iniziale (una volta, quando si sceglie Garmin): fino a 28 notti, solo sul
 * telefono. Minimizzazione (Parte 4): sonno e HRV a intervallo + la sola FC del giorno
 * (dailyHeartRate) per ogni notte da salvare; niente riepiloghi giornalieri (passi, stress...).
 * Salta i giorni che hanno gia' una notte (di qualunque sorgente) e la notte di oggi.
 */
class GarminStoricoWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  companion object {
    private const val GIORNI = 28L
  }

  private fun problema(r: LetturaGarmin) = r !is LetturaGarmin.Ok && r !is LetturaGarmin.Vuoto

  override suspend fun doWork(): Result =
      withContext(Dispatchers.IO) {
        val ctx = applicationContext
        if (!GarminScelta.attiva(ctx)) return@withContext Result.success()
        val oggi = LocalDate.now()
        val da = oggi.minusDays(GIORNI)
        val a = oggi.minusDays(1)
        val presenti = mattineConNotte(ctx)
        val sonni = GarminClient.leggi(ctx, "/sleep-service/sleep/dailySleepSummariesByDate?startDate=$da&endDate=$a")
        val hrv = GarminClient.leggi(ctx, "/hrv-service/hrv/daily/$da/$a")
        if (problema(sonni) || problema(hrv)) {
          AutoStopLog.write(ctx, "Storico Garmin: lettura non riuscita, riprovo piu' tardi")
          return@withContext Result.retry()
        }
        val hrvPerGiorno =
            (hrv as? LetturaGarmin.Ok)?.json.oggetto()?.get("hrvSummaries")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.oggetto() }?.associateBy { it.get("calendarDate")?.takeIf { d -> !d.isJsonNull }?.asString ?: "" }
                .orEmpty()
        val elenco = (sonni as? LetturaGarmin.Ok)?.json?.takeIf { it.isJsonArray }?.asJsonArray
        var salvate = 0
        for (e in elenco ?: emptyList<JsonElement>()) {
          val sonno = e.oggetto() ?: continue
          val giorno = sonno.get("calendarDate")?.takeIf { !it.isJsonNull }?.asString ?: continue
          if (giorno in presenti || giorno >= oggi.toString()) continue
          if ((sonno.numero("sleepTimeSeconds") ?: 0.0) <= 0.0) continue
          val rf = GarminClient.leggi(ctx, "/wellness-service/wellness/dailyHeartRate?date=$giorno")
          if (problema(rf)) {
            AutoStopLog.write(ctx, "Storico Garmin interrotto ($salvate notti salvate): riprovo piu' tardi")
            return@withContext Result.retry()
          }
          val fc = (rf as? LetturaGarmin.Ok)?.json
          val h = hrvPerGiorno[giorno]?.let { JsonObject().apply { add("hrvSummary", it) } }
          val notte =
              GarminNotte.componi(sonno, h, GarminNotte.serieFc(fc),
                  fcRiposo = fc.oggetto()?.numero("restingHeartRate"),
                  fcMinima = fc.oggetto()?.numero("minHeartRate")) ?: continue
          GarminNotte.salva(ctx, notte)
          salvate++
        }
        GarminScelta.storicoFatto(ctx)
        AutoStopLog.write(ctx, "Storico Garmin: $salvate notti salvate (solo sul telefono)")
        Result.success()
      }
}
