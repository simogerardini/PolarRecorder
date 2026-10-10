package com.wboelens.polarrecorder.biosleep.ponte

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Test obbligatori del protocollo: vettori della Parte 6, RFC 5869, andata e ritorno. */
class CriptoPonteTest {
  @Suppress("UNCHECKED_CAST")
  private val v: Map<String, Any?> =
      Json.leggi(javaClass.classLoader!!.getResource("ponte/vettori_prova.json")!!.readText()) as Map<String, Any?>

  private fun s(k: String) = v[k] as String

  private fun hex(h: String) = ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

  @Test
  fun hkdfRfc5869() {
    // Caso 1
    assertArrayEquals(
        hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
        CriptoPonte.hkdf(hex("0b".repeat(22)), hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42))
    // Caso 3: salt e info vuoti
    assertArrayEquals(
        hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"),
        CriptoPonte.hkdf(hex("0b".repeat(22)), ByteArray(0), ByteArray(0), 42))
  }

  @Test
  fun chiaveECodiceComeIlBrowser() {
    val telefono = CriptoPonte.privDaPkcs8(s("priv_telefono_pkcs8"))
    val chiave = CriptoPonte.chiaveCollegamento(telefono, CriptoPonte.da64(s("pub_browser")), s("sessione"))
    assertEquals(s("chiave_collegamento"), CriptoPonte.b64(chiave))
    // Stessa chiave dal lato del browser
    val browser = CriptoPonte.privDaPkcs8(s("priv_browser_pkcs8"))
    assertArrayEquals(chiave, CriptoPonte.chiaveCollegamento(browser, CriptoPonte.da64(s("pub_telefono")), s("sessione")))
    assertEquals(s("codice_verifica"), CriptoPonte.codiceVerifica(chiave))
  }

  @Test
  fun chiavePubblicaRawAndataERitorno() {
    val raw = CriptoPonte.da64(s("pub_telefono"))
    assertArrayEquals(raw, CriptoPonte.pubRaw(CriptoPonte.pubDaRaw(raw)))
    val nuova = CriptoPonte.pubRaw(CriptoPonte.nuovaCoppia().public)
    assertEquals(65, nuova.size)
    assertEquals(4.toByte(), nuova[0])
  }

  @Test
  fun decifraLeBusteDiProva() {
    val chiave = CriptoPonte.da64(s("chiave_collegamento"))
    val pacchetto = Json.leggi(CriptoPonte.apri(chiave, s("sessione"), "collegamento", s("busta_collegamento")))
    assertEquals(v["pacchetto_collegamento"], pacchetto)

    @Suppress("UNCHECKED_CAST")
    val p = v["pacchetto_collegamento"] as Map<String, Any?>
    val chiaveDati = CriptoPonte.da64(p["chiave_dati"] as String)
    val copia = Json.leggi(CriptoPonte.apri(chiaveDati, p["id_collegamento"] as String, "snapshot", s("busta_snapshot")))
    assertEquals(v["snapshot"], copia)
  }

  @Test
  fun hashDelSegreto() {
    @Suppress("UNCHECKED_CAST")
    val p = v["pacchetto_collegamento"] as Map<String, Any?>
    assertEquals(s("hash_segreto_browser"), CriptoPonte.hashSegreto(p["segreto_browser"] as String))
  }

  @Test
  fun andataERitornoEAadSbagliata() {
    val k = CriptoPonte.byteCasuali(32)
    val json = """{"v":1,"testo":"àèì 中文 \"virgolette\""}"""
    val busta = CriptoPonte.chiudi(k, "col_1", "snapshot", json)
    assertEquals(json, CriptoPonte.apri(k, "col_1", "snapshot", busta))
    assertTrue("nonce nuovo a ogni busta", busta != CriptoPonte.chiudi(k, "col_1", "snapshot", json))
    for ((id, tipo) in listOf("col_2" to "snapshot", "col_1" to "comando")) {
      try {
        CriptoPonte.apri(k, id, tipo, busta)
        fail("AAD sbagliata ($id|$tipo) accettata")
      } catch (e: javax.crypto.AEADBadTagException) {
        // atteso
      }
    }
  }

  @Test
  fun collegamentoCompletoTraDueCoppieNuove() {
    val tel = CriptoPonte.nuovaCoppia()
    val br = CriptoPonte.nuovaCoppia()
    val a = CriptoPonte.chiaveCollegamento(tel.private, CriptoPonte.pubRaw(br.public), "s1")
    val b = CriptoPonte.chiaveCollegamento(br.private, CriptoPonte.pubRaw(tel.public), "s1")
    assertArrayEquals(a, b)
    assertEquals(CriptoPonte.codiceVerifica(a), CriptoPonte.codiceVerifica(b))
  }
}
