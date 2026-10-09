package com.wboelens.polarrecorder.biosleep.cervello

import com.google.gson.JsonParser
import com.wboelens.polarrecorder.biosleep.intervals.Credenziali
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LinguaTest {
  @Test
  fun linguaDelTelefono() {
    assertEquals("it", Lingua.daLocale("it"))
    assertEquals("es", Lingua.daLocale("ES"))
    assertEquals("zh", Lingua.daLocale("zh"))
    assertEquals("en", Lingua.daLocale("de"), "lingua non supportata: inglese")
    assertTrue(Lingua.calendarioInInglese("zh"))
  }

  @Test
  fun linguaNelConfig() {
    val o = JsonParser.parseString(ConfigCervello(Credenziali.Token("t"), "/c", lingua = "es").json()).asJsonObject
    assertEquals("es", o.get("lingua")!!.asString)
  }
}
