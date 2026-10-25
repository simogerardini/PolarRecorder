package com.wboelens.polarrecorder.biosleep.garmin

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.wboelens.polarrecorder.biosleep.CleanBeats
import com.wboelens.polarrecorder.biosleep.NightResult
import com.wboelens.polarrecorder.biosleep.NightSummary
import com.wboelens.polarrecorder.biosleep.SleepDb
import com.wboelens.polarrecorder.biosleep.SleepStages
import com.wboelens.polarrecorder.biosleep.WindowMetrics
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Parte 11: una notte di Garmin Connect -> notte NoctaliX (sessions.source = "GARMIN").
 *
 * Mappatura (forma verificata sulla sonda del 25/10/2026):
 * - durata e fasi: dailySleep (sleepStart/EndTimestampGMT, *SleepSeconds). La Personal API non da'
 *   l'andamento delle fasi nella notte, solo i totali: l'ipnogramma resta vuoto (mode "GARMIN").
 * - rMSSD: hrvSummary.lastNightAvg, cioe' la media delle finestre di 5' del sonno (stessa
 *   definizione di NoctalixRMSSD); finestre: hrvReadings ogni 5'.
 * - FC: serie di dailyHeartRate (un valore ogni 2') nella finestra di sonno; FC media = avgHeartRate
 *   del sonno (e' il valore che la banda usa come FC a riposo, come per la fascia).
 * - Affidabilita': % della notte coperta da letture HRV (stessa idea della fascia).
 * - Respiro e minimo della FC non si scrivono: livelli diversi dalla fascia, il coach li legge
 *   senza separare le sorgenti.
 * Tutti gli orari sono GMT in millisecondi (i campi "Local" di Garmin sbagliano nel cambio d'ora).
 */
object GarminNotte {
  const val MODO_FASI = "GARMIN"
  private const val FINESTRA_MS = 300_000L

  data class Notte(val summary: NightSummary, val windows: List<WindowMetrics>, val stages: SleepStages)

  private fun JsonObject.num(k: String): Double? =
      get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

  private fun JsonObject.obj(k: String): JsonObject? = get(k)?.takeIf { it.isJsonObject }?.asJsonObject

  private fun JsonObject.arr(k: String): JsonArray? = get(k)?.takeIf { it.isJsonArray }?.asJsonArray

  /** heartRateValues di dailyHeartRate -> (ms, bpm), senza i valori nulli. */
  fun serieFc(json: JsonElement?): List<Pair<Long, Int>> =
      (json?.takeIf { it.isJsonObject }?.asJsonObject?.arr("heartRateValues") ?: return emptyList()).mapNotNull { e ->
        val a = e.takeIf { it.isJsonArray }?.asJsonArray ?: return@mapNotNull null
        if (a.size() < 2 || a[0].isJsonNull || a[1].isJsonNull) null else a[0].asLong to a[1].asInt
      }

  /** hrvReadings di /hrv-service/hrv/<data> -> (ms GMT, ms rMSSD). */
  fun lettureHrv(hrv: JsonObject?): List<Pair<Long, Double>> =
      (hrv?.arr("hrvReadings") ?: return emptyList()).mapNotNull { e ->
        val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val v = o.num("hrvValue")?.takeIf { it > 0 } ?: return@mapNotNull null
        val t = runCatching { LocalDateTime.parse(o.get("readingTimeGMT").asString).toInstant(ZoneOffset.UTC).toEpochMilli() }
            .getOrNull() ?: return@mapNotNull null
        t to v
      }.sortedBy { it.first }

  /**
   * Compone la notte. null se Garmin non ha (ancora) il sonno della notte o manca la FC.
   * fcRiposo/fcMinima: dal riepilogo del giorno quando manca la serie (storico iniziale).
   */
  fun componi(
      sonno: JsonObject,
      hrv: JsonObject?,
      fc: List<Pair<Long, Int>>,
      fcRiposo: Double? = null,
      fcMinima: Double? = null,
  ): Notte? {
    val inizio = sonno.num("sleepStartTimestampGMT")?.toLong() ?: return null
    val fine = sonno.num("sleepEndTimestampGMT")?.toLong() ?: return null
    val sonnoS = sonno.num("sleepTimeSeconds")?.toInt() ?: return null
    if (fine <= inizio || sonnoS <= 0) return null

    val letture = lettureHrv(hrv).filter { it.first >= inizio - FINESTRA_MS && it.first <= fine }
    val fcNotte = fc.filter { it.first in inizio..fine }
    val hrAvg = sonno.num("avgHeartRate")?.takeIf { it > 0 }
        ?: fcNotte.takeIf { it.isNotEmpty() }?.map { it.second.toDouble() }?.average()
        ?: return null

    // Finestre di 5' dall'inizio del sonno: FC media del tratto, rMSSD della lettura che vi cade
    val n = ((fine - inizio + FINESTRA_MS - 1) / FINESTRA_MS).toInt()
    val finestre =
        (0 until n).mapNotNull { i ->
          val da = inizio + i * FINESTRA_MS
          val a = da + FINESTRA_MS
          val hr = fcNotte.filter { it.first >= da && it.first < a }.map { it.second.toDouble() }
          val rm = letture.firstOrNull { it.first >= da && it.first < a }?.second
          if (hr.isEmpty() && rm == null) null
          else WindowMetrics(da, if (hr.isEmpty()) Double.NaN else hr.average(), rm, null, null, if (rm != null) 100.0 else 0.0)
        }
    val mediePerFinestra = finestre.map { it.hr }.filter { !it.isNaN() }
    val restingHr = mediePerFinestra.minOrNull() ?: fcRiposo ?: hrAvg
    val hrMin = fcNotte.minOfOrNull { it.second }?.toDouble() ?: fcMinima ?: restingHr

    val rmssd = hrv?.obj("hrvSummary")?.num("lastNightAvg")?.takeIf { it > 0 }
        ?: letture.takeIf { it.isNotEmpty() }?.map { it.second }?.average()
    val meta = (inizio + fine) / 2
    val tarde = letture.filter { it.first >= meta }.map { it.second }
    val qualita = if (letture.isEmpty()) null else minOf(100.0, 100.0 * letture.size * FINESTRA_MS / (fine - inizio))

    val min = { k: String -> ((sonno.num(k) ?: 0.0) / 60).toInt() }
    val stages =
        SleepStages(
            hypnoStartMs = inizio, hypnogram = "", sleepOnsetMs = inizio, sleepEndMs = fine,
            tstMin = sonnoS / 60, deepMin = min("deepSleepSeconds"), lightMin = min("lightSleepSeconds"),
            remMin = min("remSleepSeconds"), wakeMin = min("awakeSleepSeconds"), mode = MODO_FASI)
    val summary =
        NightSummary(
            sessionId = 0, startMs = inizio, endMs = fine, beats = 0, gaps = 0,
            pctGood = 0.0, pctCorrected = 0.0, pctDropped = 0.0,
            hrMin = hrMin, restingHr = restingHr, hrAvg = hrAvg,
            rmssd = rmssd, sdnn = null, pnn50 = null,
            windowsOk = letture.size, windowsTotal = n, qualityPct = qualita,
            rmssdLate = if (tarde.size >= 3) tarde.average() else null,
            sorgente = NightSummary.SORGENTE_GARMIN)
    // le finestre senza FC prendono la media della notte (il grafico non accetta buchi NaN)
    return Notte(summary, finestre.map { if (it.hr.isNaN()) it.copy(hr = hrAvg) else it }, stages)
  }

  /** Salva (o aggiorna, stessa ora d'inizio) la notte nel database. Ritorna l'id della sessione. */
  fun salva(context: Context, notte: Notte): Long {
    val db = SleepDb.get(context)
    val s = notte.summary
    val id = db.sessioneDa(NightSummary.SOURCE_GARMIN, s.startMs)
        ?: db.createSession("Garmin Connect", "garmin", s.startMs, NightSummary.SOURCE_GARMIN)
    db.closeSession(id, s.endMs)
    db.saveNight(
        NightResult(s.copy(sessionId = id), notte.windows, CleanBeats(DoubleArray(0), DoubleArray(0), IntArray(0))),
        notte.stages, System.currentTimeMillis())
    return id
  }

  /** Esito della lettura della notte di un giorno. */
  sealed interface Esito {
    data class Salvata(val sessionId: Long, val notte: Notte) : Esito

    /** Garmin non ha ancora la notte (orologio non sincronizzato o non indossato). */
    data object NonAncora : Esito

    data object ChiaveNonValida : Esito

    data object TroppeRichieste : Esito

    data class Errore(val messaggio: String) : Esito
  }

  private fun problema(r: LetturaGarmin): Esito? =
      when (r) {
        LetturaGarmin.ChiaveNonValida -> Esito.ChiaveNonValida
        LetturaGarmin.TroppeRichieste -> Esito.TroppeRichieste
        is LetturaGarmin.Errore -> Esito.Errore(r.messaggio)
        else -> null
      }

  /**
   * Notte con risveglio il [giorno]: 1 chiamata finche' Garmin non ha il sonno, poi 3 (HRV e FC
   * di due giorni, perche' il sonno inizia prima di mezzanotte). [aspettaHrv]: senza HRV non si
   * salva ancora (Garmin a volte la calcola qualche minuto dopo il sonno).
   */
  fun scarica(context: Context, giorno: LocalDate, aspettaHrv: Boolean): Esito {
    val rs = GarminClient.leggi(context, "/sleep-service/sleep/dailySleep?date=$giorno")
    problema(rs)?.let { return it }
    val sonno = (rs as? LetturaGarmin.Ok)?.json?.takeIf { it.isJsonObject }?.asJsonObject ?: return Esito.NonAncora
    if ((sonno.num("sleepTimeSeconds") ?: 0.0) <= 0.0) return Esito.NonAncora
    val rh = GarminClient.leggi(context, "/hrv-service/hrv/$giorno")
    problema(rh)?.let { return it }
    val hrv = (rh as? LetturaGarmin.Ok)?.json?.takeIf { it.isJsonObject }?.asJsonObject
    if (hrv == null && aspettaHrv) return Esito.NonAncora
    val fc = mutableListOf<Pair<Long, Int>>()
    var fcRiposo: Double? = null
    for (g in listOf(giorno.minusDays(1), giorno)) {
      val r = GarminClient.leggi(context, "/wellness-service/wellness/dailyHeartRate?date=$g")
      problema(r)?.let { return it }
      val j = (r as? LetturaGarmin.Ok)?.json
      fc += serieFc(j)
      if (g == giorno) fcRiposo = j?.takeIf { it.isJsonObject }?.asJsonObject?.num("restingHeartRate")
    }
    val notte = componi(sonno, hrv, fc, fcRiposo) ?: return Esito.NonAncora
    return Esito.Salvata(salva(context, notte), notte)
  }
}
