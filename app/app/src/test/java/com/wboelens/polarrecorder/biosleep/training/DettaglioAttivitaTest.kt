package com.wboelens.polarrecorder.biosleep.training

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Attivita' e serie reali lette da Intervals.icu (corsa con potenza, bici con e senza, nuoto). */
class DettaglioAttivitaTest {
  private fun r(nome: String) =
      javaClass.getResource("/intervals/$nome")?.readText() ?: error("Manca src/test/resources/intervals/$nome")

  @Test
  fun corsaDiSoglia() {
    val a = DettaglioParser.attivita(r("attivita_corsa.json"))!!
    assertEquals(Sport.CORSA, a.sport)
    assertEquals(2410, a.durataS)
    assertEquals(51, a.tss)
    assertEquals(157, a.fcMedia)
    assertEquals("4:53 /km", Ritmo.perSport(a.velocitaMedia!!, a.sport))
    assertEquals(7, a.zoneFc.size)
    assertEquals("< 144 bpm", a.zoneFc[0].intervallo)
    assertEquals("144–153 bpm", a.zoneFc[1].intervallo)
    assertEquals(7, a.zonePotenza.size, "SS (sweet spot) escluso dalle barre")
    assertEquals("< 143 W", a.zonePotenza[0].intervallo) // 55% di FTP 260
    assertEquals(7, a.zonePasso.size)
    assertEquals(5, a.intervalli.size)
    assertEquals(2, a.intervalli.count { it.lavoro })
    assertEquals(263, a.intervalli[1].watt)
  }

  @Test
  fun nuotoEBiciSenzaPotenza() {
    val n = DettaglioParser.attivita(r("attivita_nuoto.json"))!!
    assertEquals(Sport.NUOTO, n.sport)
    assertEquals(25.0, n.vascaM)
    assertTrue(Ritmo.perSport(n.velocitaMedia!!, n.sport).endsWith("/100m"))
    val b = DettaglioParser.attivita(r("attivita_bici_hr.json"))!!
    assertEquals(0, b.zonePotenza.size, "senza misuratore di potenza niente zone di potenza")
    assertEquals(5, b.zoneFc.size)
    assertNull(b.distanzaM)
  }

  @Test
  fun serieRidotte() {
    for ((nome, attesi) in
        listOf(
            "streams_corsa.json" to setOf("heartrate", "watts", "velocity_smooth", "cadence"),
            "streams_bici_power.json" to setOf("heartrate", "watts", "velocity_smooth", "cadence", "altitude"),
            "streams_bici_hr.json" to setOf("heartrate"),
            "streams_nuoto.json" to setOf("heartrate", "velocity_smooth", "cadence"))) {
      val testo = DettaglioParser.riduci(r(nome))
      val f = DettaglioParser.flussi(testo)!!
      assertEquals(attesi, f.serie.keys, nome)
      assertTrue(f.tempoS.size in 300..601, "$nome: ${f.tempoS.size} punti")
      assertTrue(testo.length < 40_000, "$nome: ${testo.length} caratteri in cache")
    }
  }
}
