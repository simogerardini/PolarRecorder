package com.wboelens.polarrecorder.biosleep.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegoleAvvioTest {
  private val da = 598 // 21:58
  private val a = 809 // 01:29

  @Test
  fun margineDiMezzoraPrimaDellaFascia() {
    assertTrue(RegoleAvvio.inOrario(580, da, a)) // 21:40, la sera del 12/10
    assertTrue(RegoleAvvio.inOrario(568, da, a)) // 21:28, inizio del margine
    assertFalse(RegoleAvvio.inOrario(567, da, a)) // 21:27
    assertTrue(RegoleAvvio.inOrario(809, da, a)) // 01:29
    assertFalse(RegoleAvvio.inOrario(810, da, a))
  }

  @Test
  fun caricaOppureBatteriaSufficiente() {
    assertTrue(RegoleAvvio.energiaOk(inCarica = true, batteriaPct = 5))
    assertTrue(RegoleAvvio.energiaOk(inCarica = false, batteriaPct = 40))
    assertFalse(RegoleAvvio.energiaOk(inCarica = false, batteriaPct = 39))
    assertFalse(RegoleAvvio.energiaOk(inCarica = false, batteriaPct = null))
  }

  @Test
  fun quandoRicontrollare() {
    assertEquals(28, RegoleAvvio.fraMinuti(540, da, a)) // 21:00 -> alle 21:28
    assertEquals(10, RegoleAvvio.fraMinuti(600, da, a)) // dentro la fascia senza energia
    assertEquals(null, RegoleAvvio.fraMinuti(820, da, a)) // fascia finita
  }
}
