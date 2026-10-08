package com.wboelens.polarrecorder.biosleep.cervello

import com.google.gson.JsonParser
import com.wboelens.polarrecorder.biosleep.intervals.Credenziali
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Contratto con cervello.esegui_app: il JSON che l'app manda e quello che legge. */
class CervelloTest {
  @Test
  fun configJsonComeDaContratto() {
    val c = ConfigCervello(Credenziali.Chiave("ab\"c\\d", "0"), "/data/user/0/app/files/coach", senzaAttesa = true)
    val o = JsonParser.parseString(c.json()).asJsonObject
    assertEquals("ab\"c\\d", o.get("intervals_api_key")!!.asString, "API key con virgolette: JSON ancora valido")
    assertEquals("0", o.get("intervals_athlete_id")!!.asString)
    assertEquals("/data/user/0/app/files/coach", o.get("cartella")!!.asString)
    assertEquals("auto", o.get("modo")!!.asString)
    assertEquals(false, o.get("dry_run")!!.asBoolean)
    assertEquals(true, o.get("senza_attesa")!!.asBoolean)
    assertTrue(!c.toString().contains("ab\""), "la API key non finisce nei log")
  }

  @Test
  fun configConToken() {
    val o = JsonParser.parseString(ConfigCervello(Credenziali.Token("T0K"), "/c").json()).asJsonObject
    assertEquals("T0K", o.get("intervals_token")!!.asString)
    assertTrue(!o.has("intervals_api_key") && !o.has("intervals_athlete_id"), "con il token niente API key")
    assertTrue(!ConfigCervello(Credenziali.Token("T0K"), "/c").toString().contains("T0K"))
  }

  @Test
  fun esitoPrepara() {
    val e = EsitoPrepara.da("""{"esito":"ok","creati":["NoctalixRMSSD"],"esistenti":["NoctalixSDNN","NoctalixRHR"]}""")
    assertEquals(EsitoPrepara.OK, e.esito)
    assertEquals(1, e.creati.size)
    assertEquals(EsitoPrepara.PERMESSO_MANCANTE, EsitoPrepara.da("""{"esito":"permesso_mancante","creati":[],"esistenti":[]}""").esito)
    assertEquals(EsitoPrepara.ERRORE, EsitoPrepara.da("rotto").esito)
  }

  @Test
  fun risultatoCompleto() {
    val r =
        RisultatoCervello.da(
            """{"esito":"pianificata","notifiche":["🔵 Piano pronto",""],"riepilogo_file":"/x/riepilogo_2026-10-05.json","log_file":"/x/log/coach_1.txt"}""")
    assertEquals(RisultatoCervello.PIANIFICATA, r.esito)
    assertEquals(listOf("🔵 Piano pronto"), r.notifiche, "le notifiche vuote si scartano")
    assertEquals("/x/riepilogo_2026-10-05.json", r.riepilogoFile)
    assertNull(r.errore)
  }

  @Test
  fun erroreEJsonRotto() {
    val e = RisultatoCervello.da("""{"esito":"errore","notifiche":[],"riepilogo_file":null,"log_file":"/x/l.txt","errore":"KeyError: 'id'"}""")
    assertEquals(RisultatoCervello.ERRORE, e.esito)
    assertNull(e.riepilogoFile)
    assertEquals("KeyError: 'id'", e.errore)
    val rotto = RisultatoCervello.da("Traceback (most recent call last)")
    assertEquals(RisultatoCervello.ERRORE, rotto.esito)
    assertTrue(rotto.errore!!.startsWith("Risposta del cervello illeggibile"))
  }
}
