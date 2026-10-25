package com.wboelens.polarrecorder.biosleep.garmin

import android.content.Context
import com.wboelens.polarrecorder.biosleep.intervals.Cifratura
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsSettings

/**
 * Parte 11: credenziali Garmin Connect (Personal API). Stanno nello stesso file delle
 * credenziali Intervals.icu (biosleep_intervals), gia' escluso dal backup su cloud e dal
 * backup/trasferimento dell'app, cifrate con la chiave dell'Android Keystore.
 * Termini Garmin: la chiave e' personale e non va condivisa con terzi -> non lascia mai il
 * telefono (niente Worker, niente backup, niente collegamento web) e non finisce nei log.
 */
class GarminSettings(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(IntervalsSettings.PREFS_NAME, Context.MODE_PRIVATE)

  /** Personal API key ("" se non collegato). */
  var chiave: String
    get() = prefs.getString(K_CHIAVE, null)?.let { Cifratura.decifra(it) } ?: ""
    set(value) {
      val v = value.trim()
      prefs.edit().apply {
        if (v.isEmpty()) remove(K_CHIAVE) else putString(K_CHIAVE, Cifratura.cifra(v))
        // chiave nuova: il token della vecchia non vale piu'
        remove(K_TOKEN).remove(K_TOKEN_EXP)
      }.apply()
    }

  val collegato: Boolean
    get() = prefs.contains(K_CHIAVE)

  /** Token di accesso (24 ore) e sua scadenza in secondi epoch (claim exp del JWT). */
  internal var token: String
    get() = prefs.getString(K_TOKEN, null)?.let { Cifratura.decifra(it) } ?: ""
    set(value) {
      prefs.edit().apply { if (value.isEmpty()) remove(K_TOKEN) else putString(K_TOKEN, Cifratura.cifra(value)) }.apply()
    }

  internal var tokenExp: Long
    get() = prefs.getLong(K_TOKEN_EXP, 0L)
    set(value) {
      prefs.edit().putLong(K_TOKEN_EXP, value).apply()
    }

  /** Limite di frequenza (HTTP 429): niente chiamate fino a questo istante (ms). */
  internal var pausaFinoMs: Long
    get() = prefs.getLong(K_PAUSA, 0L)
    set(value) {
      prefs.edit().putLong(K_PAUSA, value).apply()
    }

  fun scollega() {
    prefs.edit().remove(K_CHIAVE).remove(K_TOKEN).remove(K_TOKEN_EXP).remove(K_PAUSA).apply()
  }

  companion object {
    private const val K_CHIAVE = "garmin_chiave_cifrata"
    private const val K_TOKEN = "garmin_token_cifrato"
    private const val K_TOKEN_EXP = "garmin_token_exp"
    private const val K_PAUSA = "garmin_pausa_fino_ms"
  }
}
