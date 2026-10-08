package com.wboelens.polarrecorder.biosleep.cervello

import android.content.Context
import com.google.gson.JsonParser
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * "Tempo disponibile" per singola data, dal "+" del calendario: minuti (0 = non disponibile).
 * Le date passate si eliminano a ogni lettura. Passate al cervello in "disponibilita_date".
 */
object DisponibilitaDate {
  private const val PREFS = "noctalix_disponibilita_date"
  private const val CHIAVE = "date"

  private val _versione = MutableStateFlow(0)
  val versione: StateFlow<Int> = _versione.asStateFlow()

  fun tutte(context: Context): Map<String, Int> {
    val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val grezze =
        runCatching {
              JsonParser.parseString(p.getString(CHIAVE, "{}")).asJsonObject.entrySet().associate { (k, v) -> k to v.asInt }
            }
            .getOrDefault(emptyMap())
    val valide = PianoCalendario.disponibilita(grezze)
    if (valide.size != grezze.size) p.edit().putString(CHIAVE, PianoCalendario.json(valide).toString()).apply()
    return valide
  }

  /** minuti = null toglie la data (torna la disponibilita' della settimana tipo). */
  fun salva(context: Context, data: LocalDate, minuti: Int?) {
    val m = tutte(context).toMutableMap()
    if (minuti == null) m.remove(data.toString()) else m[data.toString()] = minuti.coerceIn(0, 600)
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CHIAVE, PianoCalendario.json(m).toString()).apply()
    _versione.update { it + 1 }
  }

  /** Per il configJson: null se non ce ne sono. */
  fun perCervello(context: Context): Map<String, Int>? = tutte(context).ifEmpty { null }
}
