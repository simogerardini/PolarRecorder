package com.wboelens.polarrecorder.biosleep.cervello

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DetpTest {
  @Test
  fun profilo() {
    assertEquals("""{"core2":false,"detp":false}""", ProfiloAtleta().json().getAsJsonObject("detp").toString())
    // DETP senza sensore: resta spento
    assertEquals(false, ProfiloAtleta(detp = Detp(core2 = false, detp = true)).json().getAsJsonObject("detp")!!.get("detp")!!.asBoolean)
    assertEquals(true, ProfiloAtleta(detp = Detp(core2 = true, detp = true)).json().getAsJsonObject("detp")!!.get("detp")!!.asBoolean)
  }

  @Test
  fun stryd() {
    assertEquals(false, ProfiloAtleta().json().get("stryd")!!.asBoolean)
    assertEquals(true, ProfiloAtleta(stryd = true).json().get("stryd")!!.asBoolean)
  }

  @Test
  fun sweatComeIlCervello() {
    // 1 kg perso + 0,5 L bevuti in 60 minuti = 1,5 L/h
    val d = DatiSweat(70.0, 69.0, 1.0, 0.5, 0.0, 60.0, null)
    assertEquals(1.5, d.litriOra, 1e-9)
    assertNull(d.problema())
    assertTrue(d.copy(minuti = 20.0).problema()!!.contains("30 minuti"))
    assertTrue(d.copy(p2 = 70.5).problema()!!.contains("non plausibile"))
    assertTrue(d.copy(sodioMgL = 50.0).problema() != null)
    assertEquals(69.4, SweatTest.numero("69,4"))
  }

  @Test
  fun cardDelloSweatTest() {
    val t = SweatTest.data(listOf("DETP: heat block il 2026-10-08", "DETP: sweat test il 2026-10-10", "altro"))
    assertEquals(LocalDate.of(2026, 10, 10), t)
    assertTrue(!SweatTest.daChiedere(t, null, LocalDate.of(2026, 10, 9)), "prima del test no")
    assertTrue(SweatTest.daChiedere(t, null, LocalDate.of(2026, 10, 10)))
    assertTrue(!SweatTest.daChiedere(t, t, LocalDate.of(2026, 10, 11)), "dati gia' salvati")
    assertTrue(!SweatTest.daChiedere(t, null, LocalDate.of(2026, 10, 30)), "oltre 14 giorni")
  }
}
