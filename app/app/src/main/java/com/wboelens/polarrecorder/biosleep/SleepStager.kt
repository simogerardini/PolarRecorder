package com.wboelens.polarrecorder.biosleep

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Fasi del sonno per epoche da 30 s. */
enum class Stage(val code: Char) {
  WAKE('W'),
  LIGHT('L'),
  DEEP('D'),
  REM('R');

  companion object {
    fun fromCode(c: Char): Stage = entries.firstOrNull { it.code == c } ?: LIGHT
  }
}

/** Attivita' dell'accelerometro aggregata al secondo (dalla H10). */
class AccSeconds(
    val tSec: LongArray, // secondo Unix
    val activity: IntArray, // movimento nel secondo (vedi metric)
    val gx: IntArray, // media per asse (mg): direzione della gravita' = postura
    val gy: IntArray,
    val gz: IntArray,
    /**
     * Come e' misurato activity. 1 = deviazione standard del modulo (vecchie notti): sul torace
     * include il respiro, quindi NON distingue movimento e respiro profondo -> non usato.
     * 2 = variazione media tra campioni consecutivi (0,1 mg): insensibile al respiro, lento.
     * 3 = come 2, piu' gx/gy/gz in 0,1 mg: abbastanza precisi da leggere il respiro del torace.
     */
    val metric: Int = 3,
) {
  val size: Int
    get() = tSec.size
}

/** Risultato della stima delle fasi. hypnogram: una lettera per epoca (W, L, D, R). */
data class SleepStages(
    val hypnoStartMs: Long,
    val hypnogram: String,
    val sleepOnsetMs: Long?,
    val sleepEndMs: Long?,
    val tstMin: Int,
    val deepMin: Int,
    val lightMin: Int,
    val remMin: Int,
    val wakeMin: Int,
    val mode: String, // "HRV" oppure "HRV+ACC"
    /**
     * Caratteristiche usate per ogni epoca (z-score), riga per riga, nell'ordine di
     * FEATURE_NAMES. Servono alla taratura offline (es. confronto con Oura).
     */
    val features: FloatArray? = null,
) {
  companion object {
    const val EPOCH_MS = 30_000L
    val FEATURE_NAMES =
        listOf(
            "hr", "hf_nu", "dfa_a1", "lf_hf", "hr_sd", "arousal", "activity",
            "resp_regularity", "resp_rate_sd")
  }
}

/**
 * Stima delle fasi del sonno da intervalli RR e, se c'e', accelerometro.
 *
 * E' un modello a regole, non un polisonnografo: le soglie sono relative alla notte stessa
 * (z-score robusti) e un algoritmo di Viterbi impone transizioni plausibili tra le fasi.
 * Basi fisiologiche:
 *  - Sonno profondo: FC minima, potenza HF (respiratoria) alta, ritmo molto regolare, DFA alfa1 basso.
 *  - REM: FC piu' alta e instabile, LF/HF alto, DFA alfa1 vicino a 1, nessun movimento (atonia).
 *  - Veglia: FC sopra la media della notte, accelerazioni improvvise, movimento.
 */
object SleepStager {
  /** Da aumentare quando cambiano profili o regole: le notti passate vengono ricalcolate. */
  const val VERSION = 6 // 6: profili ed equilibrio tarati con Oura (7 notti); 5: respiro dal torace

  private const val WINDOW_HALF_MS = 150_000.0 // finestra HRV di 5 minuti centrata sull'epoca
  private const val MIN_WINDOW_BEATS = 120
  private const val MIN_EPOCH_BEATS = 10
  private const val AROUSAL_BPM = 10.0
  private const val AROUSAL_BEATS = 4
  private const val POSTURE_DEG = 30.0
  private const val POSTURE_BONUS = 1000.0 // 100 mg: un cambio di postura conta come molto movimento
  private const val REM_BLOCK_MIN = 45.0 // niente REM nei primi 45 minuti
  private const val ONSET_EPOCHS = 20 // 10 minuti di sonno continuo = addormentamento

  // Griglia di frequenze per Lomb-Scargle (Hz): bande LF 0,04-0,15 e HF 0,15-0,40
  private val FREQS = DoubleArray(73) { 0.04 + it * 0.005 }

