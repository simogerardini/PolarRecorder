package com.wboelens.polarrecorder.biosleep.cervello

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.LocalDate

/** Dati dello sweat test (kg, minuti, mg/L) come li vuole cervello.registra_sweat. */
data class DatiSweat(
    val p1: Double,
    val p2: Double,
    val b1: Double,
    val b2: Double,
    val urine: Double,
    val minuti: Double,
    val sodioMgL: Double?,
) {
  /** Stessa formula del cervello (detp.sweat_rate): ((P1-P2) + (B1-B2) - U) / ore. */
  val litriOra: Double get() = ((p1 - p2) + (b1 - b2) - urine) / (minuti / 60)

  /** null = plausibile come per il cervello (0,2-4 L/h, almeno 30 minuti). */
  fun problema(): String? =
      when {
        minuti < 30 -> "la durata deve essere di almeno 30 minuti"
        litriOra !in 0.2..4.0 -> "sudorazione di ${"%.2f".format(litriOra)} L/h non plausibile: controlla pesi e borraccia"
        sodioMgL != null && sodioMgL !in 100.0..3000.0 -> "sodio tra 100 e 3000 mg/L"
        else -> null
      }

  fun json(): JsonObject =
      JsonObject().apply {
        addProperty("p1", p1)
        addProperty("p2", p2)
        addProperty("b1", b1)
        addProperty("b2", b2)
        addProperty("urine", urine)
        addProperty("minuti", minuti)
        sodioMgL?.let { addProperty("sodio_mg_l", it) }
      }
}

data class EsitoSweat(val esito: String, val litriOra: Double?, val errore: String?) {
  companion object {
    fun da(testo: String): EsitoSweat =
        try {
          val o = JsonParser.parseString(testo).asJsonObject
          EsitoSweat(
              o.get("esito")?.asString ?: "errore",
              o.get("litri_h")?.takeIf { it.isJsonPrimitive }?.asDouble,
              o.get("errore")?.takeIf { it.isJsonPrimitive }?.asString)
        } catch (e: RuntimeException) {
          EsitoSweat("errore", null, "Risposta illeggibile: ${e.message}")
        }
  }
}

object SweatTest {
  private val RIGA = Regex("""DETP:\s*sweat test il (\d{4}-\d{2}-\d{2})""", RegexOption.IGNORE_CASE)

  /** "kg" con la virgola o il punto. */
  fun numero(t: String): Double? = t.trim().replace(',', '.').toDoubleOrNull()

  /** Data dello sweat test piu' recente tra i motivi dei riepiloghi. */
  fun data(motivi: List<String>): LocalDate? =
      motivi.mapNotNull { RIGA.find(it)?.groupValues?.get(1) }.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.maxOrNull()

  /** Card visibile dal giorno del test in poi, finche' i dati di quel test non sono salvati (14 giorni al massimo). */
  fun daChiedere(test: LocalDate?, registrato: LocalDate?, oggi: LocalDate): Boolean =
      test != null && oggi >= test && oggi <= test.plusDays(14) && registrato != test
}
