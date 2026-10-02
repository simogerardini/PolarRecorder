package com.wboelens.polarrecorder.biosleep.readiness

import java.time.LocalDate

/** Una riga wellness di Intervals.icu ridotta ai campi della forma. */
data class RigaForma(
    val id: String?,
    val date: String?,
    val ctl: Double?,
    val atl: Double?,
    val rampRate: Double?,
) {
  /** x.get("id") or x.get("date") or "" */
  val giorno: String get() = id?.takeIf { it.isNotEmpty() } ?: date?.takeIf { it.isNotEmpty() } ?: ""
}

/** Carico di un'attivita' svolta. load = null se il carico non e' un numero (o manca). */
data class CaricoAttivita(val startDateLocal: String?, val load: Double?)

data class StatoForma(
    val ctl: Double,
    val atl: Double,
    val tsb: Double,
    val formPct: Double,
    val zona: String, // Transizione | Fresco | Grigia | Ottimale | Alto rischio
    val colore: String, // giallo | blu | grigio | verde | rosso
    val giorniInZona: Int,
    val storicoGg: Int,
    val rischioRelativo: Boolean,
)

/**
 * Copia di wellness_reale e stato_forma (intervals_coach.py): CTL/ATL ricalcolati sulle sole
 * sedute ESEGUITE (le righe di Intervals.icu contengono anche il carico pianificato) e zona
 * letta sul TSB assoluto. Verificata da BaselineParityTest sui "casi_forma".
 */
object FormaCalc {
  // math.exp(-1/7) e math.exp(-1/42) di Python, scritti come letterali: la funzione exp della
  // JVM puo' differire dall'ultima cifra binaria, i letterali no.
  const val EWMA_ATL = 0.8668778997501816
  const val EWMA_CTL = 0.9764716866522433
  private const val CARICO_TOLL = 5.0
  const val RISCHIO_RELATIVO_PCT = -30.0
  /** Finestra del coach: get_wellness(14) e get_activities(14). */
  const val FINESTRA_GG = 14L

  private val BANDE = listOf(
      Triple(20.0, "Transizione", "giallo"), Triple(5.0, "Fresco", "blu"),
      Triple(-10.0, "Grigia", "grigio"), Triple(-30.0, "Ottimale", "verde"))

  /** _zona_da_form_pct (applicata al TSB assoluto, come fa il coach). */
  fun zona(tsb: Double): Pair<String, String> {
    for ((soglia, nome, colore) in BANDE) if (tsb > soglia) return nome to colore
    return "Alto rischio" to "rosso"
  }

