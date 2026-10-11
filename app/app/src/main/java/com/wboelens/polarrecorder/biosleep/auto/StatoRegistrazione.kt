package com.wboelens.polarrecorder.biosleep.auto

/**
 * "C'e' una registrazione in corso?" senza fidarsi del solo servizio vivo: il RecordingService puo'
 * restare in esecuzione anche senza registrare (per esempio collegato all'interfaccia), e allora
 * l'avvio automatico si fermava credendo che la notte fosse gia' partita.
 */
object StatoRegistrazione {
  /**
   * @param statoDalServizio lo stato reale letto dal servizio collegato; null se l'app non e'
   *   collegata al servizio (es. avviso della fascia con lo schermo spento)
   * @param servizioVivo il RecordingService e' in esecuzione
   * @param sessioniAperte sessioni nel database senza fine (una registrazione ne apre una)
   */
  fun inCorso(statoDalServizio: Boolean?, servizioVivo: Boolean, sessioniAperte: Int): Boolean =
      statoDalServizio ?: (servizioVivo && sessioniAperte > 0)
}
