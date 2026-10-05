package com.wboelens.polarrecorder.biosleep.intervals

import android.content.Context
import android.database.SQLException
import com.wboelens.polarrecorder.biosleep.SleepDb
import com.wboelens.polarrecorder.biosleep.cache.CacheSync
import com.wboelens.polarrecorder.biosleep.cervello.CoachWorker
import com.wboelens.polarrecorder.biosleep.cervello.NotificheCoach
import java.time.LocalDate

/** Collega database e Intervals: invia una notte e salva l'esito. Da chiamare fuori dal main thread. */
object IntervalsSync {
  fun syncNight(context: Context, sessionId: Long): IntervalsResult {
    val settings = IntervalsSettings(context)
    val credenziali = settings.credenziali ?: return IntervalsResult.Failed("Intervals.icu non collegato")
    val db = SleepDb.get(context)
    val night =
        try {
          db.loadNight(sessionId)
        } catch (e: SQLException) {
          return IntervalsResult.Failed("Errore del database: ${e.message}")
        } ?: return IntervalsResult.Failed("Notte non trovata")

    val result = IntervalsClient.uploadNight(IntervalsAuth.header(credenziali), night.summary, night.stages)
    try {
      val syncedAt = if (result is IntervalsResult.Ok) System.currentTimeMillis() else null
      db.markSync(sessionId, syncedAt, result.message)
    } catch (e: SQLException) {
      // l'esito resta comunque visibile nel log; non blocca nulla
    }
    if (result is IntervalsResult.Ok) {
      CacheSync.aggiornaInBackground(context, forza = true)
      // Il cervello del coach (Python nell'app) solo per la notte di oggi: reinviare una notte
      // vecchia non deve rifare il piano. Stessa data della wellness appena inviata.
      val data = IntervalsClient.morningDate(night.summary)
      if (data == LocalDate.now().toString()) {
        // Scelta B: il coach aspetta fino a 15 minuti i tag del mattino (parte prima se li salvi)
        CoachWorker.dopoNotte(context, data, CoachWorker.ATTESA_TAG_MS)
        NotificheCoach.chiediTag(context, data)
      }
    }
    return result
  }
}
