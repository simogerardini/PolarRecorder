package com.wboelens.polarrecorder.biosleep.demo

import com.google.gson.JsonParser
import com.wboelens.polarrecorder.biosleep.readiness.BioBaseline
import com.wboelens.polarrecorder.biosleep.readiness.StatoForma
import com.wboelens.polarrecorder.biosleep.riepilogo.Messaggi
import com.wboelens.polarrecorder.biosleep.riepilogo.RiepilogoParser
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Insieme di esempio della demo: date relative a oggi, coerenza attivita'/calendario, riepilogo leggibile. */
class DemoParte3Test {
  private val oggi = LocalDate.of(2026, 10, 14) // mercoledi'

  private fun lista(risorsa: String, da: LocalDate, a: LocalDate) =
      JsonParser.parseString(DatiDemo.json(risorsa, da, a, oggi)).asJsonArray.map { it.asJsonObject }

  @Test
  fun wellnessConForma() {
    val w = lista("wellness", oggi.minusDays(119), oggi)
    assertEquals(120, w.size)
    assertEquals(oggi.toString(), w.last().get("id").asString)
    val ctl = w.last().get("ctl").asDouble
    assertTrue(ctl in 30.0..80.0, "CTL $ctl")
    assertTrue(w.all { it.get("rmssd") == null }, "la notte viene dalle notti DEMO della Parte 1, non da qui")
  }

  @Test
  fun attivitaDiQuattroSettimaneEUnaSaltata() {
    val a = lista("activities", oggi.minusDays(120), oggi)
    val giorni = a.map { it.get("start_date_local").asString.take(10) }
    assertTrue(giorni.all { it >= oggi.minusDays(28).toString() && it < oggi.toString() })
    assertTrue(oggi.minusDays(10).toString() !in giorni)
    assertEquals(27, a.size) // 28 giorni meno quella saltata, oggi non ancora fatta
  }

  @Test
  fun calendarioDellaSettimanaAbbinato() {
    val e = lista("events", oggi.minusDays(120), oggi.plusDays(30))
    assertEquals(7, e.size)
    assertEquals("2026-10-12", e.first().get("start_date_local").asString.take(10))
    val fatte = e.filter { it.has("paired_activity_id") }.map { it.get("start_date_local").asString.take(10) }
    assertEquals(listOf("2026-10-12", "2026-10-13"), fatte)
    val a = lista("activities", oggi.minusDays(7), oggi).filter { it.has("paired_event_id") }
    assertEquals(2, a.size)
  }

  @Test
  fun riepilogoNelFormatoDelCervello() {
    val b = BioBaseline(ok = true, nGiorniHrv = 14, banda = "verde", baselineHrv = 55.0, rolling7Hrv = 56.2,
        pctVsBaseline = 2.2, zLn = 0.31, normalRangeMs = 51.1 to 58.9, direzione7v7 = "stabile")
    val f = StatoForma(ctl = 52.0, atl = 58.0, tsb = -6.0, formPct = -11.5, zona = "Grigia", colore = "grigio",
        giorniInZona = 3, storicoGg = 90, rischioRelativo = false)
    val json = DatiDemo.riepilogo(oggi, b, f, DatiDemo.sedute(oggi))
    val r = RiepilogoParser.leggi(json)
    assertNotNull(r)
    val codici = JsonParser.parseString(json).asJsonObject.getAsJsonArray("messaggi").map { it.asJsonObject.get("codice").asString }
    assertEquals(emptyList<String>(), codici.filter { it !in Messaggi.ORDINE.keys }, "codici senza traduzione")
    assertTrue("biometria_procedi" in codici && "decisione_rispetta_piano" in codici)
  }
}