  /**
   * [soloFc] (punto 10): fascia senza RR affidabili (solo FC o sensore ottico). L'HRV non e'
   * reale: si usano solo FC, instabilita' della FC, micro-risvegli e, se c'e', l'accelerometro.
   */
  fun stage(beats: CleanBeats, acc: AccSeconds?, soloFc: Boolean = false): SleepStages? {
    val t = beats.t
    val nn = beats.nn
    if (t.size < MIN_WINDOW_BEATS) return null
    val start = t.first()
    val end = t.last()
    val nEpochs = ((end - start) / SleepStages.EPOCH_MS).toInt()
    if (nEpochs < 2 * ONSET_EPOCHS) return null
    val hasAcc = acc != null && acc.size > 60 && acc.metric >= 2

    // --- Caratteristiche per epoca ---------------------------------------------------------
    val hr = DoubleArray(nEpochs) { Double.NaN }
    val rmssd = DoubleArray(nEpochs) { Double.NaN }
    val hfNu = DoubleArray(nEpochs) { Double.NaN }
    val lfhf = DoubleArray(nEpochs) { Double.NaN }
    val alpha1 = DoubleArray(nEpochs) { Double.NaN }
    val arousal = DoubleArray(nEpochs)
    val activity = DoubleArray(nEpochs)

    var lo = 0
    var hi = 0
    var eLo = 0
    for (k in 0 until nEpochs) {
      val e0 = start + k * SleepStages.EPOCH_MS
      val e1 = e0 + SleepStages.EPOCH_MS
      val c = (e0 + e1) / 2
      while (lo < t.size && t[lo] < c - WINDOW_HALF_MS) lo++
      while (hi < t.size && t[hi] < c + WINDOW_HALF_MS) hi++
      while (eLo < t.size && t[eLo] < e0) eLo++

      // FC dell'epoca
      var sum = 0.0
      var cnt = 0
      var j = eLo
      while (j < t.size && t[j] < e1) {
        if (!nn[j].isNaN()) {
          sum += nn[j]
          cnt++
        }
        j++
      }
      if (cnt >= MIN_EPOCH_BEATS) hr[k] = 60000.0 / (sum / cnt)

      // HRV sulla finestra di 5 minuti
      val wt = ArrayList<Double>(hi - lo)
      val wv = ArrayList<Double>(hi - lo)
      for (i in lo until hi) if (!nn[i].isNaN()) {
        wt.add(t[i] / 1000.0)
        wv.add(nn[i])
      }
      if (wv.size >= MIN_WINDOW_BEATS) {
        rmssd[k] = rmssdOf(nn, lo, hi)
        val (lf, hf) = lombBands(wt.toDoubleArray(), wv.toDoubleArray())
        if (lf + hf > 0) {
          hfNu[k] = hf / (lf + hf)
          lfhf[k] = lf / max(hf, 1e-9)
        }
        alpha1[k] = dfaAlpha1(wv.toDoubleArray())
      }
    }

    // Micro-risvegli: FC istantanea > mediana del minuto precedente + 10 bpm per 4+ battiti
    countArousals(t, nn, start, nEpochs, arousal)

    // Instabilita' della FC: deviazione standard della FC per epoca su +-5 epoche
    val hrSd = DoubleArray(nEpochs) { k ->
      val vals = (maxOf(0, k - 5)..minOf(nEpochs - 1, k + 5)).map { hr[it] }.filter { !it.isNaN() }
      if (vals.size < 3) Double.NaN else stdOf(vals)
    }

    // Accelerometro: attivita' media + cambi di postura
    if (hasAcc) fillActivity(acc!!, start, nEpochs, activity)

    // Respiro dal torace: regolarita' (profondo = molto regolare, REM = irregolare)
    val hasResp = hasAcc && acc!!.metric >= 3
    val respReg = DoubleArray(nEpochs) { Double.NaN }
    val respRate = DoubleArray(nEpochs) { Double.NaN }
    if (hasResp) fillRespiration(acc!!, start, nEpochs, respReg, respRate)
    val respRateSd = DoubleArray(nEpochs) { k ->
      val vals = (maxOf(0, k - 5)..minOf(nEpochs - 1, k + 5)).map { respRate[it] }.filter { !it.isNaN() }
      if (vals.size < 3) Double.NaN else stdOf(vals)
    }

    // --- Punteggi relativi alla notte --------------------------------------------------------
    val zHr = robustZ(hr)
    val zRmssd = robustZ(rmssd)
    val zHf = robustZ(hfNu)
    val zLfhf = robustZ(lfhf)
    val zA1 = robustZ(alpha1)
    val zAr = robustZ(arousal)
    val zSd = robustZ(hrSd)
    val zAct = if (hasAcc) robustZ(activity) else DoubleArray(nEpochs)
    val zRespReg = if (hasResp) robustZ(respReg) else DoubleArray(nEpochs)
    val zRespSd = if (hasResp) robustZ(respRateSd) else DoubleArray(nEpochs)

    // Ogni fase ha un "profilo tipico" di z-score; il punteggio di un'epoca e' tanto piu' alto
    // quanto piu' le sue caratteristiche sono vicine al profilo (distanza pesata).
    val features = arrayOf(zHr, zHf, zA1, zLfhf, zSd, zAr, zAct, zRespReg, zRespSd)
    val weights =
        doubleArrayOf(
            1.0,
            if (soloFc) 0.0 else 0.6, // HF normalizzata
            if (soloFc) 0.0 else 0.6, // DFA alfa1
            if (soloFc) 0.0 else 0.5, // LF/HF
            0.6, 0.5,
            if (hasAcc) 0.8 else 0.0,
            if (hasResp) 0.6 else 0.0,
            if (hasResp) 0.5 else 0.0,
        )
    val base = if (soloFc) "FC" else "HRV"
    val mode = if (hasResp) "$base+ACC+RESP" else if (hasAcc) "$base+ACC" else base
    val scores = Array(nEpochs) { DoubleArray(4) } // W, L, D, R
    for (k in 0 until nEpochs) {
      val minutes = k * 0.5
      val frac = k.toDouble() / nEpochs
      for (c in 0 until 4) {
        var d2 = 0.0
        for (f in features.indices) {
          val diff = features[f][k] - PROTOTYPES[c][f]
          d2 += weights[f] * diff * diff
        }
        // Solo la somiglianza al profilo: quanto e' frequente ogni fase lo dicono gia' le
        // transizioni di Viterbi. Aggiungere qui anche la frequenza la conterebbe due volte.
        scores[k][c] = -0.5 * d2 + BIAS[c]
      }
      // Architettura tipica: profondo nella prima parte della notte, REM nella seconda
      scores[k][2] += 0.8 * (1 - 2 * frac)
      scores[k][3] += 0.6 * (2 * frac - 1)
      if (minutes < REM_BLOCK_MIN) scores[k][3] -= 3.0
    }

    val featureRows = FloatArray(nEpochs * features.size)
    for (k in 0 until nEpochs) for (f in features.indices) {
      featureRows[k * features.size + f] = features[f][k].toFloat()
    }

    val path = viterbi(scores)
    val stages = path.map { Stage.entries[it] }.toMutableList()

    // --- Addormentamento e risveglio finale ----------------------------------------------------
    val onset = findOnset(stages)
    val lastSleep = stages.indexOfLast { it != Stage.WAKE }
    if (onset == null || lastSleep < onset) {
      return summarize(start.toLong(), stages.map { Stage.WAKE }, null, null, mode)
          .copy(features = featureRows)
    }
    for (k in 0 until onset) stages[k] = Stage.WAKE
    return summarize(start.toLong(), stages, onset, lastSleep, mode).copy(features = featureRows)
  }

