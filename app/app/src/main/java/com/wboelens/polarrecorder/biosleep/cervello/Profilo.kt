package com.wboelens.polarrecorder.biosleep.cervello

import com.wboelens.polarrecorder.biosleep.demo.DemoParte3
import android.content.Context
import com.google.gson.JsonNull
import com.google.gson.JsonObject

/** Giorni come li vuole il cervello. */
val GIORNI = listOf("lun", "mar", "mer", "gio", "ven", "sab", "dom")

/**
 * Settimana tipo (cervello: coach_settimanale.settimana_tipo_valida): giorni dei due lunghi, giorno
 * di riposo facoltativo, sedute per disciplina. I valori predefiniti sono quelli del cervello.
 */
data class SettimanaTipo(
    val lungoBici: String = "sab",
    val lungoCorsa: String = "dom",
    val riposo: String? = null,
    val sedute: Map<String, Int> = PREDEFINITE,
) {
  /** Errori come li conta il cervello; vuoto = valida. */
  fun errori(): List<String> = buildList {
    if (lungoBici !in GIORNI) add("lungo bici: giorno non valido")
    if (lungoCorsa !in GIORNI) add("lungo corsa: giorno non valido")
    if (riposo != null && riposo !in GIORNI) add("riposo: giorno non valido")
    for ((f, n) in sedute) {
      val l = LIMITI[f]
      if (l == null || n !in l) add("sedute $f fuori dai limiti")
    }
    if (lungoBici == lungoCorsa) add("lungo bici e lungo corsa nello stesso giorno")
    if (riposo != null && (riposo == lungoBici || riposo == lungoCorsa)) add("il riposo coincide con un lungo")
  }

  fun json(): JsonObject =
      JsonObject().apply {
        addProperty("lungo_bici", lungoBici)
        addProperty("lungo_corsa", lungoCorsa)
        if (riposo == null) add("riposo", JsonNull.INSTANCE) else addProperty("riposo", riposo)
        add("sedute", JsonObject().apply { for (f in DISCIPLINE) addProperty(f, sedute[f] ?: PREDEFINITE.getValue(f)) })
      }

  companion object {
    val DISCIPLINE = listOf("nuoto", "bici", "corsa", "forza")
    val PREDEFINITE = mapOf("nuoto" to 2, "bici" to 2, "corsa" to 3, "forza" to 2)
    val LIMITI = mapOf("nuoto" to 1..4, "bici" to 1..4, "corsa" to 1..4, "forza" to 0..2)
  }
}

/**
 * Palestra (cervello: palestra.scegli_scheda): attrezzatura disponibile e livello. Corpo libero e
 * trasferta/hotel sono sempre inclusi e si mandano sempre: una lista vuota il cervello la
 * leggerebbe come "predefinita" (bilanciere, kettlebell, elastici).
 */
data class Palestra(
    val attrezzatura: Set<String> = PREDEFINITA,
    val livello: String = "intermediate",
) {
  fun json(): JsonObject =
      JsonObject().apply {
        add("attrezzatura", com.google.gson.JsonArray().apply { ATTREZZI.map { it.first }.filter { it in tutta }.forEach { add(it) } })
        addProperty("livello", livello)
      }

  /** Quella mandata al cervello: le scelte piu' quelle sempre incluse. */
  val tutta: Set<String> get() = attrezzatura + SEMPRE

  companion object {
    val ATTREZZI =
        listOf(
            "barbell_gym" to "Palestra con bilanciere",
            "kettlebell" to "Kettlebell",
            "trx_suspension" to "TRX",
            "resistance_bands" to "Elastici",
            "bodyweight_only" to "Corpo libero",
            "hotel_minimal" to "Trasferta / hotel")
    val SEMPRE = setOf("bodyweight_only", "hotel_minimal")
    val PREDEFINITA = setOf("barbell_gym", "kettlebell", "resistance_bands")
    val LIVELLI = listOf("beginner" to "Principiante", "intermediate" to "Intermedio", "advanced" to "Avanzato")
  }
}

