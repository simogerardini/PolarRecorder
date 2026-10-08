package com.wboelens.polarrecorder

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.core.content.ContextCompat
import com.wboelens.polarrecorder.biosleep.BioSleepDataSaver
import com.wboelens.polarrecorder.biosleep.SleepDb
import com.wboelens.polarrecorder.biosleep.auto.HabitLearner
import com.wboelens.polarrecorder.biosleep.auto.NightNotifier
import com.wboelens.polarrecorder.biosleep.auto.NightProfileStore
import com.wboelens.polarrecorder.biosleep.cache.CacheSync
import com.wboelens.polarrecorder.biosleep.cervello.CoachWorker
import com.wboelens.polarrecorder.biosleep.protezione.Protezione
import com.wboelens.polarrecorder.biosleep.intervals.OAuthIntervals
import com.wboelens.polarrecorder.biosleep.riepilogo.RiepilogoLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.wboelens.polarrecorder.biosleep.ui.BackupScreen
import com.wboelens.polarrecorder.biosleep.ui.ProtezioneScreen
import com.wboelens.polarrecorder.biosleep.ui.FasciaScreen
import com.wboelens.polarrecorder.biosleep.ui.GareScreen
import com.wboelens.polarrecorder.biosleep.ui.LicenzeScreen
import com.wboelens.polarrecorder.biosleep.ui.ProfiloScreen
import com.wboelens.polarrecorder.biosleep.ui.TagScreen
import com.wboelens.polarrecorder.biosleep.ui.SonnoScreen
import com.wboelens.polarrecorder.biosleep.ui.BioAgeScreen
import com.wboelens.polarrecorder.biosleep.ui.HomeScreen
import com.wboelens.polarrecorder.biosleep.ui.IntervalsSettingsScreen
import com.wboelens.polarrecorder.services.RecordingService
import com.wboelens.polarrecorder.biosleep.ui.NightDetailScreen
import com.wboelens.polarrecorder.biosleep.ui.NightsScreen
import com.wboelens.polarrecorder.biosleep.ui.allenamento.AttivitaScreen
import com.wboelens.polarrecorder.biosleep.ui.allenamento.BarraBioSleep
import com.wboelens.polarrecorder.biosleep.ui.allenamento.CalendarioScreen
import com.wboelens.polarrecorder.biosleep.ui.allenamento.GestisciLinkRiepilogo
import com.wboelens.polarrecorder.biosleep.ui.allenamento.GraficiScreen
import com.wboelens.polarrecorder.biosleep.ui.allenamento.OggiScreen
import com.wboelens.polarrecorder.biosleep.ui.allenamento.RiepilogoScreen
import com.wboelens.polarrecorder.managers.PermissionManager
import com.wboelens.polarrecorder.managers.PolarManager
import com.wboelens.polarrecorder.managers.PreferencesManager
import com.wboelens.polarrecorder.services.RecordingServiceConnection
import com.wboelens.polarrecorder.ui.components.LogMessageSnackbarHost
import com.wboelens.polarrecorder.ui.components.SnackbarMessageDisplayer
import com.wboelens.polarrecorder.ui.theme.NoctalixTheme
import com.wboelens.polarrecorder.viewModels.LogViewModel
import com.wboelens.polarrecorder.viewModels.ViewModelFactory

class MainActivity : ComponentActivity() {
  // Get Application instance for accessing Application-scoped state
  private val app: PolarRecorderApplication
    get() = application as PolarRecorderApplication

  // ViewModels use factory to inject Application-scoped state
  private val logViewModel: LogViewModel by viewModels {
    ViewModelFactory(app.deviceState, app.logState)
  }

  // These are now retrieved from Application
  private lateinit var polarManager: PolarManager
  private lateinit var permissionManager: PermissionManager
  private lateinit var preferencesManager: PreferencesManager

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
    RiepilogoLink.daIntent(intent) // tocco sulla notifica del riepilogo del coach
    gestisciRitornoOAuth(intent)
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
    RiepilogoLink.daIntent(intent) // app aperta dalla notifica "Piano pronto"
    CoachWorker.assicuraRipiego(this) // coach di ripiego delle 10:30, se non e' gia' in coda
    gestisciRitornoOAuth(intent)
    OAuthIntervals.preparaSeAggiornata(this) // a ogni aggiornamento: campi BioSleep su Intervals.icu
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

    // Get service connection from Application
    serviceConnection = app.getServiceConnection()

    // Determine start destination based on recording state
    // BioSleep: si parte sempre dalla schermata iniziale (che mostra anche la notte in corso)
    val startDestination = "oggi"

    permissionManager = PermissionManager(this)

