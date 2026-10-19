package com.wboelens.polarrecorder.biosleep.cervello

import java.time.LocalDate

/**
 * Ciclo mestruale (facoltativo, cervello 2026.10.19-precisione3): i 7 giorni prima di ogni
 * mestruazione (fase luteale tardiva) escono dalla baseline con il tag interno "luteale".
 * Copia di efficienza.giorni_luteali del cervello: app e cervello escludono le stesse notti.
 */
object Ciclo {
  const val TAG = "luteale"
  const val LUTEALE_GIORNI = 7L
  const val CICLO_DEFAULT = 28L

  /** Date dei 7 giorni prima di ogni inizio (il prossimo stimato dalla durata media dei cicli). */
  fun giorniLuteali(inizi: Collection<String>, oggi: LocalDate): Set<String> {
    val d = inizi.mapNotNull { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }.sorted()
    if (d.isEmpty()) return emptySet()
    val durate = d.zipWithNext { a, b -> b.toEpochDay() - a.toEpochDay() }.filter { it in 21..40 }
    // Python round(): pari al mezzo (Math.rint)
    val ciclo = if (durate.isNotEmpty()) Math.rint(durate.sum().toDouble() / durate.size).toLong() else CICLO_DEFAULT
    val prossimi = d.drop(1) + d.last().plusDays(ciclo)
    val limite = oggi.plusDays(7).toString()
    return prossimi.flatMap { p -> (1..LUTEALE_GIORNI).map { p.minusDays(it).toString() } }.filter { it <= limite }.toSet()
  }

  /** Tag di giorno + "luteale" nei giorni luteali tra da e a (stessa logica del cervello). */
  fun conLuteali(tag: Map<String, Set<String>>, inizi: Collection<String>, da: LocalDate, a: LocalDate): Map<String, Set<String>> {
    if (inizi.isEmpty()) return tag
    val out = tag.toMutableMap()
    for (g in giorniLuteali(inizi, a)) if (g >= da.toString() && g <= a.toString()) out[g] = out[g].orEmpty() + TAG
    return out
  }

  /** configJson profilo.ciclo: solo gli inizi degli ultimi 6 mesi. */
  fun recenti(inizi: Collection<String>, oggi: LocalDate = LocalDate.now()): List<String> {
    val dal = oggi.minusMonths(6).toString()
    return inizi.filter { it >= dal && it <= oggi.toString() }.sorted()
  }
}
