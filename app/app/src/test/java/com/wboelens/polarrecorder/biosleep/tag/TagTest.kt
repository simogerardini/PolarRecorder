package com.wboelens.polarrecorder.biosleep.tag

import com.wboelens.polarrecorder.biosleep.readiness.BioBaselineCalc
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TagTest {
  @Test
  fun vocabolarioComeIlCervello() {
    // le 17 chiavi di giorno e le 10 di seduta di coach_settimanale.TAG_GIORNO / TAG_SEDUTA
    assertEquals(17, Vocabolario.GIORNO.size)
    assertEquals(10, Vocabolario.SEDUTA.size)
    assertTrue("infortunio:caviglia" in Vocabolario.GIORNO)
    assertTrue("dolore:spalla" in Vocabolario.SEDUTA)
    // ogni tag confondente della baseline e' un tag di giorno dell'app
    assertTrue(Vocabolario.GIORNO.containsAll(BioBaselineCalc.TAG_CONFONDENTI))
    assertEquals("Infortunio — ginocchio", Vocabolario.etichetta("infortunio:ginocchio"))
  }

  @Test
  fun dataDellaNotte() {
    assertEquals(LocalDate.of(2026, 10, 6), Vocabolario.mattinaDellaNotte(LocalDateTime.of(2026, 10, 5, 22, 30)))
    assertEquals(LocalDate.of(2026, 10, 6), Vocabolario.mattinaDellaNotte(LocalDateTime.of(2026, 10, 6, 0, 40)))
  }

  @Test
  fun bloccoPerIlCervello() {
    assertNull(Vocabolario.json(emptyMap(), emptyMap()))
    val j = Vocabolario.json(mapOf("2026-10-06" to setOf("stress", "alcol")), mapOf("i1" to setOf("dolore:ginocchio", "fatica_alta")))!!
    assertEquals("""["alcol","stress"]""", j.getAsJsonObject("giorni").get("2026-10-06").toString(), "ordine del vocabolario")
    assertEquals("""["fatica_alta","dolore:ginocchio"]""", j.getAsJsonObject("sedute").get("i1").toString())
  }
}
