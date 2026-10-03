package com.wboelens.polarrecorder.biosleep.coach

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Ogni riga della tabella delle risposte del contratto con il workflow del coach. */
class GitHubDispatchTest {
  private val adesso = 1_800_000_000_000L
  private fun h(vararg coppie: Pair<String, String>): (String) -> String? = { k -> coppie.toMap()[k] }
  private val nessuno = h()

  @Test
  fun richiestaComeDaContratto() {
    assertEquals(
        "https://api.github.com/repos/simonegerardini/stryd-coach/actions/workflows/intervals_coach.yml/dispatches",
        GitHubDispatch.url("simonegerardini/stryd-coach"))
    assertEquals(
        """{"ref":"main","inputs":{"data":"2026-10-04","origine":"app"}}""", GitHubDispatch.corpo("2026-10-04"))
    assertEquals(false, runCatching { GitHubDispatch.corpo("2026-10-4") }.isSuccess)
    assertEquals(false, GitHubDispatch.repoValido("stryd-coach"))
  }

  @Test
  fun avviatoENonRitentabili() {
    assertEquals(Esito.Avviato, GitHubDispatch.esito(204, nessuno, "", 1, adesso))
    assertEquals(Problema.TOKEN, (GitHubDispatch.esito(401, nessuno, "", 1, adesso) as Esito.Errore).problema)
    for (c in listOf(403, 404, 422)) {
      val e = GitHubDispatch.esito(c, h("x-ratelimit-remaining" to "4999"), "{}", 1, adesso)
      assertEquals(Problema.CONFIGURAZIONE, (e as Esito.Errore).problema, "HTTP $c")
    }
  }

  @Test
  fun limiteDiFrequenzaAspettaQuantoDiceGitHub() {
    val ra = GitHubDispatch.esito(403, h("retry-after" to "60"), "", 1, adesso)
    assertEquals(60_000L, (ra as Esito.Ritenta).ritardoMs)
    val reset = GitHubDispatch.esito(403, h("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "${adesso / 1000 + 300}"), "", 1, adesso)
    assertEquals(300_000L, (reset as Esito.Ritenta).ritardoMs)
  }

  @Test
  fun reteE5xxConBackoffEMassimoCinqueRitentativi() {
    val attese = (1..5).map { (GitHubDispatch.esito(null, nessuno, "", it, adesso) as Esito.Ritenta).ritardoMs / 60_000 }
    assertEquals(listOf(1L, 2L, 4L, 8L, 16L), attese)
    assertEquals(4L, (GitHubDispatch.esito(503, nessuno, "", 3, adesso) as Esito.Ritenta).ritardoMs / 60_000)
    assertEquals(Problema.RETE, (GitHubDispatch.esito(502, nessuno, "", 6, adesso) as Esito.Errore).problema)
  }

  @Test
  fun scadenzaToken() {
    val oggi = LocalDate.of(2026, 10, 4)
    val s = GitHubDispatch.scadenza("2026-10-15 10:00:00 UTC")
    assertEquals(LocalDate.of(2026, 10, 15), s)
    assertEquals(11L, GitHubDispatch.giorniSeInScadenza(s, oggi))
    assertNull(GitHubDispatch.giorniSeInScadenza(GitHubDispatch.scadenza("2027-10-03 10:00:00 UTC"), oggi))
    assertNull(GitHubDispatch.scadenza(null))
  }
}