    setContent {
      NoctalixTheme {
        val navController = rememberNavController()

        // Get the snackbarHostState from the ErrorHandler
        val (snackbarHostState, currentLogType) =
            SnackbarMessageDisplayer(logViewModel = logViewModel)

        LaunchedEffect(Unit) {
          permissionManager.checkAndRequestPermissions {
            Log.d(TAG, "Necessary permissions for scanning granted")
            // Ricerca della fascia solo se va ancora configurata
            if (navController.currentDestination?.route == "home" &&
                NightProfileStore(this@MainActivity).load() == null) {
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

        // BioSleep: tocco sulla notifica del riepilogo del coach -> schermata Riepilogo
        GestisciLinkRiepilogo(navController)

        // Primo avvio: guida "Protezione notturna" una volta (poi resta in Impostazioni)
        LaunchedEffect(Unit) {
          if (!Protezione.guidaVista(applicationContext)) navController.navigate("protezione")
        }

        // BioSleep: notte chiusa all'apertura -> elenco notti, poi il dettaglio quando e' pronta
        val morningStop by morningStopRequest.collectAsState()
        LaunchedEffect(morningStop) {
          if (!morningStop) return@LaunchedEffect
          // Pila pulita: indietro dalle notti si torna alla schermata iniziale
          navController.navigate("oggi") {
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
            // BioSleep Parte 3: Oggi, Calendario, Grafici, Riepilogo del coach
            composable("oggi") {
              OggiScreen(
                  bottomBar = { BarraBioSleep(navController) },
                  onApriSeduta = { data, evento ->
                    navController.navigate("calendario?data=$data" + (evento?.let { "&evento=$it" } ?: ""))
                  },
                  onApriRiepilogo = { data -> navController.navigate("riepilogo/$data") },
                  onApriImpostazioni = { navController.navigate("intervalsSettings") },
                  onApriTag = { data -> navController.navigate("tag/$data") },
                  onApriProtezione = { navController.navigate("protezione") },
              )
            }
            composable(
                "calendario?data={data}&evento={evento}",
                arguments =
                    listOf(
                        navArgument("data") {
                          type = NavType.StringType
                          nullable = true
                          defaultValue = null
                        },
                        navArgument("evento") {
                          type = NavType.StringType
                          nullable = true
                          defaultValue = null
                        },
                    ),
            ) { entry ->
              CalendarioScreen(
                  data = entry.arguments?.getString("data")?.let { java.time.LocalDate.parse(it) },
                  evento = entry.arguments?.getString("evento"),
                  bottomBar = { BarraBioSleep(navController) },
                  onApriAttivita = { id -> navController.navigate("attivita/$id") },
              )
            }
            composable("grafici") { GraficiScreen(bottomBar = { BarraBioSleep(navController) }) }
            composable("sonno") { SonnoScreen(onBack = { navController.navigateUp() }) }
            composable("profilo") {
              ProfiloScreen(onBack = { navController.navigateUp() }, onApriGare = { navController.navigate("gare") })
            }
            composable("gare") { GareScreen(onBack = { navController.navigateUp() }) }
            composable("licenze") { LicenzeScreen(onBack = { navController.navigateUp() }) }
            composable("backup") { BackupScreen(onBack = { navController.navigateUp() }) }
            composable("protezione") { ProtezioneScreen(onBack = { navController.navigateUp() }) }
            composable("fascia") { FasciaScreen(onBack = { navController.navigateUp() }) }
            composable("tag/{data}") { entry ->
              TagScreen(
                  dataIniziale = entry.arguments?.getString("data") ?: java.time.LocalDate.now().toString(),
                  onBack = { navController.navigateUp() })
            }
            composable("attivita/{id}") { entry ->
              AttivitaScreen(id = entry.arguments?.getString("id") ?: "", onBack = { navController.navigateUp() })
            }
            composable("riepilogo/{data}") { entry ->
              RiepilogoScreen(
                  data = entry.arguments?.getString("data") ?: java.time.LocalDate.now().toString(),
                  onBack = { navController.navigateUp() },
                  onApriSeduta = { data -> navController.navigate("calendario?data=$data") },
              )
            }
            // BioSleep: schermata iniziale (configurazione fascia, avvio, notte in corso)
            composable("home") {
              HomeScreen(
                  polarManager = polarManager,
                  serviceConnection = serviceConnection,
                  onStartNight = {
                    val intent =
                        Intent(this@MainActivity, RecordingService::class.java)
                            .setAction(RecordingService.ACTION_START_NIGHT)
                    ContextCompat.startForegroundService(this@MainActivity, intent)
                  },
                  onNightStopped = { navController.navigate("nights") },
                  onOpenNights = { navController.navigate("nights") },
                  onOpenBioAge = { navController.navigate("bioAge") },
                  onOpenIntervals = { navController.navigate("intervalsSettings") },
                  bottomBar = { BarraBioSleep(navController) },
                  onOpenSleep = { navController.navigate("sonno") },
              )
            }
            // BioSleep: elenco notti e dettaglio di una notte
            composable("nights") {
              NightsScreen(
                  onBack = { navController.navigateUp() },
                  onOpenNight = { id -> navController.navigate("night/$id") },
                  onOpenSettings = { navController.navigate("intervalsSettings") },
                  onOpenBioAge = { navController.navigate("bioAge") },
              )
            }
            composable("bioAge") { BioAgeScreen(onBack = { navController.navigateUp() }) }
            composable("intervalsSettings") {
              IntervalsSettingsScreen(
                  onBack = { navController.navigateUp() },
                  onApriProfilo = { navController.navigate("profilo") },
                  onApriGare = { navController.navigate("gare") },
                  onApriLicenze = { navController.navigate("licenze") },
                  onApriBackup = { navController.navigate("backup") },
                  onApriProtezione = { navController.navigate("protezione") },
                  onApriFascia = { navController.navigate("fascia") },
              )
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
          }
        }
      }
    }
  }

  /**
   * Ritorno dal collegamento a Intervals.icu (App Link verificato sul dominio del Worker): il
   * controllo dello state e il salvataggio del token avvengono fuori dal main thread, poi si apre
   * la schermata Impostazioni con l'esito. L'URI si toglie dall'intent: un ritorno vale una volta.
   */
  private fun gestisciRitornoOAuth(intent: Intent?) {
    if (!OAuthIntervals.eRitorno(intent)) return
    val uri = intent?.data ?: return
    intent.data = null
    val app = applicationContext
    kotlin.concurrent.thread(name = "biosleep-oauth") { OAuthIntervals.gestisci(app, uri) }
    RiepilogoLink.richiesta.value = "intervalsSettings"
  }

  override fun onStart() {
    super.onStart()
    CacheSync.aggiornaInBackground(this)
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
