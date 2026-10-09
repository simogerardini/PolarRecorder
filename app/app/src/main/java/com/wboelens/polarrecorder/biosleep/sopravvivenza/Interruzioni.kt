package com.wboelens.polarrecorder.biosleep.sopravvivenza

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Un evento registrato durante la notte (servizio, Bluetooth, fascia). */
data class EventoNotte(val tMs: Long, val tipo: TipoEvento, val dettaglio: String = "")

enum class TipoEvento {
  SERVIZIO_AVVIATO, // onCreate del servizio: se avviene dentro un buco, il processo era morto
  SERVIZIO_CHIUSO, // onDestroy del servizio (chiusura ordinata)
  RIPRESA, // la notte riprende dopo un'interruzione (watchdog o riavvio di Android)
  BT_SPENTO,
  BT_ACCESO,
  FASCIA_SCOLLEGATA,
  FASCIA_COLLEGATA,
}

/** Causa di un buco come codice stabile: contratto biosleep_interruzioni e traduzioni. */
enum class CodiceCausa { APP_CHIUSA, BT_SPENTO, FUORI_PORTATA, SENZA_CONTATTO }

/** [causa] per esteso con l'ora; [breve] senza ora, per la card "Registrazione interrotta". */
data class Buco(
    val daMs: Long,
    val aMs: Long,
    val causa: String,
    val breve: String,
    val codice: CodiceCausa,
) {
  val minuti: Int
    get() = ((aMs - daMs) / 60_000).toInt()
}

data class InterruzioniNotte(val buchi: List<Buco>) {
  val minutiPersi: Int
    get() = buchi.sumOf { it.minuti }

  /** Es. "23' persi: app chiusa dal sistema alle 03:12 (18'); fascia fuori portata alle 05:40 (5')". */
  fun testo(): String? =
      if (buchi.isEmpty()) null
      else "$minutiPersi' persi: " + buchi.joinToString("; ") { "${it.causa} (${it.minuti}')" }

  /** Causa del buco piu' lungo, piu' quanti altri ce ne sono: "app chiusa dal sistema, +2 interruzioni". */
  /** Buco piu' lungo: la causa principale della notte. */
  fun principale(): Buco? = buchi.maxByOrNull { it.aMs - it.daMs }

  /** Quanti buchi oltre al principale. */
  val altre: Int
    get() = (buchi.size - 1).coerceAtLeast(0)

  fun causaBreve(): String? {
    val principale = buchi.maxByOrNull { it.aMs - it.daMs } ?: return null
    val altri = buchi.size - 1
    return principale.breve + when (altri) {
      0 -> ""
      1 -> ", +1 interruzione"
      else -> ", +$altri interruzioni"
    }
  }
}

/**
 * Trova i buchi di una notte nei battiti salvati e ne indica la causa probabile dagli eventi.
 * Nessuna dipendenza Android: testabile sul PC.
 *
 * Un buco e' un intervallo di almeno 2 minuti senza pacchetti di battiti (la fascia ne manda
 * uno al secondo). Causa, in ordine di priorita':
 *  1. Bluetooth spento durante il buco;
 *  2. servizio ripartito dentro il buco senza una chiusura ordinata -> app chiusa dal sistema;
 *  3. fascia scollegata durante il buco -> fuori portata o spenta;
 *  4. altrimenti -> battiti assenti (fascia spostata o senza contatto).
 */
object RilevaInterruzioni {
  const val MIN_BUCO_MS = 120_000L
  private const val MARGINE_MS = 90_000L
  private val ORA = DateTimeFormatter.ofPattern("HH:mm")

  fun trova(
      pacchettiMs: LongArray,
      eventi: List<EventoNotte>,
      zona: ZoneId = ZoneId.systemDefault(),
  ): InterruzioniNotte {
    if (pacchettiMs.size < 2) return InterruzioniNotte(emptyList())
    val t = pacchettiMs.sorted()
    val buchi = mutableListOf<Buco>()
    for (i in 1 until t.size) {
      val a = t[i - 1]
      val b = t[i]
      if (b - a < MIN_BUCO_MS) continue
      val (causa, breve, codice) = causa(a, b, eventi, zona)
      buchi += Buco(a, b, causa, breve, codice)
    }
    return InterruzioniNotte(buchi)
  }

  /** (causa con l'ora, causa breve). */
  private fun causa(a: Long, b: Long, eventi: List<EventoNotte>, zona: ZoneId): Triple<String, String, CodiceCausa> {
    val dentro = eventi.filter { it.tMs in (a - MARGINE_MS)..(b + MARGINE_MS) }
    fun ora(ms: Long) = Instant.ofEpochMilli(ms).atZone(zona).format(ORA)
    dentro.firstOrNull { it.tipo == TipoEvento.BT_SPENTO }?.let {
      return Triple("Bluetooth spento alle ${ora(it.tMs)}", "Bluetooth spento", CodiceCausa.BT_SPENTO)
    }
    val chiusuraOrdinata = dentro.any { it.tipo == TipoEvento.SERVIZIO_CHIUSO }
    val ripartito = dentro.any { it.tipo == TipoEvento.SERVIZIO_AVVIATO || it.tipo == TipoEvento.RIPRESA }
    if (ripartito && !chiusuraOrdinata) return Triple("app chiusa dal sistema alle ${ora(a)}", "app chiusa dal sistema", CodiceCausa.APP_CHIUSA)
    if (dentro.any { it.tipo == TipoEvento.FASCIA_SCOLLEGATA }) {
      return Triple("fascia fuori portata o spenta alle ${ora(a)}", "fascia fuori portata", CodiceCausa.FUORI_PORTATA)
    }
    return Triple("nessun battito dalle ${ora(a)} (fascia spostata o senza contatto)", "fascia senza contatto", CodiceCausa.SENZA_CONTATTO)
  }
}

/** Ripresa di una notte interrotta: logica pura usata dal watchdog e dal recupero all'avvio. */
object RipresaNotte {
  const val MAX_SILENZIO_MS = 3 * 3_600_000L // oltre 3 ore senza battiti la notte e' finita
  const val MAX_DURATA_MS = 14 * 3_600_000L

  /** Vero se una sessione aperta va ripresa invece di essere chiusa. */
  fun daRiprendere(nowMs: Long, inizioMs: Long, ultimoBattitoMs: Long?, mattino: Boolean): Boolean =
      !mattino &&
          ultimoBattitoMs != null &&
          nowMs - ultimoBattitoMs <= MAX_SILENZIO_MS &&
          nowMs - inizioMs <= MAX_DURATA_MS
}
