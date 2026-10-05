package com.wboelens.polarrecorder.biosleep.sonno

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Una notte vista dalle metriche del sonno. giorno = mattina del risveglio (come la wellness).
 * inizio/fine = addormentamento e risveglio finale stimati dalla stadiazione; aLetto = durata
 * della registrazione (tempo a letto). Minuti per fase dalla stadiazione BioSleep.
 */
data class NotteSonno(
    val giorno: LocalDate,
    val inizio: LocalDateTime,
    val fine: LocalDateTime,
    val aLettoMin: Int,
    val sonnoMin: Int,
    val profondoMin: Int,
    val remMin: Int,
    val vegliaMin: Int,
    /** Minuti dall'inizio della registrazione all'addormentamento (null = non stimabile). */
    val latenzaMin: Int? = null,
) {
  /** Punto centrale del sonno. */
  val centro: LocalDateTime get() = inizio.plusSeconds(Duration.between(inizio, fine).seconds / 2)
}

/** Minuti dopo mezzanotte di un orario notturno; le ore serali diventano negative (23:00 = -60). */
fun minutiNotte(t: LocalDateTime): Int {
  val m = t.hour * 60 + t.minute
  return if (m >= 12 * 60) m - 24 * 60 else m
}

fun orario(minutiNotte: Int): LocalTime = LocalTime.of(0, 0).plusMinutes(((minutiNotte % 1440) + 1440).toLong() % 1440)

private fun lineare(x: Double, punti: List<Pair<Double, Double>>): Double {
  if (x <= punti.first().first) return punti.first().second
  if (x >= punti.last().first) return punti.last().second
  for (i in 1 until punti.size) {
    val (x0, y0) = punti[i - 1]
    val (x1, y1) = punti[i]
    if (x <= x1) return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
  }
  return punti.last().second
}

// --- Fabbisogno e deficit ------------------------------------------------------------------------

/** Fabbisogno di sonno (ore) appreso dalle notti; 8 h finche' le notti sono meno di 14. */
data class Fabbisogno(val ore: Double, val appreso: Boolean, val notti: Int)

enum class LivelloDeficit(val etichetta: String) { NESSUNO("Nessuno"), BASSO("Basso"), MODERATO("Moderato"), ALTO("Alto") }

data class Deficit(val ore: Double, val livello: LivelloDeficit, val notti: Int)

/**
 * Logica scelta per BioSleep (le formule di Oura non sono pubbliche):
 * - fabbisogno = media del quarto di notti piu' lunghe degli ultimi 60 giorni, cioe' quanto dormi
 *   quando nessuno ti sveglia, limitata tra 7 e 9,5 h;
 * - deficit = "conto" degli ultimi 14 giorni: ogni notte aggiunge (fabbisogno - sonno), una notte
 *   piu' lunga del fabbisogno restituisce, e il conto non scende sotto zero. Le notti mancanti
 *   (fascia non indossata) non contano: meglio non stimare che inventare.
 */
object Debito {
  const val NOTTI_MINIME = 14
  const val FABBISOGNO_PREDEFINITO = 8.0

  fun fabbisogno(notti: List<NotteSonno>, oggi: LocalDate): Fabbisogno {
    val recenti = notti.filter { it.giorno > oggi.minusDays(60) && it.giorno <= oggi && it.sonnoMin > 0 }
    if (recenti.size < NOTTI_MINIME) return Fabbisogno(FABBISOGNO_PREDEFINITO, false, recenti.size)
    val lunghe = recenti.map { it.sonnoMin / 60.0 }.sortedDescending().take(maxOf(1, recenti.size / 4))
    return Fabbisogno(lunghe.average().coerceIn(7.0, 9.5), true, recenti.size)
  }

  fun deficit(notti: List<NotteSonno>, oggi: LocalDate, fabbisogno: Double): Deficit {
    val finestra = notti.filter { it.giorno > oggi.minusDays(14) && it.giorno <= oggi }.sortedBy { it.giorno }
    var conto = 0.0
    for (n in finestra) conto = maxOf(0.0, conto + fabbisogno - n.sonnoMin / 60.0)
    val livello =
        when {
          conto < 2 -> LivelloDeficit.NESSUNO
          conto < 5 -> LivelloDeficit.BASSO
          conto < 10 -> LivelloDeficit.MODERATO
          else -> LivelloDeficit.ALTO
        }
    return Deficit(conto, livello, finestra.size)
  }
}

// --- Cronotipo e orologio biologico --------------------------------------------------------------

enum class TipoCronotipo(val etichetta: String) {
  MOLTO_MATTUTINO("Decisamente mattutino"),
  MATTUTINO("Mattutino"),
  INTERMEDIO("Intermedio"),
  SERALE("Serale"),
  MOLTO_SERALE("Decisamente serale"),
}

/** Orari ottimali: centro del sonno ideale, e addormentamento/risveglio = centro -+ fabbisogno/2. */
data class Cronotipo(val tipo: TipoCronotipo, val centroMin: Int, val sonnoMin: Int, val svegliaMin: Int, val notti: Int)

enum class Allineamento(val etichetta: String) { IN_LINEA("In linea"), IN_ANTICIPO("In anticipo"), IN_RITARDO("In ritardo") }

/**
 * Cronotipo dal punto centrale del sonno nei giorni liberi (venerdi' e sabato notte), corretto
 * per il debito come nel questionario di Monaco (MCTQ, Roenneberg): nei giorni liberi si recupera
 * sonno e il centro slitta in avanti, quindi si toglie meta' della differenza di durata.
 * Ultimi 90 giorni, almeno 21 notti di cui 4 libere; senza abbastanza notti libere usa tutte le notti.
 */
