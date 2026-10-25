package com.wboelens.polarrecorder.biosleep.demo

import com.wboelens.polarrecorder.biosleep.cervello.Cervello
import com.wboelens.polarrecorder.biosleep.cervello.RisultatoCervello
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsClient
import java.io.File
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Modalita' demo: nessuna scrittura verso Intervals.icu. Le scritture passano da due soli punti:
 * IntervalsClient.request (Kotlin) e Cervello.python (tutto il cervello: gare, pause, soglie,
 * sport-settings, pulizia, prepara_account, coach). In demo il primo rifiuta POST/PUT/DELETE senza
 * aprire connessioni, il secondo non chiama Python e risponde con un errore riconoscibile.
 */
class DemoScrittureTest {
  @AfterEach fun fuori() = DemoParte3.perTest(null)

  @Test
  fun nessunaScritturaHttpInDemo() {
    DemoParte3.perTest(true)
    for (metodo in listOf("POST", "PUT", "DELETE")) {
      assertThrows(DemoNonConsentito::class.java) { IntervalsClient.request(metodo, "https://intervals.icu/api/v1/athlete/0/events", "Bearer x", "{}") }
    }
  }

  @Test
  fun rispostaDelCervelloInDemo() {
    val r = RisultatoCervello.da(Cervello.rispostaDemo("esegui_app"))
    assertEquals(RisultatoCervello.ERRORE, r.esito)
    assertEquals(Cervello.DEMO_NON_DISPONIBILE, r.errore)
  }

  @Test
  fun unSoloPuntoVersoIlCervello() {
    val src = File("src/main/java").walkTopDown().filter { it.name.endsWith(".kt") }.toList()
    val chiamate = src.flatMap { f -> Regex("""getModule\("cervello"\)\s*(//[^\n]*\s*)?\.callAttr""").findAll(f.readText()).map { f.name }.toList() }
    assertEquals(listOf("Cervello.kt"), chiamate, "ogni funzione del cervello passa da Cervello.python")
    val http = src.filter { "openConnection()" in it.readText() }.map { it.name }.sorted()
    assertTrue(http.all { it in setOf("IntervalsClient.kt", "IntervalsReader.kt", "OAuthIntervals.kt", "ClientePonte.kt",
        // Parte 11: solo letture da Garmin Connect (piu' il token), nessuna scrittura; in demo non si connette
        "GarminClient.kt") }, "connessioni nuove: $http")
  }
}
