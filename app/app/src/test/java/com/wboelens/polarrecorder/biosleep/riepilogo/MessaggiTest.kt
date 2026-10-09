package com.wboelens.polarrecorder.biosleep.riepilogo

import java.io.File
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ogni codice del catalogo del cervello (python/messaggi.py) ha la sua stringa in tutte le lingue,
 * con lo stesso numero di argomenti; la traduzione rimette i valori al posto giusto.
 */
class MessaggiTest {
  private val lingue = listOf("values", "values-en", "values-es", "values-zh-rCN")

  /** Stringhe di un file, come le restituirebbe Android (apici e virgolette tolti). */
  private fun stringhe(cartella: String): Map<String, String> =
      Regex("""<string name="([a-z_0-9]+)"[^>]*>(.*?)</string>""").findAll(File("src/main/res/$cartella/strings_messaggi.xml").readText())
          .associate { m ->
            var s = m.groupValues[2]
            if (s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1)
            m.groupValues[1] to s.replace("\\'", "'").replace("\\\"", "\"").replace("&lt;", "<").replace("&amp;", "&")
          }

  private fun codiciDelCervello(): Set<String> {
    val py = File("src/main/python/messaggi.py").readText()
    val blocchi = Regex("""(CATALOGO|NOTIFICHE) = \[(.*?)\n\]""", RegexOption.DOT_MATCHES_ALL).findAll(py).joinToString("\n") { it.groupValues[2] }
    return Regex("""^\s*\("([a-z_]+)",""", RegexOption.MULTILINE).findAll(blocchi).map { it.groupValues[1] }.toSet()
  }

  @Test
  fun ogniCodiceHaLaSuaStringaInOgniLingua() {
    val codici = codiciDelCervello()
    assertTrue(codici.size >= 59, "catalogo del cervello non letto (${codici.size} codici)")
    assertEquals(emptySet<String>(), codici - Messaggi.ORDINE.keys, "codici del cervello senza ORDINE nell'app")
    for (l in lingue) {
      val s = stringhe(l)
      for (c in codici) {
        val t = s["msg_$c"] ?: error("$l: manca msg_$c")
        val n = Regex("%(\\d)\\${'$'}s").findAll(t).map { it.groupValues[1].toInt() }.maxOrNull() ?: 0
        assertEquals(Messaggi.ORDINE.getValue(c).size, n, "$l msg_$c: argomenti")
      }
    }
  }

  private fun traduttore(cartella: String): (Messaggio) -> String {
    val s = stringhe(cartella)
    val loc = if (cartella == "values") Locale.ITALY else Locale.ENGLISH
    return { m ->
      Messaggi.traduci(
          m,
          stringa = { nome, args -> s[nome]?.let { String.format(loc, it, *args) } },
          data = { it.substring(8) + "/" + it.substring(5, 7) },
          numero = { if (loc == Locale.ITALY) it.replace('.', ',') else it })
    }
  }

  @Test
  fun valoriAlPostoGiusto() {
    val it = traduttore("values")
    val caldo = Messaggio("motivo", "caldo_corsa_convertita", mapOf("data" to "2026-07-14", "temp" to "31", "umidita" to "60", "ora" to "18", "minuti" to "50", "fattore" to "1.2"), emptyList(), "x")
    assertEquals("caldo del 14/07: 31 °C, umidità 60% alle 18: corsa facile convertita in bici indoor 50' (x1.2)", it(caldo))
    val daTag = caldo.copy(valori = mapOf("data" to "2026-07-14", "minuti" to "50", "fattore" to "1.2"))
    assertEquals("caldo del 14/07 (tag): corsa facile convertita in bici indoor 50' (x1.2)", it(daTag))
    val tsb = Messaggio("avviso", "tsb_fuori_fascia", mapOf("tsb" to "-12.3", "min" to "-10.0", "max" to "5.0"), emptyList(), "x")
    assertEquals("TSB previsto a domenica -12,3 fuori dalla fascia attesa -10,0/5,0", it(tsb))
    assertEquals("brick alleggerito: corsa di qualità di ieri oltre il 125% del TSS pianificato", it(Messaggio("motivo", "brick_alleggerito", emptyMap(), emptyList(), "x")))
    val en = traduttore("values-en")
    assertEquals("TSB expected on Sunday -12.3 outside the expected range -10.0/5.0", en(tsb))
    val multiplo = Messaggio("motivo", Messaggi.MULTIPLO, emptyMap(), listOf(tsb, tsb), "x")
    assertEquals(en(tsb) + "; " + en(tsb), en(multiplo))
    // codice sconosciuto o non codificato: il testo italiano del cervello
    assertEquals("testo libero", en(Messaggio("motivo", Messaggi.NON_CODIFICATO, emptyMap(), emptyList(), "testo libero")))
  }
}
