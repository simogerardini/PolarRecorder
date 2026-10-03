package com.wboelens.polarrecorder.biosleep.intervals

import android.content.Context
import android.database.SQLException
import com.wboelens.polarrecorder.biosleep.SleepDb
import com.wboelens.polarrecorder.biosleep.cache.CacheSync
import com.wboelens.polarrecorder.biosleep.coach.CoachAvvioWorker
import com.wboelens.polarrecorder.biosleep.riepilogo.RiepilogoWorker
import java.time.LocalDate

/** Collega database e Intervals: invia una notte e salva l'esito. Da chiamare fuori dal main thread. */
object IntervalsSync {
  fun syncNight(context: Context, sessionId: Long): IntervalsResult {
    val settings = IntervalsSettings(context)
    if (!settings.isConfigured) {
      return IntervalsResult.Failed("API key Intervals.icu non impostata")
    }
    val db = SleepDb.get(context)
    val night =
        try {
          db.loadNight(sessionId)
        } catch (e: SQLException) {
          return IntervalsResult.Failed("Errore del database: ${e.message}")
        } ?: return IntervalsResult.Failed("Notte non trovata")

    val result = IntervalsClient.uploadNight(settings.apiKey, night.summary, night.stages)
    try {
      val syncedAt = if (result is IntervalsResult.Ok) System.currentTimeMillis() else null
      db.markSync(sessionId, syncedAt, result.message)
    } catch (e: SQLException) {
      // l'esito resta comunque visibile nel log; non blocca nulla
    }
    if (result is IntervalsResult.Ok) {
      CacheSync.aggiornaInBackground(context, forza = true)
      // Coach e attesa del riepilogo solo per la notte di oggi: reinviare una notte vecchia non
      // deve avviare il coach ne' far aspettare un riepilogo. Stessa data della wellness inviata.
      val data = IntervalsClient.morningDate(night.summary)
      if (data == LocalDate.now().toString()) {
        CoachAvvioWorker.avvia(context, data)
        RiepilogoWorker.avvia(context, LocalDate.parse(data))
      }
    }
    return result
  }
}
