package com.wboelens.polarrecorder.biosleep

import com.wboelens.polarrecorder.BuildConfig
import android.content.Context
import android.database.SQLException
import android.util.Log
import com.polar.sdk.api.model.PolarAccelerometerData
import com.polar.sdk.api.model.PolarHrData
import android.os.Build
import com.wboelens.polarrecorder.biosleep.hal.Capacita
import com.wboelens.polarrecorder.biosleep.hal.Contatto
import com.wboelens.polarrecorder.biosleep.hal.DriverRegistry
import com.wboelens.polarrecorder.biosleep.hal.Fasce
import com.wboelens.polarrecorder.biosleep.hal.FasceGatt
import com.wboelens.polarrecorder.biosleep.hal.HrPacket
import com.wboelens.polarrecorder.biosleep.hal.RapportoFascia
import com.wboelens.polarrecorder.biosleep.hal.SintesiBattiti
import com.wboelens.polarrecorder.biosleep.hal.StatoRr
import com.wboelens.polarrecorder.biosleep.hal.TipoDriver
import com.wboelens.polarrecorder.biosleep.hal.ValutatoreSessione
import com.wboelens.polarrecorder.biosleep.finalize.NightFinalizeWorker
import com.wboelens.polarrecorder.biosleep.watchdog.AutoStopLog
import com.wboelens.polarrecorder.biosleep.watchdog.BeatLiveness
import com.polar.sdk.api.model.PolarPpiData
import com.wboelens.polarrecorder.biosleep.auto.NightNotifier
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsSettings
import com.wboelens.polarrecorder.biosleep.intervals.InvioNotteWorker
import com.wboelens.polarrecorder.biosleep.sopravvivenza.EventiNotte
import com.wboelens.polarrecorder.biosleep.sopravvivenza.RilevaInterruzioni
import com.wboelens.polarrecorder.biosleep.sopravvivenza.TipoEvento
import com.wboelens.polarrecorder.biosleep.watchdog.Watchdog
import com.wboelens.polarrecorder.biosleep.live.CampioneAcc
import com.wboelens.polarrecorder.dataSavers.DataSaver
import com.wboelens.polarrecorder.dataSavers.InitializationState
import com.wboelens.polarrecorder.managers.DeviceInfoForDataSaver
import com.wboelens.polarrecorder.managers.PreferencesManager
import com.wboelens.polarrecorder.state.LogState
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Salva i battiti (RR) nel database locale e, a fine registrazione, analizza la notte.
 * E' l'unico salvataggio di BioSleep ed e' sempre attivo.
 */
