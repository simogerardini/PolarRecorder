package com.wboelens.polarrecorder.biosleep.checkin

import com.google.gson.JsonParser
import com.wboelens.polarrecorder.biosleep.training.EventoCal
import java.time.LocalDate
import java.time.LocalTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CheckinTest {
  private val oggi = LocalDate.of(2026, 10, 10)

  private fun ev(data: LocalDate, ext: String?, cat: String = "WORKOUT", nome: String = "Corsa") =
      EventoCal(id = "e$ext", data = data, categoria = cat, tipo = "Run", nome = nome, durataS = 2700, tss = 40, distanzaM = null,
          descrizione = null, attivitaId = null, passi = null, externalId = ext)

  @Test
  fun sedutePassateDelCoachSenzaRisposta() {
    val eventi =
        listOf(
            ev(oggi.minusDays(1), "sw:corsa_chiave:2026-10-09"),
            ev(oggi.minusDays(2), "sw:forza:2026-10-08"),
            ev(oggi.minusDays(8), "sw:vecchia:2026-10-02"), // oltre 7 giorni
            ev(oggi.minusDays(1), null), // non del coach
            ev(oggi.minusDays(1), "sw:gara:2026-10-09", cat = "RACE_A"),
            ev(oggi, "sw:bici_chiave:2026-10-10"), // oggi: solo da mezzogiorno
        )
    val mattina = Checkin.daChiedere(eventi, setOf("sw:forza:2026-10-08"), oggi, LocalTime.of(9, 0))
    assertEquals(listOf("sw:corsa_chiave:2026-10-09"), mattina.map { it.externalId })
    val sera = Checkin.daChiedere(eventi, emptySet(), oggi, LocalTime.of(19, 0))
    assertEquals(listOf("sw:forza:2026-10-08", "sw:corsa_chiave:2026-10-09", "sw:bici_chiave:2026-10-10"), sera.map { it.externalId })
  }

  @Test
  fun formatoPerIlCervello() {
    val m =
        mapOf(
            "sw:a:2026-10-09" to Checkin.Risposta(Checkin.MODIFICATA, 7, 35, "2026-10-09", setOf("gambe_pesanti")),
            "sw:b:2026-10-08" to Checkin.Risposta(Checkin.SALTATA, null, null, "2026-10-08"))
    val j = Checkin.scrivi(m)
    val a = j.getAsJsonObject("sw:a:2026-10-09")
    assertEquals("modificata", a.get("stato")!!.asString)
    assertEquals(7, a.get("rpe")!!.asInt)
    assertEquals(35, a.get("minuti")!!.asInt)
    assertEquals("2026-10-09", a.get("data")!!.asString)
    val b = j.getAsJsonObject("sw:b:2026-10-08")
    assertFalse(b.has("rpe"))
    assertEquals(m, Checkin.leggi(j.toString()))
  }

  @Test
  fun modalitaDegradataDalRiepilogo() {
    val deg = JsonParser.parseString("""{"data":"2026-10-10","messaggi":[{"tipo":"avviso","codice":"attivita_non_leggibili","testo":"x"}]}""").asJsonObject
    val ok = JsonParser.parseString("""{"data":"2026-10-10","messaggi":[{"tipo":"avviso","codice":"tsb_basso","testo":"x"}]}""").asJsonObject
    assertTrue(Checkin.degradatoNel(deg))
    assertFalse(Checkin.degradatoNel(ok))
  }
}
