package com.wboelens.polarrecorder.biosleep

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.wboelens.polarrecorder.biosleep.auto.NightTimes

/**
 * Una riga della tabella rr: un battito, con l'orario di ricezione del suo pacchetto.
 * rrMs negativo = battito segnalato non valido dal sensore (conta solo per la linea del tempo).
 */
data class RrRow(val sessionId: Long, val phoneMs: Long, val rrMs: Int)

/**
 * Una notte nell'elenco "Le mie notti".
 * syncedAt: quando e' stata inviata a Intervals.icu (null = non inviata); syncStatus: ultimo esito.
 */
data class NightListItem(
    val sessionId: Long,
    val name: String,
    val summary: NightSummary,
    val syncedAt: Long? = null,
    val syncStatus: String? = null,
    val stages: SleepStages? = null,
)

/** Un secondo di accelerometro aggregato. */
data class AccRow(
    val sessionId: Long,
    val tSec: Long,
    val activity: Int,
    val gx: Int,
    val gy: Int,
    val gz: Int,
)

/** Una sessione salvata (una registrazione per un dispositivo). */
data class SessionInfo(
    val id: Long,
    val name: String,
    val deviceId: String,
    val source: String,
    val startMs: Long,
    val endMs: Long?,
)

/**
 * Database locale dell'app (file "biosleep.db" nella memoria privata dell'app).
 * Usa SQLiteOpenHelper, gia' incluso in Android: nessuna libreria da aggiungere.
 */
