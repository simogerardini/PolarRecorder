package com.wboelens.polarrecorder.biosleep.riepilogo

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.SQLException
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.gson.JsonParser
import com.wboelens.polarrecorder.MainActivity
import com.wboelens.polarrecorder.R
import com.wboelens.polarrecorder.biosleep.cache.CacheRepo
import com.wboelens.polarrecorder.biosleep.cache.IntervalsReader
import com.wboelens.polarrecorder.biosleep.cache.Lettura
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsSettings
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Attesa del riepilogo del coach dopo l'invio della notte: legge gli eventi di oggi da
 * Intervals.icu ogni 10 minuti per al massimo 2 ore; appena trova la NOTE riepilogo la salva,
 * mostra la notifica e si ferma.
 *
 * Perche' una catena di lavori singoli e non un lavoro periodico: WorkManager non ripete un lavoro
 * periodico piu' spesso di ogni 15 minuti. Ogni giro, se non trova nulla, programma il successivo
 * fra 10 minuti. Android puo' ritardare un giro (risparmio energetico): la scadenza resta 2 ore
 * dall'avvio, quindi il controllo non si allunga.
 */
class RiepilogoWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

  companion object {
    private const val TAG = "BioSleepRiepilogo"
    private const val NOME_LAVORO = "riepilogo_coach"
    private const val K_DATA = "data"
    private const val K_SCADENZA = "scadenza"

    /** Da chiamare dopo l'invio riuscito della notte. Riparte da zero se gia' in corso. */
    fun avvia(context: Context, oggi: LocalDate = LocalDate.now()) {
      val data = oggi.toString()
      RiepilogoDb.get(context).iniziaAttesa(data)
      programma(context, data, System.currentTimeMillis() + Attesa.DURATA_MS, 0, ExistingWorkPolicy.REPLACE)
    }

    private fun programma(context: Context, data: String, scadenza: Long, ritardoMin: Long, politica: ExistingWorkPolicy) {
      val richiesta =
          OneTimeWorkRequestBuilder<RiepilogoWorker>()
              .setInputData(Data.Builder().putString(K_DATA, data).putLong(K_SCADENZA, scadenza).build())
              .setInitialDelay(ritardoMin, TimeUnit.MINUTES)
              .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
              .build()
      WorkManager.getInstance(context).enqueueUniqueWork(NOME_LAVORO, politica, richiesta)
    }
  }

  override fun doWork(): Result {
    val ctx = applicationContext
    val data = inputData.getString(K_DATA) ?: return Result.success()
    val scadenza = inputData.getLong(K_SCADENZA, 0L)
    try {
      val db = RiepilogoDb.get(ctx)
      // 1. Gia' salvato (per esempio da un aggiornamento della cache): basta notificare.
      var json = db.json(data)
      // 2. Altrimenti lo cerca negli eventi di oggi.
      if (json == null) {
        val settings = IntervalsSettings(ctx)
        if (!settings.isConfigured) return Result.success()
        val giorno = LocalDate.parse(data)
        when (val l = IntervalsReader.leggi(settings.apiKey, "events", giorno, giorno)) {
          is Lettura.Ok -> {
            val eventi =
                runCatching { JsonParser.parseString(l.testo).asJsonArray.filter { it.isJsonObject }.map { it.asJsonObject } }
                    .getOrDefault(emptyList())
            json = RiepilogoParser.trova(eventi, data)?.toString()
            json?.let { db.salva(data, it) }
          }
          is Lettura.Errore -> Log.w(TAG, "Lettura eventi non riuscita: ${l.messaggio}")
        }
      }
      when (Attesa.passo(json != null, db.notificato(data), System.currentTimeMillis(), scadenza)) {
        Passo.NOTIFICA -> {
          val r = RiepilogoParser.leggi(json!!)
          if (r != null && db.segnaNotificato(data)) Notifica.mostra(ctx, r)
        }
        Passo.GIA_NOTIFICATO -> {}
        Passo.SCADUTO -> {
          Log.i(TAG, "Nessun riepilogo per $data entro 2 ore")
          db.segnaScaduta(data)
        }
        Passo.RIPROVA -> programma(ctx, data, scadenza, Attesa.INTERVALLO_MIN, ExistingWorkPolicy.APPEND_OR_REPLACE)
      }
    } catch (e: SQLException) {
      Log.e(TAG, "Database riepiloghi non disponibile", e)
      programma(ctx, data, scadenza, Attesa.INTERVALLO_MIN, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }
    return Result.success()
  }
}

/** Notifica locale del riepilogo. */
object Notifica {
  private const val CANALE = "riepilogo_coach"
  const val EXTRA_RIEPILOGO = "biosleep_riepilogo_data"

  fun mostra(context: Context, r: Riepilogo) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
      return // permesso negato: il riepilogo resta comunque nella schermata Oggi
    }
    val nm = context.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(
        NotificationChannel(CANALE, "Riepilogo del coach", NotificationManager.IMPORTANCE_DEFAULT).apply {
          description = "Decisione del mattino e seduta del giorno"
        })
    val apri =
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_RIEPILOGO, r.data)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val pi =
        PendingIntent.getActivity(
            context, r.data.hashCode(), apri, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val titolo = r.titoloNotifica ?: r.decisione.etichetta ?: "Riepilogo del coach"
    val testo = r.testoNotifica ?: r.decisione.motivo ?: ""
    val n =
        NotificationCompat.Builder(context, CANALE)
            .setSmallIcon(R.drawable.ic_notifica_biosleep) // luna + battito, res/drawable
            .setContentTitle(titolo)
            .setContentText(testo)
            .setStyle(NotificationCompat.BigTextStyle().bigText(testo))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
    NotificationManagerCompat.from(context).notify(r.data.hashCode(), n)
  }
}

/** Il tocco sulla notifica arriva a MainActivity: la data da aprire passa da qui alla navigazione. */
object RiepilogoLink {
  val richiesta = MutableStateFlow<String?>(null)

  fun daIntent(intent: Intent?) {
    val data = intent?.getStringExtra(Notifica.EXTRA_RIEPILOGO) ?: return
    intent.removeExtra(Notifica.EXTRA_RIEPILOGO) // non riaprire il riepilogo a ogni rotazione
    richiesta.value = data
  }
}

/** Dopo ogni aggiornamento della cache: salva il riepilogo di oggi se c'e' (senza notificare). */
object RiepilogoDaCache {
  fun salva(context: Context, oggi: LocalDate) {
    val data = oggi.toString()
    val db = RiepilogoDb.get(context)
    if (db.json(data) != null) return
    val json = RiepilogoParser.trova(CacheRepo.get(context).eventi(oggi, oggi), data) ?: return
    db.salva(data, json.toString())
  }
}