  private fun summarize(
      start: Long,
      stages: List<Stage>,
      onset: Int?,
      lastSleep: Int?,
      mode: String,
  ): SleepStages {
    var deep = 0
    var light = 0
    var rem = 0
    var wake = 0
    if (onset != null && lastSleep != null) {
      for (k in onset..lastSleep) {
        when (stages[k]) {
          Stage.DEEP -> deep++
          Stage.LIGHT -> light++
          Stage.REM -> rem++
          Stage.WAKE -> wake++
        }
      }
    }
    val ep = SleepStages.EPOCH_MS
    return SleepStages(
        hypnoStartMs = start,
        hypnogram = stages.joinToString("") { it.code.toString() },
        sleepOnsetMs = onset?.let { start + it * ep },
        sleepEndMs = lastSleep?.let { start + (it + 1) * ep },
        tstMin = (deep + light + rem) / 2,
        deepMin = deep / 2,
        lightMin = light / 2,
        remMin = rem / 2,
        wakeMin = wake / 2,
        mode = mode,
    )
  }

  private fun findOnset(stages: List<Stage>): Int? {
    var run = 0
    for (k in stages.indices) {
      run = if (stages[k] != Stage.WAKE) run + 1 else 0
      if (run >= ONSET_EPOCHS) return k - ONSET_EPOCHS + 1
    }
    return null
  }

