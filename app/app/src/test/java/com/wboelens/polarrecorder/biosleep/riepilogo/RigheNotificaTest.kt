package com.wboelens.polarrecorder.biosleep.riepilogo

import com.google.gson.JsonParser
import java.io.File
import java.time.LocalDate
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Le notifiche del coach di esempio della Parte 2 (cervello 13d ter) ricostruite in en e zh. */
class RigheNotificaTest {
  private fun stringhe(cartella: String): Map<String, String> =
      listOf("strings_messaggi.xml", "strings_notifica.xml").flatMap { f ->
        Regex("""<string name="([a-z_0-9]+)"[^>]*>(.*?)</string>""").findAll(File("src/main/res/$cartella/$f").readText()).map {
          it.groupValues[1] to it.groupValues[2].removeSurrounding("\"").replace("\\'", "'").replace("\\\"", "\"").replace("&amp;", "&").replace("&lt;", "<")
        }.toList()
      }.toMap()

  private fun componi(cartella: String, righe: com.google.gson.JsonArray, locale: Locale): String? {
    val s = stringhe(cartella)
    return RigheNotifica.componi(righe, { n, a -> s[n]?.let { String.format(it, *a) } }, { it }, { it }, locale, LocalDate.of(2026, 1, 8))
  }

  private val esempi = JsonParser.parseString(File("src/test/resources/notifiche_esempi.json").readText()).asJsonArray

  private val italiano = listOf("Distanza obiettivo", "Volume cardio", "Ripartizione", "Alta intensit", "Banda biometrica",
      "HRV media", "Lunedi", "Martedi", "Domenica", "riposo", "rimossa", "Motivi", "Piano settimanale", "settimane",
      "ciclo continuo", "aerobica", "curva biometrica", "sotto il normal range", "Corsa", "Nuoto", "Bici chiave")

  @Test
  fun ogniEsempioSiRicostruisceSenzaItaliano() {
    assertTrue(esempi.size() >= 14, "esempi della Parte 2 mancanti")
    for ((cartella, locale) in listOf("values-en" to Locale.ENGLISH, "values-es" to Locale("es"), "values-zh-rCN" to Locale.SIMPLIFIED_CHINESE)) {
      for (e in esempi) {
        val o = e.asJsonObject
        val nome = o.get("nome")!!.asString
        val t = componi(cartella, o.getAsJsonArray("righe"), locale)
        assertNotNull(t, "$cartella, $nome: una riga non si traduce")
        if (cartella != "values-es") for (w in italiano) assertTrue(w !in t!!, "$cartella, $nome: resta \"$w\"\n$t")
        assertEquals(o.getAsJsonArray("righe").size(), t!!.lines().size, "$cartella, $nome: righe")
      }
    }
  }

  @Test
  fun righeDiEsempio() {
    val w1 = esempi.first { it.asJsonObject.get("nome")!!.asString == "continuo W1" }.asJsonObject.getAsJsonArray("righe")
    val en = componi("values-en", w1, Locale.ENGLISH)!!.lines()
    assertEquals("🗓️ Weekly plan — no race — continuous cycle, block A, load 1/4", en[0])
    assertEquals("Split: swim 18% · bike 45% · run 36%", en[3])
    assertEquals("Biometric band: GREEN", en[5])
    assertEquals("Monday 2026-01-05: Strength 45' + Endurance and pace swim 45'", en[9])
    val rim = esempi.first { it.asJsonObject.get("nome")!!.asString == "rimodulazione" }.asJsonObject.getAsJsonArray("righe")
    val zh = componi("values-zh-rCN", rim, Locale.SIMPLIFIED_CHINESE)!!
    assertTrue(zh.startsWith("🗓️ 周计划 — 2026-10-14 调整，区间 红色："), zh)
  }

  @Test
  fun codiceSconosciutoDaNull() {
    val r = JsonParser.parseString("""[{"tipo":"notifica_riga","codice":"nr_nuovo","valori":{},"testo":"x"}]""").asJsonArray
    assertEquals(null, componi("values-en", r, Locale.ENGLISH))
  }

  @Test
  fun ogniRigaDelCervelloHaLaSuaComposizione() {
    // codici di riga dichiarati dal cervello (messaggi.py, RIGHE_NOTIFICA) e chiavi delle sedute
    val py = File("src/main/python/messaggi.py").readText()
    val blocco = Regex("""RIGHE_NOTIFICA = \{(.*?)\n\}""", RegexOption.DOT_MATCHES_ALL).find(py)?.groupValues?.get(1).orEmpty()
    val codici = Regex(""""(nr_[a-z_0-9]+)"\s*:""").findAll(blocco).map { it.groupValues[1] }.toSet()
    assertTrue(codici.size >= 20, "RIGHE_NOTIFICA non letto (${codici.size})")
    assertTrue((codici - RigheNotifica.CODICI).isEmpty(), "righe del cervello che l'app non sa comporre: ${codici - RigheNotifica.CODICI}")
    for (c in listOf("values", "values-en", "values-es", "values-zh-rCN")) {
      val s = stringhe(c)
      for (k in listOf("nuoto_chiave", "nuoto_supporto", "nuoto_rigenerante", "bici_chiave", "bici_supporto", "corsa_supporto",
          "corsa_chiave", "lungo_corsa", "brick_bici", "brick_corsa", "lungo_bici", "forza", "mobilita", "gara_richiami",
          "gara_nuoto", "gara_bici_facile", "gara_attivazione")) assertTrue("ns_$k" in s, "$c: manca ns_$k")
    }
  }
}
