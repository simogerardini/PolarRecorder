package com.wboelens.polarrecorder.services

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.polar.sdk.api.PolarBleApi
import com.wboelens.polarrecorder.PolarRecorderApplication
import com.wboelens.polarrecorder.R
import com.wboelens.polarrecorder.recording.EventLogEntry
import com.wboelens.polarrecorder.recording.RecordingOrchestrator
import com.wboelens.polarrecorder.recording.StartRecordingResult
import com.wboelens.polarrecorder.state.LogState
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import com.wboelens.polarrecorder.biosleep.BioSleepDataSaver
import com.wboelens.polarrecorder.biosleep.SleepDb
import com.wboelens.polarrecorder.biosleep.auto.Habits
import com.wboelens.polarrecorder.biosleep.finalize.NightFinalizeWorker
import com.wboelens.polarrecorder.biosleep.watchdog.AutoStopAlarmReceiver
import com.wboelens.polarrecorder.biosleep.watchdog.AutoStopLog
import com.wboelens.polarrecorder.biosleep.watchdog.AutoStopRule
import com.wboelens.polarrecorder.biosleep.auto.HabitLearner
import com.wboelens.polarrecorder.biosleep.auto.NightNotifier
import com.wboelens.polarrecorder.biosleep.auto.NightProfile
import com.wboelens.polarrecorder.biosleep.auto.NightProfileStore
import com.wboelens.polarrecorder.biosleep.auto.NightStarter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** BioSleep: stato dell'avvio della notte, osservato dal pulsante "Avvia notte". */
sealed interface AvvioNotte {
  data object Fermo : AvvioNotte

  data object InCorso : AvvioNotte

  data class Fallito(val motivo: String) : AvvioNotte
}

/**
 * Foreground service for background recording. This service handles Android lifecycle and
 * notification concerns, while delegating all recording business logic to RecordingOrchestrator.
 */
@Suppress("TooManyFunctions")
class RecordingService : Service() {
  companion object {
    const val ACTION_START_RECORDING = "com.wboelens.polarrecorder.START_RECORDING"
    const val ACTION_STOP_RECORDING = "com.wboelens.polarrecorder.STOP_RECORDING"
    const val EXTRA_RECORDING_NAME = "recording_name"
    const val EXTRA_DEVICE_IDS = "device_ids"
    // BioSleep: avvio della notte con un tocco (profilo salvato, nessuna schermata)
    const val ACTION_START_NIGHT = "com.wboelens.polarrecorder.START_NIGHT"
    // BioSleep: controllo dello stop automatico chiesto dall'allarme di riserva
    const val ACTION_CHECK_AUTOSTOP = "com.wboelens.polarrecorder.CHECK_AUTOSTOP"
    private const val AUTO_STOP_CHECK_S = 60L // timer del controllo, indipendente dai dati
    private const val SAFETY_ALARM_MS = 10 * 60_000L // allarme di riserva (anche in Doze)
    private const val WAKELOCK_TIMEOUT_MS = 14 * 3_600_000L
    private const val FINALIZE_WAIT_MS = 5 * 60_000L
    private const val ALARM_REQUEST = 3_101
    private const val NOTIFICATION_ID = 1
    private const val CHANNEL_ID = "RecordingServiceChannel"

    private val _avvioNotte = MutableStateFlow<AvvioNotte>(AvvioNotte.Fermo)

    /**
     * Avvio della notte in corso, riuscito (torna Fermo) o fallito con il motivo. La schermata lo
     * osserva per riattivare "Avvia notte" appena il servizio rinuncia, senza timer.
     */
    val avvioNotte: StateFlow<AvvioNotte> = _avvioNotte.asStateFlow()
  }

  private val executor = Executors.newSingleThreadScheduledExecutor()
  private var notificationUpdates: ScheduledFuture<*>? = null

  // Dependencies (initialized in onCreate)
  private lateinit var orchestrator: RecordingOrchestrator
  private lateinit var logState: LogState

  // Coroutine scope for state observation
  private val scope = CoroutineScope(Dispatchers.Main + Job())
  private var connectedDevicesJob: Job? = null
  private var logMessagesJob: Job? = null
  private var selectedDevicesJob: Job? = null
  private var nightStartJob: Job? = null
  private var autoStopFuture: ScheduledFuture<*>? = null
  @Volatile private var habits: Habits? = null
  private var wakeLock: PowerManager.WakeLock? = null
  private var finalizeWaitJob: Job? = null

  // Binder
  private val binder = LocalBinder()

