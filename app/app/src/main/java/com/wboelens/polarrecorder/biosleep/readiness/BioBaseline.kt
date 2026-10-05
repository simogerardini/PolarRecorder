package com.wboelens.polarrecorder.biosleep.readiness

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Un giorno della serie biometrica, come i dict di get_oura_history / biosleep_history_da_wellness. */
data class GiornoBio(
    val data: String?,
    val hrvMs: PyNum?,
    val restingHr: PyNum?,
    val tags: List<String?> = emptyList(),
    val sleepH: Double? = null,
)

data class FcRiposo(
    val baseline: Double?,
    val rolling7: Double,
    val delta: Double?,
    val allarme: Boolean,
)

/** Uscita numerica di calc_baseline_biometrici (senza i testi per il prompt del coach). */
data class BioBaseline(
    val ok: Boolean,
    val nGiorniHrv: Int,
    val banda: String? = null, // "verde" | "giallo" | "rosso" | "grigio"
    val baselineHrv: Double? = null,
    val rolling7Hrv: Double? = null,
    val cvPct: Double? = null,
    val pctVsBaseline: Double? = null,
    val persistenzaGgSotto: Int = 0,
    val zLn: Double? = null,
    val sdLn: Double? = null,
    val sdLnGrezza: Double? = null,
    val normalRangeMs: Pair<Double, Double>? = null,
    val cvCollassato: Boolean = false,
    val sdClampata: Boolean = false,
    val direzione7v7: String? = null,
    val delta7v7Pct: Double? = null,
    val ggRitardo: Int = 0,
    val nGiorniFinestra7: Int = 0,
    val fcRiposo: FcRiposo? = null,
)

/**
 * Copia fedele di calc_baseline_biometrici (intervals_coach.py). Ogni modifica alla formula
 * nel coach va riportata qui: il test BaselineParityTest confronta questa funzione con i casi
 * generati da genera_casi_baseline.py e fallisce finche' le due non coincidono.
 * Le operazioni numeriche passano da [Py] (sum, round) per avere gli stessi bit di Python.
 */
object BioBaselineCalc {

  /**
   * Tag di giorno che escludono una notte dalla baseline (biometria.TAG_CONFONDENTI del cervello).
   * Dal 05/10/2026 confronto ESATTO con la chiave del vocabolario dell'app (prima: sottostringa
   * dei tag Oura). Il test di parita' verifica che l'elenco coincida con quello del cervello.
   */
  val TAG_CONFONDENTI =
      setOf("alcol", "cena_tardiva", "caffeina_tardi", "stress", "viaggio", "malattia", "sonno_disturbato", "caldo", "altitudine")

  private const val BASELINE_SD_GG = 42
  private const val SWC_PCT_MIN = 3.0
  private const val SWC_PCT_MAX = 8.0

  private class Punto(val data: String, val v: PyNum, val conf: Boolean) {
    val dt: LocalDate? = Py.parseDate(data)
  }

  private fun lnPy(v: Double) = ln(max(v, 1e-6))

  private fun truthy(x: Double?) = x != null && x != 0.0

  /** _bio_media_sd: media e SD di popolazione; (null, null) se vuoto. */
  private fun mediaSd(vals: List<PyNum>): Pair<Double?, Double?> {
    if (vals.isEmpty()) return null to null
    val m = Py.sum(vals).v / vals.size
    val sd = if (vals.size > 1) sqrt(Py.sumF(vals.map { (it.v - m) * (it.v - m) }) / vals.size) else 0.0
    return m to sd
  }

  private fun mediaSdF(vals: List<Double>) = mediaSd(vals.map { PyNum.of(it) })

  private fun finestra(serie: List<Punto>, fine: LocalDate, giorni: Int, soloPuliti: Boolean = false): List<PyNum> {
    val inizio = fine.minusDays((giorni - 1).toLong())
    return serie
        .filter { (!soloPuliti || !it.conf) && it.dt != null && !it.dt.isBefore(inizio) && !it.dt.isAfter(fine) }
        .map { it.v }
  }

