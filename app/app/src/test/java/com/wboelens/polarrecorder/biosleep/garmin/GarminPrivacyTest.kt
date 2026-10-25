package com.wboelens.polarrecorder.biosleep.garmin

import com.google.gson.JsonParser
import com.wboelens.polarrecorder.biosleep.NightSummary
import com.wboelens.polarrecorder.biosleep.SleepDb
import com.wboelens.polarrecorder.biosleep.cache.NottiLocali
import com.wboelens.polarrecorder.biosleep.intervals.CampiWellness
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Parte 4: le notti Garmin restano sul telefono (fuori da web e card), e lo scollegamento puo' cancellarle. */
@RunWith(RobolectricTestRunner::class)
class GarminPrivacyTest {
  @Test
  fun notteGarminSoloSulTelefono() {
    val ctx = RuntimeEnvironment.getApplication()
    val inizio = 1_792_878_088_000L
    val fine = inizio + 28_800_000L
    val sonno =
        JsonParser.parseString(
            """{"sleepTimeSeconds":29280,"sleepStartTimestampGMT":$inizio,"sleepEndTimestampGMT":$fine,
               "deepSleepSeconds":4560,"lightSleepSeconds":24720,"remSleepSeconds":0,"awakeSleepSeconds":600,
               "avgHeartRate":56.0}""").asJsonObject
    val hrv = JsonParser.parseString("""{"hrvSummary":{"lastNightAvg":34}}""").asJsonObject
    GarminNotte.salva(ctx, GarminNotte.componi(sonno, hrv, emptyList(), fcRiposo = 52.0, fcMinima = 49.0)!!)
    val giorno = Instant.ofEpochMilli(fine).atZone(ZoneId.systemDefault()).toLocalDate()

    val sulTelefono = NottiLocali.righe(ctx, giorno, giorno)
    assertEquals(1, sulTelefono.size)
    assertEquals(NightSummary.SORGENTE_GARMIN, sulTelefono[0].get(CampiWellness.F_SOURCE).asInt)
    assertTrue("in uscita non deve esserci", NottiLocali.righe(ctx, giorno, giorno, conGarmin = false).isEmpty())

    assertTrue(GarminScelta.scollega(ctx, cancellaNotti = true) >= 1)
    assertTrue(SleepDb.get(ctx).sessioniDa(NightSummary.SOURCE_GARMIN).isEmpty())
    assertTrue(NottiLocali.righe(ctx, giorno, giorno).isEmpty())
  }
}
