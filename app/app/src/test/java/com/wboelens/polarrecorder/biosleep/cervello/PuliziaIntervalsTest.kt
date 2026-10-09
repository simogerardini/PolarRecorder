package com.wboelens.polarrecorder.biosleep.cervello

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PuliziaIntervalsTest {
  @Test
  fun provaEConferma() {
    val p = EsitoPulizia.da("""{"esito": "prova", "eventi_da_eliminare": [11, 12, 13], "eventi_eliminati": 0, "campi_da_eliminare": [5], "campi_eliminati": 0}""")
    assertTrue(p.ok)
    assertEquals(3, p.eventiDaEliminare)
    assertEquals(1, p.campiDaEliminare)
    assertEquals(null, p.perTipo)
    val c = EsitoPulizia.da("""{"esito": "ok", "eventi_da_eliminare": [11, 12, 13], "eventi_eliminati": 3, "campi_da_eliminare": [5], "campi_eliminati": 1}""")
    assertTrue(c.completo)
    val parziale = EsitoPulizia.da("""{"esito": "ok", "eventi_da_eliminare": [11, 12], "eventi_eliminati": 1, "campi_da_eliminare": [], "campi_eliminati": 0}""")
    assertFalse(parziale.completo)
  }

  @Test
  fun perTipoEErrori() {
    val p = EsitoPulizia.da("""{"esito": "prova", "eventi_da_eliminare": [1, 2], "per_tipo": {"sedute": 1, "gare": 1, "pause": 0}}""")
    assertEquals(mapOf("sedute" to 1, "gare" to 1, "pause" to 0), p.perTipo)
    assertEquals(EsitoPulizia.PERMESSO_MANCANTE, EsitoPulizia.da("""{"esito": "permesso_mancante", "eventi_da_eliminare": []}""").esito)
    val e = EsitoPulizia.da("""{"esito": "errore", "errore": "ConnectionError: x"}""")
    assertFalse(e.ok)
    assertEquals("ConnectionError: x", e.errore)
    assertFalse(EsitoPulizia.da("non json").ok)
  }
}
