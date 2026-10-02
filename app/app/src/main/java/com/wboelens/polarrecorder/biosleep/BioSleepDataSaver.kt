package com.wboelens.polarrecorder.biosleep

import android.content.Context
import android.database.SQLException
import android.util.Log
import com.polar.sdk.api.model.PolarAccelerometerData
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarPpiData
import com.wboelens.polarrecorder.biosleep.auto.NightNotifier
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsResult
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsSettings
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsSync
import com.wboelens.polarrecorder.dataSavers.DataSaver
import com.wboelens.polarrecorder.dataSavers.InitializationState
import com.wboelens.polarrecorder.managers.DeviceInfoForDataSaver
import com.wboelens.polarrecorder.managers.PreferencesManager
import com.wboelens.polarrecorder.state.LogState
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Salva i battiti (RR) nel database locale e, a fine registrazione, analizza la notte.
 * Si affianca agli altri DataSaver (file, MQTT): e' sempre attivo.
 */
class BioSleepDataSaver(
    context: Context,
    logState: LogState,
    preferencesManager: PreferencesManager,
) : DataSaver(logState, preferencesManager) {

  companion object {
    private const val FLUSH_EVERY_ROWS = 300
    private const val FLUSH_EVERY_MS = 30_000L
    private const val TAG = "BioSleep"

    /**
     * Sensori ottici (PPI): sopra questo errore stimato dal sensore il battito e' considerato non
     * valido. Polar indica: sotto 10 ms molto accurato, sopra 30 ms probabile artefatto.
     */
    private const val PPI_MAX_ERROR_MS = 30

    /**
     * Per quanti giorni tenere i battiti compressi delle notti gia' analizzate.
     * 0 = per sempre (~60 KB a notte, circa 20 MB in un anno). Servono per rianalizzare le
     * notti passate quando miglioreremo gli algoritmi (es. fasi del sonno).
     */
    private const val KEEP_RR_ARCHIVE_DAYS = 0

    /** Id dell'ultima notte nuova appena analizzata (le schermate lo osservano per aggiornarsi). */
    val newNightReady = MutableStateFlow<Long?>(null)

    /** true mentre una notte appena chiusa e' in analisi. */
    val analyzing = MutableStateFlow(false)
  }

  private val appContext = context.applicationContext
  private val db = SleepDb.get(context)
  private val sessions = ConcurrentHashMap<String, Long>() // deviceId -> sessionId
  private val sources = ConcurrentHashMap<String, String>() // deviceId -> "RR" oppure "PPI"
  private val buffer = ArrayList<RrRow>()
  private val lock = Any()
  private var lastFlushMs = 0L
  private var firstBeatLogged = false

  /** Orario dell'ultimo battito valido ricevuto: serve allo stop automatico del mattino. */
  @Volatile
  var lastValidBeatMs: Long = 0L
    private set

  // Un solo thread per l'analisi: non blocca mai la registrazione
  private val analysisExecutor = Executors.newSingleThreadExecutor()

  // Accelerometro (H10): i campioni a 25 Hz vengono riassunti in una riga al secondo
  private val accBuffer = ArrayList<AccRow>()
  private var accSessionId = -1L
  private var accSec = -1L
  private var accN = 0
  private var accJerk = 0.0 // somma delle variazioni del modulo tra campioni consecutivi
  private var accJerkN = 0
  private var accPrevM = Double.NaN
  private var accX = 0L
  private var accY = 0L
  private var accZ = 0L

  override val isConfigured: Boolean = true

  init {
    // Apre subito il database all'avvio dell'app: eventuali aggiornamenti di schema avvengono qui,
    // e il Database Inspector di Android Studio lo vede senza dover avviare una registrazione.
    try {
      db.writableDatabase
    } catch (e: SQLException) {
      logState.addLogError("BioSleep: impossibile aprire il database: ${e.message}")
    }
    // Fase 7: calcola le fasi del sonno anche per le notti registrate prima dell'aggiornamento
    analysisExecutor.execute { stageOldNights() }
  }

  override fun enable() {
    _isEnabled.value = true
  }

  override fun disable() {
    _isEnabled.value = false
  }

  override fun initSaving(
      recordingName: String,
      deviceIdsWithInfo: Map<String, DeviceInfoForDataSaver>,
  ) {
    super.initSaving(recordingName, deviceIdsWithInfo)
    try {
      analyzeLeftovers()
      val now = System.currentTimeMillis()
      sessions.clear()
      sources.clear()
      for ((deviceId, info) in deviceIdsWithInfo) {
        // Se per il dispositivo e' attivo lo stream PPI (sensore ottico) si usa quello,
        // altrimenti gli RR dello stream HR (fascia toracica). Mai entrambi: sarebbero doppi.
        val source = if (info.dataTypes.contains("PPI")) "PPI" else "RR"
        sources[deviceId] = source
        sessions[deviceId] = db.createSession(recordingName, deviceId, now, source)
        logState.addLogMessage("BioSleep: dispositivo $deviceId, battiti da $source")
      }
      synchronized(lock) {
        buffer.clear()
        lastFlushMs = now
        firstBeatLogged = false
        lastValidBeatMs = now
        accBuffer.clear()
        accSec = -1L
        accN = 0
        accPrevM = Double.NaN
      }
      _isInitialized.value = InitializationState.SUCCESS
    } catch (e: SQLException) {
      logState.addLogError("BioSleep: impossibile preparare il database: ${e.message}")
      _isInitialized.value = InitializationState.FAILED
    }
  }

  override fun saveData(
      phoneTimestamp: Long,
      deviceId: String,
      recordingName: String,
      dataType: String,
      data: Any,
  ) {
    val sessionId = sessions[deviceId] ?: return
    if (dataType == "ACC") {
      saveAcc(sessionId, phoneTimestamp, data as? List<*> ?: return)
      return
    }
    val source = sources[deviceId] ?: return
    if (dataType != (if (source == "PPI") "PPI" else "HR")) return
    val samples = data as? List<*> ?: return

    synchronized(lock) {
      for (s in samples) {
        when (s) {
          is PolarHrData.PolarHrSample -> {
            if (s.contactStatusSupported && !s.contactStatus) continue // fascia staccata
            for (rr in s.rrsMs) buffer.add(RrRow(sessionId, phoneTimestamp, rr))
            if (s.rrsMs.isNotEmpty()) lastValidBeatMs = phoneTimestamp
          }
          is PolarPpiData.PolarPpiSample -> {
            if (s.ppi <= 0) continue
            val invalid =
                s.blockerBit ||
                    (s.skinContactSupported && !s.skinContactStatus) ||
                    s.errorEstimate > PPI_MAX_ERROR_MS
            // Non valido -> salvato negativo: tiene il tempo, ma e' escluso dall'HRV
            buffer.add(RrRow(sessionId, phoneTimestamp, if (invalid) -s.ppi else s.ppi))
            if (!invalid) lastValidBeatMs = phoneTimestamp
          }
        }
      }
      if (buffer.size >= FLUSH_EVERY_ROWS || phoneTimestamp - lastFlushMs >= FLUSH_EVERY_MS) {
        flushLocked(phoneTimestamp)
      }
    }
  }

  /** Riassume i campioni ACC per secondo: variabilita' del modulo (movimento) e postura. */
  private fun saveAcc(sessionId: Long, phoneTimestamp: Long, samples: List<*>) {
    synchronized(lock) {
      accSessionId = sessionId
      val list = samples.filterIsInstance<PolarAccelerometerData.PolarAccelerometerDataSample>()
      if (list.isEmpty()) return
      // Ogni pacchetto contiene ~1-2 s di campioni: per assegnare ogni campione al secondo giusto
      // si usa l'orologio della fascia, ancorato all'orario di arrivo dell'ultimo campione.
      // Con secondi precisi il respiro (un atto ogni ~4 s) resta leggibile.
      val lastTs = list.last().timeStamp
      for (a in list) {
        val sec = (phoneTimestamp - (lastTs - a.timeStamp) / 1_000_000) / 1000
        if (accSec == -1L) accSec = sec
        if (sec > accSec) {
          closeAccSecondLocked()
          accSec = sec
        }
        val m = sqrt((a.x.toDouble() * a.x + a.y.toDouble() * a.y + a.z.toDouble() * a.z))
        accN++
        // Movimento = variazioni rapide tra campioni a 25 Hz. Il respiro cambia lentamente
        // (~0,25 Hz) e tra due campioni vicini quasi non si vede: non sporca la misura.
        if (!accPrevM.isNaN()) {
          accJerk += kotlin.math.abs(m - accPrevM)
          accJerkN++
        }
        accPrevM = m
        accX += a.x
        accY += a.y
        accZ += a.z
      }
      if (accBuffer.size >= FLUSH_EVERY_ROWS || phoneTimestamp - lastFlushMs >= FLUSH_EVERY_MS) {
        flushLocked(phoneTimestamp)
      }
    }
  }

  private fun closeAccSecondLocked() {
    if (accN > 0) {
      val jerk = if (accJerkN > 0) accJerk / accJerkN * 10 else 0.0 // in 0,1 mg
      accBuffer.add(
          AccRow(
              accSessionId,
              accSec,
              jerk.roundToInt(),
              // assi in 0,1 mg: serve la precisione per vedere il respiro (pochi mg)
              (accX * 10 / accN).toInt(),
              (accY * 10 / accN).toInt(),
              (accZ * 10 / accN).toInt(),
          ))
    }
    accN = 0
    accJerk = 0.0
    accJerkN = 0
    accX = 0L
    accY = 0L
    accZ = 0L
  }

  /** Scrive il buffer sul database. Va chiamata tenendo il lock. */
  private fun flushLocked(nowMs: Long) {
    if (accBuffer.isNotEmpty()) {
      try {
        db.insertAcc(accBuffer)
        accBuffer.clear()
      } catch (e: SQLException) {
        logState.addLogError("BioSleep: errore di scrittura accelerometro: ${e.message}")
      }
    }
    if (buffer.isEmpty()) {
      lastFlushMs = nowMs
      return
    }
    try {
      db.insertRr(buffer)
      if (!firstBeatLogged) {
        logState.addLogMessage("BioSleep: primi ${buffer.size} battiti salvati nel database.")
        firstBeatLogged = true
      }
      buffer.clear()
    } catch (e: SQLException) {
      // Il buffer non viene svuotato: si riprova al prossimo giro
      logState.addLogError("BioSleep: errore di scrittura sul database: ${e.message}")
    }
    lastFlushMs = nowMs
  }

  /**
   * Chiude la registrazione e avvia subito l'analisi. Chiamata dall'orchestrator appena premi stop.
   * Si puo' chiamare piu' volte: dalla seconda in poi non fa nulla.
   */
  fun finishRecording() {
    val now = System.currentTimeMillis()
    synchronized(lock) {
      closeAccSecondLocked()
      flushLocked(now)
    }
    val ended = sessions.values.toList()
    sessions.clear()
    sources.clear()
    if (ended.isNotEmpty()) analyzing.value = true
    for (id in ended) {
      try {
        db.closeSession(id, now)
      } catch (e: SQLException) {
        logState.addLogError("BioSleep: impossibile chiudere la sessione $id: ${e.message}")
      }
      analysisExecutor.execute { analyze(id) }
    }
  }

  override fun stopSaving() {
    finishRecording() // rete di sicurezza, se lo stop non e' passato da finishRecording()
    super.stopSaving()
  }

  /**
   * Analizza sessioni rimaste senza risultato (es. app chiusa dal sistema durante la notte).
   * L'elenco si legge subito, prima di creare la nuova sessione, cosi' quella nuova non ci finisce.
   */
  private fun analyzeLeftovers() {
    val leftovers = db.sessionsToAnalyze()
    for (id in leftovers) analysisExecutor.execute { analyze(id) }
    val toArchive = db.sessionsToArchive()
    if (toArchive.isNotEmpty()) analysisExecutor.execute { archiveOnly(toArchive) }
  }

  /** Comprime notti gia' analizzate che hanno ancora i battiti in forma estesa. */
  @Suppress("TooGenericExceptionCaught")
  private fun archiveOnly(ids: List<Long>) {
    try {
      val kb = ids.sumOf { db.archiveSession(it) } / 1024
      db.compact()
      Log.i(TAG, "Archiviate ${ids.size} notti precedenti: $kb KB")
    } catch (e: Exception) {
      Log.e(TAG, "Archiviazione notti precedenti fallita", e)
    }
  }

  // Qualsiasi errore nell'analisi va catturato: un'eccezione non gestita in questo thread
  // farebbe chiudere l'app. I battiti restano comunque salvati e si puo' rianalizzare.
  @Suppress("TooGenericExceptionCaught")
  private fun analyze(sessionId: Long, isNewNight: Boolean = true) {
    try {
      val (phoneMs, rrMs) = db.loadRr(sessionId)
      val result = NightAnalyzer.analyze(sessionId, phoneMs, rrMs)
      if (result == null) {
        if (isNewNight) {
          db.deleteSession(sessionId)
          logState.addLogMessage("BioSleep: sessione $sessionId troppo corta, eliminata.")
        }
        return
      }
      // Fase 7: fasi del sonno (HRV, piu' accelerometro se registrato)
      val stages = stageSafely(result, db.loadAcc(sessionId))
      db.saveNight(result, stages, System.currentTimeMillis())
      if (isNewNight) newNightReady.value = sessionId
      if (!isNewNight) {
        Log.i(TAG, "Fasi del sonno calcolate per la notte $sessionId (${stages?.mode ?: "n/d"})")
        return
      }
      val text = formatSummary(result.summary)
      Log.i(TAG, text) // visibile in Android Studio -> Logcat, filtro "BioSleep"
      logState.addLogSuccess(text, withSnackbar = true)

      // Ottimizzazione spazio: battiti singoli -> archivio compresso, poi compattazione
      val archiveBytes = db.archiveSession(sessionId)
      if (KEEP_RR_ARCHIVE_DAYS > 0) db.purgeArchivesOlderThan(KEEP_RR_ARCHIVE_DAYS)
      db.compact()
      Log.i(TAG, "Sessione $sessionId archiviata: ${archiveBytes / 1024} KB")

      // Fase 6: invio automatico a Intervals.icu (se configurato)
      val settings = IntervalsSettings(appContext)
      var intervalsLine: String? = null
      if (settings.isConfigured && settings.autoUpload) {
        val sync = IntervalsSync.syncNight(appContext, sessionId)
        Log.i(TAG, "Intervals.icu: ${sync.message}")
        if (sync is IntervalsResult.Ok) {
          logState.addLogSuccess("BioSleep: ${sync.message}")
          intervalsLine = "✓ Inviata a Intervals.icu"
        } else {
          logState.addLogError("BioSleep: invio a Intervals.icu non riuscito: ${sync.message}")
          intervalsLine = "Intervals.icu: invio non riuscito, riprova dall'app"
        }
      }

      // Riepilogo nella notifica: al mattino non serve aprire l'app
      NightNotifier.notifySummary(appContext, result.summary, stages, intervalsLine)
    } catch (e: Exception) {
      Log.e(TAG, "Analisi sessione $sessionId fallita", e)
      logState.addLogError("BioSleep: analisi della sessione $sessionId fallita: ${e.message}")
    } finally {
      if (isNewNight) analyzing.value = false
    }
  }

  @Suppress("TooGenericExceptionCaught")
  private fun stageSafely(result: NightResult, acc: AccSeconds?): SleepStages? =
      try {
        SleepStager.stage(result.beats, acc)
      } catch (e: Exception) {
        Log.e(TAG, "Stima delle fasi del sonno fallita", e)
        null
      }

  /** Notti salvate prima della Fase 7: si calcolano le fasi dai battiti archiviati. */
  @Suppress("TooGenericExceptionCaught")
  private fun stageOldNights() {
    try {
      // Se l'algoritmo delle fasi e' cambiato si ricalcolano tutte le notti, una volta sola
      val prefs = appContext.getSharedPreferences("biosleep_stager", Context.MODE_PRIVATE)
      val ids =
          if (prefs.getInt("version", 0) < SleepStager.VERSION) db.nightsWithBeats()
          else db.sessionsWithoutStages()
      ids.forEach { analyze(it, isNewNight = false) }
      prefs.edit().putInt("version", SleepStager.VERSION).apply()
    } catch (e: Exception) {
      Log.e(TAG, "Calcolo fasi per le notti precedenti fallito", e)
    }
  }

  private fun formatSummary(s: NightSummary): String {
    fun f(v: Double?, d: Int = 1) = if (v == null) "n/d" else String.format(Locale.ITALY, "%.${d}f", v)
    val hours = (s.endMs - s.startMs) / 3_600_000.0
    return "BioSleep notte ${s.sessionId}: ${f(hours)} h, ${s.beats} battiti, " +
        "interruzioni ${s.gaps}, buoni ${f(s.pctGood)} %, corretti ${f(s.pctCorrected)} %, " +
        "scartati ${f(s.pctDropped)} % | FC min ${f(s.hrMin, 0)}, riposo ${f(s.restingHr, 0)}, " +
        "media ${f(s.hrAvg, 0)} bpm | rMSSD ${f(s.rmssd)} ms, SDNN ${f(s.sdnn)} ms, " +
        "pNN50 ${f(s.pnn50)} % | finestre ${s.windowsOk}/${s.windowsTotal}"
  }
}
