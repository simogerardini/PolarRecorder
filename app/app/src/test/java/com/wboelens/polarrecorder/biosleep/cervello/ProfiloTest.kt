package com.wboelens.polarrecorder.biosleep.cervello

import com.google.gson.JsonParser
import com.wboelens.polarrecorder.biosleep.intervals.Credenziali
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfiloTest {
  @Test
  fun profiloComeDaContratto() {
    val p = ProfiloAtleta(189, 42, 10.5, mapOf("lun" to 90, "ven" to 0, "sab" to 300))
    val c = ConfigCervello(Credenziali.Chiave("k", "0"), "/c", profilo = p)
    val o = JsonParser.parseString(c.json()).asJsonObject.getAsJsonObject("profilo")
    assertEquals(189, o.get("fc_max")!!.asInt)
    assertEquals(42, o.get("fc_riposo")!!.asInt)
    assertEquals(10.5, o.get("tetto_ore")!!.asDouble)
    val d = o.getAsJsonObject("disponibilita")
    assertEquals(90, d.get("lun")!!.asInt)
    assertEquals(0, d.get("ven")!!.asInt, "0 = non disponibile")
    assertNull(d.get("mar"), "giorno assente = nessun limite")
  }

  @Test
  fun profiloVuotoNonSiManda() {
    assertNull(ProfiloAtleta().json())
    val o = JsonParser.parseString(ConfigCervello(Credenziali.Chiave("k", "0"), "/c").json()).asJsonObject
    assertTrue(!o.has("profilo"))
  }

  @Test
  fun suggerimenti() {
    val s = SuggerimentiProfilo.calcola(listOf("2026-09-20" to 186, "2026-10-02" to 191, "2026-09-25" to 255), listOf(44.0, 41.0, 43.0, 42.0, 40.0, 42.0))
    assertEquals(191, s.fcMax, "255 e' un picco impossibile: scartato")
    assertEquals("2026-10-02", s.fcMaxData)
    assertEquals(42, s.fcRiposo)
    assertNull(SuggerimentiProfilo.calcola(emptyList(), listOf(41.0, 42.0)).fcRiposo, "servono almeno 5 notti")
  }
}
