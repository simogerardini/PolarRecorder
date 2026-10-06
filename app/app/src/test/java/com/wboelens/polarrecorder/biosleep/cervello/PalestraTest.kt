package com.wboelens.polarrecorder.biosleep.cervello

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PalestraTest {
  private fun lista(p: Palestra) = p.json().getAsJsonArray("attrezzatura")!!.map { it.asString }

  @Test
  fun predefinitaComeIlCervello() {
    val p = ProfiloAtleta().json().getAsJsonObject("palestra")
    assertEquals("intermediate", p.get("livello")!!.asString)
    assertEquals(
        listOf("barbell_gym", "kettlebell", "resistance_bands", "bodyweight_only", "hotel_minimal"), lista(Palestra()))
  }

  @Test
  fun corpoLiberoEHotelSempreInclusi() {
    // anche togliendo tutto: la lista non e' mai vuota (vuota = "predefinita" per il cervello)
    assertEquals(listOf("bodyweight_only", "hotel_minimal"), lista(Palestra(attrezzatura = emptySet(), livello = "beginner")))
    assertEquals(listOf("trx_suspension", "bodyweight_only", "hotel_minimal"), lista(Palestra(setOf("trx_suspension"))))
  }
}
