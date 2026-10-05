package com.wboelens.polarrecorder.biosleep.riepilogo

import android.content.Intent
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wboelens.polarrecorder.biosleep.readiness.PyJson
import kotlinx.coroutines.flow.MutableStateFlow

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

/** Una seduta del piano. */
data class SedutaPiano(
    val data: String,
    val nome: String,
    val tipo: String?,
    val durataMin: Int?,
    val qualita: Boolean,
    val declassata: Boolean,
    val descrizione: String?,
)

/**
 * Alta intensita' della settimana come la conta il coach (limita_alta_intensita): minuti di
 * lavoro intenso su minuti di bici + corsa, con il tetto della fase deciso dal coach (20% nel
 * ciclo continuo 80/20, 10% in preparazione gara) e la regola che lo spiega. L'app non fissa
 * nessun tetto: se manca, la linea non si disegna.
 */
data class AltaIntensita(val minuti: Int, val suMinuti: Int, val tettoPct: Double?, val regola: String? = null) {
  val pct: Double get() = if (suMinuti > 0) minuti * 100.0 / suMinuti else 0.0

  /** Rosso solo con un superamento vero del tetto. */
  val sopraTetto: Boolean get() = tettoPct != null && pct > tettoPct
}

/**
 * Riepilogo del coach (riepilogo_<data>.json del cervello). Contiene i campi del piano (tipo,
 * fase, banda, motivi, ore_target, sedute) e, quando il cervello li scrive, i blocchi completi
 * della vecchia schermata (decisione, biometria, forma, discipline, intensita, volume, carico,
 * oggi, avvisi, non_scritte). Ogni campo puo' mancare: la schermata completa con i dati locali
 * dove puo' (stesse formule del coach) e nasconde il resto. "testo" si legge ma non si mostra.
 */
data class Riepilogo(
    val versione: Int?,
    val data: String,
    val tipo: String?,
    val esito: String?,
    val ora: String?,
    val decisione: Decisione,
    val fase: String?,
    val gara: String?,
    val banda: String?,
    val motivi: List<String>,
    val oreTarget: Double?,
    val biometria: BiometriaCoach?,
    val forma: FormaCoach?,
    val discipline: Map<String, Map<String, QuotaDisciplina>>,
    val intensita: Intensita?,
    val altaIntensita: AltaIntensita?,
    val volume: Volume?,
    val carico: Carico?,
    val oggi: String?,
    val avvisi: String?,
    val nonScritte: List<String>,
    val sedute: List<SedutaPiano>,
    val testo: String?,
) {
  val settimanale: Boolean get() = tipo == "settimanale"
}

object RiepilogoParser {
  // Riga del testo del coach: "Alta intensita': 18' su 525' bici+corsa (3.4%, tetto 10%)"
  private val RIGA_ALTA = Regex("""Alta intensita'?: (\d+)' su (\d+)'.*?tetto (\d+(?:\.\d+)?)%""")

  /**
   * Preferisce il campo strutturato "intensita" {alta_min, base_min, tetto_pct} se il cervello lo
   * scrive; altrimenti legge la riga del testo (stesso modulo, formato stabile).
   */
  private fun altaIntensita(j: JsonObject, testo: String?): AltaIntensita? {
    val riga = testo?.let { RIGA_ALTA.find(it) }
    j.get("intensita")?.takeIf { it.isJsonObject }?.asJsonObject?.let { o ->
      val alta = PyJson.num(o.get("alta_min"))?.v
      val base = PyJson.num(o.get("base_min"))?.v
      if (alta != null && base != null) {
        // tetto: quello del campo; nei riepiloghi senza tetto_pct, quello della riga del testo
        val tetto = PyJson.num(o.get("tetto_pct"))?.v ?: riga?.groupValues?.get(3)?.toDouble()
        val regola = o.get("regola")?.takeIf { it.isJsonPrimitive }?.let { PyJson.str(it) }?.takeIf { it.isNotBlank() }
        return AltaIntensita(alta.toInt(), base.toInt(), tetto, regola)
      }
    }
    val m = riga ?: return null
    return AltaIntensita(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toDouble())
  }

