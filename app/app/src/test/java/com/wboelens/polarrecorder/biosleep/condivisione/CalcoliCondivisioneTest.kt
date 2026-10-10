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
  fun distanza_nuotoSempreInMetri() {
    assertEquals(true to "1500", Calcoli.distanza(1.5, Locale.ITALIAN, "NUOTO"))
  }

  @Test
  fun ritmo_perSport() {
    // 10 km in 45:00 -> 4:30 /km
    assertEquals(Calcoli.Ritmo(Calcoli.TipoRitmo.PASSO_KM, "4:30"),
        Calcoli.ritmo("CORSA", 10.0, 2700))
    // 1500 m in 27:00 -> 1:48 /100 m
    assertEquals(Calcoli.Ritmo(Calcoli.TipoRitmo.PASSO_100M, "1:48"),
        Calcoli.ritmo("NUOTO", 1.5, 1620))
    // 60 km in 2 h -> 30,0 km/h
    assertEquals(Calcoli.Ritmo(Calcoli.TipoRitmo.VELOCITA, "30,0"),
        Calcoli.ritmo("BICI", 60.0, 7200, Locale.ITALIAN))
    assertNull(Calcoli.ritmo("PALESTRA", 5.0, 3600))
    assertNull(Calcoli.ritmo("CORSA", null, 3600))
    assertNull(Calcoli.ritmo("CORSA", 10.0, 0))
  }

  @Test
  fun minSec_arrotondaSenzaSessanta() {
    assertEquals("4:00", Calcoli.minSec(239.6))
    assertEquals("5:01", Calcoli.minSec(300.6))
  }

  @Test
  fun campiIniziali_predefinitiFiltratiSuIDisponibili() {
    val disp = listOf(CampoSeduta.DISTANZA, CampoSeduta.TSS, CampoSeduta.POTENZA,
        CampoSeduta.FC_MEDIA, CampoSeduta.CALORIE)
    // senza scelta salvata: predefiniti disponibili, poi gli altri, FC esclusa
    assertEquals(listOf(CampoSeduta.DISTANZA, CampoSeduta.TSS, CampoSeduta.POTENZA,
        CampoSeduta.CALORIE), Calcoli.campiIniziali(null, disp))
    // scelta salvata: rispettata, filtrata su cio' che la seduta ha
    assertEquals(listOf(CampoSeduta.FC_MEDIA, CampoSeduta.TSS),
        Calcoli.campiIniziali("FC_MEDIA,RITMO,TSS,XYZ", disp))
  }

  @Test
  fun alterna_massimoQuattroEOrdineDeiChip() {
    val quattro = listOf(CampoSeduta.DISTANZA, CampoSeduta.RITMO, CampoSeduta.TSS,
        CampoSeduta.RECUPERO)
    assertEquals(quattro, Calcoli.alterna(quattro, CampoSeduta.POTENZA))
    assertEquals(listOf(CampoSeduta.DISTANZA, CampoSeduta.TSS, CampoSeduta.RECUPERO),
        Calcoli.alterna(quattro, CampoSeduta.RITMO))
    assertEquals(listOf(CampoSeduta.DISTANZA, CampoSeduta.TSS),
        Calcoli.alterna(listOf(CampoSeduta.TSS), CampoSeduta.DISTANZA))
  }

  @Test
  fun disponibili_soloCampiConDati() {
    val corsa = CardSeduta(sport = "Corsa", nome = "Soglia", durataMin = 45,
        giorno = java.time.LocalDate.of(2026, 10, 2), distanzaKm = 10.0, tss = 60,
        sportCodice = "CORSA", fcMedia = 150, pianoPct = 0)
    assertEquals(listOf(CampoSeduta.DISTANZA, CampoSeduta.RITMO, CampoSeduta.TSS,
        CampoSeduta.FC_MEDIA), corsa.disponibili())
    val palestra = CardSeduta(sport = "Palestra", nome = "Forza", durataMin = 40,
        giorno = java.time.LocalDate.of(2026, 10, 2), sportCodice = "PALESTRA")
    assertTrue(palestra.disponibili().isEmpty())
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
