package com.wboelens.polarrecorder.biosleep.intervals

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wboelens.polarrecorder.biosleep.auto.NightNotifier
import com.wboelens.polarrecorder.biosleep.watchdog.AutoStopLog
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Invio di una notte a Intervals.icu, separato dall'analisi.
 * - Parte solo con la rete disponibile (vincolo di WorkManager): a telefono in Doze o senza
 *   connessione resta in coda e parte al primo momento utile, anche dopo un riavvio.
 * - Se fallisce per rete o server riprova con attese crescenti (10, 20, 40 minuti...).
 * - Usa le stesse credenziali e la stessa funzione del pulsante "Invia ora" (IntervalsSync),
 *   che dopo un invio riuscito della notte di oggi avvia il coach (coach-<data>).
 * Ogni tentativo scrive una riga nel registro (files/biosleep_autostop.log) con l'esito HTTP.
 */
class InvioNotteWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  companion object {
    private const val K_SESSIONE = "sessione"
    private const val MAX_TENTATIVI_RETE = 20
    private const val MAX_TENTATIVI_ACCESSO = 4

    fun nome(sessionId: Long) = "biosleep-invio-$sessionId"

    /** KEEP: una notte gia' in coda non viene accodata due volte. */
    fun accoda(context: Context, sessionId: Long, motivo: String) {
      val req =
          OneTimeWorkRequestBuilder<InvioNotteWorker>()
              .setInputData(Data.Builder().putLong(K_SESSIONE, sessionId).build())
              .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
              .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
              .build()
      WorkManager.getInstance(context).enqueueUniqueWork(nome(sessionId), ExistingWorkPolicy.KEEP, req)
      AutoStopLog.write(context, "Invio notte $sessionId in coda ($motivo)")
    }
  }

  override suspend fun doWork(): Result {
    val ctx = applicationContext
    val id = inputData.getLong(K_SESSIONE, -1L)
    if (id < 0) return Result.success()
    val tentativo = runAttemptCount + 1
    val settings = IntervalsSettings(ctx)
    if (!settings.autoUpload) {
      AutoStopLog.write(ctx, "Invio notte $id: invio automatico disattivato nelle impostazioni")
      return Result.success()
    }
    // Diagnosi delle credenziali, senza mai scriverle nel registro
    val token = settings.token
    val origine =
        when {
          token.isNotEmpty() -> "token OAuth"
          settings.collegato -> "token presente ma NON decifrabile, uso la API key se c'e'"
          settings.apiKey.isNotEmpty() -> "API key"
          else -> "nessuna credenziale"
        }
    val r = withContext(Dispatchers.IO) { IntervalsSync.syncNight(ctx, id) }
    AutoStopLog.write(ctx, "Invio notte $id (tentativo $tentativo, $origine): ${r.message}")
    if (r is IntervalsResult.Ok) return Result.success()

    val m = r.message
    val rete = m.startsWith("Connessione non riuscita") || Regex("HTTP (5\\d\\d|429)").containsMatchIn(m)
    val accesso = m.contains("non valido") || m.contains("non collegato")
    return when {
      rete && tentativo < MAX_TENTATIVI_RETE -> Result.retry()
      accesso && tentativo < MAX_TENTATIVI_ACCESSO -> Result.retry()
      else -> {
        NightNotifier.notifyAlert(ctx, "Notte non inviata a Intervals.icu", "$m\nPuoi reinviarla dalla scheda della notte.")
        Result.failure()
      }
    }
  }
}
