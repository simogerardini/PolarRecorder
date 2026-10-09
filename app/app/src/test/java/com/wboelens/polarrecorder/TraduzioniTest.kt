package com.wboelens.polarrecorder

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ogni testo italiano dell'interfaccia (res/values/strings_*.xml) ha la traduzione in inglese,
 * spagnolo e cinese, con gli stessi argomenti %1$s, %2$s... e senza il marchio scritto a mano.
 */
class TraduzioniTest {
  private val lingue = listOf("values-en", "values-es", "values-zh-rCN")
  private val segnaposto = Regex("%(\\d+)\\${'$'}s")

  private fun stringhe(f: File): Map<String, String> =
      Regex("""<string name="([a-z0-9_]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(f.readText())
          .associate { it.groupValues[1] to it.groupValues[2] }

  @Test
  fun ogniTestoHaLeSueTraduzioni() {
    val base = File("src/main/res/values").listFiles { f -> f.name.startsWith("strings_") }.orEmpty()
    assertTrue(base.isNotEmpty(), "nessun file strings_*.xml in values")
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
}
