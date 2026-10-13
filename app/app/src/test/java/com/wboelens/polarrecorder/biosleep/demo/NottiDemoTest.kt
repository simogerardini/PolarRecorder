package com.wboelens.polarrecorder.biosleep.demo

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NottiDemoTest {
  private val oggi = LocalDate.of(2026, 10, 13)
  private val zona = ZoneId.of("Europe/Rome")
  private val notti = NottiDemo.genera(oggi, zona)

  @Test
  fun quattordiciNottiFinoAStamattina() {
    assertEquals(14, notti.size)
    assertEquals(oggi, notti.last().data)
    assertEquals(oggi.minusDays(13), notti.first().data)
  }

  @Test
  fun sempreUgualiAParitaDiData() {
    assertEquals(notti, NottiDemo.genera(oggi, zona))
  }

  @Test
  fun ipnogrammaCoerenteConDurataEFasi() {
    for (n in notti) {
      assertEquals(((n.fineMs - n.inizioMs) / 30_000).toInt(), n.ipnogramma.length)
      assertTrue(n.ipnogramma.all { it in "WLDR" })
      assertEquals(n.leggeroMin + n.profondoMin + n.remMin, n.sonnoMin)
      assertTrue("sonno ${n.sonnoMin}", n.sonnoMin in 330..500)
      assertTrue("profondo ${n.profondoMin}", n.profondoMin in 40..160)
      assertTrue("rem ${n.remMin}", n.remMin in 50..160)
      assertTrue(n.addormentamentoMs > n.inizioMs && n.risveglioMs <= n.fineMs)
    }
  }

  @Test
  fun valoriPlausibiliEFinestreOgniCinqueMinuti() {
    for (n in notti) {
      assertTrue(n.fc5MinBassi in 40.0..55.0 && n.fcMin < n.fc5MinBassi && n.fcMedia > n.fc5MinBassi)
      assertTrue(n.rmssd in 30.0..70.0 && n.qualitaPct in 80.0..100.0)
      assertTrue(n.finestre.zipWithNext().all { (a, b) -> b.inizioMs - a.inizioMs == 300_000L })
      assertTrue(n.finestre.size >= 80)
    }
    // almeno una notte "storta" per mostrare le differenze
    assertTrue(notti.any { it.qualitaPct < 90 })
  }
}
