package com.wboelens.polarrecorder.biosleep.sopravvivenza

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InterruzioniTest {
  private val z = ZoneId.of("Europe/Rome")
  private val t0 = ZonedDateTime.of(2026, 10, 6, 23, 0, 0, 0, z).toInstant().toEpochMilli()
  private val m = 60_000L

  private fun at(h: Int, mi: Int) = ZonedDateTime.of(2026, 10, 7, h, mi, 0, 0, z).toInstant().toEpochMilli()

  @Test
  fun causeDeiBuchi() {
    val buchi = listOf(at(3, 12) to at(3, 30), at(4, 0) to at(4, 10), at(5, 40) to at(5, 45), at(6, 20) to at(6, 21))
    val p =
        generateSequence(t0) { it + 1000 }
            .takeWhile { it < at(7, 0) }
            .filter { x -> buchi.none { x > it.first && x < it.second } }
            .toList()
            .toLongArray()
    val ev =
        listOf(
            EventoNotte(at(3, 25), TipoEvento.SERVIZIO_AVVIATO),
            EventoNotte(at(3, 26), TipoEvento.RIPRESA),
            EventoNotte(at(4, 0), TipoEvento.BT_SPENTO),
            EventoNotte(at(4, 9), TipoEvento.BT_ACCESO),
            EventoNotte(at(5, 41), TipoEvento.FASCIA_SCOLLEGATA),
            EventoNotte(at(5, 45), TipoEvento.FASCIA_COLLEGATA))
    val r = RilevaInterruzioni.trova(p, ev, z)
    assertEquals(3, r.buchi.size) // il buco di 1 minuto non conta
    assertEquals("app chiusa dal sistema alle 03:12", r.buchi[0].causa)
    assertEquals(18, r.buchi[0].minuti)
    assertEquals("Bluetooth spento alle 04:00", r.buchi[1].causa)
    assertTrue(r.buchi[2].causa.startsWith("fascia fuori portata o spenta alle 05:40"))
    assertEquals(33, r.minutiPersi)
  }

  @Test
  fun notteSenzaBuchi() {
    assertNull(RilevaInterruzioni.trova(LongArray(1000) { t0 + it * 1000L }, emptyList(), z).testo())
  }

  @Test
  fun ripresa() {
    val now = at(3, 40)
    assertTrue(RipresaNotte.daRiprendere(now, t0, now - 10 * m, mattino = false))
    assertFalse(RipresaNotte.daRiprendere(now, t0, now - 10 * m, mattino = true))
    assertFalse(RipresaNotte.daRiprendere(now, t0, now - 240 * m, mattino = false))
    assertFalse(RipresaNotte.daRiprendere(now, t0, null, mattino = false))
  }

  @Test
  fun causaBrevePerLaCard() {
    val buchi = listOf(at(3, 12) to at(3, 30), at(4, 0) to at(4, 10), at(5, 40) to at(5, 45))
    val p =
        generateSequence(t0) { it + 1000 }
            .takeWhile { it < at(7, 0) }
            .filter { x -> buchi.none { x > it.first && x < it.second } }
            .toList()
            .toLongArray()
    val ev =
        listOf(
            EventoNotte(at(3, 25), TipoEvento.SERVIZIO_AVVIATO),
            EventoNotte(at(4, 0), TipoEvento.BT_SPENTO),
            EventoNotte(at(5, 41), TipoEvento.FASCIA_SCOLLEGATA))
    assertEquals("app chiusa dal sistema, +2 interruzioni", RilevaInterruzioni.trova(p, ev, z).causaBreve())
    assertNull(RilevaInterruzioni.trova(LongArray(100) { t0 + it * 1000L }, ev, z).causaBreve())
  }

  @Test
  fun codiceEAltrePerIlContratto() {
    val buchi = listOf(at(3, 12) to at(3, 30), at(4, 0) to at(4, 10), at(5, 40) to at(5, 45))
    val p =
        generateSequence(t0) { it + 1000 }
            .takeWhile { it < at(7, 0) }
            .filter { x -> buchi.none { x > it.first && x < it.second } }
            .toList()
            .toLongArray()
    val ev =
        listOf(
            EventoNotte(at(3, 25), TipoEvento.SERVIZIO_AVVIATO),
            EventoNotte(at(4, 0), TipoEvento.BT_SPENTO),
            EventoNotte(at(5, 41), TipoEvento.FASCIA_SCOLLEGATA))
    val r = RilevaInterruzioni.trova(p, ev, z)
    assertEquals(CodiceCausa.APP_CHIUSA, r.principale()!!.codice)
    assertEquals(2, r.altre)
    assertEquals(listOf(CodiceCausa.APP_CHIUSA, CodiceCausa.BT_SPENTO, CodiceCausa.FUORI_PORTATA), r.buchi.map { it.codice })
    val vuota = RilevaInterruzioni.trova(LongArray(100) { t0 + it * 1000L }, ev, z)
    assertNull(vuota.principale())
    assertEquals(0, vuota.altre)
  }
}
