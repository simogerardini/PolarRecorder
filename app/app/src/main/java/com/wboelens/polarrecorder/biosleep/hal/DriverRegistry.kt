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

  private val OTTICHE = listOf("verity", "oh1", "polar sense", "coros", "scosche", "rhythm", "whoop", "armband", "amazfit")
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
