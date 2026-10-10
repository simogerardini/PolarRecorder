package com.wboelens.polarrecorder.biosleep.ponte

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Risposte del ponte simulate: il ponte vero va online al Passo 3 della Parte 6. */
class ClientePonteTest {
  private data class Chiamata(val metodo: String, val url: String, val h: Map<String, String>, val corpo: String?)

  private class Finto(var risposta: (Chiamata) -> RispostaHttp) : Trasporto {
    val chiamate = mutableListOf<Chiamata>()

    override fun chiama(metodo: String, url: String, intestazioni: Map<String, String>, corpo: String?): RispostaHttp {
      val c = Chiamata(metodo, url, intestazioni, corpo)
      chiamate += c
      return risposta(c)
    }
  }

  private val ok = { _: Any -> RispostaHttp(204, "") }

  @Test
  fun collegamentoInDueTempi() {
    val t = Finto { RispostaHttp(201, "") }
    val p = ClientePonte("https://ponte", t)
    assertEquals(EsitoPonte.Ok(Unit), p.inviaChiaveTelefono("sess 1", "PUB"))
    assertEquals(EsitoPonte.Ok(Unit), p.creaCollegamento("sess 1", "ID", "HT", "HB"))
    assertEquals(EsitoPonte.Ok(Unit), p.inviaRisposta("sess 1", "BUSTA"))
    val (a, b, c) = t.chiamate
    assertEquals(Chiamata("POST", "https://ponte/sessioni/sess+1/telefono", a.h, """{"pub_telefono":"PUB"}"""), a)
    assertEquals("""{"sessione":"sess 1","id":"ID","hash_telefono":"HT","hash_browser":"HB"}""", b.corpo)
    assertEquals("https://ponte/collegamenti", b.url)
    assertEquals("""{"busta":"BUSTA"}""", c.corpo)
    assertTrue(a.h["Content-Type"]!!.startsWith("application/json"))
  }

  @Test
  fun copiaConSegretoDelTelefono() {
    val t = Finto(ok)
    ClientePonte("https://ponte", t).caricaCopia("col_1", "SEGRETO", "BUSTA")
    val c = t.chiamate.single()
    assertEquals("PUT", c.metodo)
    assertEquals("https://ponte/c/col_1/snapshot", c.url)
    assertEquals("Bearer SEGRETO", c.h["Authorization"])
    assertEquals("BUSTA", c.corpo)
  }

  @Test
  fun comandiComeIlPonte() {
    // Formato reale del ponte v1: [{seq, busta}] in ordine di seq
    val p = ClientePonte("https://ponte", Finto { RispostaHttp(200, """[{"seq":12,"busta":"B2"},{"seq":7,"busta":"B1"},{"busta":"senza seq"}]""") })
    assertEquals(EsitoPonte.Ok(listOf(7L to "B1", 12L to "B2")), p.comandi("col_1", "S"))
    val vuoto = ClientePonte("https://ponte", Finto { RispostaHttp(200, "[]") })
    assertEquals(EsitoPonte.Ok(emptyList<Pair<Long, String>>()), vuoto.comandi("col_1", "S"))
    val t = Finto(ok)
    ClientePonte("https://ponte", t).cancellaComandi("col_1", "S", 12L)
    assertEquals("https://ponte/c/col_1/comandi?fino=12", t.chiamate.single().url)
    assertEquals("DELETE", t.chiamate.single().metodo)
  }

  @Test
  fun erroriERete() {
    assertEquals(EsitoPonte.NonTrovato, ClientePonte("x", Finto { RispostaHttp(404, "") }).caricaCopia("a", "b", "c"))
    val e = ClientePonte("x", Finto { RispostaHttp(500, "guasto") }).caricaCopia("a", "b", "c")
    assertEquals(EsitoPonte.Errore(500, "HTTP 500: guasto"), e)
    val rete = ClientePonte("x", Finto { throw IOException("offline") }).caricaCopia("a", "b", "c")
    assertEquals(EsitoPonte.Errore(null, "rete: offline"), rete)
    val rotta = ClientePonte("x", Finto { RispostaHttp(200, "[non json") }).comandi("a", "b")
    assertTrue(rotta is EsitoPonte.Errore)
  }

  @Test
  fun scollegaAncheSeGiaEliminato() {
    assertEquals(EsitoPonte.Ok(Unit), ClientePonte("x", Finto { RispostaHttp(404, "") }).scollega("a", "b"))
    val t = Finto(ok)
    ClientePonte("https://ponte", t).scollega("col_1", "S")
    assertEquals(Chiamata("DELETE", "https://ponte/c/col_1", mapOf("Authorization" to "Bearer S"), null), t.chiamate.single())
  }

  @Test
  fun comandiApplicati() {
    val c = Comandi.leggi("""{"id":"c1","creato":"2026-10-10T07:00:00Z","tipo":"tag_giorno","dati":{"data":"2026-10-10","tag":["alcol"]}}""")!!
    assertEquals("tag_giorno", c.tipo)
    assertEquals(listOf("alcol"), c.dati["tag"])
    assertEquals(EsitoComando("c1", false, "non ancora supportato dall'app"), Comandi.applica(c) { null })
    assertEquals(EsitoComando("c1", true), Comandi.applica(c) { EsitoComando(it.id, true) })
    assertEquals(EsitoComando("c2", true), Comandi.applica(Comando("c2", "aggiorna_ora", emptyMap())) { null })
    assertEquals(EsitoComando("c3", false, "comando sconosciuto: boh"), Comandi.applica(Comando("c3", "boh", emptyMap())) { null })
    assertEquals(null, Comandi.leggi("""{"tipo":"aggiorna_ora"}"""))
  }
}
