package com.wboelens.polarrecorder.biosleep.tag

import com.wboelens.polarrecorder.biosleep.readiness.BioBaselineCalc
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Tag dalla schermata Notte: confondenti per primi, data = mattina del risveglio. */
class TagNotteTest {
  @Test
  fun confondentiPerPrimi() {
    val conf = listOf("alcol", "cena_tardiva", "caffeina_tardi", "stress", "viaggio", "malattia", "sonno_disturbato", "caldo", "altitudine")
    assertEquals(conf, Vocabolario.NOTTE.take(9))
    assertEquals(BioBaselineCalc.TAG_CONFONDENTI.toSet() - "luteale", conf.toSet(), "gli stessi della baseline (luteale: tag interno)")
    assertEquals(Vocabolario.GIORNO.toSet(), Vocabolario.NOTTE.toSet(), "stesso vocabolario dei tag di giorno")
    assertEquals(Vocabolario.NOTTE.size, Vocabolario.NOTTE.toSet().size)
  }

  @Test
  fun dataDelRisveglio() {
    val z = ZoneId.systemDefault()
    // notte tra il 16 e il 17: fine alle 6:40 del 17 -> 2026-10-17 (come la wellness e il coach)
    val fine = LocalDateTime.of(2026, 10, 17, 6, 40).atZone(z).toInstant().toEpochMilli()
    assertEquals(LocalDate.of(2026, 10, 17), Vocabolario.mattinaDaFine(fine))
    // stessa data dei tag messi la sera prima dalla scheda Notte (TagSera)
    assertEquals(Vocabolario.mattinaDellaNotte(LocalDateTime.of(2026, 10, 16, 23, 0)), Vocabolario.mattinaDaFine(fine))
  }
}
