package com.wboelens.polarrecorder.biosleep.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin

class RespiroRsaTest {
  @Test
  fun segueIlRespiroDegliRr() {
    // RR a 55 bpm che oscillano di +-40 ms con un respiro ogni ~5 battiti
    val r = RespiroRsa()
    val aperture = (0 until 60).mapNotNull { i -> r.aggiungi((1090 + 40 * sin(2 * PI * i / 5.0)).toInt()) }
    assertTrue(aperture.size > 40)
    val serie = (0 until 60).map { i -> (1090 + 40 * sin(2 * PI * i / 5.0)).toInt() }
    val r2 = RespiroRsa()
    val coppie = serie.map { it to r2.aggiungi(it) }.filter { it.second != null }
    val corto = coppie.minByOrNull { it.first }!!.second!!
    val lungo = coppie.maxByOrNull { it.first }!!.second!!
    assertTrue(corto > 0.7f, "RR piu' corto (inspirazione) = fiore aperto: $corto")
    assertTrue(lungo < 0.3f, "RR piu' lungo (espirazione) = fiore chiuso: $lungo")
    assertEquals(55, r2.bpm, "60000 / 1090 ms = 55 bpm")
  }

  @Test
  fun scartaArtefattiEdEctopici() {
    val r = RespiroRsa()
    repeat(10) { r.aggiungi(1000) }
    assertNull(r.aggiungi(250), "fuori range")
    assertNull(r.aggiungi(1500), "oltre il 20% dalla mediana")
    assertEquals(60, r.bpm)
  }
}
