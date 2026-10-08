package com.wboelens.polarrecorder.biosleep.intervals

// GENERATO da strumenti/genera_campi_kotlin.py (fonte: python/campi.py). Non modificare a mano.
object CampiWellness {
    const val F_RMSSD = "NoctalixRMSSD"   // rMSSD (ms)
    const val F_SDNN = "NoctalixSDNN"   // SDNN (ms)
    const val F_RHR = "NoctalixRHR"   // RHR (bpm)
    const val F_MIN_HR = "NoctalixMinHR"   // Min HR (bpm)
    const val F_AVG_HR = "NoctalixAvgHR"   // Avg HR (bpm)
    const val F_HOURS = "NoctalixHours"   // Recording h (h)
    const val F_SLEEP_HOURS = "NoctalixSleepHours"   // Sleep h (h)
    const val F_DEEP_MIN = "NoctalixDeepMin"   // Deep min (min)
    const val F_REM_MIN = "NoctalixREMMin"   // REM min (min)
    const val F_LIGHT_MIN = "NoctalixLightMin"   // Light min (min)
    const val F_AWAKE_MIN = "NoctalixAwakeMin"   // Awake min (min)
    const val F_QUALITY = "NoctalixQuality"   // Quality (%)
    val TUTTI = listOf(F_RMSSD, F_SDNN, F_RHR, F_MIN_HR, F_AVG_HR, F_HOURS, F_SLEEP_HOURS, F_DEEP_MIN, F_REM_MIN, F_LIGHT_MIN, F_AWAKE_MIN, F_QUALITY)
}