object OrologioBiologico {
  const val NOTTI_MINIME = 21

  private fun libera(n: NotteSonno) = n.giorno.dayOfWeek.value in 6..7 // risveglio di sabato o domenica

  fun cronotipo(notti: List<NotteSonno>, oggi: LocalDate, fabbisognoOre: Double): Cronotipo? {
    val recenti = notti.filter { it.giorno > oggi.minusDays(90) && it.giorno <= oggi }
    if (recenti.size < NOTTI_MINIME) return null
    val libere = recenti.filter { libera(it) }.takeIf { it.size >= 4 } ?: recenti
    val centroLibero = libere.map { minutiNotte(it.centro) }.average()
    val durataLibera = libere.map { it.sonnoMin.toDouble() }.average()
    val durataMedia = recenti.map { it.sonnoMin.toDouble() }.average()
    val centro = (centroLibero - maxOf(0.0, durataLibera - durataMedia) / 2).roundToInt()
    val tipo =
        when {
          centro < 120 -> TipoCronotipo.MOLTO_MATTUTINO
          centro < 180 -> TipoCronotipo.MATTUTINO
          centro < 270 -> TipoCronotipo.INTERMEDIO
          centro < 330 -> TipoCronotipo.SERALE
          else -> TipoCronotipo.MOLTO_SERALE
        }
    val meta = (fabbisognoOre * 30).roundToInt()
    return Cronotipo(tipo, centro, centro - meta, centro + meta, recenti.size)
  }

  /** Scarto del centro di questa notte rispetto al cronotipo, in minuti (+ = in ritardo). */
  fun scarto(notte: NotteSonno, c: Cronotipo): Int = minutiNotte(notte.centro) - c.centroMin

  fun allineamento(scartoMin: Int): Allineamento =
      when {
        abs(scartoMin) <= 30 -> Allineamento.IN_LINEA
        scartoMin > 0 -> Allineamento.IN_RITARDO
        else -> Allineamento.IN_ANTICIPO
      }
}

// --- Punteggio del sonno -------------------------------------------------------------------------

data class Contributo(val nome: String, val punti: Int, val peso: Int)

data class Punteggio(val totale: Int, val contributi: List<Contributo>)

/**
 * Punteggio 0-100, media pesata di sette contributi (pesi scelti per BioSleep):
 * durata rispetto al fabbisogno 30, efficienza 15, sonno profondo 15, REM 15, continuita' 10,
 * latenza 5, orario rispetto al cronotipo 10 (finche' il cronotipo non c'e', il suo peso va alla
 * durata). Latenza: come Oura, ideale 10-20 minuti; meno di 5 indica stanchezza accumulata.
 * Profondo e REM vengono dalla stadiazione stimata da battito e respiro: pesano meno della durata,
 * che e' la misura piu' affidabile.
 */
object PunteggioSonno {
  fun calcola(n: NotteSonno, fabbisognoOre: Double, cronotipo: Cronotipo?): Punteggio {
    val ore = n.sonnoMin / 60.0
    val durata = lineare(ore / fabbisognoOre, listOf(0.5 to 0.0, 0.75 to 60.0, 0.9 to 85.0, 1.0 to 100.0))
    val efficienza =
        lineare(if (n.aLettoMin > 0) n.sonnoMin.toDouble() / n.aLettoMin else 0.0, listOf(0.65 to 0.0, 0.75 to 50.0, 0.85 to 85.0, 0.9 to 100.0))
    val pctProfondo = if (n.sonnoMin > 0) n.profondoMin * 100.0 / n.sonnoMin else 0.0
    val pctRem = if (n.sonnoMin > 0) n.remMin * 100.0 / n.sonnoMin else 0.0
    val profondo = lineare(pctProfondo, listOf(5.0 to 20.0, 10.0 to 60.0, 15.0 to 100.0))
    val rem = lineare(pctRem, listOf(10.0 to 30.0, 15.0 to 70.0, 20.0 to 100.0))
    val continuita = lineare(n.vegliaMin.toDouble(), listOf(20.0 to 100.0, 60.0 to 50.0, 120.0 to 0.0))
    val contributi =
        mutableListOf(
            Contributo("Durata", durata.roundToInt(), if (cronotipo == null) 40 else 30),
            Contributo("Efficienza", efficienza.roundToInt(), 15),
            Contributo("Sonno profondo", profondo.roundToInt(), 15),
            Contributo("Sonno REM", rem.roundToInt(), 15),
            Contributo("Continuità", continuita.roundToInt(), 10),
        )
    n.latenzaMin?.let { l ->
      val punti = lineare(l.toDouble(), listOf(0.0 to 60.0, 5.0 to 80.0, 10.0 to 100.0, 20.0 to 100.0, 30.0 to 80.0, 60.0 to 30.0))
      contributi.add(Contributo("Latenza", punti.roundToInt(), 5))
    }
    if (cronotipo != null) {
      val scarto = abs(OrologioBiologico.scarto(n, cronotipo)).toDouble()
      contributi.add(Contributo("Orario", lineare(scarto, listOf(30.0 to 100.0, 120.0 to 40.0, 180.0 to 0.0)).roundToInt(), 10))
    }
    val totale = contributi.sumOf { it.punti * it.peso } / contributi.sumOf { it.peso }
    return Punteggio(totale, contributi)
  }

  /** Come le fasce di Oura: 85+ ottimo, 70-84 buono, sotto 70 da curare. */
  fun etichetta(p: Int) = when {
    p >= 85 -> "Ottimo"
    p >= 70 -> "Buono"
    else -> "Da curare"
  }
}
