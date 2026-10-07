package com.wboelens.polarrecorder.biosleep.auto

import com.wboelens.polarrecorder.PolarRecorderApplication
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType
import com.wboelens.polarrecorder.biosleep.hal.FasceGatt
import com.wboelens.polarrecorder.dataSavers.InitializationState
import com.wboelens.polarrecorder.managers.DeviceInfoForDataSaver
import com.wboelens.polarrecorder.recording.RecordingOrchestrator
import com.wboelens.polarrecorder.state.ConnectionState
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Prepara una registrazione notturna senza schermate: trova la fascia, la collega, imposta i dati
 * del profilo e inizializza i salvataggi. E' lo stesso percorso delle 6 schermate, fatto in codice.
 */
class NightStarter(private val app: PolarRecorderApplication) {

  sealed interface Result {
    data class Ready(val recordingName: String) : Result

    data class Failed(val reason: String) : Result
  }

  companion object {
    private const val FIND_TIMEOUT_MS = 45_000L
    private const val CONNECT_TIMEOUT_MS = 90_000L
    private const val FLOW_TIMEOUT_MS = 10_000L
    private const val SAVERS_TIMEOUT_MS = 30_000L
    private const val POLL_MS = 250L
    private val NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")
  }

  @Suppress("ReturnCount")
  suspend fun prepare(profile: NightProfile): Result {
    app.ensureManagersInitialized()
    val polar = app.polarManager ?: return Result.Failed("Gestore Bluetooth non disponibile")
    val savers = app.dataSavers ?: return Result.Failed("Salvataggi non disponibili")
    val state = app.deviceState
    val id = profile.deviceId
    var profileForce: NightProfile? = null

    // 1. La fascia deve essere "vista": se non e' gia' in elenco si fa una scansione
    if (state.allDevices.value.none { it.info.deviceId == id }) {
      withContext(Dispatchers.Main) { polar.scanForDevices() }
      withTimeoutOrNull(FIND_TIMEOUT_MS) {
        state.allDevices.first { list -> list.any { it.info.deviceId == id } }
      } ?: return Result.Failed("Fascia ${profile.deviceName} non trovata: è indossata e vicina?")
    }

    // 2. Seleziona solo questa fascia
    state.allDevices.value.forEach { d ->
      val wanted = d.info.deviceId == id
      if (d.isSelected != wanted) state.toggleIsSelected(d.info.deviceId)
    }

    // 3. Collegamento (fino a 90 s: include la lettura delle capacita' del sensore)
    if (state.getConnectionState(id) != ConnectionState.CONNECTED) {
      withContext(Dispatchers.Main) { polar.connectToDevice(id) }
      val outcome =
          withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            state.allDevices
                .first { list ->
                  val s = list.find { it.info.deviceId == id }?.connectionState
                  s == ConnectionState.CONNECTED || s == ConnectionState.FAILED
                }
                .find { it.info.deviceId == id }
                ?.connectionState
          }
      if (outcome != ConnectionState.CONNECTED) {
        // Punto 10: una Polar che non risponde al driver Polar si prova con il GATT standard
        val device = state.allDevices.value.find { it.info.deviceId == id }
        if (device == null || !device.info.name.startsWith("Polar") || FasceGatt.isGatt(app, id)) {
          return Result.Failed("Collegamento alla fascia non riuscito")
        }
        withContext(Dispatchers.Main) { polar.disconnectDevice(id) }
        FasceGatt.registra(app, id, device.info.address)
        withContext(Dispatchers.Main) { polar.connectToDevice(id) } // ora va al driver GATT
        val gatt =
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
              state.allDevices.first { l -> l.find { it.info.deviceId == id }?.connectionState == ConnectionState.CONNECTED }
            }
        if (gatt == null) {
          FasceGatt.dimentica(app, id)
          return Result.Failed("Collegamento alla fascia non riuscito (anche con il driver standard)")
        }
        // Solo battito: l'accelerometro della H10 si legge solo con il driver Polar
        profileForce = profile.copy(dataTypes = setOf(PolarDeviceDataType.HR), sensorSettings = emptyMap())
      }
    }
    withContext(Dispatchers.Main) { polar.stopPeriodicScanning() }

    // 4. Dati e parametri del profilo (es. HR + ACC 25 Hz); solo HR se si e' ripiegato sul GATT
    val effettivo = profileForce ?: profile
    state.updateDeviceDataTypes(id, effettivo.dataTypes)
    state.updateDeviceSensorSettings(id, effettivo.sensorSettings)

    // 5. selectedDevices e connectedDevices si aggiornano solo se qualcuno li "ascolta":
    //    attendiamo che riflettano la fascia prima di avviare la registrazione
    val flowsReady =
        withTimeoutOrNull(FLOW_TIMEOUT_MS) {
          state.selectedDevices.first { l -> l.any { it.info.deviceId == id } }
          state.connectedDevices.first { l -> l.any { it.info.deviceId == id } }
        }
    if (flowsReady == null) return Result.Failed("Stato della fascia non disponibile")

    // 6. Inizializza i salvataggi attivi, come la schermata "Initializing Data Savers"
    val name = "notte_" + LocalDateTime.now().format(NAME_FORMAT)
    val dataTypeNames =
        effettivo.dataTypes.map { it.name }.toMutableSet().apply {
          add("LOG")
          add(RecordingOrchestrator.EVENT_LOG_DATA_TYPE)
        }
    val info = mapOf(id to DeviceInfoForDataSaver(profile.deviceName, dataTypeNames))
    val enabled = savers.asList().filter { it.isEnabled.value }
    withContext(Dispatchers.Main) { enabled.forEach { it.initSaving(name, info) } }

    val saversOk =
        withTimeoutOrNull(SAVERS_TIMEOUT_MS) {
          var ok: Boolean? = null
          while (ok == null) {
            val states = enabled.map { it.isInitialized.value }
            ok =
                when {
                  states.any { it == InitializationState.FAILED } -> false
                  states.all { it == InitializationState.SUCCESS } -> true
                  else -> null
                }
            if (ok == null) delay(POLL_MS)
          }
          ok
        }
    if (saversOk != true) return Result.Failed("Inizializzazione dei salvataggi non riuscita")

    return Result.Ready(name)
  }
}
