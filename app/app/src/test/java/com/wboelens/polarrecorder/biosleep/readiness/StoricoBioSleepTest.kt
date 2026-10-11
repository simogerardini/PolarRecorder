package com.wboelens.polarrecorder.biosleep.readiness

import com.google.gson.JsonObject
import com.wboelens.polarrecorder.biosleep.intervals.CampiWellness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Storico dei vecchi codici: stesso ripiego campo per campo del cervello 2026.10.11-fonte-noctalix.
 * I vecchi codici si costruiscono con CampiStorici.vecchio: nei sorgenti non compaiono mai scritti
 * (CampiWellnessTest.nessunVecchioCodiceNeiSorgenti).
 */
class StoricoBioSleepTest {
  private val v = CampiStorici::vecchio

  private fun riga(id: String, vararg campi: Pair<String, Double>) =
      PyJson.wellnessBio(JsonObject().apply {
        addProperty("id", id)
        for ((k, x) in campi) addProperty(k, x)
      })

  @Test
  fun leggeIVecchiCodiciQuandoMancanoINuovi() {
    val w = riga("2026-09-20", v(CampiWellness.F_RMSSD) to 55.1, v(CampiWellness.F_AVG_HR) to 46.0,
        v(CampiWellness.F_QUALITY) to 97.0, v(CampiWellness.F_SLEEP_HOURS) to 7.5)
    assertEquals(55.1, w.rmssd!!.v, 0.0)
    assertEquals(46.0, w.avgHr!!.v, 0.0)
    assertEquals(97.0, w.quality!!.v, 0.0)
    assertEquals(7.5, w.sleepHours!!.v, 0.0)
  }

  @Test
  fun ilNuovoCodiceHaLaPrecedenza() {
    val w = riga("2026-10-11", CampiWellness.F_RMSSD to 58.8, v(CampiWellness.F_RMSSD) to 40.0,
        CampiWellness.F_QUALITY to 99.7)
    assertEquals(58.8, w.rmssd!!.v, 0.0)
    assertEquals(99.7, w.quality!!.v, 0.0)
  }

  @Test
  fun filtroQualitaAncheSuiVecchiCodici() {
    val righe = listOf(
        riga("2026-09-20", v(CampiWellness.F_RMSSD) to 55.0, v(CampiWellness.F_QUALITY) to 60.0),
        riga("2026-09-21", v(CampiWellness.F_RMSSD) to 56.0, v(CampiWellness.F_QUALITY) to 90.0),
        riga("2026-10-11", CampiWellness.F_RMSSD to 58.8, CampiWellness.F_QUALITY to 99.7))
    assertEquals(listOf("2026-09-21", "2026-10-11"), BioSleepSeries.daWellness(righe).map { it.data })
  }

  @Test
  fun iCampiStandardNonContano() {
    val w = riga("2026-10-10", "hrv" to 61.0, "restingHR" to 44.0)
    assertNull(w.rmssd)
    assertEquals(emptyList<GiornoBio>(), BioSleepSeries.daWellness(listOf(w)))
  }

}