  /**
   * Profilo tipico (z-score rispetto alla notte) di ogni fase, nell'ordine:
   * FC, HF normalizzata, DFA alfa1, LF/HF, instabilita' FC, micro-risvegli, attivita' ACC.
   * Il sonno leggero e' la fase piu' frequente, quindi coincide con la "media" della notte.
   */
  private val PROTOTYPES =
      arrayOf(
          // Tarati con confronta_oura.py su 7 notti (01-07/10/2026, 5 con accelerometro e respiro):
          // kappa su notti non usate per tarare 0,30 -> 0,37. Valori misurati su una persona.
          doubleArrayOf(0.44, -0.20, 0.30, 0.45, 0.52, 0.18, 0.48, -0.41, 0.39), // Veglia
          doubleArrayOf(-0.11, 0.16, -0.10, 0.08, 0.15, 0.13, -0.12, -0.02, -0.09), // Leggero
          doubleArrayOf(0.27, 0.37, -0.30, -0.11, -0.08, 0.08, -0.10, 0.14, -0.20), // Profondo
          doubleArrayOf(-0.07, -0.45, 0.57, 0.83, 0.86, 0.42, 0.56, -0.52, 0.32), // REM
      )

  /**
   * Correzione di equilibrio tra le fasi, tarata con confronta_oura.py perche' la proporzione
   * di ogni fase stimata segua quella del riferimento. Zero = nessuna correzione.
   */
  private val BIAS = doubleArrayOf(-0.45, 0.00, -0.43, -0.53)

  // --- Viterbi: sequenza di fasi piu' probabile con transizioni plausibili --------------------
  private val TRANSITIONS =
      arrayOf(
          doubleArrayOf(0.90, 0.09, 0.0, 0.01), // da Veglia
          doubleArrayOf(0.02, 0.93, 0.03, 0.02), // da Leggero
          doubleArrayOf(0.01, 0.05, 0.94, 0.0), // da Profondo (mai direttamente in REM)
          doubleArrayOf(0.02, 0.04, 0.0, 0.94), // da REM
      )

  private fun viterbi(scores: Array<DoubleArray>): IntArray {
    val n = scores.size
    val logA = Array(4) { i -> DoubleArray(4) { j -> ln(TRANSITIONS[i][j] + 1e-6) } }
    val logE = Array(n) { k -> logSoftmax(scores[k]) }
    val v = Array(n) { DoubleArray(4) }
    val back = Array(n) { IntArray(4) }
    val logPi = doubleArrayOf(ln(0.97), ln(0.01), ln(0.01), ln(0.01))
    for (j in 0 until 4) v[0][j] = logPi[j] + logE[0][j]
    for (k in 1 until n) {
      for (j in 0 until 4) {
        var best = Double.NEGATIVE_INFINITY
        var arg = 0
        for (i in 0 until 4) {
          val x = v[k - 1][i] + logA[i][j]
          if (x > best) {
            best = x
            arg = i
          }
        }
        v[k][j] = best + logE[k][j]
        back[k][j] = arg
      }
    }
    val path = IntArray(n)
    path[n - 1] = (0 until 4).maxBy { v[n - 1][it] }
    for (k in n - 1 downTo 1) path[k - 1] = back[k][path[k]]
    return path
  }

  private fun logSoftmax(x: DoubleArray): DoubleArray {
    val m = x.max()
    val lse = m + ln(x.sumOf { exp(it - m) })
    return DoubleArray(x.size) { x[it] - lse }
  }

