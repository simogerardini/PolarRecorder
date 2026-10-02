package com.wboelens.polarrecorder.biosleep.auto

import android.content.Context
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType
import com.polar.sdk.api.model.PolarSensorSetting
import com.wboelens.polarrecorder.state.Device
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Le impostazioni di una notte: quale fascia, quali dati, con quali parametri.
 * Viene salvata automaticamente ogni volta che una registrazione parte, cosi' "Avvia notte"
 * puo' ripeterla senza passare dalle schermate.
 */
data class NightProfile(
    val deviceId: String,
    val deviceName: String,
    val dataTypes: Set<PolarDeviceDataType>,
    val sensorSettings: Map<PolarDeviceDataType, Map<PolarSensorSetting.SettingType, Int>>,
) {
  companion object {
    /** Crea il profilo dal dispositivo selezionato al momento dell'avvio. */
    fun fromDevice(device: Device): NightProfile =
        NightProfile(
            deviceId = device.info.deviceId,
            deviceName = device.info.name,
            dataTypes = device.dataTypes,
            sensorSettings =
                device.sensorSettings
                    .mapValues { (_, setting) ->
                      setting.settings.mapNotNull { (type, values) ->
                        values.firstOrNull()?.let { type to it }
                      }.toMap()
                    }
                    .filterValues { it.isNotEmpty() },
        )
  }
}

/** Salva e legge il profilo della notte nella memoria privata dell'app. */
class NightProfileStore(context: Context) {
  private val prefs =
      context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  fun save(profile: NightProfile) {
    val settings = JSONObject()
    profile.sensorSettings.forEach { (dataType, values) ->
      val v = JSONObject()
      values.forEach { (type, value) -> v.put(type.name, value) }
      settings.put(dataType.name, v)
    }
    val json =
        JSONObject()
            .put("deviceId", profile.deviceId)
            .put("deviceName", profile.deviceName)
            .put("dataTypes", JSONArray(profile.dataTypes.map { it.name }))
            .put("settings", settings)
    prefs.edit().putString(KEY_PROFILE, json.toString()).apply()
  }

  fun load(): NightProfile? {
    val text = prefs.getString(KEY_PROFILE, null) ?: return null
    return try {
      val json = JSONObject(text)
      val types = json.getJSONArray("dataTypes")
      val dataTypes =
          (0 until types.length())
              .mapNotNull { i -> enumOrNull<PolarDeviceDataType>(types.getString(i)) }
              .toSet()
      val settingsJson = json.optJSONObject("settings") ?: JSONObject()
      val settings =
          settingsJson
              .keys()
              .asSequence()
              .mapNotNull { key ->
                val dataType = enumOrNull<PolarDeviceDataType>(key) ?: return@mapNotNull null
                val values = settingsJson.getJSONObject(key)
                val map =
                    values
                        .keys()
                        .asSequence()
                        .mapNotNull { t ->
                          enumOrNull<PolarSensorSetting.SettingType>(t)?.let { it to values.getInt(t) }
                        }
                        .toMap()
                dataType to map
              }
              .toMap()
      if (dataTypes.isEmpty()) null
      else NightProfile(json.getString("deviceId"), json.optString("deviceName", ""), dataTypes, settings)
    } catch (e: JSONException) {
      null
    }
  }

  private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
      enumValues<T>().firstOrNull { it.name == name }

  companion object {
    private const val PREFS_NAME = "biosleep_auto"
    private const val KEY_PROFILE = "night_profile"
  }
}
