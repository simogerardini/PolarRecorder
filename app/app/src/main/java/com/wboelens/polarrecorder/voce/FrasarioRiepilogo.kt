package com.wboelens.polarrecorder.voce

/** Elementi del frasario: segmento audio o pausa. File generato da installa_voce.py. */
sealed class Elemento {
    data class Seg(val id: String) : Elemento()
    data class Pausa(val ms: Int) : Elemento()
}

enum class Confronto { SOPRA, NELLA, SOTTO }
enum class Semaforo { VERDE, GIALLO, ROSSO, GRIGIO }

enum class Disciplina(val id: String) {
    NUOTO("d_nuoto"), BICI("d_bici"), CORSA("d_corsa"), BRICK("d_brick"), FORZA("d_forza");

    companion object {
        /** Dalla sigla dei nomi seduta del cervello: SWIM, BIKE, RUN, BRICK, STR. */
        fun daSigla(sigla: String?): Disciplina? = when (sigla?.trim()?.uppercase()) {
            "SWIM" -> NUOTO; "BIKE" -> BICI; "RUN" -> CORSA; "BRICK" -> BRICK; "STR" -> FORZA
            else -> null
        }
    }
}

data class SedutaVocale(val disciplina: Disciplina, val minuti: Int? = null)

/** Pagina OGGI. Ogni campo null viene saltato. */
data class DatiRiepilogoVocale(
    val sonnoMin: Int? = null,
    val profondoMin: Int? = null,
    val hrvMs: Int? = null,
    val hrvConfronto: Confronto? = null,
    val fcRiposo: Int? = null,
    val semaforo: Semaforo? = null,
    val sedute: List<SedutaVocale> = emptyList(),
    val giornoDiRiposo: Boolean = false,
)

/** Mattoni comuni a tutte le pagine. */
object Mattoni {
    const val NUM_MAX = 200
    const val VIRGOLA = 180
    const val PUNTO = 380

    fun num(n: Int) = Elemento.Seg("n_$n")

    /** "sette ore e ventiquattro minuti", "un'ora", "quaranta minuti"... */
    fun durata(e: MutableList<Elemento>, minutiTotali: Int) {
        val h = minutiTotali / 60
        val m = minutiTotali % 60
        when {
            h == 1 -> e += Elemento.Seg("u_un_ora")
            h > 1 -> { e += num(h.coerceAtMost(NUM_MAX)); e += Elemento.Seg("u_ore") }
        }
        if (h > 0 && m > 0) e += Elemento.Seg("c_e")
        when {
            m == 1 -> { e += num(1); e += Elemento.Seg("u_minuto") }
            m > 1 -> { e += num(m); e += Elemento.Seg("u_minuti") }
        }
    }

    fun hrv(e: MutableList<Elemento>, hrvMs: Int?, confronto: Confronto?) {
        val v = hrvMs?.takeIf { it in 1..NUM_MAX } ?: return
        e += Elemento.Seg("hrv_pre"); e += num(v); e += Elemento.Seg("u_ms")
        confronto?.let { c ->
            e += Elemento.Pausa(VIRGOLA)
            e += Elemento.Seg(
                when (c) {
                    Confronto.SOPRA -> "hrv_sopra"
                    Confronto.NELLA -> "hrv_media"
                    Confronto.SOTTO -> "hrv_sotto"
                }
            )
        }
        e += Elemento.Pausa(PUNTO)
    }

    fun battiti(e: MutableList<Elemento>, pre: String, valore: Int?) {
        val v = valore?.takeIf { it in 1..NUM_MAX } ?: return
        e += Elemento.Seg(pre); e += num(v); e += Elemento.Seg("fc_post"); e += Elemento.Pausa(PUNTO)
    }

    fun semaforo(e: MutableList<Elemento>, s: Semaforo?) {
        s ?: return
        e += Elemento.Seg(
            when (s) {
                Semaforo.VERDE -> "sem_verde"
                Semaforo.GIALLO -> "sem_giallo"
                Semaforo.ROSSO -> "sem_rosso"
                Semaforo.GRIGIO -> "sem_grigio"
            }
        )
        e += Elemento.Pausa(PUNTO)
    }

    /** Sedute di oggi + chiusura. */
    fun sedute(e: MutableList<Elemento>, sedute: List<SedutaVocale>, riposo: Boolean) {
        when {
            riposo -> {
                e += Elemento.Seg("seduta_riposo"); e += Elemento.Pausa(PUNTO)
                e += Elemento.Seg("chiusura_giorno")
            }
            sedute.isNotEmpty() -> {
                e += Elemento.Seg("seduta_pre")
                sedute.forEachIndexed { i, s ->
                    if (i > 0) { e += Elemento.Pausa(VIRGOLA); e += Elemento.Seg("c_poi") }
                    e += Elemento.Seg(s.disciplina.id)
                    s.minuti?.takeIf { it > 0 }?.let { m -> e += Elemento.Pausa(VIRGOLA); durata(e, m) }
                }
                e += Elemento.Pausa(PUNTO)
                e += Elemento.Seg("chiusura_allenamento")
            }
            else -> e += Elemento.Seg("chiusura_giorno")
        }
    }
}

/** Pagina OGGI. */
object FrasarioRiepilogo {
    const val NUM_MAX = Mattoni.NUM_MAX

    fun componi(d: DatiRiepilogoVocale): List<Elemento> {
        val e = mutableListOf<Elemento>()
        e += Elemento.Seg("saluto"); e += Elemento.Pausa(Mattoni.PUNTO)
        d.sonnoMin?.takeIf { it > 0 }?.let { sonno ->
            e += Elemento.Seg("sonno_pre"); Mattoni.durata(e, sonno)
            d.profondoMin?.takeIf { it > 0 }?.let { p ->
                e += Elemento.Pausa(Mattoni.VIRGOLA)
                e += Elemento.Seg("profondo_pre"); Mattoni.durata(e, p); e += Elemento.Seg("profondo_post")
            }
            e += Elemento.Pausa(Mattoni.PUNTO)
        }
        Mattoni.hrv(e, d.hrvMs, d.hrvConfronto)
        Mattoni.battiti(e, "fc_pre", d.fcRiposo)
        Mattoni.semaforo(e, d.semaforo)
        Mattoni.sedute(e, d.sedute, d.giornoDiRiposo)
        return e
    }
}
