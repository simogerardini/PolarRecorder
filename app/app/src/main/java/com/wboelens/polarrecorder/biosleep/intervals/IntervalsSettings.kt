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
 * La API key e' cifrata con una chiave dell'Android Keystore (non lascia mai il telefono) e il
 * file e' escluso dal backup su cloud (res/xml/backup_rules.xml e data_extraction_rules.xml).
 * Una API key salvata in chiaro dalle versioni precedenti viene cifrata alla prima lettura.
 */
class IntervalsSettings(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  var apiKey: String
    get() {
      prefs.getString(KEY_API_CIFRATA, null)?.let { return Cifratura.decifra(it) ?: "" }
      val inChiaro = prefs.getString(KEY_API, null) ?: return ""
      // migrazione: cifra e cancella la copia in chiaro
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

  /** Id atleta di Intervals.icu (es. i123456); "0" = l'atleta della API key. */
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

  val isConfigured: Boolean
    get() = prefs.contains(KEY_API_CIFRATA) || !prefs.getString(KEY_API, null).isNullOrBlank()

  companion object {
    const val PREFS_NAME = "biosleep_intervals"
    private const val KEY_API = "api_key" // vecchio formato in chiaro, solo per la migrazione
    private const val KEY_API_CIFRATA = "api_key_cifrata"
    private const val KEY_ATLETA = "athlete_id"
    private const val KEY_AUTO = "auto_upload"
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

  /** null se non decifrabile (chiave del Keystore persa): la API key va reinserita. */
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
