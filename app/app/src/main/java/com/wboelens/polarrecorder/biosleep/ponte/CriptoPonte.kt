package com.wboelens.polarrecorder.biosleep.ponte

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Crittografia del collegamento telefono <-> browser (docs/protocollo_collegamento.md), solo con
 * java.security e javax.crypto: nessuna dipendenza nuova.
 *  - ECDH P-256, chiavi pubbliche raw non compresse (65 byte), base64url senza padding.
 *  - Chiave di collegamento = HKDF-SHA256(segreto ECDH, salt = sessione, info = INFO_HKDF).
 *  - Busta = base64url(nonce 12 || AES-256-GCM(gzip(JSON))), AAD = "<id>|<tipo>".
 */
object CriptoPonte {
  const val INFO_HKDF = "noctalix/collega/v1"
  private const val INFO_CODICE = "noctalix/codice/v1"
  private const val NONCE = 12
  private const val TAG_BIT = 128
  private const val COORD = 32

  private val casuale = SecureRandom()

  fun b64(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

  fun da64(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

  fun byteCasuali(n: Int): ByteArray = ByteArray(n).also { casuale.nextBytes(it) }

  fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

  /** Hash da depositare sul ponte: SHA-256 dei byte del segreto, in base64url. */
  fun hashSegreto(segretoB64: String): String = b64(sha256(da64(segretoB64)))

  // ── ECDH P-256 ──────────────────────────────────────────────────────────────
  private val P256: ECParameterSpec by lazy {
    AlgorithmParameters.getInstance("EC").run {
      init(ECGenParameterSpec("secp256r1"))
      getParameterSpec(ECParameterSpec::class.java)
    }
  }

  fun nuovaCoppia(): KeyPair =
      KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"), casuale)
        generateKeyPair()
      }

  /** 0x04 || X || Y, coordinate di 32 byte. */
  fun pubRaw(k: PublicKey): ByteArray {
    val w = (k as ECPublicKey).w
    return byteArrayOf(4) + a32(w.affineX) + a32(w.affineY)
  }

  fun pubDaRaw(raw: ByteArray): PublicKey {
    require(raw.size == 1 + 2 * COORD && raw[0] == 4.toByte()) { "chiave pubblica non valida" }
    val x = BigInteger(1, raw.copyOfRange(1, 1 + COORD))
    val y = BigInteger(1, raw.copyOfRange(1 + COORD, raw.size))
    return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), P256))
  }

  /** Solo per i test con i vettori: chiave privata in PKCS#8 base64url. */
  fun privDaPkcs8(b64: String): PrivateKey =
      KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(da64(b64)))

  private fun a32(n: BigInteger): ByteArray {
    val b = n.toByteArray()
    return when {
      b.size == COORD -> b
      b.size > COORD -> b.copyOfRange(b.size - COORD, b.size) // zero di segno in testa
      else -> ByteArray(COORD - b.size) + b
    }
  }

  fun segretoEcdh(mia: PrivateKey, sua: PublicKey): ByteArray =
      KeyAgreement.getInstance("ECDH").run {
        init(mia)
        doPhase(sua, true)
        generateSecret()
      }

  // ── HKDF-SHA256 (RFC 5869) ──────────────────────────────────────────────────
  fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, lunghezza: Int): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(COORD) else salt, "HmacSHA256"))
    val prk = mac.doFinal(ikm)
    mac.init(SecretKeySpec(prk, "HmacSHA256"))
    val out = ByteArrayOutputStream()
    var t = ByteArray(0)
    var n = 1
    while (out.size() < lunghezza) {
      mac.update(t)
      mac.update(info)
      mac.update(n.toByte())
      t = mac.doFinal()
      out.write(t)
      n++
    }
    return out.toByteArray().copyOf(lunghezza)
  }

  fun chiaveCollegamento(mia: PrivateKey, suaRaw: ByteArray, sessione: String): ByteArray =
      hkdf(segretoEcdh(mia, pubDaRaw(suaRaw)), sessione.toByteArray(Charsets.UTF_8),
          INFO_HKDF.toByteArray(Charsets.UTF_8), COORD)

  /** 6 cifre: primi 4 byte di SHA-256(chiave || "noctalix/codice/v1"), modulo 1 000 000. */
  fun codiceVerifica(chiave: ByteArray): String {
    val h = sha256(chiave + INFO_CODICE.toByteArray(Charsets.UTF_8))
    val n = ((h[0].toLong() and 0xff) shl 24) or ((h[1].toLong() and 0xff) shl 16) or
        ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)
    return "%06d".format(n % 1_000_000)
  }

  // ── Buste ───────────────────────────────────────────────────────────────────
  fun chiudi(chiave: ByteArray, id: String, tipo: String, json: String): String {
    val nonce = byteCasuali(NONCE)
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(chiave, "AES"), GCMParameterSpec(TAG_BIT, nonce))
    c.updateAAD("$id|$tipo".toByteArray(Charsets.UTF_8))
    return b64(nonce + c.doFinal(gzip(json.toByteArray(Charsets.UTF_8))))
  }

  /** Lancia un'eccezione (AEADBadTagException) se chiave, id o tipo non corrispondono. */
  fun apri(chiave: ByteArray, id: String, tipo: String, busta: String): String {
    val b = da64(busta)
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, SecretKeySpec(chiave, "AES"), GCMParameterSpec(TAG_BIT, b, 0, NONCE))
    c.updateAAD("$id|$tipo".toByteArray(Charsets.UTF_8))
    return String(gunzip(c.doFinal(b, NONCE, b.size - NONCE)), Charsets.UTF_8)
  }

  fun gzip(b: ByteArray): ByteArray =
      ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

  fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
}
