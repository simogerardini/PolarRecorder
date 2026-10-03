package com.wboelens.polarrecorder.biosleep.live

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Respiro letto dal battito (aritmia sinusale respiratoria): inspirando il cuore accelera e gli
 * intervalli RR si accorciano, espirando rallenta e si allungano. Ogni RR viene confrontato con la
 * media degli ultimi battiti: piu' corto della media = inspirazione = fiore che si apre.
 *
 * Filtro artefatti come da specifica GATT del progetto: RR fuori da 300-2000 ms, oppure diverso
 * di oltre il 20% dalla mediana dei 10 precedenti, viene ignorato.
 */
class RespiroRsa(private val finestra: Int = 20) {
  private val rr = ArrayDeque<Double>()

  /** Battiti al minuto dagli ultimi 5 RR validi (mediana), null finche' non ce ne sono 3. */
  var bpm: Int? = null
    private set

  /** Aggiunge un RR. Ritorna l'apertura del fiore (0 = chiuso, 1 = aperto) o null se non ancora stimabile. */
  fun aggiungi(rrMs: Int): Float? {
    val v = rrMs.toDouble()
    if (v < 300 || v > 2000) return null
    if (rr.size >= 3) {
      val m = mediana(rr.takeLast(10))
      if (abs(v - m) > 0.2 * m) return null // battito ectopico o artefatto
    }
    rr.addLast(v)
    while (rr.size > finestra) rr.removeFirst()
    if (rr.size >= 3) bpm = (60_000 / mediana(rr.takeLast(5))).toInt()
    if (rr.size < 6) return null
    val media = rr.average()
    val sd = sqrt(rr.sumOf { (it - media) * (it - media) } / rr.size).coerceAtLeast(5.0)
    val z = ((v - media) / sd).coerceIn(-2.0, 2.0)
    return (0.5 - 0.25 * z).toFloat() // RR corto (z < 0) -> piu' aperto
  }

  private fun mediana(xs: List<Double>): Double {
    val s = xs.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
  }
}
