package com.wboelens.polarrecorder.biosleep.readiness

import org.junit.Assert.assertEquals
import org.junit.Test

/** Stessi casi di prova_sorgente.py (campi.filtra_sorgente nel cervello). */
class SorgenteNotteTest {
  private fun r(id: String, rmssd: Double?, src: Double? = null) =
      WellnessBio(id, null, rmssd?.let { PyNum.of(it) }, null, null, null, src?.let { PyNum.of(it) })

  private val base = listOf(r("2026-10-20", 60.0), r("2026-10-21", 62.0, 1.0), r("2026-10-22", null),
      r("2026-10-23", 45.0, 2.0), r("2026-10-24", 47.0, 2.0))

  @Test
  fun ultimaNotteGarmin() =
      assertEquals(listOf("2026-10-22", "2026-10-23", "2026-10-24"), BioSleepSeries.filtraSorgente(base).map { it.id })

  @Test
  fun ritornoAllaFascia() =
      assertEquals(listOf("2026-10-20", "2026-10-21", "2026-10-22", "2026-10-25"),
          BioSleepSeries.filtraSorgente(base + r("2026-10-25", 61.0)).map { it.id })

  @Test
  fun senzaNotti() = assertEquals(listOf("x"), BioSleepSeries.filtraSorgente(listOf(r("x", null))).map { it.id })

  @Test
  fun serieGarminSoltanto() =
      assertEquals(listOf(45.0, 47.0), BioSleepSeries.daWellness(base).map { it.hrvMs?.v })
}
