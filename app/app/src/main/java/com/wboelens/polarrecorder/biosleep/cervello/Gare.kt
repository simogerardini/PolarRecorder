package com.wboelens.polarrecorder.biosleep.cervello

import com.google.gson.JsonParser
import java.time.LocalDate

/** Una gara futura come la vede il coach (cervello.gare). */
data class Gara(
    val id: String,
    val nome: String,
    val data: LocalDate,
    val priorita: String,
    /** "sprint" | "olimpico" | "70.3" | "full"; null = non riconosciuta (gara creata altrove). */
    val distanza: String?,
    val settimane: Int?,
    /** La gara che detta la preparazione. */
    val obiettivo: Boolean,
    /** Prevista con caldo: il coach prepara l'acclimatazione (DETP). */
    val calda: Boolean = false,
)

data class EsitoGare(val esito: String, val gare: List<Gara>, val errore: String?)

/** Esito di salva_gara / elimina_gara. */
data class EsitoGara(val esito: String, val id: String?, val ripianifica: Boolean, val errore: String?)

object Gare {
  const val OK = "ok"
  const val NON_VALIDI = "valori_non_validi"
  const val PERMESSO_MANCANTE = "permesso_mancante"
  const val NON_TROVATA = "non_trovata"
  const val MESI_MAX = 18L

  /** Chiave per il cervello -> etichetta. */
  val DISTANZE = listOf("sprint" to "Sprint", "olimpico" to "Olimpico", "70.3" to "70.3", "full" to "Full")

  val PRIORITA =
      listOf(
          "A" to "Obiettivo della stagione",
          "B" to "Importante",
          "C" to "Allenamento")

  // Suffisso che il cervello aggiunge al nome (DISTANZE_GARA): " — Triathlon sprint" ecc.
  private val SUFFISSO = Regex("""\s+—\s+Triathlon (sprint|olimpico|70\.3|full distance)\s*$""", RegexOption.IGNORE_CASE)

  /** Il nome come lo ha scritto l'utente, senza i suffissi " — Triathlon ..." del cervello. */
  fun nomeBase(nome: String): String {
    var n = nome
    while (SUFFISSO.containsMatchIn(n)) n = n.replace(SUFFISSO, "")
    return n.trim()
  }

  /**
   * Distanza per il modulo. Il cervello classifica una sprint come "olimpico" (si prepara come
   * l'olimpico), quindi per le gare create dall'app vale il suffisso del nome: riaprendo una
   * sprint il modulo mostra Sprint, e salvando non diventa olimpico.
   */
  fun distanzaPerModulo(g: Gara): String? {
    val m = Regex("""—\s+Triathlon (sprint|olimpico|70\.3|full distance)\s*$""", RegexOption.IGNORE_CASE).find(g.nome)
    return when (m?.groupValues?.get(1)?.lowercase()) {
      "sprint" -> "sprint"
      "olimpico" -> "olimpico"
      "70.3" -> "70.3"
      "full distance" -> "full"
      else -> g.distanza
    }
  }

  /** Stessi controlli di salva_gara: lista vuota = valido. */
  fun errori(nome: String, data: LocalDate?, priorita: String?, distanza: String?, oggi: LocalDate = LocalDate.now()): List<String> =
      buildList {
        if (nome.isBlank()) add("nome mancante")
        if (data == null || data < oggi || data > oggi.plusDays(MESI_MAX * 31)) add("data: da oggi a $MESI_MAX mesi")
        if (priorita !in setOf("A", "B", "C")) add("priorità A, B o C")
        if (distanza !in DISTANZE.map { it.first }) add("distanza mancante")
      }

  fun elenco(testo: String): EsitoGare =
      try {
        val o = JsonParser.parseString(testo).asJsonObject
        val gare =
            o.get("gare")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { el ->
              val g = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
              fun s(k: String) = g.get(k)?.takeIf { it.isJsonPrimitive }?.asString
              Gara(
                  id = s("id") ?: return@mapNotNull null,
                  nome = s("nome") ?: "Gara",
                  data = runCatching { LocalDate.parse(s("data")) }.getOrNull() ?: return@mapNotNull null,
                  priorita = s("priorita") ?: "C",
                  distanza = s("distanza"),
                  settimane = s("settimane")?.toDoubleOrNull()?.toInt(),
                  obiettivo = g.get("obiettivo")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true,
                  calda = g.get("calda")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true)
            }.orEmpty()
        EsitoGare(o.get("esito")?.asString ?: "errore", gare, o.get("errore")?.takeIf { it.isJsonPrimitive }?.asString)
      } catch (e: RuntimeException) {
        EsitoGare("errore", emptyList(), "Risposta illeggibile: ${e.message}")
      }

  fun esito(testo: String): EsitoGara =
      try {
        val o = JsonParser.parseString(testo).asJsonObject
        fun s(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString
        EsitoGara(
            s("esito") ?: "errore", s("id"),
            o.get("ripianifica")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true, s("errore"))
      } catch (e: RuntimeException) {
        EsitoGara("errore", null, false, "Risposta illeggibile: ${e.message}")
      }
}
