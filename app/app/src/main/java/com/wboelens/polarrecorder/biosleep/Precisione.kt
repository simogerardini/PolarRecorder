package com.wboelens.polarrecorder.biosleep

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Tre misure in piu' per notte (cervello 2026.10.19-precisione1). null = non calcolabile. */
data class Precisione(val rmssdLate: Double?, val hrNadirPct: Double?, val respRate: Double?)

/**
 * Calcolate dopo l'analisi e le fasi, sul solo sonno (dall'addormentamento al risveglio).
 * Nessuna dipendenza Android: testabile sul PC.
 *  - rmssdLate: media dell'rMSSD delle finestre da 5' valide (stesse regole della notte intera:
 *    WindowMetrics.rmssd non null) che stanno interamente nella SECONDA meta' temporale del sonno;
 *  - hrNadirPct: posizione del minimo della FC (media mobile di 5' dei battiti validi, ogni 30 s)
 *    in % della durata del sonno, un decimale;
 *  - respRate: respiri al minuto dall'aritmia sinusale respiratoria: per ogni finestra da 4' con
 *    battiti validi per almeno il 90% del tempo, picco dello spettro dell'RR ricampionato a 4 Hz
 *    tra 0,15 e 0,40 Hz, valido se concentra almeno il 35% della potenza della banda; mediana delle finestre valide, un decimale; null con meno di 2 ore.
 */
object PrecisioneNotte {
  private const val FINESTRA_MS = 300_000L
  private const val PASSO_NADIR_MS = 30_000L
  private const val MIN_BATTITI_NADIR = 50
  private const val RESP_FINESTRA_MS = 240_000L
  private const val RESP_COPERTURA = 0.90
  private const val RESP_MIN_MIN = 120
  private const val FS = 4.0 // Hz
  private const val F_MIN = 0.15
  private const val F_MAX = 0.40
  private const val F_PASSO = 0.005
  /** Quota minima della potenza della banda attorno al picco (+-2 passi): respiro regolare, non rumore. */
  private const val CONCENTRAZIONE_MIN = 0.35
  private const val OK = 0

  fun calcola(r: NightResult, sonnoDa: Long?, sonnoA: Long?, senzaHrv: Boolean): Precisione {
    if (sonnoDa == null || sonnoA == null || sonnoA - sonnoDa < 2 * FINESTRA_MS) return Precisione(null, null, null)
    return Precisione(
        rmssdLate = if (senzaHrv) null else rmssdLate(r.windows, sonnoDa, sonnoA),
        hrNadirPct = nadir(r.beats, sonnoDa, sonnoA),
        respRate = if (senzaHrv) null else respiro(r.beats, sonnoDa, sonnoA),
    )
  }

  fun rmssdLate(w: List<WindowMetrics>, da: Long, a: Long): Double? {
    val meta = da + (a - da) / 2
    val v = w.filter { it.startMs >= meta && it.startMs + FINESTRA_MS <= a }.mapNotNull { it.rmssd }
    return if (v.isEmpty()) null else uno(v.average())
  }

  fun nadir(b: CleanBeats, da: Long, a: Long): Double? {
    val t = b.t
    val hr = DoubleArray(t.size) { if (b.flags[it] == OK && !b.nn[it].isNaN()) 60_000.0 / b.nn[it] else Double.NaN }
    var lo = 0
    var hi = 0
    var somma = 0.0
    var n = 0
    var minimo = Double.MAX_VALUE
    var dove: Long? = null
    var c = da + FINESTRA_MS / 2
    while (c + FINESTRA_MS / 2 <= a) {
      val s = (c - FINESTRA_MS / 2).toDouble()
      val e = (c + FINESTRA_MS / 2).toDouble()
      while (hi < t.size && t[hi] < e) { if (!hr[hi].isNaN()) { somma += hr[hi]; n++ }; hi++ }
      while (lo < hi && t[lo] < s) { if (!hr[lo].isNaN()) { somma -= hr[lo]; n-- }; lo++ }
      if (n >= MIN_BATTITI_NADIR) {
        val m = somma / n
        if (m < minimo) { minimo = m; dove = c }
      }
      c += PASSO_NADIR_MS
    }
    return dove?.let { uno(100.0 * (it - da) / (a - da)) }
  }

  fun respiro(b: CleanBeats, da: Long, a: Long): Double? {
    val stime = ArrayList<Double>()
    var s = da
    while (s + RESP_FINESTRA_MS <= a) {
      picco(b, s, s + RESP_FINESTRA_MS)?.let { stime += it * 60 }
      s += RESP_FINESTRA_MS
    }
    if (stime.size * RESP_FINESTRA_MS / 60_000 < RESP_MIN_MIN) return null
    stime.sort()
    val m = if (stime.size % 2 == 1) stime[stime.size / 2] else (stime[stime.size / 2 - 1] + stime[stime.size / 2]) / 2
    return uno(m)
  }

  /** Frequenza (Hz) del picco respiratorio di una finestra, o null se non affidabile. */
  internal fun picco(b: CleanBeats, s: Long, e: Long): Double? {
    val tt = ArrayList<Double>()
    val vv = ArrayList<Double>()
    var coperto = 0.0
    for (i in b.t.indices) {
      if (b.t[i] < s || b.t[i] >= e) continue
      if (b.flags[i] == OK && !b.nn[i].isNaN()) { tt += b.t[i]; vv += b.nn[i]; coperto += b.nn[i] }
    }
    if (coperto < RESP_COPERTURA * (e - s) || tt.size < 20) return null
    // Tacogramma ricampionato a 4 Hz (interpolazione lineare), senza media e tendenza lineare
    val n = ((tt.last() - tt.first()) / 1000 * FS).toInt()
    if (n < 64) return null
    val x = DoubleArray(n)
    var j = 0
    for (k in 0 until n) {
      val tk = tt.first() + k * 1000 / FS
      while (j < tt.size - 2 && tt[j + 1] < tk) j++
      val f = ((tk - tt[j]) / (tt[j + 1] - tt[j])).coerceIn(0.0, 1.0)
      x[k] = vv[j] + f * (vv[j + 1] - vv[j])
    }
    detrend(x)
    for (k in 0 until n) x[k] *= 0.5 - 0.5 * cos(2 * PI * k / (n - 1)) // Hann
    val freq = ArrayList<Double>()
    val pot = ArrayList<Double>()
    var f = F_MIN
    while (f <= F_MAX + 1e-9) {
      var re = 0.0
      var im = 0.0
      val w = 2 * PI * f / FS
      for (k in 0 until n) { re += x[k] * cos(w * k); im -= x[k] * sin(w * k) }
      freq += f
      pot += re * re + im * im
      f += F_PASSO
    }
    val i = pot.indices.maxByOrNull { pot[it] } ?: return null
    if (i == 0 || i == pot.size - 1) return null // picco al bordo della banda: non e' respiro
    val totale = pot.sum()
    val vicino = (maxOf(0, i - 2)..minOf(pot.size - 1, i + 2)).sumOf { pot[it] }
    if (totale <= 0 || vicino / totale < CONCENTRAZIONE_MIN) return null
    return freq[i]
  }

  private fun detrend(x: DoubleArray) {
    val n = x.size
    val mt = (n - 1) / 2.0
    val mx = x.average()
    var num = 0.0
    var den = 0.0
    for (k in 0 until n) { num += (k - mt) * (x[k] - mx); den += (k - mt) * (k - mt) }
    val p = if (den > 0) num / den else 0.0
    for (k in 0 until n) x[k] -= mx + p * (k - mt)
  }

  private fun uno(v: Double) = (v * 10).roundToInt() / 10.0
}
