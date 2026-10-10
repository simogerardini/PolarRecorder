package com.wboelens.polarrecorder.biosleep.condivisione

import java.io.File
import java.nio.file.Files
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalcoliCondivisioneTest {

  @Test
  fun fasi_leggeroCalcolatoEQuoteSommanoAUno() {
    val f = Calcoli.fasi(sonnoMin = 420, profondoMin = 90, remMin = 100, vegliaMin = 30)!!
    assertEquals(90f / 450f, f.profondo, 1e-6f)
    assertEquals(230f / 450f, f.leggero, 1e-6f)
    assertEquals(100f / 450f, f.rem, 1e-6f)
    assertEquals(30f / 450f, f.veglia, 1e-6f)
    assertEquals(1f, f.profondo + f.leggero + f.rem + f.veglia, 1e-6f)
  }

  @Test
  fun fasi_stadiazioneIncoerenteNonDaLeggeroNegativo() {
    val f = Calcoli.fasi(sonnoMin = 300, profondoMin = 200, remMin = 150, vegliaMin = 0)!!
    assertEquals(0f, f.leggero, 0f)
  }

  @Test
  fun fasi_tuttoAZeroNonSiDisegna() {
    assertNull(Calcoli.fasi(0, 0, 0, 0))
  }

  @Test
  fun oreMinuti() {
    assertEquals(7 to 24, Calcoli.oreMinuti(444))
    assertEquals(0 to 0, Calcoli.oreMinuti(-5))
  }

  @Test
  fun numero_separatoreDecimaleDellaLingua() {
    assertEquals("34,5", Calcoli.numero(34.46, 1, Locale.ITALIAN))
    assertEquals("34.5", Calcoli.numero(34.46, 1, Locale.ENGLISH))
    assertEquals("35", Calcoli.numero(34.5, 0, Locale.ITALIAN))
    assertEquals("1234,0", Calcoli.numero(1234.0, 1, Locale.ITALIAN))
  }

  @Test
  fun differenza_classificataComeVieneMostrata() {
    assertEquals(Calcoli.Differenza.PIU_GIOVANE, Calcoli.differenza(-4.2, 1))
    assertEquals(Calcoli.Differenza.PIU_VECCHIO, Calcoli.differenza(1.06, 1))
    // -0,04 si mostrerebbe "0,0": niente "0,0 anni in meno"
    assertEquals(Calcoli.Differenza.UGUALE, Calcoli.differenza(-0.04, 1))
    assertEquals(Calcoli.Differenza.UGUALE, Calcoli.differenza(0.4, 0))
    assertEquals("4,2", Calcoli.differenzaAssoluta(-4.2, 1, Locale.ITALIAN))
  }

  @Test
  fun distanza_metriSottoIlChilometro() {
    assertEquals(true to "850", Calcoli.distanza(0.85, Locale.ITALIAN))
    assertEquals(false to "10,2", Calcoli.distanza(10.24, Locale.ITALIAN))
    assertEquals(false to "1.5", Calcoli.distanza(1.5, Locale.ENGLISH))
  }

  @Test
  fun nomeFile_soloAscii() {
    assertEquals("noctalix_eta_123.png", Calcoli.nomeFile("età", 123))
    assertEquals("noctalix_card_1.png", Calcoli.nomeFile("", 1))
  }

  @Test
  fun pulisci_cancellaSoloIFileVecchi() {
    val dir: File = Files.createTempDirectory("condivisioni").toFile()
    val ora = 1_000_000_000L
    val vecchio = File(dir, "a.png").apply { writeText("x"); setLastModified(ora - 11 * 60_000L) }
    val recente = File(dir, "b.png").apply { writeText("x"); setLastModified(ora - 60_000L) }
    assertEquals(1, Calcoli.pulisci(dir, ora, 10 * 60_000L))
    assertFalse(vecchio.exists())
    assertTrue(recente.exists())
    dir.deleteRecursively()
  }
}
