package com.wboelens.polarrecorder.biosleep.training

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.wboelens.polarrecorder.biosleep.readiness.PyJson
import kotlin.math.roundToInt

/** Un tratto del grafico della seduta: durata e intensita' (in % del riferimento) all'inizio e alla fine. */
data class Segmento(val durataS: Int, val da: Double, val a: Double, val recupero: Boolean)

/** Una riga della struttura da mostrare: livello 0 = passo principale, 1 = dentro una ripetuta. */
data class RigaPasso(val livello: Int, val testo: String)

/**
 * Lettura di workout_doc.steps di Intervals.icu: passi con durata/distanza, obiettivo di potenza,
 * passo o FC (valore singolo o rampa start-end), ripetute annidate (reps + steps).
 */
object Struttura {
  private const val INTENSITA_ATTIVA = 70.0 // passi senza obiettivo (palestra): altezza di riferimento
  private const val INTENSITA_RECUPERO = 40.0

  private fun obiettivo(p: JsonObject): Triple<Double, Double, String>? {
    for (chiave in listOf("power", "pace", "hr")) {
      val t = p.get(chiave)?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
      val unita = PyJson.str(t.get("units")) ?: ""
      val v = PyJson.num(t.get("value"))?.v
      val da = PyJson.num(t.get("start"))?.v ?: v
      val a = PyJson.num(t.get("end"))?.v ?: v
      if (da != null && a != null) return Triple(da, a, unita)
    }
    return null
  }

  private fun recupero(p: JsonObject) = PyJson.str(p.get("intensity")) == "rest"

  private fun aZone(unita: String) = unita.endsWith("_zone") // hr_zone, power_zone, pace_zone

  /** Zona -> altezza indicativa nel grafico (% del riferimento), solo per disegnare. */
  private fun zonaInPct(z: Double) = 50.0 + 10.0 * z

  /** Segmenti per il grafico, con le ripetute gia' espanse. */
  fun segmenti(passi: JsonArray?): List<Segmento> {
    val out = ArrayList<Segmento>()
    fun visita(arr: JsonArray) {
      for (el in arr) {
        if (!el.isJsonObject) continue
        val p = el.asJsonObject
        val sotto = p.get("steps")?.takeIf { it.isJsonArray }?.asJsonArray
        if (sotto != null) {
          val reps = PyJson.num(p.get("reps"))?.v?.roundToInt() ?: 1
          repeat(reps.coerceIn(1, 50)) { visita(sotto) }
          continue
        }
        val durata = PyJson.num(p.get("duration"))?.v?.roundToInt() ?: continue
        if (durata <= 0) continue
        val ob = obiettivo(p)
        val rec = recupero(p)
        val base = if (rec) INTENSITA_RECUPERO else INTENSITA_ATTIVA
        val (da, a) =
            when {
              ob == null -> base to base
              aZone(ob.third) -> zonaInPct(ob.first) to zonaInPct(ob.second)
              else -> ob.first to ob.second
            }
        out.add(Segmento(durata, da, a, rec))
      }
    }
    passi?.let { visita(it) }
    return out
  }

  private fun unita(u: String) =
      when (u) {
        "%ftp" -> "FTP"
        "%pace" -> "passo"
        "%lthr" -> "FC soglia"
        "%hr" -> "FC max"
        else -> u.removePrefix("%")
      }

  private fun numero(v: Double) = if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else "%.1f".format(v)

  private fun riga(p: JsonObject): String {
    val parti = ArrayList<String>()
    when {
      p.get("warmup")?.let { PyJson.str(it) } == "true" -> parti.add("Riscaldamento")
      p.get("cooldown")?.let { PyJson.str(it) } == "true" -> parti.add("Defaticamento")
    }
    val distanza = PyJson.num(p.get("distance"))?.v?.takeIf { it > 0 }
    val durata = PyJson.num(p.get("duration"))?.v?.roundToInt()
    val aGiro = PyJson.str(p.get("until_lap_press")) == "true"
    when {
      distanza != null -> parti.add("${distanza.roundToInt()} m")
      durata != null && !aGiro -> parti.add(durataPasso(durata))
    }
    obiettivo(p)?.let { (da, a, u) ->
      val rampa = PyJson.str(p.get("ramp")) == "true"
      val testo =
          if (aZone(u)) {
            if (da == a) "Z${numero(da)}" else "Z${numero(da)}–Z${numero(a)}"
          } else {
            (if (da == a) "${numero(da)}%" else "${numero(da)}–${numero(a)}%") + " ${unita(u)}"
          }
      parti.add((if (rampa) "rampa " else "") + testo)
    }
    val testo = PyJson.str(p.get("text"))?.removePrefix("Press lap ")?.trim()
    when {
      !testo.isNullOrEmpty() && testo != "Rest" -> parti.add(testo)
      recupero(p) -> parti.add("Recupero")
    }
    return parti.joinToString(" · ").ifEmpty { "Passo" }
  }

  private fun durataPasso(s: Int): String {
    val m = s / 60
    val r = s % 60
    return when {
      m == 0 -> "$r\""
      r == 0 -> "$m'"
      else -> "$m'$r\""
    }
  }

  /** Righe leggibili: "Riscaldamento · 10' · rampa 60–75% FTP", poi "2x" con i passi rientrati. */
  fun righe(passi: JsonArray?): List<RigaPasso> {
    val out = ArrayList<RigaPasso>()
    for (el in passi ?: return out) {
      if (!el.isJsonObject) continue
      val p = el.asJsonObject
      val sotto = p.get("steps")?.takeIf { it.isJsonArray }?.asJsonArray
      if (sotto != null) {
        val reps = PyJson.num(p.get("reps"))?.v?.roundToInt() ?: 1
        out.add(RigaPasso(0, "${reps}x"))
        for (s in sotto) if (s.isJsonObject) out.add(RigaPasso(1, riga(s.asJsonObject)))
      } else {
        out.add(RigaPasso(0, riga(p)))
      }
    }
    return out
  }
}
