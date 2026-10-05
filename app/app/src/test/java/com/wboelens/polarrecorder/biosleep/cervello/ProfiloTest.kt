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
  fun settimanaTipoSempreMandata() {
    // senza modifiche: i valori predefiniti del cervello
    val o = ProfiloAtleta().json().getAsJsonObject("settimana")
    assertEquals("sab", o.get("lungo_bici")!!.asString)
    assertEquals("dom", o.get("lungo_corsa")!!.asString)
    assertTrue(o.get("riposo")!!.isJsonNull, "riposo null = nessuno")
    val s = o.getAsJsonObject("sedute")
    assertEquals(listOf(2, 2, 3, 2), listOf("nuoto", "bici", "corsa", "forza").map { s.get(it)!!.asInt })
    // senza profilo nel config non si manda niente
    assertTrue(!JsonParser.parseString(ConfigCervello(Credenziali.Chiave("k", "0"), "/c").json()).asJsonObject.has("profilo"))
  }

  @Test
  fun settimanaTipoValidaComeIlCervello() {
    assertTrue(SettimanaTipo().errori().isEmpty())
    assertTrue(SettimanaTipo(lungoBici = "dom").errori().isNotEmpty(), "lunghi nello stesso giorno")
    assertTrue(SettimanaTipo(riposo = "sab").errori().isNotEmpty(), "riposo su un lungo")
    assertTrue(SettimanaTipo(riposo = "lun").errori().isEmpty())
    assertTrue(SettimanaTipo(sedute = SettimanaTipo.PREDEFINITE + ("forza" to 3)).errori().isNotEmpty(), "forza 0-2")
    assertTrue(SettimanaTipo(sedute = SettimanaTipo.PREDEFINITE + ("nuoto" to 0)).errori().isNotEmpty(), "nuoto 1-4")
    val o = ProfiloAtleta(settimana = SettimanaTipo(riposo = "lun")).json().getAsJsonObject("settimana")
    assertEquals("lun", o.get("riposo")!!.asString)
  }

  @Test
  fun forzaSoloQuandoServe() {
    val senza = JsonParser.parseString(ConfigCervello(Credenziali.Chiave("k", "0"), "/c").json()).asJsonObject
    assertTrue(!senza.has("forza"))
    val con = JsonParser.parseString(ConfigCervello(Credenziali.Token("t"), "/c", modo = "settimanale", forza = true).json()).asJsonObject
    assertEquals(true, con.get("forza")!!.asBoolean)
    assertEquals("settimanale", con.get("modo")!!.asString)
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
