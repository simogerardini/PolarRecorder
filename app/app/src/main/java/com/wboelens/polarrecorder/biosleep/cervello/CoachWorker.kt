package com.wboelens.polarrecorder.biosleep.cervello

import com.wboelens.polarrecorder.biosleep.training.Formato
import com.wboelens.polarrecorder.biosleep.riepilogo.TraduzioneMessaggi
import com.wboelens.polarrecorder.biosleep.lingua.TestiSistema
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wboelens.polarrecorder.MainActivity
import com.wboelens.polarrecorder.R
import com.wboelens.polarrecorder.biosleep.cache.CacheSync
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsSettings
import com.wboelens.polarrecorder.biosleep.riepilogo.Riepilogo
import com.wboelens.polarrecorder.biosleep.riepilogo.RiepilogoDb
import com.wboelens.polarrecorder.biosleep.riepilogo.RiepilogoLink
import com.wboelens.polarrecorder.biosleep.riepilogo.RiepilogoParser
import com.wboelens.polarrecorder.biosleep.tag.TagDb
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Esegue il cervello del coach. Esito "niente" (nessuna seduta da rimodulare) = "fatto".
 * Quando parte:
 * - subito dopo l'invio riuscito della notte di oggi (dopoNotte);
 * - alle 10:30 come ripiego, con senza_attesa = true (assicuraRipiego).
 * Nome unico "coach-<data>": mai due run dello stesso giorno in coda insieme. Esito "attesa"
 * (notte non ancora su Intervals.icu): riprova fra 15 minuti, al massimo 4 volte, poi ripiego.
 */
class CoachWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  companion object {
    private const val TAG = "BioSleepCoach"
    private const val K_DATA = "data"
    private const val K_TENTATIVO = "tentativo"
    private const val K_SENZA_ATTESA = "senza_attesa"
    private const val RIPROVA_MIN = 15L
    private const val MAX_RIPROVE = 4
    private val ORA_RIPIEGO: LocalTime = LocalTime.of(10, 30)
    private const val ID_NOTIFICA_LAVORO = 5_100

    private fun nome(data: String) = "coach-$data"

    private fun richiesta(data: String, tentativo: Int, senzaAttesa: Boolean, ritardoMs: Long) =
        OneTimeWorkRequestBuilder<CoachWorker>()
            .setInputData(
                Data.Builder()
                    .putString(K_DATA, data)
                    .putInt(K_TENTATIVO, tentativo)
                    .putBoolean(K_SENZA_ATTESA, senzaAttesa)
                    .build())
            .setInitialDelay(ritardoMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()

    /**
     * Dopo la PUT della wellness di oggi (risposta 200). Se il coach di oggi sta gia' girando non
     * fa nulla; altrimenti sostituisce il ripiego delle 10:30 in attesa e parte subito.
     * Da chiamare fuori dal main thread (legge lo stato di WorkManager).
     */
    fun dopoNotte(context: Context, data: String, ritardoMs: Long = 0) {
      val wm = WorkManager.getInstance(context)
      val inCorso =
          try {
            wm.getWorkInfosForUniqueWork(nome(data)).get().any { it.state == WorkInfo.State.RUNNING }
          } catch (e: ExecutionException) {
            false
          } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
          }
      if (inCorso) return
      wm.enqueueUniqueWork(nome(data), ExistingWorkPolicy.REPLACE, richiesta(data, 1, false, ritardoMs))
      CoachStato(context).attesaTagFinoMs = if (ritardoMs > 0) System.currentTimeMillis() + ritardoMs else 0L
    }

    /** Attesa dei tag del mattino dopo l'invio della notte (scelta B). */
    const val ATTESA_TAG_MS = 15 * 60_000L

    /**
     * Tag di oggi appena salvati: se il coach li stava aspettando, parte subito. Se il coach e'
     * gia' partito (o la notte non e' ancora inviata) non fa nulla: i tag valgono dal run dopo.
     * Fuori dal main thread. Ritorna true se il coach e' stato anticipato.
     */
    fun anticipaPerTag(context: Context, oggi: String = LocalDate.now().toString()): Boolean {
      val stato = CoachStato(context)
      if (System.currentTimeMillis() >= stato.attesaTagFinoMs) return false
      dopoNotte(context, oggi, 0)
      return true
    }

