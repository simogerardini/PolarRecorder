package com.wboelens.polarrecorder.biosleep.riepilogo

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wboelens.polarrecorder.biosleep.readiness.PyJson

data class Decisione(val codice: String?, val etichetta: String?, val motivo: String?)

data class BiometriaCoach(
    val ok: Boolean?,
    val banda: String?,
    val azione: String?,
    val nota: String?,
    val hrv7gg: Double?,
    val hrvBaseline: Double?,
    val pctVsBaseline: Double?,
    val zLn: Double?,
    val rangeMs: Pair<Double, Double>?,
    val direzione: String?,
    val ggSottoRange: Int?,
    val fc7gg: Double?,
    val fcBaseline: Double?,
    val fcDelta: Double?,
    val fcAllarme: Boolean?,
    val ggRitardo: Int?,
)

data class FormaCoach(
    val ctl: Double?,
    val atl: Double?,
    val tsb: Double?,
    val formPct: Double?,
    val zona: String?,
    val zonaAttesa: String?,
    val tsbObiettivo: Double?,
    val fascia: Pair<Double, Double>?,
    val tsbDomenica: Double?,
    val ramp: Double?,
    val rampMax: Double?,
)

/** Una disciplina in una finestra (7 o 28 giorni): ore, quota sul totale, quota obiettivo di fase. */
data class QuotaDisciplina(val ore: Double?, val pct: Double?, val targetPct: Double?)

data class Intensita(val pctFacile: Double?, val pctIntenso: Double?)

data class Volume(
    val targetH: Double?,
    val sostenibileH: Double?,
    val tettoH: Double?,
    val fatteH: Double?,
    val restanoH: Double?,
    val giorni: Int?,
)

data class Carico(val fatti: Double?, val tetto: Double?, val sostenibile: Double?, val calendario: Double?)

/** Il riepilogo del mattino che il coach pubblica come NOTE su Intervals.icu (schema v1). */
data class Riepilogo(
    val versione: Int?,
    val data: String,
    val ora: String?,
    val titoloNotifica: String?,
    val testoNotifica: String?,
    val decisione: Decisione,
    val fase: String?,
    val gara: String?,
    val biometria: BiometriaCoach?,
    val forma: FormaCoach?,
    /** "7gg" e "28gg" -> disciplina (nuoto, bici, corsa, palestra) -> quota. */
    val discipline: Map<String, Map<String, QuotaDisciplina>>,
    val intensita: Intensita?,
    val volume: Volume?,
    val carico: Carico?,
    val oggi: String?,
    val avvisi: String?,
    val nonScritte: List<String>,
    val testo: String?,
)

/**
 * Lettura della NOTE riepilogo: category NOTE, external_id coach:Riepilogo:<data>, JSON nel tag
 * [[riepilogo_coach:{...}]] della description. Ogni campo puo' mancare o essere null: il modello
 * lo riporta come null e la schermata nasconde solo quell'elemento.
 */
object RiepilogoParser {
  const val PREFISSO = "coach:Riepilogo:"
  const val VERSIONE_GESTITA = 1

  // Come indicato dal coach: modalita' DOTALL, greedy fino all'ultimo "}]]".
  private val TAG = Regex("""\[\[riepilogo_coach:(\{.*\})\]\]""", RegexOption.DOT_MATCHES_ALL)

  /** Il JSON del tag, o null se manca o non e' un oggetto JSON valido. */
  fun json(descrizione: String?): JsonObject? {
    val testo = TAG.find(descrizione ?: return null)?.groupValues?.get(1) ?: return null
    return runCatching { JsonParser.parseString(testo).asJsonObject }.getOrNull()
  }

  fun eRiepilogo(evento: JsonObject): Boolean =
      PyJson.str(evento.get("category")) == "NOTE" &&
          PyJson.str(evento.get("external_id"))?.startsWith(PREFISSO) == true

  /** Il riepilogo della data indicata tra gli eventi letti da Intervals.icu (JSON grezzo). */
  fun trova(eventi: List<JsonObject>, data: String): JsonObject? =
      eventi
          .asSequence()
          .filter { eRiepilogo(it) && PyJson.str(it.get("external_id")) == PREFISSO + data }
          .mapNotNull { json(PyJson.str(it.get("description"))) }
          .firstOrNull { PyJson.str(it.get("data")) == data }

  private fun ogg(o: JsonObject?, k: String): JsonObject? = o?.get(k)?.takeIf { it.isJsonObject }?.asJsonObject

  private fun num(o: JsonObject?, k: String): Double? = PyJson.num(o?.get(k))?.v

  private fun int(o: JsonObject?, k: String): Int? = num(o, k)?.toInt()

  private fun str(o: JsonObject?, k: String): String? =
      o?.get(k)?.takeIf { it.isJsonPrimitive }?.let { PyJson.str(it) }

  private fun bool(o: JsonObject?, k: String): Boolean? =
      o?.get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

  private fun coppia(o: JsonObject?, k: String): Pair<Double, Double>? {
    val a = o?.get(k)?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
    if (a.size() < 2) return null
    val x = PyJson.num(a[0])?.v ?: return null
    val y = PyJson.num(a[1])?.v ?: return null
    return minOf(x, y) to maxOf(x, y)
  }

