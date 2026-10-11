package com.wboelens.polarrecorder.biosleep.web

import com.wboelens.polarrecorder.biosleep.readiness.BioBaseline
import com.wboelens.polarrecorder.biosleep.readiness.FcRiposo
import com.wboelens.polarrecorder.biosleep.sonno.Contributo
import com.wboelens.polarrecorder.biosleep.sonno.Punteggio
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** Nomi dei campi come nel contratto del web (riepilogo.ts Biometria, snapshot.ts Notte). */
class DatiParte3Test {
  @Test
  fun biometriaConINomiDelRiepilogo() {
    val b =
        BioBaseline(
            ok = true, nGiorniHrv = 30, banda = "verde", baselineHrv = 56.6, rolling7Hrv = 56.2, pctVsBaseline = -0.8,
            persistenzaGgSotto = 0, zLn = -0.1, normalRangeMs = 54.0 to 59.4, direzione7v7 = "stabile", ggRitardo = 0,
            fcRiposo = FcRiposo(41.6, 42.3, 0.7, false))
    val j = DatiParte3.biometria(b)
    assertEquals(
        setOf("ok", "provvisoria", "banda", "hrv_7gg", "hrv_baseline", "pct_vs_baseline", "z_ln", "range_ms", "direzione_7v7",
            "gg_sotto_range", "fc_7gg", "fc_baseline", "fc_delta", "fc_allarme", "gg_ritardo"),
        j.keySet())
    assertEquals("verde", j.get("banda")!!.asString)
    assertEquals(54.0, j.getAsJsonArray("range_ms").get(0).asDouble)
    assertFalse(j.get("fc_allarme")!!.asBoolean)
  }

  @Test
  fun punteggioEContributi() {
    val j = DatiParte3.punteggio(Punteggio(88, listOf(Contributo("Durata", 73, 35), Contributo("Efficienza", 100, 15))))
    assertEquals(88, j.get("punteggio_sonno")!!.asInt)
    val c = j.getAsJsonArray("punteggio_contributi")
    assertEquals(2, c.size())
    assertEquals(setOf("nome", "punti", "peso"), c.get(0).asJsonObject.keySet())
  }
}
