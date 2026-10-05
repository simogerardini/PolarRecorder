package com.wboelens.polarrecorder.biosleep.training

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wboelens.polarrecorder.biosleep.readiness.PyJson
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.roundToInt

/** Tempo in una zona: nome (Z1...), secondi, intervallo leggibile ("144-153 bpm"). */
data class TempoZona(val nome: String, val secondi: Int, val intervallo: String?)

/** Un intervallo di Intervals.icu (icu_intervals): lavoro o recupero, con le sue medie. */
data class Intervallo(
    val lavoro: Boolean,
    val inizioS: Int,
    val fineS: Int,
    val durataS: Int,
    val distanzaM: Double?,
    val watt: Int?,
    val fcMedia: Int?,
    val fcMax: Int?,
    val velocita: Double?,
    val cadenza: Int?,
    val zona: Int?,
)

data class DettaglioAttivita(
    val id: String,
    val nome: String,
    val tipo: String?,
    val inizio: LocalDateTime?,
    val dispositivo: String?,
    val durataS: Int?,
    val distanzaM: Double?,
    val tss: Int?,
    val intensitaPct: Double?,
    val wattNormalizzati: Int?,
    val wattMedi: Int?,
    val fcMedia: Int?,
    val fcMax: Int?,
    val velocitaMedia: Double?,
    val cadenza: Int?,
    val dislivello: Double?,
    val calorie: Int?,
    val disaccoppiamento: Double?,
    val efficienza: Double?,
    val rpe: Int?,
    val aderenza: Double?,
    val vascaM: Double?,
    val zonePotenza: List<TempoZona>,
    val zoneFc: List<TempoZona>,
    val zonePasso: List<TempoZona>,
    val intervalli: List<Intervallo>,
) {
  val sport: Sport get() = Sport.da(tipo)
}

/** Serie nel tempo ridotte per i grafici: tempi in secondi e valori per tipo (null = buco). */
data class Flussi(val tempoS: List<Int>, val serie: Map<String, List<Double?>>)

object DettaglioParser {
  /** Le serie che mostra la schermata. Le altre (hrv, latlng, temperatura...) non si scaricano. */
  val TIPI = listOf("time", "heartrate", "watts", "velocity_smooth", "cadence", "altitude")

  private fun num(o: JsonObject, k: String) = PyJson.num(o.get(k))?.v

  private fun int(o: JsonObject, k: String) = num(o, k)?.roundToInt()

