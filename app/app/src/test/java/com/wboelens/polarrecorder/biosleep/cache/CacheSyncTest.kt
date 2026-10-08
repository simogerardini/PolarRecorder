package com.wboelens.polarrecorder.biosleep.cache

import com.google.gson.JsonParser
import com.wboelens.polarrecorder.biosleep.readiness.FormaCalc
import com.wboelens.polarrecorder.biosleep.readiness.Py
import com.wboelens.polarrecorder.biosleep.readiness.PyJson
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Cache su un vero SQLite (Robolectric), con un lettore finto al posto della rete.
 * Ogni test descrive un comportamento che le schermate danno per scontato.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CacheSyncTest {
  private lateinit var db: CacheDb
  private val oggi = LocalDate.of(2026, 10, 2)
  private val adesso = 1_759_400_000_000L

  // Tre giorni costruiti con la stessa EWMA di Intervals.icu: il 01/10 seduta da 35 svolta,
  // il 02/10 (oggi) 40 solo pianificati e non ancora fatti, ma gia' contati nella riga di Intervals.
  private val ctl0 = 46.0
  private val atl0 = 50.0
  private val ctl1 = ctl0 * FormaCalc.EWMA_CTL + 35 * (1 - FormaCalc.EWMA_CTL)
  private val atl1 = atl0 * FormaCalc.EWMA_ATL + 35 * (1 - FormaCalc.EWMA_ATL)
  private val ctl2 = ctl1 * FormaCalc.EWMA_CTL + 40 * (1 - FormaCalc.EWMA_CTL)
  private val atl2 = atl1 * FormaCalc.EWMA_ATL + 40 * (1 - FormaCalc.EWMA_ATL)

  private val wellness =
      """[{"id":"2026-09-30","ctl":$ctl0,"atl":$atl0},
          {"id":"2026-10-01","ctl":$ctl1,"atl":$atl1,"NoctalixRMSSD":56,"NoctalixAvgHR":46.4,"NoctalixQuality":99.6},
          {"id":"2026-10-02","ctl":$ctl2,"atl":$atl2,"NoctalixRMSSD":55.8,"NoctalixAvgHR":48.9}]"""
  private val eventi =
      """[{"id":101,"start_date_local":"2026-10-02T00:00:00","name":"Soglia","icu_training_load":40},
          {"id":102,"start_date_local":"2026-10-05T00:00:00","name":"Lungo","icu_training_load":90},
          {"name":"senza id","start_date_local":"2026-10-03T00:00:00"}]"""
  private val attivita = """[{"id":"i1","start_date_local":"2026-10-01T07:00:00","icu_training_load":35}]"""

  private fun lettore(risposte: Map<String, String>, chiamate: MutableList<String> = mutableListOf()) =
      { risorsa: String, da: LocalDate, a: LocalDate ->
        chiamate.add("$risorsa $da $a")
        risposte[risorsa]?.let { Lettura.Ok(it) } ?: Lettura.Errore("$risorsa: offline")
      }

  private val tutto
    get() = mapOf("wellness" to wellness, "events" to eventi, "activities" to attivita)

  @Before
  fun apri() {
    db = CacheDb(RuntimeEnvironment.getApplication(), null) // database in memoria, nuovo per ogni test
  }

  @After
  fun chiudi() = db.close()

  @Test
  fun letturaCompletaScriveLeTreTabelleConLeFinestreGiuste() {
    val chiamate = mutableListOf<String>()
    assertNull(CacheSync.aggiorna(db, lettore(tutto, chiamate), oggi, adesso))
    assertEquals(
        listOf("wellness 2026-07-04 2026-10-02", "events 2026-07-04 2026-11-13", "activities 2026-07-04 2026-10-02"),
        chiamate)
    assertEquals(3, db.leggi(Tabella.WELLNESS, "2026-01-01", "2026-12-31").size)
    assertEquals("evento senza id scartato", 2, db.leggi(Tabella.EVENTI, "2026-01-01", "2026-12-31").size)
    assertEquals(1, db.leggi(Tabella.ATTIVITA, "2026-01-01", "2026-12-31").size)
    assertEquals(adesso.toString(), db.meta(CacheSync.META_ULTIMO_OK))
  }

  @Test
  fun interiEDecimaliSopravvivonoAllaCache() {
    CacheSync.aggiorna(db, lettore(tutto), oggi, adesso)
    val righe = db.leggi(Tabella.WELLNESS, "2026-10-01", "2026-10-02").map { JsonParser.parseString(it).asJsonObject }
    assertEquals(true, PyJson.num(righe[0].get("NoctalixRMSSD"))!!.isInt) // 56
    assertEquals(false, PyJson.num(righe[1].get("NoctalixRMSSD"))!!.isInt) // 55.8
  }

  @Test
  fun unaLetturaFallitaNonToccaLaFotografiaPrecedente() {
    CacheSync.aggiorna(db, lettore(tutto), oggi, adesso)
    val soloDue = mapOf("wellness" to "[]", "events" to "[]") // attivita' offline
    val errore = CacheSync.aggiorna(db, lettore(soloDue), oggi, adesso + 1)
    assertNotNull(errore)
    assertTrue(errore!!.startsWith("activities"))
    assertEquals(3, db.leggi(Tabella.WELLNESS, "2026-01-01", "2026-12-31").size)
    assertEquals(2, db.leggi(Tabella.EVENTI, "2026-01-01", "2026-12-31").size)
    assertEquals(adesso.toString(), db.meta(CacheSync.META_ULTIMO_OK))
    assertEquals(errore, db.meta(CacheSync.META_ULTIMO_ERRORE))
  }

  @Test
  fun rispostaNonJsonEUnErroreNonUnaCacheVuota() {
    CacheSync.aggiorna(db, lettore(tutto), oggi, adesso)
    val rotta = tutto + ("events" to "<html>manutenzione</html>")
    assertNotNull(CacheSync.aggiorna(db, lettore(rotta), oggi, adesso + 1))
    assertEquals(2, db.leggi(Tabella.EVENTI, "2026-01-01", "2026-12-31").size)
  }

  @Test
  fun sedutaToltaDalCoachSparisceDallaCache() {
    CacheSync.aggiorna(db, lettore(tutto), oggi, adesso)
    val senza102 = tutto + ("events" to """[{"id":101,"start_date_local":"2026-10-02T00:00:00"}]""")
    assertNull(CacheSync.aggiorna(db, lettore(senza102), oggi, adesso + 1))
    val rimasti = db.leggi(Tabella.EVENTI, "2026-01-01", "2026-12-31")
    assertEquals(1, rimasti.size)
    assertTrue(rimasti[0].contains("\"id\":101"))
  }

  @Test
  fun noteSpecchioPassataRestaQuandoIlCoachLaCancella() {
    fun specchio(id: Int, giorno: String) =
        """{"id":$id,"start_date_local":"${giorno}T00:00:00","category":"NOTE","name":"Brick",""" +
            """"external_id":"coach:SpecchioGarmin:$id","description":"x"}"""
    val conSpecchi = tutto + ("events" to "[${specchio(1, "2026-10-01")},${specchio(2, "2026-10-04")}]")
    CacheSync.aggiorna(db, lettore(conSpecchi), oggi, adesso)
    // run successivo del coach: specchio di ieri cancellato (seduta svolta), quello futuro tolto
    CacheSync.aggiorna(db, lettore(tutto + ("events" to "[]")), oggi, adesso + 1)
    val rimasti = db.leggi(Tabella.EVENTI, "2026-01-01", "2026-12-31")
    assertEquals(1, rimasti.size)
    assertTrue("resta lo specchio passato", rimasti[0].contains("coach:SpecchioGarmin:1"))
  }

  @Test
  fun formaDiOggiSenzaIlCaricoSoloPianificato() {
    CacheSync.aggiorna(db, lettore(tutto), oggi, adesso)
    val f = CacheRepo(db).forma(oggi)
    // oggi = ieri + un giorno di EWMA a carico zero (i 40 pianificati non contano)
    assertEquals(Py.round(atl1 * FormaCalc.EWMA_ATL, 1), f.oggi!!.atl, 0.0)
    assertEquals(Py.round(ctl1 * FormaCalc.EWMA_CTL, 1), f.oggi.ctl, 0.0)
    assertEquals(Py.round(atl1, 1), f.ieri!!.atl, 0.0)
    assertEquals(3, f.serie.size)
  }

  @Test
  fun dueNottiSonoCalibrazione() {
    CacheSync.aggiorna(db, lettore(tutto), oggi, adesso)
    assertEquals(Prontezza.Calibrazione(2), CacheRepo(db).prontezza(oggi))
  }
}
