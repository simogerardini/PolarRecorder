package com.wboelens.polarrecorder.biosleep

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Metriche di una finestra di 5 minuti. I valori HRV sono null se il segnale e' di bassa qualita'. */
data class WindowMetrics(
    val startMs: Long,
    val hr: Double,
    val rmssd: Double?,
    val sdnn: Double?,
    val pnn50: Double?,
    val quality: Double,
)

/** Riepilogo di una notte: stessi numeri che stampa analizza_notte.py. */
data class NightSummary(
    val sessionId: Long,
    val startMs: Long,
    val endMs: Long,
    val beats: Int,
    val gaps: Int,
    val pctGood: Double,
    val pctCorrected: Double,
    val pctDropped: Double,
    val hrMin: Double,
    val restingHr: Double,
    val hrAvg: Double,
    val rmssd: Double?,
    val sdnn: Double?,
    val pnn50: Double?,
    val windowsOk: Int,
    val windowsTotal: Int,
    /**
     * Affidabilita' della notte (0-100): percentuale della durata della registrazione coperta
     * da battiti validi. Scende sia con gli artefatti sia con le disconnessioni (un'ora senza
     * dati su 8 = circa 87%). Inviata a Intervals.icu come BioSleepQuality.
     */
    val qualityPct: Double? = null,
)

/** Battiti dopo la pulizia: tempo di fine (ms), intervallo NN (NaN = scartato), esito. */
class CleanBeats(val t: DoubleArray, val nn: DoubleArray, val flags: IntArray)

data class NightResult(
    val summary: NightSummary,
    val windows: List<WindowMetrics>,
    val beats: CleanBeats,
)

/**
 * Porting in Kotlin di analizza_notte.py. Nessuna dipendenza Android: si puo' testare sul PC.
 *
 * Input: una riga per battito, nell'ordine di arrivo. phoneMs = orario di ricezione del pacchetto
 * che conteneva quel battito (piu' battiti dello stesso pacchetto hanno lo stesso phoneMs).
 * Un valore rrMs NEGATIVO indica un battito segnalato come non valido dal sensore (es. fascia
 * ottica in movimento): la sua durata conta per la linea del tempo, ma non entra nell'HRV.
 */
object NightAnalyzer {
  private const val GAP_TOLERANCE_MS = 3000.0
  private const val DRIFT_GAIN = 0.1
  private const val ECTOPIC_TH = 0.20
  private const val MEDIAN_HALF_WIN = 5 // finestra 11 battiti centrata
  private const val MEDIAN_MIN_VALID = 5
  private const val WINDOW_MS = 300_000.0
  private const val MIN_QUALITY = 0.95
  private const val MIN_BEATS_NIGHT = 500

  private const val OK = 0
  private const val CORRECTED = 1
  private const val DROPPED = 2

  fun analyze(sessionId: Long, phoneMs: LongArray, rrMs: IntArray): NightResult? {
    require(phoneMs.size == rrMs.size)
    val (ts, rr, gaps) = rebuildTimeline(phoneMs, rrMs)
    val validBeats = rr.count { !it.isNaN() }
    if (validBeats < MIN_BEATS_NIGHT) return null

    val (t, nn, flags) = correctRr(ts, rr)
    val windows = windowMetrics(t, nn, flags)
    if (windows.isEmpty()) return null
    val valid = windows.filter { it.rmssd != null }

    val instHr = nn.filter { !it.isNaN() }.map { 60000.0 / it }.sorted()
    val summary =
        NightSummary(
            sessionId = sessionId,
            startMs = ts.first().toLong(),
            endMs = ts.last().toLong(),
            beats = validBeats,
            gaps = gaps,
            pctGood = 100.0 * flags.count { it == OK } / flags.size,
            pctCorrected = 100.0 * flags.count { it == CORRECTED } / flags.size,
            pctDropped = 100.0 * flags.count { it == DROPPED } / flags.size,
            hrMin = percentile(instHr, 1.0),
            restingHr = windows.minOf { it.hr },
            hrAvg = windows.map { it.hr }.average(),
            rmssd = valid.mapNotNull { it.rmssd }.averageOrNull(),
            sdnn = valid.mapNotNull { it.sdnn }.averageOrNull(),
            pnn50 = valid.mapNotNull { it.pnn50 }.averageOrNull(),
            windowsOk = valid.size,
            windowsTotal = windows.size,
            qualityPct = coverage(nn, flags, ts.first(), ts.last()),
        )
    return NightResult(summary, windows, CleanBeats(t, nn, flags))
  }

  // --- 1. Linea del tempo dei battiti -------------------------------------------------------
  private fun rebuildTimeline(
      phoneMs: LongArray,
      rrMs: IntArray,
  ): Triple<DoubleArray, DoubleArray, Int> {
    val t = ArrayList<Double>(rrMs.size + 16)
    val r = ArrayList<Double>(rrMs.size + 16)
    var gaps = 0
    var last: Double? = null
    var i = 0
    while (i < rrMs.size) {
      // un "pacchetto" = righe consecutive con lo stesso orario di ricezione
      var j = i
      var sum = 0.0
      while (j < rrMs.size && phoneMs[j] == phoneMs[i]) {
        sum += abs(rrMs[j])
        j++
      }
      val rx = phoneMs[i].toDouble()
      val err = last?.let { rx - (it + sum) }
      if (err == null || abs(err) > GAP_TOLERANCE_MS) {
        if (last != null) {
          gaps++
          t.add(last)
          r.add(Double.NaN)
        }
        last = rx - sum
      } else {
        last = last!! + DRIFT_GAIN * err
      }
      for (k in i until j) {
        last = last!! + abs(rrMs[k])
        t.add(last)
        r.add(if (rrMs[k] > 0) rrMs[k].toDouble() else Double.NaN)
      }
      i = j
    }
    return Triple(t.toDoubleArray(), r.toDoubleArray(), gaps)
  }

