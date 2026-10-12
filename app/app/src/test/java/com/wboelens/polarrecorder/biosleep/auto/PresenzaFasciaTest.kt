package com.wboelens.polarrecorder.biosleep.auto

import org.junit.Assert.assertEquals
import org.junit.Test

class PresenzaFasciaTest {
  @Test
  fun fasciaCollegataDaSubitoContaComePresente() {
    // Il caso delle notti mancate: la H10 si collega appena accesa e BLE_APPEARED non arriva
    assertEquals(true, PresenzaFascia.presente(PresenzaFascia.BT_COLLEGATA))
    assertEquals(true, PresenzaFascia.presente(PresenzaFascia.BLE_COMPARSA))
  }

  @Test
  fun scomparsaEScollegamento() {
    assertEquals(false, PresenzaFascia.presente(PresenzaFascia.BLE_SCOMPARSA))
    assertEquals(false, PresenzaFascia.presente(PresenzaFascia.BT_SCOLLEGATA))
  }

  @Test
  fun altriEventiIgnorati() {
    assertEquals(null, PresenzaFascia.presente(4))
    assertEquals(null, PresenzaFascia.presente(-1))
  }
}
