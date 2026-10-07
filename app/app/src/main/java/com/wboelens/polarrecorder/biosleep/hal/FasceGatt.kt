package com.wboelens.polarrecorder.biosleep.hal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.polar.sdk.api.model.PolarDeviceInfo
import com.wboelens.polarrecorder.state.ConnectionState
import com.wboelens.polarrecorder.state.DeviceState
import com.wboelens.polarrecorder.state.LogState
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.processors.PublishProcessor
import java.util.concurrent.ConcurrentHashMap

/**
 * Ponte tra il driver GATT 0x180D e il resto dell'app, che e' costruito sul Polar SDK.
 * Una fascia GATT appare in DeviceState come un dispositivo qualsiasi (id = indirizzo Bluetooth),
 * PolarManager le inoltra collegamento, scollegamento e flusso HR, e l'orchestrator riceve
 * pacchetti HrPacket al posto di PolarHrData. Watchdog, ripresa della sessione e buchi del punto 9
 * funzionano quindi senza modifiche: vedono gli stessi cambi di stato della H10.
 */
@SuppressLint("MissingPermission") // permessi Bluetooth gia' richiesti dall'app per il Polar SDK
object FasceGatt {
  private const val TAG = "BioSleepGatt"
  private const val PREFS = "biosleep_fasce_gatt"

  private class Collegamento(val driver: GattHrDriver, val flusso: PublishProcessor<HrPacket>, @Volatile var capacita: Capacita?)

  private val attivi = ConcurrentHashMap<String, Collegamento>()

  // --- Registro: quali dispositivi si leggono con il driver GATT (persistente) -------------------

  /** Id (indirizzo o id Polar) -> indirizzo Bluetooth, per i dispositivi letti via GATT. */
  fun registra(context: Context, id: String, indirizzo: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(id, indirizzo).apply()
  }

  fun isGatt(context: Context, id: String): Boolean =
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(id)

  /** Una Polar il cui driver Polar non funziona torna al driver Polar alla prossima configurazione. */
  fun dimentica(context: Context, id: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(id).apply()
  }

  private fun indirizzo(context: Context, id: String): String? =
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(id, null)

  // --- Scansione delle fasce non Polar ---------------------------------------------------------

  private var callbackScansione: ScanCallback? = null

  /** Cerca per [durataMs] i dispositivi che pubblicizzano il servizio 0x180D (le Polar le trova gia' l'SDK). */
  fun cerca(context: Context, deviceState: DeviceState, durataMs: Long) {
    val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
    ferma(context)
    val cb =
        object : ScanCallback() {
          override fun onScanResult(callbackType: Int, result: ScanResult) {
            val nome = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (nome.startsWith("Polar", ignoreCase = true)) return
            val indirizzo = result.device.address
            registra(context, indirizzo, indirizzo)
            deviceState.addDevice(
                PolarDeviceInfo(
                    deviceId = indirizzo, address = indirizzo, rssi = result.rssi, name = nome, isConnectable = true))
          }
        }
    callbackScansione = cb
    try {
      scanner.startScan(
          listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(GattHrDriver.HR_SERVICE)).build()),
          ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
          cb)
      Handler(Looper.getMainLooper()).postDelayed({ ferma(context) }, durataMs)
    } catch (e: SecurityException) {
      Log.w(TAG, "Scansione 0x180D senza permesso: ${e.message}")
    }
  }

  fun ferma(context: Context) {
    val cb = callbackScansione ?: return
    callbackScansione = null
    try {
      context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(cb)
    } catch (e: SecurityException) {
      Log.w(TAG, "Stop scansione senza permesso")
    }
  }

  // --- Collegamento ---------------------------------------------------------------------------

  fun attivo(id: String) = attivi.containsKey(id)

  fun capacita(id: String): Capacita? = attivi[id]?.capacita

  fun driver(id: String): GattHrDriver? = attivi[id]?.driver

  /** Collega con il driver GATT. Se e' gia' attivo non fa nulla: il driver si ricollega da solo. */
  fun collega(context: Context, deviceState: DeviceState, logState: LogState, id: String, nome: String) {
    if (attivi.containsKey(id)) return
    val indirizzo = indirizzo(context, id) ?: id
    val flusso = PublishProcessor.create<HrPacket>()
    val listener =
        object : HrDriverListener {
          override fun onPacchetto(tMs: Long, p: HrPacket) = flusso.onNext(p)

          override fun onCapacita(c: Capacita) {
            attivi[id]?.capacita = c
            logState.addLogMessage(
                "Fascia $nome via GATT 0x180D: ${c.produttore} ${c.modello} firmware ${c.firmware}".trim())
          }

          override fun onCollegamento(collegata: Boolean, motivo: String) {
            // Durante una disconnessione voluta lo stato resta DISCONNECTING fino alla chiusura
            if (deviceState.getConnectionState(id) == ConnectionState.DISCONNECTING) return
            deviceState.updateConnectionState(id, if (collegata) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED)
            if (collegata) logState.addLogMessage("Fascia $nome collegata ($motivo)")
            else logState.addLogError("Fascia $nome scollegata: $motivo (si ricollega da sola)", false)
          }
        }
    val driver = GattHrDriver(context.applicationContext, indirizzo, nome, listener)
    attivi[id] = Collegamento(driver, flusso, null)
    deviceState.updateConnectionState(id, ConnectionState.CONNECTING)
    driver.avvia()
  }

  fun scollega(deviceState: DeviceState, id: String) {
    val c = attivi.remove(id) ?: return
    deviceState.updateConnectionState(id, ConnectionState.DISCONNECTING)
    c.driver.ferma()
    c.flusso.onComplete()
    deviceState.updateConnectionState(id, ConnectionState.DISCONNECTED)
  }

  fun scollegaTutte(deviceState: DeviceState) = attivi.keys.toList().forEach { scollega(deviceState, it) }

  /** Flusso dei pacchetti per l'orchestrator: si puo' sottoscrivere di nuovo dopo una riconnessione. */
  fun flusso(id: String): Flowable<HrPacket> =
      attivi[id]?.flusso?.onBackpressureBuffer() ?: Flowable.error(IllegalStateException("Fascia $id non collegata via GATT"))
}