  // --- Caratteristiche ---------------------------------------------------------------------
  private fun rmssdOf(nn: DoubleArray, lo: Int, hi: Int): Double {
    var s = 0.0
    var c = 0
    for (i in lo + 1 until hi) {
      val d = nn[i] - nn[i - 1]
      if (!d.isNaN()) {
        s += d * d
        c++
      }
    }
    return if (c > 0) sqrt(s / c) else Double.NaN
  }

  /** Periodogramma di Lomb-Scargle (gestisce i battiti non equidistanti): potenza LF e HF. */
  private fun lombBands(tSec: DoubleArray, x: DoubleArray): Pair<Double, Double> {
    val n = x.size
    // detrend lineare
    val tm = tSec.average()
    val xm = x.average()
    var sxy = 0.0
    var sxx = 0.0
    for (i in 0 until n) {
      sxy += (tSec[i] - tm) * (x[i] - xm)
      sxx += (tSec[i] - tm) * (tSec[i] - tm)
    }
    val slope = if (sxx > 0) sxy / sxx else 0.0
    val y = DoubleArray(n) { x[it] - xm - slope * (tSec[it] - tm) }
    var lf = 0.0
    var hf = 0.0
    for (f in FREQS) {
      val w = 2 * PI * f
      var s2 = 0.0
      var c2 = 0.0
      for (i in 0 until n) {
        s2 += sin(2 * w * tSec[i])
        c2 += cos(2 * w * tSec[i])
      }
      val tau = atan2(s2, c2) / (2 * w)
      var yc = 0.0
      var ys = 0.0
      var cc = 0.0
      var ss = 0.0
      for (i in 0 until n) {
        val a = w * (tSec[i] - tau)
        val ca = cos(a)
        val sa = sin(a)
        yc += y[i] * ca
        ys += y[i] * sa
        cc += ca * ca
        ss += sa * sa
      }
      val p = 0.5 * (yc * yc / max(cc, 1e-12) + ys * ys / max(ss, 1e-12))
      if (f < 0.15) lf += p else hf += p
    }
    return lf to hf
  }

