package com.wboelens.polarrecorder.biosleep.riepilogo

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Una notifica sola per data, riepilogo che sopravvive, attesa che scade anche senza l'ultimo giro. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RiepilogoDbTest {
  private lateinit var db: RiepilogoDb
  private val data = "2026-09-23"

  @Before
  fun apri() {
    db = RiepilogoDb(RuntimeEnvironment.getApplication(), null)
  }

  @After
  fun chiudi() = db.close()

  @Test
  fun unaSolaNotificaPerData() {
    db.salva(data, """{"data":"$data"}""")
    assertTrue(db.segnaNotificato(data))
    assertFalse("seconda volta: niente notifica", db.segnaNotificato(data))
    db.salva(data, """{"data":"$data","v":1}""") // il coach riscrive la NOTE: resta notificato
    assertTrue(db.notificato(data))
    assertEquals("""{"data":"$data","v":1}""", db.json(data))
  }

  @Test
  fun attesaScadutaDopoDueOreAncheSenzaUltimoGiro() {
    val t0 = 1_000_000L
    db.iniziaAttesa(data, t0)
    assertFalse(db.scaduta(data, t0 + Attesa.DURATA_MS - 1))
    assertTrue(db.scaduta(data, t0 + Attesa.DURATA_MS))
    db.iniziaAttesa(data, t0 + Attesa.DURATA_MS) // notte reinviata: si riparte
    assertFalse(db.scaduta(data, t0 + Attesa.DURATA_MS + 1))
    db.segnaScaduta(data)
    assertTrue(db.scaduta(data, t0 + Attesa.DURATA_MS + 1))
  }

  @Test
  fun storicoOltre120GiorniRimosso() {
    db.salva("2026-01-01", "{}")
    db.salva(data, "{}")
    assertEquals(null, db.json("2026-01-01"))
  }
}
