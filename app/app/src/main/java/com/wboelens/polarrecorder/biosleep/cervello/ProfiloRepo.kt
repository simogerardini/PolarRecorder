package com.wboelens.polarrecorder.biosleep.cervello

import android.content.Context
import com.wboelens.polarrecorder.biosleep.SleepDb
import com.wboelens.polarrecorder.biosleep.cache.CacheRepo
import com.wboelens.polarrecorder.biosleep.readiness.PyJson
import java.time.LocalDate

/** Profilo da mandare al cervello e valori proposti per la schermata Profilo. Fuori dal main thread. */
object ProfiloRepo {
  fun suggerimenti(context: Context, oggi: LocalDate = LocalDate.now()): Suggerimenti {
    val maxSedute =
        CacheRepo.get(context).attivita(oggi.minusDays(90), oggi).mapNotNull { o ->
          val fc = PyJson.num(o.get("max_heartrate"))?.v?.toInt() ?: return@mapNotNull null
          (PyJson.str(o.get("start_date_local"))?.take(10) ?: "") to fc
        }
    val riposo =
        runCatching { SleepDb.get(context).listNights() }.getOrDefault(emptyList())
            .sortedBy { it.summary.endMs }
            .map { it.summary.restingHr }
    return SuggerimentiProfilo.calcola(maxSedute, riposo)
  }

  /**
   * Profilo salvato; se la FC a riposo non e' impostata si usa quella misurata dalle notti
   * BioSleep (almeno 5). La FC massima invece solo se impostata o confermata nella schermata:
   * un picco anomalo del cardiofrequenzimetro non deve cambiare le zone da solo.
   */
  fun effettivo(context: Context): ProfiloAtleta {
    val p = ProfiloStore(context).leggi()
    if (p.fcRiposo != null) return p
    val misurata = runCatching { suggerimenti(context).fcRiposo }.getOrNull()
    return p.copy(fcRiposo = misurata)
  }
}
