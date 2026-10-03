package com.wboelens.polarrecorder.biosleep.cache

import android.content.Context
import android.database.SQLException
import android.util.Log
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsSettings
import com.wboelens.polarrecorder.biosleep.riepilogo.RiepilogoDaCache
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Stato della cache per le schermate.
 * versione cresce a ogni aggiornamento riuscito: le schermate la osservano e rileggono il database.
 */
data class StatoCache(
    val aggiornatoMs: Long? = null,
    val errore: String? = null,
    val inCorso: Boolean = false,
    val versione: Int = 0,
)

/**
 * Aggiorna la cache da Intervals.icu. Chiamato:
 * - all'apertura dell'app (MainActivity.onStart), al massimo ogni 10 minuti;
 * - dopo l'invio riuscito di una notte (IntervalsSync), sempre (forza = true).
 * Se una lettura fallisce non si scrive nulla: resta l'ultima fotografia completa, coerente.
 */
object CacheSync {
  private const val TAG = "BioSleepCache"
  private const val MIN_INTERVALLO_MS = 10 * 60_000L
  const val META_ULTIMO_OK = "ultimo_ok_ms"
  const val META_ULTIMO_ERRORE = "ultimo_errore"

  // Un solo thread: due aggiornamenti non si sovrappongono mai.
  private val esecutore = Executors.newSingleThreadExecutor { r -> Thread(r, "biosleep-cache") }
  // Al massimo uno in corso + uno in attesa: gli altri inviti sono superflui.
  private val inAttesa = AtomicInteger(0)

  private val _stato = MutableStateFlow(StatoCache())
  val stato: StateFlow<StatoCache> = _stato.asStateFlow()

  fun aggiornaInBackground(context: Context, forza: Boolean = false) {
    val app = context.applicationContext
    if (inAttesa.incrementAndGet() > 2) {
      inAttesa.decrementAndGet()
      return
    }
    esecutore.execute {
      try {
        aggiornaSeServe(app, forza)
      } catch (e: RuntimeException) {
        Log.e(TAG, "Aggiornamento cache interrotto", e) // mai far cadere l'app per la cache
        _stato.update { it.copy(inCorso = false, errore = "errore imprevisto: ${e.message}") }
      } finally {
        inAttesa.decrementAndGet()
      }
    }
  }

  private fun aggiornaSeServe(app: Context, forza: Boolean) {
    val db = CacheDb.get(app)
    val ultimoOk = leggiMeta(db, META_ULTIMO_OK)?.toLongOrNull()
    if (_stato.value.aggiornatoMs == null && ultimoOk != null) {
      _stato.update { it.copy(aggiornatoMs = ultimoOk) } // dopo un riavvio dell'app
    }
    if (!forza && ultimoOk != null && System.currentTimeMillis() - ultimoOk < MIN_INTERVALLO_MS) return
    val settings = IntervalsSettings(app)
    if (!settings.isConfigured) {
      _stato.update { it.copy(errore = "API key Intervals.icu non impostata") }
      return
    }
    val oggi = LocalDate.now()
    if (aggiorna(db, { risorsa, da, a -> IntervalsReader.leggi(settings.apiKey, risorsa, da, a) }, oggi) == null) {
      // Riepilogo del coach di oggi, se gia' pubblicato: salvato per la home, senza notifica
      try {
        RiepilogoDaCache.salva(app, oggi)
      } catch (e: SQLException) {
        Log.w(TAG, "Riepilogo non salvato: ${e.message}")
      }
    }
  }

  /**
   * Una lettura completa: tre GET, poi un'unica scrittura. Ritorna null se riuscita, altrimenti
   * il messaggio d'errore. Il lettore e' un parametro per poterlo sostituire nei test.
   */
  fun aggiorna(
      db: CacheDb,
      lettore: (risorsa: String, da: LocalDate, a: LocalDate) -> Lettura,
      oggi: LocalDate,
      adessoMs: Long = System.currentTimeMillis(),
  ): String? {
    _stato.update { it.copy(inCorso = true) }
    val f = Finestre(oggi)
    val richieste =
        listOf(
            Triple(Tabella.WELLNESS, "wellness", f.storicoDa to oggi),
            Triple(Tabella.EVENTI, "events", f.storicoDa to f.eventiA),
            Triple(Tabella.ATTIVITA, "activities", f.storicoDa to oggi),
        )
    val blocchi = ArrayList<Blocco>()
    for ((tabella, risorsa, finestra) in richieste) {
      val (da, a) = finestra
      val righe =
          when (val l = lettore(risorsa, da, a)) {
            is Lettura.Errore -> return fallito(db, l.messaggio)
            is Lettura.Ok ->
                try {
                  CachePayload.righe(l.testo, tabella)
                } catch (e: RispostaNonValida) {
                  return fallito(db, "$risorsa: ${e.message}")
                }
          }
      val conserva = if (tabella == Tabella.EVENTI) oggi.toString() else null
      blocchi.add(Blocco(tabella, da.toString(), a.toString(), righe, conserva))
    }
    try {
      db.sostituisci(blocchi, mapOf(META_ULTIMO_OK to adessoMs.toString(), META_ULTIMO_ERRORE to ""))
    } catch (e: SQLException) {
      return fallito(db, "database: ${e.message}")
    }
    Log.i(TAG, "Cache aggiornata: " + blocchi.joinToString { "${it.tabella.sql} ${it.righe.size}" })
    _stato.update { StatoCache(aggiornatoMs = adessoMs, errore = null, inCorso = false, versione = it.versione + 1) }
    return null
  }

  private fun fallito(db: CacheDb, messaggio: String): String {
    Log.w(TAG, "Cache non aggiornata: $messaggio")
    try {
      db.scriviMeta(META_ULTIMO_ERRORE, messaggio)
    } catch (e: SQLException) {
      // l'errore resta comunque nello stato e nel log
    }
    _stato.update { it.copy(inCorso = false, errore = messaggio) }
    return messaggio
  }

  private fun leggiMeta(db: CacheDb, chiave: String): String? =
      try {
        db.meta(chiave)
      } catch (e: SQLException) {
        null
      }
}