class BioSleepDataSaver(
    context: Context,
    logState: LogState,
    preferencesManager: PreferencesManager,
) : DataSaver(logState, preferencesManager) {

  companion object {
    private const val MAX_IN_ATTESA_FC = 600 // 10 minuti di pacchetti senza RR
    /** Letto dalla card "Registrazione interrotta" della Parte 3 (Protezione.ultimaInterruzione). */
    const val PREFS_INTERRUZIONI = "biosleep_interruzioni"
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

    /**
     * Battiti in diretta (RR in ms, solo quelli validi) per il fiore della schermata Notte.
     * Senza nessuno in ascolto i valori vengono scartati: la registrazione non rallenta mai e non
     * si accumula memoria (al massimo 64 battiti in attesa, poi si perdono i piu' vecchi).
     */
    val battiti =
        MutableSharedFlow<Int>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Accelerometro in diretta (H10), un pacchetto alla volta, per il fiore che segue il respiro.
     * Stesse regole dei battiti: senza nessuno in ascolto si scarta tutto.
     */
    val accLive =
        MutableSharedFlow<List<CampioneAcc>>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  }

  private val appContext = context.applicationContext
  private val db = SleepDb.get(context)
  private val sessions = ConcurrentHashMap<String, Long>() // deviceId -> sessionId
  private val sources = ConcurrentHashMap<String, String>() // deviceId -> "RR" oppure "PPI"
  private val buffer = ArrayList<RrRow>()
  private val lock = Any()
  private var lastFlushMs = 0L
  private var firstBeatLogged = false

  /**
   * Ultimo istante con una sequenza di battiti plausibili (vedi BeatLiveness): serve allo stop
   * automatico del mattino. Non basta il "contatto" segnalato dalla fascia.
   */
  @Volatile
  var lastValidBeatMs: Long = 0L
    private set

  /** Ultimo pacchetto ricevuto dalla fascia, anche senza battiti (solo per il registro). */
  @Volatile
  var lastPacketMs: Long = 0L
    private set

  private val liveness = BeatLiveness()

  // Punto 10: fascia della sessione (capacita', qualita' degli RR) e battiti dalla sola FC
  private val valutatori = mutableMapOf<String, ValutatoreSessione>()
  private val sintesi = mutableMapOf<String, SintesiBattiti>()
  private val inAttesaFc = mutableMapOf<String, MutableList<Pair<Long, Int>>>()
  private val inizioSessione = mutableMapOf<String, Long>()
  private val finalizeLock = Any()

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
      logState.addLogError("impossibile aprire il database: ${e.message}")
    }
    // Nessuna notte deve restare aperta: all'avvio del processo (riavvio del servizio, apertura
    // dell'app) le notti rimaste aperte o senza analisi vengono chiuse da NightFinalizeWorker
    analysisExecutor.execute { finalizeIfPending("avvio dell'app") }
    // Notti analizzate ma mai inviate (es. invio fallito senza rete): tornano in coda da sole
    analysisExecutor.execute { accodaInviiInSospeso() }
    // Fase 7: calcola le fasi del sonno anche per le notti registrate prima dell'aggiornamento
    analysisExecutor.execute { stageOldNights() }
  }

  @Suppress("TooGenericExceptionCaught")
  private fun finalizeIfPending(reason: String) {
    try {
      val current = sessions.values.toSet() + listOfNotNull(Watchdog.sessioneDaRiprendere(appContext))
      val pending =
          db.openSessions().filter { it !in current } +
              db.sessionsToAnalyze().filter { it !in current }
      if (pending.isNotEmpty()) NightFinalizeWorker.enqueue(appContext, "$reason: ${pending.distinct().size} notti in sospeso")
    } catch (e: Exception) {
      Log.e(TAG, "Controllo notti in sospeso fallito", e)
    }
  }

  /**
   * Chiamata da NightFinalizeWorker, fuori dal main thread. Chiude le sessioni rimaste aperte
   * (non quella in registrazione) all'ultimo battito salvato, poi analizza, archivia, invia e
   * notifica tutte le notti senza risultato. Ritorna quante notti ha analizzato.
   */
  fun finalizePendingNights(): Int =
      synchronized(finalizeLock) {
        // Esclusa anche una notte interrotta ma non finita: la riprende il watchdog
        val ripresa = Watchdog.sessioneDaRiprendere(appContext)
        if (ripresa != null && ripresa !in sessions.values) {
          AutoStopLog.write(appContext, "Recupero: la notte $ripresa e' interrotta ma non finita, resta aperta per la ripresa")
        }
        val current = sessions.values.toSet() + listOfNotNull(ripresa)
        for (id in db.openSessions()) {
          if (id in current) continue
          val end = db.lastBeatMs(id) ?: System.currentTimeMillis()
          db.closeSession(id, end)
          AutoStopLog.write(appContext, "Recupero: sessione $id rimasta aperta, chiusa all'ultimo battito")
        }
        val pending = db.sessionsToAnalyze().filter { it !in current }
        for (id in pending) {
          AutoStopLog.write(appContext, "Analisi della sessione $id")
          analyze(id, isNewNight = true)
        }
        analyzing.value = false
        pending.size
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
      // Notte interrotta (servizio chiuso dal sistema) che riparte: si continua la stessa sessione
      val ripresa = Watchdog.sessioneDaRiprendere(appContext, now)
      sessions.clear()
      sources.clear()
      for ((deviceId, info) in deviceIdsWithInfo) {
        // Se per il dispositivo e' attivo lo stream PPI (sensore ottico) si usa quello,
        // altrimenti gli RR dello stream HR (fascia toracica). Mai entrambi: sarebbero doppi.
        val source = if (info.dataTypes.contains("PPI")) "PPI" else "RR"
        sources[deviceId] = source
        sessions[deviceId] = ripresa ?: db.createSession(recordingName, deviceId, now, source)
        if (ripresa != null) {
          AutoStopLog.write(appContext, "Ripresa della notte $ripresa dopo un'interruzione")
          EventiNotte.registra(appContext, TipoEvento.RIPRESA, "sessione $ripresa")
        }
        logState.addLogMessage("dispositivo $deviceId, battiti da $source")
        val gatt = FasceGatt.isGatt(appContext, deviceId)
        val capacita =
            Capacita(
                driver = if (gatt) TipoDriver.GATT_180D else TipoDriver.POLAR,
                hr = true,
                rr = null,
                acc = !gatt && info.dataTypes.contains("ACC"),
                tipo = DriverRegistry.tipo(info.deviceName))
        synchronized(lock) {
          valutatori[deviceId] = ValutatoreSessione(sessions.getValue(deviceId), info.deviceName, capacita)
          sintesi[deviceId] = SintesiBattiti()
          inAttesaFc.remove(deviceId)
          inizioSessione[deviceId] = now
        }
        Fasce.aggiorna(valutatori[deviceId]?.stato())
      }
      synchronized(lock) {
        buffer.clear()
        lastFlushMs = now
        firstBeatLogged = false
        lastValidBeatMs = now
        lastPacketMs = now
        liveness.reset(now)
        accBuffer.clear()
        accSec = -1L
        accN = 0
        accPrevM = Double.NaN
      }
      _isInitialized.value = InitializationState.SUCCESS
    } catch (e: SQLException) {
      logState.addLogError("impossibile preparare il database: ${e.message}")
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
            val contatto =
                when {
                  !s.contactStatusSupported -> Contatto.NON_SUPPORTATO
                  s.contactStatus -> Contatto.PRESENTE
                  else -> Contatto.ASSENTE // fascia staccata
                }
            gestisciPacchetto(sessionId, deviceId, phoneTimestamp, HrPacket(s.hr, contatto, null, s.rrsMs, null))
          }
          // Punto 10: fasce di altre marche lette con il driver GATT 0x180D
          is HrPacket -> gestisciPacchetto(sessionId, deviceId, phoneTimestamp, s)
          is PolarPpiData.PolarPpiSample -> {
            if (s.ppi <= 0) continue
            val invalid =
                s.blockerBit ||
                    (s.skinContactSupported && !s.skinContactStatus) ||
                    s.errorEstimate > PPI_MAX_ERROR_MS
            // Non valido -> salvato negativo: tiene il tempo, ma e' escluso dall'HRV
            buffer.add(RrRow(sessionId, phoneTimestamp, if (invalid) -s.ppi else s.ppi))
            lastPacketMs = phoneTimestamp
            if (!invalid) {
              liveness.add(s.ppi, phoneTimestamp)
              lastValidBeatMs = liveness.lastSustainedMs
              battiti.tryEmit(s.ppi)
            }
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
      if (accLive.subscriptionCount.value > 0) {
        accLive.tryEmit(
            list.map { a -> CampioneAcc(phoneTimestamp - (lastTs - a.timeStamp) / 1_000_000, a.x, a.y, a.z) })
      }
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
        logState.addLogError("errore di scrittura accelerometro: ${e.message}")
      }
    }
    if (buffer.isEmpty()) {
      lastFlushMs = nowMs
      return
    }
    try {
      db.insertRr(buffer)
      if (!firstBeatLogged) {
        logState.addLogMessage("primi ${buffer.size} battiti salvati nel database.")
        firstBeatLogged = true
      }
      buffer.clear()
    } catch (e: SQLException) {
      // Il buffer non viene svuotato: si riprova al prossimo giro
      logState.addLogError("errore di scrittura sul database: ${e.message}")
    }
    lastFlushMs = nowMs
  }

  /**
   * Chiude la registrazione e avvia subito l'analisi. Chiamata dall'orchestrator appena premi stop.
   * Si puo' chiamare piu' volte: dalla seconda in poi non fa nulla.
   */
  /**
   * Un pacchetto di battito, da qualsiasi driver (chiamata dentro lock). Gli RR si salvano come
   * sempre; senza RR, se la fascia e' "solo FC", si salvano battiti ricostruiti dalla FC (i primi
   * 5 minuti restano in attesa finche' non si sa se gli RR arriveranno).
   */
  private fun gestisciPacchetto(sessionId: Long, deviceId: String, t: Long, p: HrPacket) {
    lastPacketMs = t
    if (p.daScartare) return
    val v = valutatori[deviceId]
    if (v != null && v.aggiungi(t, p)) valutazioneCambiata(deviceId, v)
    if (p.rrMs.isNotEmpty()) {
      inAttesaFc.remove(deviceId)
      for (rr in p.rrMs) {
        buffer.add(RrRow(sessionId, t, rr))
        battiti.tryEmit(rr)
        liveness.add(rr, t)
      }
      lastValidBeatMs = liveness.lastSustainedMs
      return
    }
    if (p.hr <= 0) return
    when (v?.valutazione?.stato) {
      StatoRr.SOLO_FC -> salvaDallaFc(sessionId, deviceId, t, p.hr)
      StatoRr.IN_VALUTAZIONE, null -> {
        val attesa = inAttesaFc.getOrPut(deviceId) { mutableListOf() }
        if (attesa.size < MAX_IN_ATTESA_FC) attesa += t to p.hr
      }
      else -> Unit // RR attesi ma assenti in questo pacchetto: normale sotto i 60 bpm
    }
  }

  private fun salvaDallaFc(sessionId: Long, deviceId: String, t: Long, hr: Int) {
    for (rr in sintesi.getOrPut(deviceId) { SintesiBattiti() }.da(t, hr)) {
      buffer.add(RrRow(sessionId, t, rr))
      liveness.add(rr, t)
    }
    lastValidBeatMs = liveness.lastSustainedMs
  }

  private fun valutazioneCambiata(deviceId: String, v: ValutatoreSessione) {
    FasceGatt.capacita(deviceId)?.let { c -> v.capacita = c.copy(rr = v.capacita.rr) } // modello, firmware
    val stato = v.stato()
    Fasce.aggiorna(stato)
    AutoStopLog.write(appContext, "Fascia ${v.nome}: RR ${stato.valutazione.stato} (${stato.valutazione.motivo})")
    if (stato.valutazione.stato == StatoRr.SOLO_FC) {
      // i primi minuti senza RR diventano battiti dalla FC
      inAttesaFc.remove(deviceId)?.forEach { (t, hr) -> salvaDallaFc(v.sessionId, deviceId, t, hr) }
    }
    val rapporto = rapportoFascia(deviceId, v)
    analysisExecutor.execute { salvaFascia(v.sessionId, stato.valutazione.stato.name, rapporto) }
  }

  private fun rapportoFascia(deviceId: String, v: ValutatoreSessione): String {
    val inizio = inizioSessione[deviceId] ?: System.currentTimeMillis()
    val driver = FasceGatt.driver(deviceId)
    val disconnessioni =
        driver?.disconnessioni
            ?: try {
              EventiNotte.get(appContext).eventiTra(inizio, System.currentTimeMillis()).count { it.tipo == TipoEvento.FASCIA_SCOLLEGATA }
            } catch (e: SQLException) {
              0
            }
    return RapportoFascia(
            nome = v.nome,
            capacita = v.capacita,
            statoRr = v.valutazione.stato,
            motivoRr = v.valutazione.motivo,
            percentualeRrValidi = v.valutazione.percentualeValidi,
            pacchettiScartatiSenzaContatto = driver?.scartatiSenzaContatto ?: 0,
            pacchettiMalformati = driver?.malformati ?: 0,
            disconnessioni = disconnessioni,
            durataMinuti = ((System.currentTimeMillis() - inizio) / 60_000).toInt(),
            versioneApp = Fasce.versioneApp(),
            telefono = Fasce.telefono(),
            android = Build.VERSION.SDK_INT)
        .json()
  }

  private fun salvaFascia(sessionId: Long, stato: String, rapporto: String) {
    try {
      EventiNotte.get(appContext).salvaFascia(sessionId, stato, rapporto)
    } catch (e: SQLException) {
      Log.w(TAG, "Fascia della sessione $sessionId non salvata: ${e.message}")
    }
  }

  fun finishRecording() {
    // Punto 10: valutazione finale della fascia e rapporto per ogni sessione che si chiude
    synchronized(lock) {
      for ((deviceId, v) in valutatori) {
        if (v.forza(System.currentTimeMillis()) && v.valutazione.stato == StatoRr.SOLO_FC) {
          inAttesaFc.remove(deviceId)?.forEach { (t, hr) -> salvaDallaFc(v.sessionId, deviceId, t, hr) }
        }
        FasceGatt.capacita(deviceId)?.let { c -> v.capacita = c.copy(rr = v.capacita.rr) }
        salvaFascia(v.sessionId, v.valutazione.stato.name, rapportoFascia(deviceId, v))
      }
      valutatori.clear()
      inAttesaFc.clear()
    }
    Fasce.aggiorna(null)
    val now = System.currentTimeMillis()
    synchronized(lock) {
      closeAccSecondLocked()
      flushLocked(now)
    }
    val ended = sessions.values.toList()
    sessions.clear()
    sources.clear()
    if (ended.isEmpty()) return
    analyzing.value = true
    for (id in ended) {
      try {
        db.closeSession(id, now)
      } catch (e: SQLException) {
        logState.addLogError("impossibile chiudere la sessione $id: ${e.message}")
      }
    }
    // Analisi, invio a Intervals.icu e coach: in un lavoro protetto, non su un thread qualunque
    // (con l'app chiusa Android congelerebbe il processo a meta' analisi)
    NightFinalizeWorker.enqueue(appContext, "fine registrazione, sessioni $ended")
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
    if (db.sessionsToAnalyze().isNotEmpty() || db.openSessions().isNotEmpty()) {
      NightFinalizeWorker.enqueue(appContext, "notti in sospeso trovate all'avvio di una registrazione")
    }
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
      var result = NightAnalyzer.analyze(sessionId, phoneMs, rrMs)
      if (result == null) {
        if (isNewNight) {
          db.deleteSession(sessionId)
          logState.addLogMessage("sessione $sessionId troppo corta, eliminata.")
        }
        return
      }
      // Punto 10: fascia "solo FC" o con RR non affidabili -> niente HRV (non salvata, non inviata:
      // NoctalixRMSSD assente su Intervals.icu e il coach esclude la notte dalla baseline HRV)
      val statoRr = try { EventiNotte.get(appContext).statoRr(sessionId) } catch (e: SQLException) { null }
      val senzaHrv = statoRr == StatoRr.SOLO_FC.name || statoRr == StatoRr.NON_AFFIDABILI.name
      if (senzaHrv) {
        result =
            result.copy(
                summary = result.summary.copy(rmssd = null, sdnn = null, pnn50 = null),
                windows = result.windows.map { it.copy(rmssd = null, sdnn = null, pnn50 = null) })
        if (isNewNight) AutoStopLog.write(appContext, "Notte $sessionId: fascia $statoRr, HRV non calcolata")
      }
      // Fase 7: fasi del sonno (HRV, piu' accelerometro se registrato; solo FC se mancano gli RR)
      val stages = stageSafely(result, db.loadAcc(sessionId), soloFc = senzaHrv)
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

      // Punto 9: buchi della notte (minuti persi e causa probabile)
      val buchi = interruzioni(sessionId, phoneMs, result.summary.startMs, result.summary.endMs)

      // Invio a Intervals.icu: in un lavoro separato con vincolo di rete e nuovi tentativi
      // (InvioNotteWorker). Dopo l'invio riuscito della notte di oggi parte il coach.
      val settings = IntervalsSettings(appContext)
      var intervalsLine: String? = null
      if (AutoStopLog.testMode(appContext)) {
        AutoStopLog.write(appContext, "Notte $sessionId analizzata [PROVA]: invio a Intervals.icu e coach saltati")
      } else if (settings.isConfigured && settings.autoUpload) {
        InvioNotteWorker.accoda(appContext, sessionId, "notte appena analizzata")
        intervalsLine = "Invio a Intervals.icu in corso (parte appena c'è rete)"
      } else {
        AutoStopLog.write(appContext, "Notte $sessionId: invio non fatto " +
            "(collegato=${settings.isConfigured}, invio automatico=${settings.autoUpload})")
      }
      buchi?.let { intervalsLine = "Interruzioni: $it" + (intervalsLine?.let { l -> "\n$l" } ?: "") }

      // Riepilogo nella notifica: al mattino non serve aprire l'app
      NightNotifier.notifySummary(appContext, result.summary, stages, intervalsLine)
    } catch (e: Exception) {
      Log.e(TAG, "Analisi sessione $sessionId fallita", e)
      logState.addLogError("analisi della sessione $sessionId fallita: ${e.message}")
    } finally {
      if (isNewNight) analyzing.value = false
    }
  }

  /** Calcola e salva i buchi della notte; ritorna il testo per la notifica (null se nessuno). */
  @Suppress("TooGenericExceptionCaught")
  private fun interruzioni(sessionId: Long, phoneMs: LongArray, startMs: Long, endMs: Long): String? =
      try {
        val eventi = EventiNotte.get(appContext).eventiTra(startMs - 60_000, endMs + 60_000)
        val r = RilevaInterruzioni.trova(phoneMs, eventi)
        EventiNotte.get(appContext).salvaInterruzioni(sessionId, r, endMs)
        r.causaBreve()?.let { causa -> segnalaInterruzione(endMs, r.minutiPersi, causa) }
        r.testo()?.also { AutoStopLog.write(appContext, "Notte $sessionId: $it") }
      } catch (e: Exception) {
        Log.e(TAG, "Calcolo interruzioni fallito", e)
        null
      }

  /**
   * Contratto con la Parte 3 (card in Oggi): SharedPreferences "biosleep_interruzioni", chiave
   * "ultima", JSON {"data": mattina del risveglio, "minuti": minuti persi, "causa": testo breve}.
   * Una sola voce, sovrascritta a ogni notte interrotta; notti senza interruzioni: nulla.
   * Le notti di prova (modalita' prova degli script) non la scrivono.
   */
  private fun segnalaInterruzione(fineNotteMs: Long, minuti: Int, causa: String) {
    if (minuti <= 0) return
    if (AutoStopLog.testMode(appContext)) {
      AutoStopLog.write(appContext, "Interruzione di prova non segnalata alla card ($minuti min, $causa)")
      return
    }
    val data = Instant.ofEpochMilli(fineNotteMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    val json = JSONObject().put("data", data).put("minuti", minuti).put("causa", causa).toString()
    appContext.getSharedPreferences(PREFS_INTERRUZIONI, Context.MODE_PRIVATE).edit().putString("ultima", json).apply()
  }

  /** Notti degli ultimi 7 giorni mai inviate o fallite per la rete: di nuovo in coda. */
  @Suppress("TooGenericExceptionCaught")
  private fun accodaInviiInSospeso() {
    try {
      if (AutoStopLog.testMode(appContext)) return
      val settings = IntervalsSettings(appContext)
      if (!settings.isConfigured || !settings.autoUpload) return
      val limite = System.currentTimeMillis() - 7 * 86_400_000L
      db.listNights()
          .filter { it.summary.endMs >= limite && it.syncedAt == null }
          .filter { n ->
            val esito = n.syncStatus
            esito == null || esito.startsWith("Connessione") || esito.contains("HTTP 5")
          }
          .forEach { InvioNotteWorker.accoda(appContext, it.sessionId, "notte non ancora inviata") }
    } catch (e: Exception) {
      Log.e(TAG, "Controllo invii in sospeso fallito", e)
    }
  }

  @Suppress("TooGenericExceptionCaught")
  private fun stageSafely(result: NightResult, acc: AccSeconds?, soloFc: Boolean = false): SleepStages? =
      try {
        SleepStager.stage(result.beats, acc, soloFc)
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
    return "${BuildConfig.APP_NAME} notte ${s.sessionId}: ${f(hours)} h, ${s.beats} battiti, " +
        "interruzioni ${s.gaps}, buoni ${f(s.pctGood)} %, corretti ${f(s.pctCorrected)} %, " +
        "scartati ${f(s.pctDropped)} % | FC min ${f(s.hrMin, 0)}, riposo ${f(s.restingHr, 0)}, " +
        "media ${f(s.hrAvg, 0)} bpm | rMSSD ${f(s.rmssd)} ms, SDNN ${f(s.sdnn)} ms, " +
        "pNN50 ${f(s.pnn50)} % | finestre ${s.windowsOk}/${s.windowsTotal}"
  }
}
