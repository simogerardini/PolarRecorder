package com.wboelens.polarrecorder.biosleep.cervello

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PauseTest {
  private val oggi = LocalDate.of(2026, 10, 8) // giovedi'

  @Test
  fun elencoEdEsiti() {
    val e = Pause.elenco(
        """{"esito": "ok", "pause": [{"id": 77, "dal": "2026-10-10", "al": "2026-10-12", "tipo": "ferie", "nota": "Lago", "dall_app": true},
           {"id": "x9", "dal": "2026-10-20", "al": "2026-10-20", "tipo": "infortunio", "nota": "", "dall_app": false}]}""")
    assertEquals(2, e.pause.size)
    assertEquals("77", e.pause[0].id)
    assertTrue(e.pause[0].contiene(LocalDate.of(2026, 10, 12)))
    assertFalse(e.pause[1].dallApp)
    val ok = Pause.esito("""{"esito": "ok", "id": 77, "ripianifica": true}""")
    assertTrue(ok.ripianifica)
    assertEquals(Pause.NON_VALIDI, Pause.esito("""{"esito": "valori_non_validi", "errore": "x"}""").esito)
  }

  @Test
  fun validazioneComeIlCervello() {
    assertTrue(Pause.errori(oggi, oggi.plusDays(3), "ferie", oggi).isEmpty())
    assertTrue(Pause.errori(oggi.plusDays(3), oggi, "ferie", oggi).isNotEmpty())
    assertTrue(Pause.errori(oggi.minusDays(5), oggi.minusDays(1), "ferie", oggi).isNotEmpty(), "gia' finita")
    assertTrue(Pause.errori(oggi, oggi, "vacanza", oggi).isNotEmpty())
    assertTrue(Pause.errori(oggi, oggi.plusDays(120), "ferie", oggi).isNotEmpty(), "oltre 120 giorni")
  }

  @Test
  fun settimanaInCorsoEDisponibilita() {
    assertEquals(LocalDate.of(2026, 10, 11), PianoCalendario.domenica(oggi))
    assertTrue(PianoCalendario.inSettimana(oggi.plusDays(2), oggi.plusDays(2), oggi))
    assertFalse(PianoCalendario.inSettimana(oggi.plusDays(4), oggi.plusDays(9), oggi), "da lunedi' prossimo")
    assertTrue(PianoCalendario.inSettimana(oggi.minusDays(3), oggi.plusDays(9), oggi), "pausa a cavallo")
    assertFalse(PianoCalendario.inSettimana(oggi.minusDays(3), oggi.minusDays(1), oggi), "solo giorni passati")
    val m = PianoCalendario.disponibilita(mapOf("2026-10-07" to 30, "2026-10-08" to 0, "2026-10-15" to 45), oggi)
    assertEquals(mapOf("2026-10-08" to 0, "2026-10-15" to 45), m)
    assertEquals("{\"2026-10-08\":0,\"2026-10-15\":45}", PianoCalendario.json(m).toString())
    assertEquals("—", PianoCalendario.badge(0))
    assertEquals("30'", PianoCalendario.badge(30))
  }
}