  inner class LocalBinder : Binder() {
    val recordingState: StateFlow<RecordingState>
      get() = orchestrator.recordingState

    val lastData: StateFlow<Map<String, Map<PolarBleApi.PolarDeviceDataType, Float?>>>
      get() = orchestrator.lastData

    val lastDataTimestamps: StateFlow<Map<String, Long>>
      get() = orchestrator.lastDataTimestamps

    val eventLogEntries: StateFlow<List<EventLogEntry>>
      get() = orchestrator.eventLogEntries

    fun startRecording(recordingName: String) {
      this@RecordingService.doStartRecording(recordingName)
    }

    fun stopRecording() {
      this@RecordingService.doStopRecording()
    }

    fun addEvent() {
      orchestrator.addEvent()
    }

    fun updateEventLabel(index: Int, label: String) {
      orchestrator.updateEventLabel(index, label)
    }

    fun getService(): RecordingService = this@RecordingService
  }

  private val app: PolarRecorderApplication
    get() = application as PolarRecorderApplication

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    initializeDependencies()
    startObservingDeviceChanges()
    startObservingLogMessages()
    keepSelectedDevicesUpdated()
  }

  /**
   * selectedDevices si aggiorna solo finche' qualcuno lo osserva. Con lo schermo dell'app chiuso
   * (avvio da notifica o in automatico) nessuna schermata lo osserva: lo fa il servizio.
   */
  private fun keepSelectedDevicesUpdated() {
    selectedDevicesJob = scope.launch { app.deviceState.selectedDevices.collect {} }
  }

  private fun initializeDependencies() {
    app.ensureManagersInitialized()
    orchestrator = app.recordingOrchestrator!!
    logState = app.logState
  }

  private fun startObservingDeviceChanges() {
    connectedDevicesJob =
        scope.launch {
          app.deviceState.connectedDevices.collect { devices ->
            val recordingStopped = orchestrator.handleDevicesChanged(devices)
            if (recordingStopped) {
              stopServiceAfterRecordingEnded()
            }
          }
        }
  }

  private fun startObservingLogMessages() {
    logMessagesJob =
        scope.launch {
          logState.logMessages.collect { messages ->
            orchestrator.handleLogMessagesChanged(messages)
          }
        }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START_RECORDING -> {
        val name = intent.getStringExtra(EXTRA_RECORDING_NAME)
        if (name != null) {
          doStartRecording(name)
        }
      }
      ACTION_STOP_RECORDING -> doStopRecording()
      ACTION_START_NIGHT -> startNight()
      ACTION_CHECK_AUTOSTOP -> {
        if (orchestrator.recordingState.value.isRecording || nightStartJob?.isActive == true) {
          executor.execute { runAutoStopCheck("allarme") }
        } else {
          // Allarme arrivato a un servizio ripartito senza registrazione: chiude le notti aperte
          AutoStopLog.write(this, "Allarme senza registrazione attiva -> chiusura notti aperte")
          NightFinalizeWorker.enqueue(this, "allarme dopo riavvio del servizio")
          stopSelf()
        }
      }
      else -> {
        // Service started without action - show notification if recording
        if (orchestrator.recordingState.value.isRecording) {
          val notification = createNotification()
          ServiceCompat.startForeground(
              this,
              NOTIFICATION_ID,
              notification,
              ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
          )
        }
      }
    }
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder = binder

  private fun doStartRecording(recordingName: String) {
    val result = orchestrator.startRecording(recordingName)

    if (result is StartRecordingResult.Success) {
      _avvioNotte.value = AvvioNotte.Fermo
      // Service-specific: start foreground notification
      val notification = createNotification()
      ServiceCompat.startForeground(
          this,
          NOTIFICATION_ID,
          notification,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
      )
      scheduleNotificationUpdates()
      saveNightProfile()
      startAutoStopMonitor()
      AutoStopLog.write(this, "Registrazione avviata: $recordingName")
    }
    // Errors are already logged by orchestrator
  }

  private fun doStopRecording() {
    logState.requestFlushQueue()

    Handler(Looper.getMainLooper()).post {
      orchestrator.stopRecording()
      stopServiceAfterRecordingEnded()
    }
  }

  // --- BioSleep: avvio con un tocco, profilo, stop automatico -------------------------------

  /** Prepara e avvia la notte con le impostazioni dell'ultima registrazione. */
  private fun startNight() {
    if (orchestrator.recordingState.value.isRecording || nightStartJob?.isActive == true) return
    _avvioNotte.value = AvvioNotte.InCorso
    // Una notte non si ferma per una disconnessione momentanea: la fascia si ricollega da sola,
    // e la fine la decide lo stop automatico (ereditato da Polar Recorder, qui sempre spento)
    app.preferencesManager.recordingStopOnDisconnect = false
    // Entro 5 secondi dall'avvio un servizio in primo piano deve mostrare la sua notifica
    ServiceCompat.startForeground(
        this,
        NOTIFICATION_ID,
        createStartingNotification(),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
    )
    nightStartJob =
        scope.launch {
          val profile = withContext(Dispatchers.IO) { NightProfileStore(this@RecordingService).load() }
          val result =
              if (profile == null) {
                NightStarter.Result.Failed(
                    "Nessuna impostazione salvata: fai prima una registrazione dalle schermate"
                )
              } else {
                NightStarter(app).prepare(profile)
              }
          when (result) {
            is NightStarter.Result.Ready -> {
              doStartRecording(result.recordingName)
              if (!orchestrator.recordingState.value.isRecording) {
                failNightStart("La registrazione non è partita, controlla l'app")
              }
            }
            is NightStarter.Result.Failed -> failNightStart(result.reason)
          }
        }
  }

  private fun failNightStart(reason: String) {
    _avvioNotte.value = AvvioNotte.Fallito(reason)
    logState.addLogError("BioSleep: avvio della notte non riuscito: $reason")
    NightNotifier.notifyAlert(this, "Avvio della notte non riuscito", reason)
    stopServiceAfterRecordingEnded()
  }

  /** Ricorda fascia, dati e parametri di questa registrazione per il prossimo "Avvia notte". */
  private fun saveNightProfile() {
    val device = app.deviceState.allDevices.value.firstOrNull { it.isSelected } ?: return
    if (device.dataTypes.isEmpty()) return
    val profile = NightProfile.fromDevice(device)
    scope.launch(Dispatchers.IO) { NightProfileStore(this@RecordingService).save(profile) }
  }

  /**
   * Stop automatico del mattino. Tre livelli, nessuno dei quali dipende dall'arrivo dei dati:
   *  1. un timer ogni 60 s sul thread del servizio (lo stesso che aggiorna la notifica);
   *  2. un wakelock parziale per tutta la registrazione, cosi' la CPU non dorme tra i controlli;
   *  3. un allarme di riserva ogni 10 minuti che funziona anche in Doze.
   * Ogni controllo scrive una riga in files/biosleep_autostop.log.
   */
  private fun startAutoStopMonitor() {
    acquireWakeLock()
    autoStopFuture?.cancel(false)
    executor.execute {
      habits = HabitLearner.learn(SleepDb.get(this).nightTimes(), System.currentTimeMillis())
      AutoStopLog.write(this, "Stop automatico attivo dalle ${HabitLearner.minutesToClock(habits!!.morningFromMinute)}")
    }
    autoStopFuture =
        executor.scheduleWithFixedDelay(
            { runAutoStopCheck("timer") }, AUTO_STOP_CHECK_S, AUTO_STOP_CHECK_S, TimeUnit.SECONDS)
    scheduleSafetyAlarm()
  }

  @Suppress("TooGenericExceptionCaught")
  private fun runAutoStopCheck(source: String) {
    try {
      val state = orchestrator.recordingState.value
      if (!state.isRecording) return
      val bioSleep = app.dataSavers?.bioSleep ?: return
      val now = System.currentTimeMillis()
      val h = habits ?: HabitLearner.learn(emptyList(), now)
      val decision =
          AutoStopRule.decide(
              now, state.recordingStartTime, bioSleep.lastValidBeatMs, bioSleep.lastPacketMs,
              HabitLearner.isMorning(now, h), AutoStopLog.testMode(this))
      AutoStopLog.write(this, "Controllo ($source): ${decision.reason}")
      if (source == "allarme") scheduleSafetyAlarm()
      if (decision.stop) {
        logState.addLogMessage("BioSleep: fascia tolta, registrazione fermata in automatico")
        doStopRecording()
      }
    } catch (e: Exception) {
      // un errore non deve mai spegnere il controllo: si riprova al giro successivo
      AutoStopLog.write(this, "Controllo ($source) fallito: ${e.javaClass.simpleName} ${e.message}")
    }
  }

  private fun safetyAlarmIntent(): PendingIntent =
      PendingIntent.getBroadcast(
          this, ALARM_REQUEST, Intent(this, AutoStopAlarmReceiver::class.java),
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

  private fun scheduleSafetyAlarm() {
    val am = getSystemService(AlarmManager::class.java) ?: return
    val at = System.currentTimeMillis() + SAFETY_ALARM_MS
    val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, safetyAlarmIntent())
    else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, safetyAlarmIntent())
  }

  private fun acquireWakeLock() {
    if (wakeLock?.isHeld == true) return
    wakeLock =
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BioSleep:notte")
            .apply { acquire(WAKELOCK_TIMEOUT_MS) }
  }

  private fun stopAutoStopMonitor() {
    autoStopFuture?.cancel(false)
    autoStopFuture = null
    getSystemService(AlarmManager::class.java)?.cancel(safetyAlarmIntent())
    if (wakeLock?.isHeld == true) wakeLock?.release()
    wakeLock = null
  }

  private fun createStartingNotification(
      title: String = "Avvio della notte",
      text: String = "Collegamento alla fascia in corso…",
  ): Notification =
      NotificationCompat.Builder(this, CHANNEL_ID)
          .setContentTitle(title)
          .setContentText(text)
          .setSmallIcon(R.drawable.ic_notifica_notte)
          .setColor(ContextCompat.getColor(this, R.color.biosleep_notifica))
          .setOngoing(true)
          .build()

  private fun stopServiceAfterRecordingEnded() {
    stopAutoStopMonitor()
    // Ferma l'aggiornamento della notifica, altrimenti ricompare ogni minuto dopo lo stop
    notificationUpdates?.cancel(false)
    notificationUpdates = null
    if (!BioSleepDataSaver.analyzing.value) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return
    }
    // Notte in analisi: il servizio resta in primo piano finche' non ha finito (max 5 minuti),
    // cosi' Android non congela il processo. L'analisi vera la fa NightFinalizeWorker.
    AutoStopLog.write(this, "Registrazione fermata: analisi della notte in corso")
    getSystemService(NotificationManager::class.java)
        .notify(NOTIFICATION_ID, createStartingNotification("Analisi della notte", "Calcolo e invio a Intervals.icu…"))
    finalizeWaitJob?.cancel()
    finalizeWaitJob =
        scope.launch {
          withTimeoutOrNull(FINALIZE_WAIT_MS) { BioSleepDataSaver.analyzing.first { !it } }
          stopForeground(STOP_FOREGROUND_REMOVE)
          stopSelf()
        }
  }

  private fun scheduleNotificationUpdates() {
    notificationUpdates?.cancel(false)
    notificationUpdates =
        executor.scheduleWithFixedDelay(
        {
          val notification = createNotification()
          val notificationManager =
              getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
          notificationManager.notify(NOTIFICATION_ID, notification)
        },
        1,
        1,
        TimeUnit.MINUTES,
    )
  }

  private fun createNotificationChannel() {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
      val channel =
          NotificationChannel(
              CHANNEL_ID,
              "Notte in corso", // nome visibile in Impostazioni > Notifiche
              NotificationManager.IMPORTANCE_LOW,
          )
      val manager = getSystemService(NotificationManager::class.java)
      manager.createNotificationChannel(channel)
    }
  }

  private fun createNotification(): Notification {
    val durationMs =
        System.currentTimeMillis() - orchestrator.recordingState.value.recordingStartTime
    val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs)
    // es. "da 6 h 05'" oppure "da 12'"
    val durationText = if (minutes >= 60) "da ${minutes / 60} h %02d'".format(minutes % 60) else "da $minutes'"

    val pendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )

    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Notte in corso")
        .setContentText("Registrazione $durationText · si ferma da sola quando togli la fascia")
        .setSmallIcon(R.drawable.ic_notifica_notte)
        .setColor(ContextCompat.getColor(this, R.color.biosleep_notifica))
        .setOngoing(true)
        .setContentIntent(pendingIntent)
        .build()
  }

  override fun onDestroy() {
    // Cancel coroutine jobs
    connectedDevicesJob?.cancel()
    logMessagesJob?.cancel()
    selectedDevicesJob?.cancel()
    nightStartJob?.cancel()
    finalizeWaitJob?.cancel()
    stopAutoStopMonitor()
    AutoStopLog.write(this, "Servizio chiuso (registrazione attiva: ${orchestrator.recordingState.value.isRecording})")
    // Servizio chiuso a meta' avvio (es. dal sistema): il pulsante non deve restare bloccato
    if (_avvioNotte.value == AvvioNotte.InCorso) _avvioNotte.value = AvvioNotte.Fermo

    // Cleanup orchestrator resources
    orchestrator.cleanup()

    executor.shutdownNow()
    super.onDestroy()
  }
}
