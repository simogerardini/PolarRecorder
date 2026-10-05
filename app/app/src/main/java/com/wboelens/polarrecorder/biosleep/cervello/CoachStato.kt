package com.wboelens.polarrecorder.biosleep.cervello

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Ultimo run del coach, per le impostazioni: esito, durata, data, log. */
class CoachStato(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences("biosleep_coach_stato", Context.MODE_PRIVATE)

  val esito: String? get() = prefs.getString(K_ESITO, null)
  val data: String? get() = prefs.getString(K_DATA, null)
  val durataMs: Long get() = prefs.getLong(K_DURATA, 0L)
  val logFile: String? get() = prefs.getString(K_LOG, null)
  val errore: String? get() = prefs.getString(K_ERRORE, null)
  val modo: String? get() = prefs.getString(K_MODO, null)

  /** Ultima misura di un run "settimanale" (anche di prova), in ms. */
  val durataSettimanaleMs: Long get() = prefs.getLong(K_DURATA_SETT, 0L)

  fun registra(data: String, r: RisultatoCervello, durataMs: Long, modo: String) {
    prefs.edit().apply {
      putString(K_DATA, data)
      putString(K_ESITO, r.esito)
      putLong(K_DURATA, durataMs)
      putString(K_LOG, r.logFile)
      putString(K_ERRORE, r.errore)
      putString(K_MODO, modo)
      if (r.esito == RisultatoCervello.PIANIFICATA) putLong(K_DURATA_SETT, durataMs)
    }.apply()
    _versione.update { it + 1 }
  }

  /** Run di prova (settimanale, senza scrivere): registra solo la durata, non tocca lo stato del giorno. */
  fun registraProva(durataMs: Long) {
    prefs.edit().putLong(K_DURATA_SETT, durataMs).apply()
    _versione.update { it + 1 }
  }

  /** Il coach di questa data ha gia' fatto il suo lavoro: il ripiego delle 10:30 non serve. */
  fun fatto(data: String) =
      this.data == data &&
          esito in
              setOf(
                  RisultatoCervello.PIANIFICATA, RisultatoCervello.FATTO, RisultatoCervello.NIENTE,
                  RisultatoCervello.GIA_FATTO)

  companion object {
    private const val K_ESITO = "esito"
    private const val K_DATA = "data"
    private const val K_DURATA = "durata_ms"
    private const val K_LOG = "log_file"
    private const val K_ERRORE = "errore"
    private const val K_MODO = "modo"
    private const val K_DURATA_SETT = "durata_settimanale_ms"

    private val _versione = MutableStateFlow(0)
    val versione: StateFlow<Int> = _versione.asStateFlow()
  }
}
