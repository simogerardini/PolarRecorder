package com.wboelens.polarrecorder.biosleep.garmin

import com.wboelens.polarrecorder.biosleep.SleepStages
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Parte 11: andamento delle fasi di una notte Garmin, stimato da NoctaliX. La Personal API da'
 * solo i totali; qui si distribuiscono quei totali (esatti) su finestre di 5 minuti:
 * - veglia: salti di FC e FC instabile nella finestra;
 * - profondo: FC molto stabile, prima parte della notte (nei dati Garmin il profondo NON ha
 *   l'HRV piu' alta: conta la stabilita' della FC);
 * - REM: verso la fine della notte, mai nei primi 45 minuti (posizione solo indicativa);
 * - leggero: il resto.
 * Tarata su 4 notti Garmin Cirqa confrontate con l'ipnogramma di Garmin (27/10/2026), verificata
 * fuori campione: kappa 0,40; profondo 74%, veglia 58%, REM 27%. Copia di stima2 (pesi finali)
 * del prototipo Python: stesso risultato sul caso di riferimento di GarminIpnogrammaTest.
 */
object GarminIpnogramma {
  private const val FINESTRA_MS = 300_000L
  private const val EPOCHE_PER_FINESTRA = (FINESTRA_MS / SleepStages.EPOCH_MS).toInt()
  private const val NIENTE_REM_MIN = 45.0
  private const val COPERTURA_MINIMA = 0.8

  // pesi (prototipo: dr=-1, dh=0.5, dsd=0.5, dt=2, rt=0.5, k=1, wsd=0.5)
  private const val P_RMSSD = -1.0
  private const val P_FC = 0.5
  private const val P_STABILITA = 0.5
  private const val P_INIZIO = 2.0
  private const val P_FINE_REM = 0.5
  private const val P_INSTABILE_VEGLIA = 0.5

  private fun z(v: DoubleArray): DoubleArray {
    val m = v.average()
    val sd = sqrt(v.sumOf { (it - m) * (it - m) } / v.size).takeIf { it > 0 } ?: 1.0
    return DoubleArray(v.size) { (v[it] - m) / sd }
  }

  private fun liscia(v: DoubleArray): DoubleArray =
      DoubleArray(v.size) { i -> (maxOf(0, i - 1)..minOf(v.size - 1, i + 1)).map { v[it] }.average() }

  private fun pstdev(v: List<Double>): Double {
    val m = v.average()
    return sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
  }

  /** Riempie i buchi con la media dei vicini (+-2), in ordine, come il prototipo; altrimenti 0. */
  private fun riempi(v: Array<Double?>): DoubleArray {
    // 28/10: senza vicini la media della notte, non 0 (uno 0 creava finti salti di FC = finta veglia)
    val media = v.filterNotNull().takeIf { it.isNotEmpty() }?.average() ?: 0.0
    for (i in v.indices) {
      if (v[i] == null) {
        val vic = (maxOf(0, i - 2)..minOf(v.size - 1, i + 2)).mapNotNull { v[it] }
        v[i] = if (vic.isEmpty()) media else vic.average()
      }
    }
    return DoubleArray(v.size) { v[it]!! }
  }

  /**
   * Fasi per finestra di 5 minuti ('D','L','R','W'), con i totali Garmin arrotondati a 5 minuti.
   * Stringa vuota senza FC nella notte.
   */
  fun finestre(
      inizio: Long, fine: Long, fc: List<Pair<Long, Int>>, letture: List<Pair<Long, Double>>,
      profondoS: Double, remS: Double, vegliaS: Double,
  ): String {
    if (fine <= inizio || fc.none { it.first in inizio until fine }) return ""
    val n = ((fine - inizio + FINESTRA_MS - 1) / FINESTRA_MS).toInt()
    val hr = arrayOfNulls<Double>(n)
    val rm = arrayOfNulls<Double>(n)
    val sd = DoubleArray(n)
    for (i in 0 until n) {
      val da = inizio + i * FINESTRA_MS
      val w = fc.filter { it.first >= da && it.first < da + FINESTRA_MS }.map { it.second.toDouble() }
      if (w.isNotEmpty()) hr[i] = w.average()
      sd[i] = if (w.size > 1) pstdev(w) else 0.0
      rm[i] = letture.firstOrNull { it.first >= da && it.first < da + FINESTRA_MS }?.second
    }
    // 28/10: con la FC su meno dell'80% della notte la stima inventerebbe le fasi (prima notte
    // dello storico senza la FC prima di mezzanotte): meglio solo i totali
    if (hr.count { it != null } < COPERTURA_MINIMA * n) return ""
    val h = riempi(hr)
    val r = riempi(rm)
    val zh = z(h)
    val zr = z(r)
    val zsd = z(sd)
    val zs = z(DoubleArray(n) { if (it == 0) 0.0 else abs(h[it] - h[it - 1]) })
    val t = DoubleArray(n) { it.toDouble() / maxOf(1, n - 1) }
    val quote = mapOf('W' to round(vegliaS / 300).toInt(), 'D' to round(profondoS / 300).toInt(), 'R' to round(remS / 300).toInt())
    val fasi = CharArray(n) { 'L' }
    val liberi = (0 until n).toMutableSet()
    fun assegna(fase: Char, punteggio: DoubleArray, ammesso: (Int) -> Boolean = { true }) {
      (0 until n).filter { it in liberi && ammesso(it) }.sortedByDescending { punteggio[it] }
          .take(quote.getValue(fase)).forEach {
            fasi[it] = fase
            liberi.remove(it)
          }
    }
    assegna('W', DoubleArray(n) { zs[it] + P_INSTABILE_VEGLIA * zsd[it] })
    assegna('D', liscia(DoubleArray(n) { P_RMSSD * zr[it] - P_FC * zh[it] - P_STABILITA * zsd[it] + P_INIZIO * (1 - t[it]) }))
    val durataMin = (fine - inizio) / 60_000.0
    assegna('R', liscia(DoubleArray(n) { P_FINE_REM * t[it] })) { t[it] * durataMin > NIENTE_REM_MIN }
    return String(fasi)
  }

  /** Ipnogramma in epoche da 30 s (formato di SleepStages), stringa vuota se non stimabile. */
  fun ipnogramma(
      inizio: Long, fine: Long, fc: List<Pair<Long, Int>>, letture: List<Pair<Long, Double>>,
      profondoS: Double, remS: Double, vegliaS: Double,
  ): String {
    val f = finestre(inizio, fine, fc, letture, profondoS, remS, vegliaS)
    if (f.isEmpty()) return ""
    val epoche = ((fine - inizio) / SleepStages.EPOCH_MS).toInt()
    return String(CharArray(epoche) { f[minOf(f.length - 1, it / EPOCHE_PER_FINESTRA)] })
  }
}
