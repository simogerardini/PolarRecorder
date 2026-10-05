package com.wboelens.polarrecorder.biosleep.finalize

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wboelens.polarrecorder.PolarRecorderApplication
import com.wboelens.polarrecorder.R
import com.wboelens.polarrecorder.biosleep.watchdog.AutoStopLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Chiude, analizza, invia e notifica le notti in sospeso, protetto da WorkManager.
 *
 * Perche' un worker: fermata la registrazione, il servizio si chiude e, appena l'app non e' piu'
 * sullo schermo, Android congela il processo. Un'analisi su un thread qualunque si blocca a meta'
 * e, se poi il processo viene chiuso, la notte resta senza risultato. Un lavoro "accelerato" di
 * WorkManager invece gira fino alla fine anche con l'app chiusa, e se il processo muore viene
 * rieseguito. L'invio a Intervals.icu e l'avvio del coach (coach-<data>) restano quelli di
 * sempre: avvengono dentro l'analisi della notte.
 */
class NightFinalizeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  companion object {
    private const val NAME = "biosleep-chiusura-notte"
    private const val NOTIFICATION_ID = 5_200

    fun enqueue(context: Context, reason: String) {
      AutoStopLog.write(context, "Chiusura notte in coda ($reason)")
      val req =
          OneTimeWorkRequestBuilder<NightFinalizeWorker>()
              .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
              .build()
      // APPEND_OR_REPLACE: una richiesta arrivata durante un'analisi viene eseguita dopo
      WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }
  }

  override suspend fun doWork(): Result {
    val ctx = applicationContext
    try {
      setForeground(getForegroundInfo())
    } catch (e: IllegalStateException) {
      // Android 12+ da sfondo: il lavoro accelerato gira comunque, senza notifica
    }
    val app = ctx as PolarRecorderApplication
    withContext(Dispatchers.Main) { app.ensureManagersInitialized() }
    val saver = app.dataSavers?.bioSleep ?: return Result.retry()
    val done = withContext(Dispatchers.IO) { saver.finalizePendingNights() }
    AutoStopLog.write(ctx, "Chiusura notte completata: $done notti analizzate")
    return Result.success()
  }

  override suspend fun getForegroundInfo(): ForegroundInfo {
    // Il canale deve esistere prima della notifica, altrimenti Android rifiuta il primo piano
    applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel("biosleep_summary", "Riepilogo notte", NotificationManager.IMPORTANCE_DEFAULT))
    val n =
        NotificationCompat.Builder(applicationContext, "biosleep_summary")
            .setSmallIcon(R.drawable.ic_notifica_notte)
            .setContentTitle("Analisi della notte")
            .setContentText("Calcolo e invio a Intervals.icu")
            .setOngoing(true)
            .setSilent(true)
            .build()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      ForegroundInfo(NOTIFICATION_ID, n)
    }
  }
}