  fun calcola(serie: List<GiornoBio>, oggi: LocalDate?): BioBaseline {
    fun hasConf(tags: List<String?>) = tags.any { (it ?: "") in TAG_CONFONDENTI }

    val hist = serie.filter { !it.data.isNullOrEmpty() }.sortedBy { it.data!! }
    val hrv = hist.filter { it.hrvMs != null && it.hrvMs.v != 0.0 }.map { Punto(it.data!!, it.hrvMs!!, hasConf(it.tags)) }
    val rhr = hist.filter { it.restingHr != null && it.restingHr.v != 0.0 }
        .map { Punto(it.data!!, it.restingHr!!, hasConf(it.tags)) }

    if (hrv.size < 7) return BioBaseline(ok = false, nGiorniHrv = hrv.size)

    val ultimoDt = hrv.asReversed().firstNotNullOfOrNull { it.dt } ?: return BioBaseline(ok = false, nGiorniHrv = hrv.size)
    val inizioRoll = ultimoDt.minusDays(6)
    val oggiDt = oggi ?: LocalDate.now()
    val ggRitardo = max(0L, ChronoUnit.DAYS.between(ultimoDt, oggiDt)).toInt()

    var roll7Vals = finestra(hrv, ultimoDt, 7)
    if (roll7Vals.size < 3) roll7Vals = hrv.takeLast(7).map { it.v }
    val roll7 = Py.sum(roll7Vals).v / roll7Vals.size
    val roll7Ln = Py.sumF(roll7Vals.map { lnPy(it.v) }) / roll7Vals.size

    var basePool = hrv.filter { !it.conf && it.dt != null && it.dt.isBefore(inizioRoll) }.map { it.v }
    if (basePool.size < 7) basePool = hrv.filter { !it.conf }.map { it.v }
    if (basePool.size < 7) basePool = hrv.map { it.v }
    basePool = basePool.takeLast(BASELINE_SD_GG)
    val (baseline, sdLin) = mediaSd(basePool)
    val cv = if (truthy(baseline)) Py.round(sdLin!! / baseline!! * 100, 1) else null

    val (baseLnN, sdLnN) = mediaSdF(basePool.map { lnPy(it.v) })
    val baseLn = baseLnN!!
    var sdLn = sdLnN ?: 0.0
    val sdMin = SWC_PCT_MIN / 50.0
    val sdMax = SWC_PCT_MAX / 50.0
    val sdGrezza = sdLn
    val sdRoll7Ln = mediaSdF(roll7Vals.map { lnPy(it.v) }).second
    val cvCollassato = sdGrezza != 0.0 && basePool.size >= 21 && roll7Vals.size >= 6 &&
        (sdRoll7Ln ?: 0.0) < 0.6 * sdGrezza
    val sdClampata = sdGrezza != 0.0 && !(sdMin <= sdGrezza && sdGrezza <= sdMax)
    if (sdGrezza != 0.0) sdLn = min(max(sdGrezza, sdMin), sdMax)
    val pct = if (truthy(baseline)) Py.round((roll7 - baseline!!) / baseline * 100, 1) else null
    val z = if (sdLn > 0) Py.round((roll7Ln - baseLn) / sdLn, 2) else null
    val nrLo = Py.round(exp(baseLn - 0.5 * sdLn), 1)
    val nrHi = Py.round(exp(baseLn + 0.5 * sdLn), 1)

    val prev7 = finestra(hrv, ultimoDt.minusDays(7), 7)
    val mediaPrev7 = if (prev7.size >= 4) Py.sum(prev7).v / prev7.size else null
    val d77 = if (truthy(mediaPrev7)) Py.round((roll7 - mediaPrev7!!) / mediaPrev7 * 100, 1) else null
    val direzione = when {
      d77 == null -> "non determinabile"
      d77 >= 3 -> "in salita"
      d77 <= -3 -> "in calo"
      else -> "stabile"
    }

    // persistenza: giorni CONSECUTIVI di calendario sotto il limite; un buco interrompe la catena
    val sogliaG = if (sdLn > 0) nrLo else if (truthy(baseline)) baseline!! * 0.90 else null
    var persist = 0
    if (sogliaG != null) {
      var atteso: LocalDate? = null
      for (p in hrv.asReversed()) {
        val giorno = p.dt ?: break
        if (atteso != null && giorno != atteso) break
        if (p.v.v >= sogliaG) break
        persist++
        atteso = giorno.minusDays(1)
      }
    }

    var r7Out = Py.round(roll7, 1)
    var baseOut = Py.round(baseline!!, 1)
    var pctOut = pct
    var loOut = nrLo
    if (z != null) {
      val r7g = exp(roll7Ln)
      val lo = exp(baseLn - 0.5 * sdLn)
      val bg = exp(baseLn)
      val dec = if (Py.round(r7g, 1) == Py.round(lo, 1)) 2 else 1
      r7Out = Py.round(r7g, dec)
      loOut = Py.round(lo, dec)
      baseOut = Py.round(bg, 1)
      pctOut = Py.round((r7g - bg) / bg * 100, 1)
    }

    val banda = when {
      z == null && pct == null -> "grigio"
      z != null -> if (z >= -0.5) "verde" else if (z > -1.0) "giallo" else "rosso"
      pct!! >= -10 -> "verde"
      pct > -20 -> "giallo"
      else -> "rosso"
    }

    var fc: FcRiposo? = null
    if (rhr.size >= 7) {
      val rhrVals = finestra(rhr, ultimoDt, 7).ifEmpty { rhr.takeLast(7).map { it.v } }
      val rhrR7 = Py.sum(rhrVals).v / rhrVals.size
      val rhrPool = rhr.filter { !it.conf && it.dt != null && it.dt.isBefore(inizioRoll) }.map { it.v }
          .ifEmpty { rhr.map { it.v } }
      val rhrBase = mediaSd(rhrPool).first
      val rhrDelta = if (truthy(rhrBase)) Py.round(rhrR7 - rhrBase!!, 1) else null
      fc = FcRiposo(
          baseline = if (truthy(rhrBase)) Py.round(rhrBase!!, 1) else null,
          rolling7 = Py.round(rhrR7, 1),
          delta = rhrDelta,
          allarme = rhrDelta != null && rhrDelta >= 5)
    }

    return BioBaseline(
        ok = true, nGiorniHrv = hrv.size, banda = banda, baselineHrv = baseOut, rolling7Hrv = r7Out,
        cvPct = cv, pctVsBaseline = pctOut, persistenzaGgSotto = persist, zLn = z,
        sdLn = Py.round(sdLn, 3), sdLnGrezza = if (sdGrezza != 0.0) Py.round(sdGrezza, 3) else null,
        normalRangeMs = loOut to nrHi, cvCollassato = cvCollassato, sdClampata = sdClampata,
        direzione7v7 = direzione, delta7v7Pct = d77, ggRitardo = ggRitardo,
        nGiorniFinestra7 = roll7Vals.size, fcRiposo = fc)
  }
}