    /**
     * Ripiego delle 10:30 della prossima data utile: oggi se non sono ancora le 10:30 e il coach
     * di oggi non ha gia' finito, altrimenti domani. KEEP: non tocca un lavoro gia' in coda.
     */
    fun assicuraRipiego(context: Context, adesso: LocalDateTime = LocalDateTime.now()) {
      val oggi = adesso.toLocalDate()
      val giorno =
          if (adesso.toLocalTime() < ORA_RIPIEGO && !CoachStato(context).fatto(oggi.toString())) oggi
          else oggi.plusDays(1)
      val ritardo = Duration.between(adesso, giorno.atTime(ORA_RIPIEGO)).toMillis()
      WorkManager.getInstance(context)
          .enqueueUniqueWork(nome(giorno.toString()), ExistingWorkPolicy.KEEP, richiesta(giorno.toString(), 1, true, ritardo))
    }
  }

  override suspend fun doWork(): Result {
    val data = inputData.getString(K_DATA) ?: return Result.success()
    val tentativo = inputData.getInt(K_TENTATIVO, 1)
    val senzaAttesa = inputData.getBoolean(K_SENZA_ATTESA, false)
    val ctx = applicationContext
    NotificheCoach.canali(ctx)
    try {
      setForeground(infoPrimoPiano())
    } catch (e: IllegalStateException) {
      // Android 12+ non permette di passare in primo piano da sfondo (es. il ripiego delle 10:30
      // a telefono in tasca): il run continua come lavoro normale, entro i 10 minuti di WorkManager.
      Log.i(TAG, "Primo piano non consentito ora: run in background (${e.javaClass.simpleName})")
    }

    val settings = IntervalsSettings(ctx)
    val credenziali = settings.credenziali
    if (credenziali == null) {
      CoachStato(ctx).registra(data, RisultatoCervello(RisultatoCervello.ERRORE, emptyList(), null, null,
          "Intervals.icu non collegato"), 0, "auto")
      assicuraRipiego(ctx)
      return Result.success()
    }
    val config =
        ConfigCervello(
            credenziali, Cervello.cartella(ctx).absolutePath, "auto", false, senzaAttesa,
            profilo = ProfiloRepo.effettivo(ctx),
            tag = runCatching { TagDb.get(ctx).perCervello() }.getOrNull(),
            posizione = PosizioneTelefono.ultima(ctx),
                          disponibilitaDate = DisponibilitaDate.perCervello(ctx),
                          lingua = Lingua.effettiva(ctx))
    CoachStato(ctx).attesaTagFinoMs = 0L // il coach parte: i tag di adesso in poi valgono dal run dopo
    val t0 = System.currentTimeMillis()
    val r = withContext(Dispatchers.IO) { Cervello.esegui(ctx, config) }
    val durata = System.currentTimeMillis() - t0
    Log.i(TAG, "Coach $data (tentativo $tentativo, senza_attesa=$senzaAttesa): ${r.esito} in ${durata / 1000} s")
    CoachStato(ctx).registra(data, r, durata, "auto")

    val riepilogoLetto = r.riepilogoFile?.let { f -> runCatching { com.wboelens.polarrecorder.biosleep.riepilogo.RiepilogoParser.leggi(java.io.File(f).readText(Charsets.UTF_8)) }.getOrNull() }

    r.notifiche.forEach { NotificheCoach.testo(ctx, it, riepilogoLetto) }
    r.riepilogoFile?.let { percorso -> salvaRiepilogo(ctx, percorso) }

    when (r.esito) {
      RisultatoCervello.PIANIFICATA, RisultatoCervello.FATTO, RisultatoCervello.NIENTE ->
          CacheSync.aggiornaInBackground(ctx, forza = true) // il calendario mostra subito il piano
      RisultatoCervello.ATTESA -> {
        val wm = WorkManager.getInstance(ctx)
        if (tentativo < MAX_RIPROVE) {
          wm.enqueueUniqueWork(nome(data), ExistingWorkPolicy.APPEND_OR_REPLACE,
              richiesta(data, tentativo + 1, false, RIPROVA_MIN * 60_000))
        } else {
          // notte ancora assente: lascia fare al ripiego delle 10:30 (subito, se sono gia' passate)
          val ritardo = Duration.between(LocalDateTime.now(), LocalDate.parse(data).atTime(ORA_RIPIEGO)).toMillis()
          wm.enqueueUniqueWork(nome(data), ExistingWorkPolicy.APPEND_OR_REPLACE, richiesta(data, 1, true, ritardo))
        }
        return Result.success()
      }
      RisultatoCervello.ERRORE -> Log.w(TAG, "Coach non riuscito: ${r.errore} (log: ${r.logFile})")
    }
    assicuraRipiego(ctx)
    return Result.success()
  }

  private fun salvaRiepilogo(ctx: Context, percorso: String) {
    val testo =
        try {
          File(percorso).readText(Charsets.UTF_8)
        } catch (e: IOException) {
          Log.w(TAG, "Riepilogo non leggibile: ${e.message}")
          return
        }
    val r = RiepilogoParser.leggi(testo) ?: return
    val db = RiepilogoDb.get(ctx)
    db.salva(r.data, testo)
    if (db.segnaNotificato(r.data)) NotificheCoach.pianoPronto(ctx, r)
  }

  private fun infoPrimoPiano(): ForegroundInfo {
    val n =
        NotificationCompat.Builder(applicationContext, NotificheCoach.CANALE_LAVORO)
            .setSmallIcon(R.drawable.ic_notifica_biosleep)
            .setContentTitle(TestiSistema.traduci(applicationContext, "Coach al lavoro"))
            .setContentText(TestiSistema.traduci(applicationContext, "Analisi della notte e del piano"))
            .setOngoing(true)
            .setSilent(true)
            .build()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ForegroundInfo(ID_NOTIFICA_LAVORO, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      ForegroundInfo(ID_NOTIFICA_LAVORO, n)
    }
  }

  override suspend fun getForegroundInfo(): ForegroundInfo = infoPrimoPiano()
}

