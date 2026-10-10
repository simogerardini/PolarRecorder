package com.wboelens.polarrecorder.biosleep.ponte

/** Un comando arrivato dal web (src/types/ponte.ts). */
data class Comando(val id: String, val tipo: String, val dati: Map<String, Any?>)

object Comandi {
  val TIPI_V1 = setOf("tag_giorno", "tag_seduta", "disponibilita_data", "aggiorna_ora")

  /** Dal JSON decifrato; null se manca l'id (comando inutilizzabile). */
  fun leggi(json: String): Comando? {
    val m = Json.leggi(json) as? Map<*, *> ?: return null
    val id = m["id"] as? String ?: return null
    @Suppress("UNCHECKED_CAST")
    val dati = (m["dati"] as? Map<String, Any?>) ?: emptyMap()
    return Comando(id, m["tipo"] as? String ?: "", dati)
  }

  /**
   * Applica un comando. aggiorna_ora lo gestisce il ponte stesso; gli altri li applica la Parte 3
   * (tag e disponibilita' vivono nei suoi archivi). Tipo sconosciuto o non ancora gestito: rifiutato.
   */
  fun applica(c: Comando, parte3: (Comando) -> EsitoComando?): EsitoComando =
      when {
        c.tipo == "aggiorna_ora" -> EsitoComando(c.id, true)
        c.tipo !in TIPI_V1 -> EsitoComando(c.id, false, "comando sconosciuto: ${c.tipo}")
        else -> parte3(c) ?: EsitoComando(c.id, false, "non ancora supportato dall'app")
      }
}
