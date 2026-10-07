package com.wboelens.polarrecorder.biosleep.hal

/**
 * Battiti ricostruiti dalla sola frequenza cardiaca, per le fasce "solo FC": un intervallo di
 * 60000/FC alla volta, finche' la loro somma raggiunge l'istante del pacchetto. Cosi' la linea del
 * tempo della notte resta coerente e FC, FC a riposo, copertura e fasi (variante solo FC) si
 * calcolano come per le altre fasce. L'HRV di questi battiti NON e' reale e non viene mai usata.
 * Nessuna dipendenza Android.
 */
class SintesiBattiti {
  private var tUltimo = Double.NaN

  /** Battiti (ms) da salvare con l'istante [tMs] di questo pacchetto. */
  fun da(tMs: Long, hr: Int): List<Int> {
    if (hr !in 25..240) return emptyList()
    val rr = (60_000.0 / hr).let { Math.round(it).toInt() }
    // primo pacchetto o buco nei dati (> 3 s): si riparte, un buco resta un buco
    if (tUltimo.isNaN() || tMs - tUltimo > rr + BUCO_MS) tUltimo = tMs - rr.toDouble()
    val out = mutableListOf<Int>()
    while (tUltimo + rr <= tMs) {
      out += rr
      tUltimo += rr
    }
    return out
  }

  fun reset() {
    tUltimo = Double.NaN
  }

  companion object {
    private const val BUCO_MS = 3_000
  }
}

/** Stato della fascia nella sessione in corso, per la Parte 3 e per il rapporto. */
data class StatoFasciaSessione(
    val sessionId: Long,
    val nome: String,
    val capacita: Capacita,
    val valutazione: ValutazioneRr,
) {
  /** Vero se l'HRV di questa sessione non va calcolata ne' inviata. */
  val senzaHrv: Boolean
    get() = valutazione.stato == StatoRr.SOLO_FC || valutazione.stato == StatoRr.NON_AFFIDABILI
}

/**
 * Raccoglie i pacchetti di una sessione e ne valuta gli RR (AffidabilitaRr): la prima volta
 * dopo 5 minuti, poi ogni 10 minuti (una fascia puo' peggiorare durante la notte). Nessuna
 * dipendenza Android.
 */
class ValutatoreSessione(val sessionId: Long, val nome: String, var capacita: Capacita) {
  private val pacchetti = ArrayList<Pair<Long, HrPacket>>()
  private var inizioMs = -1L
  private var ultimaValutazioneMs = 0L

  var valutazione = ValutazioneRr(StatoRr.IN_VALUTAZIONE, "in attesa dei primi dati", null)
    private set

  /** Aggiunge un pacchetto; ritorna true se la valutazione e' cambiata. */
  fun aggiungi(tMs: Long, p: HrPacket): Boolean {
    if (inizioMs < 0) inizioMs = tMs
    if (pacchetti.size < MAX_PACCHETTI) pacchetti += tMs to p
    val intervallo = if (valutazione.stato == StatoRr.IN_VALUTAZIONE) PRIMA_MS else POI_MS
    if (tMs - ultimaValutazioneMs < intervallo) return false
    ultimaValutazioneMs = tMs
    val prima = valutazione.stato
    valutazione = AffidabilitaRr.valuta(pacchetti, inizioMs, tMs)
    if (valutazione.stato != StatoRr.IN_VALUTAZIONE) {
      capacita = capacita.copy(rr = valutazione.stato != StatoRr.SOLO_FC)
    }
    return valutazione.stato != prima
  }

  /** Valutazione immediata (fine della registrazione); ritorna true se e' cambiata. */
  fun forza(tMs: Long): Boolean {
    if (inizioMs < 0) return false
    val prima = valutazione.stato
    valutazione = AffidabilitaRr.valuta(pacchetti, inizioMs, tMs)
    if (valutazione.stato != StatoRr.IN_VALUTAZIONE) capacita = capacita.copy(rr = valutazione.stato != StatoRr.SOLO_FC)
    return valutazione.stato != prima
  }

  fun stato() = StatoFasciaSessione(sessionId, nome, capacita, valutazione)

  companion object {
    private const val PRIMA_MS = 60_000L // finche' non decide: ogni minuto
    private const val POI_MS = 10 * 60_000L
    private const val MAX_PACCHETTI = 40_000 // ~11 ore a un pacchetto al secondo
  }
}
