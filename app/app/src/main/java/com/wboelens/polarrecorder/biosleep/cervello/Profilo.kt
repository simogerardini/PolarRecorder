package com.wboelens.polarrecorder.biosleep.cervello

import android.content.Context
import com.google.gson.JsonObject

/** Giorni come li vuole il cervello. */
val GIORNI = listOf("lun", "mar", "mer", "gio", "ven", "sab", "dom")

/**
 * Profilo dell'atleta passato al cervello in "profilo". Ogni campo e' facoltativo: un campo
 * assente non si manda, e il cervello usa i valori di Intervals.icu. Disponibilita': minuti per
 * giorno, 0 = non disponibile, giorno assente = nessun limite.
 */
data class ProfiloAtleta(
    val fcMax: Int? = null,
    val fcRiposo: Int? = null,
    val tettoOre: Double? = null,
    val disponibilita: Map<String, Int> = emptyMap(),
) {
  /** Il blocco "profilo" del configJson; null se non c'e' niente da mandare. */
  fun json(): JsonObject? {
    if (fcMax == null && fcRiposo == null && tettoOre == null && disponibilita.isEmpty()) return null
    return JsonObject().apply {
      fcMax?.let { addProperty("fc_max", it) }
      fcRiposo?.let { addProperty("fc_riposo", it) }
      tettoOre?.let { addProperty("tetto_ore", it) }
      if (disponibilita.isNotEmpty()) {
        add("disponibilita", JsonObject().apply { for (g in GIORNI) disponibilita[g]?.let { addProperty(g, it) } })
      }
    }
  }

  companion object {
    val LIMITI_FC_MAX = 120..230
    val LIMITI_FC_RIPOSO = 30..100
    val LIMITI_MINUTI = 0..600
    const val TETTO_MIN = 1.0
    const val TETTO_MAX = 30.0
  }
}

/** Valori proposti dall'app: l'utente li conferma con "Usa" (un picco anomalo non entra da solo). */
data class Suggerimenti(val fcMax: Int?, val fcMaxData: String?, val fcRiposo: Int?, val nottiFcRiposo: Int)

object SuggerimentiProfilo {
  /**
   * FC massima: la piu' alta tra le sedute delle ultime settimane (dati del dispositivo), solo se
   * plausibile. FC a riposo: mediana delle FC a riposo delle ultime 7 notti BioSleep (il "minimo
   * stabile": la mediana non si fa trascinare da una notte storta), da almeno 5 notti.
   */
  fun calcola(maxSedute: List<Pair<String, Int>>, fcRiposoNotti: List<Double>): Suggerimenti {
    val max = maxSedute.filter { it.second in ProfiloAtleta.LIMITI_FC_MAX }.maxByOrNull { it.second }
    val ultime = fcRiposoNotti.takeLast(7).filter { it > 0 }
    val riposo =
        if (ultime.size >= 5) ultime.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
        else null
    return Suggerimenti(max?.second, max?.first, riposo?.let { Math.round(it).toInt() }, ultime.size)
  }
}

/** Il profilo salvato sul telefono (non e' un dato sensibile come la API key: niente cifratura). */
class ProfiloStore(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences("biosleep_profilo", Context.MODE_PRIVATE)

  fun leggi(): ProfiloAtleta =
      ProfiloAtleta(
          fcMax = prefs.getInt("fc_max", -1).takeIf { it > 0 },
          fcRiposo = prefs.getInt("fc_riposo", -1).takeIf { it > 0 },
          tettoOre = prefs.getString("tetto_ore", null)?.toDoubleOrNull(),
          disponibilita = GIORNI.mapNotNull { g -> prefs.getInt("disp_$g", -1).takeIf { it >= 0 }?.let { g to it } }.toMap(),
      )

  fun salva(p: ProfiloAtleta) {
    prefs.edit().apply {
      if (p.fcMax != null) putInt("fc_max", p.fcMax) else remove("fc_max")
      if (p.fcRiposo != null) putInt("fc_riposo", p.fcRiposo) else remove("fc_riposo")
      if (p.tettoOre != null) putString("tetto_ore", p.tettoOre.toString()) else remove("tetto_ore")
      for (g in GIORNI) p.disponibilita[g]?.let { putInt("disp_$g", it) } ?: remove("disp_$g")
    }.apply()
  }
}
