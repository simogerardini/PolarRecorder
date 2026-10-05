package com.wboelens.polarrecorder.biosleep.riepilogo

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Riepiloghi del coach (riepilogo_<data>.json del cervello) e stato della notifica "Piano pronto",
 * in un file proprio (biosleep_riepiloghi.db), separato dalla cache che si ricostruisce a ogni
 * cambio di schema: "notificato" deve sopravvivere, altrimenti la notifica si ripeterebbe.
 */
class RiepilogoDb internal constructor(context: Context, nomeFile: String?) :
    SQLiteOpenHelper(context, nomeFile, null, VERSIONE) {

  companion object {
    private const val NOME = "biosleep_riepiloghi.db"
    private const val VERSIONE = 1
    private const val GG_STORICO = 120L

    @Volatile private var instance: RiepilogoDb? = null

    fun get(context: Context): RiepilogoDb =
        instance
            ?: synchronized(this) {
              instance ?: RiepilogoDb(context.applicationContext, NOME).also { instance = it }
            }

    private val _versione = MutableStateFlow(0)

    /** Cresce a ogni salvataggio o cambio di stato: le schermate la osservano e rileggono. */
    val versione: StateFlow<Int> = _versione.asStateFlow()
  }

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE riepiloghi(data TEXT PRIMARY KEY, json TEXT NOT NULL, " +
            "notificato INTEGER NOT NULL DEFAULT 0, salvato_ms INTEGER NOT NULL)")
    // tabella delle attese della vecchia NOTE riepilogo: non piu' usata, lasciata per non migrare
    db.execSQL(
        "CREATE TABLE attese(data TEXT PRIMARY KEY, inizio_ms INTEGER, scaduta INTEGER NOT NULL DEFAULT 0)")
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    // Versione 1: niente da migrare. Le versioni future dovranno MIGRARE, non cancellare.
  }

  private fun cambiato() = _versione.update { it + 1 }

  /** Salva (o aggiorna) il JSON del riepilogo di una data, senza toccare "notificato". */
  fun salva(data: String, json: String, adessoMs: Long = System.currentTimeMillis()) {
    val db = writableDatabase
    db.beginTransaction()
    try {
      val v = ContentValues().apply {
        put("json", json)
        put("salvato_ms", adessoMs)
      }
      // Niente UPSERT: su Android 8-10 la versione di SQLite non lo supporta
      if (db.update("riepiloghi", v, "data = ?", arrayOf(data)) == 0) {
        v.put("data", data)
        db.insert("riepiloghi", null, v)
      }
      val limite = LocalDate.parse(data).minusDays(GG_STORICO).toString()
      db.delete("riepiloghi", "data < ?", arrayOf(limite))
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    cambiato()
  }

  fun json(data: String): String? =
      readableDatabase.rawQuery("SELECT json FROM riepiloghi WHERE data = ?", arrayOf(data)).use { c ->
        if (c.moveToFirst()) c.getString(0) else null
      }

  fun leggi(data: String): Riepilogo? = json(data)?.let { RiepilogoParser.leggi(it) }

  /** Il riepilogo piu' recente (per gli avvisi sulla settimana tipo nella schermata Profilo). */
  fun ultimo(): Riepilogo? =
      readableDatabase.rawQuery("SELECT json FROM riepiloghi ORDER BY data DESC LIMIT 1", null).use { c ->
        if (c.moveToFirst()) RiepilogoParser.leggi(c.getString(0)) else null
      }

  /** true solo per chi passa da "non notificato" a "notificato": una notifica sola per data. */
  fun segnaNotificato(data: String): Boolean {
    val v = ContentValues().apply { put("notificato", 1L) }
    return writableDatabase.update("riepiloghi", v, "data = ? AND notificato = 0", arrayOf(data)) == 1
  }

  fun notificato(data: String): Boolean =
      readableDatabase.rawQuery("SELECT notificato FROM riepiloghi WHERE data = ?", arrayOf(data)).use { c ->
        c.moveToFirst() && c.getLong(0) == 1L
      }
}
