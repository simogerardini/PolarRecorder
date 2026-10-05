package com.wboelens.polarrecorder.biosleep.riepilogo

import com.google.gson.JsonObject
import com.wboelens.polarrecorder.biosleep.training.Allenamenti
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * riepilogo_<data>.json del cervello (schema v1). I due file di prova sono stati prodotti dal
 * codice del cervello stesso (cervello._riepilogo su un piano della suite di coach_settimanale).
 */
class RiepilogoTest {
  private fun risorsa(nome: String) =
      javaClass.getResource("/biosleep/$nome")?.readText() ?: error("Manca src/test/resources/biosleep/$nome")

  @Test
  fun pianoSettimanaleVero() {
    val r = RiepilogoParser.leggi(risorsa("riepilogo_settimanale.json"))!!
    assertEquals(1, r.versione)
    assertEquals(true, r.settimanale)
    assertEquals("build", r.fase)
    assertEquals("verde", r.banda)
    assertEquals(11.0, r.oreTarget)
    assertEquals(12, r.sedute.size)
    assertEquals(r.sedute.map { it.data }.sorted(), r.sedute.map { it.data }, "sedute in ordine di giorno")
    val prima = r.sedute.first()
    assertEquals("Corsa di supporto 50min", prima.nome)
    assertEquals(50, prima.durataMin)
    assertEquals(false, prima.qualita)
    // descrizione senza [[tag]] ne' "intensity=..."
    assertEquals("5m Z1 HR\n40m Z2 HR\n5m Z1 HR (post: stretching)", prima.descrizione)
    // alta intensita' dal campo strutturato "intensita" del cervello
    val a = r.altaIntensita!!
    assertEquals(18, a.minuti)
    assertEquals(525, a.suMinuti)
    assertEquals(10.0, a.tettoPct)
    assertEquals("preparazione gara: al massimo il 10% del tempo di bici e corsa sopra la soglia", a.regola)
    assertEquals(false, a.sopraTetto)
  }

  @Test
  fun giornalieroSenzaSedute() {
    // esito "niente": nessuna seduta da rimodulare, il riepilogo c'e' comunque
    val r = RiepilogoParser.leggi(risorsa("riepilogo_giornaliero.json"))!!
    assertEquals(false, r.settimanale)
    assertEquals("niente", r.esito)
    assertEquals(0, r.sedute.size)
    assertNull(r.altaIntensita)
  }

  @Test
  fun campoIntensitaStrutturatoHaPrecedenza() {
    val j = JsonObject()
    j.addProperty("v", 1)
    j.addProperty("data", "2026-10-05")
    j.addProperty("tipo", "settimanale")
    j.addProperty("testo", "Alta intensita': 18' su 525' bici+corsa (3.4%, tetto 10%)")
    j.add("intensita", JsonObject().apply {
      addProperty("alta_min", 40)
      addProperty("base_min", 500)
      addProperty("tetto_pct", 10)
    })
    val a = RiepilogoParser.leggi(j)!!.altaIntensita!!
    assertEquals(40, a.minuti)
    assertEquals(8.0, a.pct)
  }

  @Test
  fun blocchiCompletiSeIlCervelloLiScrive() {
    val j = com.google.gson.JsonParser.parseString(
        """{"v":1,"data":"2026-10-05","tipo":"giornaliero","decisione":{"codice":"RIDUCI","etichetta":"🟡 RIDUCI","motivo":"banda gialla"},
           "biometria":{"ok":true,"banda":"giallo","hrv_7gg":52.1,"range_ms":[55.0,61.0],"fc_7gg":45.0},
           "forma":{"ctl":46.2,"atl":48.9,"tsb":-2.7,"fascia":[-12.7,-10.0]},
           "volume":{"target_h":9.1,"fatte_h":2.0},"carico":{"fatti":120,"tetto":420,"calendario":380},
           "intensita":{"pct_facile":82,"pct_intenso":18},"sedute":[],"testo":"messaggio lungo"}""").asJsonObject
    val r = RiepilogoParser.leggi(j)!!
    assertEquals("🟡 RIDUCI", r.decisione.etichetta)
    assertEquals(55.0 to 61.0, r.biometria!!.rangeMs)
    assertEquals(-2.7, r.forma!!.tsb)
    assertEquals(9.1, r.oreTarget, "ore obiettivo anche dal blocco volume")
    assertEquals(82.0, r.intensita!!.pctFacile)
    assertEquals(420.0, r.carico!!.tetto)
  }

  @Test
  fun tettoDellaFaseDalCoach() {
    // ciclo continuo: 10,5% con tetto 20% non e' un superamento (era il falso rosso col 10% fisso)
    val j = JsonObject()
    j.addProperty("data", "2026-10-05")
    j.addProperty("tipo", "settimanale")
    j.add("intensita", JsonObject().apply {
      addProperty("alta_min", 55)
      addProperty("base_min", 524)
      addProperty("tetto_pct", 20)
      addProperty("regola", "polarizzazione 80/20: al massimo il 20% del tempo di bici e corsa sopra la soglia")
    })
    val a = RiepilogoParser.leggi(j)!!.altaIntensita!!
    assertEquals(20.0, a.tettoPct)
    assertEquals(false, a.sopraTetto)
    assertTrue(a.regola!!.startsWith("polarizzazione 80/20"))
    // oltre il tetto: rosso
    j.getAsJsonObject("intensita").addProperty("alta_min", 110)
    assertEquals(true, RiepilogoParser.leggi(j)!!.altaIntensita!!.sopraTetto)
    // riepilogo vecchio senza tetto_pct ne' riga di testo: nessun tetto inventato
    j.getAsJsonObject("intensita").apply { remove("tetto_pct"); remove("regola") }
    assertNull(RiepilogoParser.leggi(j)!!.altaIntensita!!.tettoPct)
  }

  @Test
  fun senzaDataNonERiepilogo() {
    assertNull(RiepilogoParser.leggi("""{"v":1,"tipo":"settimanale"}"""))
    assertNull(RiepilogoParser.leggi("non json"))
  }

  @Test
  fun noteDelCoachMaiNelCalendario() {
    val o = JsonObject()
    o.addProperty("id", 777)
    o.addProperty("category", "NOTE")
    o.addProperty("start_date_local", "2026-10-05T00:00:00")
    o.addProperty("external_id", "coach:Piano:2026-10-05")
    o.addProperty("name", "Piano")
    val n = Allenamenti.evento(o)!!
    assertEquals(false, n.nota)
    val g = LocalDate.of(2026, 10, 5)
    assertEquals(0, Allenamenti.giorni(listOf(n), emptyList(), g, g, g).single().note.size)
  }
}
