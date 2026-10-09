package com.wboelens.polarrecorder.biosleep.cervello

import com.google.gson.JsonParser

/**
 * Risultato di cervello.pulisci_intervals (prova o conferma): eventi e campi creati dall'app su
 * Intervals.icu. [perTipo] (sedute, gare, pause) c'e' se il cervello lo manda; altrimenti solo il
 * totale.
 */
data class EsitoPulizia(
    val esito: String,
    val eventiDaEliminare: Int,
    val eventiEliminati: Int,
    val campiDaEliminare: Int,
    val campiEliminati: Int,
    val perTipo: Map<String, Int>?,
    val errore: String?,
) {
  val ok get() = esito == OK || esito == PROVA
  val completo get() = esito == OK && eventiEliminati == eventiDaEliminare && campiEliminati == campiDaEliminare

  companion object {
    const val PROVA = "prova"
    const val OK = "ok"
    const val PERMESSO_MANCANTE = "permesso_mancante"

    fun errore(msg: String) = EsitoPulizia("errore", 0, 0, 0, 0, null, msg)

    fun da(json: String): EsitoPulizia =
        try {
          val o = JsonParser.parseString(json).asJsonObject
          fun n(k: String) = o.get(k)?.let { if (it.isJsonArray) it.asJsonArray.size() else it.asInt } ?: 0
          val pt =
              o.get("per_tipo")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.associate { (k, v) -> k to v.asInt }
          EsitoPulizia(
              o.get("esito")?.asString ?: "errore",
              n("eventi_da_eliminare"),
              n("eventi_eliminati"),
              n("campi_da_eliminare"),
              n("campi_eliminati"),
              pt,
              o.get("errore")?.takeIf { !it.isJsonNull }?.asString)
        } catch (e: Exception) {
          errore("risposta illeggibile")
        }
  }
}
