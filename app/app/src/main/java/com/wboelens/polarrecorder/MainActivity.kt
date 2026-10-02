package com.wboelens.polarrecorder

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.content.Intent
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import com.wboelens.polarrecorder.biosleep.BioSleepDataSaver
import com.wboelens.polarrecorder.biosleep.SleepDb
import com.wboelens.polarrecorder.biosleep.auto.HabitLearner
import com.wboelens.polarrecorder.biosleep.auto.NightNotifier
import com.wboelens.polarrecorder.biosleep.auto.NightProfileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.wboelens.polarrecorder.biosleep.ui.IntervalsSettingsScreen
import com.wboelens.polarrecorder.services.RecordingService
import com.wboelens.polarrecorder.biosleep.ui.NightDetailScreen
import com.wboelens.polarrecorder.biosleep.ui.NightsScreen
import com.wboelens.polarrecorder.dataSavers.DataSavers
import com.wboelens.polarrecorder.managers.PermissionManager
import com.wboelens.polarrecorder.managers.PolarManager
import com.wboelens.polarrecorder.managers.PreferencesManager
import com.wboelens.polarrecorder.services.RecordingServiceConnection
import com.wboelens.polarrecorder.ui.components.LogMessageSnackbarHost
import com.wboelens.polarrecorder.ui.components.SnackbarMessageDisplayer
import com.wboelens.polarrecorder.ui.screens.DataSaverInitializationScreen
import com.wboelens.polarrecorder.ui.screens.DeviceConnectionScreen
import com.wboelens.polarrecorder.ui.screens.DeviceSelectionScreen
import com.wboelens.polarrecorder.ui.screens.DeviceSettingsScreen
import com.wboelens.polarrecorder.ui.screens.RecordingScreen
import com.wboelens.polarrecorder.ui.screens.RecordingSettingsScreen
import com.wboelens.polarrecorder.ui.theme.AppTheme
import com.wboelens.polarrecorder.viewModels.DeviceViewModel
import com.wboelens.polarrecorder.viewModels.FileSystemSettingsViewModel
import com.wboelens.polarrecorder.viewModels.LogViewModel
import com.wboelens.polarrecorder.viewModels.ViewModelFactory

class MainActivity : ComponentActivity() {
  // Get Application instance for accessing Application-scoped state
  private val app: PolarRecorderApplication
    get() = application as PolarRecorderApplication

  // ViewModels use factory to inject Application-scoped state
  private val deviceViewModel: DeviceViewModel by viewModels {
    ViewModelFactory(app.deviceState, app.logState)
  }
  private val logViewModel: LogViewModel by viewModels {
    ViewModelFactory(app.deviceState, app.logState)
  }
  private val fileSystemViewModel: FileSystemSettingsViewModel by viewModels()

  // These are now retrieved from Application
  private lateinit var polarManager: PolarManager
  private lateinit var permissionManager: PermissionManager
  private lateinit var preferencesManager: PreferencesManager
  private lateinit var dataSavers: DataSavers

  // Service connection for recording control
  private lateinit var serviceConnection: RecordingServiceConnection

  companion object {
    private const val TAG = "MainActivity"
    // Matches androidx.activity's internal DefaultLightScrim — a semi-opaque white used as the
    // navigation bar scrim on API 26, where light-appearance nav bar icons aren't supported.
    private const val API_26_LIGHT_NAV_SCRIM: Int = 0xe6ffffff.toInt()
    private const val MORNING_STOP_MIN_RECORDING_MS = 2 * 3_600_000L
    private const val NIGHT_READY_TIMEOUT_MS = 3 * 60_000L
  }

  /** Notte da aprire perche' l'utente ha toccato la notifica del mattino (null = nessuna). */
  private val openNightRequest = MutableStateFlow<Long?>(null)

  private fun handleOpenNight(intent: Intent?) {
    val id = intent?.getLongExtra(NightNotifier.EXTRA_SESSION_ID, -1L) ?: -1L
    if (id >= 0) {
      openNightRequest.value = id
      intent?.removeExtra(NightNotifier.EXTRA_SESSION_ID) // non riaprirla a ogni rotazione
    }
  }

