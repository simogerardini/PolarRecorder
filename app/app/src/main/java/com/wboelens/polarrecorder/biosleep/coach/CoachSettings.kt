package com.wboelens.polarrecorder.biosleep.coach

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Impostazioni e stato dell'avvio del coach su GitHub, nel file biosleep_coach.xml.
 * Il token e' cifrato con una chiave dell'Android Keystore, che non esce mai dal telefono; il file
 * e' comunque escluso dal backup (res/xml): ripristinato su un altro telefono sarebbe illeggibile.
 */
class CoachSettings(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  var repo: String
    get() = prefs.getString(K_REPO, REPO_PREDEFINITO) ?: REPO_PREDEFINITO
    set(value) {
      prefs.edit().putString(K_REPO, value.trim()).apply()
      cambiato()
    }

  /** null se assente o non decifrabile (chiave del Keystore persa: va reinserito). */
  var token: String?
    get() = prefs.getString(K_TOKEN, null)?.let { CifraturaToken.decifra(it) }
    set(value) {
      val t = value?.trim().orEmpty()
      prefs.edit().apply {
        if (t.isEmpty()) remove(K_TOKEN) else putString(K_TOKEN, CifraturaToken.cifra(t))
        // nuovo token: gli avvisi legati al vecchio non valgono piu'
        remove(K_PROBLEMA)
        remove(K_SCADENZA)
      }.apply()
      cambiato()
    }

  val haToken: Boolean
    get() = prefs.contains(K_TOKEN)

  // --- Stato dell'ultimo avvio ---------------------------------------------------------------------

  /** Problema da mostrare finche' non e' risolto (TOKEN, CONFIGURAZIONE) o per il giorno (RETE). */
  val problema: Problema?
    get() = prefs.getString(K_PROBLEMA, null)?.let { runCatching { Problema.valueOf(it) }.getOrNull() }

  val messaggio: String?
    get() = prefs.getString(K_MESSAGGIO, null)

  /** Data (YYYY-MM-DD) dell'ultimo esito. */
  val dataEsito: String?
    get() = prefs.getString(K_DATA, null)

  val scadenzaToken: String?
    get() = prefs.getString(K_SCADENZA, null)

  fun segnaAvviato(data: String) = salvaEsito(data, null, "Coach avviato dall'app")

  fun segnaInAttesa(data: String, messaggio: String) {
    prefs.edit().putString(K_DATA, data).putString(K_MESSAGGIO, messaggio).apply()
    cambiato()
  }

  fun segnaErrore(data: String, e: Esito.Errore) = salvaEsito(data, e.problema, e.messaggio)

  fun registraScadenza(valoreHeader: String?) {
    val d = GitHubDispatch.scadenza(valoreHeader) ?: return
    if (prefs.getString(K_SCADENZA, null) != d.toString()) {
      prefs.edit().putString(K_SCADENZA, d.toString()).apply()
      cambiato()
    }
  }

  private fun salvaEsito(data: String, p: Problema?, messaggio: String) {
    prefs.edit().apply {
      putString(K_DATA, data)
      putString(K_MESSAGGIO, messaggio)
      if (p == null) remove(K_PROBLEMA) else putString(K_PROBLEMA, p.name)
    }.apply()
    cambiato()
  }

  companion object {
    const val PREFS_NAME = "biosleep_coach"
    const val REPO_PREDEFINITO = "simogerardini/stryd-coach"
    private const val K_REPO = "repo"
    private const val K_TOKEN = "token_cifrato"
    private const val K_PROBLEMA = "problema"
    private const val K_MESSAGGIO = "messaggio"
    private const val K_DATA = "data_esito"
    private const val K_SCADENZA = "scadenza_token"

    private val _versione = MutableStateFlow(0)

    /** Cresce a ogni cambio: le schermate la osservano e rileggono. */
    val versione: StateFlow<Int> = _versione.asStateFlow()

    private fun cambiato() = _versione.update { it + 1 }
  }
}

/** AES-256-GCM con chiave nell'Android Keystore. Formato salvato: base64(iv):base64(cifrato). */
internal object CifraturaToken {
  private const val ALIAS = "biosleep_github_token"
  private const val KEYSTORE = "AndroidKeyStore"
  private const val TRASFORMAZIONE = "AES/GCM/NoPadding"

  private fun chiave(): SecretKey {
    val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
    val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
    gen.init(
        KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
    return gen.generateKey()
  }

  fun cifra(testo: String): String {
    val c = Cipher.getInstance(TRASFORMAZIONE)
    c.init(Cipher.ENCRYPT_MODE, chiave())
    val dati = c.doFinal(testo.toByteArray(Charsets.UTF_8))
    return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(dati, Base64.NO_WRAP)
  }

  fun decifra(salvato: String): String? =
      try {
        val (iv, dati) = salvato.split(":").let { it[0] to it[1] }
        val c = Cipher.getInstance(TRASFORMAZIONE)
        c.init(Cipher.DECRYPT_MODE, chiave(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(dati, Base64.NO_WRAP)), Charsets.UTF_8)
      } catch (e: GeneralSecurityException) {
        null
      } catch (e: IllegalArgumentException) {
        null
      } catch (e: IndexOutOfBoundsException) {
        null
      }
}
