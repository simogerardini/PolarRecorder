package com.wboelens.polarrecorder.state

import com.polar.sdk.api.model.PolarDeviceInfo
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

/** Dopo cleanup() (attivita' o servizio chiusi) lo stato deve continuare ad aggiornarsi. */
class DeviceStateCleanupTest {
  private fun fascia(id: String): PolarDeviceInfo =
      mockk(relaxed = true) {
        every { deviceId } returns id
        every { isConnectable } returns true
      }

  @Test
  fun selezioneECollegamentoVisibiliAncheDopoCleanup() = runBlocking {
    val stato = DeviceState()
    stato.cleanup()

    stato.addDevice(fascia("0F291832"))
    stato.toggleIsSelected("0F291832")
    stato.updateConnectionState("0F291832", ConnectionState.CONNECTED)

    withTimeout(2_000) {
      stato.selectedDevices.first { l -> l.any { it.info.deviceId == "0F291832" } }
      stato.connectedDevices.first { l -> l.any { it.info.deviceId == "0F291832" } }
    }
    Unit
  }
}