  private val DATA_ESATTA = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""")

  /** datetime.strptime(s, "%Y-%m-%d") senza troncare: altri caratteri = errore. */
  private fun parseEsatta(s: String): LocalDate? =
      if (DATA_ESATTA.matchEntire(s) == null) null else Py.parseDate(s)

  fun wellnessReale(wellness: List<RigaForma>, attivita: List<CaricoAttivita>): List<RigaForma> {
    val righe = wellness.filter { it.ctl != null && it.atl != null }.sortedBy { it.giorno }
    if (righe.size < 2 || attivita.isEmpty()) return wellness
    val carichi = HashMap<String, Double>()
    for (a in attivita) {
      val giorno = (a.startDateLocal ?: "").take(10)
      if (giorno.isEmpty()) continue
      val carico = a.load ?: return wellness // carico ignoto: non attribuibile al pianificato
      carichi[giorno] = (carichi[giorno] ?: 0.0) + carico
    }
    var sporco: Int? = null
    var incoerente: Int? = null
    for (i in 1 until righe.size) {
      val contabilizzato = (righe[i].atl!! - righe[i - 1].atl!! * EWMA_ATL) / (1 - EWMA_ATL)
      val eseguito = carichi[righe[i].giorno] ?: 0.0
      if (eseguito > contabilizzato + CARICO_TOLL) {
        incoerente = i
        continue
      }
      if (sporco == null && contabilizzato - eseguito > CARICO_TOLL) sporco = i
    }
    if (sporco == null) return wellness
    if (incoerente != null && incoerente >= sporco - 1) return wellness
    var ctl = righe[sporco - 1].ctl!!
    var atl = righe[sporco - 1].atl!!
    val corretti = LinkedHashMap<String, Pair<Double, Double>>()
    for (r in righe.subList(sporco, righe.size)) {
      val carico = carichi[r.giorno] ?: 0.0
      ctl = ctl * EWMA_CTL + carico * (1 - EWMA_CTL)
      atl = atl * EWMA_ATL + carico * (1 - EWMA_ATL)
      corretti[r.giorno] = ctl to atl
    }
    // rampRate = CTL del giorno - CTL di 7 giorni prima, rifatto sui CTL corretti
    val ctlPerGiorno = HashMap<String, Double>()
    righe.forEach { ctlPerGiorno[it.giorno] = it.ctl!! }
    corretti.forEach { (g, v) -> ctlPerGiorno[g] = v.first }
    val rampe = HashMap<String, Double>()
    for (g in corretti.keys) {
      val meno7 = parseEsatta(g)?.minusDays(7)?.toString() ?: continue
      val c7 = ctlPerGiorno[meno7] ?: continue
      rampe[g] = ctlPerGiorno[g]!! - c7
    }
    return wellness.map { x ->
      val v = corretti[x.giorno]
      if (v == null) x else x.copy(ctl = v.first, atl = v.second, rampRate = rampe[x.giorno] ?: x.rampRate)
    }
  }

  fun statoForma(wellness: List<RigaForma>): StatoForma? {
    val w = wellness.filter { it.ctl != null && it.ctl != 0.0 }.sortedBy { it.giorno }
    if (w.isEmpty()) return null
    fun zonaRiga(x: RigaForma): Triple<Double, Double, Pair<String, String>>? {
      val ctl = x.ctl ?: 0.0
      val atl = x.atl ?: 0.0
      if (ctl == 0.0) return null
      val tsb = ctl - atl
      return Triple(Py.round(tsb, 1), Py.round(tsb / ctl * 100, 1), zona(tsb))
    }
    val (tsb, pct, zc) = zonaRiga(w.last()) ?: return null
    var gg = 1
    for (x in w.dropLast(1).asReversed()) {
      val z = zonaRiga(x)?.third
      if (z == null || z.first != zc.first) break
      gg++
    }
    return StatoForma(
        ctl = Py.round(w.last().ctl!!, 1), atl = Py.round(w.last().atl ?: 0.0, 1), tsb = tsb, formPct = pct,
        zona = zc.first, colore = zc.second, giorniInZona = gg, storicoGg = w.size,
        rischioRelativo = pct < RISCHIO_RELATIVO_PCT)
  }

  /**
   * Serie per l'app (opzione b): righe fino a oggi; le ultime FINESTRA_GG ricalcolate sulle
   * sole sedute eseguite con la stessa finestra del coach, le precedenti come da Intervals.icu
   * (il ricalcolo parte comunque da una riga reale). Oggi = forma_oggi del coach.
   * Nota: giorniInZona qui puo' superare 14 (il coach vede solo 15 righe).
   */
  fun serieApp(wellness: List<RigaForma>, attivita: List<CaricoAttivita>, oggi: LocalDate): List<RigaForma> {
    val inizio = oggi.minusDays(FINESTRA_GG).toString()
    val fine = oggi.toString()
    val prima = wellness.filter { it.giorno < inizio }
    val finestra = wellness.filter { it.giorno in inizio..fine }
    val att = attivita.filter { (it.startDateLocal ?: "").take(10) in inizio..fine }
    return (prima + wellnessReale(finestra, att)).sortedBy { it.giorno }
  }
}
