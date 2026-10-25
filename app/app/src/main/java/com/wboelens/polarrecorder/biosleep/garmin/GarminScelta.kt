package com.wboelens.polarrecorder.biosleep.garmin

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.wboelens.polarrecorder.biosleep.NightSummary
import java.util.concurrent.TimeUnit

/**
 * Parte 11: sorgente della notte scelta dall'utente (una sola: la banda non mescola le sorgenti).
 * File "biosleep_garmin": niente segreti (la chiave sta in biosleep_intervals); sorgente_notte
 * entra nel backup dell'app.
 */
object GarminScelta {
  const val PREFS = "biosleep_garmin"
  private const val K_SORGENTE = "sorgente_notte"
  private const val K_STORICO = "storico_fatto_2"
  private const val K_ESITO = "ultimo_esito"
  private const val K_AVVISO = "avviso_chiave_giorno"
  private const val LAVORO = "noctalix-garmin-notte"
  private const val LAVORO_ORA = "noctalix-garmin-notte-ora"
  private const val LAVORO_STORICO = "noctalix-garmin-storico"

  private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun sorgente(c: Context): Int = prefs(c).getInt(K_SORGENTE, NightSummary.SORGENTE_FASCIA)

  /** Garmin scelto come sorgente e chiave presente. */
  // Parte 7: Garmin ha confermato per iscritto l'uso in un'app distribuita (chiave personale di ogni utente)
  fun attiva(c: Context): Boolean = sorgente(c) == NightSummary.SORGENTE_GARMIN && GarminSettings(c).collegato

  fun imposta(c: Context, sorgente: Int) {
    prefs(c).edit().putInt(K_SORGENTE, sorgente).apply()
    programma(c)
    if (attiva(c)) {
      subito(c)
      if (!prefs(c).getBoolean(K_STORICO, false)) avviaStorico(c)
    }
  }

  internal fun storicoFatto(c: Context) = prefs(c).edit().putBoolean(K_STORICO, true).apply()

  /** Ultimo esito per le impostazioni: "salvata:<giorno>", "attesa", "chiave", "limite", "errore". */
  fun esito(c: Context): String? = prefs(c).getString(K_ESITO, null)

  internal fun salvaEsito(c: Context, e: String) = prefs(c).edit().putString(K_ESITO, e).apply()

  /** true la prima volta in un giorno (avviso di chiave non valida una volta al giorno). */
  internal fun primoAvvisoDelGiorno(c: Context, giorno: String): Boolean {
    if (prefs(c).getString(K_AVVISO, null) == giorno) return false
    prefs(c).edit().putString(K_AVVISO, giorno).apply()
    return true
  }

  private val rete = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

  /** Controllo ogni ora (economico: senza chiamate se la notte di oggi c'e' gia'). Da onCreate dell'app. */
  fun programma(c: Context) {
    val wm = runCatching { WorkManager.getInstance(c) }.getOrNull() ?: return
    if (attiva(c) && !prefs(c).getBoolean(K_STORICO, false)) avviaStorico(c)
    if (attiva(c)) {
      wm.enqueueUniquePeriodicWork(LAVORO, ExistingPeriodicWorkPolicy.KEEP,
          PeriodicWorkRequestBuilder<GarminNotteWorker>(1, TimeUnit.HOURS).setConstraints(rete).build())
    } else {
      wm.cancelUniqueWork(LAVORO)
    }
  }

  /** Controllo immediato (all'apertura dell'app, come Oura). */
  fun subito(c: Context) {
    if (!attiva(c)) return
    runCatching {
      WorkManager.getInstance(c).enqueueUniqueWork(LAVORO_ORA, ExistingWorkPolicy.KEEP,
          OneTimeWorkRequestBuilder<GarminNotteWorker>().setConstraints(rete).build())
    }
  }

  /** Allo scollegamento: chiave cancellata, sorgente fascia; [cancellaNotti] = anche le notti Garmin del telefono. */
  fun scollega(c: Context, cancellaNotti: Boolean): Int {
    GarminSettings(c).scollega()
    imposta(c, NightSummary.SORGENTE_FASCIA)
    prefs(c).edit().remove(K_STORICO).remove(K_ESITO).remove(K_AVVISO).apply()
    if (!cancellaNotti) return 0
    val db = com.wboelens.polarrecorder.biosleep.SleepDb.get(c)
    val ids = db.sessioniDa(NightSummary.SOURCE_GARMIN)
    ids.forEach { db.deleteSession(it) }
    return ids.size
  }

  private fun avviaStorico(c: Context) {
    runCatching {
      WorkManager.getInstance(c).enqueueUniqueWork(LAVORO_STORICO, ExistingWorkPolicy.KEEP,
          OneTimeWorkRequestBuilder<GarminStoricoWorker>().setConstraints(rete).build())
    }
  }
}
