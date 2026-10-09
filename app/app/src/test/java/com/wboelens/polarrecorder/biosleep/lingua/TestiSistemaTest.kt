package com.wboelens.polarrecorder.biosleep.lingua

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Le frasi della logica (testi italiani prodotti dal codice) si traducono quando vengono mostrate.
 * Il test usa le stringhe vere di values-en e verifica: ogni modello ha la sua stringa in tutte le
 * lingue; frasi reali, con valori annidati, finiscono tradotte; una frase sconosciuta resta com'e'.
 */
class TestiSistemaTest {
  private fun stringhe(cartella: String): Map<String, String> =
      Regex("""<string name="([a-z0-9_]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
          .findAll(File("src/main/res/$cartella/strings_sistema.xml").readText())
          .associate { m ->
            var s = m.groupValues[2]
            if (s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1)
            m.groupValues[1] to s.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n").replace("&lt;", "<").replace("&amp;", "&")
          }

  private fun traduttore(cartella: String): (String) -> String {
    val s = stringhe(cartella)
    // come Android in TestiSistema: senza argomenti la stringa non si formatta
    return { t -> TestiSistema.traduci(t) { nome, args -> s[nome]?.let { if (args.isEmpty()) it else String.format(it, *args) } } }
  }

  @Test
  fun ogniModelloHaLaSuaStringa() {
    for (l in listOf("values", "values-en", "values-es", "values-zh-rCN")) {
      val s = stringhe(l)
      for (v in TestiSistema.VOCI) assertTrue(s.containsKey("sis_" + v.chiave), "$l: manca sis_${v.chiave}")
    }
  }

  @Test
  fun italianoInvariato() {
    // in italiano la stringa e' il modello stesso: il testo mostrato non cambia
    val it = traduttore("values")
    for (t in listOf(
        "Non inviata: Connessione non riuscita: timeout",
        "Interruzioni: 23' persi: app chiusa dal sistema alle 03:12 (18'); fascia fuori portata o spenta alle 05:40 (5')",
        "Registrazione da 6 h 05' · si ferma da sola quando togli la fascia",
        "Età anagrafica 41,6 · 4,2 anni in meno",
        "Fascia Polar H10 0F291832 non trovata: è indossata e vicina?",
        "Addormentamento alle 23:40 (dopo 15') · risveglio alle 06:46")) {
      assertEquals(t, it(t))
    }
  }

  @Test
  fun valoriAnnidatiTradotti() {
    val en = traduttore("values-en")
    assertEquals("Not sent: Connection failed: timeout", en("Non inviata: Connessione non riuscita: timeout"))
    assertEquals(
        "Interruptions: 23' lost: app closed by the system at 03:12 (18'); strap out of range or off at 05:40 (5')",
        en("Interruzioni: 23' persi: app chiusa dal sistema alle 03:12 (18'); fascia fuori portata o spenta alle 05:40 (5')"))
    assertEquals("app closed by the system, +2 interruptions", en("app chiusa dal sistema, +2 interruzioni"))
    assertEquals("Recording for 6 h 05' · stops by itself when you take off the strap", en("Registrazione da 6 h 05' · si ferma da sola quando togli la fascia"))
    assertEquals(
        "VO2max 53,1 (estimated from 28 runs and 2 rides, max HR 178) vs 41,6 typical for age and sex",
        en("VO2max 53,1 (stimato da 28 corse e 2 uscite in bici, FC max 178) contro 41,6 tipico per eta' e sesso"))
    assertEquals("-4,2 years", en("-4,2 anni"))
    assertEquals("1. Turn on Autostart for NoctaliX.", en("1. Attiva l'Avvio automatico per NoctaliX."))
    assertEquals("Pairing failed: cancelled", en("Associazione non riuscita: annullata"))
    assertEquals("frase che nessuno conosce", en("frase che nessuno conosce"))
  }

  @Test
  fun frasiCompostEESpazi() {
    val en = traduttore("values-en")
    assertEquals("Run · 48' · 47 TSS", en("Corsa · 48' · 47 TSS"))
    assertEquals("  No tags for today", en("  Nessun tag per oggi"))
    assertEquals("Grey zone for 12 days", en("Zona Grigia da 12 giorni"))
    assertEquals("Hours: 2h54 of 6h35 · cap 10h00 · 3h42 left in 3 days", en("Ore: 2h54 di 6h35 · tetto 10h00 · restano 3h42 in 3 gg"))
    assertEquals("Gym: 7 days 32' · 28 days 2h56", en("Palestra: 7 gg 32' · 28 gg 2h56"))
    assertEquals("done 82%", en("svolta 82%"))
    // una riga composta non va presa intera da un modello corto ("FC %s", "zona %s")
    assertEquals("Resting HR 7 days 42.3 bpm · baseline 41.6 (+0.7)", en("FC a riposo 7 gg 42.3 bpm · baseline 41.6 (+0.7)"))
    assertEquals("zone Grey · expected in this phase: Grey", en("zona Grigia · attesa in questa fase: Grigia"))
    assertEquals("Strap battery: 100% · read on 9 Oct 06:55", en("Batteria fascia: 100% · letta il 9 Oct 06:55"))
  }

  @Test
  fun ogniStringaSiFormattaSenzaErrori() {
    // un "%" letterale in una stringa con argomenti va scritto "%%": altrimenti l'app si chiude
    for (l in listOf("values", "values-en", "values-es", "values-zh-rCN")) {
      for ((k, t) in stringhe(l)) {
        val n = Regex("%(\\d+)\\${'$'}s").findAll(t).map { it.groupValues[1].toInt() }.maxOrNull() ?: 0
        if (n > 0) String.format(t, *Array(n) { "x" }) // lancia se il formato e' sbagliato
      }
    }
    val en = traduttore("values-en")
    assertEquals("80/20 polarisation: at most 20% of cycling and running time above threshold",
        en("Polarizzazione 80/20: al massimo il 20% del tempo di bici e corsa sopra la soglia"))
  }
}
