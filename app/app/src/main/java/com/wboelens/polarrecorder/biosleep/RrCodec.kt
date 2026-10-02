package com.wboelens.polarrecorder.biosleep

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Comprime i battiti di una notte in un piccolo blocco di byte (~40 KB per 8 ore).
 *
 * Formato (poi compresso con gzip):
 *   "RR" + versione(1 byte) + primo orario (8 byte) + numero righe (varint)
 *   per ogni riga: differenza di orario dalla riga precedente (varint zigzag) + RR in ms (varint)
 * Le righe dello stesso pacchetto hanno differenza 0, tra un pacchetto e l'altro ~1000:
 * numeri piccoli = pochi byte, e gzip li comprime ancora molto.
 */
object RrCodec {
  private const val VERSION = 1

  fun encode(phoneMs: LongArray, rrMs: IntArray): ByteArray {
    require(phoneMs.size == rrMs.size)
    val bytes = ByteArrayOutputStream()
    DataOutputStream(GZIPOutputStream(bytes)).use { out ->
      out.writeByte('R'.code)
      out.writeByte('R'.code)
      out.writeByte(VERSION)
      out.writeLong(if (phoneMs.isEmpty()) 0L else phoneMs[0])
      writeVarint(out, phoneMs.size.toLong())
      var prev = if (phoneMs.isEmpty()) 0L else phoneMs[0]
      for (i in phoneMs.indices) {
        val delta = phoneMs[i] - prev
        writeVarint(out, (delta shl 1) xor (delta shr 63)) // zigzag: gestisce anche delta negativi
        writeVarint(out, rrMs[i].toLong())
        prev = phoneMs[i]
      }
    }
    return bytes.toByteArray()
  }

  fun decode(blob: ByteArray): Pair<LongArray, IntArray> {
    DataInputStream(GZIPInputStream(ByteArrayInputStream(blob))).use { inp ->
      check(inp.readByte().toInt() == 'R'.code && inp.readByte().toInt() == 'R'.code) {
        "Archivio RR non valido"
      }
      val version = inp.readByte().toInt()
      check(version == VERSION) { "Versione archivio RR non supportata: $version" }
      var prev = inp.readLong()
      val n = readVarint(inp).toInt()
      val t = LongArray(n)
      val r = IntArray(n)
      for (i in 0 until n) {
        val z = readVarint(inp)
        prev += (z ushr 1) xor -(z and 1)
        t[i] = prev
        r[i] = readVarint(inp).toInt()
      }
      return t to r
    }
  }

  private fun writeVarint(out: OutputStream, value: Long) {
    var v = value
    while (v and 0x7FL.inv() != 0L) {
      out.write(((v and 0x7F) or 0x80).toInt())
      v = v ushr 7
    }
    out.write(v.toInt())
  }

  private fun readVarint(inp: InputStream): Long {
    var result = 0L
    var shift = 0
    while (true) {
      val b = inp.read()
      if (b < 0) throw EOFException("Archivio RR troncato")
      result = result or ((b and 0x7F).toLong() shl shift)
      if (b and 0x80 == 0) return result
      shift += 7
    }
  }
}
