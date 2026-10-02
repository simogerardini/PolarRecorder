package com.wboelens.polarrecorder.biosleep.readiness

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Confronta la copia Kotlin con i casi generati da genera_casi_baseline.py.
 * Ritorna (casi verificati, elenco delle differenze). Nessuna tolleranza: i numeri attesi
 * sono gia' arrotondati come li mostra il coach, quindi devono coincidere esattamente.
 */
object BaselineParity {

  class Esito(val casi: Int, val differenze: List<String>, val intestazione: String)

  fun verifica(json: String): Esito {
    val doc = JsonParser.parseString(json).asJsonObject
    val casi = doc.getAsJsonArray("casi")
    val diff = ArrayList<String>()
    casi.forEachIndexed { i, el ->
      val c = el.asJsonObject
      val nome = "caso $i (${c.get("fonte")!!.asString}, oggi ${c.get("oggi")!!.asString})"
      val oggi = Py.parseDate(c.get("oggi")!!.asString)
      val serie: List<GiornoBio>
      if (c.has("wellness")) {
        serie = BioSleepSeries.daWellness(PyJson.wellnessBio(c.getAsJsonArray("wellness")))
        confrontaSerie(nome, serie, c.getAsJsonArray("serie_attesa").map { it.asJsonObject }, diff)
      } else {
        serie = c.getAsJsonArray("serie").map { giorno(it.asJsonObject) }
      }
      confronta(nome, BioBaselineCalc.calcola(serie, oggi), c.getAsJsonObject("atteso"), diff)
    }
    val intest = "Python ${doc.get("python")!!.asString}, intervals_coach.py " +
        "${doc.get("sha256_intervals_coach")!!.asString}, generato ${doc.get("generato")!!.asString}"
    return Esito(casi.size(), diff, intest)
  }

  private fun giorno(o: JsonObject) =
      GiornoBio(
          data = PyJson.str(o.get("data")),
          hrvMs = PyJson.num(o.get("hrv_ms")),
          restingHr = PyJson.num(o.get("resting_hr")),
          tags = o.get("tags")?.takeIf { it.isJsonArray }?.asJsonArray?.map { PyJson.str(it) }.orEmpty())

  private fun dbl(e: JsonElement?): Double? = PyJson.num(e)?.v

  private fun confrontaSerie(nome: String, k: List<GiornoBio>, py: List<JsonObject>, diff: MutableList<String>) {
    if (k.size != py.size) {
      diff.add("$nome: serie BioSleep ${k.size} giorni, attesi ${py.size}")
      return
    }
    k.zip(py).forEach { (g, p) ->
      val atteso = listOf(PyJson.str(p.get("data")), PyJson.num(p.get("hrv_ms")), PyJson.num(p.get("resting_hr")),
          dbl(p.get("sleep_h")))
      val ottenuto = listOf(g.data, g.hrvMs, g.restingHr, g.sleepH)
      if (atteso != ottenuto) diff.add("$nome: giorno serie $ottenuto, atteso $atteso")
    }
  }

  private fun confronta(nome: String, b: BioBaseline, a: JsonObject, diff: MutableList<String>) {
    fun eq(campo: String, kt: Any?, py: Any?) {
      if (kt != py) diff.add("$nome: $campo = $kt, atteso $py")
    }
    eq("ok", b.ok, a.get("ok")!!.asBoolean)
    eq("n_giorni_hrv", b.nGiorniHrv, a.get("n_giorni_hrv")!!.asInt)
    if (!b.ok || !a.get("ok")!!.asBoolean) return
    eq("banda", b.banda, PyJson.str(a.get("banda")))
    eq("baseline_hrv", b.baselineHrv, dbl(a.get("baseline_hrv")))
    eq("rolling7_hrv", b.rolling7Hrv, dbl(a.get("rolling7_hrv")))
    eq("cv_pct", b.cvPct, dbl(a.get("cv_pct")))
    eq("pct_vs_baseline", b.pctVsBaseline, dbl(a.get("pct_vs_baseline")))
    eq("persistenza_gg_sotto", b.persistenzaGgSotto, a.get("persistenza_gg_sotto")!!.asInt)
    eq("z_ln", b.zLn, dbl(a.get("z_ln")))
    eq("sd_ln", b.sdLn, dbl(a.get("sd_ln")))
    eq("sd_ln_grezza", b.sdLnGrezza, dbl(a.get("sd_ln_grezza")))
    val nr = a.get("normal_range_ms")?.takeIf { it.isJsonArray }?.asJsonArray
    eq("normal_range_ms", b.normalRangeMs, nr?.let { dbl(it[0])!! to dbl(it[1])!! })
    eq("cv_collassato", b.cvCollassato, a.get("cv_collassato")!!.asBoolean)
    eq("sd_clampata", b.sdClampata, a.get("sd_clampata")!!.asBoolean)
    eq("direzione_7v7", b.direzione7v7, PyJson.str(a.get("direzione_7v7")))
    eq("delta_7v7_pct", b.delta7v7Pct, dbl(a.get("delta_7v7_pct")))
    eq("gg_ritardo", b.ggRitardo, a.get("gg_ritardo")!!.asInt)
    eq("n_giorni_finestra7", b.nGiorniFinestra7, a.get("n_giorni_finestra7")!!.asInt)
    val fc = a.get("fc_riposo")?.takeIf { it.isJsonObject }?.asJsonObject
    eq("fc_riposo presente", b.fcRiposo != null, fc != null)
    if (b.fcRiposo != null && fc != null) {
      eq("fc.baseline", b.fcRiposo.baseline, dbl(fc.get("baseline")))
      eq("fc.rolling7", b.fcRiposo.rolling7, dbl(fc.get("rolling7")))
      eq("fc.delta", b.fcRiposo.delta, dbl(fc.get("delta")))
      eq("fc.allarme", b.fcRiposo.allarme, fc.get("allarme")!!.asBoolean)
    }
  }
}
