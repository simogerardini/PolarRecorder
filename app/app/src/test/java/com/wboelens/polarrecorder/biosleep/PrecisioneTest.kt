package com.wboelens.polarrecorder.biosleep

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class PrecisioneTest {
  /** Battiti sintetici: FC che segue [fc] (bpm) e respiro a [resp] respiri/min (RSA +-ampiezza). */
  private fun battiti(oreTot: Double, fc: (Double) -> Double, resp: Double, ampiezza: Double = 40.0, seme: Int = 1): CleanBeats {
    val r = Random(seme)
    val t = ArrayList<Double>(); val nn = ArrayList<Double>()
    var ora = 0.0
    val fine = oreTot * 3_600_000
    while (ora < fine) {
      val base = 60_000.0 / fc(ora / fine)
      val v = base + ampiezza * sin(2 * PI * resp / 60.0 * ora / 1000) + r.nextDouble(-4.0, 4.0)
      ora += v; t += ora; nn += v
    }
    return CleanBeats(t.toDoubleArray(), nn.toDoubleArray(), IntArray(t.size))
  }

  private fun notte(b: CleanBeats, rmssdPrima: Double = 40.0, rmssdDopo: Double = 60.0): NightResult {
    val fine = b.t.last()
    val w = (0 until (fine / 300_000).toInt()).map { i ->
      val s = i * 300_000L
      WindowMetrics(s, 55.0, if (s < fine / 2) rmssdPrima else rmssdDopo, 50.0, 20.0, 1.0)
    }
    val s = NightSummary(1, 0, fine.toLong(), b.t.size, 0, 100.0, 0.0, 0.0, 45.0, 48.0, 55.0, 50.0, 50.0, 20.0, w.size, w.size, 95.0)
    return NightResult(s, w, b)
  }

  @Test
  fun respiroANumeroNoto() {
    for (resp in listOf(10.0, 14.0, 18.0)) {
      val b = battiti(3.0, { 55.0 }, resp, seme = resp.toInt())
      val v = PrecisioneNotte.respiro(b, 0, b.t.last().toLong())!!
      assertEquals(resp, v, 0.6, "respiro a $resp/min")
    }
  }

  @Test
  fun respiroVuotoSottoDueOreOSenzaRsa() {
    val corto = battiti(1.5, { 55.0 }, 14.0)
    assertNull(PrecisioneNotte.respiro(corto, 0, corto.t.last().toLong()), "meno di 2 ore")
    val piatto = battiti(3.0, { 55.0 }, 14.0, ampiezza = 0.0)
    assertNull(PrecisioneNotte.respiro(piatto, 0, piatto.t.last().toLong()), "nessun picco respiratorio")
  }

  @Test
  fun nadirNelPuntoNoto() {
    // FC minima al 70% del sonno (curva a V)
    val b = battiti(7.0, { x -> 50.0 + 20.0 * kotlin.math.abs(x - 0.7) }, 14.0)
    val v = PrecisioneNotte.nadir(b, 0, b.t.last().toLong())!!
    assertEquals(70.0, v, 2.0)
    // minimo all'inizio
    val b2 = battiti(7.0, { x -> 48.0 + 10.0 * x }, 14.0)
    assertTrue(PrecisioneNotte.nadir(b2, 0, b2.t.last().toLong())!! < 5.0)
  }

  @Test
  fun rmssdDellaSecondaMetaDelSonno() {
    val b = battiti(7.0, { 55.0 }, 14.0)
    val r = notte(b, rmssdPrima = 40.0, rmssdDopo = 60.0)
    val fine = b.t.last().toLong()
    assertEquals(60.0, PrecisioneNotte.rmssdLate(r.windows, 0, fine))
    // la seconda meta' del SONNO, non della registrazione: con il sonno nella prima parte
    // della registrazione la meta' cade prima e prende anche finestre a 40
    val v = PrecisioneNotte.rmssdLate(r.windows, 0, (fine * 0.8).toLong())!!
    assertTrue(v in 40.0..60.0 && v != 60.0)
  }

  @Test
  fun senzaHrvOSenzaSonnoNienteValori() {
    val b = battiti(7.0, { 55.0 }, 14.0)
    val r = notte(b)
    val p = PrecisioneNotte.calcola(r, 0, b.t.last().toLong(), senzaHrv = true)
    assertNull(p.rmssdLate); assertNull(p.respRate)
    assertTrue(p.hrNadirPct != null, "la FC c'e' anche senza HRV")
    assertEquals(Precisione(null, null, null), PrecisioneNotte.calcola(r, null, null, false))
  }
}
