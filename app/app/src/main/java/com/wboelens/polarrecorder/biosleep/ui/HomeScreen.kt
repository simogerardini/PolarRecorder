package com.wboelens.polarrecorder.biosleep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.PolarRecorderApplication
import com.wboelens.polarrecorder.biosleep.auto.NightProfile
import com.wboelens.polarrecorder.biosleep.auto.NightProfileStore
import com.wboelens.polarrecorder.biosleep.setup.BioSleepSetup
import com.wboelens.polarrecorder.managers.PolarManager
import com.wboelens.polarrecorder.services.RecordingServiceConnection
import com.wboelens.polarrecorder.state.ConnectionState
import kotlinx.coroutines.delay

/**
 * Schermata iniziale di BioSleep. Tre stati:
 *  1. nessuna fascia configurata -> elenco fasce trovate, un solo tasto "Connetti";
 *  2. fascia configurata -> "Avvia notte" e accesso a notti, eta', Intervals;
 *  3. notte in corso -> durata, stato del segnale, "Termina notte".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    polarManager: PolarManager,
    serviceConnection: RecordingServiceConnection,
    onStartNight: () -> Unit,
    onNightStopped: () -> Unit,
    onOpenNights: () -> Unit,
    onOpenBioAge: () -> Unit,
    onOpenIntervals: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
  val context = LocalContext.current
  val app = context.applicationContext as PolarRecorderApplication
  val profileStore = remember { NightProfileStore(context) }
  var profile by remember { mutableStateOf(profileStore.load()) }

  val binder by serviceConnection.binder.collectAsState()
  val recording = binder?.recordingState?.collectAsState()?.value
  val isRecording = recording?.isRecording == true

  Scaffold(
      bottomBar = bottomBar,
      topBar = {
        TopAppBar(
            title = { Text("BioSleep") },
            actions = {
              if (profile != null) {
                IconButton(onClick = onOpenNights) { Icon(Icons.Filled.Bedtime, "Le mie notti") }
                IconButton(onClick = onOpenBioAge) { Icon(Icons.Filled.HourglassTop, "Età BioSleep") }
                IconButton(onClick = onOpenIntervals) { Icon(Icons.Filled.Settings, "Intervals.icu") }
              }
            },
        )
      }
  ) { padding ->
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      val p = profile
      when {
        isRecording -> NightInProgress(recording!!.recordingStartTime, app, serviceConnection, onNightStopped)
        p == null -> SetupStrap(polarManager, app) { profile = it }
        else ->
            ReadyCard(p, isRecording, onStartNight, onOpenNights) {
              profileStore.clear()
              profile = null
            }
      }
    }
  }
}

// --- 1. Configurazione della fascia ------------------------------------------------------------

@Composable
private fun SetupStrap(
    polarManager: PolarManager,
    app: PolarRecorderApplication,
    onConfigured: (NightProfile) -> Unit,
) {
  val context = LocalContext.current
  val devices by app.deviceState.allDevices.collectAsState()
  var connectingId by remember { mutableStateOf<String?>(null) }
  var error by remember { mutableStateOf<String?>(null) }

  // Cerca le fasce solo mentre questa schermata e' visibile
  DisposableEffect(Unit) {
    polarManager.startPeriodicScanning()
    onDispose { polarManager.stopPeriodicScanning() }
  }

  // Collegata: legge cosa sa fare la fascia, sceglie le impostazioni, salva, scollega
  val connectingState = devices.find { it.info.deviceId == connectingId }?.connectionState
  LaunchedEffect(connectingId, connectingState) {
    val id = connectingId ?: return@LaunchedEffect
    val device = devices.find { it.info.deviceId == id } ?: return@LaunchedEffect
    when (connectingState) {
      ConnectionState.CONNECTED -> {
        val caps = polarManager.getDeviceCapabilities(id)
        if (caps == null) {
          error = "Collegata, ma non riesco a leggere le funzioni della fascia. Riprova."
        } else {
          val profile = BioSleepSetup.buildProfile(id, device.info.name, caps)
          BioSleepSetup.finish(context, profile)
          onConfigured(profile)
        }
        polarManager.disconnectDevice(id) // la notte si ricollega da sola con "Avvia notte"
        connectingId = null
      }
      ConnectionState.FAILED -> {
        error = "Collegamento non riuscito: la fascia è indossata con gli elettrodi bagnati?"
        connectingId = null
        polarManager.startPeriodicScanning()
      }
      else -> Unit
    }
  }
  // Tempo massimo per il collegamento
  LaunchedEffect(connectingId) {
    val id = connectingId ?: return@LaunchedEffect
    delay(90_000)
    if (connectingId == id) {
      polarManager.disconnectDevice(id)
      error = "La fascia non risponde. Avvicinala al telefono e riprova."
      connectingId = null
      polarManager.startPeriodicScanning()
    }
  }

  Text("Collega la tua fascia", style = MaterialTheme.typography.headlineSmall)
  Text(
      "1. Bagna gli elettrodi e indossa la fascia: si accende da sola.\n" +
          "2. Quando compare qui sotto, premi Connetti.\n" +
          "BioSleep imposta tutto da solo: battito e intervalli RR, e con la Polar H10 anche " +
          "movimento e respiro.",
      style = MaterialTheme.typography.bodyMedium,
  )
  error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

  val polar = devices.filter { it.info.name.startsWith("Polar") }
  if (polar.isEmpty()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      CircularProgressIndicator(Modifier.size(20.dp))
      Spacer(Modifier.width(12.dp))
      Text("Ricerca della fascia…")
    }
  }
  polar.forEach { d ->
    OutlinedCard(Modifier.fillMaxWidth()) {
      Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(d.info.name, style = MaterialTheme.typography.titleMedium)
          if (!d.info.name.startsWith("Polar H10")) {
            Text(
                "Senza accelerometro: solo battito e HRV",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        if (connectingId == d.info.deviceId) {
          CircularProgressIndicator(Modifier.size(24.dp))
        } else {
          Button(
              onClick = {
                error = null
                polarManager.stopPeriodicScanning() // il collegamento e' piu' rapido senza scansione
                connectingId = d.info.deviceId
                polarManager.connectToDevice(d.info.deviceId)
              },
              enabled = connectingId == null,
          ) {
            Text("Connetti")
          }
        }
      }
    }
  }
}

// --- 2. Pronta --------------------------------------------------------------------------------

@Composable
private fun ReadyCard(
    profile: NightProfile,
    isRecording: Boolean,
    onStartNight: () -> Unit,
    onOpenNights: () -> Unit,
    onChangeStrap: () -> Unit,
) {
  var starting by remember { mutableStateOf(false) }
  var askChange by remember { mutableStateOf(false) }
  LaunchedEffect(starting) {
    if (starting) {
      delay(120_000) // se dopo 2 minuti non e' partita, la notifica spiega il motivo
      starting = false
    }
  }
  LaunchedEffect(isRecording) { if (isRecording) starting = false }

  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Pronta per la notte", style = MaterialTheme.typography.headlineSmall)
      Text(
          "${profile.deviceName.ifBlank { profile.deviceId }} · " +
              profile.dataTypes.joinToString(" + ") { if (it.name == "ACC") "movimento e respiro" else "battito e HRV" },
          style = MaterialTheme.typography.bodyMedium,
      )
      Text(
          if (starting) "Collegamento alla fascia in corso…"
          else "Indossa la fascia e premi Avvia notte. Al mattino si ferma da sola quando la togli.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Button(
          onClick = {
            starting = true
            onStartNight()
          },
          enabled = !starting,
          modifier = Modifier.fillMaxWidth().height(56.dp),
      ) {
        Text(if (starting) "Avvio in corso…" else "Avvia notte")
      }
    }
  }
  OutlinedButton(onClick = onOpenNights, modifier = Modifier.fillMaxWidth()) { Text("Le mie notti") }
  TextButton(onClick = { askChange = true }) { Text("Cambia fascia") }

  if (askChange) {
    AlertDialog(
        onDismissRequest = { askChange = false },
        title = { Text("Cambiare fascia?") },
        text = { Text("Le notti registrate restano. Poi collegherai la nuova fascia.") },
        confirmButton = {
          TextButton(onClick = {
            askChange = false
            onChangeStrap()
          }) { Text("Cambia") }
        },
        dismissButton = { TextButton(onClick = { askChange = false }) { Text("Annulla") } },
    )
  }
}

// --- 3. Notte in corso ------------------------------------------------------------------------

@Composable
private fun NightInProgress(
    startMs: Long,
    app: PolarRecorderApplication,
    serviceConnection: RecordingServiceConnection,
    onNightStopped: () -> Unit,
) {
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  var askStop by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    while (true) {
      now = System.currentTimeMillis()
      delay(15_000)
    }
  }
  val lastBeat = app.dataSavers?.bioSleep?.lastValidBeatMs ?: 0L
  val silentMin = ((now - lastBeat) / 60_000).toInt()

  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Notte in corso", style = MaterialTheme.typography.headlineSmall)
      Text(
          "Da ${hm(((now - startMs) / 60_000).toInt())} · iniziata alle ${hourLabel(startMs)}",
          style = MaterialTheme.typography.titleMedium,
      )
      Text(
          if (silentMin < 2) "Segnale della fascia: OK"
          else "Nessun battito da $silentMin minuti: la fascia è indossata?",
          style = MaterialTheme.typography.bodyMedium,
          color = if (silentMin < 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
      )
      OutlinedButton(onClick = { askStop = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Termina notte")
      }
    }
  }
  if (askStop) {
    AlertDialog(
        onDismissRequest = { askStop = false },
        title = { Text("Terminare la notte?") },
        text = { Text("La registrazione si ferma e la notte viene analizzata.") },
        confirmButton = {
          TextButton(onClick = {
            askStop = false
            serviceConnection.stopRecordingService()
            onNightStopped()
          }) { Text("Termina") }
        },
        dismissButton = { TextButton(onClick = { askStop = false }) { Text("Continua") } },
    )
  }
}
