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
 * Comprime l'accelerometro di una notte (una riga al secondo) in pochi KB.
 * Formato (poi gzip): "AC" + versione + numero righe + per ogni colonna i valori come
 * differenze dal precedente (varint zigzag). Secondi consecutivi -> differenza 1 -> 1 byte.
 */
object AccCodec {
  // 1 = activity come deviazione standard; 2 = variazione tra campioni; 3 = come 2 + assi in 0,1 mg
  private const val VERSION = 3

  fun encode(acc: AccSeconds): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(GZIPOutputStream(bytes)).use { out ->
      out.writeByte('A'.code)
      out.writeByte('C'.code)
      out.writeByte(VERSION)
      writeVarint(out, acc.size.toLong())
      writeColumn(out, acc.tSec)
      for (col in listOf(acc.activity, acc.gx, acc.gy, acc.gz)) {
        writeColumn(out, LongArray(col.size) { col[it].toLong() })
      }
    }
    return bytes.toByteArray()
  }

  fun decode(blob: ByteArray): AccSeconds {
    DataInputStream(GZIPInputStream(ByteArrayInputStream(blob))).use { inp ->
      check(inp.readByte().toInt() == 'A'.code && inp.readByte().toInt() == 'C'.code) {
        "Archivio ACC non valido"
      }
      val version = inp.readByte().toInt()
      check(version in 1..VERSION) { "Versione archivio ACC non supportata: $version" }
      val n = readVarint(inp).toInt()
      val t = readColumn(inp, n)
      val cols = List(4) { readColumn(inp, n).let { c -> IntArray(n) { c[it].toInt() } } }
      return AccSeconds(t, cols[0], cols[1], cols[2], cols[3], metric = version)
    }
  }

  private fun writeColumn(out: OutputStream, v: LongArray) {
    var prev = 0L
    for (x in v) {
      val d = x - prev
      writeVarint(out, (d shl 1) xor (d shr 63))
      prev = x
    }
  }

  private fun readColumn(inp: InputStream, n: Int): LongArray {
    val v = LongArray(n)
    var prev = 0L
    for (i in 0 until n) {
      val z = readVarint(inp)
      prev += (z ushr 1) xor -(z and 1)
      v[i] = prev
    }
    return v
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
      if (b < 0) throw EOFException("Archivio ACC troncato")
      result = result or ((b and 0x7F).toLong() shl shift)
      if (b and 0x80 == 0) return result
      shift += 7
    }
  }
}