  // App gia' aperta: la notifica arriva qui invece di ricreare la schermata
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleOpenNight(intent)
  }

  /**
   * true se all'apertura dell'app (al mattino) la registrazione e' stata chiusa: invece della
   * schermata con "Stop recording" si va alle notti e, appena pronta, al dettaglio di stanotte.
   */
  private val morningStopRequest = MutableStateFlow(false)
  private var stoppedOnOpen = false
  private var justCreated = false

  /**
   * Come Oura: aprendo l'app nella fascia del mattino, con almeno 2 ore registrate, la notte
   * si chiude da sola (anche se la fascia e' ancora indossata).
   */
  private fun stopNightIfMorning(): Boolean {
    if (!app.isRecordingActive) return false
    val state = app.recordingOrchestrator?.recordingState?.value ?: return false
    if (!state.isRecording) return false
    val now = System.currentTimeMillis()
    if (now - state.recordingStartTime < MORNING_STOP_MIN_RECORDING_MS) return false
    val habits = HabitLearner.learn(SleepDb.get(this).nightTimes(), now)
    if (!HabitLearner.isMorning(now, habits)) return false

    Log.d(TAG, "Apertura al mattino: chiusura automatica della notte")
    BioSleepDataSaver.newNightReady.value = null
    startService(
        Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP_RECORDING))
    stoppedOnOpen = true
    morningStopRequest.value = true
    return true
  }

  @Suppress("LongMethod")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    handleOpenNight(intent)
    justCreated = true
    val stoppedNow = stopNightIfMorning()
    // Use light/dark SystemBarStyle (not auto) so contrast enforcement stays off and the
    // app's background shows through truly transparent system bars.
    val isDark =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    val statusBarStyle =
        if (isDark) SystemBarStyle.dark(Color.TRANSPARENT)
        else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    // On API 26, isAppearanceLightNavigationBars is a no-op (requires API 27), so nav bar icons
    // stay white. Apply a semi-opaque white scrim on API 26 only so they remain visible over
    // light app content; API 27+ gets the fully transparent bar.
    val navBarStyle =
        if (isDark) {
          SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
          val lightNavScrim =
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) Color.TRANSPARENT
              else API_26_LIGHT_NAV_SCRIM
          SystemBarStyle.light(lightNavScrim, lightNavScrim)
        }
    enableEdgeToEdge(statusBarStyle = statusBarStyle, navigationBarStyle = navBarStyle)
    Log.d(TAG, "onCreate: Initializing MainActivity")

    // Get preferences from Application (always available)
    preferencesManager = app.preferencesManager

    // Initialize managers in Application (creates them if they don't exist)
    app.ensureManagersInitialized()

    // Get references to Application-scoped managers
    polarManager = app.polarManager!!
    dataSavers = app.dataSavers!!

    // Get service connection from Application
    serviceConnection = app.getServiceConnection()

    // Determine start destination based on recording state
    val startDestination =
        if (!stoppedNow && app.isRecordingActive) "recording" else "deviceSelection"

    permissionManager = PermissionManager(this)

    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      if (result.resultCode == RESULT_OK) {
        fileSystemViewModel.handleDirectoryResult(this, result.data?.data)
      }
    }

    setContent {
      AppTheme {
        val navController = rememberNavController()
        // BioSleep: fascia dell'ultima registrazione (null = nessuna registrazione fatta ancora)
        val nightDeviceName = remember {
          NightProfileStore(this@MainActivity).load()?.let { it.deviceName.ifBlank { it.deviceId } }
        }

        // Get the snackbarHostState from the ErrorHandler
        val (snackbarHostState, currentLogType) =
            SnackbarMessageDisplayer(logViewModel = logViewModel)

        LaunchedEffect(Unit) {
          permissionManager.checkAndRequestPermissions {
            Log.d(TAG, "Necessary permissions for scanning granted")
            if (navController.currentDestination?.route == "deviceSelection") {
              polarManager.startPeriodicScanning()
            }
          }
        }

        // BioSleep: tocco sulla notifica del mattino -> dettaglio di quella notte
        val openNight by openNightRequest.collectAsState()
        LaunchedEffect(openNight) {
          openNight?.let { id ->
            navController.navigate("night/$id")
            openNightRequest.value = null
          }
        }

        // BioSleep: notte chiusa all'apertura -> elenco notti, poi il dettaglio quando e' pronta
        val morningStop by morningStopRequest.collectAsState()
        LaunchedEffect(morningStop) {
          if (!morningStop) return@LaunchedEffect
          // Pila pulita: indietro dalle notti si torna alla schermata iniziale
          navController.navigate("deviceSelection") {
            popUpTo(navController.graph.id) { inclusive = true }
          }
          navController.navigate("nights")
          val id =
              withTimeoutOrNull(NIGHT_READY_TIMEOUT_MS) {
                BioSleepDataSaver.newNightReady.filterNotNull().first()
              }
          if (id != null) navController.navigate("night/$id")
          morningStopRequest.value = false
        }

        Scaffold(snackbarHost = { LogMessageSnackbarHost(snackbarHostState, currentLogType) }) {
            paddingValues ->
          NavHost(
              navController = navController,
              startDestination = startDestination,
              modifier = Modifier.padding(paddingValues).consumeWindowInsets(paddingValues),
          ) {
            composable("deviceSelection") {
              DeviceSelectionScreen(
                  deviceViewModel = deviceViewModel,
                  polarManager = polarManager,
                  onContinue = { navController.navigate("deviceConnection") },
                  onOpenNights = { navController.navigate("nights") },
                  nightDeviceName = nightDeviceName,
                  onStartNight = {
                    val intent =
                        Intent(this@MainActivity, RecordingService::class.java)
                            .setAction(RecordingService.ACTION_START_NIGHT)
                    ContextCompat.startForegroundService(this@MainActivity, intent)
                  },
              )
              // Quando la notte e' partita si passa alla schermata di registrazione
              val binder by serviceConnection.binder.collectAsState()
              val nightRecording =
                  binder?.recordingState?.collectAsState()?.value?.isRecording == true
              LaunchedEffect(nightRecording) {
                // Dopo la chiusura al mattino il servizio resta "in registrazione" per un attimo:
                // non bisogna tornare alla schermata di registrazione
                if (nightRecording && !stoppedOnOpen) navController.navigate("recording")
              }
            }
            // BioSleep: elenco notti e dettaglio di una notte
            composable("nights") {
              NightsScreen(
                  onBack = { navController.navigateUp() },
                  onOpenNight = { id -> navController.navigate("night/$id") },
                  onOpenSettings = { navController.navigate("intervalsSettings") },
              )
            }
            composable("intervalsSettings") {
              IntervalsSettingsScreen(onBack = { navController.navigateUp() })
            }
            composable(
                "night/{sessionId}",
                arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
            ) { entry ->
              NightDetailScreen(
                  sessionId = entry.arguments?.getLong("sessionId") ?: 0L,
                  onBack = { navController.navigateUp() },
              )
            }
            composable("deviceConnection") {
              DeviceConnectionScreen(
                  deviceViewModel = deviceViewModel,
                  polarManager = polarManager,
                  onBackPressed = { navController.navigateUp() },
                  onContinue = { navController.navigate("deviceSettings") },
              )
            }
            composable("deviceSettings") {
              // skip device connection screen
              val backAction = {
                polarManager.disconnectAllDevices()
                navController.navigate("deviceSelection") {
                  popUpTo("deviceSelection") { inclusive = true }
                }
              }

              BackHandler(onBack = backAction)
              DeviceSettingsScreen(
                  deviceViewModel = deviceViewModel,
                  polarManager = polarManager,
                  onBackPressed = backAction,
                  onContinue = { navController.navigate("recordingSettings") },
              )
            }
            composable("recordingSettings") {
              RecordingSettingsScreen(
                  deviceViewModel = deviceViewModel,
                  fileSystemSettingsViewModel = fileSystemViewModel,
                  dataSavers = dataSavers,
                  preferencesManager = preferencesManager,
                  onBackPressed = { navController.navigateUp() },
                  onContinue = { navController.navigate("dataSaverInitialization") },
              )
            }
            composable("dataSaverInitialization") {
              DataSaverInitializationScreen(
                  dataSavers = dataSavers,
                  deviceViewModel = deviceViewModel,
                  serviceConnection = serviceConnection,
                  preferencesManager = preferencesManager,
                  onBackPressed = { navController.navigateUp() },
                  onContinue = { navController.navigate("recording") },
              )
            }
            composable("recording") {
              // Observe recording state from service
              val binder by serviceConnection.binder.collectAsState()
              val recordingState by
                  binder?.recordingState?.collectAsState()
                      ?: androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(
                            com.wboelens.polarrecorder.services.RecordingState()
                        )
                      }

              // skip data saver initialisation screen
              val backAction = {
                if (recordingState.isRecording) {
                  serviceConnection.stopRecordingService()
                }
                navController.navigate("recordingSettings") {
                  popUpTo("recordingSettings") { inclusive = true }
                }
              }

              BackHandler(onBack = backAction)
              RecordingScreen(
                  deviceViewModel = deviceViewModel,
                  serviceConnection = serviceConnection,
                  dataSavers = dataSavers,
                  onBackPressed = backAction,
                  onRestartRecording = { navController.navigate("dataSaverInitialization") },
                  onOpenNights = { navController.navigate("nights") },
              )
            }
          }
        }
      }
    }
  }

  override fun onStart() {
    super.onStart()
    // App gia' aperta e tornata in primo piano (onCreate non viene richiamato)
    if (!justCreated) stopNightIfMorning()
    justCreated = false
    // Always bind to service to observe state
    serviceConnection.bind()
    Log.d(TAG, "Service bound")
  }

  override fun onStop() {
    super.onStop()
    // Unbind from service (service keeps running if recording)
    serviceConnection.unbind()
    Log.d(TAG, "Service unbound")
  }

  override fun onDestroy() {
    super.onDestroy()
    // Only cleanup managers if no recording is active
    // Managers will persist in Application scope if recording continues
    if (!app.isRecordingActive) {
      app.cleanupIfNotRecording()
    }
  }
}