class SleepDb private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

  companion object {
    private const val DB_NAME = "biosleep.db"
    private const val DB_VERSION = 7

    @Volatile private var instance: SleepDb? = null

    /** Una sola istanza per tutta l'app (servizio in background e schermate). */
    fun get(context: Context): SleepDb =
        instance
            ?: synchronized(this) {
              instance ?: SleepDb(context.applicationContext).also { instance = it }
            }
  }

  init {
    // WAL: il servizio puo' scrivere mentre una schermata legge, senza bloccarsi a vicenda
    setWriteAheadLoggingEnabled(true)
  }

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
        """CREATE TABLE sessions(
             id INTEGER PRIMARY KEY AUTOINCREMENT,
             name TEXT NOT NULL,
             device_id TEXT NOT NULL,
             start_ms INTEGER NOT NULL,
             end_ms INTEGER,
             source TEXT NOT NULL DEFAULT 'RR')""")
    db.execSQL(
        """CREATE TABLE rr(
             session_id INTEGER NOT NULL,
             phone_ms INTEGER NOT NULL,
             rr_ms INTEGER NOT NULL)""")
    db.execSQL("CREATE INDEX idx_rr_session ON rr(session_id)")
    db.execSQL(
        """CREATE TABLE nights(
             session_id INTEGER PRIMARY KEY,
             start_ms INTEGER, end_ms INTEGER, beats INTEGER, gaps INTEGER,
             pct_good REAL, pct_corrected REAL, pct_dropped REAL,
             hr_min REAL, resting_hr REAL, hr_avg REAL,
             rmssd REAL, sdnn REAL, pnn50 REAL,
             windows_ok INTEGER, windows_total INTEGER,
             analyzed_at INTEGER,
             intervals_synced_at INTEGER,
             intervals_status TEXT)""")
    db.execSQL(
        """CREATE TABLE windows(
             session_id INTEGER NOT NULL,
             start_ms INTEGER NOT NULL,
             hr REAL, rmssd REAL, sdnn REAL, pnn50 REAL, quality REAL)""")
    db.execSQL("CREATE INDEX idx_windows_session ON windows(session_id)")
    createArchiveTable(db)
    addStagingSchema(db)
    addFeaturesTable(db)
    addQualityColumn(db)
  }

  private fun addQualityColumn(db: SQLiteDatabase) {
    db.execSQL("ALTER TABLE nights ADD COLUMN quality_pct REAL")
  }

  /** Aggiorna un database gia' esistente sul telefono senza perdere i dati. */
  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) createArchiveTable(db)
    // v3: da quale sensore vengono i battiti (le notti gia' registrate sono tutte fascia: RR)
    if (oldVersion < 3) db.execSQL("ALTER TABLE sessions ADD COLUMN source TEXT NOT NULL DEFAULT 'RR'")
    // v4: esito dell'invio a Intervals.icu
    if (oldVersion < 4) {
      db.execSQL("ALTER TABLE nights ADD COLUMN intervals_synced_at INTEGER")
      db.execSQL("ALTER TABLE nights ADD COLUMN intervals_status TEXT")
    }
    // v5: accelerometro e fasi del sonno
    if (oldVersion < 5) addStagingSchema(db)
    // v6: caratteristiche per epoca, per la taratura delle fasi
    if (oldVersion < 6) addFeaturesTable(db)
    // v7: indice di affidabilita' della notte (BioSleepQuality)
    if (oldVersion < 7) addQualityColumn(db)
  }

  private fun addFeaturesTable(db: SQLiteDatabase) {
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS stage_features(
             session_id INTEGER PRIMARY KEY,
             n_epochs INTEGER NOT NULL,
             n_features INTEGER NOT NULL,
             data BLOB NOT NULL)""")
  }

  /** float32 little-endian compressi con gzip: ~1000 epoche x 7 valori ~ 20 KB. */
  private fun packFeatures(values: FloatArray): ByteArray {
    val buf = java.nio.ByteBuffer.allocate(values.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    values.forEach { buf.putFloat(it) }
    val out = java.io.ByteArrayOutputStream()
    java.util.zip.GZIPOutputStream(out).use { it.write(buf.array()) }
    return out.toByteArray()
  }

  /** v5: tabelle dell'accelerometro e colonne delle fasi del sonno nella tabella nights. */
  private fun addStagingSchema(db: SQLiteDatabase) {
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS acc(
             session_id INTEGER NOT NULL, t_sec INTEGER NOT NULL,
             activity INTEGER NOT NULL, gx INTEGER NOT NULL, gy INTEGER NOT NULL, gz INTEGER NOT NULL)""")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_acc_session ON acc(session_id)")
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS acc_archive(
             session_id INTEGER PRIMARY KEY, data BLOB NOT NULL)""")
    for (col in
        listOf(
            "hypno_start_ms INTEGER", "hypnogram TEXT", "sleep_onset_ms INTEGER",
            "sleep_end_ms INTEGER", "tst_min INTEGER", "deep_min INTEGER", "light_min INTEGER",
            "rem_min INTEGER", "wake_min INTEGER", "staging_mode TEXT")) {
      db.execSQL("ALTER TABLE nights ADD COLUMN $col")
    }
  }

  /** v2: battiti delle notti gia' analizzate, compressi (~60 KB per notte). */
  private fun createArchiveTable(db: SQLiteDatabase) {
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS rr_archive(
             session_id INTEGER PRIMARY KEY,
             start_ms INTEGER NOT NULL,
             beats INTEGER NOT NULL,
             data BLOB NOT NULL)""")
  }

  /** source: "RR" = fascia toracica (ECG), "PPI" = sensore ottico (braccio/polso). */
  fun createSession(name: String, deviceId: String, startMs: Long, source: String): Long {
    val v = ContentValues().apply {
      put("name", name)
      put("device_id", deviceId)
      put("start_ms", startMs)
      put("source", source)
    }
    return writableDatabase.insertOrThrow("sessions", null, v)
  }

  fun closeSession(sessionId: Long, endMs: Long) {
    val v = ContentValues().apply { put("end_ms", endMs) }
    writableDatabase.update("sessions", v, "id = ?", arrayOf(sessionId.toString()))
  }

  /** Inserisce molti battiti in una sola transazione (molto piu' veloce e meno batteria). */
  fun insertRr(rows: List<RrRow>) {
    if (rows.isEmpty()) return
    val db = writableDatabase
    db.beginTransaction()
    try {
      val st = db.compileStatement("INSERT INTO rr(session_id, phone_ms, rr_ms) VALUES (?, ?, ?)")
      for (r in rows) {
        st.bindLong(1, r.sessionId)
        st.bindLong(2, r.phoneMs)
        st.bindLong(3, r.rrMs.toLong())
        st.executeInsert()
        st.clearBindings()
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  /**
   * Legge tutti i battiti di una sessione nell'ordine di arrivo: dalla tabella rr se la notte
   * non e' ancora stata archiviata, altrimenti dall'archivio compresso.
   */
  fun loadRr(sessionId: Long): Pair<LongArray, IntArray> {
    val live = loadRrRows(sessionId)
    if (live.first.isNotEmpty()) return live
    readableDatabase
        .rawQuery("SELECT data FROM rr_archive WHERE session_id = ?", arrayOf(sessionId.toString()))
        .use { c -> if (c.moveToNext()) return RrCodec.decode(c.getBlob(0)) }
    return live
  }

  private fun loadRrRows(sessionId: Long): Pair<LongArray, IntArray> {
    readableDatabase
        .rawQuery(
            "SELECT phone_ms, rr_ms FROM rr WHERE session_id = ? ORDER BY rowid",
            arrayOf(sessionId.toString()))
        .use { c ->
          val t = LongArray(c.count)
          val r = IntArray(c.count)
          var i = 0
          while (c.moveToNext()) {
            t[i] = c.getLong(0)
            r[i] = c.getInt(1)
            i++
          }
          return t to r
        }
  }

  /**
   * Dopo l'analisi: comprime i battiti della notte nell'archivio e cancella le righe singole.
   * Tutto in una transazione: o riesce tutto, o non cambia nulla.
   * Ritorna la dimensione dell'archivio in byte (0 se non c'era nulla da archiviare).
   */
  fun archiveSession(sessionId: Long): Int {
    val accBytes = archiveAcc(sessionId)
    val (t, r) = loadRrRows(sessionId)
    if (t.isEmpty()) return accBytes
    val blob = RrCodec.encode(t, r)
    // Controllo di sicurezza: si cancella solo se l'archivio si rilegge identico
    val (t2, r2) = RrCodec.decode(blob)
    check(t.contentEquals(t2) && r.contentEquals(r2)) { "Verifica archivio fallita" }

    val db = writableDatabase
    db.beginTransaction()
    try {
      val v = ContentValues().apply {
        put("session_id", sessionId)
        put("start_ms", t.first())
        put("beats", t.size)
        put("data", blob)
      }
      db.insertWithOnConflict("rr_archive", null, v, SQLiteDatabase.CONFLICT_REPLACE)
      db.delete("rr", "session_id = ?", arrayOf(sessionId.toString()))
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    return blob.size + accBytes
  }

  // --- Accelerometro (v5) ----------------------------------------------------------------------

  fun insertAcc(rows: List<AccRow>) {
    if (rows.isEmpty()) return
    val db = writableDatabase
    db.beginTransaction()
    try {
      val st =
          db.compileStatement(
              "INSERT INTO acc(session_id, t_sec, activity, gx, gy, gz) VALUES (?, ?, ?, ?, ?, ?)")
      for (r in rows) {
        st.bindLong(1, r.sessionId)
        st.bindLong(2, r.tSec)
        st.bindLong(3, r.activity.toLong())
        st.bindLong(4, r.gx.toLong())
        st.bindLong(5, r.gy.toLong())
        st.bindLong(6, r.gz.toLong())
        st.executeInsert()
        st.clearBindings()
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  private fun loadAccRows(sessionId: Long): AccSeconds =
      readableDatabase
          .rawQuery(
              "SELECT t_sec, activity, gx, gy, gz FROM acc WHERE session_id = ? ORDER BY t_sec",
              arrayOf(sessionId.toString()))
          .use { c ->
            val n = c.count
            val t = LongArray(n)
            val a = IntArray(n)
            val x = IntArray(n)
            val y = IntArray(n)
            val z = IntArray(n)
            var i = 0
            while (c.moveToNext()) {
              t[i] = c.getLong(0)
              a[i] = c.getInt(1)
              x[i] = c.getInt(2)
              y[i] = c.getInt(3)
              z[i] = c.getInt(4)
              i++
            }
            AccSeconds(t, a, x, y, z)
          }

  /** Accelerometro di una sessione (righe o archivio compresso); null se non registrato. */
  fun loadAcc(sessionId: Long): AccSeconds? {
    val live = loadAccRows(sessionId)
    if (live.size > 0) return live
    readableDatabase
        .rawQuery("SELECT data FROM acc_archive WHERE session_id = ?", arrayOf(sessionId.toString()))
        .use { c -> if (c.moveToNext()) return AccCodec.decode(c.getBlob(0)) }
    return null
  }

  private fun archiveAcc(sessionId: Long): Int {
    val acc = loadAccRows(sessionId)
    if (acc.size == 0) return 0
    val blob = AccCodec.encode(acc)
    val back = AccCodec.decode(blob)
    check(back.tSec.contentEquals(acc.tSec) && back.activity.contentEquals(acc.activity)) {
      "Verifica archivio ACC fallita"
    }
    val db = writableDatabase
    db.beginTransaction()
    try {
      val v = ContentValues().apply {
        put("session_id", sessionId)
        put("data", blob)
      }
      db.insertWithOnConflict("acc_archive", null, v, SQLiteDatabase.CONFLICT_REPLACE)
      db.delete("acc", "session_id = ?", arrayOf(sessionId.toString()))
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    return blob.size
  }

  /** Tutte le notti di cui abbiamo ancora i battiti (per ricalcolare le fasi). */
  fun nightsWithBeats(): List<Long> =
      readableDatabase
          .rawQuery(
              """SELECT n.session_id FROM nights n
                 WHERE EXISTS (SELECT 1 FROM rr_archive a WHERE a.session_id = n.session_id)
                    OR EXISTS (SELECT 1 FROM rr r WHERE r.session_id = n.session_id)""",
              null)
          .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

  /** Notti analizzate prima della Fase 7: hanno i battiti ma non ancora le fasi del sonno. */
  fun sessionsWithoutStages(): List<Long> =
      readableDatabase
          .rawQuery(
              """SELECT n.session_id FROM nights n
                 WHERE (n.hypnogram IS NULL
                        OR (n.hypnogram <> ''
                            AND NOT EXISTS (SELECT 1 FROM stage_features f
                                            WHERE f.session_id = n.session_id)))
                   AND (EXISTS (SELECT 1 FROM rr_archive a WHERE a.session_id = n.session_id)
                        OR EXISTS (SELECT 1 FROM rr r WHERE r.session_id = n.session_id))""",
              null)
          .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

  /** Elimina del tutto una sessione (usato per registrazioni troppo corte o vuote). */
  fun deleteSession(sessionId: Long) {
    val db = writableDatabase
    val arg = arrayOf(sessionId.toString())
    db.beginTransaction()
    try {
      for (table in
          listOf("rr", "rr_archive", "acc", "acc_archive", "windows", "stage_features", "nights")) {
        db.delete(table, "session_id = ?", arg)
      }
      db.delete("sessions", "id = ?", arg)
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  /**
   * Cancella i battiti archiviati piu' vecchi di [keepDays] giorni.
   * Riepiloghi (nights) e finestre (windows) restano: servono ai grafici e pesano pochi KB.
   */
  fun purgeArchivesOlderThan(keepDays: Int): Int {
    val limit = System.currentTimeMillis() - keepDays * 86_400_000L
    return writableDatabase.delete("rr_archive", "start_ms < ?", arrayOf(limit.toString()))
  }

  /**
   * Restituisce al telefono lo spazio liberato. In SQLite i dati cancellati lasciano "buchi"
   * nel file: VACUUM lo ricostruisce compatto, il checkpoint svuota il file -wal.
   */
  fun compact() {
    writableDatabase.execSQL("VACUUM")
    writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
  }

  /** Salva (o sovrascrive) il risultato dell'analisi di una notte. */
  fun saveNight(result: NightResult, stages: SleepStages?, analyzedAt: Long) {
    val s = result.summary
    val db = writableDatabase
    db.beginTransaction()
    try {
      // Una nuova analisi non deve cancellare l'esito dell'invio a Intervals gia' registrato
      var syncedAt: Long? = null
      var syncStatus: String? = null
      db.rawQuery(
              "SELECT intervals_synced_at, intervals_status FROM nights WHERE session_id = ?",
              arrayOf(s.sessionId.toString()))
          .use { c ->
            if (c.moveToNext()) {
              syncedAt = if (c.isNull(0)) null else c.getLong(0)
              syncStatus = if (c.isNull(1)) null else c.getString(1)
            }
          }
      val v = ContentValues().apply {
        put("session_id", s.sessionId)
        put("start_ms", s.startMs)
        put("end_ms", s.endMs)
        put("beats", s.beats)
        put("gaps", s.gaps)
        put("pct_good", s.pctGood)
        put("pct_corrected", s.pctCorrected)
        put("pct_dropped", s.pctDropped)
        put("hr_min", s.hrMin)
        put("resting_hr", s.restingHr)
        put("hr_avg", s.hrAvg)
        put("rmssd", s.rmssd)
        put("sdnn", s.sdnn)
        put("pnn50", s.pnn50)
        put("windows_ok", s.windowsOk)
        put("windows_total", s.windowsTotal)
        put("quality_pct", s.qualityPct)
        put("analyzed_at", analyzedAt)
        syncedAt?.let { put("intervals_synced_at", it) }
        syncStatus?.let { put("intervals_status", it) }
        if (stages == null) {
          put("hypnogram", "") // tentato ma non disponibile: non si ritenta a ogni avvio
        } else {
          put("hypno_start_ms", stages.hypnoStartMs)
          put("hypnogram", stages.hypnogram)
          stages.sleepOnsetMs?.let { put("sleep_onset_ms", it) }
          stages.sleepEndMs?.let { put("sleep_end_ms", it) }
          put("tst_min", stages.tstMin)
          put("deep_min", stages.deepMin)
          put("light_min", stages.lightMin)
          put("rem_min", stages.remMin)
          put("wake_min", stages.wakeMin)
          put("staging_mode", stages.mode)
        }
      }
      db.insertWithOnConflict("nights", null, v, SQLiteDatabase.CONFLICT_REPLACE)
      stages?.features?.let { feats ->
        val nf = SleepStages.FEATURE_NAMES.size
        val fv = ContentValues().apply {
          put("session_id", s.sessionId)
          put("n_epochs", feats.size / nf)
          put("n_features", nf)
          put("data", packFeatures(feats))
        }
        db.insertWithOnConflict("stage_features", null, fv, SQLiteDatabase.CONFLICT_REPLACE)
      }
      db.delete("windows", "session_id = ?", arrayOf(s.sessionId.toString()))
      for (w in result.windows) {
        val wv = ContentValues().apply {
          put("session_id", s.sessionId)
          put("start_ms", w.startMs)
          put("hr", w.hr)
          put("rmssd", w.rmssd)
          put("sdnn", w.sdnn)
          put("pnn50", w.pnn50)
          put("quality", w.quality)
        }
        db.insertOrThrow("windows", null, wv)
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  /** Sessioni con battiti ma senza analisi (es. app chiusa dal sistema durante la notte). */
  fun sessionsToAnalyze(): List<Long> =
      readableDatabase
          .rawQuery(
              """SELECT s.id FROM sessions s
                 WHERE NOT EXISTS (SELECT 1 FROM nights n WHERE n.session_id = s.id)
                   AND EXISTS (SELECT 1 FROM rr WHERE rr.session_id = s.id)""",
              null)
          .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

  /** Notti gia' analizzate ma con i battiti ancora non compressi (es. create dalla versione 1). */
  fun sessionsToArchive(): List<Long> =
      readableDatabase
          .rawQuery(
              """SELECT n.session_id FROM nights n
                 WHERE EXISTS (SELECT 1 FROM rr WHERE rr.session_id = n.session_id)""",
              null)
          .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

  // --- Lettura per le schermate (Fase 5) ------------------------------------------------------

  private val nightColumns =
      """n.session_id, s.name, n.start_ms, n.end_ms, n.beats, n.gaps,
         n.pct_good, n.pct_corrected, n.pct_dropped, n.hr_min, n.resting_hr, n.hr_avg,
         n.rmssd, n.sdnn, n.pnn50, n.windows_ok, n.windows_total,
         n.intervals_synced_at, n.intervals_status,
         n.hypno_start_ms, n.hypnogram, n.sleep_onset_ms, n.sleep_end_ms, n.tst_min,
         n.deep_min, n.light_min, n.rem_min, n.wake_min, n.staging_mode, n.quality_pct"""

  private fun Cursor.doubleOrNull(i: Int): Double? = if (isNull(i)) null else getDouble(i)

  private fun Cursor.toNightListItem(): NightListItem =
      NightListItem(
          sessionId = getLong(0),
          name = getString(1),
          summary =
              NightSummary(
                  sessionId = getLong(0),
                  startMs = getLong(2),
                  endMs = getLong(3),
                  beats = getInt(4),
                  gaps = getInt(5),
                  pctGood = getDouble(6),
                  pctCorrected = getDouble(7),
                  pctDropped = getDouble(8),
                  hrMin = getDouble(9),
                  restingHr = getDouble(10),
                  hrAvg = getDouble(11),
                  rmssd = doubleOrNull(12),
                  sdnn = doubleOrNull(13),
                  pnn50 = doubleOrNull(14),
                  windowsOk = getInt(15),
                  windowsTotal = getInt(16),
                  qualityPct = doubleOrNull(29),
              ),
          syncedAt = if (isNull(17)) null else getLong(17),
          syncStatus = if (isNull(18)) null else getString(18),
          stages =
              if (isNull(20) || getString(20).isEmpty()) null
              else
                  SleepStages(
                      hypnoStartMs = getLong(19),
                      hypnogram = getString(20),
                      sleepOnsetMs = if (isNull(21)) null else getLong(21),
                      sleepEndMs = if (isNull(22)) null else getLong(22),
                      tstMin = getInt(23),
                      deepMin = getInt(24),
                      lightMin = getInt(25),
                      remMin = getInt(26),
                      wakeMin = getInt(27),
                      mode = getString(28),
                  ),
      )

  /** Tutte le notti analizzate, dalla piu' recente. */
  fun listNights(): List<NightListItem> =
      readableDatabase
          .rawQuery(
              """SELECT $nightColumns FROM nights n JOIN sessions s ON s.id = n.session_id
                 ORDER BY n.start_ms DESC""",
              null)
          .use { c -> buildList { while (c.moveToNext()) add(c.toNightListItem()) } }

  /** Una sola notte, o null se non esiste. */
  fun loadNight(sessionId: Long): NightListItem? =
      readableDatabase
          .rawQuery(
              """SELECT $nightColumns FROM nights n JOIN sessions s ON s.id = n.session_id
                 WHERE n.session_id = ?""",
              arrayOf(sessionId.toString()))
          .use { c -> if (c.moveToNext()) c.toNightListItem() else null }

  /** Le finestre da 5 minuti di una notte, in ordine di tempo (per i grafici). */
  fun loadWindows(sessionId: Long): List<WindowMetrics> =
      readableDatabase
          .rawQuery(
              """SELECT start_ms, hr, rmssd, sdnn, pnn50, quality FROM windows
                 WHERE session_id = ? ORDER BY start_ms""",
              arrayOf(sessionId.toString()))
          .use { c ->
            buildList {
              while (c.moveToNext()) {
                add(
                    WindowMetrics(
                        startMs = c.getLong(0),
                        hr = c.getDouble(1),
                        rmssd = c.doubleOrNull(2),
                        sdnn = c.doubleOrNull(3),
                        pnn50 = c.doubleOrNull(4),
                        quality = c.getDouble(5),
                    ))
              }
            }
          }

  /** Registra l'esito dell'invio a Intervals.icu (syncedAt null = invio non riuscito). */
  fun markSync(sessionId: Long, syncedAt: Long?, status: String) {
    val v = ContentValues().apply {
      if (syncedAt == null) putNull("intervals_synced_at") else put("intervals_synced_at", syncedAt)
      put("intervals_status", status)
    }
    writableDatabase.update("nights", v, "session_id = ?", arrayOf(sessionId.toString()))
  }

  /** Inizio e fine di tutte le notti analizzate (per imparare le abitudini). */
  fun nightTimes(): List<NightTimes> =
      readableDatabase
          .rawQuery("SELECT start_ms, end_ms FROM nights", null)
          .use { c -> buildList { while (c.moveToNext()) add(NightTimes(c.getLong(0), c.getLong(1))) } }
}
