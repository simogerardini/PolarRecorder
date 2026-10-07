package com.wboelens.polarrecorder.biosleep.hal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.UUID

/** Eventi del driver verso chi registra (servizio di registrazione / salvataggio). */
interface HrDriverListener {
  fun onPacchetto(tMs: Long, p: HrPacket)

  fun onCapacita(c: Capacita)

  /** collegata = true quando le notifiche 0x2A37 sono attive; false a ogni caduta. */
  fun onCollegamento(collegata: Boolean, motivo: String)
}

/**
 * Driver universale per fasce cardio con servizio GATT standard Heart Rate (0x180D).
 *  - legge produttore, modello e firmware dal Device Information Service (0x180A) se c'e';
 *  - attiva le notifiche di 0x2A37 e le decodifica con Gatt2A37Parser;
 *  - scarta i pacchetti con contatto "supportato ma assente" (fascia non sulla pelle);
 *  - se il collegamento cade si ricollega da solo e in silenzio: prima con attese crescenti
 *    (2, 4, 8... fino a 60 s), poi con autoConnect, che Android completa appena la fascia torna
 *    in portata, senza consumare batteria in tentativi continui.
 * Tutte le operazioni GATT passano da un solo thread e una alla volta (Android ne accetta una).
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT e' verificato da chi avvia il driver
class GattHrDriver(
    private val context: Context,
    private val indirizzo: String,
    private val nome: String,
    private val listener: HrDriverListener,
) {
  companion object {
    private const val TAG = "BioSleepGatt"
    val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val DIS_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val DIS_PRODUTTORE: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
    val DIS_MODELLO: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    val DIS_FIRMWARE: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
    private const val ATTESA_MIN_MS = 2_000L
    private const val ATTESA_MAX_MS = 60_000L
    private const val TENTATIVI_PRIMA_DI_AUTOCONNECT = 6
  }

  private val thread = HandlerThread("gatt-$indirizzo").apply { start() }
  private val h = Handler(thread.looper)
  private var gatt: BluetoothGatt? = null
  @Volatile private var attivo = false
  private var tentativi = 0
  private val letture = ArrayDeque<UUID>()
  private var produttore = ""
  private var modello = ""
  private var firmware = ""

  // Contatori per il rapporto fascia
  @Volatile var disconnessioni = 0
    private set
  @Volatile var scartatiSenzaContatto = 0
    private set
  @Volatile var malformati = 0
    private set

  fun avvia() {
    attivo = true
    tentativi = 0
    h.post { collega(autoConnect = false) }
  }

  fun ferma() {
    attivo = false
    h.removeCallbacksAndMessages(null)
    h.post {
      try {
        gatt?.disconnect()
        gatt?.close()
      } catch (e: SecurityException) {
        Log.w(TAG, "Permesso Bluetooth mancante alla chiusura")
      }
      gatt = null
      thread.quitSafely()
    }
  }

  private fun collega(autoConnect: Boolean) {
    if (!attivo) return
    val device: BluetoothDevice =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(indirizzo) ?: run {
          riprova("Bluetooth non disponibile")
          return
        }
    try {
      gatt?.close()
      gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE, 0, h)
    } catch (e: SecurityException) {
      listener.onCollegamento(false, "permesso Bluetooth mancante")
    } catch (e: IllegalArgumentException) {
      riprova("indirizzo non valido")
    }
  }

  /** Riconnessione silenziosa: attese crescenti, poi autoConnect (attende la fascia in portata). */
  private fun riprova(motivo: String) {
    if (!attivo) return
    tentativi++
    if (tentativi > TENTATIVI_PRIMA_DI_AUTOCONNECT) {
      Log.i(TAG, "$nome: $motivo, attendo che torni in portata (autoConnect)")
      h.post { collega(autoConnect = true) }
      return
    }
    val attesa = (ATTESA_MIN_MS shl (tentativi - 1)).coerceAtMost(ATTESA_MAX_MS)
    Log.i(TAG, "$nome: $motivo, nuovo tentativo fra ${attesa / 1000} s")
    h.postDelayed({ collega(autoConnect = false) }, attesa)
  }

  private val callback =
      object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
          if (newState == BluetoothProfile.STATE_CONNECTED) {
            Log.i(TAG, "$nome collegata, cerco i servizi")
            g.discoverServices()
          } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            disconnessioni++
            listener.onCollegamento(false, "collegamento perso (stato GATT $status)")
            g.close()
            if (gatt == g) gatt = null
            riprova("collegamento perso")
          }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
          if (g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT) == null) {
            listener.onCollegamento(false, "la fascia non espone il servizio cardio 0x180D")
            g.disconnect()
            return
          }
          letture.clear()
          g.getService(DIS_SERVICE)?.let { dis ->
            listOf(DIS_PRODUTTORE, DIS_MODELLO, DIS_FIRMWARE).filter { dis.getCharacteristic(it) != null }.forEach { letture += it }
          }
          prossimaLettura(g)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            letto(g, c.uuid, value, status)

        @Deprecated("Android 12 e precedenti")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
          @Suppress("DEPRECATION") letto(g, c.uuid, c.value ?: ByteArray(0), status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
          if (d.uuid != CCCD) return
          if (status == BluetoothGatt.GATT_SUCCESS) {
            tentativi = 0
            listener.onCapacita(
                Capacita(TipoDriver.GATT_180D, hr = true, rr = null, acc = false,
                    tipo = DriverRegistry.tipo("$nome $modello"), modello = modello, produttore = produttore, firmware = firmware))
            listener.onCollegamento(true, "notifiche del battito attive")
          } else {
            listener.onCollegamento(false, "notifiche non attivate (stato $status)")
            g.disconnect()
          }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            ricevuto(c.uuid, value)

        @Deprecated("Android 12 e precedenti")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
          @Suppress("DEPRECATION") ricevuto(c.uuid, c.value ?: ByteArray(0))
        }
      }

  private fun letto(g: BluetoothGatt, uuid: UUID, value: ByteArray, status: Int) {
    if (status == BluetoothGatt.GATT_SUCCESS) {
      val testo = String(value, Charsets.UTF_8).trim { it <= ' ' }
      when (uuid) {
        DIS_PRODUTTORE -> produttore = testo
        DIS_MODELLO -> modello = testo
        DIS_FIRMWARE -> firmware = testo
      }
    }
    prossimaLettura(g)
  }

  private fun prossimaLettura(g: BluetoothGatt) {
    val uuid = letture.removeFirstOrNull()
    if (uuid != null) {
      val c = g.getService(DIS_SERVICE)?.getCharacteristic(uuid)
      if (c == null || !g.readCharacteristic(c)) prossimaLettura(g)
      return
    }
    attivaNotifiche(g)
  }

  private fun attivaNotifiche(g: BluetoothGatt) {
    val c = g.getService(HR_SERVICE).getCharacteristic(HR_MEASUREMENT)
    g.setCharacteristicNotification(c, true)
    val d = c.getDescriptor(CCCD) ?: return listener.onCollegamento(false, "descrittore delle notifiche assente")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
    } else {
      @Suppress("DEPRECATION")
      d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
      @Suppress("DEPRECATION") g.writeDescriptor(d)
    }
  }

  private fun ricevuto(uuid: UUID, value: ByteArray) {
    if (uuid != HR_MEASUREMENT) return
    val p = Gatt2A37Parser.parse(value)
    when {
      p == null -> malformati++
      p.daScartare -> scartatiSenzaContatto++
      else -> listener.onPacchetto(System.currentTimeMillis(), p)
    }
  }
}