/**
 * Giorni caldi (cervello: caldo.py): se trasformare la corsa facile in bici indoor e a che ora
 * ci si allena di solito, per leggere la previsione all'ora giusta.
 */
data class Caldo(val convertiCorsa: Boolean = true, val oraFeriale: Int = 18, val oraWeekend: Int = 10) {
  fun json(): JsonObject =
      JsonObject().apply {
        addProperty("converti_corsa", convertiCorsa)
        addProperty("ora_feriale", oraFeriale)
        addProperty("ora_weekend", oraWeekend)
      }

  companion object {
    val ORE = 5..21
  }
}

/**
 * Sensore CORE 2 e protocollo DETP (cervello: detp.py). Il DETP richiede il CORE 2: senza
 * sensore resta spento anche se salvato acceso.
 */
data class Detp(val core2: Boolean = false, val detp: Boolean = false) {
  val attivo: Boolean get() = core2 && detp

  fun json(): JsonObject =
      JsonObject().apply {
        addProperty("core2", core2)
        addProperty("detp", attivo)
      }
}

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
    /** Sempre mandata: senza modifiche coincide con quella predefinita del cervello. */
    val settimana: SettimanaTipo = SettimanaTipo(),
    /** Sempre mandata: senza modifiche coincide con quella predefinita del cervello. */
    val palestra: Palestra = Palestra(),
    /** Sempre mandata: senza modifiche coincide con quella predefinita del cervello. */
    val caldo: Caldo = Caldo(),
    /** Sempre mandato: spento finche' l'utente non dichiara il CORE 2. */
    val detp: Detp = Detp(),
    /** Stryd: qualita' di corsa in potenza (serve la CP su Intervals.icu). Sempre mandato. */
    val stryd: Boolean = false,
    /**
     * Livello dell'atleta per le sedute avanzate (principiante, intermedio, avanzato).
     * null = non scelto: non si manda e il cervello usa quello della palestra.
     */
    val livello: String? = null,
    /** Ciclo mestruale (facoltativo): conta solo se attivo; date di inizio "YYYY-MM-DD". */
    val cicloAttivo: Boolean = false,
    val cicloInizi: List<String> = emptyList(),
    /** Tymewear (facoltativo): VT1/VT2 di bici e corsa; nel configJson solo se attivo. */
    val tymewear: Tymewear = Tymewear(),
) {
  /** Il blocco "profilo" del configJson (contiene sempre almeno la settimana tipo). */
  fun json(): JsonObject {
    return JsonObject().apply {
      fcMax?.let { addProperty("fc_max", it) }
      fcRiposo?.let { addProperty("fc_riposo", it) }
      tettoOre?.let { addProperty("tetto_ore", it) }
      if (disponibilita.isNotEmpty()) {
        add("disponibilita", JsonObject().apply { for (g in GIORNI) disponibilita[g]?.let { addProperty(g, it) } })
      }
      add("settimana", settimana.json())
      add("palestra", palestra.json())
      add("caldo", caldo.json())
      add("detp", detp.json())
      addProperty("stryd", stryd)
      livello?.let { addProperty("livello", it) }
      // dato sanitario locale: solo al cervello sul telefono, mai su Intervals.icu
      if (cicloAttivo) {
        add("ciclo", JsonObject().apply {
          addProperty("attivo", true)
          add("inizi", com.google.gson.JsonArray().apply { Ciclo.recenti(cicloInizi).forEach { add(it) } })
        })
      }
      if (tymewear.attivo) add("tymewear", tymewear.json())
    }
  }

  companion object {
    val LIMITI_FC_MAX = 120..230
    val LIMITI_FC_RIPOSO = 30..100
    val LIMITI_MINUTI = 0..600
    const val TETTO_MIN = 1.0
    const val TETTO_MAX = 30.0
    val LIVELLI = listOf("principiante" to "Principiante", "intermedio" to "Intermedio", "avanzato" to "Avanzato")

    /** Il livello della palestra (in inglese) come livello dell'atleta: quello proposto se non scelto. */
    fun daPalestra(l: String) =
        when (l) {
          "beginner" -> "principiante"
          "advanced" -> "avanzato"
          else -> "intermedio"
        }
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
  private val app = context.applicationContext
  private val prefs = context.applicationContext.getSharedPreferences("biosleep_profilo", Context.MODE_PRIVATE)

  fun leggi(): ProfiloAtleta =
      ProfiloAtleta(
          fcMax = prefs.getInt("fc_max", -1).takeIf { it > 0 },
          fcRiposo = prefs.getInt("fc_riposo", -1).takeIf { it > 0 },
          tettoOre = prefs.getString("tetto_ore", null)?.toDoubleOrNull(),
          disponibilita = GIORNI.mapNotNull { g -> prefs.getInt("disp_$g", -1).takeIf { it >= 0 }?.let { g to it } }.toMap(),
          settimana =
              SettimanaTipo(
                  lungoBici = prefs.getString("sett_lungo_bici", null) ?: "sab",
                  lungoCorsa = prefs.getString("sett_lungo_corsa", null) ?: "dom",
                  riposo = prefs.getString("sett_riposo", null),
                  sedute =
                      SettimanaTipo.DISCIPLINE.associateWith { f ->
                        prefs.getInt("sett_$f", SettimanaTipo.PREDEFINITE.getValue(f))
                      }),
          palestra =
              Palestra(
                  attrezzatura = prefs.getStringSet("palestra_attrezzi", null)?.toSet() ?: Palestra.PREDEFINITA,
                  livello = prefs.getString("palestra_livello", null) ?: "intermediate"),
          caldo =
              Caldo(
                  convertiCorsa = prefs.getBoolean("caldo_converti", true),
                  oraFeriale = prefs.getInt("caldo_ora_feriale", 18),
                  oraWeekend = prefs.getInt("caldo_ora_weekend", 10)),
          detp = Detp(core2 = prefs.getBoolean("core2", false), detp = prefs.getBoolean("detp", false)),
          stryd = prefs.getBoolean("stryd", false),
          livello = prefs.getString("livello_atleta", null),
          cicloAttivo = prefs.getBoolean("ciclo_attivo", false),
          cicloInizi = prefs.getStringSet("ciclo_inizi", emptySet()).orEmpty().sorted(),
          tymewear = Tymewear.da(prefs.getString("tymewear", null)),
      )

  fun salva(p: ProfiloAtleta) {
    if (DemoParte3.attiva(app)) return // modalita' demo: il profilo vero non si tocca
    prefs.edit().apply {
      if (p.fcMax != null) putInt("fc_max", p.fcMax) else remove("fc_max")
      if (p.fcRiposo != null) putInt("fc_riposo", p.fcRiposo) else remove("fc_riposo")
      if (p.tettoOre != null) putString("tetto_ore", p.tettoOre.toString()) else remove("tetto_ore")
      for (g in GIORNI) p.disponibilita[g]?.let { putInt("disp_$g", it) } ?: remove("disp_$g")
      putString("sett_lungo_bici", p.settimana.lungoBici)
      putString("sett_lungo_corsa", p.settimana.lungoCorsa)
      if (p.settimana.riposo != null) putString("sett_riposo", p.settimana.riposo) else remove("sett_riposo")
      for (f in SettimanaTipo.DISCIPLINE) putInt("sett_$f", p.settimana.sedute[f] ?: SettimanaTipo.PREDEFINITE.getValue(f))
      putStringSet("palestra_attrezzi", p.palestra.attrezzatura - Palestra.SEMPRE)
      putString("palestra_livello", p.palestra.livello)
      putBoolean("caldo_converti", p.caldo.convertiCorsa)
      putInt("caldo_ora_feriale", p.caldo.oraFeriale)
      putInt("caldo_ora_weekend", p.caldo.oraWeekend)
      putBoolean("core2", p.detp.core2)
      putBoolean("detp", p.detp.attivo)
      putBoolean("stryd", p.stryd)
      if (p.livello != null) putString("livello_atleta", p.livello) else remove("livello_atleta")
      putBoolean("ciclo_attivo", p.cicloAttivo)
      putStringSet("ciclo_inizi", p.cicloInizi.toSet())
      putString("tymewear", p.tymewear.json().toString())
    }.apply()
  }
}
