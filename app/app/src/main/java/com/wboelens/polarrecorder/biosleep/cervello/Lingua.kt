package com.wboelens.polarrecorder.biosleep.cervello

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Lingua dell'app e del coach. Da Android 13 e' la lingua scelta per l'app (per-app language):
 * cambia l'interfaccia e va al cervello ("lingua"), che con "zh" scrive le sedute in inglese
 * (molti orologi non mostrano i caratteri cinesi). Prima di Android 13 l'interfaccia segue il
 * telefono e la scelta vale solo per le sedute.
 */
object Lingua {
  /** Codice -> nome mostrato (ogni lingua nel proprio nome; il cinese non si traslittera). */
  val LINGUE = listOf("it" to "Italiano", "en" to "English", "es" to "Español", "zh" to "中文")

  private const val PREFS = "noctalix_lingua"
  private const val CHIAVE = "lingua"
  private const val CHIEDI = "chiedi_ripianifica"

  /** Lingua del telefono se supportata, altrimenti inglese. */
  fun daLocale(lingua: String): String = lingua.lowercase().takeIf { l -> LINGUE.any { it.first == l } } ?: "en"

  /** Tag per Android: il cinese e' semplificato (zh-CN). */
  fun tag(codice: String) = if (codice == "zh") "zh-CN" else codice

  fun perAppDisponibile() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

  /** Lingua scelta per l'app (Android 13+); null = come il telefono. */
  private fun linguaApp(context: Context): String? {
    if (!perAppDisponibile()) return null
    val l = context.getSystemService(LocaleManager::class.java)?.applicationLocales
    return if (l == null || l.isEmpty) null else daLocale(l.get(0).language)
  }

  /** null = automatica (segue il telefono). */
  fun scelta(context: Context): String? =
      linguaApp(context)
          ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CHIAVE, null)?.takeIf { l -> LINGUE.any { it.first == l } }

  fun effettiva(context: Context): String = scelta(context) ?: daLocale(Locale.getDefault().language)

  /**
   * Salva la scelta e, da Android 13, cambia la lingua dell'app: Android ricrea l'Activity. La
   * domanda "aggiorno il piano?" resta in sospeso in [ripianificaInSospeso] per dopo la ricreazione.
   */
  fun salva(context: Context, codice: String?, chiediRipianifica: Boolean) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
      if (codice == null) remove(CHIAVE) else putString(CHIAVE, codice)
      putBoolean(CHIEDI, chiediRipianifica)
    }.commit()
    if (perAppDisponibile()) {
      context.getSystemService(LocaleManager::class.java)?.applicationLocales =
          if (codice == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag(codice))
    }
  }

  /** Legge e azzera la domanda in sospeso dopo un cambio di lingua. */
  fun ripianificaInSospeso(context: Context): Boolean {
    val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val si = p.getBoolean(CHIEDI, false)
    if (si) p.edit().putBoolean(CHIEDI, false).apply()
    return si
  }

  /** Il cervello scrive il calendario in inglese per il cinese. */
  fun calendarioInInglese(codice: String) = codice == "zh"
}
