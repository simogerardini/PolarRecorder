package com.wboelens.polarrecorder.biosleep.auto

import java.time.Instant
import java.time.ZoneId

/** Inizio e fine di una notte registrata (ms). */
data class NightTimes(val startMs: Long, val endMs: Long)

/**
 * Abitudini apprese dalle notti registrate.
 * Gli orari serali sono in "minuti dopo mezzogiorno" (es. 22:30 = 630, 00:30 = 750), cosi' le
 * notti a cavallo della mezzanotte non spezzano i calcoli. Gli orari del mattino sono in minuti
 * del giorno (06:44 = 404).
 */
data class Habits(
    val nightsUsed: Int,
    val nightsRequired: Int,
    val bedtimeFromNoon: Int?,
    val bedtimeToNoon: Int?,
    val morningFromMinute: Int,
) {
  val learned: Boolean
    get() = nightsUsed >= nightsRequired && bedtimeFromNoon != null && bedtimeToNoon != null
}

/** Impara gli orari di sonno dalle notti gia' registrate. Nessuna dipendenza Android. */
object HabitLearner {
  const val REQUIRED_NIGHTS = 7
  private const val MIN_NIGHT_HOURS = 4.0 // le prove brevi non contano
  private const val LOOKBACK_DAYS = 28L // contano solo le abitudini recenti
  private const val BEDTIME_MARGIN_BEFORE = 30 // minuti prima del 10° percentile
  private const val BEDTIME_MARGIN_AFTER = 60 // minuti dopo il 90° percentile
  private const val MORNING_MARGIN = 90 // stop possibile da 90' prima del risveglio piu' precoce
  private const val DEFAULT_MORNING_FROM = 4 * 60 // 04:00 durante l'apprendimento
  private const val EARLIEST_MORNING = 3 * 60 // mai prima delle 03:00
  const val MORNING_UNTIL = 12 * 60 // lo stop automatico vale fino a mezzogiorno
  private const val MINUTES_PER_DAY = 24 * 60
  private const val NOON = 12 * 60

  fun learn(nights: List<NightTimes>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Habits {
    val since = nowMs - LOOKBACK_DAYS * 86_400_000L
    val valid =
        nights.filter { it.startMs >= since && (it.endMs - it.startMs) >= MIN_NIGHT_HOURS * 3_600_000 }
    if (valid.size < REQUIRED_NIGHTS) {
      return Habits(valid.size, REQUIRED_NIGHTS, null, null, DEFAULT_MORNING_FROM)
    }
    val bed = valid.map { minutesAfterNoon(it.startMs, zone).toDouble() }.sorted()
    val wake = valid.map { minuteOfDay(it.endMs, zone).toDouble() }.sorted()
    val from = (percentile(bed, 10.0) - BEDTIME_MARGIN_BEFORE).toInt().coerceAtLeast(0)
    val to = (percentile(bed, 90.0) + BEDTIME_MARGIN_AFTER).toInt().coerceAtMost(MINUTES_PER_DAY - 1)
    val morning = (percentile(wake, 10.0) - MORNING_MARGIN).toInt().coerceIn(EARLIEST_MORNING, MORNING_UNTIL)
    return Habits(valid.size, REQUIRED_NIGHTS, from, to, morning)
  }

  /** Vero se ora siamo nella fascia del mattino in cui lo stop automatico e' permesso. */
  fun isMorning(nowMs: Long, habits: Habits, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    val m = minuteOfDay(nowMs, zone)
    return m >= habits.morningFromMinute && m < MORNING_UNTIL
  }

  /** Vero se ora siamo nella fascia serale appresa (serve all'avvio automatico). */
  fun isBedtime(nowMs: Long, habits: Habits, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    if (!habits.learned) return false
    val m = minutesAfterNoon(nowMs, zone)
    return m >= habits.bedtimeFromNoon!! && m <= habits.bedtimeToNoon!!
  }

  fun minutesAfterNoon(ms: Long, zone: ZoneId): Int =
      (minuteOfDay(ms, zone) - NOON + MINUTES_PER_DAY) % MINUTES_PER_DAY

  /** Converte "minuti dopo mezzogiorno" in testo "HH:mm". */
  fun noonMinutesToClock(m: Int): String {
    val dayMinute = (m + NOON) % MINUTES_PER_DAY
    return "%02d:%02d".format(dayMinute / 60, dayMinute % 60)
  }

  fun minutesToClock(m: Int): String = "%02d:%02d".format(m / 60, m % 60)

  private fun minuteOfDay(ms: Long, zone: ZoneId): Int {
    val t = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
    return t.hour * 60 + t.minute
  }

  private fun percentile(sorted: List<Double>, p: Double): Double {
    val pos = (sorted.size - 1) * p / 100.0
    val lo = pos.toInt()
    val hi = minOf(lo + 1, sorted.size - 1)
    return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
  }
}
