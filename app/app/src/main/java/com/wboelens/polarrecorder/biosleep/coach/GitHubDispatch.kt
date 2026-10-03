package com.wboelens.polarrecorder.biosleep.coach

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Perche' l'avvio non e' riuscito: decide quale avviso mostrare. */
enum class Problema {
  /** 401: token non valido o scaduto. */
  TOKEN,
  /** 403 senza limite di frequenza, 404, 422, token o repository mancanti. */
  CONFIGURAZIONE,
  /** Rete o GitHub non disponibili anche dopo tutti i tentativi. */
  RETE,
}

sealed interface Esito {
  data object Avviato : Esito

  data class Ritenta(val ritardoMs: Long, val motivo: String) : Esito

  data class Errore(val problema: Problema, val messaggio: String) : Esito
}

/**
 * Contratto con il workflow del coach (chat Parte 2): richiesta workflow_dispatch e lettura della
 * risposta di GitHub. Logica pura, senza rete ne' Android: e' quella coperta dai test.
 */
object GitHubDispatch {
  const val WORKFLOW = "intervals_coach.yml"
  const val REF = "main"
  const val ORIGINE = "app"

  /** Dopo il primo tentativo: 1, 2, 4, 8, 16 minuti. */
  const val MAX_RITENTATIVI = 5
  private const val MINUTO_MS = 60_000L
  /** Oltre questa attesa non ha senso insistere: alle 10:30 parte comunque il coach di ripiego. */
  private const val ATTESA_MAX_MS = 2 * 60 * MINUTO_MS
  const val GIORNI_AVVISO_SCADENZA = 14L

  private val REPO = Regex("""[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+""")
  private val DATA = Regex("""\d{4}-\d{2}-\d{2}""")

  fun repoValido(repo: String) = REPO.matches(repo)

  fun url(repo: String): String {
    require(repoValido(repo)) { "repository non valido: $repo" }
    return "https://api.github.com/repos/$repo/actions/workflows/$WORKFLOW/dispatches"
  }

  fun urlProva(repo: String): String {
    require(repoValido(repo)) { "repository non valido: $repo" }
    return "https://api.github.com/repos/$repo/actions/workflows/$WORKFLOW"
  }

  /** {"ref":"main","inputs":{"data":"YYYY-MM-DD","origine":"app"}}: tutti i valori sono stringhe. */
  fun corpo(data: String): String {
    require(DATA.matches(data)) { "data non valida: $data" }
    return """{"ref":"$REF","inputs":{"data":"$data","origine":"$ORIGINE"}}"""
  }

  /**
   * Cosa fare dopo una risposta. codice null = errore di rete. tentativo = numero di questo
   * tentativo (1 = il primo). header: lettura di un header della risposta (null se assente).
   */
  fun esito(codice: Int?, header: (String) -> String?, corpo: String, tentativo: Int, adessoMs: Long): Esito {
    fun riprova(motivo: String, ritardoMs: Long): Esito =
        if (tentativo > MAX_RITENTATIVI) Esito.Errore(Problema.RETE, "$motivo: tentativi esauriti")
        else Esito.Ritenta(ritardoMs.coerceIn(0, ATTESA_MAX_MS), motivo)
    val backoff = MINUTO_MS shl (tentativo - 1).coerceIn(0, 10) // 1, 2, 4, 8, 16 minuti
    return when {
      codice == null -> riprova("rete non disponibile", backoff)
      codice == 204 -> Esito.Avviato
      codice == 401 -> Esito.Errore(Problema.TOKEN, "Token GitHub non valido o scaduto: va rinnovato")
      (codice == 403 || codice == 429) && limitato(header) ->
          riprova("limite di frequenza di GitHub", attesaLimite(header, adessoMs) ?: backoff)
      codice == 403 ->
          Esito.Errore(Problema.CONFIGURAZIONE, "Il token non ha il permesso Actions: Read and write")
      codice == 404 ->
          Esito.Errore(
              Problema.CONFIGURAZIONE,
              "Repository o workflow non trovati, oppure il token non ha accesso al repository")
      codice == 422 -> Esito.Errore(Problema.CONFIGURAZIONE, "Richiesta rifiutata da GitHub: ${corpo.take(200)}")
      codice >= 500 -> riprova("GitHub non disponibile (HTTP $codice)", backoff)
      else -> Esito.Errore(Problema.CONFIGURAZIONE, "Risposta inattesa di GitHub (HTTP $codice): ${corpo.take(200)}")
    }
  }

  private fun limitato(header: (String) -> String?) =
      header("x-ratelimit-remaining")?.trim() == "0" || header("retry-after") != null

  /** retry-after (secondi) oppure x-ratelimit-reset (istante in secondi). */
  private fun attesaLimite(header: (String) -> String?, adessoMs: Long): Long? {
    header("retry-after")?.trim()?.toLongOrNull()?.let { return it * 1000 }
    return header("x-ratelimit-reset")?.trim()?.toLongOrNull()?.let { it * 1000 - adessoMs }
  }

  /** Header github-authentication-token-expiration ("2027-10-03 10:00:00 UTC") -> data. */
  fun scadenza(valore: String?): LocalDate? =
      valore?.trim()?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

  /** Giorni alla scadenza se sotto la soglia di avviso (14), altrimenti null. */
  fun giorniSeInScadenza(scadenza: LocalDate?, oggi: LocalDate): Long? =
      scadenza?.let { ChronoUnit.DAYS.between(oggi, it) }?.takeIf { it < GIORNI_AVVISO_SCADENZA }
}
