package com.wboelens.polarrecorder.biosleep.auto

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Android avvia questo servizio quando la fascia associata compare, si collega, scompare o si
 * scollega. Qui si aggiorna solo lo stato e si valuta l'avvio automatico.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class StrapPresenceService : CompanionDeviceService() {

  // Android 13-15. Da Android 16 il sistema chiama ANCHE questi oltre a onDevicePresenceEvent:
  // si ignorano, altrimenti ogni evento arriverebbe due volte.
  @Deprecated("Sostituito da onDevicePresenceEvent su Android 16")
  override fun onDeviceAppeared(associationInfo: AssociationInfo) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) evento(PresenzaFascia.BLE_COMPARSA)
  }

  @Deprecated("Sostituito da onDevicePresenceEvent su Android 16")
  override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) evento(PresenzaFascia.BLE_SCOMPARSA)
  }

  // Android 16+: comparsa e scomparsa BLE, ma anche collegamento e scollegamento della fascia
  @RequiresApi(Build.VERSION_CODES.BAKLAVA)
  override fun onDevicePresenceEvent(event: DevicePresenceEvent) = evento(event.event)

  private fun evento(tipo: Int) {
    AutoStart.registro(this, "Fascia: ${PresenzaFascia.nome(tipo)} (evento Android)")
    when (PresenzaFascia.presente(tipo)) {
      true -> {
        AutoStartStore(this).strapPresent = true
        AutoStart.evaluateAsync(this, "fascia ${PresenzaFascia.nome(tipo)}")
      }
      // Normale anche durante la registrazione: collegata all'app, la fascia smette di "farsi vedere"
      false -> AutoStartStore(this).strapPresent = false
      null -> Unit
    }
  }
}
