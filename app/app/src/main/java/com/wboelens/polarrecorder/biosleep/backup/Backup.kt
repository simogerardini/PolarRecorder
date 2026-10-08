package com.wboelens.polarrecorder.biosleep.backup

import com.wboelens.polarrecorder.BuildConfig
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Formato del file .biosleep: intestazione in chiaro e contenuto (uno zip) cifrato.
 *   "BSB1" | iterazioni (int) | salt (16 byte) | iv (12 byte) | AES-256-GCM(zip)
 * Chiave: PBKDF2-HMAC-SHA256 dalla password, 310.000 iterazioni, salt casuale per file.
 * Il tag GCM fa fallire la decifratura con una password sbagliata o un file alterato.
 */
object CifraturaBackup {
  private val MAGIA = byteArrayOf('B'.code.toByte(), 'S'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
  const val ITERAZIONI = 310_000
  const val PASSWORD_MINIMA = 8
  private const val SALT = 16
  private const val IV = 12

  class PasswordErrata : Exception("password errata o file danneggiato")

  class NonUnBackup : Exception("non è un backup di ${BuildConfig.APP_NAME}")

  private fun chiave(password: CharArray, salt: ByteArray, iterazioni: Int): SecretKeySpec {
    val spec = PBEKeySpec(password, salt, iterazioni, 256)
    try {
      return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    } finally {
      spec.clearPassword()
    }
  }

  /** Scrive l'intestazione e restituisce lo stream in cui scrivere lo zip in chiaro. */
  fun cifra(out: OutputStream, password: CharArray, iterazioni: Int = ITERAZIONI): OutputStream {
    val salt = ByteArray(SALT).also { SecureRandom().nextBytes(it) }
    val iv = ByteArray(IV).also { SecureRandom().nextBytes(it) }
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, chiave(password, salt, iterazioni), GCMParameterSpec(128, iv))
    out.write(MAGIA)
    out.write(byteArrayOf((iterazioni ushr 24).toByte(), (iterazioni ushr 16).toByte(), (iterazioni ushr 8).toByte(), iterazioni.toByte()))
    out.write(salt)
    out.write(iv)
    return CipherOutputStream(out, c)
  }

  /**
   * Legge l'intestazione e restituisce lo zip decifrato. Con GCM il controllo avviene alla fine:
   * una password sbagliata emerge leggendo, come PasswordErrata (da [leggiTutto]).
   */
  fun decifra(input: InputStream, password: CharArray): InputStream {
    val d = DataInputStream(input)
    val magia = ByteArray(4)
    try {
      d.readFully(magia)
    } catch (e: IOException) {
      throw NonUnBackup()
    }
    if (!magia.contentEquals(MAGIA)) throw NonUnBackup()
    val iterazioni = d.readInt()
    if (iterazioni !in 100_000..10_000_000) throw NonUnBackup()
    val salt = ByteArray(SALT).also { d.readFully(it) }
    val iv = ByteArray(IV).also { d.readFully(it) }
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, chiave(password, salt, iterazioni), GCMParameterSpec(128, iv))
    return CipherInputStream(d, c)
  }

  /** Copia lo zip decifrato su [dest]; una password sbagliata diventa PasswordErrata. */
  fun leggiTutto(decifrato: InputStream, dest: OutputStream) {
    try {
      decifrato.copyTo(dest)
    } catch (e: IOException) {
      // CipherInputStream incapsula AEADBadTagException in IOException
      if (e.cause is GeneralSecurityException || e.message?.contains("tag", ignoreCase = true) == true) throw PasswordErrata()
      throw e
    } catch (e: GeneralSecurityException) {
      throw PasswordErrata()
    }
  }
}

/** manifest.json del backup. */
data class ManifestBackup(val formato: String, val versione: Int, val appVersion: String, val creato: String) {
  companion object {
    const val FORMATO = "biosleep-backup"
    const val VERSIONE = 1
  }

  /** null = valido; altrimenti il motivo. */
  fun problema(): String? =
      when {
        formato != FORMATO -> "non è un backup di BioSleep"
        versione > VERSIONE -> "backup creato da una versione più recente dell'app: aggiorna ${BuildConfig.APP_NAME}"
        versione < 1 -> "versione del backup non valida"
        else -> null
      }
}

/** Una notte per l'esportazione CSV (valori gia' letti dal database). */
data class NotteCsv(
    val inizioMs: Long,
    val fineMs: Long,
    val sonnoMin: Int?,
    val profondoMin: Int?,
    val remMin: Int?,
    val leggeroMin: Int?,
    val vegliaMin: Int?,
    val fcMedia: Double?,
    val fcMinima: Double?,
    val fcRiposo: Double?,
    val rmssd: Double?,
    val sdnn: Double?,
    val qualitaPct: Double?,
)

object Csv {
  /** Separatore virgola e punto decimale: il CSV si apre uguale in Excel, Numbers, Python. */
  private fun n(v: Double?, dec: Int = 1) = v?.let { String.format(Locale.US, "%.${dec}f", it) } ?: ""

  private fun i(v: Int?) = v?.toString() ?: ""

  fun notti(notti: List<NotteCsv>, zona: ZoneId = ZoneId.systemDefault()): String = buildString {
    append("data,inizio,fine,durata_h,sonno_min,profondo_min,rem_min,leggero_min,veglia_min,fc_media,fc_minima,fc_riposo,rmssd_ms,sdnn_ms,qualita_pct\n")
    for (x in notti.sortedBy { it.inizioMs }) {
      val inizio = Instant.ofEpochMilli(x.inizioMs).atZone(zona)
      val fine = Instant.ofEpochMilli(x.fineMs).atZone(zona)
      append(fine.toLocalDate()).append(',')
      append(inizio.toLocalDateTime().withNano(0)).append(',')
      append(fine.toLocalDateTime().withNano(0)).append(',')
      append(n((x.fineMs - x.inizioMs) / 3_600_000.0, 2)).append(',')
      append(i(x.sonnoMin)).append(',').append(i(x.profondoMin)).append(',').append(i(x.remMin)).append(',')
      append(i(x.leggeroMin)).append(',').append(i(x.vegliaMin)).append(',')
      append(n(x.fcMedia)).append(',').append(n(x.fcMinima)).append(',').append(n(x.fcRiposo)).append(',')
      append(n(x.rmssd)).append(',').append(n(x.sdnn)).append(',').append(n(x.qualitaPct))
      append('\n')
    }
  }

  /**
   * Validita' di ogni battito, con la regola della specifica del progetto: RR tra 300 e 2000 ms
   * e scarto non oltre il 20% dalla mediana dei 10 battiti validi precedenti.
   */
  fun validi(rr: IntArray): BooleanArray {
    val out = BooleanArray(rr.size)
    val ultimi = ArrayDeque<Int>()
    for (k in rr.indices) {
      val v = rr[k]
      var ok = v in 300..2000
      if (ok && ultimi.size >= 3) {
        val m = ultimi.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2].toDouble() else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0 }
        ok = kotlin.math.abs(v - m) <= 0.2 * m
      }
      out[k] = ok
      if (ok) {
        ultimi.addLast(v)
        if (ultimi.size > 10) ultimi.removeFirst()
      }
    }
    return out
  }

  fun rr(tempiMs: LongArray, rr: IntArray): String {
    val ok = validi(rr)
    val sb = StringBuilder(rr.size * 24)
    sb.append("timestamp_ms,rr_ms,valido\n")
    for (k in rr.indices) sb.append(tempiMs[k]).append(',').append(rr[k]).append(',').append(if (ok[k]) 1 else 0).append('\n')
    return sb.toString()
  }
}
