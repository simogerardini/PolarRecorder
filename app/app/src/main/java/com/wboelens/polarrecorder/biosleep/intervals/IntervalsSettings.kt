package com.wboelens.polarrecorder.biosleep.intervals

import android.content.Context

/**
 * Impostazioni Intervals.icu salvate nella memoria privata dell'app.
 * Il file e' escluso dal backup su cloud (vedi res/xml/backup_rules.xml e data_extraction_rules.xml).
 */
class IntervalsSettings(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  var apiKey: String
    get() = prefs.getString(KEY_API, "") ?: ""
    set(value) {
      prefs.edit().putString(KEY_API, value.trim()).apply()
    }

  var autoUpload: Boolean
    get() = prefs.getBoolean(KEY_AUTO, true)
    set(value) {
      prefs.edit().putBoolean(KEY_AUTO, value).apply()
    }

  val isConfigured: Boolean
    get() = apiKey.isNotBlank()

  companion object {
    const val PREFS_NAME = "biosleep_intervals"
    private const val KEY_API = "api_key"
    private const val KEY_AUTO = "auto_upload"
  }
}
