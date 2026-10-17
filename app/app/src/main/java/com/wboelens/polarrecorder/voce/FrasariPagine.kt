package com.wboelens.polarrecorder.voce

/** Frasari delle pagine NOTTI, COACH ed ETA. File generato da installa_voce.py. */

/** Pagina NOTTI (una notte). Ogni campo null o zero viene saltato. */
data class DatiNotteVocale(
    val sonnoMin: Int? = null,
    val profondoMin: Int? = null,
    val remMin: Int? = null,
    val leggeroMin: Int? = null,
    val vegliaMin: Int? = null,
    val punteggio: Int? = null,      // 0-100
    val hrvMs: Int? = null,
    val hrvConfronto: Confronto? = null,
    val fcMediaSonno: Int? = null,
    val fcRiposo: Int? = null,
)

object FrasarioNotte {
    fun componi(d: DatiNotteVocale): List<Elemento> {
        val e = mutableListOf<Elemento>()
        d.sonnoMin?.takeIf { it > 0 }?.let { e += Elemento.Seg("sonno_pre"); Mattoni.durata(e, it); e += Elemento.Pausa(Mattoni.PUNTO) }
        d.punteggio?.takeIf { it in 0..100 }?.let {
            e += Elemento.Seg("punteggio_pre"); e += Mattoni.num(it); e += Elemento.Seg("punteggio_post")
            e += Elemento.Pausa(Mattoni.PUNTO)
        }
        fase(e, "notte_profondo", d.profondoMin)
        fase(e, "notte_rem", d.remMin)
        fase(e, "notte_leggero", d.leggeroMin)
        fase(e, "notte_veglia", d.vegliaMin)
        Mattoni.hrv(e, d.hrvMs, d.hrvConfronto)
        Mattoni.battiti(e, "fc_media_pre", d.fcMediaSonno)
        Mattoni.battiti(e, "fc_pre", d.fcRiposo)
        return e
    }

    private fun fase(e: MutableList<Elemento>, id: String, minuti: Int?) {
        val m = minuti?.takeIf { it > 0 } ?: return
        e += Elemento.Seg(id); Mattoni.durata(e, m); e += Elemento.Pausa(Mattoni.PUNTO)
    }
}

/**
 * Pagina COACH. codici = codici decisione/motivo del riepilogo del coach, nell'ordine di lettura.
 * Ogni codice e' un segmento "coach_<codice>" generato da codici_coach.json:
 * un codice senza audio viene saltato (nessun errore).
 */
data class DatiCoachVocale(
    val codici: List<String> = emptyList(),
    val semaforo: Semaforo? = null,
    val sedute: List<SedutaVocale> = emptyList(),
    val giornoDiRiposo: Boolean = false,
)

object FrasarioCoach {
    fun idCodice(codice: String) = "coach_" + codice.lowercase().replace(Regex("[^a-z0-9_]"), "_")

    fun componi(d: DatiCoachVocale): List<Elemento> {
        val e = mutableListOf<Elemento>()
        e += Elemento.Seg("coach_pre"); e += Elemento.Pausa(Mattoni.VIRGOLA)
        Mattoni.semaforo(e, d.semaforo)
        d.codici.forEach { e += Elemento.Seg(idCodice(it)); e += Elemento.Pausa(Mattoni.PUNTO) }
        Mattoni.sedute(e, d.sedute, d.giornoDiRiposo)
        return e
    }
}

/** Pagina ETA NOCTALIX (valori interi, in anni). */
data class DatiEtaVocale(val etaNoctalix: Int, val etaAnagrafica: Int? = null)

object FrasarioEta {
    fun componi(d: DatiEtaVocale): List<Elemento> {
        val e = mutableListOf<Elemento>()
        if (d.etaNoctalix !in 1..Mattoni.NUM_MAX) return e
        e += Elemento.Seg("eta_pre"); anni(e, d.etaNoctalix)
        val reale = d.etaAnagrafica?.takeIf { it in 1..Mattoni.NUM_MAX }
        if (reale == null) return e
        val diff = reale - d.etaNoctalix
        e += Elemento.Pausa(Mattoni.VIRGOLA)
        when {
            diff == 0 -> e += Elemento.Seg("eta_uguale")
            diff > 0 -> { e += Elemento.Seg("eta_meno_pre"); anni(e, diff); e += Elemento.Seg("eta_meno_post") }
            else -> { e += Elemento.Seg("eta_piu_pre"); anni(e, -diff); e += Elemento.Seg("eta_piu_post") }
        }
        return e
    }

    private fun anni(e: MutableList<Elemento>, n: Int) {
        e += Mattoni.num(n); e += Elemento.Seg(if (n == 1) "u_anno" else "u_anni")
    }
}
