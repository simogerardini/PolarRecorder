package com.wboelens.polarrecorder.biosleep.garmin

import com.google.gson.JsonParser
import com.wboelens.polarrecorder.biosleep.NightSummary
import com.wboelens.polarrecorder.biosleep.SleepDb
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Release 1.0.9 (Parte 7): nessuna fascia + notti Garmin -> elenco e dettaglio delle notti. */
@RunWith(RobolectricTestRunner::class)
class FonteNottiTest {
  @Test
  fun regola() {
    assertTrue(FonteNotti.Stato(fascia = false, garmin = false, notti = false).soloCollegaFascia)
    assertTrue(FonteNotti.Stato(fascia = false, garmin = true, notti = false).elenco)
    assertTrue(FonteNotti.Stato(fascia = false, garmin = false, notti = true).elenco)
    assertTrue(FonteNotti.Stato(fascia = true, garmin = false, notti = false).elenco)
  }

  @Test
  fun nessunaFasciaConNottiGarmin() {
    val ctx = RuntimeEnvironment.getApplication()
    val inizio = 1_792_878_088_000L
    val fine = inizio + 8 * 3_600_000L
    fun gmt(ms: Long) = Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC).toLocalDateTime().toString() + ".0"
    val sonno =
        JsonParser.parseString(
            """{"sleepTimeSeconds":29280,"sleepStartTimestampGMT":$inizio,"sleepEndTimestampGMT":$fine,
               "deepSleepSeconds":4560,"lightSleepSeconds":24720,"remSleepSeconds":0,"awakeSleepSeconds":600,
               "avgHeartRate":56.0}""").asJsonObject
    val hrv =
        JsonParser.parseString(
            """{"hrvSummary":{"lastNightAvg":34},"hrvReadings":[""" +
                (0 until 96).joinToString(",") { i -> """{"hrvValue":34,"readingTimeGMT":"${gmt(inizio + i * 300_000L + 60_000L)}"}""" } +
                "]}").asJsonObject
    val fc = (0 until 240).map { i -> inizio + i * 120_000L to 55 }
    val id = GarminNotte.salva(ctx, GarminNotte.componi(sonno, hrv, fc)!!)

    // nessuna fascia abbinata, una notte Garmin: si apre l'elenco, non "Collega la tua fascia"
    val st = FonteNotti.stato(ctx)
    assertFalse(st.fascia)
    assertTrue(st.notti)
    assertTrue(st.elenco)
    assertFalse(st.soloCollegaFascia)

    // il dettaglio ha cio' che gli serve: notte, fasi senza ipnogramma, finestre FC/rMSSD
    val db = SleepDb.get(ctx)
    val n = db.listNights().single { it.sessionId == id }
    assertEquals(NightSummary.SORGENTE_GARMIN, n.summary.sorgente)
    assertEquals(488, n.stages!!.tstMin)
    assertEquals(96, db.loadWindows(id).size)
  }
}
