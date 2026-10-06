package com.wboelens.polarrecorder.dataSavers

import android.content.Context
import com.wboelens.polarrecorder.biosleep.BioSleepDataSaver
import com.wboelens.polarrecorder.managers.PreferencesManager
import com.wboelens.polarrecorder.state.LogState

class DataSavers(
    context: Context,
    logState: LogState,
    preferencesManager: PreferencesManager,
) {
  val bioSleep: BioSleepDataSaver = BioSleepDataSaver(context, logState, preferencesManager)

  private val savers = mutableListOf<DataSaver>()

  init {
    // BioSleep: database locale + analisi notturna, sempre attivo
    bioSleep.enable()
    savers.add(bioSleep)
  }

  fun iterator(): Iterator<DataSaver> = savers.iterator()

  fun asList(): List<DataSaver> = savers.toList()

  val enabledCount: Int
    get() = savers.count { it.isEnabled.value }
}
