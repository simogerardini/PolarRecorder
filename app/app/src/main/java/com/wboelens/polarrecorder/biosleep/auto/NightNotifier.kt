package com.wboelens.polarrecorder.biosleep.auto

import com.wboelens.polarrecorder.biosleep.lingua.TestiSistema
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.wboelens.polarrecorder.biosleep.NightSummary
import com.wboelens.polarrecorder.biosleep.SleepStages
import com.wboelens.polarrecorder.biosleep.ui.fmt
import com.wboelens.polarrecorder.biosleep.ui.hm
import com.wboelens.polarrecorder.R
import com.wboelens.polarrecorder.biosleep.watchdog.AutoStopLog

/** Notifiche BioSleep: riepilogo del mattino e avvisi se l'avvio della notte non riesce. */
object NightNotifier {
  const val EXTRA_SESSION_ID = "biosleep_session_id"

  private const val CHANNEL_ID = "biosleep_summary"
  private const val SUMMARY_ID = 2001
  private const val ALERT_ID = 2002

  fun notifySummary(context: Context, s: NightSummary, stages: SleepStages?, intervalsLine: String?) {
    val hours = (s.endMs - s.startMs) / 3_600_000.0
    val first =
        if (stages != null && stages.tstMin > 0) {
          "Sonno ${hm(stages.tstMin)} · profondo ${hm(stages.deepMin)} · REM ${hm(stages.remMin)}"
        } else {
          "${fmt(hours, 1)} h registrate"
        }
    val text =
        "$first\nFC riposo ${fmt(s.restingHr)} · rMSSD ${fmt(s.rmssd, 1)} ms" +
            (intervalsLine?.let { "\n$it" } ?: "")
    post(context, SUMMARY_ID, "La tua notte", text, sessionId = s.sessionId, icona = R.drawable.ic_notifica_biosleep)
  }

  fun notifyAlert(context: Context, title: String, text: String) {
    post(context, ALERT_ID, title, text, icona = R.drawable.ic_notifica_avviso)
  }

  /**
   * Pubblica e scrive l'esito nel registro (files/biosleep_autostop.log): se una notifica non
   * compare, il registro dice perche' (permesso, canale spento, errore) invece di tacere.
   */
  private fun post(context: Context, id: Int, title: String, text: String, sessionId: Long? = null, icona: Int) {
    val nome = if (id == SUMMARY_ID) "riepilogo" else "avviso"
    if (
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      AutoStopLog.write(context, "Notifica $nome non mostrata: permesso notifiche non concesso")
      return // notifiche non permesse: si vede tutto comunque nell'app
    }
    ensureChannel(context)
    bloccata(context)?.let {
      AutoStopLog.write(context, "Notifica $nome non mostrata: $it")
      return
    }
    @Suppress("TooGenericExceptionCaught")
    try {
      pubblica(context, id, title, text, sessionId, icona)
      AutoStopLog.write(context, "Notifica $nome pubblicata")
    } catch (e: Exception) {
      // Un testo non traducibile non deve far perdere il riepilogo: si pubblica senza traduzione
      AutoStopLog.write(context, "Notifica $nome: errore ${e.javaClass.simpleName} (${e.message}), ripubblico semplice")
      runCatching { pubblica(context, id, title, text, sessionId, icona, traduci = false) }
          .onFailure { AutoStopLog.write(context, "Notifica $nome non mostrata: ${it.javaClass.simpleName}") }
    }
  }

  /** Motivo per cui Android non mostrerebbe la notifica, o null. */
  private fun bloccata(context: Context): String? {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return "notifiche dell'app disattivate"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val canale = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)
      if (canale != null && canale.importance == NotificationManager.IMPORTANCE_NONE) return "canale \"$CHANNEL_ID\" disattivato"
    }
    return null
  }

  private fun pubblica(
      context: Context, id: Int, title: String, text: String, sessionId: Long?, icona: Int, traduci: Boolean = true,
  ) {
    val launchIntent =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
          if (sessionId != null) {
            putExtra(EXTRA_SESSION_ID, sessionId)
          }
        }
    val openApp =
        PendingIntent.getActivity(
            context,
            id,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    val notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(icona)
            .setColor(ContextCompat.getColor(context, R.color.biosleep_notifica))
            .setContentTitle(if (traduci) TestiSistema.traduci(context, title) else title)
            .setContentText((if (traduci) TestiSistema.righe(context, text) else text).lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (traduci) TestiSistema.righe(context, text) else text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
    NotificationManagerCompat.from(context).notify(id, notification)
  }

  private fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
          NotificationChannel(CHANNEL_ID, TestiSistema.traduci(context, "Riepilogo notte"), NotificationManager.IMPORTANCE_DEFAULT)
      context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
  }
}
