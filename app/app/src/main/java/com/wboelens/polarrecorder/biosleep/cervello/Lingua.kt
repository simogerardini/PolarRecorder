package com.wboelens.polarrecorder.biosleep.cervello

import android.content.Context
import java.util.Locale

/**
 * Lingua scelta dall'utente, passata a ogni run del cervello ("lingua"): le sedute scritte su
 * Intervals.icu (nomi, note degli step, protocolli dei test) escono in quella lingua. Con "zh"
 * il cervello scrive in inglese, perche' molti orologi non mostrano i caratteri cinesi.
 */
object Lingua {
  /** Codice -> nome mostrato (ogni lingua nel proprio nome; il cinese non si traslittera). */
  val LINGUE = listOf("it" to "Italiano", "en" to "English", "es" to "Español", "zh" to "中文")

  private const val PREFS = "noctalix_lingua"
  private const val CHIAVE = "lingua"

  /** Lingua del telefono se supportata, altrimenti inglese. */
  fun daLocale(lingua: String): String = lingua.lowercase().takeIf { l -> LINGUE.any { it.first == l } } ?: "en"

  /** null = automatica (segue il telefono). */
  fun scelta(context: Context): String? =
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CHIAVE, null)?.takeIf { l -> LINGUE.any { it.first == l } }

  fun effettiva(context: Context): String = scelta(context) ?: daLocale(Locale.getDefault().language)

  fun salva(context: Context, codice: String?) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
      if (codice == null) remove(CHIAVE) else putString(CHIAVE, codice)
    }.apply()
  }

  /** Il cervello scrive il calendario in inglese per il cinese. */
  fun calendarioInInglese(codice: String) = codice == "zh"
}
