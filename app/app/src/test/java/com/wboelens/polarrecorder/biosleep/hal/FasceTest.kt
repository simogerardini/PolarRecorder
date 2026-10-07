package com.wboelens.polarrecorder.biosleep.hal

import kotlin.math.roundToInt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parser 0x2A37, giudizio sugli RR e catena dei driver: pacchetti costruiti a mano, niente hardware. */
class FasceTest {
  private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

  /** RR in ms -> due byte little endian in 1/1024 s. */
  private fun rrBytes(ms: Int): IntArray {
    val raw = (ms * 1024.0 / 1000.0).roundToInt()
    return intArrayOf(raw and 0xFF, raw shr 8)
  }

  // --- Parser: formati della FC ------------------------------------------------------------

  @Test
  fun hrUint8SenzaAltro() {
    val p = Gatt2A37Parser.parse(b(0x00, 62))!!
    assertEquals(62, p.hr)
    assertEquals(Contatto.NON_SUPPORTATO, p.contatto)
    assertNull(p.energiaKj)
    assertTrue(p.rrMs.isEmpty())
  }

  @Test
  fun hrUint16LittleEndian() {
    val p = Gatt2A37Parser.parse(b(0x01, 0x2C, 0x01))!! // 0x012C = 300
    assertEquals(300, p.hr)
  }

  @Test
  fun hrUint16Troncato() {
    assertNull(Gatt2A37Parser.parse(b(0x01, 0x2C)))
    assertNull(Gatt2A37Parser.parse(b(0x00)))
    assertNull(Gatt2A37Parser.parse(ByteArray(0)))
  }

  // --- Parser: contatto -------------------------------------------------------------------

  @Test
  fun contattoNeiBit1e2() {
    assertEquals(Contatto.NON_SUPPORTATO, Gatt2A37Parser.parse(b(0x00, 60))!!.contatto) // 00
    assertEquals(Contatto.NON_SUPPORTATO, Gatt2A37Parser.parse(b(0x02, 60))!!.contatto) // 01
    val assente = Gatt2A37Parser.parse(b(0x04, 60))!! // 10: supportato, assente
    assertEquals(Contatto.ASSENTE, assente.contatto)
    assertTrue(assente.daScartare)
    val presente = Gatt2A37Parser.parse(b(0x06, 60))!! // 11: supportato, presente
    assertEquals(Contatto.PRESENTE, presente.contatto)
    assertFalse(presente.daScartare)
  }

  // --- Parser: Energy Expended --------------------------------------------------------------

  @Test
  fun energiaPresenteSpostaGliRr() {
    // flag: RR + energia + contatto presente + UINT8; energia 0x0102 = 258 kJ; 1 RR da 1000 ms
    val p = Gatt2A37Parser.parse(b(0x1E, 58, 0x02, 0x01, *rrBytes(1000)))!!
    assertEquals(258, p.energiaKj)
    assertEquals(listOf(1000), p.rrMs)
  }

  @Test
  fun energiaDichiarataMaMancante() {
    assertNull(Gatt2A37Parser.parse(b(0x08, 58, 0x02)))
  }

  @Test
  fun energiaConHrUint16() {
    val p = Gatt2A37Parser.parse(b(0x19, 0x48, 0x00, 0x10, 0x00, *rrBytes(833)))!! // UINT16 + energia + RR
    assertEquals(72, p.hr)
    assertEquals(16, p.energiaKj)
    assertEquals(listOf(833), p.rrMs)
  }

  // --- Parser: zero, uno, piu' RR ----------------------------------------------------------

  @Test
  fun flagRrSenzaValori() {
    val p = Gatt2A37Parser.parse(b(0x10, 60))!!
    assertTrue(p.rrMs.isEmpty())
  }

  @Test
  fun unRr() {
    val p = Gatt2A37Parser.parse(b(0x16, 60, *rrBytes(1000)))!!
    assertEquals(listOf(1000), p.rrMs)
    assertEquals(listOf(1024), p.rrRaw)
  }

  @Test
  fun quattroRrConversione1024() {
    // 1024 -> 1000 ms, 512 -> 500 ms, 1000 -> 977 ms, 2048 -> 2000 ms
    val p = Gatt2A37Parser.parse(b(0x10, 70, 0x00, 0x04, 0x00, 0x02, 0xE8, 0x03, 0x00, 0x08))!!
    assertEquals(listOf(1000, 500, 977, 2000), p.rrMs)
  }

  @Test
  fun byteDispariFinaleIgnorato() {
    val p = Gatt2A37Parser.parse(b(0x10, 60, *rrBytes(1000), 0x05))!!
    assertEquals(listOf(1000), p.rrMs)
  }

  @Test
  fun rrFuoriRangeSegnalatiNonTolti() {
    val p = Gatt2A37Parser.parse(b(0x10, 60, *rrBytes(250), *rrBytes(1000), *rrBytes(2400)))!!
    assertEquals(3, p.rrMs.size)
    assertEquals(listOf(250, 2400), p.rrFuoriRange)
  }

  @Test
  fun valoriLimiteDelRange() {
    val p = Gatt2A37Parser.parse(b(0x10, 60, *rrBytes(300), *rrBytes(2000)))!!
    assertTrue(p.rrFuoriRange.isEmpty())
    val q = Gatt2A37Parser.parse(b(0x10, 60, *rrBytes(299), *rrBytes(2001)))!!
    assertEquals(2, q.rrFuoriRange.size)
  }

