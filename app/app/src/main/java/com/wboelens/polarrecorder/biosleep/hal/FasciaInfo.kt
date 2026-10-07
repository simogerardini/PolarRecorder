package com.wboelens.polarrecorder.biosleep.hal

/** Cosa ci si aspetta da una fascia prima (o senza) una notte registrata: per i badge. */
data class PrevisioneFascia(
    /** true = RR affidabili; false = niente HRV; null = da verificare nei primi minuti. */
    val hrv: Boolean?,
    val movimento: Boolean,
    /** Gia' verificata su una notte vera (rapporto fascia della stessa fascia). */
    val verificata: Boolean,
)

object InfoFascia {
  const val AVVISO_SENZA_HRV =
      "Questa fascia non misura l'HRV in modo affidabile: avrai durata e fasi del sonno stimate dalla FC, " +
          "ma niente HRV, prontezza e baseline."
  const val CONSIGLIO =
      "Per l'HRV usa una fascia cardio da petto che trasmette gli intervalli tra i battiti, come la Polar H10."

  /**
   * Il rapporto dell'ultima notte vale solo se e' della stessa fascia (stesso nome): allora decide
   * lui. Altrimenti si stima dal nome: Polar e fasce da petto note -> HRV da verificare o si', ottica
   * -> no. Il movimento c'e' solo sulla Polar H10 (driver Polar con accelerometro).
   */
  fun prevedi(nome: String, tipo: TipoFascia, ultimo: RapportoFascia?): PrevisioneFascia {
    val movimento = nome.startsWith("Polar H10", ignoreCase = true)
    if (ultimo != null && ultimo.nome == nome && ultimo.statoRr != StatoRr.IN_VALUTAZIONE) {
      return PrevisioneFascia(ultimo.statoRr == StatoRr.AFFIDABILI, movimento || ultimo.capacita.acc, true)
    }
    val hrv =
        when {
          nome.startsWith("Polar", ignoreCase = true) -> true
          tipo == TipoFascia.OTTICA -> false
          else -> null // da petto di altre marche o sconosciuta: lo dicono i primi 5 minuti di dati
        }
    return PrevisioneFascia(hrv, movimento, false)
  }
}
