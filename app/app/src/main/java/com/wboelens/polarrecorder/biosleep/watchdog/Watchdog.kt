package com.wboelens.polarrecorder.biosleep.watchdog

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.SQLException
import android.os.Build
import com.wboelens.polarrecorder.biosleep.SleepDb
import com.wboelens.polarrecorder.biosleep.auto.HabitLearner
import com.wboelens.polarrecorder.biosleep.sopravvivenza.RipresaNotte

/**
 * Watchdog della notte: un allarme ogni 10 minuti finche' c'e' una notte da proteggere.
 * - Servizio vivo: chiede il controllo (stop del mattino, battiti, riconnessione).
 * - Servizio morto durante la notte: lo riavvia, ricollega la fascia e riprende la stessa notte.
 * - Servizio morto al mattino o notte ormai finita: chiude e analizza la notte.
 * L'allarme e' esatto se l'utente lo consente (scatta anche in Doze all'ora precisa e permette
 * di riavviare il servizio in primo piano); altrimenti e' approssimato di qualche minuto.
 */
object Watchdog {
  const val INTERVALLO_MS = 10 * 60_000L
  private const val REQUEST = 3_101

  private fun intent(context: Context): PendingIntent =
      PendingIntent.getBroadcast(
          context, REQUEST, Intent(context, AutoStopAlarmReceiver::class.java),
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

  fun allarmiEsatti(context: Context): Boolean {
    val am = context.getSystemService(AlarmManager::class.java) ?: return false
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
  }

  fun programma(context: Context) {
    val am = context.getSystemService(AlarmManager::class.java) ?: return
    val at = System.currentTimeMillis() + INTERVALLO_MS
    if (allarmiEsatti(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(context))
    else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(context))
  }

  fun annulla(context: Context) {
    context.getSystemService(AlarmManager::class.java)?.cancel(intent(context))
  }

  /** Mattino secondo le abitudini apprese (o dalle 04:00 durante l'apprendimento). */
  fun mattino(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean =
      HabitLearner.isMorning(nowMs, HabitLearner.learn(SleepDb.get(context).nightTimes(), nowMs))

  /** Sessione aperta da riprendere (notte interrotta ma non finita), oppure null. */
  fun sessioneDaRiprendere(context: Context, nowMs: Long = System.currentTimeMillis()): Long? =
      try {
        val db = SleepDb.get(context)
        val mattino = mattino(context, nowMs)
        db.openSessions()
            .mapNotNull { id -> inizio(db, id)?.let { id to it } }
            .sortedByDescending { it.second }
            .firstOrNull { (id, start) -> RipresaNotte.daRiprendere(nowMs, start, db.lastBeatMs(id), mattino) }
            ?.first
      } catch (e: SQLException) {
        null
      }

  private fun inizio(db: SleepDb, sessionId: Long): Long? =
      db.readableDatabase
          .rawQuery("SELECT start_ms FROM sessions WHERE id = ?", arrayOf(sessionId.toString()))
          .use { c -> if (c.moveToNext()) c.getLong(0) else null }
}
