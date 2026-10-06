package com.wboelens.polarrecorder.dataSavers

import android.content.Context
import com.wboelens.polarrecorder.biosleep.BioSleepDataSaver
import com.wboelens.polarrecorder.managers.PreferencesManager
import com.wboelens.polarrecorder.state.LogState
import com.wboelens.polarrecorder.testutil.BaseRobolectricTest
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment

/** BioSleep salva solo nel proprio database: DataSavers contiene un solo salvataggio, sempre attivo. */
class DataSaversTest : BaseRobolectricTest() {

  private lateinit var context: Context
  private lateinit var logState: LogState
  private lateinit var preferencesManager: PreferencesManager

  @Before
  fun setup() {
    context = RuntimeEnvironment.getApplication()
    logState = mockk(relaxed = true)
    preferencesManager = mockk(relaxed = true)
  }

  @Test
  fun `bioSleep saver is created`() {
    val dataSavers = DataSavers(context, logState, preferencesManager)

    assertNotNull(dataSavers.bioSleep)
  }

  @Test
  fun `bioSleep saver is always enabled`() {
    val dataSavers = DataSavers(context, logState, preferencesManager)

    assertTrue(dataSavers.bioSleep.isEnabled.value)
  }

  @Test
  fun `iterator returns only BioSleep`() {
    val dataSavers = DataSavers(context, logState, preferencesManager)

    val saverList = mutableListOf<DataSaver>()
    dataSavers.iterator().forEach { saverList.add(it) }

    assertEquals(1, saverList.size)
    assertTrue(saverList.single() is BioSleepDataSaver)
  }

  @Test
  fun `asList returns only BioSleep`() {
    val dataSavers = DataSavers(context, logState, preferencesManager)

    val list = dataSavers.asList()

    assertEquals(1, list.size)
    assertTrue(list.single() is BioSleepDataSaver)
  }

  @Test
  fun `enabledCount is 1`() {
    val dataSavers = DataSavers(context, logState, preferencesManager)

    assertEquals(1, dataSavers.enabledCount)
  }
}
