package com.wboelens.polarrecorder

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Lingua predefinita: inglese (values/), per chi ha una lingua non coperta. L'italiano e' in
 * values-it/ ed e' il riferimento dei testi: ogni stringa ha la traduzione in inglese (values/ e
 * values-en/, identiche), spagnolo e cinese, con gli stessi argomenti e senza il marchio a mano.
 */
class TraduzioniTest {
  private val lingue = listOf("values", "values-en", "values-es", "values-zh-rCN")
  private val segnaposto = Regex("%(\\d+)\\${'$'}s")

  private fun stringhe(f: File): Map<String, String> =
      Regex("""<string name="([a-z0-9_]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(f.readText())
          .associate { it.groupValues[1] to it.groupValues[2] }

  private fun base() = File("src/main/res/values-it").listFiles { f -> f.name.startsWith("strings") }.orEmpty()

  @Test
  fun ogniTestoHaLeSueTraduzioni() {
    val base = base()
    assertTrue(base.isNotEmpty(), "nessun file strings*.xml in values-it")
    val errori = mutableListOf<String>()
    for (f in base) {
      val it = stringhe(f)
      for (l in lingue) {
        val tf = File("src/main/res/$l/${f.name}")
        if (!tf.exists()) {
          errori += "$l/${f.name}: manca il file"
          continue
        }
        val tr = stringhe(tf)
        for ((k, v) in it) {
          val t = tr[k]
          if (t == null) errori += "$l/${f.name}: manca $k"
          else if (segnaposto.findAll(t).map { m -> m.value }.sorted().toList() != segnaposto.findAll(v).map { m -> m.value }.sorted().toList())
              errori += "$l/${f.name}: argomenti diversi in $k"
          else if ("NoctaliX" in t) errori += "$l/${f.name}: marchio scritto a mano in $k (usa l'argomento)"
        }
      }
    }
    assertEquals(emptyList<String>(), errori)
  }

  @Test
  fun inglesePredefinitoUgualeAValuesEn() {
    val errori = mutableListOf<String>()
    for (f in base()) {
      val def = File("src/main/res/values/${f.name}")
      val en = File("src/main/res/values-en/${f.name}")
      if (def.exists() && en.exists() && stringhe(def) != stringhe(en)) errori += "${f.name}: values/ e values-en/ diversi"
    }
    assertEquals(emptyList<String>(), errori)
  }

  @Test
  fun nessunTestoItalianoNelPredefinito() {
    // segnale grezzo ma efficace: parole italiane frequenti non devono comparire in values/
    val parole = Regex("""\b(della|delle|notte|sonno|allenamento|seduta|giorni|riposo)\b""")
    val errori = base().flatMap { f ->
      val def = File("src/main/res/values/${f.name}")
      if (!def.exists()) listOf("values/${f.name}: manca") else stringhe(def).filter { (_, v) -> parole.containsMatchIn(v) }.keys.map { "values/${f.name}: $it" }
    }
    assertEquals(emptyList<String>(), errori)
  }
}
