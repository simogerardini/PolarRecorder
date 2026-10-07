package com.wboelens.polarrecorder.biosleep.cervello

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Risposte reali di cervello.controlla_soglie (atleta simulato: corsa completa, bici senza LTHR, nuoto assente). */
class SoglieTest {
  private fun r(n: String) = javaClass.getResource("/cervello/$n")?.readText() ?: error("Manca src/test/resources/cervello/$n")

  @Test
  fun daCompletare() {
    val e = Soglie.da(r("soglie_da_completare.json"))
    assertTrue(e.daCompletare)
    assertEquals(listOf("Bici · FC soglia (LTHR)", "Nuoto · Impostazioni nuoto"), e.mancanti.map { it.titolo })
    assertTrue(e.mancanti.all { it.bloccante })
    assertEquals("https://intervals.icu/settings", e.link)
    assertEquals("LTHR 167 · passo soglia 4:30/km", Soglie.valoriLeggibili(e.valori.getValue("corsa")))
  }

  @Test
  fun okConConsigliato() {
    val e = Soglie.da(r("soglie_ok.json"))
    assertEquals(Soglie.OK, e.esito)
    assertEquals(1, e.mancanti.size)
    assertTrue(!e.mancanti[0].bloccante, "LTHR del nuoto: consigliato, in grigio")
    assertEquals("CSS 1:35/100m", Soglie.valoriLeggibili(e.valori.getValue("nuoto")))
    assertEquals("LTHR 160 · FTP 260 W", Soglie.valoriLeggibili(e.valori.getValue("bici")))
  }

  @Test
  fun stryd() {
    // con Stryd e senza CP: bloccante "CP corsa (Stryd)"
    val senza = Soglie.da(r("soglie_stryd_senza_cp.json"))
    assertTrue(senza.daCompletare)
    val cp = senza.mancanti.single { it.campo == "cp" }
    assertTrue(cp.bloccante)
    assertEquals("Corsa · CP corsa (Stryd)", cp.titolo)
    // con la CP: tra i valori "CP 290 W"
    val con = Soglie.da(r("soglie_stryd_cp.json"))
    assertEquals(Soglie.OK, con.esito)
    assertEquals("LTHR 167 · passo soglia 4:30/km · CP 290 W", Soglie.valoriLeggibili(con.valori.getValue("corsa")))
  }

  @Test
  fun permessoEErrore() {
    assertEquals(Soglie.PERMESSO_MANCANTE, Soglie.da("""{"esito": "permesso_mancante", "mancanti": [], "valori": {}, "link": "https://intervals.icu/settings"}""").esito)
    assertEquals(Soglie.ERRORE, Soglie.da("rotto").esito)
  }
}
