package com.wboelens.polarrecorder.biosleep.demo

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.random.Random

/** Una finestra di 5 minuti di esempio. */
data class FinestraDemo(val inizioMs: Long, val fc: Double, val rmssd: Double, val sdnn: Double, val pnn50: Double, val qualita: Double)

/** Una notte di esempio completa, con i campi delle tabelle nights e windows. */
data class NotteDemo(
    val data: LocalDate, // mattino del risveglio
    val inizioMs: Long,
    val fineMs: Long,
    val ipnoInizioMs: Long,
    val ipnogramma: String, // W L D R ogni 30 s
    val addormentamentoMs: Long,
    val risveglioMs: Long,
    val sonnoMin: Int,
    val profondoMin: Int,
    val leggeroMin: Int,
    val remMin: Int,
    val vegliaMin: Int,
    val fcMin: Double,
    val fc5MinBassi: Double,
    val fcMedia: Double,
    val rmssd: Double,
    val sdnn: Double,
    val pnn50: Double,
    val qualitaPct: Double,
    val battiti: Int,
    val finestre: List<FinestraDemo>,
)

/**
 * Notti di esempio per la modalita' demo (revisori del Play Store, schermate del negozio).
 * Sempre uguali a parita' di data (seme fisso): realistiche ma inventate, con cicli di ~90 minuti,
 * piu' sonno profondo a inizio notte e piu' REM verso il mattino, brevi risvegli.
 */
object NottiDemo {
  const val NOTTI = 14
  private const val EPOCA_MS = 30_000L

  fun genera(oggi: LocalDate, zona: ZoneId, n: Int = NOTTI): List<NotteDemo> =
      (n downTo 1).map { giorniFa -> notte(oggi.minusDays((giorniFa - 1).toLong()), zona) }

  fun notte(mattino: LocalDate, zona: ZoneId): NotteDemo {
    val r = Random(mattino.toEpochDay())
    val sera = mattino.minusDays(1)
    // A letto tra 22:45 e 23:35, in piedi tra 6:25 e 7:15
    val inizio = sera.atTime(LocalTime.of(22, 45)).plusMinutes(r.nextLong(0, 50)).atZone(zona).toInstant().toEpochMilli()
    val fine = mattino.atTime(LocalTime.of(6, 25)).plusMinutes(r.nextLong(0, 50)).atZone(zona).toInstant().toEpochMilli()
    val epoche = ((fine - inizio) / EPOCA_MS).toInt()
    val ip = ipnogramma(epoche, r)
    val primoSonno = ip.indexOfFirst { it != 'W' }
    val ultimoSonno = ip.indexOfLast { it != 'W' }
    fun min(c: Char) = (ip.count { it == c } / 2.0).roundToInt()
    val veglia = (ip.substring(primoSonno, ultimoSonno + 1).count { it == 'W' } / 2.0).roundToInt()

    // Una notte su sette un po' peggiore (alcol, tardi): HRV piu' bassa, FC piu' alta
    val storta = mattino.toEpochDay() % 7 == 3L
    val fcBassa = 45.0 + r.nextDouble(-1.5, 1.5) + if (storta) 4 else 0
    val rmssd = 54.0 + r.nextDouble(-5.0, 5.0) - if (storta) 12 else 0
    val finestre = finestre(inizio, fine, ip, fcBassa, rmssd, r)
    val fcMedia = finestre.map { it.fc }.average()
    return NotteDemo(
        data = mattino, inizioMs = inizio, fineMs = fine, ipnoInizioMs = inizio, ipnogramma = ip,
        addormentamentoMs = inizio + primoSonno * EPOCA_MS, risveglioMs = inizio + (ultimoSonno + 1) * EPOCA_MS,
        sonnoMin = min('L') + min('D') + min('R'), profondoMin = min('D'), leggeroMin = min('L'), remMin = min('R'),
        vegliaMin = veglia,
        fcMin = r1(fcBassa - 3), fc5MinBassi = r1(fcBassa), fcMedia = r1(fcMedia),
        rmssd = r1(rmssd), sdnn = r1(rmssd * 1.3 + r.nextDouble(-3.0, 3.0)), pnn50 = r1(rmssd * 0.55),
        qualitaPct = r1(if (storta) 86.0 else 95.0 + r.nextDouble(0.0, 4.5)),
        battiti = (fcMedia * (fine - inizio) / 60_000).roundToInt(),
        finestre = finestre,
    )
  }

  /** Addormentamento 8-20', poi cicli da ~90' (D che cala, R che cresce), risvegli brevi, sveglia. */
  private fun ipnogramma(epoche: Int, r: Random): String {
    val sb = StringBuilder()
    fun metti(c: Char, minuti: Double) = repeat((minuti * 2).roundToInt().coerceAtLeast(1)) { sb.append(c) }
    metti('W', r.nextDouble(8.0, 20.0))
    var ciclo = 0
    while (sb.length < epoche - 10) {
      val profondo = (32.0 - ciclo * 8).coerceAtLeast(3.0) + r.nextDouble(-4.0, 4.0)
      val rem = (8.0 + ciclo * 5).coerceAtMost(28.0) + r.nextDouble(-3.0, 3.0)
      metti('L', r.nextDouble(20.0, 30.0))
      metti('D', profondo)
      metti('L', r.nextDouble(12.0, 20.0))
      metti('R', rem)
      if (r.nextDouble() < 0.45) metti('W', r.nextDouble(1.0, 5.0)) // breve risveglio fra i cicli
      ciclo++
    }
    sb.setLength(epoche - 10)
    repeat(10) { sb.append('W') } // ultimi 5 minuti svegli
    return sb.toString()
  }

  private fun finestre(inizio: Long, fine: Long, ip: String, fcBassa: Double, rmssd: Double, r: Random): List<FinestraDemo> {
    val out = mutableListOf<FinestraDemo>()
    var t = inizio
    while (t + 300_000 <= fine) {
      val k = ((t - inizio) / EPOCA_MS).toInt().coerceIn(0, ip.length - 1)
      val (dFc, fRm) =
          when (ip[k]) {
            'D' -> -1.0 to 1.25
            'R' -> 4.0 to 0.8
            'W' -> 9.0 to 0.7
            else -> 1.5 to 1.0
          }
      val fc = fcBassa + dFc + r.nextDouble(0.0, 2.0)
      val rm = rmssd * fRm + r.nextDouble(-4.0, 4.0)
      out += FinestraDemo(t, r1(fc), r1(rm), r1(rm * 1.3), r1((rm * 0.55).coerceAtLeast(0.0)), r1(97.0 + r.nextDouble(0.0, 3.0)))
      t += 300_000
    }
    return out
  }

  private fun r1(v: Double) = (v * 10).roundToInt() / 10.0
}
