package com.wboelens.polarrecorder.biosleep.intervals

import java.net.URLDecoder

/** Esito del ritorno dal collegamento, prima di salvare qualcosa. */
sealed interface RitornoOAuth {
  data class Collegato(val token: String, val atleta: String, val scope: String) : RitornoOAuth {
    override fun toString() = "Collegato(***, atleta=$atleta, scope=$scope)"
  }

  data class Rifiutato(val motivo: String) : RitornoOAuth
}

/**
 * Lettura e controllo del ritorno del Worker (logica pura, testata). Il Worker riapre l'app con
 * https://<worker>/app#token=...&scope=...&athlete_id=...&state=... (oppure #error=...&state=...).
 * I parametri stanno nel frammento (#): un frammento non viene mai mandato a un server, quindi se
 * l'app non e' installata il token non finisce nei log del Worker.
 */
object RitornoOAuthParser {
  /** Durata del nonce: un collegamento non completato entro 10 minuti va rifatto. */
  const val VALIDITA_NONCE_MS = 10 * 60_000L

  fun parametri(frammento: String?, query: String?): Map<String, String> =
      // in toMap vince l'ultimo: il frammento (dove il Worker mette il token) ha la precedenza
      listOfNotNull(query, frammento)
          .flatMap { it.split('&') }
          .mapNotNull { coppia ->
            val i = coppia.indexOf('=')
            if (i <= 0) null
            else URLDecoder.decode(coppia.substring(0, i), "UTF-8") to URLDecoder.decode(coppia.substring(i + 1), "UTF-8")
          }
          .toMap()

  fun valuta(p: Map<String, String>, nonce: String?, nonceMs: Long, adessoMs: Long): RitornoOAuth {
    val state = p["state"]
    if (nonce.isNullOrEmpty() || state != nonce) return RitornoOAuth.Rifiutato("richiesta di collegamento non riconosciuta: ricomincia da Impostazioni")
    if (adessoMs - nonceMs > VALIDITA_NONCE_MS) return RitornoOAuth.Rifiutato("collegamento scaduto: ricomincia da Impostazioni")
    p["error"]?.let { return RitornoOAuth.Rifiutato(if (it == "access_denied") "autorizzazione negata su Intervals.icu" else "Intervals.icu: $it") }
    val token = p["token"]?.takeIf { it.isNotBlank() } ?: return RitornoOAuth.Rifiutato("risposta senza token")
    return RitornoOAuth.Collegato(token, p["athlete_id"].orEmpty(), p["scope"].orEmpty())
  }
}

