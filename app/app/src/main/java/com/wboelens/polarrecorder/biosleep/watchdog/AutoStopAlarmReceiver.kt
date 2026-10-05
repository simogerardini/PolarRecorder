package com.wboelens.polarrecorder.biosleep.watchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wboelens.polarrecorder.biosleep.finalize.NightFinalizeWorker
import com.wboelens.polarrecorder.services.RecordingService

/**
 * Rete di sicurezza dello stop automatico: un allarme ogni 10 minuti (anche in Doze) chiede al
 * servizio di fare il controllo. Se il servizio non c'e' piu' (chiuso dal sistema), Android non
 * permette di riavviarlo da qui: si avvia invece la chiusura delle notti rimaste aperte.
 */
class AutoStopAlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    try {
      context.startService(
          Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_CHECK_AUTOSTOP))
    } catch (e: IllegalStateException) {
      AutoStopLog.write(context, "Allarme: servizio non attivo (${e.javaClass.simpleName}) -> chiusura notti aperte")
      NightFinalizeWorker.enqueue(context, "allarme senza servizio")
    } catch (e: SecurityException) {
      AutoStopLog.write(context, "Allarme: avvio servizio negato -> chiusura notti aperte")
      NightFinalizeWorker.enqueue(context, "allarme senza servizio")
    }
  }
}
