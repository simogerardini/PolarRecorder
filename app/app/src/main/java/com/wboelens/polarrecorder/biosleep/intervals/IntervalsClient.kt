package com.wboelens.polarrecorder.biosleep.intervals

import com.wboelens.polarrecorder.BuildConfig
import com.wboelens.polarrecorder.biosleep.NightSummary
import com.wboelens.polarrecorder.biosleep.SleepStages
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONException
import org.json.JSONObject

/** Esito di una chiamata a Intervals.icu. */
sealed interface IntervalsResult {
  val message: String

  data class Ok(override val message: String) : IntervalsResult

  data class Failed(override val message: String) : IntervalsResult
}

/**
 * Client minimo per l'API REST di Intervals.icu (nessuna libreria esterna).
 * Autenticazione: header Authorization gia' pronto (IntervalsAuth.header): Bearer con il token
 * del collegamento OAuth, oppure Basic con la API key. Athlete id "0" = il proprietario.
 */
object IntervalsClient {
  internal const val BASE_URL = "https://intervals.icu/api/v1/athlete/0"
  private const val CONNECT_TIMEOUT_MS = 15_000
  private const val READ_TIMEOUT_MS = 20_000

  // Campi personalizzati di benessere: li crea cervello.prepara_account al collegamento
  const val F_RMSSD = "BioSleepRMSSD"
  const val F_SDNN = "BioSleepSDNN"
  const val F_RHR = "BioSleepRHR"
  const val F_MIN_HR = "BioSleepMinHR"
  const val F_AVG_HR = "BioSleepAvgHR"
  const val F_HOURS = "BioSleepHours"
  const val F_QUALITY = "BioSleepQuality"
  // Fase 7: fasi del sonno
  const val F_SLEEP_HOURS = "BioSleepSleepHours"
  const val F_DEEP = "BioSleepDeepMin"
  const val F_REM = "BioSleepREMMin"
  const val F_LIGHT = "BioSleepLightMin"
  const val F_AWAKE = "BioSleepAwakeMin"

  /** Codice campo -> descrizione, per la schermata delle impostazioni. */
  val CUSTOM_FIELDS =
      listOf(
          F_RMSSD to "rMSSD notte (ms)",
          F_SDNN to "SDNN notte (ms)",
          F_RHR to "FC a riposo (bpm)",
          F_MIN_HR to "FC minima (bpm)",
          F_AVG_HR to "FC media notte (bpm)",
          F_HOURS to "Durata registrazione (ore)",
          F_QUALITY to "Affidabilità: % della notte coperta da battiti validi",
          F_SLEEP_HOURS to "Sonno effettivo stimato (ore)",
          F_DEEP to "Sonno profondo stimato (min)",
          F_REM to "Sonno REM stimato (min)",
          F_LIGHT to "Sonno leggero stimato (min)",
          F_AWAKE to "Veglia dopo l'addormentamento (min)",
      )

  /** La notte appartiene al giorno del risveglio, come per Oura e Garmin. */
  fun morningDate(s: NightSummary): String =
      Instant.ofEpochMilli(s.endMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()

  fun buildWellness(s: NightSummary, stages: SleepStages?): JSONObject =
      JSONObject().apply {
        if (stages != null && stages.tstMin > 0) {
          put(F_SLEEP_HOURS, round(stages.tstMin / 60.0, 2))
          put(F_DEEP, stages.deepMin)
          put(F_REM, stages.remMin)
          put(F_LIGHT, stages.lightMin)
          put(F_AWAKE, stages.wakeMin)
        }
        put(F_RHR, s.restingHr.roundToInt())
        put(F_MIN_HR, s.hrMin.roundToInt())
        put(F_AVG_HR, round(s.hrAvg, 1))
        put(F_HOURS, round((s.endMs - s.startMs) / 3_600_000.0, 2))
        s.qualityPct?.let { put(F_QUALITY, round(it, 1)) }
        s.rmssd?.let { put(F_RMSSD, round(it, 1)) }
        s.sdnn?.let { put(F_SDNN, round(it, 1)) }
      }

  /** Invia il riepilogo di una notte nei campi personalizzati del giorno del risveglio. */
  fun uploadNight(auth: String, s: NightSummary, stages: SleepStages?): IntervalsResult {
    val date = morningDate(s)
    val body = buildWellness(s, stages)
    val (code, text) =
        try {
          request("PUT", "$BASE_URL/wellness/$date", auth, body.toString())
        } catch (e: IOException) {
          return IntervalsResult.Failed("Connessione non riuscita: ${e.message}")
        }
    if (code != HttpURLConnection.HTTP_OK) return IntervalsResult.Failed(describeError(code, text))

    // Controllo: Intervals restituisce il giorno aggiornato. Se un campo manca nella risposta,
    // quasi sempre significa che il campo personalizzato non e' stato creato.
    val missing =
        try {
          val saved = JSONObject(text)
          body.keys().asSequence().filter { !saved.has(it) || saved.isNull(it) }.toList()
        } catch (e: JSONException) {
          emptyList()
        }
    return if (missing.isEmpty()) {
      IntervalsResult.Ok("Inviata a Intervals.icu ($date)")
    } else {
      IntervalsResult.Failed(
          "Intervals non ha salvato: ${missing.joinToString()}. " +
              "Mancano i campi ${BuildConfig.APP_NAME}: in Impostazioni premi \"Prepara i campi\" e reinvia la notte.")
    }
  }

  /** Verifica accesso e connessione leggendo il profilo atleta. */
  fun testConnection(auth: String): IntervalsResult {
    val (code, text) =
        try {
          request("GET", BASE_URL, auth, null)
        } catch (e: IOException) {
          return IntervalsResult.Failed("Connessione non riuscita: ${e.message}")
        }
    if (code != HttpURLConnection.HTTP_OK) return IntervalsResult.Failed(describeError(code, text))
    val name =
        try {
          JSONObject(text).optString("name", "")
        } catch (e: JSONException) {
          ""
        }
    return IntervalsResult.Ok(if (name.isBlank()) "Connessione riuscita" else "Connesso come $name")
  }

  private fun describeError(code: Int, text: String): String =
      when (code) {
        HttpURLConnection.HTTP_UNAUTHORIZED,
        HttpURLConnection.HTTP_FORBIDDEN -> "Accesso a Intervals.icu non valido: ricollega o controlla la API key (HTTP $code)"
        422 -> "Dati rifiutati da Intervals (HTTP 422): ${text.take(200)}"
        else -> "Errore Intervals HTTP $code: ${text.take(200)}"
      }

  /** auth = valore completo dell'header Authorization ("Bearer ..." o "Basic ..."). */
  internal fun request(method: String, url: String, auth: String, body: String?): Pair<Int, String> {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
      conn.requestMethod = method
      conn.connectTimeout = CONNECT_TIMEOUT_MS
      conn.readTimeout = READ_TIMEOUT_MS
      conn.setRequestProperty("Authorization", auth)
      conn.setRequestProperty("Accept", "application/json")
      if (body != null) {
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
      }
      val code = conn.responseCode
      val stream = if (code in 200..299) conn.inputStream else conn.errorStream
      val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
      return code to text
    } finally {
      conn.disconnect()
    }
  }

  private fun round(v: Double, decimals: Int): Double =
      String.format(Locale.US, "%.${decimals}f", v).toDouble()
}
