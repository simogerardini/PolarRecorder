package com.wboelens.polarrecorder.biosleep.auto

/**
 * Condizioni dell'avvio automatico, in minuti dopo mezzogiorno (la fascia serale attraversa la
 * mezzanotte senza salti: 21:58 = 598, 01:29 = 809).
 *  - Orario: dalla fascia appresa, con [MARGINE_MIN] minuti di anticipo (si va a letto prima).
 *  - Energia: telefono in carica oppure batteria almeno al [BATTERIA_MIN]%: una notte di
 *    registrazione consuma pochi punti, la carica obbligatoria fermava troppe notti.
 */
object RegoleAvvio {
  const val MARGINE_MIN = 30
  const val BATTERIA_MIN = 40
  const val RICONTROLLO_MIN = 10

  fun inOrario(oraNoon: Int, daNoon: Int, aNoon: Int): Boolean = oraNoon in (daNoon - MARGINE_MIN)..aNoon

  fun energiaOk(inCarica: Boolean, batteriaPct: Int?): Boolean =
      inCarica || (batteriaPct != null && batteriaPct >= BATTERIA_MIN)

  /** Fra quanti minuti ricontrollare se oggi non si parte; null = fascia serale finita. */
  fun fraMinuti(oraNoon: Int, daNoon: Int, aNoon: Int): Int? =
      when {
        oraNoon < daNoon - MARGINE_MIN -> daNoon - MARGINE_MIN - oraNoon // troppo presto
        oraNoon <= aNoon -> RICONTROLLO_MIN // dentro la fascia: manca l'energia
        else -> null
      }
}
