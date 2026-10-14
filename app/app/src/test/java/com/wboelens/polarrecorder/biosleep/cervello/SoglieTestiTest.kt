package com.wboelens.polarrecorder.biosleep.cervello

import com.wboelens.polarrecorder.biosleep.lingua.TestiSistema
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Card "Soglie da completare": titolo e conseguenza si traducono dai codici (disciplina, campo),
 * mai dalla frase italiana del cervello. Ogni combinazione che il cervello puo' produrre ha il suo
 * modello in TestiSistema (quindi la traduzione in ogni lingua).
 */
class SoglieTestiTest {
  private val modelli = TestiSistema.VOCI.map { it.modello }.toSet()

  /** (disciplina, campo, gravita') di _SOGLIE in cervello.py, piu' "sport" e la CP di Stryd. */
  private fun combinazioni(): List<SogliaMancante> {
    val py = File("src/main/python/cervello.py").readText()
    val soglie = Regex("""\("(corsa|bici|nuoto)", \([^)]*\), "([a-z_]+)", "[a-z_]+", "(bloccante|consigliato)"""")
        .findAll(py).map { SogliaMancante(it.groupValues[1], it.groupValues[2], it.groupValues[3] == "bloccante", "") }.toList()
    check(soglie.size >= 6) { "tabella _SOGLIE non trovata in cervello.py" }
    val sport = listOf("corsa", "bici", "nuoto").map { SogliaMancante(it, "sport", true, "") }
    return soglie + sport + SogliaMancante("corsa", "cp", true, "")
  }

  @Test
  fun ogniConseguenzaHaIlSuoTesto() {
    val senza = combinazioni().filter { Soglie.effettoIt(it)?.let { e -> e in modelli } != true }
    assertEquals(emptyList<SogliaMancante>(), senza)
  }

  @Test
  fun ogniTitoloHaIlSuoTesto() {
    val pezzi = combinazioni().flatMap { listOf(Soglie.disciplina(it.disciplina), Soglie.campoIt(it.campo, it.disciplina)) }.toSet()
    // FTP e' uguale in ogni lingua
    assertEquals(emptySet<String>(), pezzi.filter { it !in modelli && it != "FTP" }.toSet())
  }
}
