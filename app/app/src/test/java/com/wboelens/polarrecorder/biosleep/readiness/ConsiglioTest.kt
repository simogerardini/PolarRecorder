package com.wboelens.polarrecorder.biosleep.readiness

import com.wboelens.polarrecorder.biosleep.readiness.Consiglio.Livello
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConsiglioTest {
  private fun b(
      banda: String,
      z: Double? = 0.3,
      sotto: Int = 0,
      fcAllarme: Boolean = false,
      cv: Boolean = false,
      dir: String? = "stabile",
  ) =
      BioBaseline(
          ok = true, nGiorniHrv = 30, banda = banda, zLn = z, persistenzaGgSotto = sotto, cvCollassato = cv,
          direzione7v7 = dir, fcRiposo = FcRiposo(47.0, 50.0, if (fcAllarme) 5.0 else 0.5, fcAllarme))

  @Test
  fun livelli() {
    assertEquals(Livello.SPINGI, Consiglio.calcola(b("verde", z = 0.4), 7.2)!!.livello)
    assertEquals(Livello.NORMALE, Consiglio.calcola(b("verde", z = -0.2), 7.2)!!.livello)
    assertEquals(Livello.PIANO, Consiglio.calcola(b("giallo", z = -1.2), 7.2)!!.livello)
    assertEquals(Livello.RIPOSO, Consiglio.calcola(b("giallo", z = -1.2, sotto = 3), 7.2)!!.livello)
    assertEquals(Livello.RIPOSO, Consiglio.calcola(b("rosso", z = -2.0), 7.2)!!.livello)
  }

  @Test
  fun nonSiSpingeSe() {
    // FC alta: vai piano anche con banda verde
    assertEquals(Livello.PIANO, Consiglio.calcola(b("verde", fcAllarme = true), 7.2)!!.livello)
    // saturazione, CV collassato, HRV in calo, sonno corto: si resta al piano
    for ((x, motivo) in listOf(
        b("verde", z = 1.4) to "saturazione",
        b("verde", cv = true) to "quasi azzerata",
        b("verde", dir = "in calo") to "in calo",
    )) {
      val e = Consiglio.calcola(x, 7.2)!!
      assertEquals(Livello.NORMALE, e.livello)
      assertTrue(e.motivi.any { motivo in it }, e.motivi.toString())
    }
    val corto = Consiglio.calcola(b("verde"), 5.5)!!
    assertEquals(Livello.NORMALE, corto.livello)
    assertEquals(listOf("Sonno corto stanotte (5h30)"), corto.motivi)
  }

  @Test
  fun senzaBandaNessunConsiglio() {
    assertNull(Consiglio.calcola(BioBaseline(ok = false, nGiorniHrv = 5), 7.0))
    assertNull(Consiglio.calcola(b("grigio"), 7.0))
  }
}
