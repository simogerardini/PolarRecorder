package com.wboelens.polarrecorder.biosleep.voce

import com.google.gson.JsonObject
import com.wboelens.polarrecorder.biosleep.cache.CacheRepo
import com.wboelens.polarrecorder.biosleep.cache.Prontezza
import com.wboelens.polarrecorder.biosleep.intervals.CampiWellness
import com.wboelens.polarrecorder.biosleep.readiness.BioBaseline
import com.wboelens.polarrecorder.biosleep.readiness.CampiStorici
import com.wboelens.polarrecorder.biosleep.readiness.PyJson
import com.wboelens.polarrecorder.biosleep.training.Allenamenti
import com.wboelens.polarrecorder.voce.Confronto
import com.wboelens.polarrecorder.voce.DatiRiepilogoVocale
import com.wboelens.polarrecorder.voce.Disciplina
import com.wboelens.polarrecorder.voce.Semaforo
import com.wboelens.polarrecorder.voce.SedutaVocale
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Dati per la voce (Parte 10) dalle strutture dell'app. Funzioni pure o di sola lettura: il
 * package com.wboelens.polarrecorder.voce (generato da installa_voce.py) non si tocca.
 */
object VoceDati {
  /** Banda biometrica -> semaforo; calibrazione o nessuna banda = grigio. */
  fun semaforo(banda: String?): Semaforo =
      when (banda) {
        "verde" -> Semaforo.VERDE
        "giallo", "gialla", "arancione" -> Semaforo.GIALLO
        "rosso", "rossa" -> Semaforo.ROSSO
        else -> Semaforo.GRIGIO
      }

  fun semaforo(p: Prontezza): Semaforo = if (p is Prontezza.Banda && p.baseline.ok) semaforo(p.baseline.banda) else Semaforo.GRIGIO

  /** rMSSD della notte rispetto al normal range della banda. */
  fun confronto(rmssd: Double?, range: Pair<Double, Double>?): Confronto? {
    val v = rmssd ?: return null
    val (lo, hi) = range ?: return null
    return when {
      v > hi -> Confronto.SOPRA
      v < lo -> Confronto.SOTTO
      else -> Confronto.NELLA
    }
  }

  /** Seduta dal nome del coach ("BIKE · Soglia · 4x10min"): disciplina dalla sigla, minuti dalla durata. */
  fun seduta(nome: String?, minuti: Int?): SedutaVocale? =
      Disciplina.daSigla(nome?.substringBefore(" · ")?.substringBefore("·"))?.let { SedutaVocale(it, minuti?.takeIf { m -> m > 0 }) }

  /**
   * Pagina Oggi: (chiave del contenuto = data della notte piu' recente analizzata, dati vocali).
   * Senza coach (solo biometria) niente sedute. null se nessuna notte negli ultimi 7 giorni.
   */
  fun oggi(repo: CacheRepo, oggi: LocalDate, conSedute: Boolean): Pair<String, DatiRiepilogoVocale>? {
    val notte: JsonObject =
        repo.wellness(oggi.minusDays(6), oggi)
            .filter { CampiStorici.leggi(it, CampiWellness.F_RMSSD) != null }
            .maxByOrNull { PyJson.str(it.get("id")).orEmpty() } ?: return null
    val data = PyJson.str(notte.get("id"))?.take(10) ?: return null
    fun n(c: String) = PyJson.num(CampiStorici.leggi(notte, c))?.v
    val p = repo.prontezza(oggi)
    val b: BioBaseline? = (p as? Prontezza.Banda)?.baseline
    val rmssd = n(CampiWellness.F_RMSSD)
    val eventi = if (conSedute) repo.eventi(oggi, oggi).mapNotNull { Allenamenti.evento(it) }.filter { it.data == oggi } else emptyList()
    val sedute = eventi.mapNotNull { e -> seduta(e.nome, e.durataS?.let { it / 60 }) }
    return data to
        DatiRiepilogoVocale(
            sonnoMin = n(CampiWellness.F_SLEEP_HOURS)?.let { (it * 60).roundToInt() },
            profondoMin = n(CampiWellness.F_DEEP_MIN)?.roundToInt(),
            hrvMs = rmssd?.roundToInt(),
            hrvConfronto = confronto(rmssd, b?.normalRangeMs),
            fcRiposo = n(CampiWellness.F_RHR)?.roundToInt(),
            semaforo = semaforo(p),
            sedute = sedute,
            giornoDiRiposo = conSedute && sedute.isEmpty(),
        )
  }
}
