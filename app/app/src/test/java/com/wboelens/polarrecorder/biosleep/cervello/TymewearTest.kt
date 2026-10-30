package com.wboelens.polarrecorder.biosleep.cervello

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tymewear (cervello 2026.10.30-tymewear): configJson, passo, precedenza manuale/auto. */
class TymewearTest {
  private val manuale = TestTymewear("2026-10-01", Tymewear.MANUALE, SogliaVt(149.0, 149.0, null, 60.0), SogliaVt(165.0, 190.0, null, 95.0))

  @Test
  fun configJsonSoloSeAttivo() {
    assertFalse(ProfiloAtleta(tymewear = Tymewear(false, manuale)).json().has("tymewear"))
    val o = ProfiloAtleta(tymewear = Tymewear(true, manuale, TestTymewear("2026-10-02", vt1 = SogliaVt(150.0, passo = 300.0))))
        .json().getAsJsonObject("tymewear")
    assertTrue(o.get("attivo").asBoolean)
    val b = o.getAsJsonObject("bici")
    assertEquals("manuale", b.get("fonte").asString)
    assertEquals(190.0, b.getAsJsonObject("vt2").get("w").asDouble)
    val c = o.getAsJsonObject("corsa").getAsJsonObject("vt1")
    assertEquals(300.0, c.get("passo").asDouble)
    assertFalse(c.has("w"), "corsa senza Stryd: passo al posto dei watt")
    assertTrue(c.get("ve").isJsonNull, "assenti = null")
  }

  @Test
  fun andataERitorno() {
    val t = Tymewear(true, manuale)
    assertEquals(t, Tymewear.da(t.json().toString()))
    assertEquals(Tymewear(), Tymewear.da(null))
  }

  @Test
  fun passo() {
    assertEquals(270.0, Tymewear.passo("4:30"))
    assertNull(Tymewear.passo("4:75"))
    assertNull(Tymewear.passo("430"))
    assertEquals("4:30", Tymewear.passoTxt(270.0))
  }

  @Test
  fun automaticoSoloSePiuRecente() {
    val attuale = Tymewear(true, manuale)
    fun dal(data: String) =
        JsonParser.parseString("""{"bici":{"data":"$data","vt1":{"fc":150,"w":152},"vt2":{"fc":167,"w":195}}}""").asJsonObject
    assertNull(Tymewear.unisci(attuale, null), "campo assente: nessuna azione")
    assertNull(Tymewear.unisci(attuale, dal("2026-10-01")), "stessa data: vince il manuale")
    assertNull(Tymewear.unisci(attuale, dal("2026-09-01")), "piu' vecchio: resta il manuale")
    val nuovo = Tymewear.unisci(attuale, dal("2026-10-20"))!!
    assertEquals(Tymewear.AUTO, nuovo.bici!!.fonte)
    assertEquals(195.0, nuovo.bici!!.vt2.w)
    assertEquals(null, nuovo.corsa)
  }
}
