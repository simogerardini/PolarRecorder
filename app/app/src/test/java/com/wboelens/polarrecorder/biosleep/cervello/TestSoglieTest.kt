package com.wboelens.polarrecorder.biosleep.cervello

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestSoglieTest {
  private val stato =
      """{"ore_target": {}, "ultimo_test": {"data": "2026-10-10", "chiave": "run_tt30", "nome": "Test 30' corsa"}}"""

  @Test
  fun testInProgrammaNellaSettimana() {
    val t = StatoCoachFile.ultimoTest(stato)!!
    assertEquals("Test 30' corsa", t.nome)
    assertTrue(t.inProgramma(LocalDate.of(2026, 10, 6)), "martedi', test sabato: stessa settimana")
    assertTrue(t.inProgramma(LocalDate.of(2026, 10, 10)), "il giorno del test")
    assertTrue(!t.inProgramma(LocalDate.of(2026, 10, 11)), "dopo il test")
    assertTrue(!t.inProgramma(LocalDate.of(2026, 10, 3)), "settimana prima")
  }

  @Test
  fun testCssDaCompletare() {
    val t = StatoCoachFile.ultimoTest(
        """{"ultimo_test": {"data": "2026-10-05", "chiave": "swim_css", "nome": "Test CSS", "attesa_tempi": true}}""")!!
    assertTrue(t.chiedeTempiCss)
    // dopo registra_css il cervello mette attesa_tempi=false ed elaborato=true: la card sparisce
    val chiuso = StatoCoachFile.ultimoTest(
        """{"ultimo_test": {"data": "2026-10-05", "chiave": "swim_css", "nome": "Test CSS", "attesa_tempi": false, "elaborato": true}}""")!!
    assertTrue(!chiuso.chiedeTempiCss)
    assertNull(StatoCoachFile.ultimoTest("{}"))
    assertNull(StatoCoachFile.ultimoTest("rotto"))
  }

  @Test
  fun tempiEdEsiti() {
    assertEquals(372, secondiDaMmSs("6:12"))
    assertEquals(175, secondiDaMmSs(" 2:55 "))
    assertNull(secondiDaMmSs("6:72"))
    assertNull(secondiDaMmSs("372"))
    assertEquals(EsitoCss.OK, EsitoCss.da("""{"esito": "ok", "css": "1:38/100m"}""").esito)
    assertEquals("1:38/100m", EsitoCss.da("""{"esito": "ok", "css": "1:38/100m"}""").css)
    val nv = EsitoCss.da("""{"esito": "valori_non_validi", "errore": "tempi incoerenti: il 400 deve durare circa il doppio del 200"}""")
    assertEquals(EsitoCss.NON_VALIDI, nv.esito)
    assertTrue(nv.errore!!.contains("doppio"))
  }

  @Test
  fun soglieAggiornateDagliAvvisi() {
    val avvisi =
        "LTHR corsa 160 -> 167 aggiornata su Intervals.icu dagli sforzi reali\n" +
            "test in programma il 2026-10-10: Test 30' corsa\n" +
            "passo soglia corsa 4:25/km dal test del 2026-10-03, aggiornato su Intervals.icu\n" +
            "test di corsa del 2026-10-03: passo 3:50/km troppo diverso dall'attuale 4:30/km (oltre il 15%): non scritto, conferma tu il valore su Intervals.icu"
    assertEquals(2, AvvisiSoglie.aggiornate(avvisi).size)
    assertTrue(AvvisiSoglie.aggiornate(avvisi).all { !it.contains("troppo diverso") })
  }
}
