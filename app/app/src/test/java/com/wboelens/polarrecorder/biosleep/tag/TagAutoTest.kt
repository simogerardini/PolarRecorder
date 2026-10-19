package com.wboelens.polarrecorder.biosleep.tag

import com.google.gson.JsonParser
import com.wboelens.polarrecorder.biosleep.cervello.ConfigCervello
import com.wboelens.polarrecorder.biosleep.intervals.Credenziali
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tag automatici del cervello 2026.10.19-precisione1: lettura, testi, filtro, configJson. */
class TagAutoTest {
  private val json = """[
    {"data":"2026-10-18","tag":"caldo","motivo":"tag_auto_notte_tropicale","valori":{"minima":"21.4"}},
    {"data":"2026-10-19","tag":"viaggio","motivo":"tag_auto_viaggio","valori":{"da":"Europe/Rome","a":"America/New_York"}},
    {"data":"2026-10-19","tag":"sonno_disturbato","motivo":"tag_auto_sonno_tardivo","valori":{"ritardo":"105"}},
    {"data":"2026-10-19","tag":"sconosciuto","motivo":"x","valori":{}},
    {"tag":"caldo"}
  ]"""

  @Test
  fun lettura() {
    val t = TagAuto.leggi(JsonParser.parseString(json))
    assertEquals(listOf("2026-10-18:caldo", "2026-10-19:viaggio", "2026-10-19:sonno_disturbato"), t.map { it.id })
    assertEquals("Notte tropicale: minima 21.4 °C", TagAuto.testo(t[0]))
    assertEquals("Fuso orario cambiato: da Europe/Rome a America/New_York", TagAuto.testo(t[1]))
    assertEquals("Sonno iniziato 105 minuti dopo il solito", TagAuto.testo(t[2]))
  }

  @Test
  fun soloNonRifiutatiENonGiaScelti() {
    val t = TagAuto.leggi(JsonParser.parseString(json))
    assertEquals(listOf("2026-10-19:sonno_disturbato"),
        TagAuto.daMostrare(t, "2026-10-19", setOf("2026-10-19:viaggio"), emptySet()).map { it.id })
    assertEquals(emptyList<TagAutomatico>(), TagAuto.daMostrare(t, "2026-10-18", emptySet(), setOf("caldo")))
  }

  @Test
  fun dalRiepilogoPiuRecente() {
    val dir = Files.createTempDirectory("rie").toFile()
    File(dir, "riepilogo_2026-10-17.json").writeText("""{"tag_automatici":[]}""")
    File(dir, "riepilogo_2026-10-19.json").writeText("""{"tag_automatici":$json}""")
    assertEquals(3, TagAuto.dalRiepilogo(dir, LocalDate.of(2026, 10, 19)).size)
    assertEquals(0, TagAuto.dalRiepilogo(dir, LocalDate.of(2026, 11, 30)).size, "riepiloghi troppo vecchi: niente proposte")
  }

  @Test
  fun configJson() {
    val notti = JsonParser.parseString("""{"2026-10-19":{"inizio":"00:45"}}""").asJsonObject
    val o = JsonParser.parseString(
        ConfigCervello(Credenziali.Token("t"), "/c", fuso = "Europe/Rome", notti = notti,
            tagAutoRifiutati = listOf("2026-10-19:viaggio")).json()).asJsonObject
    assertEquals("Europe/Rome", o.get("fuso").asString)
    assertEquals("00:45", o.getAsJsonObject("notti").getAsJsonObject("2026-10-19").get("inizio").asString)
    assertEquals("2026-10-19:viaggio", o.getAsJsonArray("tag_auto_rifiutati")[0].asString)
    val vuoto = JsonParser.parseString(ConfigCervello(Credenziali.Token("t"), "/c", fuso = null).json()).asJsonObject
    assertTrue(!vuoto.has("notti") && !vuoto.has("tag_auto_rifiutati") && !vuoto.has("fuso"))
  }
}
