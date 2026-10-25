package com.wboelens.polarrecorder.biosleep.readiness

import com.wboelens.polarrecorder.biosleep.intervals.CampiWellness
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** I campi di una riga wellness di Intervals.icu che servono alla serie BioSleep. */
data class WellnessBio(
    val id: String?,
    val date: String?,
    val rmssd: PyNum?,
    val avgHr: PyNum?,
    val quality: PyNum?,
    val sleepHours: PyNum?,
    /** Parte 11: NoctalixSource (null = notte scritta prima del campo = fascia). */
    val sorgente: PyNum? = null,
)

/**
 * Copia di biosleep_history_da_wellness (intervals_coach.py): stessa serie che il coach
 * passa alla baseline. FC a riposo = NoctalixAvgHR (media notturna, come Oura dal 25/09),
 * notti con NoctalixQuality sotto soglia escluse, notti senza il campo incluse.
 */
object BioSleepSeries {
  const val QUALITA_MIN = 80.0 // BIOSLEEP_QUALITA_MIN

  private fun num(v: PyNum?) = if (v != null && v.v > 0) v else null

  /** Sorgente di una riga: 1 fascia, 2 Garmin; assente o non valida = fascia (campi.sorgente). */
  fun sorgente(w: WellnessBio): Int {
    val v = w.sorgente?.v?.toInt()
    return if (v == 1 || v == 2) v else 1
  }

  private fun haNotte(w: WellnessBio) = w.rmssd != null && w.rmssd.v > 0

  /** Copia di campi.filtra_sorgente: solo le notti della sorgente dell'ultima notte (per data). */
  fun filtraSorgente(righe: List<WellnessBio>): List<WellnessBio> {
    val giorno = { w: WellnessBio -> (w.id?.takeIf { it.isNotEmpty() } ?: w.date?.takeIf { it.isNotEmpty() } ?: "").take(10) }
    var ultima: WellnessBio? = null
    var ultimoGiorno = ""
    for (w in righe) {
      if (!haNotte(w)) continue
      val g = giorno(w)
      if (ultima == null || g >= ultimoGiorno) {
        ultima = w
        ultimoGiorno = g
      }
    }
    val corrente = ultima?.let { sorgente(it) } ?: return righe
    return righe.filter { !haNotte(it) || sorgente(it) == corrente }
  }

  fun daWellness(righe: List<WellnessBio>, tags: Map<String, List<String>> = emptyMap()): List<GiornoBio> {
    val out = ArrayList<GiornoBio>()
    for (w in filtraSorgente(righe)) {
      val d = (w.id?.takeIf { it.isNotEmpty() } ?: w.date?.takeIf { it.isNotEmpty() } ?: "").take(10)
      val hrv = num(w.rmssd)
      if (d.isEmpty() || hrv == null) continue
      if (w.quality != null && w.quality.v < QUALITA_MIN) continue
      val sonno = num(w.sleepHours)
      out.add(
          GiornoBio(
              data = d, hrvMs = hrv, restingHr = num(w.avgHr),
              tags = tags[d].orEmpty(), sleepH = sonno?.let { Py.round(it.v, 1) }))
    }
    return out.sortedBy { it.data }
  }
}

/**
 * Storico BioSleep*: prima della rinomina NoctaliX (08/10/2026) le notti stavano nei campi
 * BioSleep<suffisso>. Come il cervello (campi.VECCHI): per ogni giorno e campo vale il codice
 * Noctalix*, se manca il vecchio. I campi standard (hrv, restingHR, ...) non contano mai.
 */
object CampiStorici {
  fun vecchio(codice: String): String = "BioSleep" + codice.removePrefix("Noctalix")

  fun leggi(o: JsonObject, codice: String): JsonElement? =
      o.get(codice)?.takeIf { !it.isJsonNull } ?: o.get(vecchio(codice))?.takeIf { !it.isJsonNull }
}

/** Lettura del JSON di Intervals.icu con la stessa distinzione intero/decimale di Python. */
object PyJson {

  /** Numero JSON -> PyNum (56 intero, 56.0 decimale); null per null, stringhe, booleani. */
  fun num(e: JsonElement?): PyNum? {
    if (e == null || e.isJsonNull || !e.isJsonPrimitive) return null
    val p = e.asJsonPrimitive
    if (!p.isNumber) return null
    val s = p.asString // per i numeri Gson restituisce il testo originale
    val isInt = s.none { it == '.' || it == 'e' || it == 'E' }
    val v = s.toDoubleOrNull() ?: return null
    return if (v.isFinite()) PyNum(v, isInt) else null
  }

  fun str(e: JsonElement?): String? =
      if (e == null || e.isJsonNull || !e.isJsonPrimitive) null else e.asJsonPrimitive.asString

  fun wellnessBio(o: JsonObject) =
      WellnessBio(
          id = str(o.get("id")), date = str(o.get("date")),
          rmssd = num(CampiStorici.leggi(o, CampiWellness.F_RMSSD)),
          avgHr = num(CampiStorici.leggi(o, CampiWellness.F_AVG_HR)),
          quality = num(CampiStorici.leggi(o, CampiWellness.F_QUALITY)),
          sleepHours = num(CampiStorici.leggi(o, CampiWellness.F_SLEEP_HOURS)),
          sorgente = num(o.get(CampiWellness.F_SOURCE)))

  fun wellnessBio(a: JsonArray): List<WellnessBio> = a.filter { it.isJsonObject }.map { wellnessBio(it.asJsonObject) }

  fun rigaForma(o: JsonObject) =
      RigaForma(
          id = str(o.get("id")), date = str(o.get("date")), ctl = num(o.get("ctl"))?.v,
          atl = num(o.get("atl"))?.v, rampRate = num(o.get("rampRate"))?.v)

  fun righeForma(a: JsonArray): List<RigaForma> = a.filter { it.isJsonObject }.map { rigaForma(it.asJsonObject) }

  fun caricoAttivita(o: JsonObject) =
      CaricoAttivita(startDateLocal = str(o.get("start_date_local")), load = num(o.get("icu_training_load"))?.v)

  fun carichiAttivita(a: JsonArray): List<CaricoAttivita> =
      a.filter { it.isJsonObject }.map { caricoAttivita(it.asJsonObject) }
}
