package com.wboelens.polarrecorder.biosleep

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wboelens.polarrecorder.biosleep.demo.Demo
import com.wboelens.polarrecorder.biosleep.hal.StatoRr
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsAuth
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsClient
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsResult
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsSettings
import com.wboelens.polarrecorder.biosleep.sopravvivenza.EventiNotte
import com.wboelens.polarrecorder.biosleep.watchdog.AutoStopLog
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Precisione della biometria sulle notti gia' registrate (una volta sola):
 *  1. ricalcolo dai battiti (anche archiviati) di rMSSD tarda, minimo della FC e respiro, con le
 *     stesse regole delle notti nuove (PrecisioneNotte, sonno dalle fasi salvate, niente HRV con
 *     fascia solo-FC o RR non affidabili);
 *  2. invio a Intervals.icu delle notti gia' inviate in passato, con la stessa scrittura delle notti
 *     nuove (IntervalsClient.uploadNight: soglia di qualita' 80% compresa). Si usa il client e non
 *     IntervalsSync: nessun avvio del coach, nessun aggiornamento della cache.
 * Le notti gia' inviate da questo lavoro non si reinviano (anche dopo un nuovo tentativo).
 */
class PrecisioneStoricoWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  companion object {
    private const val NOME = "noctalix-precisione-storico"
    private const val PREFS = "noctalix_precisione"
    private const val K_FATTO = "storico_v1"
    private const val K_INVIATE = "storico_inviate"
    private const val SOGLIA_QUALITA = 80.0
    private const val MAX_TENTATIVI = 6

    fun accoda(context: Context) {
      if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_FATTO, false)) return
      val req =
          OneTimeWorkRequestBuilder<PrecisioneStoricoWorker>()
              .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
              .build()
      WorkManager.getInstance(context).enqueueUniqueWork(NOME, ExistingWorkPolicy.KEEP, req)
    }
  }

  override suspend fun doWork(): Result =
      withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val db = SleepDb.get(ctx)

        // 1. Ricalcolo
        var ricalcolate = 0
        for (id in db.nottiSenzaPrecisione()) {
          @Suppress("TooGenericExceptionCaught")
          try {
            val stages = db.loadNight(id)?.stages ?: continue
            val (phone, rr) = db.loadRr(id)
            val res = NightAnalyzer.analyze(id, phone, rr) ?: continue
            val stato = runCatching { EventiNotte.get(ctx).statoRr(id) }.getOrNull()
            val senzaHrv = stato == StatoRr.SOLO_FC.name || stato == StatoRr.NON_AFFIDABILI.name
            val r = if (senzaHrv) res.copy(windows = res.windows.map { it.copy(rmssd = null, sdnn = null, pnn50 = null) }) else res
            db.salvaPrecisione(id, PrecisioneNotte.calcola(r, stages.sleepOnsetMs, stages.sleepEndMs, senzaHrv))
            ricalcolate++
          } catch (e: Exception) {
            AutoStopLog.write(ctx, "Precisione storico: notte $id non ricalcolata (${e.javaClass.simpleName})")
          }
        }

        // 2. Invio delle notti gia' inviate in passato
        val settings = IntervalsSettings(ctx)
        val cred = settings.credenziali
        if (cred == null || !settings.autoUpload || Demo.attiva(ctx)) {
          AutoStopLog.write(ctx, "Precisione storico: $ricalcolate notti ricalcolate, invio non eseguito (Intervals.icu non collegato o invio automatico spento)")
          prefs.edit().putBoolean(K_FATTO, true).apply()
          return@withContext Result.success()
        }
        val inviate = prefs.getStringSet(K_INVIATE, emptySet())!!.toMutableSet()
        var nuove = 0
        var errori = 0
        for (n in db.listNights().sortedBy { it.summary.startMs }) {
          val s = n.summary
          if (n.syncedAt == null || n.sessionId.toString() in inviate) continue
          if (s.rmssdLate == null && s.hrNadirPct == null && s.respRate == null) continue
          if ((s.qualityPct ?: 0.0) < SOGLIA_QUALITA) continue
          if (Demo.eNotteDemo(ctx, n.sessionId)) continue
          val r = IntervalsClient.uploadNight(IntervalsAuth.header(cred), s, n.stages)
          if (r is IntervalsResult.Ok) {
            inviate += n.sessionId.toString()
            nuove++
            prefs.edit().putStringSet(K_INVIATE, inviate.toSet()).apply()
          } else {
            errori++
            AutoStopLog.write(ctx, "Precisione storico: notte ${n.sessionId} non inviata: ${r.message}")
          }
        }
        AutoStopLog.write(ctx, "Precisione storico: $ricalcolate notti ricalcolate, $nuove inviate, $errori errori")
        if (errori > 0 && runAttemptCount + 1 < MAX_TENTATIVI) return@withContext Result.retry()
        prefs.edit().putBoolean(K_FATTO, true).apply()
        Result.success()
      }
}
