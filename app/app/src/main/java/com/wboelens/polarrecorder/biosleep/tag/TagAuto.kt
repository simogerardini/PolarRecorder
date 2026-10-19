package com.wboelens.polarrecorder.biosleep.tag

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wboelens.polarrecorder.biosleep.SleepDb
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Proposta del cervello (riepilogo "tag_automatici"): notte confondente riconosciuta dai dati. */
data class TagAutomatico(val data: String, val tag: String, val motivo: String, val valori: Map<String, String>) {
  /** Forma di "tag_auto_rifiutati" nel configJson. */
  val id: String get() = "$data:$tag"
}

/**
 * Tag automatici (cervello 2026.10.19-precisione1). Valgono gia' per la baseline nel cervello e
 * non limitano l'allenamento; l'app li propone sulla notte: "Conferma" = tag di giorno normale,
 * "Non era cosi'" = nascosto qui e passato al cervello in "tag_auto_rifiutati".
 */
object TagAuto {
  private const val PREFS = "biosleep_tag_auto"
  private const val K_RIFIUTATI = "rifiutati"
  private const val CONSERVA_GG = 120L
  private val ORA = DateTimeFormatter.ofPattern("HH:mm")
  private val _versione = MutableStateFlow(0)
  val versione: StateFlow<Int> = _versione.asStateFlow()

  /** Il campo "tag_automatici" del riepilogo; voci incomplete o tag fuori vocabolario scartati. */
  fun leggi(e: JsonElement?): List<TagAutomatico> =
      (e?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()).mapNotNull { v ->
        val o = v.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        fun s(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString
        val data = s("data")?.take(10) ?: return@mapNotNull null
        val tag = s("tag")?.takeIf { it in Vocabolario.GIORNO } ?: return@mapNotNull null
        val valori = o.get("valori")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()
            ?.filter { it.value.isJsonPrimitive }?.associate { it.key to it.value.asString }.orEmpty()
        TagAutomatico(data, tag, s("motivo").orEmpty(), valori)
      }

  /** Proposte dall'ultimo riepilogo del cervello (al massimo di 8 giorni fa). */
  fun dalRiepilogo(cartella: File, oggi: LocalDate = LocalDate.now()): List<TagAutomatico> {
    val dal = oggi.minusDays(7).toString()
    val f = cartella.listFiles { x -> x.name.startsWith("riepilogo_") && x.name.endsWith(".json") }
        ?.filter { it.name.removePrefix("riepilogo_").removeSuffix(".json") >= dal }
        ?.maxByOrNull { it.name } ?: return emptyList()
    return runCatching { leggi(JsonParser.parseString(f.readText()).asJsonObject.get("tag_automatici")) }.getOrDefault(emptyList())
  }

  /** Frase italiana del motivo (tradotta con tr); null per un motivo nuovo del cervello. */
  fun testo(t: TagAutomatico): String? =
      when (t.motivo) {
        "tag_auto_notte_tropicale" -> t.valori["minima"]?.let { "Notte tropicale: minima $it °C" }
        "tag_auto_viaggio" -> t.valori["da"]?.let { da -> t.valori["a"]?.let { a -> "Fuso orario cambiato: da $da a $a" } }
        "tag_auto_sonno_tardivo" -> t.valori["ritardo"]?.toIntOrNull()?.let { "Sonno iniziato $it minuti dopo il solito" }
        else -> null
      }

  fun rifiutati(context: Context): Set<String> =
      context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(K_RIFIUTATI, emptySet()).orEmpty()

  /** "Non era cosi'": nascosto in app e passato al cervello. */
  fun rifiuta(context: Context, t: TagAutomatico) {
    val oggi = LocalDate.now()
    val tenuti = rifiutati(context).filter { (it.substringBefore(":").let { d -> runCatching { LocalDate.parse(d) }.getOrNull() } ?: oggi) >= oggi.minusDays(CONSERVA_GG) }
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putStringSet(K_RIFIUTATI, (tenuti + t.id).toSet()).apply()
    _versione.update { it + 1 }
  }

  /** "Conferma": diventa un tag di giorno normale (TagDb). */
  fun conferma(context: Context, t: TagAutomatico) {
    val db = TagDb.get(context)
    db.impostaGiorno(t.data, db.giorno(t.data) + t.tag)
  }

  /** Proposte da mostrare sulla notte del giorno [data]: non rifiutate e non gia' tra i tag. */
  fun daMostrare(proposte: List<TagAutomatico>, data: String, rifiutati: Set<String>, giaScelti: Set<String>): List<TagAutomatico> =
      proposte.filter { it.data == data && it.id !in rifiutati && it.tag !in giaScelti }.distinctBy { it.id }

  /** configJson "tag_auto_rifiutati": ["YYYY-MM-DD:chiave"]. */
  fun perCervello(context: Context): List<String> = rifiutati(context).sorted()

  /**
   * configJson "notti": {"YYYY-MM-DD": {"inizio": "HH:MM"}}: inizio del sonno (o della registrazione
   * se il sonno non e' stimato) delle notti degli ultimi 28 giorni; data = giorno del risveglio.
   */
  fun notti(context: Context, oggi: LocalDate = LocalDate.now()): JsonObject? {
    val z = ZoneId.systemDefault()
    val dal = oggi.minusDays(28)
    val righe = runCatching { SleepDb.get(context).listNights() }.getOrDefault(emptyList())
        .mapNotNull { n ->
          val fine = n.summary.endMs
          val d = Vocabolario.mattinaDaFine(fine)
          if (d.isBefore(dal) || d.isAfter(oggi)) return@mapNotNull null
          val inizio = (n.stages?.sleepOnsetMs ?: n.summary.startMs)
          Triple(d.toString(), fine, Instant.ofEpochMilli(inizio).atZone(z).toLocalTime().format(ORA))
        }
        .sortedBy { it.second } // a parita' di giorno vale l'ultima notte
    if (righe.isEmpty()) return null
    return JsonObject().apply { for ((d, _, h) in righe) add(d, JsonObject().apply { addProperty("inizio", h) }) }
  }
}
