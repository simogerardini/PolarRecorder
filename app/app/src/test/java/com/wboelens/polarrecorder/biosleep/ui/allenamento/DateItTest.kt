package com.wboelens.polarrecorder.biosleep.ui.allenamento

import com.wboelens.polarrecorder.biosleep.cervello.Lingua
import java.time.LocalDate
import java.util.Locale
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Date e numeri con il Locale dell'utente, mai formati scritti a mano per una sola lingua. */
class DateItTest {
  private val prima = Locale.getDefault()
  private val d = LocalDate.of(2026, 10, 9) // venerdi'

  @AfterEach
  fun ripristina() = Locale.setDefault(prima)

  @Test
  fun datePerLingua() {
    Locale.setDefault(Locale.ITALIAN)
    assertEquals("ven 9 ott", DateIt.breve(d))
    assertEquals("Venerdì 9 ottobre", DateIt.lunga(d))
    Locale.setDefault(Locale.ENGLISH)
    assertEquals("Fri, Oct 9", DateIt.breve(d))
    Locale.setDefault(Locale.forLanguageTag("es"))
    assertEquals("Viernes, 9 de octubre", DateIt.lunga(d))
    Locale.setDefault(Locale.forLanguageTag("zh-CN"))
    assertEquals("10月9日", DateIt.asse(d))
    assertEquals("10月9日 星期五", DateIt.lunga(d))
  }

  @Test
  fun tagCinese() {
    assertEquals("zh-CN", Lingua.tag("zh"))
    assertEquals("en", Lingua.tag("en"))
  }
}
