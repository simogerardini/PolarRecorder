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
 * Riepiloghi del coach e stato delle notifiche, in un file proprio (biosleep_riepiloghi.db).
 *
 * Perche' non nella cache: il coach cancella la NOTE del giorno prima, quindi un riepilogo passato
 * non si puo' rileggere da Intervals.icu; la cache invece si cancella e si ricostruisce a ogni
 * cambio di schema. Anche "notificato" deve sopravvivere, altrimenti la notifica si ripeterebbe.
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
      db.delete("attese", "data < ?", arrayOf(limite))
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

  /** true solo per chi passa da "non notificato" a "notificato": una notifica sola per data. */
  fun segnaNotificato(data: String): Boolean {
    val v = ContentValues().apply { put("notificato", 1L) }
    return writableDatabase.update("riepiloghi", v, "data = ? AND notificato = 0", arrayOf(data)) == 1
  }

  fun notificato(data: String): Boolean =
      readableDatabase.rawQuery("SELECT notificato FROM riepiloghi WHERE data = ?", arrayOf(data)).use { c ->
        c.moveToFirst() && c.getLong(0) == 1L
      }

  /** Nuova attesa (notte appena inviata): riparte da adesso, azzerando un "scaduta" precedente. */
  fun iniziaAttesa(data: String, adessoMs: Long = System.currentTimeMillis()) {
    val v = ContentValues().apply {
      put("data", data)
      put("inizio_ms", adessoMs)
      put("scaduta", 0L)
    }
    writableDatabase.insertWithOnConflict("attese", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    cambiato()
  }

  fun segnaScaduta(data: String) {
    val v = ContentValues().apply { put("scaduta", 1L) }
    writableDatabase.update("attese", v, "data = ?", arrayOf(data))
    cambiato()
  }

  /**
   * Attesa finita senza riepilogo. Vale anche se l'ultimo giro non e' mai partito (telefono
   * senza rete, WorkManager rimandato): passate 2 ore dall'inizio, l'attesa e' comunque scaduta.
   */
  fun scaduta(data: String, adessoMs: Long = System.currentTimeMillis()): Boolean =
      readableDatabase.rawQuery("SELECT inizio_ms, scaduta FROM attese WHERE data = ?", arrayOf(data)).use { c ->
        c.moveToFirst() && (c.getLong(1) == 1L || (!c.isNull(0) && adessoMs - c.getLong(0) >= Attesa.DURATA_MS))
      }
}
