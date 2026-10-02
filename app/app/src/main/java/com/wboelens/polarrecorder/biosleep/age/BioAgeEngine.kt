package com.wboelens.polarrecorder.biosleep.age

import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** Sesso biologico: i valori di riferimento del VO2max sono diversi. */
enum class Sex { MALE, FEMALE }

/** Dati di ingresso, gia' raccolti da database BioSleep e Intervals.icu. */
data class BioAgeInput(
    val chronologicalAge: Double,
    val sex: Sex,
    /** Minuti di sonno delle notti valide (affidabilita' >= 80%) degli ultimi 28 giorni. */
    val sleepMinutes: List<Double>,
    /** Centro del sonno di ogni notte valida, in minuti dopo mezzogiorno (es. 03:00 = 900). */
    val sleepMidpoints: List<Double>,
    /** VO2max piu' recente (ml/kg/min) degli ultimi 60 giorni, null se assente. */
    val vo2max: Double?,
    /**
     * Minuti di attivita' per ognuna delle ultime 4 settimane, in "minuti moderati equivalenti"
     * (zone 1-2 x1, zone 3+ x2, come nelle linee guida). null se Intervals.icu non risponde.
     */
    val weeklyActivityMinutes: List<Double>?,
    /** Da dove viene il VO2max e quanto e' incerto (frazione: 0,05 = +-5%). */
    val vo2Source: String = "Intervals.icu",
    val vo2RelSd: Double = 0.05,
)

/** Quanto ogni componente sposta l'eta', in anni (negativo = piu' giovane). */
data class AgeComponent(
    val key: String,
    val label: String,
    val years: Double,
    val detail: String,
)

data class BioAgeResult(
    val age: Double,
    val low: Double, // intervallo all'80%
    val high: Double,
    val chronologicalAge: Double,
    val components: List<AgeComponent>,
    val missing: List<String>,
)

sealed interface BioAgeOutcome {
    data class Ready(val result: BioAgeResult) : BioAgeOutcome

    /** Pochi dati: nessuna eta', ma un'anteprima delle componenti gia' calcolabili. */
    data class Calibrating(
        val validNights: Int,
        val requiredNights: Int,
        val missing: List<String>,
        val preview: List<AgeComponent>,
    ) : BioAgeOutcome
}

/**
 * Eta' BioSleep: eta' anagrafica + gli "anni di rischio" di ogni componente.
 *
 * Metodo: ogni studio fornisce un rapporto di rischio (HR) di mortalita' per tutte le cause.
 * Il rischio di mortalita' degli adulti raddoppia circa ogni 8 anni (legge di Gompertz), quindi
 * un HR equivale a ln(HR) / (ln 2 / 8) anni. Esempio: HR 0,87 -> -1,6 anni.
 * Il riferimento di ogni componente e' una persona tipica della tua eta' e sesso.
 *
 * Fonti (verificate):
 *  - VO2max: Kodama et al., JAMA 2009 - HR 0,87 (IC 0,84-0,90) per ogni MET (3,5 ml/kg/min) in piu'.
 *    Valori tipici per eta'/sesso: tabelle Cooper Institute usate da Garmin (media 40-60 percentile).
 *  - Attivita': Arem et al., JAMA Intern Med 2015 - rispetto a nessuna attivita': <1x linee guida
 *    0,80; 1-2x 0,69; 2-3x 0,63; 3-5x 0,61 (plateau); >=10x 0,69 (nessun danno).
 *  - Durata del sonno: Cappuccio et al., Sleep 2010 - breve 1,12; lungo 1,30 (sonno riferito).
 *  - Regolarita' del sonno: Windred et al., Sleep 2024 - quintile meno regolare con rischio piu'
 *    alto del 20-48%. Qui approssimata con la variabilita' dell'orario centrale del sonno.
 *
 * NON incluse nel numero: FC a riposo e HRV. Gli studi le misurano da svegli, in condizioni
 * diverse dalla notte: i valori notturni della fascia non sono confrontabili con quei riferimenti.
 */
