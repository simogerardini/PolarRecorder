package com.wboelens.polarrecorder.managers

import android.content.Context
import com.wboelens.polarrecorder.testutil.BaseRobolectricTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment

/**
 * Unit tests for PreferencesManager - verifies persistence and retrieval of all preference types
 * (recording settings).
 */
class PreferencesManagerTest : BaseRobolectricTest() {

  private lateinit var context: Context
  private lateinit var preferencesManager: PreferencesManager

  @Before
  fun setup() {
    context = RuntimeEnvironment.getApplication()
    preferencesManager = PreferencesManager(context)
  }

  // ==================== Recording Settings Tests ====================

  @Test
  fun `recordingName default is PolarRecording`() {
    assertEquals("PolarRecording", preferencesManager.recordingName)
  }

  @Test
  fun `recordingName setter persists value`() {
    preferencesManager.recordingName = "CustomRecording"

    val newManager = PreferencesManager(context)
    assertEquals("CustomRecording", newManager.recordingName)
  }

  @Test
  fun `recordingNameAppendTimestamp default is true`() {
    assertTrue(preferencesManager.recordingNameAppendTimestamp)
  }

  @Test
  fun `recordingNameAppendTimestamp setter persists value`() {
    preferencesManager.recordingNameAppendTimestamp = false

    val newManager = PreferencesManager(context)
    assertFalse(newManager.recordingNameAppendTimestamp)
  }

  @Test
  fun `recordingStopOnDisconnect default is false`() {
    assertFalse(preferencesManager.recordingStopOnDisconnect)
  }

  @Test
  fun `recordingStopOnDisconnect setter persists value`() {
    preferencesManager.recordingStopOnDisconnect = true

    val newManager = PreferencesManager(context)
    assertTrue(newManager.recordingStopOnDisconnect)
  }

  // ==================== Edge Cases ====================

  @Test
  fun `preferences persist across manager instances`() {
    preferencesManager.recordingName = "TestName"

    val newManager = PreferencesManager(context)

    assertEquals("TestName", newManager.recordingName)
  }

  @Test
  fun `setting and getting values in sequence works correctly`() {
    preferencesManager.recordingName = "First"
    assertEquals("First", preferencesManager.recordingName)

    preferencesManager.recordingName = "Second"
    assertEquals("Second", preferencesManager.recordingName)

    preferencesManager.recordingName = "Third"
    assertEquals("Third", preferencesManager.recordingName)
  }
}
