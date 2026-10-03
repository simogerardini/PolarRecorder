package com.wboelens.polarrecorder.biosleep.coach

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Risposta HTTP ridotta a cio' che serve al contratto. codice null = errore di rete. */
class RispostaGitHub(val codice: Int?, val corpo: String, private val intestazioni: Map<String, String>) {
  fun header(nome: String): String? = intestazioni[nome.lowercase()]
}

/** Le due chiamate a GitHub: avvio del workflow e prova di accesso. Mai il token nei log. */
object GitHubClient {
  private const val TIMEOUT_MS = 20_000

  private fun chiama(metodo: String, url: String, token: String, corpo: String?): RispostaGitHub {
    val conn =
        try {
          URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
          return RispostaGitHub(null, e.message ?: "", emptyMap())
        }
    return try {
      conn.requestMethod = metodo
      conn.connectTimeout = TIMEOUT_MS
      conn.readTimeout = TIMEOUT_MS
      conn.setRequestProperty("Authorization", "Bearer $token")
      conn.setRequestProperty("Accept", "application/vnd.github+json")
      conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
      if (corpo != null) {
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(corpo.toByteArray(Charsets.UTF_8)) }
      }
      val codice = conn.responseCode
      val stream = if (codice in 200..299) conn.inputStream else conn.errorStream
      val testo = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
      val h =
          conn.headerFields.entries
              .filter { it.key != null && it.value.isNotEmpty() }
              .associate { it.key.lowercase() to it.value.first() }
      RispostaGitHub(codice, testo, h)
    } catch (e: IOException) {
      RispostaGitHub(null, e.message ?: "", emptyMap())
    } finally {
      conn.disconnect()
    }
  }

  fun avvia(repo: String, token: String, data: String) =
      chiama("POST", GitHubDispatch.url(repo), token, GitHubDispatch.corpo(data))

  /** Legge il workflow: verifica token, repository e accesso senza avviare il coach. */
  fun prova(repo: String, token: String) = chiama("GET", GitHubDispatch.urlProva(repo), token, null)
}

/**
 * Avvio del coach dopo l'invio della notte di oggi. Ritenta da se' con una catena di lavori
 * singoli (non con il ritentativo di WorkManager) per poter aspettare esattamente il tempo
 * chiesto da GitHub (retry-after / x-ratelimit-reset). Ripetere e' sicuro: il workflow esegue un
 * solo coach al giorno.
 */
class CoachAvvioWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

  companion object {
    private const val TAG = "BioSleepCoach"
    private const val NOME_LAVORO = "avvio_coach"
    private const val K_DATA = "data"
    private const val K_TENTATIVO = "tentativo"

    /** data = giorno del risveglio (YYYY-MM-DD), la stessa della wellness appena inviata. */
    fun avvia(context: Context, data: String) =
        programma(context, data, 1, 0, ExistingWorkPolicy.REPLACE)

    private fun programma(context: Context, data: String, tentativo: Int, ritardoMs: Long, politica: ExistingWorkPolicy) {
      val richiesta =
          OneTimeWorkRequestBuilder<CoachAvvioWorker>()
              .setInputData(Data.Builder().putString(K_DATA, data).putInt(K_TENTATIVO, tentativo).build())
              .setInitialDelay(ritardoMs, TimeUnit.MILLISECONDS)
              .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
              .build()
      WorkManager.getInstance(context).enqueueUniqueWork(NOME_LAVORO, politica, richiesta)
    }
  }

  override fun doWork(): Result {
    val ctx = applicationContext
    val data = inputData.getString(K_DATA) ?: return Result.success()
    val tentativo = inputData.getInt(K_TENTATIVO, 1)
    val s = CoachSettings(ctx)
    val token = s.token
    if (token == null || !GitHubDispatch.repoValido(s.repo)) {
      val motivo = if (token == null) "Token GitHub non impostato" else "Repository non valido: ${s.repo}"
      s.segnaErrore(data, Esito.Errore(Problema.CONFIGURAZIONE, motivo))
      Log.w(TAG, motivo)
      return Result.success()
    }
    val r = GitHubClient.avvia(s.repo, token, data)
    s.registraScadenza(r.header("github-authentication-token-expiration"))
    when (val e = GitHubDispatch.esito(r.codice, r::header, r.corpo, tentativo, System.currentTimeMillis())) {
      Esito.Avviato -> {
        Log.i(TAG, "Coach avviato per $data (tentativo $tentativo)")
        s.segnaAvviato(data)
      }
      is Esito.Ritenta -> {
        Log.w(TAG, "${e.motivo}: nuovo tentativo fra ${e.ritardoMs / 1000} s")
        s.segnaInAttesa(data, "${e.motivo}: nuovo tentativo in corso")
        programma(ctx, data, tentativo + 1, e.ritardoMs, ExistingWorkPolicy.APPEND_OR_REPLACE)
      }
      is Esito.Errore -> {
        // 422 e risposte inattese: la risposta di GitHub resta nel log locale
        Log.w(TAG, "Coach non avviato (HTTP ${r.codice}): ${e.messaggio}")
        s.segnaErrore(data, e)
      }
    }
    return Result.success()
  }
}
