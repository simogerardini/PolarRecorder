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
    // l'elenco dei tag confondenti deve essere lo stesso del cervello (confronto esatto)
    doc.get("tag_confondenti")?.takeIf { it.isJsonArray }?.asJsonArray?.let { a ->
      val py = a.map { it.asString }.toSet()
      if (py != BioBaselineCalc.TAG_CONFONDENTI) diff.add("tag_confondenti: Kotlin ${BioBaselineCalc.TAG_CONFONDENTI}, cervello $py")
    }
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

  /** Forma: wellness_reale + stato_forma sui "casi_forma", piu' la serie dell'app sul caso reale. */
  fun verificaForma(json: String): Esito {
    val doc = JsonParser.parseString(json).asJsonObject
    val diff = ArrayList<String>()
    val casi = doc.getAsJsonArray("casi_forma")
    eqD(diff, "costante EWMA_ATL", FormaCalc.EWMA_ATL, dbl(doc.get("ewma_atl")))
    eqD(diff, "costante EWMA_CTL", FormaCalc.EWMA_CTL, dbl(doc.get("ewma_ctl")))
    eqD(diff, "costante RISCHIO_RELATIVO_PCT", FormaCalc.RISCHIO_RELATIVO_PCT, dbl(doc.get("rischio_relativo_pct")))
    casi.forEachIndexed { i, el ->
      val c = el.asJsonObject
      val nome = "forma $i (${c.get("fonte")!!.asString})"
      val w = PyJson.righeForma(c.getAsJsonArray("wellness"))
      val serie = if (c.has("attivita")) {
        val r = FormaCalc.wellnessReale(w, PyJson.carichiAttivita(c.getAsJsonArray("attivita")))
        val attese = c.getAsJsonArray("reale").map { it.asJsonObject }
        if (r.size != attese.size) diff.add("$nome: ${r.size} righe, attese ${attese.size}")
        else r.zip(attese).forEach { (k, p) ->
          val atteso = listOf(PyJson.str(p.get("giorno")), dbl(p.get("ctl")), dbl(p.get("atl")), dbl(p.get("rampRate")))
          val ottenuto = listOf(k.giorno, k.ctl, k.atl, k.rampRate)
          if (atteso != ottenuto) diff.add("$nome: riga $ottenuto, attesa $atteso")
        }
        if (c.get("fonte")!!.asString == "forma_reale") {
          val app = FormaCalc.serieApp(w, PyJson.carichiAttivita(c.getAsJsonArray("attivita")),
              Py.parseDate(w.maxOf { it.giorno })!!)
          confrontaForma("$nome serieApp", FormaCalc.statoForma(app), c.get("forma"), diff)
        }
        r
      } else w
      confrontaForma(nome, FormaCalc.statoForma(serie), c.get("forma"), diff)
    }
    return Esito(casi.size(), diff, "")
  }

  private fun eqD(diff: MutableList<String>, campo: String, kt: Double, py: Double?) {
    if (kt != py) diff.add("$campo = $kt, atteso $py")
  }

  private fun confrontaForma(nome: String, f: StatoForma?, a: JsonElement?, diff: MutableList<String>) {
    val o = a?.takeIf { it.isJsonObject }?.asJsonObject
    if ((f == null) != (o == null)) {
      diff.add("$nome: forma $f, attesa $o")
      return
    }
    if (f == null || o == null) return
    val atteso = listOf(dbl(o.get("ctl")), dbl(o.get("atl")), dbl(o.get("tsb")), dbl(o.get("form_pct")),
        PyJson.str(o.get("zona")), PyJson.str(o.get("colore")), o.get("giorni_in_zona")!!.asInt,
        o.get("storico_gg")!!.asInt, o.get("rischio_relativo")!!.asBoolean)
    val ottenuto = listOf(f.ctl, f.atl, f.tsb, f.formPct, f.zona, f.colore, f.giorniInZona, f.storicoGg, f.rischioRelativo)
    if (atteso != ottenuto) diff.add("$nome: forma $ottenuto, attesa $atteso")
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
