package com.wboelens.polarrecorder.biosleep.auto

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Android avvia questo servizio quando la fascia associata compare (indossata: inizia a
 * trasmettere) o scompare. Qui si aggiorna solo lo stato e si valuta l'avvio automatico.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class StrapPresenceService : CompanionDeviceService() {

  // Android 13-15
  @Deprecated("Sostituito da onDevicePresenceEvent su Android 16")
  override fun onDeviceAppeared(associationInfo: AssociationInfo) = appeared()

  @Deprecated("Sostituito da onDevicePresenceEvent su Android 16")
  override fun onDeviceDisappeared(associationInfo: AssociationInfo) = disappeared()

  // Android 16+
  @RequiresApi(Build.VERSION_CODES.BAKLAVA)
  override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
    when (event.event) {
      DevicePresenceEvent.EVENT_BLE_APPEARED -> appeared()
      DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> disappeared()
      else -> Unit
    }
  }

  private fun appeared() {
    Log.i("BioSleep", "Fascia rilevata")
    AutoStartStore(this).strapPresent = true
    AutoStart.evaluateAsync(this, "fascia rilevata")
  }

  private fun disappeared() {
    // Normale anche durante la registrazione: collegata all'app, la fascia smette di "farsi vedere"
    AutoStartStore(this).strapPresent = false
  }
}
