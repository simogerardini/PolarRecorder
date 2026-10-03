package com.wboelens.polarrecorder.biosleep.riepilogo

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wboelens.polarrecorder.biosleep.training.Allenamenti
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** NOTE riepilogo del coach: lettura del JSON (schema v1, file di esempio del coach) e regole intorno. */
class RiepilogoTest {
  private val esempio =
      javaClass.getResource("/biosleep/riepilogo_esempio.json")?.readText()
          ?: error("Manca src/test/resources/biosleep/riepilogo_esempio.json")

  /** La NOTE come la scrive il coach: testo libero, poi il tag con il JSON su una riga. */
  private fun nota(json: String, data: String = "2026-09-23"): JsonObject {
    val o = JsonObject()
    o.addProperty("id", 777)
    o.addProperty("category", "NOTE")
    o.addProperty("start_date_local", "${data}T00:00:00")
    o.addProperty("external_id", "coach:Riepilogo:$data")
    o.addProperty("description", "Riepilogo del coach\n[[riepilogo_coach:$json]]")
    return o
  }

  private val compatto = JsonParser.parseString(esempio).toString()

  @Test
  fun leggeLEsempioDelCoach() {
    val r = RiepilogoParser.leggi(RiepilogoParser.json(nota(compatto).get("description")!!.asString)!!)!!
    assertEquals(1, r.versione)
    assertEquals("2026-09-23", r.data)
    assertEquals("🔵 PUOI SPINGERE", r.titoloNotifica)
    assertEquals("SPINGI", r.decisione.codice)
    assertEquals("Base 70.3 Multisport (prep. continua)", r.fase)
    assertNull(r.gara)
    val b = r.biometria!!
    assertEquals(true, b.ok)
    assertEquals(55.0 to 61.0, b.rangeMs)
    assertEquals(60.1, b.hrv7gg)
    assertNull(b.ggRitardo)
    val f = r.forma!!
    assertEquals(-12.7 to -10.0, f.fascia)
    assertNull(f.tsbDomenica)
    assertEquals(100.0, r.discipline["7gg"]!!["corsa"]!!.pct)
    assertNull(r.discipline["28gg"]!!["palestra"]!!.pct)
    assertNull(r.intensita)
    assertEquals(7.1, r.volume!!.restanoH)
    assertNull(r.carico!!.fatti)
    assertNull(r.avvisi, "stringa vuota = nessun avviso")
    assertEquals(emptyList<String>(), r.nonScritte)
    assertEquals(true, r.testo!!.startsWith("🎯 COACH"))
  }

  @Test
  fun jsonSuPiuRigheGrazieADotall() {
    val indentato = esempio // il file di esempio e' indentato su piu' righe
    assertEquals("2026-09-23", RiepilogoParser.leggi(RiepilogoParser.json(nota(indentato).get("description")!!.asString)!!)!!.data)
  }

  @Test
  fun trovaSoloIlRiepilogoDellaData() {
    val ieri = nota(compatto.replace("2026-09-23", "2026-09-22"), "2026-09-22")
    val altraNota = nota(compatto).deepCopy().apply { addProperty("external_id", "coach:SpecchioGarmin:1") }
    assertNull(RiepilogoParser.trova(listOf(ieri, altraNota), "2026-09-23"))
    assertEquals("SPINGI", RiepilogoParser.trova(listOf(ieri, nota(compatto)), "2026-09-23")
        ?.getAsJsonObject("decisione")?.get("codice")?.asString)
  }

  @Test
  fun campiFacoltativiENonScritte() {
    val j = JsonParser.parseString(compatto).asJsonObject
    j.add("intensita", JsonParser.parseString("""{"pct_facile": 78.5, "pct_intenso": null}"""))
    j.add("non_scritte", JsonParser.parseString("""["Bici Z2 sab", {"data": "2026-09-27", "nome": "Lungo"}]"""))
    j.addProperty("avvisi", "Credito basso")
    j.getAsJsonObject("discipline").getAsJsonObject("7gg").getAsJsonObject("bici").addProperty("target_pct", 45)
    val r = RiepilogoParser.leggi(j)!!
    assertEquals(78.5, r.intensita!!.pctFacile)
    assertNull(r.intensita!!.pctIntenso)
    assertEquals(listOf("Bici Z2 sab", "2026-09-27 · Lungo"), r.nonScritte)
    assertEquals("Credito basso", r.avvisi)
    assertEquals(45.0, r.discipline["7gg"]!!["bici"]!!.targetPct)
  }

  @Test
  fun tagRottoOSenzaDataNonERiepilogo() {
    assertNull(RiepilogoParser.json("[[riepilogo_coach:{rotto]]"))
    assertNull(RiepilogoParser.leggi(JsonParser.parseString("""{"v":1}""").asJsonObject))
  }

  @Test
  fun passiDellAttesa() {
    val t0 = 1_000_000L
    val fine = t0 + Attesa.DURATA_MS
    assertEquals(Passo.NOTIFICA, Attesa.passo(true, false, t0, fine))
    assertEquals(Passo.GIA_NOTIFICATO, Attesa.passo(true, true, t0, fine))
    assertEquals(Passo.RIPROVA, Attesa.passo(false, false, fine - 1, fine))
    assertEquals(Passo.SCADUTO, Attesa.passo(false, false, fine, fine))
    assertEquals(Passo.NOTIFICA, Attesa.passo(true, false, fine + 5, fine), "trovato anche all'ultimo giro: notifica")
  }

  @Test
  fun notaDelCoachMaiNelCalendario() {
    val n = Allenamenti.evento(nota(compatto))!!
    assertEquals(false, n.nota)
    val giorno = LocalDate.of(2026, 9, 23)
    val g = Allenamenti.giorni(listOf(n), emptyList(), giorno, giorno, giorno).single()
    assertEquals(0, g.note.size)
    assertEquals(0, g.pianificate.size)
  }
}
