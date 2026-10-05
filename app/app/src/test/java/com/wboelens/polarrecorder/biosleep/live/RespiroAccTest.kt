package com.wboelens.polarrecorder.biosleep.live

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Segnale sintetico come quello della H10 sul torace: gravita' (~1 g, inclinata), respiro di
 * pochi mg lungo un asse obliquo, vibrazione del battito, rumore. Respiro vero noto: si misura
 * quanto il fiore lo segue in fase, in ritardo e in ampiezza.
 */
class RespiroAccTest {
  private val fs = 25.0

  private class Esito(val ritardoMs: Long, val correlazione: Double, val aperture: List<Float>, val verso: Double)

  private fun simula(
      secondi: Int,
      ampiezza: (Double) -> Double,
      versoAsse: Double,
      periodoS: Double = 4.5,
  ): Esito {
    val r = RespiroAcc(fs)
    val rnd = Random(7)
    val asse = doubleArrayOf(0.30, 0.25, 0.92).let { a -> val n = sqrt(a.sumOf { it * it }); a.map { versoAsse * it / n } }
    val vero = ArrayList<Double>()
    val out = ArrayList<Float?>()
    var prossimoBattito = 0.0
    for (k in 0 until (secondi * fs).toInt()) {
      val t = k / fs
      val fase = 2 * PI * t / periodoS
      val resp = ampiezza(t) * sin(fase) // > 0 = inspirazione (torace che si espande)
      val bcg = 3.0 * sin(2 * PI * 1.1 * t)
      val x = 120 + asse[0] * resp + bcg + rnd.nextDouble(-1.0, 1.0)
      val y = -980 + asse[1] * resp + rnd.nextDouble(-1.0, 1.0)
      val z = 150 + asse[2] * resp + 0.5 * bcg + rnd.nextDouble(-1.0, 1.0)
      val tMs = (t * 1000).toLong()
      out.add(r.campione(tMs, x, y, z))
      vero.add(sin(fase))
      // battito: il cuore accelera nell'inspirazione con ~1 s di ritardo (aritmia sinusale)
      if (t >= prossimoBattito) {
        val hr = 58 + 4 * sin(2 * PI * (t - 1.0) / periodoS)
        val rr = (60_000 / hr).toInt()
        r.battito(tMs, rr)
        prossimoBattito = t + rr / 1000.0
      }
    }
    // ritardo: spostamento che massimizza la correlazione apertura-respiro vero (ultimi 60 s)
    val inizio = out.size - (60 * fs).toInt()
    var migliore = -1.0
    var ritardo = 0
    for (lag in 0..50) {
      val a = (inizio until out.size).map { out[it]!!.toDouble() }
      val b = (inizio until out.size).map { vero[it - lag] }
      val c = corr(a, b)
      if (c > migliore) {
        migliore = c
        ritardo = lag
      }
    }
    return Esito((ritardo * 1000 / fs).toLong(), migliore, out.filterNotNull(), r.verso)
  }

  private fun corr(a: List<Double>, b: List<Double>): Double {
    val ma = a.average()
    val mb = b.average()
    var sab = 0.0
    var saa = 0.0
    var sbb = 0.0
    for (i in a.indices) {
      sab += (a[i] - ma) * (b[i] - mb)
      saa += (a[i] - ma) * (a[i] - ma)
      sbb += (b[i] - mb) * (b[i] - mb)
    }
    return sab / sqrt(saa * sbb)
  }

  @Test
  fun inFaseEConPocoRitardo() {
    for (verso in listOf(1.0, -1.0)) {
      val e = simula(180, { 12.0 }, verso)
      assertEquals(1.0, e.verso * verso, "verso dell'asse ricavato dal battito (asse $verso)")
      assertTrue(e.correlazione > 0.9, "il fiore segue il respiro: r = ${e.correlazione}")
      assertTrue(e.ritardoMs <= 500, "ritardo del calcolo ${e.ritardoMs} ms")
      println("verso $verso: r = ${e.correlazione}, ritardo ${e.ritardoMs} ms")
    }
  }

  @Test
  fun respiroProfondoApreDiPiu() {
    // 120 s di respiro normale (12 mg), poi 3 respiri profondi (30 mg)
    val e = simula(134, { t -> if (t < 120) 12.0 else 30.0 }, 1.0)
    val n = e.aperture.size
    val normale = e.aperture.subList(n - (40 * fs).toInt(), n - (14 * fs).toInt())
    val profondo = e.aperture.subList(n - (13 * fs).toInt(), n)
    val ampN = normale.max() - normale.min()
    val ampP = profondo.max() - profondo.min()
    println("ampiezza normale $ampN, profonda $ampP")
    assertTrue(ampP > ampN * 1.5, "profondo $ampP contro normale $ampN")
  }
}
