package com.wboelens.polarrecorder.biosleep.auto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatoRegistrazioneTest {
  @Test
  fun servizioVivoSenzaSessioneNonERegistrazione() {
    // Il caso che bloccava l'avvio automatico
    assertFalse(StatoRegistrazione.inCorso(null, servizioVivo = true, sessioniAperte = 0))
  }

  @Test
  fun servizioVivoConSessioneApertaERegistrazione() {
    assertTrue(StatoRegistrazione.inCorso(null, servizioVivo = true, sessioniAperte = 1))
  }

  @Test
  fun sessioneApertaMaServizioMortoNonERegistrazione() {
    // Notte interrotta dal sistema: la riprende il watchdog, non e' una registrazione in corso
    assertFalse(StatoRegistrazione.inCorso(null, servizioVivo = false, sessioniAperte = 1))
  }

  @Test
  fun loStatoDelServizioCollegatoVinceSempre() {
    assertTrue(StatoRegistrazione.inCorso(true, servizioVivo = false, sessioniAperte = 0))
    assertFalse(StatoRegistrazione.inCorso(false, servizioVivo = true, sessioniAperte = 3))
  }
}