  // --- 2. Pulizia artefatti ----------------------------------------------------------------
  private fun correctRr(
      ts: DoubleArray,
      rrIn: DoubleArray,
  ): Triple<DoubleArray, DoubleArray, IntArray> {
    val n = rrIn.size
    // Pre-filtro largo: un battito mancato (es. 2 x 1300 = 2600 ms) deve arrivare alla
    // classificazione per essere diviso, non essere scartato subito.
    val s = DoubleArray(n) { if (rrIn[it] > 150 && rrIn[it] < 4000) rrIn[it] else Double.NaN }
    val med = DoubleArray(n) { centeredMedian(s, it) }
    val tOut = ArrayList<Double>(n)
    val nnOut = ArrayList<Double>(n)
    val fOut = ArrayList<Int>(n)
    fun push(t: Double, v: Double, f: Int) {
      tOut.add(t); nnOut.add(v); fOut.add(f)
    }
    val th = ECTOPIC_TH
    var i = 0
    while (i < n) {
      val r = s[i]
      val m = med[i]
      val nx = if (i + 1 < n) s[i + 1] else Double.NaN
      if (r.isNaN() || m.isNaN()) {
        push(ts[i], Double.NaN, DROPPED); i++; continue
      }
      val d = (r - m) / m
      if (abs(d) <= th && r in 300.0..2000.0) {
        push(ts[i], r, OK); i++; continue
      }
      if (d < -th && !nx.isNaN()) {
        if (abs(r + nx - m) < th * m) { // battito extra -> fondi
          push(ts[i + 1], r + nx, CORRECTED); i += 2; continue
        }
        if (nx > m * (1 + th) && abs(r + nx - 2 * m) < th * m) { // ectopico corto-lungo
          val h = (r + nx) / 2
          push(ts[i + 1] - h, h, CORRECTED)
          push(ts[i + 1], h, CORRECTED)
          i += 2; continue
        }
      }
      val k = (r / m).roundToInt()
      if (k >= 2 && abs(r - k * m) < th * m) { // battito mancato -> dividi
        for (jj in 0 until k) push(ts[i] - r + (jj + 1) * r / k, r / k, CORRECTED)
        i++; continue
      }
      push(ts[i], Double.NaN, DROPPED); i++
    }
    return Triple(tOut.toDoubleArray(), nnOut.toDoubleArray(), fOut.toIntArray())
  }

  private fun centeredMedian(s: DoubleArray, i: Int): Double {
    val from = maxOf(0, i - MEDIAN_HALF_WIN)
    val to = minOf(s.size - 1, i + MEDIAN_HALF_WIN)
    val v = (from..to).map { s[it] }.filter { !it.isNaN() }.sorted()
    if (v.size < MEDIAN_MIN_VALID) return Double.NaN
    return if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / 2
  }

  // --- 3. Metriche su finestre da 5 minuti ------------------------------------------------
  private fun windowMetrics(t: DoubleArray, nn: DoubleArray, f: IntArray): List<WindowMetrics> {
    val out = ArrayList<WindowMetrics>()
    if (t.isEmpty()) return out
    var t0 = t.first()
    val end = t.last()
    while (t0 + WINDOW_MS <= end) {
      val idx = t.indices.filter { t[it] >= t0 && t[it] < t0 + WINDOW_MS }
      val w = idx.map { nn[it] }
      val validVals = w.filter { !it.isNaN() }
      if (validVals.size > 50) {
        val quality = idx.count { f[it] == OK }.toDouble() / idx.size
        val diffs = (1 until w.size).map { w[it] - w[it - 1] }.filter { !it.isNaN() }
        val good = quality >= MIN_QUALITY && diffs.size > 30
        out.add(
            WindowMetrics(
                startMs = t0.toLong(),
                hr = 60000.0 / validVals.average(),
                rmssd = if (good) sqrt(diffs.map { it * it }.average()) else null,
                sdnn = if (good) sampleStd(validVals) else null,
                pnn50 = if (good) 100.0 * diffs.count { abs(it) > 50 } / diffs.size else null,
                quality = quality,
            ))
      }
      t0 += WINDOW_MS
    }
    return out
  }

  /** Somma delle durate dei battiti buoni / durata della registrazione, in %. */
  private fun coverage(nn: DoubleArray, flags: IntArray, start: Double, end: Double): Double {
    val span = end - start
    if (span <= 0) return 0.0
    var good = 0.0
    for (i in nn.indices) if (flags[i] == OK && !nn[i].isNaN()) good += nn[i]
    return (100.0 * good / span).coerceIn(0.0, 100.0)
  }

  // --- utilita' ---------------------------------------------------------------------------
  private fun sampleStd(v: List<Double>): Double {
    val m = v.average()
    return sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
  }

  /** Percentile con interpolazione lineare (come numpy.percentile). v deve essere ordinato. */
  private fun percentile(v: List<Double>, p: Double): Double {
    val pos = (v.size - 1) * p / 100.0
    val lo = pos.toInt()
    val hi = minOf(lo + 1, v.size - 1)
    return v[lo] + (v[hi] - v[lo]) * (pos - lo)
  }

  private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
}