  /** DFA alfa1 su scale 4-16 battiti. */
  private fun dfaAlpha1(x: DoubleArray): Double {
    val m = x.average()
    val y = DoubleArray(x.size)
    var acc = 0.0
    for (i in x.indices) {
      acc += x[i] - m
      y[i] = acc
    }
    val logN = ArrayList<Double>()
    val logF = ArrayList<Double>()
    for (n in 4..16) {
      val segs = y.size / n
      if (segs < 4) continue
      var total = 0.0
      for (s in 0 until segs) {
        // retta di regressione sul segmento
        val off = s * n
        val xm = (n - 1) / 2.0
        var ym = 0.0
        for (i in 0 until n) ym += y[off + i]
        ym /= n
        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until n) {
          sxy += (i - xm) * (y[off + i] - ym)
          sxx += (i - xm) * (i - xm)
        }
        val b = sxy / sxx
        for (i in 0 until n) {
          val r = y[off + i] - (ym + b * (i - xm))
          total += r * r
        }
      }
      val f = sqrt(total / (segs * n))
      if (f > 0) {
        logN.add(ln(n.toDouble()))
        logF.add(ln(f))
      }
    }
    if (logN.size < 3) return Double.NaN
    val nm = logN.average()
    val fm = logF.average()
    var sxy = 0.0
    var sxx = 0.0
    for (i in logN.indices) {
      sxy += (logN[i] - nm) * (logF[i] - fm)
      sxx += (logN[i] - nm) * (logN[i] - nm)
    }
    return sxy / sxx
  }

  private fun countArousals(t: DoubleArray, nn: DoubleArray, start: Double, nEpochs: Int, out: DoubleArray) {
    val inst = DoubleArray(nn.size) { if (nn[it].isNaN()) Double.NaN else 60000.0 / nn[it] }
    var lo = 0
    var run = 0
    for (i in inst.indices) {
      while (lo < i && t[lo] < t[i] - 60_000) lo++
      val past = (lo until i).map { inst[it] }.filter { !it.isNaN() }
      if (past.size < 20 || inst[i].isNaN()) {
        run = 0
        continue
      }
      val med = past.sorted()[past.size / 2]
      if (inst[i] > med + AROUSAL_BPM) {
        run++
        if (run == AROUSAL_BEATS) {
          val k = ((t[i] - start) / SleepStages.EPOCH_MS).toInt()
          if (k in 0 until nEpochs) out[k] += 1.0
        }
      } else {
        run = 0
      }
    }
  }

  private fun fillActivity(acc: AccSeconds, start: Double, nEpochs: Int, out: DoubleArray) {
    val sum = DoubleArray(nEpochs)
    val cnt = IntArray(nEpochs)
    val gx = DoubleArray(nEpochs)
    val gy = DoubleArray(nEpochs)
    val gz = DoubleArray(nEpochs)
    for (i in 0 until acc.size) {
      val k = ((acc.tSec[i] * 1000.0 - start) / SleepStages.EPOCH_MS).toInt()
      if (k !in 0 until nEpochs) continue
      sum[k] += acc.activity[i]
      cnt[k]++
      gx[k] += acc.gx[i]
      gy[k] += acc.gy[i]
      gz[k] += acc.gz[i]
    }
    var prev: Int? = null
    for (k in 0 until nEpochs) {
      if (cnt[k] == 0) {
        out[k] = Double.NaN
        continue
      }
      out[k] = sum[k] / cnt[k]
      // Un cambio di postura (> 30 gradi) e' quasi sempre un risveglio o un sonno leggero
      prev?.let { p ->
        val dot = gx[k] * gx[p] + gy[k] * gy[p] + gz[k] * gz[p]
        val norm =
            sqrt(gx[k] * gx[k] + gy[k] * gy[k] + gz[k] * gz[k]) *
                sqrt(gx[p] * gx[p] + gy[p] * gy[p] + gz[p] * gz[p])
        if (norm > 0) {
          val deg = Math.toDegrees(acos((dot / norm).coerceIn(-1.0, 1.0)))
          if (deg > POSTURE_DEG) out[k] += POSTURE_BONUS
        }
      }
      prev = k
    }
  }

  // --- Respiro dal torace ----------------------------------------------------------------------
  private const val RESP_HALF_S = 60L // finestra di 2 minuti centrata sull'epoca
  private const val RESP_MIN_SECONDS = 90
  private const val RESP_PEAK_HZ = 0.02 // larghezza del picco per la regolarita'
  private val RESP_FREQS = DoubleArray(41) { 0.10 + it * 0.01 } // 6-30 atti/min

  /**
   * Il torace si alza e si abbassa a ogni respiro e la fascia si inclina un poco: la direzione
   * della gravita' vista dall'accelerometro oscilla al ritmo del respiro.
   * Per ogni epoca, su 2 minuti:
   *  1. si prende la direzione in cui l'oscillazione e' piu' ampia (componente principale);
   *  2. se ne calcola lo spettro tra 0,10 e 0,50 Hz (6-30 atti al minuto);
   *  3. frequenza respiratoria = picco; regolarita' = quota di potenza vicina al picco.
   *     Respiro regolare (sonno profondo) -> picco stretto -> regolarita' alta.
   */
  private fun fillRespiration(
      acc: AccSeconds,
      start: Double,
      nEpochs: Int,
      regularity: DoubleArray,
      rate: DoubleArray,
  ) {
    var lo = 0
    var hi = 0
    for (k in 0 until nEpochs) {
      val cSec = ((start + (k + 0.5) * SleepStages.EPOCH_MS) / 1000).toLong()
      while (lo < acc.size && acc.tSec[lo] < cSec - RESP_HALF_S) lo++
      while (hi < acc.size && acc.tSec[hi] < cSec + RESP_HALF_S) hi++
      val n = hi - lo
      if (n < RESP_MIN_SECONDS) continue
      val sig = principalComponent(acc, lo, hi)
      val t = DoubleArray(n) { (acc.tSec[lo + it] - cSec).toDouble() }
      val power = spectrum(t, sig)
      var peak = 0
      for (i in power.indices) if (power[i] > power[peak]) peak = i
      val total = power.sum()
      if (total <= 0) continue
      var near = 0.0
      for (i in power.indices) {
        if (kotlin.math.abs(RESP_FREQS[i] - RESP_FREQS[peak]) <= RESP_PEAK_HZ) near += power[i]
      }
      regularity[k] = near / total
      rate[k] = RESP_FREQS[peak] * 60
    }
  }

  /** Proietta i tre assi (centrati) sulla direzione di massima varianza. */
  private fun principalComponent(acc: AccSeconds, lo: Int, hi: Int): DoubleArray {
    val n = hi - lo
    val m = DoubleArray(3)
    for (i in lo until hi) {
      m[0] += acc.gx[i]
      m[1] += acc.gy[i]
      m[2] += acc.gz[i]
    }
    for (j in 0..2) m[j] /= n
    val x = Array(n) { i ->
      doubleArrayOf(acc.gx[lo + i] - m[0], acc.gy[lo + i] - m[1], acc.gz[lo + i] - m[2])
    }
    val cov = Array(3) { DoubleArray(3) }
    for (r in x) for (a in 0..2) for (b in 0..2) cov[a][b] += r[a] * r[b]
    var v = doubleArrayOf(1.0, 1.0, 1.0)
    repeat(30) {
      val w = DoubleArray(3) { a -> (0..2).sumOf { b -> cov[a][b] * v[b] } }
      val norm = sqrt(w.sumOf { it * it })
      if (norm < 1e-12) return DoubleArray(n)
      v = DoubleArray(3) { w[it] / norm }
    }
    // detrend lineare: toglie la lenta deriva di postura
    val y = DoubleArray(n) { i -> x[i][0] * v[0] + x[i][1] * v[1] + x[i][2] * v[2] }
    val tm = (n - 1) / 2.0
    var sxy = 0.0
    var sxx = 0.0
    for (i in 0 until n) {
      sxy += (i - tm) * y[i]
      sxx += (i - tm) * (i - tm)
    }
    val slope = if (sxx > 0) sxy / sxx else 0.0
    return DoubleArray(n) { y[it] - slope * (it - tm) }
  }

  /** Periodogramma di Lomb-Scargle sulle frequenze respiratorie (tollera secondi mancanti). */
  private fun spectrum(t: DoubleArray, y: DoubleArray): DoubleArray =
      DoubleArray(RESP_FREQS.size) { fi ->
        val w = 2 * PI * RESP_FREQS[fi]
        var s2 = 0.0
        var c2 = 0.0
        for (i in t.indices) {
          s2 += sin(2 * w * t[i])
          c2 += cos(2 * w * t[i])
        }
        val tau = atan2(s2, c2) / (2 * w)
        var yc = 0.0
        var ys = 0.0
        var cc = 0.0
        var ss = 0.0
        for (i in t.indices) {
          val a = w * (t[i] - tau)
          val ca = cos(a)
          val sa = sin(a)
          yc += y[i] * ca
          ys += y[i] * sa
          cc += ca * ca
          ss += sa * sa
        }
        0.5 * (yc * yc / max(cc, 1e-12) + ys * ys / max(ss, 1e-12))
      }

  /** z-score robusto (mediana e IQR della notte). Valori mancanti -> 0 (neutro). */
  private fun robustZ(v: DoubleArray): DoubleArray {
    val valid = v.filter { !it.isNaN() }.sorted()
    if (valid.size < 10) return DoubleArray(v.size)
    val med = quantile(valid, 0.5)
    val iqr = quantile(valid, 0.75) - quantile(valid, 0.25)
    val scale = if (iqr > 1e-9) iqr else stdOf(valid).takeIf { it > 1e-9 } ?: return DoubleArray(v.size)
    return DoubleArray(v.size) { if (v[it].isNaN()) 0.0 else ((v[it] - med) / scale).coerceIn(-4.0, 4.0) }
  }

  private fun quantile(sorted: List<Double>, q: Double): Double {
    val pos = (sorted.size - 1) * q
    val lo = pos.toInt()
    val hi = minOf(lo + 1, sorted.size - 1)
    return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
  }

  private fun stdOf(v: List<Double>): Double {
    val m = v.average()
    return sqrt(v.sumOf { (it - m) * (it - m) } / max(1, v.size - 1))
  }

}
