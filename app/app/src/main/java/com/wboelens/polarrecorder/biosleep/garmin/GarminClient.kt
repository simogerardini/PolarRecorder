package com.wboelens.polarrecorder.biosleep.garmin

import android.content.Context
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Esito di una lettura dalla Personal API di Garmin Connect. */
sealed interface LetturaGarmin {
  data class Ok(val json: JsonElement) : LetturaGarmin

  /** 204 o corpo vuoto: per quel giorno Garmin non ha dati (es. orologio non indossato). */
  data object Vuoto : LetturaGarmin

  /** Chiave assente, scaduta o eliminata su Garmin Connect: va creata e inserita di nuovo. */
  data object ChiaveNonValida : LetturaGarmin

  /** HTTP 429: limite di frequenza di Garmin; si riprova dopo la pausa. */
  data object TroppeRichieste : LetturaGarmin

  data class Errore(val messaggio: String) : LetturaGarmin
}

/**
 * Parte 11: client minimo della Garmin Connect Personal API (solo lettura, nessuna libreria).
 * - La chiave si scambia con un token di accesso valido 24 ore (POST .../personal-api-key/token,
 *   header "Authorization: ApiKey <chiave>"); la risposta e' il JWT come testo grezzo
 *   (verificato con la sonda del 25/10/2026).
 * - Il token si riusa finche' gli resta piu' di un'ora; con 401 se ne chiede uno nuovo una volta.
 * - 429: pausa di 12 ore senza chiamate (termini Garmin: limiti di frequenza).
 * Da chiamare fuori dal main thread.
 */
object GarminClient {
  const val BASE = "https://personal.connectapi.garmin.com"
  const val PAGINA_CHIAVE = "https://connect.garmin.com/app/settings/personalApiToken"
  private const val TOKEN_PATH = "/personalapitoken-service/personal-api/personal-api-key/token"
  private const val MARGINE_S = 3600L
  private const val PAUSA_429_MS = 12 * 3_600_000L
  private const val CONNECT_TIMEOUT_MS = 15_000
  private const val READ_TIMEOUT_MS = 30_000

  /** Il JWT dalla risposta del token: testo grezzo, tra virgolette o dentro un JSON. */
  internal fun estraiToken(corpo: String): String? {
    val t = corpo.trim()
    if (t.isEmpty()) return null
    if (t.startsWith("{")) {
      return try {
        val o = JsonParser.parseString(t).asJsonObject
        listOf("access_token", "accessToken", "token").firstNotNullOfOrNull { k ->
          o.get(k)?.takeIf { it.isJsonPrimitive }?.asString
        }
      } catch (e: Exception) {
        null
      }
    }
    return t.removeSurrounding("\"").takeIf { it.count { c -> c == '.' } == 2 }
  }

  /** Claim exp (secondi epoch) del JWT, null se illeggibile. */
  internal fun scadenza(jwt: String): Long? =
      try {
        val p = jwt.split(".")[1]
        val json = String(java.util.Base64.getUrlDecoder().decode(p.padEnd((p.length + 3) / 4 * 4, '=')), Charsets.UTF_8)
        JsonParser.parseString(json).asJsonObject.get("exp")?.asLong
      } catch (e: Exception) {
        null
      }

  private fun ora() = System.currentTimeMillis()

  /** Token valido (dalla cache o nuovo); null + motivo se non ottenibile. */
  private fun token(s: GarminSettings, forza: Boolean): Pair<String?, LetturaGarmin?> {
    val chiave = s.chiave
    if (chiave.isEmpty()) return null to LetturaGarmin.ChiaveNonValida
    val inCache = s.token
    if (!forza && inCache.isNotEmpty() && s.tokenExp - ora() / 1000 > MARGINE_S) return inCache to null
    val (code, testo) =
        try {
          http("POST", BASE + TOKEN_PATH, "ApiKey $chiave")
        } catch (e: IOException) {
          return null to LetturaGarmin.Errore("Connessione non riuscita: ${e.message}")
        }
    return when {
      code == 429 -> {
        s.pausaFinoMs = ora() + PAUSA_429_MS
        null to LetturaGarmin.TroppeRichieste
      }
      code == 401 || code == 403 -> null to LetturaGarmin.ChiaveNonValida
      code != 200 -> null to LetturaGarmin.Errore("Garmin HTTP $code")
      else -> {
        val jwt = estraiToken(testo) ?: return null to LetturaGarmin.Errore("Token Garmin non riconosciuto")
        s.token = jwt
        s.tokenExp = scadenza(jwt) ?: (ora() / 1000 + 23 * 3600)
        jwt to null
      }
    }
  }

  /** GET di un percorso della Personal API (es. "/hrv-service/hrv/2026-10-25"). */
  fun leggi(context: Context, percorso: String): LetturaGarmin {
    // modalita' demo: nessuna connessione verso Garmin (stesso interruttore di IntervalsClient)
    if (com.wboelens.polarrecorder.biosleep.demo.DemoParte3.scrittureBloccate()) return LetturaGarmin.Errore("Modalità demo")
    val s = GarminSettings(context)
    if (ora() < s.pausaFinoMs) return LetturaGarmin.TroppeRichieste
    var forza = false
    repeat(2) {
      val (tok, problema) = token(s, forza)
      if (tok == null) return problema ?: LetturaGarmin.Errore("Token Garmin assente")
      val (code, testo) =
          try {
            http("GET", BASE + percorso, "Bearer $tok")
          } catch (e: IOException) {
            return LetturaGarmin.Errore("Connessione non riuscita: ${e.message}")
          }
      when {
        code == 401 && !forza -> forza = true // token revocato prima della scadenza: uno nuovo
        code == 401 || code == 403 -> return LetturaGarmin.ChiaveNonValida
        code == 429 -> {
          s.pausaFinoMs = ora() + PAUSA_429_MS
          return LetturaGarmin.TroppeRichieste
        }
        code == 204 -> return LetturaGarmin.Vuoto
        code != 200 -> return LetturaGarmin.Errore("Garmin HTTP $code: ${testo.take(160)}")
        else -> {
          val j = try { JsonParser.parseString(testo) } catch (e: Exception) { null }
          return if (j == null || j.isJsonNull) LetturaGarmin.Vuoto else LetturaGarmin.Ok(j)
        }
      }
    }
    return LetturaGarmin.ChiaveNonValida
  }

  private fun http(metodo: String, url: String, auth: String): Pair<Int, String> {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
      conn.requestMethod = metodo
      conn.connectTimeout = CONNECT_TIMEOUT_MS
      conn.readTimeout = READ_TIMEOUT_MS
      conn.setRequestProperty("Authorization", auth)
      // il token arriva come testo grezzo: con Accept JSON Garmin risponde 406
      conn.setRequestProperty("Accept", if (metodo == "POST") "*/*" else "application/json")
      if (metodo == "POST") {
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(0)
      }
      val code = conn.responseCode
      val stream = if (code in 200..299) conn.inputStream else conn.errorStream
      val testo = stream?.bufferedReader()?.use { it.readText() } ?: ""
      return code to testo
    } finally {
      conn.disconnect()
    }
  }
}
