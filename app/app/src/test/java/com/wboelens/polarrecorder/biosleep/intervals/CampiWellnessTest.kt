package com.wboelens.polarrecorder.biosleep.intervals

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nessun vecchio codice di campo wellness (BioSleep + suffisso) nei sorgenti: l'unico elenco e'
 * CampiWellness, generato da python/campi.py. I vecchi codici si ricavano dai nuovi, cosi' questo
 * file non li contiene e non trova se stesso.
 */
class CampiWellnessTest {
  @Test
  fun dodiciCodiciNoctalix() {
    assertEquals(12, CampiWellness.TUTTI.toSet().size)
    assertTrue(CampiWellness.TUTTI.all { it.startsWith("Noctalix") })
  }

  @Test
  fun nessunVecchioCodiceNeiSorgenti() {
    val vecchi = CampiWellness.TUTTI.map { "Bio" + "Sleep" + it.removePrefix("Noctalix") }
    val regex = Regex("(" + vecchi.joinToString("|") + ")\\b")
    val cartelle = listOf("src/main/java", "src/main/python", "src/test/java", "src/test/resources")
    val trovati =
        // campi.py contiene di proposito la tabella vecchio -> nuovo usata dalla migrazione dello storico
        cartelle.flatMap { c -> File(c).walkTopDown().filter { it.isFile && it.name != "campi.py" }.toList() }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, r -> if (regex.containsMatchIn(r)) "${f.path}:${i + 1}" else null } }
    assertTrue("Vecchi codici BioSleep* ancora presenti:\n" + trovati.joinToString("\n"), trovati.isEmpty())
  }
}
