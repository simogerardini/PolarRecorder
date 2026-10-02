package com.wboelens.polarrecorder.biosleep.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Ricontrollo periodico: la fascia e' indossata ma mancava l'orario o la carica. */
class AutoStartAlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    AutoStart.evaluateAsync(context, "ricontrollo")
  }
}
