package com.wboelens.polarrecorder.biosleep.auto

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
    post(context, SUMMARY_ID, "La tua notte", text, s.sessionId)
  }

  fun notifyAlert(context: Context, title: String, text: String) {
    post(context, ALERT_ID, title, text, null)
  }

  private fun post(context: Context, id: Int, title: String, text: String, sessionId: Long? = null) {
    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
    ) {
      return // notifiche non permesse: si vede tutto comunque nell'app
    }
    ensureChannel(context)
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
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
    NotificationManagerCompat.from(context).notify(id, notification)
  }

  private fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
          NotificationChannel(CHANNEL_ID, "Riepilogo notte", NotificationManager.IMPORTANCE_DEFAULT)
      context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
  }
}
