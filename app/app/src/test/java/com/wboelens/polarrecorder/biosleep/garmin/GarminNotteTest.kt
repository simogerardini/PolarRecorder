package com.wboelens.polarrecorder.biosleep.garmin

import com.google.gson.JsonParser
import com.wboelens.polarrecorder.biosleep.NightSummary
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Forma dei JSON della sonda del 25/10/2026, con valori inventati (niente dati reali nel repo). */
class GarminNotteTest {
  private val inizio = 1_792_878_088_000L
  private val fine = inizio + 8 * 3_600_000L

  private val sonno =
      JsonParser.parseString(
          """{"calendarDate":"2026-10-25","sleepTimeSeconds":29280,"sleepStartTimestampGMT":$inizio,
             "sleepEndTimestampGMT":$fine,"deepSleepSeconds":4560,"lightSleepSeconds":24720,
             "remSleepSeconds":0,"awakeSleepSeconds":600,"avgHeartRate":56.0}""").asJsonObject

  private fun gmt(ms: Long) = Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC).toLocalDateTime().toString() + ".0"

  private val hrv =
      JsonParser.parseString(
          """{"hrvSummary":{"calendarDate":"2026-10-25","lastNightAvg":34},"hrvReadings":[""" +
              (0 until 96).joinToString(",") { i ->
                """{"hrvValue":${if (i < 48) 30 else 40},"readingTimeGMT":"${gmt(inizio + i * 300_000L + 60_000L)}"}"""
              } + "]}").asJsonObject

  private val fc = (0 until 240).map { i -> inizio + i * 120_000L to (if (i == 100) 48 else 55) }

  @Test
  fun notteCompleta() {
    val n = GarminNotte.componi(sonno, hrv, fc, fcRiposo = 52.0)!!
    val s = n.summary
    assertEquals(NightSummary.SORGENTE_GARMIN, s.sorgente)
    assertEquals(34.0, s.rmssd!!, 0.0)
    assertEquals(40.0, s.rmssdLate!!, 0.0)
    assertEquals(100.0, s.qualityPct!!, 0.0)
    assertEquals(56.0, s.hrAvg, 0.0)
    assertEquals(48.0, s.hrMin, 0.0)
    assertTrue(s.restingHr < 55.0)
    assertEquals(96, s.windowsOk)
    assertEquals(96, s.windowsTotal)
    assertNull(s.respRate)
    val st = n.stages
    assertEquals(488, st.tstMin)
    assertEquals(76, st.deepMin)
    assertEquals(412, st.lightMin)
    assertEquals(0, st.remMin)
    assertEquals(10, st.wakeMin)
    assertEquals("", st.hypnogram)
    assertEquals(GarminNotte.MODO_FASI, st.mode)
    assertEquals(96, n.windows.size)
  }

  @Test
  fun storicoSenzaSerie() {
    val h = JsonParser.parseString("""{"hrvSummary":{"lastNightAvg":54}}""").asJsonObject
    val s = GarminNotte.componi(sonno, h, emptyList(), fcRiposo = 52.0, fcMinima = 49.0)!!.summary
    assertEquals(54.0, s.rmssd!!, 0.0)
    assertEquals(52.0, s.restingHr, 0.0)
    assertEquals(49.0, s.hrMin, 0.0)
    assertNull(s.qualityPct) // senza letture: le colonne di precisione non si scrivono
  }

  @Test
  fun sonnoNonAncoraPronto() {
    val vuoto = JsonParser.parseString("""{"sleepTimeSeconds":null,"sleepStartTimestampGMT":null}""").asJsonObject
    assertNull(GarminNotte.componi(vuoto, null, fc))
  }

  @Test
  fun serieFcSenzaNulli() {
    val j = JsonParser.parseString("""{"heartRateValues":[[1,50],[2,null],[3,52]]}""")
    assertEquals(listOf(1L to 50, 3L to 52), GarminNotte.serieFc(j))
  }
}
