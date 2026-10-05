package com.wboelens.polarrecorder.biosleep.intervals

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

/**
 * Impostazioni Intervals.icu salvate nella memoria privata dell'app.
 * Token OAuth e API key sono cifrati con una chiave dell'Android Keystore (non lascia mai il
 * telefono) e il file e' escluso dal backup su cloud (res/xml/backup_rules.xml).
 * Il token del collegamento ha la precedenza; la API key resta come opzione avanzata.
 */
class IntervalsSettings(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  // --- Collegamento OAuth ---------------------------------------------------------------------

  /** Token OAuth ("" se non collegato). Non scade: resta finche' non si scollega. */
  var token: String
    get() = prefs.getString(KEY_TOKEN, null)?.let { Cifratura.decifra(it) } ?: ""
    set(value) {
      val v = value.trim()
      prefs.edit().apply { if (v.isEmpty()) remove(KEY_TOKEN) else putString(KEY_TOKEN, Cifratura.cifra(v)) }.apply()
    }

  /** Id atleta restituito dal collegamento (solo per mostrarlo: le chiamate usano "0"). */
  var atletaCollegato: String
    get() = prefs.getString(KEY_ATLETA_OAUTH, "") ?: ""
    set(value) {
      prefs.edit().putString(KEY_ATLETA_OAUTH, value.trim()).apply()
    }

  var scopeCollegato: String
    get() = prefs.getString(KEY_SCOPE, "") ?: ""
    set(value) {
      prefs.edit().putString(KEY_SCOPE, value.trim()).apply()
    }

  val collegato: Boolean
    get() = prefs.contains(KEY_TOKEN)

  fun scollegaLocale() {
    prefs.edit().remove(KEY_TOKEN).remove(KEY_ATLETA_OAUTH).remove(KEY_SCOPE).apply()
  }

  // --- API key (opzione avanzata) -------------------------------------------------------------

  var apiKey: String
    get() {
      prefs.getString(KEY_API_CIFRATA, null)?.let { return Cifratura.decifra(it) ?: "" }
      val inChiaro = prefs.getString(KEY_API, null) ?: return ""
      // migrazione dalle versioni con la API key in chiaro
      prefs.edit().putString(KEY_API_CIFRATA, Cifratura.cifra(inChiaro)).remove(KEY_API).apply()
      return inChiaro
    }
    set(value) {
      val v = value.trim()
      prefs.edit().apply {
        remove(KEY_API)
        if (v.isEmpty()) remove(KEY_API_CIFRATA) else putString(KEY_API_CIFRATA, Cifratura.cifra(v))
      }.apply()
    }

  /** Id atleta per la API key (es. i123456); "0" = l'atleta della API key. */
  var athleteId: String
    get() = prefs.getString(KEY_ATLETA, "0")?.ifBlank { "0" } ?: "0"
    set(value) {
      prefs.edit().putString(KEY_ATLETA, value.trim()).apply()
    }

  var autoUpload: Boolean
    get() = prefs.getBoolean(KEY_AUTO, true)
    set(value) {
      prefs.edit().putBoolean(KEY_AUTO, value).apply()
    }

  /** Le credenziali da usare: il token se c'e', altrimenti la API key; null se nessuna. */
  val credenziali: Credenziali?
    get() {
      token.takeIf { it.isNotEmpty() }?.let { return Credenziali.Token(it) }
      return apiKey.takeIf { it.isNotEmpty() }?.let { Credenziali.Chiave(it, athleteId) }
    }

  val isConfigured: Boolean
    get() = collegato || prefs.contains(KEY_API_CIFRATA) || !prefs.getString(KEY_API, null).isNullOrBlank()

  companion object {
    const val PREFS_NAME = "biosleep_intervals"
    private const val KEY_API = "api_key" // vecchio formato in chiaro, solo per la migrazione
    private const val KEY_API_CIFRATA = "api_key_cifrata"
    private const val KEY_ATLETA = "athlete_id"
    private const val KEY_AUTO = "auto_upload"
    private const val KEY_TOKEN = "oauth_token_cifrato"
    private const val KEY_ATLETA_OAUTH = "oauth_athlete_id"
    private const val KEY_SCOPE = "oauth_scope"
  }
}

/** AES-256-GCM con chiave nell'Android Keystore. Formato salvato: base64(iv):base64(cifrato). */
internal object Cifratura {
  private const val ALIAS = "biosleep_intervals_key"
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

  /** null se non decifrabile (chiave del Keystore persa): va ricollegato o reinserito. */
  fun decifra(salvato: String): String? =
      try {
        val parti = salvato.split(":")
        val c = Cipher.getInstance(TRASFORMAZIONE)
        c.init(Cipher.DECRYPT_MODE, chiave(), GCMParameterSpec(128, Base64.decode(parti[0], Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(parti[1], Base64.NO_WRAP)), Charsets.UTF_8)
      } catch (e: GeneralSecurityException) {
        null
      } catch (e: IllegalArgumentException) {
        null
      } catch (e: IndexOutOfBoundsException) {
        null
      }
}
