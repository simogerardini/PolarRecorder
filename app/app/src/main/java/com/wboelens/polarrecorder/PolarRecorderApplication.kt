package com.wboelens.polarrecorder

import com.wboelens.polarrecorder.biosleep.demo.DemoParte3
import com.wboelens.polarrecorder.biosleep.web.FontiParte3Noctalix
import com.wboelens.polarrecorder.biosleep.ponte.Parte3Ponte
import com.wboelens.polarrecorder.biosleep.auto.StatoRegistrazione
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.wboelens.polarrecorder.biosleep.cervello.Lingua
import com.wboelens.polarrecorder.dataSavers.DataSavers
import com.wboelens.polarrecorder.managers.PolarManager
import com.wboelens.polarrecorder.managers.PreferencesManager
import com.wboelens.polarrecorder.recording.AndroidAppInfoProvider
import com.wboelens.polarrecorder.recording.RecordingOrchestrator
import com.wboelens.polarrecorder.services.RecordingService
import com.wboelens.polarrecorder.services.RecordingServiceConnection
import com.wboelens.polarrecorder.state.DeviceState
import com.wboelens.polarrecorder.state.LogState

/**
 * Application class that holds Application-scoped state and managers. This ensures that recording
 * infrastructure survives Activity restarts during long recording sessions.
 */
class PolarRecorderApplication : Application() {
  // Application-scoped state holders (survive Activity restart)
  val deviceState = DeviceState()
  val logState = LogState()

  // Preferences are initialized immediately as they have no dependencies
  lateinit var preferencesManager: PreferencesManager
    private set

  // Managers are initialized lazily when first Activity starts
  private var _polarManager: PolarManager? = null
  private var _dataSavers: DataSavers? = null
  private var _recordingOrchestrator: RecordingOrchestrator? = null

  val polarManager: PolarManager?
    get() = _polarManager

  val dataSavers: DataSavers?
    get() = _dataSavers

  val recordingOrchestrator: RecordingOrchestrator?
    get() = _recordingOrchestrator

  // Service connection for recording control
  private var _serviceConnection: RecordingServiceConnection? = null

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    Lingua.allineaLocale(newConfig)
  }

  override fun onCreate() {
    super.onCreate()
    // lingua non coperta dal telefono: date e numeri in inglese come l'interfaccia
    Lingua.allineaLocale(resources.configuration)
    // modalita' demo (Parte 1): dati di esempio della Parte 3 per Oggi, Calendario, Grafici
    com.wboelens.polarrecorder.biosleep.demo.Demo.parte3 = { c, attiva -> DemoParte3.cambia(c, attiva) }
    DemoParte3.inizializza(this)
    preferencesManager = PreferencesManager(applicationContext)
    // ponte web: i dati della Parte 3 per la copia cifrata e i comandi dal sito
    Parte3Ponte.fonti = FontiParte3Noctalix
    // avvio automatico: rinnova l'osservazione della fascia associata
    com.wboelens.polarrecorder.biosleep.auto.AutoStart.assicuraOsservazione(this)
    // Parte 11: notte da Garmin Connect (se scelto come sorgente): controllo orario + uno subito
    com.wboelens.polarrecorder.biosleep.garmin.GarminScelta.programma(this)
    com.wboelens.polarrecorder.biosleep.garmin.GarminScelta.subito(this)
  }

  /**
   * Initialize managers if they haven't been initialized yet. Called from MainActivity.onCreate().
   * Uses Application-scoped state classes so managers survive Activity restarts.
   */
  fun ensureManagersInitialized() {
    if (_polarManager == null) {
      _dataSavers = DataSavers(applicationContext, logState, preferencesManager)
      _polarManager = PolarManager(applicationContext, deviceState, logState)
      _recordingOrchestrator =
          RecordingOrchestrator(
              polarManager = _polarManager!!,
              deviceState = deviceState,
              logState = logState,
              preferencesManager = preferencesManager,
              dataSavers = _dataSavers!!,
              appInfoProvider = AndroidAppInfoProvider(applicationContext),
          )
    }
  }

  /** Get or create the service connection for recording control. */
  fun getServiceConnection(): RecordingServiceConnection {
    if (_serviceConnection == null) {
      _serviceConnection = RecordingServiceConnection(this)
    }
    return _serviceConnection!!
  }

  /**
   * Registrazione in corso, dallo stato reale: quello del servizio se l'app e' collegata, altrimenti
   * servizio vivo E una sessione aperta nel database. Il solo "servizio vivo" non basta: il servizio
   * puo' essere in esecuzione senza registrare, e l'avvio automatico si fermava per questo.
   */
  val isRecordingActive: Boolean
    get() {
      val dalServizio = _serviceConnection?.binder?.value?.recordingState?.value?.isRecording
      if (dalServizio != null) return dalServizio
      val vivo = isServiceRunning(RecordingService::class.java)
      if (!vivo) return false
      val aperte =
          runCatching { com.wboelens.polarrecorder.biosleep.SleepDb.get(this).openSessions().size }
              .getOrElse { return true } // database illeggibile: meglio non avviare una seconda notte
      return StatoRegistrazione.inCorso(null, vivo, aperte)
    }

  @Suppress("DEPRECATION")
  private fun isServiceRunning(serviceClass: Class<*>): Boolean {
    val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    for (service in manager.getRunningServices(Int.MAX_VALUE)) {
      if (serviceClass.name == service.service.className) {
        return true
      }
    }
    return false
  }

  /**
   * Cleanup managers when no recording is active. This should be called when the app is being
   * closed and no recording is in progress.
   */
  fun cleanupIfNotRecording() {
    if (!isRecordingActive) {
      _recordingOrchestrator?.cleanup()
      _polarManager?.cleanup()
      deviceState.cleanup()
      _polarManager = null
      _dataSavers?.asList()?.forEach { it.stopSaving() }
      _dataSavers = null
      _recordingOrchestrator = null
    }
  }
}
