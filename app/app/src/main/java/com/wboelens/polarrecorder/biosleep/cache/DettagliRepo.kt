package com.wboelens.polarrecorder.biosleep.cache

import android.content.Context
import android.database.SQLException
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsAuth
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsSettings
import com.wboelens.polarrecorder.biosleep.training.DettaglioAttivita
import com.wboelens.polarrecorder.biosleep.training.DettaglioParser
import com.wboelens.polarrecorder.biosleep.training.Flussi

/** Esito del caricamento dell'analisi di una seduta. */
sealed interface EsitoDettaglio {
  data class Pronto(val attivita: DettaglioAttivita, val flussi: Flussi?) : EsitoDettaglio

  data class Errore(val messaggio: String) : EsitoDettaglio
}

/**
 * Analisi di una seduta svolta: dalla cache se gia' aperta, altrimenti due letture da
 * Intervals.icu (attivita' con zone e intervalli, serie nel tempo) poi salvate. Fuori dal main thread.
 */
object DettagliRepo {
  fun carica(context: Context, id: String): EsitoDettaglio {
    val db = CacheDb.get(context)
    val salvato = try { db.dettaglio(id) } catch (e: SQLException) { null }
    if (salvato != null) {
      DettaglioParser.attivita(salvato.first)?.let { return EsitoDettaglio.Pronto(it, DettaglioParser.flussi(salvato.second)) }
    }
    val credenziali = IntervalsSettings(context).credenziali ?: return EsitoDettaglio.Errore("Intervals.icu non collegato")
    val chiave = IntervalsAuth.header(credenziali)
    val attivitaTesto =
        when (val l = IntervalsReader.leggiPercorso(chiave, "/activity/$id?intervals=true")) {
          is Lettura.Ok -> l.testo
          is Lettura.Errore -> return EsitoDettaglio.Errore(l.messaggio)
        }
    val attivita = DettaglioParser.attivita(attivitaTesto) ?: return EsitoDettaglio.Errore("Risposta di Intervals.icu non valida")
    // Le serie sono facoltative: se mancano si mostrano comunque numeri, zone e intervalli
    val tipi = DettaglioParser.TIPI.joinToString(",")
    val flussiTesto =
        (IntervalsReader.leggiPercorso(chiave, "/activity/$id/streams.json?types=$tipi") as? Lettura.Ok)
            ?.let { DettaglioParser.riduci(it.testo) } ?: "{}"
    try {
      db.salvaDettaglio(id, attivitaTesto, flussiTesto)
    } catch (e: SQLException) {
      // senza cache si riscarica la prossima volta
    }
    return EsitoDettaglio.Pronto(attivita, DettaglioParser.flussi(flussiTesto))
  }
}
