package com.wboelens.polarrecorder.biosleep.setup

import android.content.Context
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType
import com.polar.sdk.api.model.PolarSensorSetting.SettingType
import com.wboelens.polarrecorder.biosleep.auto.NightProfile
import com.wboelens.polarrecorder.biosleep.auto.NightProfileStore
import com.wboelens.polarrecorder.managers.DeviceStreamCapabilities

/**
 * Configurazione automatica della fascia: BioSleep decide da solo cosa acquisire e come.
 *  - HR + intervalli RR: sempre (FC, HRV, fasi del sonno).
 *  - Accelerometro, se la fascia lo ha (Polar H10): movimento e respiro dal torace.
 *    Parametri: 25 Hz (la frequenza piu' bassa disponibile: basta per movimento e respiro e
 *    consuma meno batteria), il fondo scala piu' piccolo (2 g: massima precisione per i pochi mg
 *    del respiro), la risoluzione piu' alta.
 *  - Niente ECG o PPG: non servono all'app. I dati restano nel database di BioSleep.
 */
object BioSleepSetup {
  private const val TARGET_ACC_HZ = 25

  fun buildProfile(deviceId: String, deviceName: String, caps: DeviceStreamCapabilities): NightProfile {
    val types = mutableSetOf(PolarDeviceDataType.HR)
    val settings = mutableMapOf<PolarDeviceDataType, Map<SettingType, Int>>()
    if (PolarDeviceDataType.ACC in caps.availableTypes) {
      val available = caps.settings[PolarDeviceDataType.ACC]?.first?.settings.orEmpty()
      val chosen =
          available
              .mapNotNull { (type, values) -> choose(type, values)?.let { type to it } }
              .toMap()
      if (SettingType.SAMPLE_RATE in chosen) {
        types += PolarDeviceDataType.ACC
        settings[PolarDeviceDataType.ACC] = chosen
      }
    }
    return NightProfile(deviceId, deviceName, types, settings)
  }

  private fun choose(type: SettingType, values: Set<Int>): Int? {
    if (values.isEmpty()) return null
    return when (type) {
      SettingType.SAMPLE_RATE -> values.filter { it >= TARGET_ACC_HZ }.minOrNull() ?: values.max()
      SettingType.RANGE -> values.min()
      SettingType.RESOLUTION -> values.max()
      SettingType.CHANNELS -> values.max()
      else -> values.min()
    }
  }

  /** Salva il profilo della fascia: da qui in poi "Avvia notte" e l'avvio automatico lo usano. */
  fun finish(context: Context, profile: NightProfile) {
    NightProfileStore(context).save(profile)
  }
}
