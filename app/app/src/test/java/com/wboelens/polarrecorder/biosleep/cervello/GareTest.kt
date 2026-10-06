package com.wboelens.polarrecorder.biosleep.cervello

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** gare.json: risposta reale di cervello.gare (sprint dall'app, olimpico A, gara generica). */
class GareTest {
  private fun r(n: String) = javaClass.getResource("/cervello/$n")?.readText() ?: error("Manca $n")

  @Test
  fun elenco() {
    val e = Gare.elenco(r("gare.json"))
    assertEquals(Gare.OK, e.esito)
    assertEquals(3, e.gare.size)
    val ob = e.gare.single { it.obiettivo }
    assertEquals("A", ob.priorita)
    assertEquals(17, ob.settimane)
    assertNull(e.gare.single { it.nome == "Gara" }.distanza, "distanza non riconosciuta")
    assertEquals("101", e.gare.first().id)
  }

  @Test
  fun sprintRestaSprintNelModulo() {
    // il coach la classifica "olimpico" (stessa preparazione): il modulo deve mostrare Sprint
    val sprint = Gare.elenco(r("gare.json")).gare.first()
    assertEquals("olimpico", sprint.distanza)
    assertEquals("sprint", Gare.distanzaPerModulo(sprint))
    assertEquals("Lago", Gare.nomeBase(sprint.nome))
    assertEquals("Lago", Gare.nomeBase("Lago — Triathlon olimpico — Triathlon 70.3"))
    assertEquals("Gara", Gare.nomeBase("Gara"))
  }

  @Test
  fun validazioneComeIlCervello() {
    val oggi = LocalDate.of(2026, 10, 6)
    assertTrue(Gare.errori("Lago", oggi.plusDays(30), "B", "sprint", oggi).isEmpty())
    assertEquals(4, Gare.errori("", oggi.minusDays(1), "D", null, oggi).size)
    assertTrue(Gare.errori("Lago", oggi.plusDays(18 * 31 + 1), "A", "full", oggi).isNotEmpty(), "oltre 18 mesi")
  }

  @Test
  fun esiti() {
    val ok = Gare.esito("""{"esito": "ok", "id": 555, "ripianifica": true}""")
    assertEquals("555", ok.id)
    assertTrue(ok.ripianifica)
    assertEquals(Gare.NON_VALIDI, Gare.esito("""{"esito": "valori_non_validi", "errore": "nome mancante"}""").esito)
    assertEquals(Gare.NON_TROVATA, Gare.esito("""{"esito": "non_trovata"}""").esito)
  }
}
