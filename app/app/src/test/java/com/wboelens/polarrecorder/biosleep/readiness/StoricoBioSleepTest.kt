package com.wboelens.polarrecorder.biosleep.readiness

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Storico BioSleep*: stesso ripiego campo per campo del cervello 2026.10.11-fonte-noctalix. */
class StoricoBioSleepTest {
  private fun riga(json: String) = PyJson.wellnessBio(JsonParser.parseString(json).asJsonObject)

  @Test
  fun leggeIVecchiCodiciQuandoMancanoINuovi() {
    val w = riga("""{"id":"2026-09-20","BioSleepRMSSD":55.1,"BioSleepAvgHR":46.0,"BioSleepQuality":97.0,"BioSleepSleepHours":7.5}""")
    assertEquals(55.1, w.rmssd!!.v, 0.0)
    assertEquals(46.0, w.avgHr!!.v, 0.0)
    assertEquals(97.0, w.quality!!.v, 0.0)
    assertEquals(7.5, w.sleepHours!!.v, 0.0)
  }

  @Test
  fun ilNuovoCodiceHaLaPrecedenza() {
    val w = riga("""{"id":"2026-10-11","NoctalixRMSSD":58.8,"BioSleepRMSSD":40.0,"NoctalixQuality":99.7}""")
    assertEquals(58.8, w.rmssd!!.v, 0.0)
    assertEquals(99.7, w.quality!!.v, 0.0)
  }

  @Test
  fun filtroQualitaAncheSuiVecchiCodici() {
    val righe = listOf(
        riga("""{"id":"2026-09-20","BioSleepRMSSD":55.0,"BioSleepQuality":60.0}"""),
        riga("""{"id":"2026-09-21","BioSleepRMSSD":56.0,"BioSleepQuality":90.0}"""),
        riga("""{"id":"2026-10-11","NoctalixRMSSD":58.8,"NoctalixQuality":99.7}"""))
    assertEquals(listOf("2026-09-21", "2026-10-11"), BioSleepSeries.daWellness(righe).map { it.data })
  }

  @Test
  fun iCampiStandardNonContano() {
    val w = riga("""{"id":"2026-10-10","hrv":61.0,"restingHR":44}""")
    assertNull(w.rmssd)
    assertEquals(emptyList<GiornoBio>(), BioSleepSeries.daWellness(listOf(w)))
  }
}
