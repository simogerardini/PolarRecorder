package com.wboelens.polarrecorder.biosleep.intervals

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Ritorno dal Worker: accettato solo con lo state giusto, entro 10 minuti, con un token. */
class RitornoOAuthTest {
  private val t0 = 1_800_000_000_000L
  private fun p(f: String) = RitornoOAuthParser.parametri(f, null)

  @Test
  fun collegamentoRiuscito() {
    val r = RitornoOAuthParser.valuta(p("token=abc%2B1&scope=ACTIVITY%3AREAD%2CWELLNESS%3AWRITE&athlete_id=i595029&state=N1"), "N1", t0, t0 + 60_000)
    assertEquals(RitornoOAuth.Collegato("abc+1", "i595029", "ACTIVITY:READ,WELLNESS:WRITE"), r)
    assertTrue(!r.toString().contains("abc"), "il token non finisce nei log")
  }

  @Test
  fun rifiuti() {
    fun rif(f: String, nonce: String? = "N1", dopo: Long = 1_000) =
        RitornoOAuthParser.valuta(p(f), nonce, t0, t0 + dopo) is RitornoOAuth.Rifiutato
    assertTrue(rif("token=x&state=ALTRO"), "state diverso")
    assertTrue(rif("token=x&state=N1", nonce = null), "nessun collegamento avviato dall'app")
    assertTrue(rif("token=x&state=N1", dopo = RitornoOAuthParser.VALIDITA_NONCE_MS + 1), "nonce scaduto")
    assertTrue(rif("error=access_denied&state=N1"), "autorizzazione negata")
    assertTrue(rif("state=N1"), "senza token")
  }

  @Test
  fun anchePerQueryMaPrimaIlFrammento() {
    val m = RitornoOAuthParser.parametri("token=dal_frammento&state=N1", "token=dalla_query")
    assertEquals("dal_frammento", m["token"])
  }
}
