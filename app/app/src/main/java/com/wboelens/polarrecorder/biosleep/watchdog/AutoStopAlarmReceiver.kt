package com.wboelens.polarrecorder.biosleep.watchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.wboelens.polarrecorder.PolarRecorderApplication
import com.wboelens.polarrecorder.biosleep.finalize.NightFinalizeWorker
import com.wboelens.polarrecorder.biosleep.sopravvivenza.EventiNotte
import com.wboelens.polarrecorder.biosleep.sopravvivenza.TipoEvento
import com.wboelens.polarrecorder.services.RecordingService

/** Allarme del watchdog (vedi Watchdog): controlla, riavvia o chiude la notte. */
class AutoStopAlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val app = context.applicationContext as PolarRecorderApplication
    val pending = goAsync() // la lettura del database non va fatta sul main thread
    Thread {
      try {
        decidi(context.applicationContext, app)
      } finally {
        pending.finish()
      }
    }.start()
  }

  @Suppress("TooGenericExceptionCaught")
  private fun decidi(context: Context, app: PolarRecorderApplication) {
    if (app.isRecordingActive) {
      try {
        context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_CHECK_AUTOSTOP))
        return
      } catch (e: Exception) {
        AutoStopLog.write(context, "Watchdog: servizio non raggiungibile (${e.javaClass.simpleName})")
      }
    }
    val sessione = Watchdog.sessioneDaRiprendere(context)
    if (sessione == null) {
      AutoStopLog.write(context, "Watchdog: nessuna notte in corso da riprendere -> chiusura notti aperte")
      NightFinalizeWorker.enqueue(context, "watchdog")
      return
    }
    // Notte interrotta (servizio chiuso dal sistema): si riprende la stessa sessione
    Watchdog.programma(context) // il prossimo controllo, anche se il riavvio non riesce
    AutoStopLog.write(context, "Watchdog: servizio morto con la notte $sessione in corso -> riavvio")
    EventiNotte.registra(context, TipoEvento.RIPRESA, "watchdog")
    try {
      ContextCompat.startForegroundService(
          context, Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_RESUME_NIGHT))
    } catch (e: Exception) {
      // Senza allarmi esatti Android puo' negare l'avvio in primo piano da sfondo: si riprova
      // al prossimo allarme; i dati fino all'interruzione sono comunque salvati
      AutoStopLog.write(context, "Watchdog: riavvio negato (${e.javaClass.simpleName}): riprovo fra 10 minuti")
    }
  }
}
