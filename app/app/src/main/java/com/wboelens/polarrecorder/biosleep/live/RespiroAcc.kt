package com.wboelens.polarrecorder.biosleep.live

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Respiro letto dall'accelerometro della H10 sul torace: il torace che si espande muove la fascia,
 * e il movimento compare direttamente nel segnale, con l'ampiezza del respiro e senza ritardo
 * fisiologico (a differenza del battito, che reagisce al respiro con 1-2 s di ritardo).
 *
 * Catena per ogni campione (25 Hz):
 * 1. passa-alto (media lenta di ~8 s sottratta): toglie la gravita' e i cambi di postura;
 * 2. passa-basso (~0,35 s): toglie la vibrazione del battito e il rumore;
 * 3. asse principale (PCA sulla covarianza degli ultimi ~20 s): la direzione in cui il respiro
 *    muove la fascia, qualunque sia la posizione nel letto;
 * 4. ampiezza rispetto a quella tipica degli ultimi ~30 s: un respiro profondo apre di piu' il
 *    fiore, uno superficiale di meno;
 * 5. verso: l'asse ha due versi possibili. Si sceglie quello in cui l'inspirazione coincide con
 *    l'accelerazione del cuore (aritmia sinusale respiratoria), confrontando le due serie.
 */
class RespiroAcc(private val fs: Double = 25.0) {
  private val gravita = DoubleArray(3)
  private val filtrato = DoubleArray(3)
  private val cov = Array(3) { DoubleArray(3) }
  private var asse = doubleArrayOf(0.0, 0.0, 1.0)
  private var potenza = 0.0
  private var n = 0
  private var inizializzato = false

  /** +1 o -1: verso dell'asse per cui inspirare = aprire. */
  var verso = 1.0
    private set

  /** true quando il verso e' stato confermato dal battito. */
  var versoConfermato = false
    private set

  private val storiaP = ArrayDeque<Pair<Long, Double>>()
  private val storiaHr = ArrayDeque<Pair<Long, Double>>()
  private var ultimoControlloVerso = 0L

  private val aGravita = 1.0 / (1 + fs * 8.0)
  private val aFiltro = 1.0 / (1 + fs * 0.35)
  private val aCov = 1.0 / (1 + fs * 20.0)
  private val aPotenza = 1.0 / (1 + fs * 30.0)

  /** Apertura del fiore 0..1 per questo campione (in mg), null durante i primi secondi. */
  fun campione(tMs: Long, x: Double, y: Double, z: Double): Float? {
    val v = doubleArrayOf(x, y, z)
    if (!inizializzato) {
      v.copyInto(gravita)
      inizializzato = true
    }
    val hp = DoubleArray(3)
    for (i in 0..2) {
      gravita[i] += aGravita * (v[i] - gravita[i])
      hp[i] = v[i] - gravita[i]
      filtrato[i] += aFiltro * (hp[i] - filtrato[i])
    }
    for (i in 0..2) for (j in 0..2) cov[i][j] += aCov * (filtrato[i] * filtrato[j] - cov[i][j])
    n++
    if (n % 5 == 0) aggiornaAsse()
    val p = filtrato[0] * asse[0] + filtrato[1] * asse[1] + filtrato[2] * asse[2]
    potenza += aPotenza * (p * p - potenza)
    storiaP.addLast(tMs to p)
    while (storiaP.isNotEmpty() && storiaP.first().first < tMs - STORIA_MS) storiaP.removeFirst()
    if (tMs - ultimoControlloVerso > 10_000) {
      ultimoControlloVerso = tMs
      controllaVerso()
    }
    if (n < fs * AVVIO_S) return null
    val ampiezzaTipica = sqrt(2 * potenza).coerceAtLeast(MIN_MG)
    // respiro tipico = mezza corsa del fiore (0,28-0,72): resta spazio per i respiri profondi
    return (0.5 + 0.22 * verso * p / ampiezzaTipica).coerceIn(0.0, 1.0).toFloat()
  }

  /** Un battito (RR) ricevuto all'istante tMs: serve solo a scegliere il verso. */
  fun battito(tMs: Long, rrMs: Int) {
    if (rrMs !in 300..2000) return
    storiaHr.addLast(tMs to 60_000.0 / rrMs)
    while (storiaHr.isNotEmpty() && storiaHr.first().first < tMs - STORIA_MS) storiaHr.removeFirst()
  }

  /** Potenza iterata: l'autovettore principale della covarianza, senza invertirne il verso. */
  private fun aggiornaAsse() {
    var a = asse
    repeat(3) {
      val b = DoubleArray(3) { i -> cov[i][0] * a[0] + cov[i][1] * a[1] + cov[i][2] * a[2] }
      val norma = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
      if (norma < 1e-12) return
      a = DoubleArray(3) { b[it] / norma }
    }
    asse = a
  }

  /**
   * Correlazione tra movimento respiratorio e frequenza cardiaca negli ultimi 60 s. Il cuore
   * accelera verso la fine dell'inspirazione: il movimento si confronta 1 s prima di ogni battito.
   */
  private fun controllaVerso() {
    if (storiaHr.size < 30 || storiaP.size < fs * 30) return
    val coppie =
        storiaHr.mapNotNull { (t, hr) -> valoreA(t - RITARDO_RSA_MS)?.let { it to hr } }
    if (coppie.size < 30) return
    val mp = coppie.sumOf { it.first } / coppie.size
    val mh = coppie.sumOf { it.second } / coppie.size
    var sxy = 0.0
    var sxx = 0.0
    var syy = 0.0
    for ((p, h) in coppie) {
      sxy += (p - mp) * (h - mh)
      sxx += (p - mp) * (p - mp)
      syy += (h - mh) * (h - mh)
    }
    if (sxx <= 0 || syy <= 0) return
    val r = sxy / sqrt(sxx * syy)
    if (abs(r) >= SOGLIA_CORRELAZIONE) {
      verso = if (r > 0) 1.0 else -1.0
      versoConfermato = true
    }
  }

  private fun valoreA(t: Long): Double? {
    if (storiaP.isEmpty() || t < storiaP.first().first || t > storiaP.last().first) return null
    // ricerca binaria del campione piu' vicino
    var lo = 0
    var hi = storiaP.size - 1
    while (lo < hi) {
      val m = (lo + hi) / 2
      if (storiaP[m].first < t) lo = m + 1 else hi = m
    }
    return storiaP[lo].second
  }

  companion object {
    private const val AVVIO_S = 8.0
    private const val MIN_MG = 2.0 // sotto: rumore, il fiore resta quasi fermo
    private const val STORIA_MS = 60_000L
    private const val RITARDO_RSA_MS = 1_000L
    private const val SOGLIA_CORRELAZIONE = 0.2
  }
}

/** Un campione dell'accelerometro in diretta: istante sull'orologio del telefono (ms), assi in mg. */
data class CampioneAcc(val tMs: Long, val x: Int, val y: Int, val z: Int)