  // --- Giudizio sugli RR ----------------------------------------------------------------------

  private fun sessione(minuti: Int, generaRr: (Random, Int) -> List<Int>?, hrDa: (List<Int>?) -> Int = { 60 }): List<Pair<Long, HrPacket>> {
    val r = Random(7)
    return (0 until minuti * 60).map { s ->
      val rr = generaRr(r, s)
      val raw = rr?.map { (it * 1024.0 / 1000.0).roundToInt() }
      s * 1000L to HrPacket(hrDa(rr), Contatto.PRESENTE, null, rr ?: emptyList(), raw)
    }
  }

  /** Fascia da petto: RR con variabilita' battito-battito (rMSSD ~40 ms) e respiro. */
  private fun petto(r: Random, s: Int): List<Int> =
      listOf((1000 + 40 * kotlin.math.sin(s / 4.0) + r.nextDouble(-30.0, 30.0)).roundToInt())

  @Test
  fun pettoAffidabile() {
    val v = AffidabilitaRr.valuta(sessione(10, ::petto), 0, 600_000)
    assertEquals(StatoRr.AFFIDABILI, v.stato)
    assertTrue(v.percentualeValidi!! > 99.0)
  }

  @Test
  fun senzaRrSoloFcDopo5Minuti() {
    val s = sessione(6, { _, _ -> null })
    assertEquals(StatoRr.IN_VALUTAZIONE, AffidabilitaRr.valuta(s, 0, 4 * 60_000).stato)
    assertEquals(StatoRr.SOLO_FC, AffidabilitaRr.valuta(s, 0, 5 * 60_000).stato)
  }

  @Test
  fun otticoQuantizzato() {
    // orologio interno a 1/64 s: valori grezzi multipli di 16/1024
    val s =
        sessione(10, { r, x -> listOf(petto(r, x)[0]) }).map { (t, p) ->
          val raw = p.rrRaw!!.map { (it / 16.0).roundToInt() * 16 }
          t to p.copy(rrRaw = raw, rrMs = raw.map { (it * 1000.0 / 1024).roundToInt() })
        }
    val v = AffidabilitaRr.valuta(s, 0, 600_000)
    assertEquals(StatoRr.NON_AFFIDABILI, v.stato)
    assertTrue(v.motivo.contains("1/64"))
  }

  @Test
  fun otticoCalcolatoDallaFc() {
    // FC intera che cambia lentamente; RR = 60000/FC
    val s =
        (0 until 600).map { x ->
          val hr = 58 + (x / 40) % 5
          val rr = (60_000.0 / hr).roundToInt()
          x * 1000L to HrPacket(hr, Contatto.NON_SUPPORTATO, null, listOf(rr), null)
        }
    val v = AffidabilitaRr.valuta(s, 0, 600_000)
    assertEquals(StatoRr.NON_AFFIDABILI, v.stato)
    assertTrue(v.motivo.contains("calcolati"))
  }

  @Test
  fun otticoRumoroso() {
    val s = sessione(10, { r, _ -> listOf(r.nextInt(400, 1800)) })
    val v = AffidabilitaRr.valuta(s, 0, 600_000)
    assertEquals(StatoRr.NON_AFFIDABILI, v.stato)
    assertTrue(v.motivo.contains("non validi"))
  }

  @Test
  fun polarSdkSenzaValoriGrezziResteAffidabile() {
    val s = sessione(10, ::petto).map { (t, p) -> t to p.copy(rrRaw = null) }
    assertEquals(StatoRr.AFFIDABILI, AffidabilitaRr.valuta(s, 0, 600_000).stato)
  }

  // --- Catena dei driver ----------------------------------------------------------------------

  private val hr = setOf(DriverRegistry.UUID_HR_SERVICE)

  @Test
  fun catena() {
    assertEquals(listOf(TipoDriver.POLAR, TipoDriver.GATT_180D), DriverRegistry.catena("Polar H10 A1B2C3D4", hr))
    assertEquals(listOf(TipoDriver.GATT_180D), DriverRegistry.catena("HRM 600:12345", hr))
    assertEquals(listOf(TipoDriver.GATT_180D), DriverRegistry.catena("COROS HRM 1A2B", hr))
    assertTrue(DriverRegistry.catena("Cuffie Bluetooth", emptySet()).isEmpty())
  }

  @Test
  fun tipoDaNome() {
    assertEquals(TipoFascia.PETTO, DriverRegistry.tipo("Polar H10 A1B2C3D4"))
    assertEquals(TipoFascia.PETTO, DriverRegistry.tipo("HRM 600:12345"))
    assertEquals(TipoFascia.OTTICA, DriverRegistry.tipo("COROS HRM 1A2B"))
    assertEquals(TipoFascia.OTTICA, DriverRegistry.tipo("Polar Verity Sense 0A1B"))
    assertEquals(TipoFascia.SCONOSCIUTO, DriverRegistry.tipo("XYZ-123"))
  }

  @Test
  fun accSoloH10ConDriverPolar() {
    assertTrue(DriverRegistry.haAcc("Polar H10 A1B2C3D4", TipoDriver.POLAR))
    assertFalse(DriverRegistry.haAcc("Polar H10 A1B2C3D4", TipoDriver.GATT_180D))
    assertFalse(DriverRegistry.haAcc("Polar H9 A1B2C3D4", TipoDriver.POLAR))
    assertNotNull(DriverRegistry.catena("Polar H9", emptySet()).firstOrNull())
  }
}
