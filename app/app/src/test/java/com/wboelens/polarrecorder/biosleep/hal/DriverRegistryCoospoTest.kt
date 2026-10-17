package com.wboelens.polarrecorder.biosleep.hal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverRegistryCoospoTest {
  private val hr = setOf(DriverRegistry.UUID_HR_SERVICE)

  @Test
  fun coospoH808sSenzaServizioNelSegnale() {
    // Il caso del tester: nome da sensore cardio, 0x180D non pubblicizzato
    assertTrue(DriverRegistry.candidataGatt("H808S 0123456", emptySet()))
    assertTrue(DriverRegistry.candidataGatt("CooSpo H808S", emptySet()))
    assertTrue(DriverRegistry.candidataGatt("HW807", emptySet()))
    assertTrue(DriverRegistry.candidataGatt("H6M 12345", emptySet()))
    assertTrue(DriverRegistry.candidataGatt("TICKR 1A2B", emptySet()))
    assertTrue(DriverRegistry.candidataGatt("HRM-Dual:123", emptySet()))
  }

  @Test
  fun qualsiasiDispositivoCheDichiaraIlServizio() {
    assertTrue(DriverRegistry.candidataGatt("XYZ-123", hr))
  }

  @Test
  fun altriDispositiviEPolarEsclusi() {
    assertFalse(DriverRegistry.candidataGatt("Galaxy Buds2 Pro", emptySet()))
    assertFalse(DriverRegistry.candidataGatt("[TV] Samsung", emptySet()))
    assertFalse(DriverRegistry.candidataGatt("Mi Smart Band 8", emptySet()))
    assertFalse(DriverRegistry.candidataGatt("Polar H10 0F291832", hr)) // le Polar le trova l'SDK
    assertFalse(DriverRegistry.candidataGatt("", hr))
  }

  @Test
  fun h808sEUnBraccialeOttico() {
    assertEquals(TipoFascia.OTTICA, DriverRegistry.tipo("H808S 0123456"))
    assertEquals(TipoFascia.OTTICA, DriverRegistry.tipo("CooSpo HW807"))
    assertEquals(TipoFascia.PETTO, DriverRegistry.tipo("CooSpo H6M"))
    assertEquals(TipoFascia.PETTO, DriverRegistry.tipo("Polar H10 0F291832"))
  }
}
