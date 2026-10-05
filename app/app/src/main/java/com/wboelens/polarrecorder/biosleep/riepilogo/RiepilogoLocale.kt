package com.wboelens.polarrecorder.biosleep.riepilogo

import com.wboelens.polarrecorder.biosleep.cache.CacheRepo
import com.wboelens.polarrecorder.biosleep.training.Allenamenti
import com.wboelens.polarrecorder.biosleep.training.GiornoCal
import java.time.LocalDate

/**
 * Quello che la schermata del coach prende dall'app e non dal riepilogo: la seduta del giorno dal
 * calendario (con abbinamento allo svolto) e la storia della TSB per il grafico. Tutti i blocchi
 * del coach (biometria, forma, discipline, volume, carico) arrivano dal cervello.
 */
data class RiepilogoLocale(
    val giorno: GiornoCal?,
    /** TSB giorno per giorno (serie della forma dell'app), per il grafico della schermata del coach. */
    val storicoTsb: List<Pair<LocalDate, Double>> = emptyList(),
) {
  companion object {
    fun calcola(repo: CacheRepo, giorno: LocalDate, oggi: LocalDate = LocalDate.now()): RiepilogoLocale {
      val eventi = repo.eventi(giorno, giorno).mapNotNull { Allenamenti.evento(it) }
      val attivita = repo.attivita(giorno, giorno).mapNotNull { Allenamenti.attivita(it) }
      return RiepilogoLocale(
          giorno = Allenamenti.giorni(eventi, attivita, giorno, giorno, oggi).firstOrNull(),
          storicoTsb =
              repo.forma(giorno).serie.mapNotNull { r ->
                val d = runCatching { LocalDate.parse(r.giorno) }.getOrNull() ?: return@mapNotNull null
                if (r.ctl != null && r.atl != null && d <= giorno) d to (r.ctl - r.atl) else null
              },
      )
    }
  }
}
