package com.wboelens.polarrecorder.biosleep.readiness

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * La banda dell'app coincide con quella del coach (intervals_coach.py)?
 * I casi stanno in src/test/resources/biosleep/baseline_cases.json e si rigenerano con
 * genera_casi_baseline.py ogni volta che la formula del coach cambia.
 */
class BaselineParityTest {
  @Test
  fun bandaIdenticaAlCoach() {
    val testo = javaClass.getResource("/biosleep/baseline_cases.json")?.readText()
        ?: error("Manca src/test/resources/biosleep/baseline_cases.json")
    val esito = BaselineParity.verifica(testo)
    println("Casi verificati: ${esito.casi} — ${esito.intestazione}")
    esito.differenze.take(30).forEach { println("  DIFF $it") }
    assertTrue(esito.casi > 0, "Nessun caso nel file")
    assertTrue(esito.differenze.isEmpty(),
        "${esito.differenze.size} differenze fra app e coach (prime 30 stampate sopra)")
  }
}