  /** La descrizione delle sedute e' nella sintassi di Intervals.icu: via tag e "intensity=". */
  fun descrizioneLeggibile(d: String?): String? =
      d?.lines()
          ?.map { it.replace(Regex("""\[\[[^\]]*\]\]"""), "").replace(Regex("""\s*intensity=\w+"""), "").trim() }
          ?.filter { it.isNotEmpty() }
          ?.joinToString("\n") { it.removePrefix("- ").trim() }
          ?.ifEmpty { null }

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

  private fun lista(o: JsonObject, k: String): List<JsonElement> =
      o.get(k)?.takeIf { it.isJsonArray }?.asJsonArray?.toList() ?: emptyList()

  fun leggi(j: JsonObject): Riepilogo? {
    val data = str(j, "data")?.takeIf { it.length == 10 } ?: return null
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
    val testo = str(j, "testo")?.takeIf { it.isNotBlank() }
    val sedute =
        lista(j, "sedute").mapNotNull { el ->
          val s = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
          SedutaPiano(
              data = str(s, "data") ?: return@mapNotNull null,
              nome = str(s, "nome") ?: "Seduta",
              tipo = str(s, "tipo"),
              durataMin = num(s, "durata_min")?.toInt(),
              qualita = bool(s, "qualita") == true,
              declassata = bool(s, "declassata") == true,
              descrizione = descrizioneLeggibile(str(s, "descrizione")),
          )
        }
    return Riepilogo(
        versione = int(j, "v"),
        data = data,
        tipo = str(j, "tipo"),
        esito = str(j, "esito"),
        ora = str(j, "ora"),
        decisione = Decisione(str(dec, "codice"), str(dec, "etichetta"), str(dec, "motivo")),
        fase = str(j, "fase") ?: str(per, "fase"),
        gara = gara,
        banda = str(j, "banda") ?: str(b, "banda"),
        motivi = lista(j, "motivi").mapNotNull { m -> m.takeIf { it.isJsonPrimitive }?.let { PyJson.str(it) } }.filter { it.isNotBlank() },
        oreTarget = num(j, "ore_target") ?: num(v, "target_h"),
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
        intensita = i?.let { Intensita(num(it, "pct_facile"), num(it, "pct_intenso")) }?.takeIf { it.pctFacile != null || it.pctIntenso != null },
        altaIntensita = altaIntensita(j, testo),
        volume =
            v?.let {
              Volume(num(it, "target_h"), num(it, "sostenibile_h"), num(it, "tetto_h"), num(it, "fatte_h"),
                  num(it, "restano_h"), int(it, "giorni"))
            },
        carico = c?.let { Carico(num(it, "fatti"), num(it, "tetto"), num(it, "sostenibile"), num(it, "calendario")) },
        oggi = str(j, "oggi")?.takeIf { it.isNotBlank() },
        avvisi = str(j, "avvisi")?.takeIf { it.isNotBlank() },
        nonScritte = lista(j, "non_scritte").mapNotNull { voce(it) },
        sedute = sedute.sortedBy { it.data },
        testo = testo,
    )
  }

  fun leggi(testo: String): Riepilogo? =
      runCatching { JsonParser.parseString(testo).asJsonObject }.getOrNull()?.let { leggi(it) }
}

/**
 * Il tocco su una notifica del coach arriva a MainActivity: la rotta da aprire passa da qui alla
 * navigazione. "Piano pronto" -> riepilogo/<data>; "Com'e' andata la notte?" -> tag/<data>.
 */
object RiepilogoLink {
  const val EXTRA_RIEPILOGO = "biosleep_riepilogo_data"
  const val EXTRA_ROTTA = "biosleep_rotta"
  val richiesta = MutableStateFlow<String?>(null)

  fun daIntent(intent: Intent?) {
    intent ?: return
    val rotta =
        intent.getStringExtra(EXTRA_ROTTA) ?: intent.getStringExtra(EXTRA_RIEPILOGO)?.let { "riepilogo/$it" } ?: return
    // non riaprire la schermata a ogni rotazione
    intent.removeExtra(EXTRA_ROTTA)
    intent.removeExtra(EXTRA_RIEPILOGO)
    richiesta.value = rotta
  }
}
