package com.wboelens.polarrecorder.biosleep.sopravvivenza

import android.content.ContentValues
import android.content.Context
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Cancellando una notte non devono restare le sue interruzioni, il rapporto fascia e la card. */
@RunWith(RobolectricTestRunner::class)
class CancellaNotteTest {
  private val ctx: Context = RuntimeEnvironment.getApplication()
  private val ev = EventiNotte.get(ctx)
  private val zona = ZoneId.systemDefault()

  private fun fineNotte(d: LocalDate) = d.atTime(7, 0).atZone(zona).toInstant().toEpochMilli()

  private fun prepara(id: Long, giorno: LocalDate) {
    ev.writableDatabase.insert(
        "interruzioni", null,
        ContentValues().apply {
          put("session_id", id)
          put("minuti_persi", 33)
          put("descrizione", "app chiusa dal sistema alle 03:12")
          put("fine_notte_ms", fineNotte(giorno))
        })
    ev.salvaFascia(id, "AFFIDABILI", "{}")
  }

  private fun righe(tabella: String, id: Long) =
      ev.readableDatabase.rawQuery("SELECT COUNT(*) FROM $tabella WHERE session_id = ?", arrayOf(id.toString())).use {
        it.moveToFirst()
        it.getInt(0)
      }

  private fun card() = ctx.getSharedPreferences("biosleep_interruzioni", Context.MODE_PRIVATE)

  @Test
  fun cancellaRigheECardDellaNotte() {
    val oggi = LocalDate.of(2026, 10, 9)
    prepara(101, oggi)
    prepara(102, oggi.minusDays(1))
    card().edit().putString("ultima", JSONObject().put("data", oggi.toString()).put("minuti", 33).toString()).commit()

    ev.dimenticaSessione(101)

    assertEquals(0, righe("interruzioni", 101))
    assertEquals(0, righe("fascia_sessione", 101))
    assertNull(card().getString("ultima", null))
    assertEquals(1, righe("interruzioni", 102)) // le altre notti restano
    assertEquals(1, righe("fascia_sessione", 102))
  }

  @Test
  fun cardDiUnAltraNotteResta() {
    val giorno = LocalDate.of(2026, 10, 5)
    prepara(201, giorno)
    card().edit().putString("ultima", JSONObject().put("data", "2026-10-08").toString()).commit()

    ev.dimenticaSessione(201)

    assertNotNull(card().getString("ultima", null))
  }

  @Test
  fun notteSenzaInterruzioniNonDaErrori() {
    ev.dimenticaSessione(999)
    assertEquals(0, righe("interruzioni", 999))
  }
}
