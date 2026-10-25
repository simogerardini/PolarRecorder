package com.wboelens.polarrecorder.biosleep.cache

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wboelens.polarrecorder.biosleep.intervals.CampiWellness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NottiLocaliTest {
  private fun o(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

  @Test
  fun ilTelefonoVinceSulServer() {
    val server = listOf(o("""{"id": "2026-10-09", "${CampiWellness.F_RMSSD}": 50.5, "ctl": 45.8}"""))
    val locali = listOf(o("""{"id": "2026-10-09", "${CampiWellness.F_RMSSD}": 51.0}"""))
    val r = NottiLocali.unisci(server, locali)
    assertEquals(1, r.size)
    assertEquals(51.0, r[0].get(CampiWellness.F_RMSSD)!!.asDouble)
    assertEquals(45.8, r[0].get("ctl")!!.asDouble)
  }

  /** Come cs.unisci_notti_locali: fascia inviata sul server, Garmin sul telefono (sorgente scelta Garmin). */
  @Test
  fun stessoGiornoFasciaInviataEGarminLocale() {
    val server =
        listOf(o("""{"id": "2026-10-25", "${CampiWellness.F_RMSSD}": 50.5, "${CampiWellness.F_RMSSD_LATE}": 48.0,
                     "${CampiWellness.F_SOURCE}": 1, "ctl": 45.8}"""))
    val locali = listOf(o("""{"id": "2026-10-25", "${CampiWellness.F_RMSSD}": 34.0, "${CampiWellness.F_SOURCE}": 2}"""))
    val r = NottiLocali.unisci(server, locali).single()
    assertEquals(34.0, r.get(CampiWellness.F_RMSSD)!!.asDouble)
    assertEquals(2, r.get(CampiWellness.F_SOURCE)!!.asInt)
    assertEquals(null, r.get(CampiWellness.F_RMSSD_LATE)) // tolto: non e' della notte Garmin
    assertEquals(45.8, r.get("ctl")!!.asDouble)
  }

  @Test
  fun giornoSenzaNotteSulServerPrendeILocaliETieneGliAltriCampi() {
    val server = listOf(o("""{"id": "2026-10-09", "ctl": 45.8}"""), o("""{"id": "2026-10-07", "${CampiWellness.F_RMSSD}": 49.0}"""))
    val locali =
        listOf(
            o("""{"id": "2026-10-09", "${CampiWellness.F_RMSSD}": 51.0, "${CampiWellness.F_AVG_HR}": 48.2}"""),
            o("""{"id": "2026-10-08", "${CampiWellness.F_RMSSD}": 47.0}"""))
    val r = NottiLocali.unisci(server, locali)
    assertEquals(listOf("2026-10-07", "2026-10-08", "2026-10-09"), r.map { it.get("id")!!.asString })
    assertEquals(51.0, r[2].get(CampiWellness.F_RMSSD)!!.asDouble)
    assertEquals(45.8, r[2].get("ctl")!!.asDouble)
    assertEquals(48.2, r[2].get(CampiWellness.F_AVG_HR)!!.asDouble)
  }

  @Test
  fun senzaIntervalsSoloLocali() {
    val r = NottiLocali.unisci(emptyList(), listOf(o("""{"id": "2026-10-09", "${CampiWellness.F_RMSSD}": 51.0}""")))
    assertEquals(1, r.size)
  }
}