/** Notifiche del coach: canale "Coach" per gli avvisi, canale silenzioso per "Coach al lavoro". */
object NotificheCoach {
  const val CANALE = "coach"
  const val CANALE_LAVORO = "coach_lavoro"

  fun canali(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CANALE, TestiSistema.traduci(context, "Coach"), NotificationManager.IMPORTANCE_DEFAULT))
    nm.createNotificationChannel(
        NotificationChannel(CANALE_LAVORO, TestiSistema.traduci(context, "Coach al lavoro"), NotificationManager.IMPORTANCE_LOW))
  }

  private fun permesso(context: Context) =
      Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
          ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
              PackageManager.PERMISSION_GRANTED

  private fun apri(context: Context, data: String?): PendingIntent {
    val i =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    if (data != null) i.putExtra(RiepilogoLink.EXTRA_RIEPILOGO, data)
    return PendingIntent.getActivity(
        context, (data ?: "coach").hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  }

  private fun mostra(context: Context, id: Int, titolo: String, testo: String, data: String?) {
    if (!permesso(context)) return
    canali(context)
    val n =
        NotificationCompat.Builder(context, CANALE)
            .setSmallIcon(R.drawable.ic_notifica_biosleep)
            .setContentTitle(TestiSistema.traduci(context, titolo))
            .setContentText(TestiSistema.traduci(context, TraduzioneMessaggi.testo(context, testo)))
            .setStyle(NotificationCompat.BigTextStyle().bigText(TestiSistema.traduci(context, TraduzioneMessaggi.testo(context, testo))))
            .setContentIntent(apri(context, data))
            .setAutoCancel(true)
            .build()
    NotificationManagerCompat.from(context).notify(id, n)
  }

  /** Un elemento di "notifiche" del cervello (gli avvisi che prima andavano su Telegram). */
  fun testo(context: Context, testo: String, riepilogo: Riepilogo? = null) {
    // Resoconto settimanale: testo libero italiano del cervello (righe senza codice). In un'altra
    // lingua lo si ricompone dal riepilogo strutturato; in italiano resta quello completo.
    if (riepilogo != null && Lingua.effettiva(context) != "it" && testo.trimStart().startsWith("\uD83D\uDDD3")) {
      val obiettivo =
          listOfNotNull(riepilogo.fase, riepilogo.oreTarget?.let { "obiettivo settimana ${Formato.durata((it * 3600).toInt())}" })
              .joinToString(" · ")
      val righe =
          listOfNotNull(
                  riepilogo.decisione.etichetta,
                  riepilogo.decisione.motivo,
                  obiettivo.ifBlank { null },
                  "Piano della settimana: ${riepilogo.sedute.size} sedute",
                  riepilogo.biometria?.azione)
              .map { TestiSistema.traduci(context, it) }
      mostra(context, testo.hashCode(), TestiSistema.traduci(context, "\uD83D\uDDD3\uFE0F Piano settimanale"), righe.joinToString("\n"), riepilogo.data)
      return
    }
    // ogni riga nella lingua dell'app: intestazione e motivi arrivano dal cervello con un codice
    val righe =
        testo.trim().lines().map { riga ->
          val inizio = Regex("^\\s*[-•*]\\s*").find(riga)?.value ?: ""
          inizio + TestiSistema.traduci(context, riga.substring(inizio.length))
        }
    val titolo = righe.first().take(60)
    val corpo = righe.drop(1).joinToString("\n").trim().ifEmpty { righe.first() }
    mostra(context, testo.hashCode(), titolo, corpo, null)
  }

  /** Dopo l'invio della notte: il tocco apre i tag del mattino; il coach aspetta fino a 15 minuti. */
  fun chiediTag(context: Context, data: String) {
    if (!permesso(context)) return
    canali(context)
    val i =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(RiepilogoLink.EXTRA_ROTTA, "tag/$data")
    val pi = PendingIntent.getActivity(context, ("tag" + data).hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val n =
        NotificationCompat.Builder(context, CANALE)
            .setSmallIcon(R.drawable.ic_notifica_biosleep)
            .setContentTitle(TestiSistema.traduci(context, "Com'è andata la notte?"))
            .setContentText(TestiSistema.traduci(context, "Aggiungi i tag: il coach li aspetta 15 minuti"))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter(CoachWorker.ATTESA_TAG_MS)
            .build()
    NotificationManagerCompat.from(context).notify(("tag" + data).hashCode(), n)
  }

  /** "Piano pronto": il tocco apre la schermata Riepilogo di quella data. */
  fun pianoPronto(context: Context, r: Riepilogo) {
    val testo =
        if (r.settimanale) {
          val ore = r.oreTarget?.let { " · ${"%.1f".format(it)} h" } ?: ""
          "Piano della settimana: ${r.sedute.size} sedute$ore"
        } else {
          r.testo?.lines()?.firstOrNull { it.isNotBlank() } ?: "Seduta di oggi aggiornata"
        }
    mostra(context, ("piano" + r.data).hashCode(), "Piano pronto", testo, r.data)
  }
}
