package com.wboelens.polarrecorder.biosleep.garmin

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.JsonObject
import com.wboelens.polarrecorder.R
import com.wboelens.polarrecorder.biosleep.NightSummary
import com.wboelens.polarrecorder.biosleep.SleepDb
import com.wboelens.polarrecorder.biosleep.auto.NightNotifier
import com.wboelens.polarrecorder.biosleep.demo.Demo
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsClient
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsSettings
import com.wboelens.polarrecorder.biosleep.intervals.InvioNotteWorker
import com.wboelens.polarrecorder.biosleep.watchdog.AutoStopLog
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Giorni (mattine) che hanno gia' una notte nel database, di qualunque sorgente. */
internal fun mattineConNotte(context: Context, sorgente: Int? = null): Set<String> =
    SleepDb.get(context).listNights()
        .filter { sorgente == null || it.summary.sorgente == sorgente }
        .map { IntervalsClient.morningDate(it.summary) }.toSet()

/** Dopo il salvataggio: invio a Intervals.icu (che avvia il coach per la notte di oggi). */
internal fun dopoSalvataggio(context: Context, sessionId: Long, motivo: String) {
  val s = IntervalsSettings(context)
  if (s.isConfigured && s.autoUpload) InvioNotteWorker.accoda(context, sessionId, motivo)
}

/**
 * Parte 11: la notte di oggi da Garmin Connect. Gira ogni ora e all'apertura dell'app; dalle 5
 * alle 13, finche' Garmin non ha la notte. Fino alle 9 aspetta anche l'HRV.
 */
class GarminNotteWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val ctx = applicationContext
    if (!GarminScelta.attiva(ctx) || Demo.attiva(ctx)) return Result.success()
    val ora = LocalTime.now()
    if (ora.hour < 5 || ora.hour >= 13) return Result.success()
    val oggi = LocalDate.now()
    return withContext(Dispatchers.IO) {
      if (oggi.toString() in mattineConNotte(ctx, NightSummary.SORGENTE_GARMIN)) return@withContext Result.success()
      val e = GarminNotte.scarica(ctx, oggi, aspettaHrv = ora.hour < 9)
      when (e) {
        is GarminNotte.Esito.Salvata -> {
          GarminScelta.salvaEsito(ctx, "salvata:$oggi")
          AutoStopLog.write(ctx, "Notte Garmin $oggi salvata (sessione ${e.sessionId})")
          NightNotifier.notifySummary(ctx, e.notte.summary.copy(sessionId = e.sessionId), e.notte.stages, null)
          dopoSalvataggio(ctx, e.sessionId, "notte Garmin")
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
 * Parte 11: storico iniziale (una volta, quando si sceglie Garmin): fino a 56 notti con 3 chiamate
 * ogni 28 giorni (sonno, HRV e riepilogo del giorno), cosi' la banda non aspetta 14 notti.
 * Salta i giorni che hanno gia' una notte (di qualunque sorgente) e la notte di oggi.
 */
class GarminStoricoWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  companion object {
    private const val GIORNI = 56L
    private const val BLOCCO = 28L
  }

  override suspend fun doWork(): Result =
      withContext(Dispatchers.IO) {
        val ctx = applicationContext
        if (!GarminScelta.attiva(ctx)) return@withContext Result.success()
        val oggi = LocalDate.now()
        val presenti = mattineConNotte(ctx)
        var salvate = 0
        var da = oggi.minusDays(GIORNI)
        while (da < oggi) {
          val a = minOf(da.plusDays(BLOCCO - 1), oggi.minusDays(1))
          val sonni = GarminClient.leggi(ctx, "/sleep-service/sleep/dailySleepSummariesByDate?startDate=$da&endDate=$a")
          val hrv = GarminClient.leggi(ctx, "/hrv-service/hrv/daily/$da/$a")
          val giorni = GarminClient.leggi(ctx, "/usersummary-service/v2/dailysummaries?startDate=$da&endDate=$a")
          if (listOf(sonni, hrv, giorni).any { it !is LetturaGarmin.Ok && it !is LetturaGarmin.Vuoto }) {
            AutoStopLog.write(ctx, "Storico Garmin interrotto ($salvate notti salvate): riprovo piu' tardi")
            return@withContext Result.retry()
          }
          val hrvPerGiorno =
              (hrv as? LetturaGarmin.Ok)?.json?.takeIf { it.isJsonObject }?.asJsonObject?.getAsJsonArray("hrvSummaries")
                  ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
                  ?.associateBy { it.get("calendarDate")?.asString ?: "" }.orEmpty()
          val fcPerGiorno =
              (giorni as? LetturaGarmin.Ok)?.json?.takeIf { it.isJsonObject }?.asJsonObject?.getAsJsonArray("data")
                  ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
                  ?.associateBy { it.get("calendarDate")?.asString ?: "" }.orEmpty()
          val elenco = (sonni as? LetturaGarmin.Ok)?.json?.takeIf { it.isJsonArray }?.asJsonArray
          for (e in elenco ?: emptyList<com.google.gson.JsonElement>()) {
            val sonno = e.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val giorno = sonno.get("calendarDate")?.takeIf { !it.isJsonNull }?.asString ?: continue
            if (giorno in presenti || giorno >= oggi.toString()) continue
            val h = hrvPerGiorno[giorno]?.let { JsonObject().apply { add("hrvSummary", it) } }
            val fc = fcPerGiorno[giorno]?.get("heartRate")?.takeIf { it.isJsonObject }?.asJsonObject
            val notte =
                GarminNotte.componi(sonno, h, emptyList(),
                    fcRiposo = fc?.get("restingValue")?.takeIf { !it.isJsonNull }?.asDouble,
                    fcMinima = fc?.get("minValue")?.takeIf { !it.isJsonNull }?.asDouble) ?: continue
            dopoSalvataggio(ctx, GarminNotte.salva(ctx, notte), "storico Garmin")
            salvate++
          }
          da = a.plusDays(1)
        }
        GarminScelta.storicoFatto(ctx)
        AutoStopLog.write(ctx, "Storico Garmin: $salvate notti salvate")
        Result.success()
      }
}
