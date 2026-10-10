package com.wboelens.polarrecorder.biosleep.cache

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wboelens.polarrecorder.biosleep.intervals.CampiWellness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NottiLocaliTest {
  private fun o(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

  @Test
  fun ilServerVinceSeHaLaNotte() {
    val server = listOf(o("""{"id": "2026-10-09", "${CampiWellness.F_RMSSD}": 50.5, "ctl": 45.8}"""))
    val locali = listOf(o("""{"id": "2026-10-09", "${CampiWellness.F_RMSSD}": 51.0}"""))
    val r = NottiLocali.unisci(server, locali)
    assertEquals(1, r.size)
    assertEquals(50.5, r[0].get(CampiWellness.F_RMSSD)!!.asDouble)
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
