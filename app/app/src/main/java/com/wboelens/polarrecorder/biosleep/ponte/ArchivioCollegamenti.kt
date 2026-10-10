package com.wboelens.polarrecorder.biosleep.ponte

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Un browser collegato. I segreti restano solo qui, cifrati con l'Android Keystore. */
data class Collegamento(
    val id: String,
    val segretoTelefono: String,
    val chiaveDati: String,
    val creatoMs: Long,
    val ultimaCopiaMs: Long? = null,
    val byteCopia: Int? = null,
)

/** Collegamenti attivi ed esiti dei comandi da riportare nella copia successiva. */
class ArchivioCollegamenti(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  companion object {
    private const val PREFS = "noctalix_ponte"
    private const val K_COLLEGAMENTI = "collegamenti"
    private const val K_ESITI = "esiti"
    private const val ALIAS = "noctalix_ponte_key"
    private const val IV = 12

    private val _stato = MutableStateFlow<List<Collegamento>>(emptyList())
    /** Per la schermata: si aggiorna a ogni modifica. */
    val stato: StateFlow<List<Collegamento>> = _stato.asStateFlow()
  }

  init {
    _stato.value = elenco()
  }

  @Synchronized
  fun elenco(): List<Collegamento> {
    val cifrato = prefs.getString(K_COLLEGAMENTI, null) ?: return emptyList()
    val testo = runCatching { decifra(cifrato) }.getOrNull() ?: return emptyList()
    return (Json.leggi(testo) as? List<*>).orEmpty().mapNotNull { x ->
      val m = x as? Map<*, *> ?: return@mapNotNull null
      Collegamento(
          id = m["id"] as? String ?: return@mapNotNull null,
          segretoTelefono = m["segreto_telefono"] as? String ?: return@mapNotNull null,
          chiaveDati = m["chiave_dati"] as? String ?: return@mapNotNull null,
          creatoMs = (m["creato"] as? Double)?.toLong() ?: 0L,
          ultimaCopiaMs = (m["ultima_copia"] as? Double)?.toLong(),
          byteCopia = (m["byte_copia"] as? Double)?.toInt())
    }
  }

  @Synchronized
  private fun salva(l: List<Collegamento>) {
    val json = Json.scrivi(l.map {
      linkedMapOf("id" to it.id, "segreto_telefono" to it.segretoTelefono, "chiave_dati" to it.chiaveDati,
          "creato" to it.creatoMs, "ultima_copia" to it.ultimaCopiaMs, "byte_copia" to it.byteCopia)
    })
    prefs.edit().putString(K_COLLEGAMENTI, cifra(json)).commit()
    _stato.value = l
  }

  fun aggiungi(c: Collegamento) = salva(elenco().filter { it.id != c.id } + c)

  fun rimuovi(id: String) = salva(elenco().filter { it.id != id })

  fun copiaInviata(id: String, ms: Long, byte: Int) =
      salva(elenco().map { if (it.id == id) it.copy(ultimaCopiaMs = ms, byteCopia = byte) else it })

  @Synchronized
  fun esitiInAttesa(): List<EsitoComando> =
      (runCatching { Json.leggi(prefs.getString(K_ESITI, "[]") ?: "[]") }.getOrNull() as? List<*>).orEmpty().mapNotNull { x ->
        val m = x as? Map<*, *> ?: return@mapNotNull null
        EsitoComando(m["id"] as? String ?: return@mapNotNull null, m["ok"] == true, m["errore"] as? String)
      }

  @Synchronized
  fun aggiungiEsiti(e: List<EsitoComando>) {
    if (e.isEmpty()) return
    val tutti = (esitiInAttesa() + e).takeLast(100)
    prefs.edit().putString(K_ESITI, Json.scrivi(tutti.map { linkedMapOf("id" to it.id, "ok" to it.ok, "errore" to it.errore) })).commit()
  }

  /** Dopo una copia arrivata a tutti i browser: gli esiti sono stati riportati. */
  @Synchronized
  fun togliEsiti(riportati: List<EsitoComando>) {
    val ids = riportati.map { it.id }.toSet()
    val restano = esitiInAttesa().filter { it.id !in ids }
    prefs.edit().putString(K_ESITI, Json.scrivi(restano.map { linkedMapOf("id" to it.id, "ok" to it.ok, "errore" to it.errore) })).commit()
  }

  // ── Keystore ────────────────────────────────────────────────────────────────
  private fun chiave(): SecretKey {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
    val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    g.init(
        KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
    return g.generateKey()
  }

  private fun cifra(testo: String): String {
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, chiave())
    return CriptoPonte.b64(c.iv + c.doFinal(testo.toByteArray(Charsets.UTF_8)))
  }

  private fun decifra(s: String): String {
    val b = CriptoPonte.da64(s)
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, chiave(), GCMParameterSpec(128, b, 0, IV))
    return String(c.doFinal(b, IV, b.size - IV), Charsets.UTF_8)
  }
}
