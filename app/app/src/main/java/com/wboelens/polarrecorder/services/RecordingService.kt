package com.wboelens.polarrecorder.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.polar.sdk.api.PolarBleApi
import com.wboelens.polarrecorder.PolarRecorderApplication
import com.wboelens.polarrecorder.recording.EventLogEntry
import com.wboelens.polarrecorder.recording.RecordingOrchestrator
import com.wboelens.polarrecorder.recording.StartRecordingResult
import com.wboelens.polarrecorder.state.LogState
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import com.wboelens.polarrecorder.biosleep.SleepDb
import com.wboelens.polarrecorder.biosleep.auto.HabitLearner
import com.wboelens.polarrecorder.biosleep.auto.NightNotifier
import com.wboelens.polarrecorder.biosleep.auto.NightProfile
import com.wboelens.polarrecorder.biosleep.auto.NightProfileStore
import com.wboelens.polarrecorder.biosleep.auto.NightStarter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private const val AUTO_STOP_NO_DATA_MS = 10 * 60_000L // 10 minuti senza battiti
    private const val AUTO_STOP_MIN_RECORDING_MS = 2 * 3_600_000L // solo dopo almeno 2 ore
    private const val AUTO_STOP_CHECK_MS = 60_000L
    private const val NOTIFICATION_ID = 1
    private const val CHANNEL_ID = "RecordingServiceChannel"
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
  private var autoStopJob: Job? = null

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
   * Al mattino, quando la fascia viene tolta, non arrivano piu' battiti: dopo 10 minuti senza dati,
   * nella fascia oraria del mattino e dopo almeno 2 ore di registrazione, si ferma da sola.
   */
  private fun startAutoStopMonitor() {
    autoStopJob?.cancel()
    autoStopJob =
        scope.launch {
          val habits =
              withContext(Dispatchers.IO) {
                HabitLearner.learn(
                    SleepDb.get(this@RecordingService).nightTimes(),
                    System.currentTimeMillis(),
                )
              }
          val bioSleep = app.dataSavers?.bioSleep ?: return@launch
          while (isActive) {
            delay(AUTO_STOP_CHECK_MS)
            val state = orchestrator.recordingState.value
            if (!state.isRecording) break
            val now = System.currentTimeMillis()
            val lastBeat = maxOf(bioSleep.lastValidBeatMs, state.recordingStartTime)
            if (
                now - state.recordingStartTime >= AUTO_STOP_MIN_RECORDING_MS &&
                    now - lastBeat >= AUTO_STOP_NO_DATA_MS &&
                    HabitLearner.isMorning(now, habits)
            ) {
              logState.addLogMessage("BioSleep: fascia tolta, registrazione fermata in automatico")
              doStopRecording()
              break
            }
          }
        }
  }

  private fun createStartingNotification(): Notification =
      NotificationCompat.Builder(this, CHANNEL_ID)
          .setContentTitle("Avvio della notte")
          .setContentText("Collegamento alla fascia in corso…")
          .setSmallIcon(android.R.drawable.ic_media_play)
          .setOngoing(true)
          .build()

  private fun stopServiceAfterRecordingEnded() {
    autoStopJob?.cancel()
    autoStopJob = null
    // Ferma l'aggiornamento della notifica, altrimenti ricompare ogni minuto dopo lo stop
    notificationUpdates?.cancel(false)
    notificationUpdates = null
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
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
              "Recording Service Channel",
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
    val durationText =
        if (minutes == 1L) {
          "1 minute"
        } else {
          "$minutes minutes"
        }

    val pendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )

    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Recording in progress")
        .setContentText("Recording for $durationText")
        .setSmallIcon(android.R.drawable.ic_media_play)
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
    autoStopJob?.cancel()

    // Cleanup orchestrator resources
    orchestrator.cleanup()

    executor.shutdownNow()
    super.onDestroy()
  }
}
