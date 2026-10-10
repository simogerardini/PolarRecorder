package com.wboelens.polarrecorder.biosleep.readiness

/**
 * Consiglio del giorno per la modalita' solo biometria (stile HRV4Training): dai soli dati della
 * notte, senza carico di allenamento. Usa la banda del coach (stesso calcolo, BioBaselineCalc):
 *  - rosso, o giallo da 3+ giorni          -> RIPOSO      (riposo o recupero attivo)
 *  - giallo, o FC a riposo in allarme      -> PIANO       (vai piano: intensita' e volume ridotti)
 *  - verde con HRV sopra la media (0 < z <= 1 SD), in non calo, senza allarmi, CV non collassato
 *    e almeno 6 h di sonno stanotte         -> SPINGI      (puoi spingere)
 *  - verde negli altri casi                 -> NORMALE     (allenati come previsto)
 * Oltre +1 SD non si spinge: puo' essere saturazione parasimpatica (nota del coach). CV collassato:
 * non aumentare il carico (Plews 2013). I [motivi] sono frasi italiane tradotte da TestiSistema.
 */
object Consiglio {
  enum class Livello { SPINGI, NORMALE, PIANO, RIPOSO }

  data class Esito(val livello: Livello, val motivi: List<String>)

  const val SONNO_MIN_SPINGERE = 6.0
  private const val GG_SOTTO_RIPOSO = 3

  fun calcola(b: BioBaseline, sonnoStanotteOre: Double?): Esito? {
    if (!b.ok || b.banda == null || b.banda == "grigio") return null
    val motivi = mutableListOf<String>()
    val fc = b.fcRiposo?.takeIf { it.allarme }
    fc?.let { f -> motivi += "FC a riposo sopra l'abituale (${f.delta?.let { conSegno(it) } ?: uno(f.rolling7)} bpm)" }
    if (b.persistenzaGgSotto >= 2) motivi += "HRV sotto il range da ${b.persistenzaGgSotto} giorni"
    val livello =
        when {
          b.banda == "rosso" -> Livello.RIPOSO
          b.banda == "giallo" && b.persistenzaGgSotto >= GG_SOTTO_RIPOSO -> Livello.RIPOSO
          b.banda == "giallo" || fc != null -> Livello.PIANO
          else -> {
            val z = b.zLn
            val ostacoli = mutableListOf<String>()
            if (b.cvCollassato) ostacoli += "Variabilità dell'HRV quasi azzerata: meglio non aumentare il carico"
            if (z != null && z > 1.0) ostacoli += "HRV molto sopra la media: possibile saturazione, meglio non spingere"
            if (b.direzione7v7 == "in calo") ostacoli += "HRV in calo negli ultimi 7 giorni"
            if (sonnoStanotteOre != null && sonnoStanotteOre < SONNO_MIN_SPINGERE)
                ostacoli += "Sonno corto stanotte (${durata(sonnoStanotteOre)})"
            motivi += ostacoli
            if (z != null && z > 0.0 && ostacoli.isEmpty()) Livello.SPINGI else Livello.NORMALE
          }
        }
    return Esito(livello, motivi)
  }

  fun titolo(l: Livello) =
      when (l) {
        Livello.SPINGI -> "Puoi spingere"
        Livello.NORMALE -> "Allenati come previsto"
        Livello.PIANO -> "Vai piano"
        Livello.RIPOSO -> "Riposo o recupero attivo"
      }

  fun spiegazione(l: Livello) =
      when (l) {
        Livello.SPINGI -> "HRV sopra la tua media: buona giornata per un allenamento intenso."
        Livello.NORMALE -> "HRV nella tua norma: allenati come avevi previsto."
        Livello.PIANO -> "Riduci intensità e volume: oggi meglio lavoro facile, in Z1-Z2."
        Livello.RIPOSO -> "Il corpo chiede recupero: riposo o attività molto leggera."
      }

  fun emoji(l: Livello) =
      when (l) {
        Livello.SPINGI -> "🔵"
        Livello.NORMALE -> "🟢"
        Livello.PIANO -> "🟠"
        Livello.RIPOSO -> "🔴"
      }

  // formati semplici e indipendenti dalla lingua (la frase poi passa da TestiSistema)
  private fun uno(x: Double) = String.format(java.util.Locale.ROOT, "%.1f", x)

  private fun conSegno(x: Double) = (if (x > 0) "+" else "") + uno(x)

  private fun durata(ore: Double): String {
    val m = Math.round(ore * 60).toInt()
    return "${m / 60}h${"%02d".format(m % 60)}"
  }
}