object BioAgeEngine {
    const val REQUIRED_NIGHTS = 14
    private const val NO_VO2 =
        "VO2max (assente su Intervals.icu e non stimabile: servono almeno 3 corse in piano o " +
            "uscite in bici con potenza, di 20+ minuti, negli ultimi 90 giorni)"
    private const val DOUBLING_YEARS = 8.0 // Gompertz: il rischio raddoppia ogni ~8 anni (7-9)
    private const val DOUBLING_SD = 0.75
    private const val VO2_REFERENCE_SD = 3.0 // le tabelle di riferimento differiscono tra fonti
    private const val DRAWS = 400
    private const val ML_PER_MET = 3.5

    // Cooper Institute (tabelle Garmin): media tra 40 e 60 percentile per fascia d'eta'
    private val VO2_AGES = doubleArrayOf(25.0, 35.0, 45.0, 55.0, 65.0, 75.0)
    private val VO2_MALE = doubleArrayOf(43.55, 42.25, 40.45, 37.4, 33.9, 30.85)
    private val VO2_FEMALE = doubleArrayOf(37.8, 36.1, 34.65, 31.55, 28.75, 27.0)

    // Arem 2015: (multipli del minimo raccomandato, HR) - punti centrali delle categorie
    private val ACT_X = doubleArrayOf(0.0, 0.5, 1.5, 2.5, 4.0, 10.0)
    private val ACT_HR = doubleArrayOf(1.0, 0.80, 0.69, 0.63, 0.61, 0.69)
    private const val ACT_REFERENCE_MULTIPLE = 1.5 // persona che rispetta le linee guida
    private const val GUIDELINE_MINUTES = 150.0

    fun compute(input: BioAgeInput, seed: Int = 7): BioAgeOutcome {
        val missing = mutableListOf<String>()
        val validNights = input.sleepMinutes.size
        if (input.vo2max == null) missing += NO_VO2
        if (input.weeklyActivityMinutes == null) missing += "Allenamento (Intervals.icu non configurato o non raggiungibile)"
        val activityWeight = if (input.vo2max != null) 0.5 else 1.0 // fitness e attivita' si sovrappongono
        if (validNights < REQUIRED_NIGHTS) {
            // Anteprima: componenti gia' calcolabili; il sonno solo da 3 notti in su
            val preview = components(input, activityWeight, null, includeSleep = validNights >= 3)
            return BioAgeOutcome.Calibrating(validNights, REQUIRED_NIGHTS, missing, preview)
        }

        val rnd = Random(seed)

        // Valori centrali
        val central = components(input, activityWeight, null)
        // Intervallo: si ripete il calcolo variando dati (ricampionamento) e coefficienti (IC)
        val draws = DoubleArray(DRAWS) { components(input, activityWeight, rnd).sumOf { it.years } }
        draws.sort()
        val total = central.sumOf { it.years }
        val age = input.chronologicalAge + total
        return BioAgeOutcome.Ready(
            BioAgeResult(
                age = age,
                low = input.chronologicalAge + draws[(DRAWS * 0.10).toInt()],
                high = input.chronologicalAge + draws[(DRAWS * 0.90).toInt()],
                chronologicalAge = input.chronologicalAge,
                components = central,
                missing = missing,
            ))
    }

