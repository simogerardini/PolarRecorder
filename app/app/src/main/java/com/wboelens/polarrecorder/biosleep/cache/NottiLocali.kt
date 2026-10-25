package com.wboelens.polarrecorder.biosleep.cache

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wboelens.polarrecorder.biosleep.NightSummary
import com.wboelens.polarrecorder.biosleep.SleepDb
import com.wboelens.polarrecorder.biosleep.intervals.CampiWellness
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsClient
import com.wboelens.polarrecorder.biosleep.readiness.CampiStorici
import java.time.LocalDate

/**
 * Le notti registrate sul telefono come righe wellness, con lo stesso costruttore dell'invio a
 * Intervals.icu (IntervalsClient.buildWellness): stessi campi, stessi arrotondamenti. Servono alla
 * banda e alle schermate quando la notte non e' (ancora) su Intervals.icu: modalita' solo
 * biometria, account non collegato, invio automatico spento, telefono offline.
 */
object NottiLocali {
  /**
   * Righe wellness delle notti con risveglio in [da, a], una per giorno: vince la notte della
   * sorgente scelta (fascia/Garmin), poi la piu' recente.
   * Parte 11 (privacy, Parte 4): [conGarmin] = false per tutto cio' che esce dal telefono
   * (NoctaliX Web, card condivisibili): le notti Garmin restano solo sul telefono.
   */
  fun righe(context: Context, da: LocalDate, a: LocalDate, conGarmin: Boolean = true): List<JsonObject> =
      try {
        val preferita = com.wboelens.polarrecorder.biosleep.garmin.GarminScelta.sorgente(context)
        SleepDb.get(context).listNights()
            .filter { conGarmin || it.summary.sorgente != NightSummary.SORGENTE_GARMIN }
            .mapNotNull { n ->
              val giorno = IntervalsClient.morningDate(n.summary)
              if (giorno < da.toString() || giorno > a.toString()) return@mapNotNull null
              val o = JsonParser.parseString(IntervalsClient.buildWellness(n.summary, n.stages).toString()).asJsonObject
              o.addProperty("id", giorno)
              ((if (n.summary.sorgente == preferita) 1L else 0L) to n.summary.endMs) to o
            }
            .sortedWith(compareBy({ it.first.first }, { it.first.second }))
            .associateBy { it.second.get("id").asString }
            .values
            .map { it.second }
      } catch (e: Exception) {
        emptyList()
      }

  /**
   * Unisce la cache di Intervals.icu e le notti locali. Il giorno che su Intervals.icu ha gia' la
   * notte (rMSSD) resta quello del server, identico a cio' che legge il coach; un giorno senza notte
   * prende i campi Noctalix locali, conservando gli altri campi del server (CTL, ATL...).
   */
  fun unisci(server: List<JsonObject>, locali: List<JsonObject>): List<JsonObject> {
    val perGiorno = LinkedHashMap<String, JsonObject>()
    for (o in server) perGiorno[(o.get("id")?.asString ?: continue).take(10)] = o
    for (l in locali) {
      val g = l.get("id")?.asString ?: continue
      val s = perGiorno[g]
      when {
        s == null -> perGiorno[g] = l
        CampiStorici.leggi(s, CampiWellness.F_RMSSD) != null -> Unit
        else ->
            perGiorno[g] =
                s.deepCopy().apply { for (k in CampiWellness.TUTTI) l.get(k)?.let { add(k, it) } }
      }
    }
    return perGiorno.entries.sortedBy { it.key }.map { it.value }
  }

  /**
   * Parte 11: le notti del telefono (fascia e Garmin, ultimi 365 giorni) per il cervello, che le
   * preferisce a quelle lette da Intervals.icu (config "notti_locali", contratto con la Parte 2).
   */
  fun scriviPerCervello(context: Context, file: java.io.File, oggi: LocalDate = LocalDate.now()): String {
    val arr = com.google.gson.JsonArray()
    righe(context, oggi.minusDays(365), oggi).forEach { arr.add(it) }
    file.writeText(arr.toString())
    return file.absolutePath
  }
}
