package com.wboelens.polarrecorder.biosleep.cervello

import com.google.gson.JsonParser
import com.wboelens.polarrecorder.biosleep.intervals.Credenziali
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CaldoTest {
  @Test
  fun posizioneArrotondataAUnChilometro() {
    val p = Posizione.arrotondata(45.467891, 7.876543)
    assertEquals(45.47, p.lat)
    assertEquals(7.88, p.lon)
  }

  @Test
  fun posizioneNelConfigSoloSeC_e() {
    val con = JsonParser.parseString(ConfigCervello(Credenziali.Token("t"), "/c", posizione = Posizione(45.47, 7.88)).json()).asJsonObject
    assertEquals(45.47, con.getAsJsonObject("posizione")!!.get("lat")!!.asDouble)
    assertEquals(7.88, con.getAsJsonObject("posizione")!!.get("lon")!!.asDouble)
    val senza = JsonParser.parseString(ConfigCervello(Credenziali.Token("t"), "/c").json()).asJsonObject
    assertTrue(!senza.has("posizione"), "senza posizione il campo si omette")
    assertTrue(!ConfigCervello(Credenziali.Token("t"), "/c", posizione = Posizione(45.47, 7.88)).toString().contains("45.47"), "mai nei log")
  }

  @Test
  fun profiloCaldoPredefinito() {
    val c = ProfiloAtleta().json().getAsJsonObject("caldo")
    assertEquals(true, c.get("converti_corsa")!!.asBoolean)
    assertEquals(18, c.get("ora_feriale")!!.asInt)
    assertEquals(10, c.get("ora_weekend")!!.asInt)
  }
}