    /** rnd = null: valori centrali; altrimenti un'estrazione casuale per l'intervallo. */
    private fun components(
        input: BioAgeInput,
        activityWeight: Double,
        rnd: Random?,
        includeSleep: Boolean = true,
    ): List<AgeComponent> {
        val out = mutableListOf<AgeComponent>()
        val doubling = DOUBLING_YEARS + if (rnd == null) 0.0 else DOUBLING_SD * gauss(rnd)
        val yearsPerLnHr = doubling / ln(2.0)

        input.vo2max?.let { v0 ->
            val v = if (rnd == null) v0 else v0 * (1 + input.vo2RelSd * gauss(rnd))
            val ref0 = interp(input.chronologicalAge, VO2_AGES, if (input.sex == Sex.MALE) VO2_MALE else VO2_FEMALE)
            val ref = ref0 + if (rnd == null) 0.0 else VO2_REFERENCE_SD * gauss(rnd)
            val lnHrPerMet = ln(0.87) + if (rnd == null) 0.0 else 0.0176 * gauss(rnd)
            // Saturazione morbida: oltre ~6 anni la relazione lineare degli studi non e' dimostrata
            val years = soft(lnHrPerMet * (v - ref) / ML_PER_MET * yearsPerLnHr, 6.0)
            out += AgeComponent(
                "vo2", "Fitness (VO2max)", years,
                "VO2max ${fmt1(v0)} (${input.vo2Source}) contro ${fmt1(ref0)} tipico per eta' e sesso")
        }

        input.weeklyActivityMinutes?.let { weeks0 ->
            val weeks = if (rnd == null || weeks0.isEmpty()) weeks0 else List(weeks0.size) { weeks0[rnd.nextInt(weeks0.size)] }
            val perWeek = if (weeks.isEmpty()) 0.0 else weeks.average()
            val multiple = perWeek / GUIDELINE_MINUTES
            val hr = interp(multiple, ACT_X, ACT_HR)
            val ref = interp(ACT_REFERENCE_MULTIPLE, ACT_X, ACT_HR)
            val years = soft(activityWeight * ln(hr / ref) * yearsPerLnHr, 3.0)
            out += AgeComponent(
                "activity", "Allenamento", years,
                "${perWeek.toInt()} min/settimana equivalenti (${fmt1(multiple)}x il minimo raccomandato)")
        }

        if (!includeSleep) return out
        val sleep = resample(input.sleepMinutes, rnd)
        val medianSleep = median(sleep)
        val hrDur = sleepDurationHr(medianSleep / 60.0)
        out += AgeComponent(
            "sleep_duration", "Durata del sonno", soft(ln(hrDur) * yearsPerLnHr, 3.0),
            "mediana ${fmt1(medianSleep / 60.0)} h di sonno")

        val mids = resample(input.sleepMidpoints, rnd)
        val sd = stdDev(mids)
        val hrReg = sleepRegularityHr(sd)
        out += AgeComponent(
            "sleep_regularity", "Regolarita' del sonno", soft(ln(hrReg) * yearsPerLnHr, 3.0),
            "orario del sonno variabile di +-${sd.toInt()} minuti")
        return out
    }

    /** Cappuccio 2010: 7-8,5 h riferimento; <=6 h -> 1,12; >=9,5 h -> 1,30 (tra i due: lineare). */
    fun sleepDurationHr(hours: Double): Double = when {
        hours <= 6.0 -> 1.12
        hours < 7.0 -> 1.12 - 0.12 * (hours - 6.0)
        hours <= 8.5 -> 1.0
        hours < 9.5 -> 1.0 + 0.30 * (hours - 8.5)
        else -> 1.30
    }

    /** Windred 2024 (approssimato): +-20 min -> 0,90; +-45 min -> 1,0; >= +-90 min -> 1,30. */
    fun sleepRegularityHr(sdMinutes: Double): Double = when {
        sdMinutes <= 20 -> 0.90
        sdMinutes < 45 -> 0.90 + 0.10 * (sdMinutes - 20) / 25
        sdMinutes < 90 -> 1.0 + 0.30 * (sdMinutes - 45) / 45
        else -> 1.30
    }

    // --- utilita' ---------------------------------------------------------------------------
    /** Saturazione morbida verso +-limit: lineare vicino a zero, mai oltre il limite. */
    private fun soft(x: Double, limit: Double) = limit * kotlin.math.tanh(x / limit)

    private fun interp(x: Double, xs: DoubleArray, ys: DoubleArray): Double {
        if (x <= xs.first()) return ys.first()
        if (x >= xs.last()) return ys.last()
        val i = xs.indexOfFirst { it > x }
        val f = (x - xs[i - 1]) / (xs[i] - xs[i - 1])
        return ys[i - 1] + f * (ys[i] - ys[i - 1])
    }

    private fun resample(v: List<Double>, rnd: Random?): List<Double> =
        if (rnd == null || v.isEmpty()) v else List(v.size) { v[rnd.nextInt(v.size)] }

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    private fun stdDev(v: List<Double>): Double {
        if (v.size < 2) return 0.0
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
    }

    private fun gauss(rnd: Random): Double {
        // Box-Muller
        val u1 = rnd.nextDouble().coerceAtLeast(1e-12)
        val u2 = rnd.nextDouble()
        return sqrt(-2 * ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }

    private fun fmt1(x: Double) = String.format(java.util.Locale.ITALY, "%.1f", x)

}
