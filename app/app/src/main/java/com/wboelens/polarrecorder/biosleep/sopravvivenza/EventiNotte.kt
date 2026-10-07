package com.wboelens.polarrecorder.biosleep.sopravvivenza

import android.content.ContentValues
import android.content.Context
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * Eventi della notte (servizio avviato/chiuso, Bluetooth, fascia) e interruzioni calcolate.
 * Database separato da biosleep.db: non tocca lo schema delle notti.
 */
class EventiNotte private constructor(context: Context) :
    SQLiteOpenHelper(context, "biosleep_eventi.db", null, 2) {

  companion object {
    private const val TENUTA_MS = 30L * 86_400_000L // gli eventi servono solo per qualche notte

    @Volatile private var instance: EventiNotte? = null

    fun get(context: Context): EventiNotte =
        instance ?: synchronized(this) { instance ?: EventiNotte(context.applicationContext).also { instance = it } }

    /** Registra un evento; non lancia mai eccezioni (un evento perso non deve fermare nulla). */
    fun registra(context: Context, tipo: TipoEvento, dettaglio: String = "") {
      try {
        get(context).writableDatabase.insert(
            "eventi", null,
            ContentValues().apply {
              put("t_ms", System.currentTimeMillis())
              put("tipo", tipo.name)
              put("dettaglio", dettaglio)
            })
      } catch (e: SQLException) {
        Log.w("BioSleepEventi", "Evento $tipo non salvato: ${e.message}")
      }
    }
  }

  init {
    setWriteAheadLoggingEnabled(true)
  }

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL("CREATE TABLE eventi(t_ms INTEGER NOT NULL, tipo TEXT NOT NULL, dettaglio TEXT)")
    db.execSQL("CREATE INDEX idx_eventi_t ON eventi(t_ms)")
    db.execSQL(
        """CREATE TABLE interruzioni(session_id INTEGER PRIMARY KEY, minuti_persi INTEGER NOT NULL,
             descrizione TEXT, fine_notte_ms INTEGER NOT NULL)""")
    creaFascia(db)
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) creaFascia(db)
  }

  /** v2 (punto 10): fascia usata in ogni sessione, capacita' e qualita' degli RR. */
  private fun creaFascia(db: SQLiteDatabase) {
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS fascia_sessione(session_id INTEGER PRIMARY KEY,
             stato_rr TEXT NOT NULL, rapporto TEXT NOT NULL, aggiornato_ms INTEGER NOT NULL)""")
  }

  /** Salva (o aggiorna) fascia e qualita' RR di una sessione; rapporto = RapportoFascia.json(). */
  fun salvaFascia(sessionId: Long, statoRr: String, rapporto: String) {
    writableDatabase.insertWithOnConflict(
        "fascia_sessione", null,
        ContentValues().apply {
          put("session_id", sessionId)
          put("stato_rr", statoRr)
          put("rapporto", rapporto)
          put("aggiornato_ms", System.currentTimeMillis())
        },
        SQLiteDatabase.CONFLICT_REPLACE)
  }

  /** Stato degli RR registrato per la sessione (nome di StatoRr), null se sconosciuto. */
  fun statoRr(sessionId: Long): String? =
      readableDatabase
          .rawQuery("SELECT stato_rr FROM fascia_sessione WHERE session_id = ?", arrayOf(sessionId.toString()))
          .use { c -> if (c.moveToNext()) c.getString(0) else null }

  fun ultimoRapportoFascia(): String? =
      readableDatabase
          .rawQuery("SELECT rapporto FROM fascia_sessione ORDER BY aggiornato_ms DESC LIMIT 1", null)
          .use { c -> if (c.moveToNext()) c.getString(0) else null }

  fun eventiTra(daMs: Long, aMs: Long): List<EventoNotte> =
      readableDatabase
          .rawQuery(
              "SELECT t_ms, tipo, dettaglio FROM eventi WHERE t_ms BETWEEN ? AND ? ORDER BY t_ms",
              arrayOf(daMs.toString(), aMs.toString()))
          .use { c ->
            buildList {
              while (c.moveToNext()) {
                val tipo = TipoEvento.entries.firstOrNull { it.name == c.getString(1) } ?: continue
                add(EventoNotte(c.getLong(0), tipo, c.getString(2) ?: ""))
              }
            }
          }

  fun salvaInterruzioni(sessionId: Long, r: InterruzioniNotte, fineNotteMs: Long) {
    val db = writableDatabase
    db.insertWithOnConflict(
        "interruzioni", null,
        ContentValues().apply {
          put("session_id", sessionId)
          put("minuti_persi", r.minutiPersi)
          put("descrizione", r.testo())
          put("fine_notte_ms", fineNotteMs)
        },
        SQLiteDatabase.CONFLICT_REPLACE)
    db.delete("eventi", "t_ms < ?", arrayOf((System.currentTimeMillis() - TENUTA_MS).toString()))
  }
}
