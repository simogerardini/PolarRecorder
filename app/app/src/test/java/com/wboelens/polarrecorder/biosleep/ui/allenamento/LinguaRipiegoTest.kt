package com.wboelens.polarrecorder.biosleep.cervello

import com.wboelens.polarrecorder.biosleep.ui.allenamento.DateIt
import java.time.LocalDate
import java.util.Locale
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Lingua non coperta (es. francese): interfaccia, cervello, date e numeri in inglese, mai in italiano. */
class LinguaRipiegoTest {
  private val prima = Locale.getDefault()

  @AfterEach fun ripristina() = Locale.setDefault(prima)

  @Test
  fun linguaAlCervello() {
    assertEquals("en", Lingua.daLocale("fr"))
    assertEquals("en", Lingua.daLocale("de"))
    assertEquals("it", Lingua.daLocale("it"))
    assertEquals("zh", Lingua.daLocale("zh"))
  }

  @Test
  fun localePerDateENumeri() {
    assertEquals(Locale.ENGLISH, Lingua.localeSupportato(Locale.FRANCE))
    assertEquals(Locale.ITALY, Lingua.localeSupportato(Locale.ITALY))
    assertEquals(Locale.SIMPLIFIED_CHINESE, Lingua.localeSupportato(Locale.SIMPLIFIED_CHINESE))
  }

  @Test
  fun dateConLinguaNonCoperta() {
    Locale.setDefault(Lingua.localeSupportato(Locale.GERMANY))
    assertEquals("Fri, Oct 9", DateIt.breve(LocalDate.of(2026, 10, 9)))
    assertEquals("34.5", String.format(Locale.getDefault(), "%.1f", 34.46))
  }
}
