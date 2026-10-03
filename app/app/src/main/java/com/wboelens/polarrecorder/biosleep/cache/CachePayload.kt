package com.wboelens.polarrecorder.biosleep.cache

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wboelens.polarrecorder.biosleep.readiness.PyJson
import com.wboelens.polarrecorder.biosleep.training.Allenamenti
import java.time.LocalDate

/**
 * Finestre di lettura, tutte relative a oggi.
 * - wellness e attivita': 90 giorni indietro (grafici a 90 giorni; la banda ne usa 60, la forma 14);
 * - eventi: 90 giorni indietro (calendario passato, pianificato vs svolto) e 42 avanti.
 */
data class Finestre(val oggi: LocalDate) {
  val storicoDa: LocalDate = oggi.minusDays(GG_STORICO)
  val eventiA: LocalDate = oggi.plusDays(GG_FUTURO)

  companion object {
    const val GG_STORICO = 90L
    const val GG_FUTURO = 42L
  }
}

/** La risposta di Intervals.icu non e' quella attesa (non un array JSON, o JSON rotto). */
class RispostaNonValida(messaggio: String) : Exception(messaggio)

/** Trasforma il testo di una risposta di Intervals.icu nelle righe della cache. Nessun accesso ad Android. */
object CachePayload {

  /**
   * wellness: chiave e giorno = "id" (YYYY-MM-DD);
   * eventi e attivita': chiave = "id", giorno = primi 10 caratteri di "start_date_local".
   * Gli elementi senza chiave o senza giorno vengono scartati.
   */
  fun righe(testo: String, tabella: Tabella): List<RigaCache> {
    val radice =
        try {
          JsonParser.parseString(testo)
        } catch (e: RuntimeException) { // JsonParseException e' una RuntimeException
          throw RispostaNonValida("JSON non valido: ${e.message}")
        }
    if (!radice.isJsonArray) throw RispostaNonValida("attesa una lista, ricevuto: ${testo.take(120)}")
    val out = ArrayList<RigaCache>()
    for (el in radice.asJsonArray) {
      if (!el.isJsonObject) continue
      val o = el.asJsonObject
      val riga = riga(o, tabella) ?: continue
      out.add(riga)
    }
    return out
  }

  private fun riga(o: JsonObject, tabella: Tabella): RigaCache? {
    val id = PyJson.str(o.get("id"))?.takeIf { it.isNotEmpty() } ?: return null
    val data =
        when (tabella) {
          Tabella.WELLNESS -> id.take(10)
          Tabella.EVENTI, Tabella.ATTIVITA -> PyJson.str(o.get("start_date_local"))?.take(10)
        }
    if (data == null || data.length != 10) return null
    // NOTE specchio di una seduta su Garmin: il coach la cancella il giorno dopo, l'app la tiene
    // per mostrare nel calendario e nella settimana cosa era pianificato.
    val conserva =
        tabella == Tabella.EVENTI &&
            PyJson.str(o.get("external_id"))?.startsWith(Allenamenti.PREFISSO_SPECCHIO) == true
    // toString() di Gson riscrive i numeri con il testo originale (56 resta 56, 56.0 resta 56.0)
    return RigaCache(id, data, o.toString(), conserva)
  }
}
