package com.wboelens.polarrecorder.biosleep.hal

/** Stato del contatto con la pelle dichiarato dalla fascia (bit 1-2 dei flag di 0x2A37). */
enum class Contatto { NON_SUPPORTATO, ASSENTE, PRESENTE }

/**
 * Un pacchetto di battito, indipendente dalla marca della fascia: lo producono sia il driver
 * GATT 0x180D sia il driver Polar. rrRaw sono i valori originali in 1/1024 s (null se la fonte
 * li da' gia' in ms, come il Polar SDK): servono a riconoscere i sensori ottici quantizzati.
 */
data class HrPacket(
    val hr: Int,
    val contatto: Contatto,
    val energiaKj: Int?,
    val rrMs: List<Int>,
    val rrRaw: List<Int>?,
) {
  /** Contatto supportato ma assente: la fascia non e' sulla pelle, il pacchetto va scartato. */
  val daScartare: Boolean
    get() = contatto == Contatto.ASSENTE

  /** RR fuori 300-2000 ms (200-30 bpm): artefatti secondo la specifica, segnalati non tolti. */
  val rrFuoriRange: List<Int>
    get() = rrMs.filter { it !in RR_MIN_MS..RR_MAX_MS }

  companion object {
    const val RR_MIN_MS = 300
    const val RR_MAX_MS = 2000
  }
}

/**
 * Parser della caratteristica Heart Rate Measurement (0x2A37) del servizio GATT standard 0x180D,
 * secondo spec_gatt_0x180D.txt. Nessuna dipendenza Android: testabile sul PC.
 * Ritorna null per un pacchetto malformato (troppo corto per i campi dichiarati nei flag).
 */
object Gatt2A37Parser {
  private const val FLAG_HR_UINT16 = 0x01
  private const val FLAG_ENERGIA = 0x08
  private const val FLAG_RR = 0x10

  fun parse(data: ByteArray): HrPacket? {
    if (data.size < 2) return null
    val flags = u8(data, 0)
    var offset = 1

    val hr: Int
    if (flags and FLAG_HR_UINT16 == 0) {
      hr = u8(data, offset)
      offset += 1
    } else {
      if (data.size < offset + 2) return null
      hr = u16(data, offset)
      offset += 2
    }

    val contatto =
        when ((flags shr 1) and 0x03) {
          3 -> Contatto.PRESENTE
          2 -> Contatto.ASSENTE
          else -> Contatto.NON_SUPPORTATO // 0 e 1: non supportato
        }

    var energia: Int? = null
    if (flags and FLAG_ENERGIA != 0) {
      if (data.size < offset + 2) return null
      energia = u16(data, offset)
      offset += 2
    }

    val raw = mutableListOf<Int>()
    if (flags and FLAG_RR != 0) {
      while (offset + 1 < data.size) {
        raw += u16(data, offset)
        offset += 2
      }
    }
    // 1/1024 s -> ms, arrotondato al ms (lo standard e' 1/1024 s: 0,98 ms di risoluzione)
    val ms = raw.map { Math.round(it * 1000.0 / 1024.0).toInt() }
    return HrPacket(hr, contatto, energia, ms, raw)
  }

  private fun u8(d: ByteArray, i: Int) = d[i].toInt() and 0xFF

  private fun u16(d: ByteArray, i: Int) = u8(d, i) or (u8(d, i + 1) shl 8)
}
