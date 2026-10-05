package com.wboelens.polarrecorder.biosleep.intervals

import java.util.Base64

/** Come l'app accede a Intervals.icu: token OAuth (preferito) oppure API key personale. */
sealed interface Credenziali {
  /** Token del collegamento OAuth: Bearer, atleta "0" = proprietario del token. */
  data class Token(val token: String) : Credenziali {
    override fun toString() = "Token(***)"
  }

  /** API key personale (opzione avanzata): Basic con utente "API_KEY". */
  data class Chiave(val chiave: String, val atleta: String) : Credenziali {
    override fun toString() = "Chiave(***, atleta=$atleta)"
  }
}

object IntervalsAuth {
  /** Valore dell'header Authorization per le chiamate all'API. */
  fun header(c: Credenziali): String =
      when (c) {
        is Credenziali.Token -> "Bearer ${c.token}"
        is Credenziali.Chiave ->
            "Basic " + Base64.getEncoder().encodeToString("API_KEY:${c.chiave}".toByteArray(Charsets.UTF_8))
      }
}
