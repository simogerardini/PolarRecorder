package com.wboelens.polarrecorder.biosleep.cervello

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wboelens.polarrecorder.R
import com.wboelens.polarrecorder.biosleep.cache.CacheSync
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsSettings
import com.wboelens.polarrecorder.biosleep.riepilogo.RiepilogoDb
import com.wboelens.polarrecorder.biosleep.riepilogo.RiepilogoParser
import com.wboelens.polarrecorder.biosleep.tag.TagDb
import java.io.File
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Ripianifica questa settimana": il cervello con modo "settimanale" e forza = true, solo su
 * richiesta dell'utente (mai in automatico). Le sedute passate restano invariate. Gira in
 * WorkManager perche' dura alcuni minuti: lasciare la schermata non lo interrompe.
 * Non tocca lo stato del coach del giorno: il run giornaliero e il ripiego delle 10:30 restano.
 */
class RipianificaWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  companion object {
    const val NOME = "ripianifica_settimana"
    const val K_ESITO = "esito"
    const val K_ERRORE = "errore"
    const val K_DATA = "data"
    private const val ID_NOTIFICA = 5_200

    fun avvia(context: Context) {
      val r =
          OneTimeWorkRequestBuilder<RipianificaWorker>()
              .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
              .build()
      // KEEP: un secondo tocco mentre gira non avvia un altro run
      WorkManager.getInstance(context).enqueueUniqueWork(NOME, ExistingWorkPolicy.KEEP, r)
    }
  }

  override suspend fun doWork(): Result {
    val ctx = applicationContext
    NotificheCoach.canali(ctx)
    try {
      setForeground(primoPiano())
    } catch (e: IllegalStateException) {
      Log.i("BioSleepCoach", "Ripianifica senza primo piano: ${e.javaClass.simpleName}")
    }
    val oggi = LocalDate.now().toString()
    val credenziali =
        IntervalsSettings(ctx).credenziali
            ?: return Result.success(Data.Builder().putString(K_ESITO, RisultatoCervello.ERRORE).putString(K_ERRORE, "Intervals.icu non collegato").build())
    val config =
        ConfigCervello(
            credenziali, Cervello.cartella(ctx).absolutePath, modo = "settimanale", senzaAttesa = true, forza = true,
            profilo = ProfiloRepo.effettivo(ctx), tag = runCatching { TagDb.get(ctx).perCervello() }.getOrNull())
    val r = withContext(Dispatchers.IO) { Cervello.esegui(ctx, config) }
    Log.i("BioSleepCoach", "Ripianifica: ${r.esito}")
    r.notifiche.forEach { NotificheCoach.testo(ctx, it) }
    var data: String? = null
    r.riepilogoFile?.let { percorso ->
      try {
        val testo = File(percorso).readText(Charsets.UTF_8)
        RiepilogoParser.leggi(testo)?.let { rie ->
          RiepilogoDb.get(ctx).salva(rie.data, testo)
          data = rie.data
        }
      } catch (e: IOException) {
        Log.w("BioSleepCoach", "Riepilogo non leggibile: ${e.message}")
      }
    }
    if (r.esito == RisultatoCervello.PIANIFICATA) CacheSync.aggiornaInBackground(ctx, forza = true)
    return Result.success(
        Data.Builder().putString(K_ESITO, r.esito).putString(K_ERRORE, r.errore).putString(K_DATA, data ?: oggi).build())
  }

  private fun primoPiano(): ForegroundInfo {
    val n =
        NotificationCompat.Builder(applicationContext, NotificheCoach.CANALE_LAVORO)
            .setSmallIcon(R.drawable.ic_notifica_biosleep)
            .setContentTitle("Coach al lavoro")
            .setContentText("Ripianifico la settimana")
            .setOngoing(true)
            .setSilent(true)
            .build()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ForegroundInfo(ID_NOTIFICA, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    else ForegroundInfo(ID_NOTIFICA, n)
  }

  override suspend fun getForegroundInfo(): ForegroundInfo = primoPiano()
}
