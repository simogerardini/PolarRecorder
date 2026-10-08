package com.wboelens.polarrecorder.biosleep.cervello

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Ferie, malattia o infortunio su Intervals.icu (cervello.pause / salva_pausa / elimina_pausa). */
data class Pausa(
    val id: String,
    val dal: LocalDate,
    val al: LocalDate,
    /** "ferie" | "malattia" | "infortunio" */
    val tipo: String,
    val nota: String,
    /** false = inserita a mano su Intervals.icu. */
    val dallApp: Boolean,
) {
  fun contiene(d: LocalDate) = !d.isBefore(dal) && !d.isAfter(al)
}

data class EsitoPause(val esito: String, val pause: List<Pausa>, val errore: String?)

data class EsitoPausa(val esito: String, val id: String?, val ripianifica: Boolean, val errore: String?)

object Pause {
  const val OK = "ok"
  const val NON_VALIDI = "valori_non_validi"
  const val PERMESSO_MANCANTE = "permesso_mancante"
  const val NON_TROVATA = "non_trovata"
  const val GIORNI_MAX = 120

  val TIPI = listOf("ferie" to "Ferie", "malattia" to "Malattia", "infortunio" to "Infortunio")

  fun etichetta(tipo: String) = TIPI.firstOrNull { it.first == tipo }?.second ?: tipo

  fun elenco(testo: String): EsitoPause =
      try {
        val o = JsonParser.parseString(testo).asJsonObject
        val pause =
            o.get("pause")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { el ->
              val p = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
              fun s(k: String) = p.get(k)?.takeIf { it.isJsonPrimitive }?.asString
              val dal = runCatching { LocalDate.parse(s("dal")) }.getOrNull() ?: return@mapNotNull null
              val al = runCatching { LocalDate.parse(s("al")) }.getOrNull() ?: dal
              Pausa(
                  s("id") ?: return@mapNotNull null, dal, al, s("tipo") ?: "ferie", s("nota") ?: "",
                  p.get("dall_app")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: true)
            }.orEmpty()
        EsitoPause(o.get("esito")?.asString ?: "errore", pause, o.get("errore")?.takeIf { it.isJsonPrimitive }?.asString)
      } catch (e: RuntimeException) {
        EsitoPause("errore", emptyList(), "Risposta illeggibile: ${e.message}")
      }

  fun esito(testo: String): EsitoPausa =
      try {
        val o = JsonParser.parseString(testo).asJsonObject
        fun s(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString
        EsitoPausa(
            s("esito") ?: "errore", s("id"),
            o.get("ripianifica")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true, s("errore"))
      } catch (e: RuntimeException) {
        EsitoPausa("errore", null, false, "Risposta illeggibile: ${e.message}")
      }

  /** Stessi controlli di salva_pausa; lista vuota = valida. */
  fun errori(dal: LocalDate?, al: LocalDate?, tipo: String?, oggi: LocalDate = LocalDate.now()): List<String> = buildList {
    if (dal == null || al == null) add("date mancanti")
    else if (al.isBefore(dal)) add("la fine viene prima dell'inizio")
    else if (al.isBefore(oggi)) add("la pausa è già finita")
    else if (java.time.temporal.ChronoUnit.DAYS.between(dal, al) + 1 > GIORNI_MAX) add("al massimo $GIORNI_MAX giorni")
    if (tipo !in TIPI.map { it.first }) add("scegli il tipo")
  }
}

/** Calendario: regole comuni a pause, disponibilita' e gare aggiunte dal "+". */
object PianoCalendario {
  /** Domenica della settimana in corso. */
  fun domenica(oggi: LocalDate): LocalDate = oggi.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))

  /** L'intervallo tocca la settimana in corso, da oggi a domenica (i giorni passati non contano). */
  fun inSettimana(dal: LocalDate, al: LocalDate, oggi: LocalDate = LocalDate.now()): Boolean =
      !al.isBefore(oggi) && !dal.isAfter(domenica(oggi))

  /** Minuti per data, solo da oggi in poi, come li vuole il cervello in "disponibilita_date". */
  fun disponibilita(m: Map<String, Int>, oggi: LocalDate = LocalDate.now()): Map<String, Int> =
      m.filter { (d, min) -> min >= 0 && runCatching { !LocalDate.parse(d).isBefore(oggi) }.getOrDefault(false) }.toSortedMap()

  fun json(m: Map<String, Int>): JsonObject = JsonObject().apply { for ((d, min) in m) addProperty(d, min) }

  /** Badge sul giorno: "—" se non disponibile, altrimenti i minuti. */
  fun badge(minuti: Int?): String? = when (minuti) {
    null -> null
    0 -> "—"
    else -> "$minuti'"
  }

  val SCORCIATOIE = listOf(0, 30, 45, 60, 90)
}
