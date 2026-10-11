package com.wboelens.polarrecorder.biosleep.cervello

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Quanto il coach aspetta i tag del mattino dopo l'invio della notte (scelta dell'utente).
 * 0 = subito: il coach parte senza aspettare e la notifica dei tag non viene mostrata.
 * Lo stesso valore regola il ritardo del CoachWorker, il testo e la durata della notifica.
 */
object AttesaTag {
  val OPZIONI = listOf(0, 5, 10, 15, 30)
  const val PREDEFINITO = 15
  private const val PREFS = "biosleep_coach_attesa"
  private const val CHIAVE = "attesa_tag_min"
  private var flusso: MutableStateFlow<Int>? = null

  fun minuti(context: Context): Int =
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(CHIAVE, PREDEFINITO).takeIf { it in OPZIONI }
          ?: PREDEFINITO

  fun ms(context: Context): Long = minuti(context) * 60_000L

  fun imposta(context: Context, m: Int) {
    if (m !in OPZIONI) return
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(CHIAVE, m).apply()
    flusso?.value = m
  }

  fun flusso(context: Context): StateFlow<Int> =
      flusso ?: MutableStateFlow(minuti(context.applicationContext)).also { flusso = it }
}
