package com.wboelens.polarrecorder.biosleep.cache

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Base64

/** Esito di una lettura. Errore.definitivo = inutile riprovare subito (API key sbagliata). */
sealed interface Lettura {
  data class Ok(val testo: String) : Lettura

  data class Errore(val messaggio: String, val definitivo: Boolean = false) : Lettura
}

/**
 * Letture GET da Intervals.icu: wellness, events, activities con oldest/newest.
 * Stessa autenticazione dell'invio (utente "API_KEY", password = la chiave).
 * Riprova due volte, con pausa crescente, su errori di rete, 429 (troppe richieste) e 5xx.
 */
object IntervalsReader {
  private const val BASE_URL = "https://intervals.icu/api/v1/athlete/0"
  private const val CONNECT_TIMEOUT_MS = 15_000
  private const val READ_TIMEOUT_MS = 30_000
  private const val TENTATIVI = 3
  private const val PAUSA_MS = 2_000L

  /** risorsa: "wellness", "events" o "activities". Da chiamare fuori dal main thread. */
  fun leggi(apiKey: String, risorsa: String, da: LocalDate, a: LocalDate): Lettura {
    val url = "$BASE_URL/$risorsa?oldest=$da&newest=$a"
    var ultimo: Lettura.Errore = Lettura.Errore("nessun tentativo")
    for (tentativo in 1..TENTATIVI) {
      val esito = get(url, apiKey)
      if (esito is Lettura.Ok) return esito
      ultimo = esito as Lettura.Errore
      if (ultimo.definitivo || tentativo == TENTATIVI) break
      try {
        Thread.sleep(PAUSA_MS * tentativo)
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        break
      }
    }
    return Lettura.Errore("$risorsa: ${ultimo.messaggio}", ultimo.definitivo)
  }

  private fun get(url: String, apiKey: String): Lettura {
    val auth = Base64.getEncoder().encodeToString("API_KEY:$apiKey".toByteArray(Charsets.UTF_8))
    val conn =
        try {
          URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
          return Lettura.Errore("connessione non riuscita (${e.message})")
        }
    return try {
      conn.requestMethod = "GET"
      conn.connectTimeout = CONNECT_TIMEOUT_MS
      conn.readTimeout = READ_TIMEOUT_MS
      conn.setRequestProperty("Authorization", "Basic $auth")
      conn.setRequestProperty("Accept", "application/json")
      val code = conn.responseCode
      val stream = if (code in 200..299) conn.inputStream else conn.errorStream
      val testo = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
      when {
        code == HttpURLConnection.HTTP_OK -> Lettura.Ok(testo)
        code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN ->
            Lettura.Errore("API key non valida (HTTP $code)", definitivo = true)
        code == 429 || code >= 500 -> Lettura.Errore("Intervals.icu non disponibile (HTTP $code)")
        else -> Lettura.Errore("HTTP $code: ${testo.take(150)}", definitivo = true)
      }
    } catch (e: IOException) {
      Lettura.Errore("connessione non riuscita (${e.message})")
    } finally {
      conn.disconnect()
    }
  }
}
