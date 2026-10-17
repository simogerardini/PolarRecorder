package com.wboelens.polarrecorder.biosleep.hal

/** Driver con cui leggere una fascia. */
enum class TipoDriver { POLAR, GATT_180D }

/** Dove si indossa: deducibile dal nome per i modelli noti, altrimenti dai dati. */
enum class TipoFascia { PETTO, OTTICA, SCONOSCIUTO }

/** Cosa sa fare una fascia, rilevato a runtime. rr = null finche' non arrivano dati. */
data class Capacita(
    val driver: TipoDriver,
    val hr: Boolean,
    val rr: Boolean?,
    val acc: Boolean,
    val tipo: TipoFascia,
    val modello: String = "",
    val produttore: String = "",
    val firmware: String = "",
)

/** Un dispositivo trovato in scansione, cosi' come lo vede la Parte 3. */
data class FasciaTrovata(
    val id: String,
    val nome: String,
    val tipo: TipoFascia,
    val catena: List<TipoDriver>,
    val rssi: Int?,
)

/**
 * Sceglie i driver per un dispositivo, in ordine di tentativo:
 *  - Polar H10 e altre Polar -> driver Polar (HR+RR, e ACC sulla H10), poi GATT come ripiego;
 *  - qualunque altro dispositivo che espone il servizio 0x180D -> driver GATT;
 *  - altrimenti non e' una fascia cardio.
 */
object DriverRegistry {
  const val UUID_HR_SERVICE = "0000180d-0000-1000-8000-00805f9b34fb"

  // Bracciali ottici: CooSpo H808S e HW7xx/HW9 sono da braccio (prima di "h8" delle fasce da petto)
  private val OTTICHE = listOf("verity", "oh1", "polar sense", "coros", "scosche", "rhythm", "whoop", "armband", "amazfit",
      "h808", "hw7", "hw8", "hw9", "hw10")

  /**
   * Nomi tipici dei sensori cardio: servono per le fasce che dichiarano il servizio 0x180D solo
   * dopo il collegamento e non nel segnale di ricerca (es. alcuni bracciali CooSpo). Il servizio si
   * verifica comunque al collegamento (GattHrDriver): un dispositivo senza 0x180D viene rifiutato.
   */
  private val NOMI_CARDIO = listOf("coospo", "heart", "hrm", "hr-", "hr ", " hr", "tickr", "wahoo", "garmin hr", "magene",
      "xoss", "moofit", "igpsport", "suunto", "decathlon", "kalenji", "geonaute", "scosche", "rhythm", "verity", "coros",
      "h808", "hw7", "hw8", "hw9", "hw10", "h6", "h7", "h9", "808s", "pulse", "puls", "cardio")
  private val PETTO = listOf("polar h", "hrm", "tickr", "wahoo", "coospo h", "h6", "h7", "h8", "h9", "h10", "magene", "xoss", "suunto", "dual", "stryd")

  fun catena(nome: String, serviziPubblicizzati: Set<String>): List<TipoDriver> {
    val n = nome.lowercase()
    val haHr = serviziPubblicizzati.any { it.lowercase() == UUID_HR_SERVICE }
    return when {
      n.startsWith("polar") -> listOf(TipoDriver.POLAR, TipoDriver.GATT_180D)
      haHr -> listOf(TipoDriver.GATT_180D)
      else -> emptyList()
    }
  }

  /** Il dispositivo trovato in ricerca va proposto come fascia (driver GATT)? */
  fun candidataGatt(nome: String, serviziPubblicizzati: Set<String>): Boolean {
    if (nome.isBlank() || nome.startsWith("Polar", ignoreCase = true)) return false
    if (serviziPubblicizzati.any { it.lowercase() == UUID_HR_SERVICE }) return true
    val n = " ${nome.lowercase()} "
    return NOMI_CARDIO.any { n.contains(it) }
  }

  fun tipo(nome: String): TipoFascia {
    val n = nome.lowercase()
    return when {
      OTTICHE.any { n.contains(it) } -> TipoFascia.OTTICA // prima: "COROS HR" contiene anche "hr"
      PETTO.any { n.contains(it) } -> TipoFascia.PETTO
      else -> TipoFascia.SCONOSCIUTO
    }
  }

  /** Solo la H10 ha l'accelerometro letto dall'app. */
  fun haAcc(nome: String, driver: TipoDriver) = driver == TipoDriver.POLAR && nome.lowercase().startsWith("polar h10")
}
