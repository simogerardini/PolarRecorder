package com.wboelens.polarrecorder.biosleep.cervello

import com.google.gson.JsonParser
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Ciclo mestruale: stessa formula di efficienza.giorni_luteali del cervello; configJson. */
class CicloTest {
  private val oggi = LocalDate.of(2026, 10, 19)

  @Test
  fun unSoloInizioCiclo28() {
    // inizio 01/10 -> prossimo stimato 29/10 -> luteali 22..28/10, ma solo fino a oggi+7 = 26/10
    assertEquals((22..26).map { "2026-10-%02d".format(it) }.toSet(), Ciclo.giorniLuteali(listOf("2026-10-01"), oggi))
  }

  @Test
  fun durataMediaEInizioRegistrato() {
    // cicli di 30 e 31 giorni -> media 30.5 -> 30 (pari, come round() di Python)
    val l = Ciclo.giorniLuteali(listOf("2026-08-01", "2026-08-31", "2026-10-01"), oggi)
    assertTrue("2026-08-30" in l && "2026-08-24" in l && "2026-08-23" !in l) // prima del 31/08
    assertTrue("2026-09-30" in l) // prima del 01/10
    assertTrue("2026-10-24" in l && "2026-10-30" !in l) // prossimo stimato 31/10, entro oggi+7
    assertEquals(emptySet<String>(), Ciclo.giorniLuteali(emptyList(), oggi))
  }

  @Test
  fun tagPerLaBaseline() {
    val t = Ciclo.conLuteali(mapOf("2026-10-20" to setOf("alcol")), listOf("2026-10-01"), oggi.minusDays(30), oggi)
    assertEquals(emptySet<String>(), t["2026-10-20"].orEmpty() - "alcol", "dopo oggi niente")
    val t2 = Ciclo.conLuteali(emptyMap(), listOf("2026-09-01", "2026-09-29"), oggi.minusDays(30), oggi)
    assertEquals(setOf("luteale"), t2["2026-09-28"])
  }

  @Test
  fun configJsonSoloSeAttivo() {
    assertFalse(ProfiloAtleta().json().has("ciclo"))
    assertFalse(ProfiloAtleta(cicloAttivo = false, cicloInizi = listOf("2026-10-01")).json().has("ciclo"))
    val c = ProfiloAtleta(cicloAttivo = true, cicloInizi = listOf("2025-01-01", LocalDate.now().minusDays(3).toString())).json()
        .getAsJsonObject("ciclo")
    assertTrue(c.get("attivo").asBoolean)
    assertEquals(1, c.getAsJsonArray("inizi").size(), "solo gli ultimi 6 mesi")
    JsonParser.parseString(c.toString())
  }
}