  private fun str(o: JsonObject, k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.let { PyJson.str(it) }?.takeIf { it.isNotBlank() }

  private fun numeri(e: JsonElement?): List<Double>? =
      e?.takeIf { it.isJsonArray }?.asJsonArray?.map { PyJson.num(it)?.v ?: 0.0 }

  fun attivita(o: JsonObject): DettaglioAttivita? {
    val id = str(o, "id") ?: return null
    val ftp = num(o, "icu_ftp")
    // Potenza: icu_zone_times [{id: Z1, secs}], soglie in % di FTP (icu_power_zones); SS = sweet spot, a parte
    val sogliePot = numeri(o.get("icu_power_zones"))
    val zonePotenza =
        o.get("icu_zone_times")?.takeIf { it.isJsonArray }?.asJsonArray.orEmpty().mapNotNull { el ->
          val z = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
          val nome = str(z, "id") ?: return@mapNotNull null
          if (!nome.startsWith("Z")) return@mapNotNull null
          val i = nome.drop(1).toIntOrNull()?.minus(1) ?: return@mapNotNull null
          TempoZona(nome, int(z, "secs") ?: 0, intervallo(sogliePot, i, ftp?.let { f -> { pct: Double -> "${(f * pct / 100).roundToInt()}" } }, "W"))
        }
    // FC: icu_hr_zone_times [secs...] con soglie superiori in bpm (icu_hr_zones)
    val soglieFc = numeri(o.get("icu_hr_zones"))
    val zoneFc =
        numeri(o.get("icu_hr_zone_times")).orEmpty().mapIndexed { i, s ->
          TempoZona("Z${i + 1}", s.roundToInt(), intervallo(soglieFc, i, { b: Double -> "${b.roundToInt()}" }, "bpm"))
        }
    // Passo: pace_zone_times con soglie in % del passo di soglia (threshold_pace, m/s)
    val sogliePasso = numeri(o.get("pace_zones"))
    val soglia = num(o, "threshold_pace")
    val nuoto = Sport.da(str(o, "type")) == Sport.NUOTO
    val zonePasso =
        numeri(o.get("pace_zone_times")).orEmpty().mapIndexed { i, s ->
          val fmt: ((Double) -> String)? = soglia?.let { t -> { pct: Double -> Ritmo.passo(t * pct / 100, nuoto, conUnita = false) } }
          TempoZona("Z${i + 1}", s.roundToInt(), intervallo(sogliePasso, i, fmt, if (nuoto) "/100m" else "/km", decrescente = true))
        }
    val intervalli =
        o.get("icu_intervals")?.takeIf { it.isJsonArray }?.asJsonArray.orEmpty().mapNotNull { el ->
          val v = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
          Intervallo(
              lavoro = str(v, "type") == "WORK",
              inizioS = int(v, "start_index") ?: 0,
              fineS = int(v, "end_index") ?: 0,
              durataS = int(v, "moving_time") ?: 0,
              distanzaM = num(v, "distance"),
              watt = int(v, "average_watts"),
              fcMedia = int(v, "average_heartrate"),
              fcMax = int(v, "max_heartrate"),
              velocita = num(v, "average_speed"),
              cadenza = int(v, "average_cadence"),
              zona = int(v, "zone"),
          )
        }
    return DettaglioAttivita(
        id = id,
        nome = str(o, "name") ?: "Attività",
        tipo = str(o, "type"),
        inizio = str(o, "start_date_local")?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
        dispositivo = str(o, "device_name"),
        durataS = int(o, "moving_time"),
        distanzaM = num(o, "distance")?.takeIf { it > 0 },
        tss = int(o, "icu_training_load"),
        intensitaPct = num(o, "icu_intensity"),
        wattNormalizzati = int(o, "icu_weighted_avg_watts"),
        wattMedi = int(o, "icu_average_watts"),
        fcMedia = int(o, "average_heartrate"),
        fcMax = int(o, "max_heartrate"),
        velocitaMedia = num(o, "average_speed")?.takeIf { it > 0 } ?: num(o, "pace")?.takeIf { it > 0 },
        cadenza = int(o, "average_cadence"),
        dislivello = num(o, "total_elevation_gain")?.takeIf { it > 0 },
        calorie = int(o, "calories"),
        disaccoppiamento = num(o, "decoupling"),
        efficienza = num(o, "icu_efficiency_factor"),
        rpe = int(o, "icu_rpe"),
        aderenza = num(o, "compliance")?.takeIf { it > 0 },
        vascaM = num(o, "pool_length"),
        zonePotenza = zonePotenza.filter { it.secondi > 0 || zonePotenza.any { z -> z.secondi > 0 } },
        zoneFc = zoneFc,
        zonePasso = zonePasso,
        intervalli = intervalli,
    )
  }

  fun attivita(testo: String): DettaglioAttivita? =
      runCatching { JsonParser.parseString(testo).asJsonObject }.getOrNull()?.let { attivita(it) }

  /** "144-153 bpm"; soglie = limiti superiori; la prima zona "< x", l'ultima "> x" (999 = senza limite). */
  private fun intervallo(
      soglie: List<Double>?,
      i: Int,
      fmt: ((Double) -> String)?,
      unita: String,
      decrescente: Boolean = false,
  ): String? {
    if (soglie == null || fmt == null || i !in soglie.indices) return null
    val alto = soglie[i].takeIf { it < 900 }
    val basso = if (i > 0) soglie[i - 1] else null
    return when {
      basso == null && alto != null -> (if (decrescente) "> " else "< ") + "${fmt(alto)} $unita"
      alto == null && basso != null -> (if (decrescente) "< " else "> ") + "${fmt(basso)} $unita"
      basso != null && alto != null -> "${fmt(basso)}–${fmt(alto)} $unita"
      else -> null
    }
  }

  private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()

  /**
   * Riduce le serie di Intervals.icu (un punto al secondo, anche 5000 per un'ora e mezza) a circa
   * [punti] medie consecutive, solo per i tipi mostrati. E' cio' che si salva nella cache.
   */
  fun riduci(streamsJson: String, punti: Int = 600): String {
    val lista = runCatching { JsonParser.parseString(streamsJson).asJsonArray }.getOrNull() ?: return "{}"
    val perTipo = HashMap<String, List<Double?>>()
    for (el in lista) {
      val s = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
      val tipo = str(s, "type") ?: continue
      if (tipo !in TIPI) continue
      val dati = s.get("data")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
      perTipo[tipo] = dati.map { PyJson.num(it)?.v }
    }
    val tempo = perTipo["time"] ?: return "{}"
    val passo = maxOf(1, (tempo.size + punti - 1) / punti)
    val out = JsonObject()
    for ((tipo, valori) in perTipo) {
      val ridotti = JsonArray()
      var i = 0
      while (i < valori.size) {
        val blocco = valori.subList(i, minOf(i + passo, valori.size))
        if (tipo == "time") {
          ridotti.add(blocco.first()?.roundToInt() ?: 0)
        } else {
          val validi = blocco.filterNotNull()
          if (validi.isEmpty()) ridotti.add(com.google.gson.JsonNull.INSTANCE)
          else ridotti.add(Math.round(validi.average() * 100) / 100.0)
        }
        i += passo
      }
      out.add(tipo, ridotti)
    }
    return out.toString()
  }

  /** Le serie ridotte salvate in cache -> Flussi. Le serie tutte nulle o tutte a zero si scartano. */
  fun flussi(ridotti: String): Flussi? {
    val o = runCatching { JsonParser.parseString(ridotti).asJsonObject }.getOrNull() ?: return null
    val tempo = o.get("time")?.takeIf { it.isJsonArray }?.asJsonArray?.map { PyJson.num(it)?.v?.roundToInt() ?: 0 } ?: return null
    val serie =
        TIPI.filter { it != "time" }
            .mapNotNull { t ->
              val v = o.get(t)?.takeIf { it.isJsonArray }?.asJsonArray?.map { e -> PyJson.num(e)?.v } ?: return@mapNotNull null
              if (v.none { it != null && it != 0.0 }) null else t to v
            }
            .toMap()
    return Flussi(tempo, serie)
  }
}

/** Passo e velocita' nei formati dello sport: corsa min/km, nuoto min/100 m, bici km/h. */
object Ritmo {
  fun passo(metriAlSecondo: Double, nuoto: Boolean, conUnita: Boolean = true): String {
    if (metriAlSecondo <= 0.05) return "—"
    val secondi = ((if (nuoto) 100.0 else 1000.0) / metriAlSecondo).roundToInt()
    val testo = "${secondi / 60}:${"%02d".format(secondi % 60)}"
    return if (conUnita) testo + (if (nuoto) " /100m" else " /km") else testo
  }

  fun kmh(metriAlSecondo: Double): String = String.format(Locale.ITALY, "%.1f km/h", metriAlSecondo * 3.6)

  /** Il formato giusto per lo sport. */
  fun perSport(v: Double, sport: Sport): String =
      when (sport) {
        Sport.CORSA -> passo(v, false)
        Sport.NUOTO -> passo(v, true)
        else -> kmh(v)
      }
}
