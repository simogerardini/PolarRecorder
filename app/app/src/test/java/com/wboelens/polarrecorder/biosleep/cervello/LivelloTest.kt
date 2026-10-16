package com.wboelens.polarrecorder.biosleep.cervello

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** Livello dell'atleta per le sedute avanzate (cervello 2026.10.13-avanzate): profilo.livello. */
class LivelloTest {
  @Test
  fun livelloNelProfiloSoloSeScelto() {
    assertFalse(ProfiloAtleta().json().has("livello"), "non scelto: il cervello usa quello della palestra")
    for (l in ProfiloAtleta.LIVELLI.map { it.first }) assertEquals(l, ProfiloAtleta(livello = l).json().get("livello").asString)
    assertEquals(listOf("principiante", "intermedio", "avanzato"), ProfiloAtleta.LIVELLI.map { it.first })
  }

  @Test
  fun dallaPalestra() {
    assertEquals("principiante", ProfiloAtleta.daPalestra("beginner"))
    assertEquals("intermedio", ProfiloAtleta.daPalestra("intermediate"))
    assertEquals("avanzato", ProfiloAtleta.daPalestra("advanced"))
  }
}
