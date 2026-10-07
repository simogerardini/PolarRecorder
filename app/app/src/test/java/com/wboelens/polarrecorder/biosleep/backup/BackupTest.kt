package com.wboelens.polarrecorder.biosleep.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupTest {
  private val dati = ByteArray(200_000) { (it * 31 % 251).toByte() }

  private fun cifrato(pw: String, iter: Int = 100_000): ByteArray {
    val buf = ByteArrayOutputStream()
    CifraturaBackup.cifra(buf, pw.toCharArray(), iter).use { it.write(dati) }
    return buf.toByteArray()
  }

  @Test
  fun andataERitorno() {
    val c = cifrato("una password lunga")
    assertTrue(!c.toList().windowed(16, 997).any { w -> dati.toList().windowed(16, 997).contains(w) }, "il contenuto non e' in chiaro")
    val out = ByteArrayOutputStream()
    CifraturaBackup.leggiTutto(CifraturaBackup.decifra(ByteArrayInputStream(c), "una password lunga".toCharArray()), out)
    assertTrue(out.toByteArray().contentEquals(dati))
  }

  @Test
  fun passwordErrataEFileEstraneo() {
    val c = cifrato("giusta123")
    var errata = false
    try {
      CifraturaBackup.leggiTutto(CifraturaBackup.decifra(ByteArrayInputStream(c), "sbagliata".toCharArray()), ByteArrayOutputStream())
    } catch (e: CifraturaBackup.PasswordErrata) {
      errata = true
    }
    assertTrue(errata, "password sbagliata riconosciuta")
    var estraneo = false
    try {
      CifraturaBackup.decifra(ByteArrayInputStream("PK\u0003\u0004 uno zip qualsiasi".toByteArray()), "x".toCharArray())
    } catch (e: CifraturaBackup.NonUnBackup) {
      estraneo = true
    }
    assertTrue(estraneo)
    // salt e iv casuali: due backup con la stessa password sono diversi
    assertTrue(!cifrato("giusta123").contentEquals(c))
  }

  @Test
  fun manifest() {
    assertNull(ManifestBackup(ManifestBackup.FORMATO, 1, "2.1.1", "2026-10-07T21:00:00").problema())
    assertTrue(ManifestBackup(ManifestBackup.FORMATO, 2, "9", "x").problema()!!.contains("più recente"))
    assertTrue(ManifestBackup("altro", 1, "9", "x").problema() != null)
  }

  @Test
  fun csv() {
    val z = java.time.ZoneId.of("Europe/Rome")
    val n = NotteCsv(1_791_100_000_000, 1_791_133_000_000, 526, 230, 188, 108, 10, 46.0, 38.0, 41.0, 60.6, 88.3, 99.7)
    val righe = Csv.notti(listOf(n), z).trim().lines()
    assertEquals(2, righe.size)
    assertEquals(15, righe[1].split(',').size)
    assertTrue(righe[1].endsWith(",46.0,38.0,41.0,60.6,88.3,99.7"))
    val v = Csv.validi(intArrayOf(1000, 1010, 990, 1005, 2500, 1500, 1000, 250))
    assertEquals(listOf(true, true, true, true, false, false, true, false), v.toList())
    assertEquals("timestamp_ms,rr_ms,valido\n1,1000,1\n2,2500,0\n", Csv.rr(longArrayOf(1, 2), intArrayOf(1000, 2500)))
  }
}
