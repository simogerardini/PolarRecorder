package com.wboelens.polarrecorder.biosleep.garmin

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.google.gson.JsonParser
import com.wboelens.polarrecorder.biosleep.NightSummary
import com.wboelens.polarrecorder.biosleep.SleepDb
import com.wboelens.polarrecorder.biosleep.intervals.CampiWellness
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** La notte Garmin nel database: una sola sessione, fasi senza ipnogramma, sorgente 2 su Intervals.icu. */
@RunWith(RobolectricTestRunner::class)
class GarminSalvataggioTest {
  @Test
  fun salvaERilegge() {
    val ctx = RuntimeEnvironment.getApplication()
    val inizio = 1_792_878_088_000L
    val sonno =
        JsonParser.parseString(
            """{"sleepTimeSeconds":29280,"sleepStartTimestampGMT":$inizio,"sleepEndTimestampGMT":${inizio + 28_800_000L},
               "deepSleepSeconds":4560,"lightSleepSeconds":24720,"remSleepSeconds":0,"awakeSleepSeconds":600,
               "avgHeartRate":56.0}""").asJsonObject
    val hrv = JsonParser.parseString("""{"hrvSummary":{"lastNightAvg":34}}""").asJsonObject
    val notte = GarminNotte.componi(sonno, hrv, emptyList(), fcRiposo = 52.0, fcMinima = 49.0)!!
    val id = GarminNotte.salva(ctx, notte)
    assertEquals(id, GarminNotte.salva(ctx, notte)) // stessa notte: nessun doppione
    val r = SleepDb.get(ctx).loadNight(id)!!
    assertEquals(NightSummary.SORGENTE_GARMIN, r.summary.sorgente)
    val st = r.stages!!
    assertEquals(GarminNotte.MODO_FASI, st.mode)
    assertEquals(488, st.tstMin)
    assertEquals("", st.hypnogram)
    val w = IntervalsClient.buildWellness(r.summary, st)
    assertEquals(2, w.getInt(CampiWellness.F_SOURCE))
    assertEquals(34.0, w.getDouble(CampiWellness.F_RMSSD), 0.0)
    assertEquals(8.13, w.getDouble(CampiWellness.F_SLEEP_HOURS), 0.0)
  }
}
