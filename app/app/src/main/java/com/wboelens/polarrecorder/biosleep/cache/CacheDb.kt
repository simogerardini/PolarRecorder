package com.wboelens.polarrecorder.biosleep.cache

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Le tre tabelle della cache, con i nomi SQL ammessi (nessun nome di tabella arriva da fuori). */
enum class Tabella(val sql: String) {
  WELLNESS("wellness"),
  EVENTI("events"),
  ATTIVITA("activities"),
}

/**
 * Una riga della cache: chiave, giorno (YYYY-MM-DD) e l'oggetto JSON di Intervals.icu intero.
 * conserva = true: riga da tenere anche quando Intervals.icu non la restituisce piu', se e' di
 * un giorno passato (le NOTE specchio delle sedute su Garmin, che il coach cancella il giorno dopo).
 */
data class RigaCache(val id: String, val data: String, val json: String, val conserva: Boolean = false)

/**
 * Il contenuto di una tabella per una finestra di giorni [da, a], da sostituire in blocco.
 * Le righe con conserva = 1 e giorno precedente a conservaPrimaDi restano anche se assenti.
 */
data class Blocco(
    val tabella: Tabella,
    val da: String,
    val a: String,
    val righe: List<RigaCache>,
    val conservaPrimaDi: String? = null,
)

/**
 * Cache locale dei dati letti da Intervals.icu, in un file SEPARATO da biosleep.db.
 *
 * Perche' un file a parte: le notti registrate sono dati che esistono solo sul telefono, la cache
 * no (si ricostruisce con una lettura). Tenendole separate, un cambio di schema della cache si
 * risolve cancellandola e rileggendo, senza mai toccare le notti.
 *
 * Perche' il JSON intero e non colonne: le funzioni allineate al coach (banda, forma) leggono il
 * JSON con la stessa distinzione intero/decimale di Python (PyJson). Conservare il testo originale
 * garantisce che leggano esattamente cio' che legge il coach, e un campo nuovo di Intervals.icu
 * non richiede di cambiare lo schema.
 */
class CacheDb internal constructor(context: Context, nomeFile: String?) :
    SQLiteOpenHelper(context, nomeFile, null, DB_VERSION) {

  companion object {
    private const val DB_NAME = "biosleep_cache.db"
    // 2 (03/10/2026): colonna "conserva" per le NOTE specchio passate. La cache si ricrea.
    private const val DB_VERSION = 2

    @Volatile private var instance: CacheDb? = null

    fun get(context: Context): CacheDb =
        instance
            ?: synchronized(this) {
              instance ?: CacheDb(context.applicationContext, DB_NAME).also { instance = it }
            }
  }

  init {
    // WAL: l'aggiornamento in background scrive mentre una schermata legge, senza bloccarla
    setWriteAheadLoggingEnabled(true)
  }

  override fun onCreate(db: SQLiteDatabase) {
    for (t in Tabella.entries) {
      db.execSQL(
          "CREATE TABLE ${t.sql}(id TEXT PRIMARY KEY, data TEXT NOT NULL, json TEXT NOT NULL, " +
              "conserva INTEGER NOT NULL DEFAULT 0)")
      db.execSQL("CREATE INDEX idx_${t.sql}_data ON ${t.sql}(data)")
    }
    db.execSQL("CREATE TABLE meta(chiave TEXT PRIMARY KEY, valore TEXT)")
  }

  /** Cache ricostruibile: a ogni cambio di versione si cancella e si rilegge da Intervals.icu. */
  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = ricrea(db)

  override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = ricrea(db)

  private fun ricrea(db: SQLiteDatabase) {
    for (t in Tabella.entries) db.execSQL("DROP TABLE IF EXISTS ${t.sql}")
    db.execSQL("DROP TABLE IF EXISTS meta")
    onCreate(db)
  }

  /**
   * Sostituisce in un'unica transazione le finestre lette e aggiorna i metadati: o tutto o niente.
   * Dentro ogni finestra si cancella e si reinserisce, cosi' una seduta tolta dal coach sparisce
   * anche dall'app; prima della finestra si cancella tutto, cosi' la cache non cresce all'infinito.
   * Eccezione: le righe "conserva" di giorni passati restano finche' escono dalla finestra.
   */
  fun sostituisci(blocchi: List<Blocco>, meta: Map<String, String>) {
    val db = writableDatabase
    db.beginTransaction()
    try {
      for (b in blocchi) {
        val t = b.tabella.sql
        db.delete(
            t,
            "data < ? OR (data BETWEEN ? AND ? AND NOT (conserva = 1 AND data < ?))",
            arrayOf(b.da, b.da, b.a, b.conservaPrimaDi ?: ""))
        for (r in b.righe) {
          val v = ContentValues().apply {
            put("id", r.id)
            put("data", r.data)
            put("json", r.json)
            put("conserva", if (r.conserva) 1L else 0L)
          }
          db.insertWithOnConflict(t, null, v, SQLiteDatabase.CONFLICT_REPLACE)
        }
      }
      for ((k, valore) in meta) scriviMeta(db, k, valore)
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  /** Oggetti JSON della tabella con giorno in [da, a], in ordine di giorno. */
  fun leggi(tabella: Tabella, da: String, a: String): List<String> {
    val out = ArrayList<String>()
    readableDatabase
        .rawQuery(
            "SELECT json FROM ${tabella.sql} WHERE data BETWEEN ? AND ? ORDER BY data, id", arrayOf(da, a))
        .use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
    return out
  }

  fun meta(chiave: String): String? =
      readableDatabase.rawQuery("SELECT valore FROM meta WHERE chiave = ?", arrayOf(chiave)).use { c ->
        if (c.moveToFirst()) c.getString(0) else null
      }

  fun scriviMeta(chiave: String, valore: String) = scriviMeta(writableDatabase, chiave, valore)

  private fun scriviMeta(db: SQLiteDatabase, chiave: String, valore: String) {
    val v = ContentValues().apply {
      put("chiave", chiave)
      put("valore", valore)
    }
    db.insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE)
  }
}
