package com.wboelens.polarrecorder.managers

import android.content.Context
import android.content.SharedPreferences

data class PreferenceConfig<T>(val key: String, val defaultValue: T)

object Preferences {
  val RECORDING_NAME = PreferenceConfig("recording_name", "PolarRecording")
  val RECORDING_NAME_APPEND_TIMESTAMP = PreferenceConfig("recording_name_append_timestamp", true)
  val RECORDING_STOP_ON_DISCONNECT = PreferenceConfig("recording_stop_on_disconnect", false)
}

class PreferencesManager(context: Context) {
  companion object {
    private var PREF_NAME = "com.wboelens.polarrecorder.PREFS"
  }

  private val mPref: SharedPreferences =
      context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

  var recordingName: String
    get() =
        mPref.getString(Preferences.RECORDING_NAME.key, Preferences.RECORDING_NAME.defaultValue)!!
    set(name) {
      mPref.edit().putString(Preferences.RECORDING_NAME.key, name).apply()
    }

  var recordingNameAppendTimestamp: Boolean
    get() =
        mPref.getBoolean(
            Preferences.RECORDING_NAME_APPEND_TIMESTAMP.key,
            Preferences.RECORDING_NAME_APPEND_TIMESTAMP.defaultValue,
        )
    set(enabled) {
      mPref.edit().putBoolean(Preferences.RECORDING_NAME_APPEND_TIMESTAMP.key, enabled).apply()
    }

  var recordingStopOnDisconnect: Boolean
    get() =
        mPref.getBoolean(
            Preferences.RECORDING_STOP_ON_DISCONNECT.key,
            Preferences.RECORDING_STOP_ON_DISCONNECT.defaultValue,
        )
    set(name) {
      mPref.edit().putBoolean(Preferences.RECORDING_STOP_ON_DISCONNECT.key, name).apply()
    }
}
