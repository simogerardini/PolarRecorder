package com.wboelens.polarrecorder.biosleep.age

import kotlin.math.max

/** Un'attivita' di Intervals.icu con i campi che servono alla stima. */
data class ActivitySample(
    val type: String,
    val distanceM: Double?,
    val movingS: Double?,
    val avgHr: Double?,
    val maxHr: Double?,
    val elevationGainM: Double?,
    val avgWatts: Double?,
)

data class Vo2Estimate(val vo2max: Double, val runs: Int, val rides: Int, val hrMax: Double, val hrRest: Double)

/**
 * Stima del VO2max da allenamenti normali (non serve un test massimale), con lo stesso principio
 * dei sistemi degli orologi sportivi:
 *  1. il costo in ossigeno dello sforzo si ricava dalle equazioni metaboliche ACSM:
 *     corsa in piano VO2 = 0,2 x velocita' (m/min) + 3,5; bici VO2 = 10,8 x W/kg + 7
 *  2. la frazione di riserva cardiaca usata (%HRR) equivale alla frazione di riserva di VO2
 *     (relazione di Swain): %HRR = (FC media - FC riposo) / (FC max - FC riposo)
 *  3. VO2max = 3,5 + (VO2 sforzo - 3,5) / %HRR
 * Si usa la mediana di tutte le attivita' adatte degli ultimi 90 giorni: un singolo allenamento
 * in salita, al caldo o stanchi pesa poco.
 * FC a riposo = FC media notturna di BioSleep; FC max = la seconda piu' alta registrata (la prima
 * puo' essere un artefatto).
 */
object Vo2Estimator {
  private const val MIN_DURATION_S = 20 * 60
  private const val MIN_HRR = 0.55 // sforzi troppo leggeri: la relazione FC-VO2 e' meno lineare
  private const val MAX_HRR = 0.92 // sforzi quasi massimali: la FC media non e' stabile
  private const val MAX_GRADE = 0.015 // corsa: piu' dell'1,5% di dislivello medio -> esclusa
  private const val MIN_ACTIVITIES = 3
  private val RUN_TYPES = setOf("Run", "VirtualRun")
  private val RIDE_TYPES = setOf("Ride", "VirtualRide", "GravelRide")

  fun estimate(activities: List<ActivitySample>, hrRest: Double, weightKg: Double?): Vo2Estimate? {
    val maxes = activities.mapNotNull { it.maxHr }.filter { it in 120.0..230.0 }.sortedDescending()
    if (maxes.size < 2) return null
    val hrMax = maxes[1]
    if (hrMax - hrRest < 60) return null

    var runs = 0
    var rides = 0
    val estimates = mutableListOf<Double>()
    for (a in activities) {
      val t = a.movingS ?: continue
      val hr = a.avgHr ?: continue
      if (t < MIN_DURATION_S) continue
      val hrr = (hr - hrRest) / (hrMax - hrRest)
      if (hrr !in MIN_HRR..MAX_HRR) continue
      val effortVo2 =
          when (a.type) {
            in RUN_TYPES -> {
              val d = a.distanceM ?: continue
              val grade = (a.elevationGainM ?: 0.0) / max(d, 1.0)
              if (grade > MAX_GRADE) continue
              val v = d / (t / 60) // m/min
              if (v !in 130.0..400.0) continue // camminata o dati GPS anomali
              0.2 * v + 3.5
            }
            in RIDE_TYPES -> {
              val w = a.avgWatts ?: continue
              val kg = weightKg ?: continue
              if (w < 60) continue
              10.8 * w / kg + 7
            }
            else -> continue
          }
      val vo2 = 3.5 + (effortVo2 - 3.5) / hrr
      if (vo2 !in 20.0..90.0) continue
      estimates += vo2
      if (a.type in RUN_TYPES) runs++ else rides++
    }
    if (estimates.size < MIN_ACTIVITIES) return null
    val s = estimates.sorted()
    val median = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    return Vo2Estimate(median, runs, rides, hrMax, hrRest)
  }
}
