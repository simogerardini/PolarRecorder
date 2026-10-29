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
import kotlinx.coroutines.delay
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
  if (giorno != LocalDate.now().toString() || !ModalitaCoach.attivo(context)) {
    AutoStopLog.write(context, "Coach dopo la notte Garmin $giorno: non avviato (coach spento o notte non di oggi)")
    return
  }
  val attesaMs = AttesaTag.ms(context)
  CoachWorker.dopoNotte(context, giorno, attesaMs)
  AutoStopLog.write(context, "Coach dopo la notte Garmin $giorno: avviato (attesa tag ${attesaMs / 60_000} min)")
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
    val subito = inputData.getBoolean(GarminScelta.K_SUBITO, false) // apertura dell'app: sempre
    avvisoScadenza(ctx)
    val ora = LocalTime.now()
    if (ora.hour < 5 || ora.hour >= 13) return Result.success()
    val oggi = LocalDate.now()
    return withContext(Dispatchers.IO) {
      if (oggi.toString() in mattineConNotte(ctx, NightSummary.SORGENTE_GARMIN)) return@withContext Result.success()
      // 29/10: controllo ogni 15 minuti fino alle 10 (la notte arriva entro 15' dalla sincronizzazione),
      // poi al massimo uno all'ora: finche' Garmin non ha la notte costa una chiamata
      if (!subito && ora.hour >= 10 && System.currentTimeMillis() - GarminScelta.ultimoTentativoMs(ctx) < 55 * 60_000L) {
        return@withContext Result.success()
      }
      GarminScelta.segnaTentativo(ctx)
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
 * telefono, notte di oggi compresa (anche se si collega Garmin di sera).
 * 28/10: ogni notte si legge come al mattino (GarminNotte.scarica: sonno, HRV con le letture ogni
 * 5 minuti, FC del giorno e della vigilia). Prima lo storico leggeva solo i riepiloghi HRV e la FC
 * del giorno: senza letture niente grafico rMSSD, e senza la FC prima di mezzanotte l'andamento
 * stimato delle fasi era inventato. Costa 4 chiamate per notte, una volta sola.
 * Salta i giorni che hanno gia' una notte (di qualunque sorgente).
 */
class GarminStoricoWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  companion object {
    private const val GIORNI = 28L
    private const val PAUSA_MS = 500L
  }

  override suspend fun doWork(): Result =
      withContext(Dispatchers.IO) {
        val ctx = applicationContext
        if (!GarminScelta.attiva(ctx)) return@withContext Result.success()
        val oggi = LocalDate.now()
        val presenti = mattineConNotte(ctx)
        // un'unica chiamata per sapere quali giorni hanno un sonno
        val sonni = GarminClient.leggi(ctx, "/sleep-service/sleep/dailySleepSummariesByDate?startDate=${oggi.minusDays(GIORNI)}&endDate=$oggi")
        if (sonni !is LetturaGarmin.Ok && sonni !is LetturaGarmin.Vuoto) {
          AutoStopLog.write(ctx, "Storico Garmin: lettura non riuscita, riprovo piu' tardi")
          return@withContext Result.retry()
        }
        val giorni =
            ((sonni as? LetturaGarmin.Ok)?.json?.takeIf { it.isJsonArray }?.asJsonArray ?: emptyList<JsonElement>())
                .mapNotNull { it.oggetto() }
                .filter { (it.numero("sleepTimeSeconds") ?: 0.0) > 0.0 }
                .mapNotNull { it.get("calendarDate")?.takeIf { d -> !d.isJsonNull }?.asString }
                .filter { it !in presenti && it <= oggi.toString() }
                .sorted()
        var salvate = 0
        for (g in giorni) {
          when (val e = GarminNotte.scarica(ctx, LocalDate.parse(g), aspettaHrv = false)) {
            is GarminNotte.Esito.Salvata -> salvate++
            GarminNotte.Esito.NonAncora -> {}
            else -> {
              AutoStopLog.write(ctx, "Storico Garmin interrotto ($salvate notti salvate, $e): riprovo piu' tardi")
              return@withContext Result.retry()
            }
          }
          delay(PAUSA_MS)
        }
        GarminScelta.storicoFatto(ctx)
        AutoStopLog.write(ctx, "Storico Garmin: $salvate notti salvate (solo sul telefono)")
        Result.success()
      }
}
