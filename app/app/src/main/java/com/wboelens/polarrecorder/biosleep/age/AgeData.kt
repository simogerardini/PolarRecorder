package com.wboelens.polarrecorder.biosleep.age

import android.content.Context
import android.database.SQLException
import com.wboelens.polarrecorder.biosleep.SleepDb
import com.wboelens.polarrecorder.biosleep.auto.HabitLearner
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsClient
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsSettings
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Data di nascita e sesso, nella memoria privata dell'app. Piu' lo storico dell'eta' stimata. */
class AgeProfileStore(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  var birthDate: LocalDate?
    get() = prefs.getString(KEY_BIRTH, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    set(v) = prefs.edit().putString(KEY_BIRTH, v?.toString()).apply()

  var sex: Sex?
    get() = prefs.getString(KEY_SEX, null)?.let { s -> Sex.entries.firstOrNull { it.name == s } }
    set(v) = prefs.edit().putString(KEY_SEX, v?.name).apply()

  /** Storico: data -> eta' stimata (una al giorno), per il ritmo d'invecchiamento. */
  fun history(): Map<LocalDate, Double> {
    val json = runCatching { JSONObject(prefs.getString(KEY_HISTORY, "{}") ?: "{}") }.getOrNull() ?: return emptyMap()
    return json.keys().asSequence().mapNotNull { k ->
      runCatching { LocalDate.parse(k) to json.getDouble(k) }.getOrNull()
    }.toMap()
  }

  fun saveToday(age: Double, chronological: Double) {
    val json = runCatching { JSONObject(prefs.getString(KEY_HISTORY, "{}") ?: "{}") }.getOrNull() ?: JSONObject()
    // si salva la differenza dall'eta' anagrafica, che non cresce da sola col calendario
    json.put(LocalDate.now().toString(), age - chronological)
    prefs.edit().putString(KEY_HISTORY, json.toString()).apply()
  }

  companion object {
    private const val PREFS = "biosleep_age"
    private const val KEY_BIRTH = "birth_date"
    private const val KEY_SEX = "sex"
    private const val KEY_HISTORY = "history"
  }
}

/** Tutto cio' che serve alla schermata. */
data class BioAgeScreenData(
    val outcome: BioAgeOutcome,
    /** Notti degli ultimi 28 giorni, e quante escluse e perche'. */
    val nightsRecent: Int,
    val nightsLowQuality: Int,
    val nightsNoStages: Int,
    /** Anni biologici per anno di calendario (1,0 = come il calendario); null se < 90 giorni. */
    val pace: Double?,
    val historyDays: Int,
)

/** Raccoglie i dati (database + Intervals.icu) e calcola. Da chiamare fuori dal main thread. */
object AgeRepository {
  private const val WINDOW_DAYS = 28L
  private const val VO2_DAYS = 60L
  private const val ACTIVITY_DAYS = 90L
  private const val MIN_QUALITY = 80.0
  private const val PACE_MIN_DAYS = 90
  private const val ESTIMATE_REL_SD = 0.10 // stima da allenamenti: circa +-10%

  fun load(context: Context, birth: LocalDate, sex: Sex): BioAgeScreenData {
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val since = now - WINDOW_DAYS * 86_400_000L

    // Notti valide degli ultimi 28 giorni
    val nights =
        try {
          SleepDb.get(context).listNights()
        } catch (e: SQLException) {
          emptyList()
        }
    val recent = nights.filter { it.summary.endMs >= since }
    val lowQuality = recent.count { (it.summary.qualityPct ?: 0.0) < MIN_QUALITY }
    val noStages = recent.count { (it.summary.qualityPct ?: 0.0) >= MIN_QUALITY && (it.stages?.tstMin ?: 0) <= 0 }
    val valid =
        recent.filter { (it.summary.qualityPct ?: 0.0) >= MIN_QUALITY && (it.stages?.tstMin ?: 0) > 0 }
    val sleepMinutes = valid.map { it.stages!!.tstMin.toDouble() }
    val midpoints =
        valid.map {
          val st = it.stages!!
          val a = st.sleepOnsetMs ?: it.summary.startMs
          val b = st.sleepEndMs ?: it.summary.endMs
          HabitLearner.minutesAfterNoon((a + b) / 2, zone).toDouble()
        }
    // FC "a riposo" per la stima del VO2max: mediana della FC media notturna
    val hrRest = valid.map { it.summary.hrAvg }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

    // Intervals.icu: wellness (VO2max, peso) e attivita' (allenamento, stima VO2max)
    val settings = IntervalsSettings(context)
    var vo2: Double? = null
    var vo2Source = "Intervals.icu"
    var vo2Sd = 0.05
    var weekly: List<Double>? = null
    if (settings.isConfigured) {
      val wellness = fetchWellness(settings.apiKey)
      val activities = fetchActivities(settings.apiKey)
      vo2 = wellness?.vo2max
      if (activities != null) weekly = weeklyMinutes(activities)
      if (vo2 == null && activities != null && hrRest != null) {
        Vo2Estimator.estimate(activities.map { it.sample }, hrRest, wellness?.weightKg)?.let {
          vo2 = it.vo2max
          vo2Sd = ESTIMATE_REL_SD
          vo2Source =
              "stimato da ${it.runs} corse e ${it.rides} uscite in bici, FC max ${it.hrMax.toInt()}"
        }
      }
    }

    val today = LocalDate.now()
    val chrono = ChronoUnit.DAYS.between(birth, today) / 365.2425
    val outcome =
        BioAgeEngine.compute(
            BioAgeInput(chrono, sex, sleepMinutes, midpoints, vo2, weekly, vo2Source, vo2Sd))

    val store = AgeProfileStore(context)
    if (outcome is BioAgeOutcome.Ready) store.saveToday(outcome.result.age, chrono)
    val history = store.history()
    return BioAgeScreenData(outcome, recent.size, lowQuality, noStages, pace(history), history.size)
  }

  /** Ritmo = 1 + pendenza (anni di differenza per anno). Serve uno storico di almeno 90 giorni. */
  private fun pace(history: Map<LocalDate, Double>): Double? {
    if (history.size < 2) return null
    val first = history.keys.min()
    val span = ChronoUnit.DAYS.between(first, history.keys.max())
    if (span < PACE_MIN_DAYS) return null
    val xs = history.keys.map { ChronoUnit.DAYS.between(first, it) / 365.2425 }
    val ys = history.values.toList()
    val mx = xs.average()
    val my = ys.average()
    var sxy = 0.0
    var sxx = 0.0
    for (i in xs.indices) {
      sxy += (xs[i] - mx) * (ys[i] - my)
      sxx += (xs[i] - mx) * (xs[i] - mx)
    }
    return if (sxx > 0) 1 + sxy / sxx else null
  }

  private class Wellness(val vo2max: Double?, val weightKg: Double?)

  private class Activity(val day: LocalDate, val zones: DoubleArray?, val sample: ActivitySample)

  /** VO2max piu' recente (60 giorni) e peso piu' recente (90 giorni) dal wellness. */
  private fun fetchWellness(apiKey: String): Wellness? {
    val newest = LocalDate.now()
    val oldest = newest.minusDays(ACTIVITY_DAYS)
    return try {
      val (code, text) =
          IntervalsClient.request(
              "GET", "${IntervalsClient.BASE_URL}/wellness?oldest=$oldest&newest=$newest", apiKey, null)
      if (code != 200) return null
      val arr = JSONArray(text)
      val days = (0 until arr.length()).map { arr.getJSONObject(it) }.sortedByDescending { it.optString("id") }
      fun num(o: JSONObject, k: String) = if (!o.has(k) || o.isNull(k)) null else o.getDouble(k)
      val vo2Limit = newest.minusDays(VO2_DAYS).toString()
      Wellness(
          vo2max = days.filter { it.optString("id") >= vo2Limit }.firstNotNullOfOrNull { num(it, "vo2max") }
              ?.takeIf { it > 10 },
          weightKg = days.firstNotNullOfOrNull { num(it, "weight") }?.takeIf { it in 30.0..200.0 },
      )
    } catch (e: IOException) {
      null
    } catch (e: JSONException) {
      null
    }
  }

  private fun fetchActivities(apiKey: String): List<Activity>? {
    val newest = LocalDate.now()
    val oldest = newest.minusDays(ACTIVITY_DAYS - 1)
    val fields =
        "start_date_local,type,distance,moving_time,average_heartrate,max_heartrate," +
            "total_elevation_gain,icu_average_watts,icu_hr_zone_times"
    return try {
      val (code, text) =
          IntervalsClient.request(
              "GET",
              "${IntervalsClient.BASE_URL}/activities?oldest=$oldest&newest=$newest&fields=$fields",
              apiKey,
              null)
      if (code != 200) return null
      val arr = JSONArray(text)
      (0 until arr.length()).mapNotNull { i ->
        val a = arr.getJSONObject(i)
        val day = runCatching { LocalDate.parse(a.optString("start_date_local").take(10)) }.getOrNull()
            ?: return@mapNotNull null
        fun num(k: String) = if (!a.has(k) || a.isNull(k)) null else a.optDouble(k, Double.NaN).takeIf { !it.isNaN() }
        val zArr = a.optJSONArray("icu_hr_zone_times")
        val zones = zArr?.let { z -> DoubleArray(z.length()) { z.optDouble(it, 0.0) } }
        Activity(
            day,
            zones,
            ActivitySample(
                type = a.optString("type"),
                distanceM = num("distance"),
                movingS = num("moving_time"),
                avgHr = num("average_heartrate"),
                maxHr = num("max_heartrate"),
                elevationGainM = num("total_elevation_gain"),
                avgWatts = num("icu_average_watts"),
            ),
        )
      }
    } catch (e: IOException) {
      null
    } catch (e: JSONException) {
      null
    }
  }

  /** Minuti moderati equivalenti per settimana (zone 1-2 x1, zone 3+ x2), ultime 4 settimane. */
  private fun weeklyMinutes(activities: List<Activity>): List<Double> {
    val today = LocalDate.now()
    val weeks = DoubleArray((WINDOW_DAYS / 7).toInt())
    for (a in activities) {
      val w = (ChronoUnit.DAYS.between(a.day, today) / 7).toInt()
      if (w !in weeks.indices) continue
      var minutes = 0.0
      a.zones?.forEachIndexed { z, secs -> minutes += if (z >= 2) 2 * secs / 60 else secs / 60 }
      if (minutes <= 0) minutes = (a.sample.movingS ?: 0.0) / 60 // senza fascia: moderato
      weeks[w] += minutes
    }
    return weeks.toList()
  }
}