  /** Un elemento di non_scritte: testo, oppure i valori semplici di un oggetto uniti da " · ". */
  private fun voce(e: JsonElement): String? =
      when {
        e.isJsonPrimitive -> PyJson.str(e)
        e.isJsonObject ->
            e.asJsonObject.entrySet()
                .mapNotNull { (_, v) -> v.takeIf { it.isJsonPrimitive }?.let { PyJson.str(it) } }
                .filter { it.isNotBlank() }
                .joinToString(" · ")
                .ifEmpty { null }
        else -> null
      }

  private fun discipline(d: JsonObject?): Map<String, Map<String, QuotaDisciplina>> {
    if (d == null) return emptyMap()
    // target_pct letto dentro ogni disciplina della finestra; in alternativa da discipline.target_pct
    val targetComune = ogg(d, "target_pct")
    return listOf("7gg", "28gg").associateWith { finestra ->
      val f = ogg(d, finestra)
      listOf("nuoto", "bici", "corsa", "palestra").mapNotNull { sport ->
        val q = ogg(f, sport) ?: return@mapNotNull null
        sport to QuotaDisciplina(num(q, "h"), num(q, "pct_h"), num(q, "target_pct") ?: num(targetComune, sport))
      }.toMap()
    }.filterValues { it.isNotEmpty() }
  }

  fun leggi(j: JsonObject): Riepilogo? {
    val data = str(j, "data")?.takeIf { it.length == 10 } ?: return null
    val notifica = ogg(j, "notifica")
    val dec = ogg(j, "decisione")
    val per = ogg(j, "periodizzazione")
    val gara = per?.get("gara")?.let { g ->
      when {
        g.isJsonPrimitive -> PyJson.str(g)
        g.isJsonObject -> str(g.asJsonObject, "nome") ?: str(g.asJsonObject, "data")
        else -> null
      }
    }
    val b = ogg(j, "biometria")
    val f = ogg(j, "forma")
    val i = ogg(j, "intensita")
    val v = ogg(j, "volume")
    val c = ogg(j, "carico")
    return Riepilogo(
        versione = int(j, "v"),
        data = data,
        ora = str(j, "ora"),
        titoloNotifica = str(notifica, "titolo"),
        testoNotifica = str(notifica, "testo"),
        decisione = Decisione(str(dec, "codice"), str(dec, "etichetta"), str(dec, "motivo")),
        fase = str(per, "fase"),
        gara = gara,
        biometria =
            b?.let {
              BiometriaCoach(
                  ok = bool(it, "ok"), banda = str(it, "banda"), azione = str(it, "azione"), nota = str(it, "nota"),
                  hrv7gg = num(it, "hrv_7gg"), hrvBaseline = num(it, "hrv_baseline"),
                  pctVsBaseline = num(it, "pct_vs_baseline"), zLn = num(it, "z_ln"), rangeMs = coppia(it, "range_ms"),
                  direzione = str(it, "direzione_7v7"), ggSottoRange = int(it, "gg_sotto_range"),
                  fc7gg = num(it, "fc_7gg"), fcBaseline = num(it, "fc_baseline"), fcDelta = num(it, "fc_delta"),
                  fcAllarme = bool(it, "fc_allarme"), ggRitardo = int(it, "gg_ritardo"))
            },
        forma =
            f?.let {
              FormaCoach(
                  ctl = num(it, "ctl"), atl = num(it, "atl"), tsb = num(it, "tsb"), formPct = num(it, "form_pct"),
                  zona = str(it, "zona"), zonaAttesa = str(it, "zona_attesa"), tsbObiettivo = num(it, "tsb_obiettivo"),
                  fascia = coppia(it, "fascia"), tsbDomenica = num(it, "tsb_domenica"), ramp = num(it, "ramp"),
                  rampMax = num(it, "ramp_max"))
            },
        discipline = discipline(ogg(j, "discipline")),
        intensita = i?.let { Intensita(num(it, "pct_facile"), num(it, "pct_intenso")) },
        volume =
            v?.let {
              Volume(num(it, "target_h"), num(it, "sostenibile_h"), num(it, "tetto_h"), num(it, "fatte_h"),
                  num(it, "restano_h"), int(it, "giorni"))
            },
        carico = c?.let { Carico(num(it, "fatti"), num(it, "tetto"), num(it, "sostenibile"), num(it, "calendario")) },
        oggi = str(j, "oggi")?.takeIf { it.isNotBlank() },
        avvisi = str(j, "avvisi")?.takeIf { it.isNotBlank() },
        nonScritte =
            j.get("non_scritte")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { voce(it) }.orEmpty(),
        testo = str(j, "testo")?.takeIf { it.isNotBlank() },
    )
  }

  fun leggi(testoJson: String): Riepilogo? =
      runCatching { JsonParser.parseString(testoJson).asJsonObject }.getOrNull()?.let { leggi(it) }
}

/** Cosa fa il controllo in background a ogni giro (logica pura, testata). */
enum class Passo { NOTIFICA, GIA_NOTIFICATO, RIPROVA, SCADUTO }

object Attesa {
  const val INTERVALLO_MIN = 10L
  const val DURATA_MS = 2 * 60 * 60 * 1000L

  fun passo(trovato: Boolean, giaNotificato: Boolean, adessoMs: Long, scadenzaMs: Long): Passo =
      when {
        trovato && giaNotificato -> Passo.GIA_NOTIFICATO
        trovato -> Passo.NOTIFICA
        adessoMs >= scadenzaMs -> Passo.SCADUTO
        else -> Passo.RIPROVA
      }
}
