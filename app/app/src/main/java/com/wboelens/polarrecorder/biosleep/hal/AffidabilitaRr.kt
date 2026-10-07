package com.wboelens.polarrecorder.biosleep.hal

import kotlin.math.abs

/** Qualita' degli intervalli RR di una sessione. */
enum class StatoRr {
  /** Ancora troppo pochi dati per decidere (primi minuti). */
  IN_VALUTAZIONE,
  AFFIDABILI,
  /** Nessun RR in 5 minuti: la fascia manda solo la frequenza cardiaca. */
  SOLO_FC,
  /** RR presenti ma tipici di un sensore ottico o troppo rumorosi: HRV non calcolabile. */
  NON_AFFIDABILI,
}

data class ValutazioneRr(val stato: StatoRr, val motivo: String, val percentualeValidi: Double?)

/**
 * Decide se gli RR di una fascia servono per l'HRV. Nessuna dipendenza Android.
 *
 * Una fascia da petto misura il tempo tra due battiti elettrici con risoluzione 1/1024 s: i
 * valori sono quasi tutti diversi tra loro e variano un po' a ogni battito. Un sensore ottico
 * spesso no: gli "RR" sono calcolati dalla frequenza cardiaca (60000/FC), oppure misurati con un
 * orologio interno lento (1/64 s, 1/128 s), oppure molto rumorosi. Controlli, in ordine:
 *  1. nessun RR dopo 5 minuti -> SOLO_FC;
 *  2. valori grezzi tutti multipli di un passo >= 8/1024 s -> quantizzati (orologio lento);
 *  3. RR uguali a 60000/FC in almeno l'80% dei pacchetti -> calcolati dalla FC;
 *  4. differenze consecutive nulle in oltre il 35% dei casi -> andamento troppo regolare;
 *  5. meno dell'80% di battiti validi -> troppo rumorosi.
 */
object AffidabilitaRr {
  const val ATTESA_RR_MS = 5 * 60_000L
  private const val MIN_RR = 200 // sotto questa soglia si aspetta ancora
  private const val PASSO_QUANTIZZATO = 8
  private const val QUOTA_DA_FC = 0.80
  private const val QUOTA_DIFF_NULLE = 0.35
  private const val QUOTA_VALIDI = 0.80

  /**
   * [pacchetti]: (istante ms, pacchetto) in ordine di arrivo, solo quelli a contatto presente o
   * non supportato. [inizioMs]: arrivo del primo pacchetto con la FC.
   */
  fun valuta(pacchetti: List<Pair<Long, HrPacket>>, inizioMs: Long, oraMs: Long): ValutazioneRr {
    val rr = pacchetti.flatMap { it.second.rrMs }
    if (rr.isEmpty()) {
      return if (oraMs - inizioMs >= ATTESA_RR_MS) ValutazioneRr(StatoRr.SOLO_FC, "la fascia non invia gli intervalli RR", null)
      else ValutazioneRr(StatoRr.IN_VALUTAZIONE, "in attesa dei primi RR", null)
    }
    val validi = percentualeValidi(rr)
    if (rr.size < MIN_RR) return ValutazioneRr(StatoRr.IN_VALUTAZIONE, "pochi RR finora (${rr.size})", validi)

    val raw = pacchetti.flatMap { it.second.rrRaw ?: emptyList() }
    if (raw.size >= MIN_RR) {
      val passo = raw.fold(0) { g, v -> gcd(g, v) }
      if (passo >= PASSO_QUANTIZZATO) {
        return ValutazioneRr(StatoRr.NON_AFFIDABILI, "RR quantizzati a 1/${1024 / passo} s (tipico dei sensori ottici)", validi)
      }
    }
    val conRr = pacchetti.filter { it.second.rrMs.isNotEmpty() && it.second.hr > 0 }
    val daFc = conRr.count { (_, p) -> p.rrMs.all { abs(it - 60_000.0 / p.hr) <= 2.0 } }
    if (conRr.isNotEmpty() && daFc.toDouble() / conRr.size >= QUOTA_DA_FC) {
      return ValutazioneRr(StatoRr.NON_AFFIDABILI, "RR calcolati dalla frequenza cardiaca, non misurati", validi)
    }
    val nulle = rr.zipWithNext().count { (a, b) -> a == b }.toDouble() / (rr.size - 1)
    if (nulle > QUOTA_DIFF_NULLE) {
      return ValutazioneRr(StatoRr.NON_AFFIDABILI, "andamento troppo regolare (${(nulle * 100).toInt()}% di RR identici consecutivi)", validi)
    }
    if (validi < QUOTA_VALIDI * 100) {
      return ValutazioneRr(StatoRr.NON_AFFIDABILI, "troppi battiti non validi (${validi.toInt()}%)", validi)
    }
    return ValutazioneRr(StatoRr.AFFIDABILI, "RR misurati e coerenti", validi)
  }

  /** % di RR plausibili: 300-2000 ms e entro il 20% della mediana dei 10 precedenti. */
  fun percentualeValidi(rr: List<Int>): Double {
    if (rr.isEmpty()) return 0.0
    var ok = 0
    for (i in rr.indices) {
      val v = rr[i]
      if (v !in HrPacket.RR_MIN_MS..HrPacket.RR_MAX_MS) continue
      val prec = rr.subList(maxOf(0, i - 10), i).filter { it in HrPacket.RR_MIN_MS..HrPacket.RR_MAX_MS }
      if (prec.size >= 3) {
        val med = prec.sorted()[prec.size / 2]
        if (abs(v - med) > med * 0.20) continue
      }
      ok++
    }
    return ok * 100.0 / rr.size
  }

  private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
