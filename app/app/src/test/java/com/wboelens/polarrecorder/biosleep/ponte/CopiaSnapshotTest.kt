package com.wboelens.polarrecorder.biosleep.ponte

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CopiaSnapshotTest {
  private val roma = ZoneId.of("Europe/Rome")

  private fun ms(g: Int, h: Int, m: Int) = LocalDateTime.of(2026, 10, g, h, m).atZone(roma).toInstant().toEpochMilli()

  @Suppress("UNCHECKED_CAST")
  private fun leggi(s: String) = Json.leggi(s) as Map<String, Any?>

  private fun notteDiProva(): NotteSorgente {
    val inizio = ms(9, 23, 0)
    return NotteSorgente(
        inizioMs = inizio, fineMs = ms(10, 6, 50),
        addormentamentoMs = ms(9, 23, 20), risveglioMs = ms(10, 6, 40),
        fcMin = 44.04, fc5MinBassi = 46.26, fcMedia = 49.95, rmssd = 55.84, sdnn = 70.0,
        qualitaPct = 96.2, ipnoInizioMs = inizio, ipnogramma = "WWLLDDRRLLWW",
        sonnoMin = 400, profondoMin = 80, leggeroMin = 220, remMin = 90, vegliaMin = 10,
        metodoFasi = "HRV+ACC+RESP", sensore = "RR",
        finestre = listOf(
            FinestraSorgente(inizio, 52.0, 50.0, 60.0, 20.0, 99.0),
            FinestraSorgente(inizio + 300_000, 50.0, 55.0, 65.0, 25.0, 100.0),
            // buco di una finestra
            FinestraSorgente(inizio + 900_000, 48.0, 60.0, 70.0, 30.0, 98.0)),
        // buco dalla 3a alla 5a epoca (da 60 s a 150 s)
        buchi = listOf(inizio + 60_000 to inizio + 150_000),
        interruzioni = listOf(2 to "app chiusa dal sistema"),
    )
  }

  private fun dati(notti: List<NotteSorgente> = listOf(notteDiProva())) =
      DatiCopia(generatoMs = ms(10, 7, 0), zona = roma, versioneApp = "0.0.0", lingua = "it",
          atletaId = "i000000", notti = notti)

  @Test
  fun copiaVuotaUgualeAlVettore() {
    val v = Json.leggi(javaClass.classLoader!!.getResource("ponte/vettori_prova.json")!!.readText()) as Map<*, *>
    assertEquals(v["snapshot"], Json.leggi(CopiaSnapshot.costruisci(dati(emptyList()))))
  }

  @Test
  fun schemaCampiObbligatori() {
    val c = leggi(CopiaSnapshot.costruisci(dati()))
    for (k in listOf("v", "generato", "app", "atleta", "notti", "riepiloghi")) assertTrue("manca $k", c.containsKey(k))
    assertEquals(1.0, c["v"])
    assertEquals("2026-10-10T07:00:00+02:00", c["generato"])
    val app = c["app"] as Map<*, *>
    assertEquals(setOf("versione", "lingua", "fuso"), app.keys)
    assertEquals("Europe/Rome", app["fuso"])
    assertEquals("i000000", (c["atleta"] as Map<*, *>)["intervals_id"])
    assertTrue(c["notti"] is List<*>)
    assertTrue(c["riepiloghi"] is List<*>)
    // I campi della Parte 3 mancano finche' non arrivano (assenti, non inventati)
    for (k in listOf("profilo", "prontezza", "tag_giorni", "eta", "sonno", "comandi_applicati")) assertFalse(c.containsKey(k))
  }

  @Test
  fun campiDellaNotte() {
    val n = (leggi(CopiaSnapshot.costruisci(dati()))["notti"] as List<*>)[0] as Map<*, *>
    assertEquals("2026-10-10", n["data"])
    assertEquals("2026-10-09T23:00:00+02:00", n["inizio"])
    assertEquals("2026-10-09T23:20:00+02:00", n["addormentamento"])
    assertEquals(7.83, n["ore_registrazione"])
    assertEquals(6.67, n["ore_sonno"])
    assertEquals(46.3, n["fc_5min_bassi"]) // NoctalixRHR, non la "FC a riposo" del coach
    assertEquals(50.0, n["fc_media"])
    assertNull(n["fc_riposo"])
    assertEquals(true, n["valida"])
    assertEquals(mapOf("profondo" to 80.0, "leggero" to 220.0, "rem" to 90.0, "veglia" to 10.0), n["fasi_min"])
    assertEquals(listOf(mapOf("minuti" to 2.0, "causa" to "app chiusa dal sistema")), n["interruzioni"])
    assertEquals("HRV+ACC+RESP", n["metodo_fasi"])
    assertFalse(n.containsKey("punteggio_sonno")) // Parte 3
  }

  @Test
  fun ipnogrammaConIBuchi() {
    val n = (leggi(CopiaSnapshot.costruisci(dati()))["notti"] as List<*>)[0] as Map<*, *>
    val ip = n["ipnogramma"] as Map<*, *>
    assertEquals(30.0, ip["passo_s"])
    assertEquals("2026-10-09T23:00:00+02:00", ip["inizio"])
    assertEquals("WW---DRRLLWW", ip["fasi"]) // centri delle epoche 2, 3, 4 (75, 105, 135 s) nel buco [60 s, 150 s)
  }

  @Test
  fun serieAPassoFissoConNullNeiBuchi() {
    val n = (leggi(CopiaSnapshot.costruisci(dati()))["notti"] as List<*>)[0] as Map<*, *>
    val s = n["serie"] as Map<*, *>
    assertEquals(300.0, s["passo_s"])
    assertEquals(listOf(52.0, 50.0, null, 48.0), s["fc"])
    assertEquals(listOf(50.0, 55.0, null, 60.0), s["rmssd"])
  }

  @Test
  fun contributiDelPunteggioCopiatiSenzaRicalcoli() {
    val notte = notteDiProva().copy(punteggioSonno = 82,
        punteggioContributiJson = """[{"nome":"durata","punti":78,"peso":0.35},{"nome":"profondo","punti":90,"peso":0.2}]""")
    val n = (leggi(CopiaSnapshot.costruisci(dati(listOf(notte))))["notti"] as List<*>)[0] as Map<*, *>
    assertEquals(82.0, n["punteggio_sonno"])
    assertEquals(listOf(mapOf("nome" to "durata", "punti" to 78.0, "peso" to 0.35), mapOf("nome" to "profondo", "punti" to 90.0, "peso" to 0.2)),
        n["punteggio_contributi"])
    // Solo le fasce, senza blocco della Parte 3
    assertEquals(mapOf("fascia_letto" to mapOf("da" to "22:30", "a" to "23:40")),
        CopiaSnapshot.sonnoUnito(null, SonnoSorgente(lettoDa = "22:30", lettoA = "23:40")))
    assertNull(CopiaSnapshot.sonnoUnito(null, null))
  }

  @Test
  fun validaComeIlCoach() {
    assertFalse(CopiaSnapshot.valida(notteDiProva().copy(qualitaPct = 79.9)))
    assertFalse(CopiaSnapshot.valida(notteDiProva().copy(sonnoMin = null)))
    assertFalse(CopiaSnapshot.valida(notteDiProva().copy(rmssd = null)))
    assertTrue(CopiaSnapshot.valida(notteDiProva().copy(qualitaPct = 80.0)))
  }

  @Test
  fun partiDellaParte3EComandi() {
    val d = dati().copy(
        riepiloghiJson = listOf("""{"v":1,"data":"2026-10-10","messaggi":[{"tipo":"motivo","codice":"x","valori":{},"testo":"t","testo_locale":"t"}]}"""),
        profiloJson = """{"fc_max":186}""",
        eta = EtaSorgente("2026-10-10", 34.24, 41.0, 31.0, 37.5, null, false, listOf(ComponenteEta("vo2max", -3.21, 53.2, "ml/kg/min"))),
        sonno = SonnoSorgente(lettoDa = "22:45", lettoA = "23:50", inizioMattino = "05:50"),
        sonnoJson = """{"fabbisogno_h":7.6,"fabbisogno_appreso":false,"deficit_h":1.2,"deficit_livello":"basso","notti":9,"cronotipo":null}""",
        comandiApplicati = listOf(EsitoComando("c1", true), EsitoComando("c2", false, "comando sconosciuto: x")))
    val c = leggi(CopiaSnapshot.costruisci(d))
    assertEquals(186.0, (c["profilo"] as Map<*, *>)["fc_max"])
    assertEquals("2026-10-10", ((c["riepiloghi"] as List<*>)[0] as Map<*, *>)["data"])
    val e = c["eta"] as Map<*, *>
    assertEquals(34.2, e["eta"])
    assertEquals(listOf(31.0, 37.5), e["intervallo_80"])
    assertEquals(listOf(mapOf("chiave" to "vo2max", "anni" to -3.2, "valore" to 53.2, "unita" to "ml/kg/min")), e["componenti"])
    // Blocco sonno della Parte 3 copiato com'e', piu' le fasce della Parte 1
    val s = c["sonno"] as Map<*, *>
    assertEquals(mapOf("da" to "22:45", "a" to "23:50"), s["fascia_letto"])
    assertEquals("05:50", s["inizio_mattino"])
    assertEquals("basso", s["deficit_livello"])
    assertEquals(9.0, s["notti"])
    assertNull(s["cronotipo"]) // null della Parte 3: campo omesso, equivalente per il contratto
    assertEquals(
        listOf(mapOf("id" to "c1", "esito" to "ok"), mapOf("id" to "c2", "esito" to "rifiutato", "errore" to "comando sconosciuto: x")),
        c["comandi_applicati"])
  }
}
