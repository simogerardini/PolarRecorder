package com.wboelens.polarrecorder.biosleep.sonno

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SonnoTest {
  private val oggi = LocalDate.of(2026, 10, 4)

  /** Notte che finisce la mattina di [g], addormentamento alle [h]:[m] della sera prima. */
  private fun notte(g: LocalDate, h: Int, m: Int, sonnoMin: Int, veglia: Int = 25) =
      NotteSonno(
          giorno = g,
          inizio = LocalDateTime.of(g.minusDays(if (h >= 12) 1 else 0), java.time.LocalTime.of(h, m)),
          fine = LocalDateTime.of(g.minusDays(if (h >= 12) 1 else 0), java.time.LocalTime.of(h, m)).plusMinutes((sonnoMin + veglia).toLong()),
          aLettoMin = sonnoMin + veglia + 10, sonnoMin = sonnoMin, profondoMin = sonnoMin * 17 / 100,
          remMin = sonnoMin * 21 / 100, vegliaMin = veglia)

  @Test
  fun fabbisognoEDeficit() {
    assertEquals(false, Debito.fabbisogno(listOf(notte(oggi, 23, 0, 420)), oggi).appreso)
    // 30 notti: settimana 7 h, weekend 8,5 h -> fabbisogno dalle notti lunghe
    val notti = (0L until 30).map { i -> val g = oggi.minusDays(i); notte(g, 23, 0, if (g.dayOfWeek.value >= 6) 510 else 420) }
    val f = Debito.fabbisogno(notti, oggi)
    assertTrue(f.appreso)
    assertTrue(f.ore in 8.0..8.6, "fabbisogno ${f.ore}")
    val d = Debito.deficit(notti, oggi, f.ore)
    assertTrue(d.ore > 2, "una settimana a 7 h con fabbisogno ~8,3 h accumula debito: ${d.ore}")
    // due notti lunghe ripagano
    val ripagato = Debito.deficit(notti.map { if (it.giorno >= oggi.minusDays(1)) it.copy(sonnoMin = 660) else it }, oggi, f.ore)
    assertTrue(ripagato.ore < d.ore)
    assertEquals(LivelloDeficit.NESSUNO, Debito.deficit(notti.map { it.copy(sonnoMin = 600) }, oggi, f.ore).livello)
  }

  @Test
  fun cronotipoEAllineamento() {
    assertNull(OrologioBiologico.cronotipo(listOf(notte(oggi, 23, 0, 420)), oggi, 8.0))
    // si addormenta alle 22:15 e dorme 8 h -> centro 2:15 (come lo screenshot di Oura)
    val notti = (0L until 40).map { notte(oggi.minusDays(it), 22, 15, 480 - 25) }
    val c = OrologioBiologico.cronotipo(notti, oggi, 8.0)!!
    assertEquals(TipoCronotipo.MATTUTINO, c.tipo)
    assertEquals("02:15", orario(c.centroMin).toString())
    assertEquals("22:15", orario(c.sonnoMin).toString())
    assertEquals("06:15", orario(c.svegliaMin).toString())
    // stanotte addormentato 2 h piu' tardi: in ritardo di ~2 h
    val tardi = notte(oggi, 0, 15, 455)
    val scarto = OrologioBiologico.scarto(tardi, c)
    assertTrue(scarto in 110..130, "scarto $scarto")
    assertEquals(Allineamento.IN_RITARDO, OrologioBiologico.allineamento(scarto))
  }

  @Test
  fun punteggio() {
    val buona = PunteggioSonno.calcola(notte(oggi, 22, 30, 480, veglia = 15), 8.0, null)
    assertTrue(buona.totale >= 85, "notte piena: ${buona.totale}")
    assertEquals(40, buona.contributi.first { it.nome == "Durata" }.peso, "senza cronotipo la durata pesa 40")
    val corta = PunteggioSonno.calcola(notte(oggi, 1, 0, 300, veglia = 70), 8.0, null)
    assertTrue(corta.totale < 70, "5 h con risvegli: ${corta.totale}")
    assertEquals("Da curare", PunteggioSonno.etichetta(corta.totale))
  }

  @Test
  fun notteDelDatabaseDiStanotte() {
    // la notte degli screenshot: registrazione 23:21 -> 08:34, addormentamento 23:36, risveglio 08:33
    val z = java.time.ZoneId.of("Europe/Rome")
    fun ms(g: LocalDate, h: Int, m: Int) = LocalDateTime.of(g, java.time.LocalTime.of(h, m)).atZone(z).toInstant().toEpochMilli()
    val ieri = LocalDate.of(2026, 10, 3)
    val fasi =
        com.wboelens.polarrecorder.biosleep.SleepStages(
            hypnoStartMs = 0, hypnogram = "", sleepOnsetMs = ms(ieri, 23, 36), sleepEndMs = ms(oggi, 8, 33),
            tstMin = 526, deepMin = 230, lightMin = 108, remMin = 188, wakeMin = 10, mode = "HRV+ACC")
    val s = SonnoRepo.daRegistrazione(ms(ieri, 23, 21), ms(oggi, 8, 34), fasi, z)!!
    assertEquals(oggi, s.giorno)
    assertEquals(553, s.aLettoMin)
    assertEquals(15, s.latenzaMin, "addormentamento 15 minuti dopo l'inizio, come nella schermata della notte")
    assertEquals("04:04", orario(minutiNotte(s.centro)).toString(), "centro tra 23:36 e 08:33")
    val stato = SonnoRepo.calcola(listOf(s), oggi)
    assertTrue(stato.punteggio!!.totale >= 85, "8 h 46' con efficienza 95%: ${stato.punteggio!!.totale}")
    assertNull(stato.cronotipo, "una notte non basta per il cronotipo")
    assertEquals(14, stato.ultimi14.size)
    assertEquals(526 / 60.0, stato.ultimi14.last().second)
  }
}
