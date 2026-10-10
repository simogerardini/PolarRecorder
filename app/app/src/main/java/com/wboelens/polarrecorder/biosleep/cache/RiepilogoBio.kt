package com.wboelens.polarrecorder.biosleep.cache

import android.content.Context
import com.wboelens.polarrecorder.biosleep.intervals.CampiWellness
import com.wboelens.polarrecorder.biosleep.lingua.TestiSistema
import com.wboelens.polarrecorder.biosleep.readiness.Consiglio
import com.wboelens.polarrecorder.biosleep.training.Formato
import java.time.LocalDate

/**
 * Riga della notifica del mattino in modalita' solo biometria: il consiglio del giorno (lo stesso
 * della card in Oggi), la banda HRV e i motivi. Tradotta qui: gli emoji in testa non farebbero
 * riconoscere la frase piu' tardi.
 */
object RiepilogoBio {
  fun riga(context: Context, oggi: LocalDate = LocalDate.now()): String? =
      try {
        fun t(x: String) = TestiSistema.traduci(context, x)
        val repo = CacheRepo.get(context)
        when (val p = repo.prontezza(oggi)) {
          is Prontezza.Calibrazione -> t("Recupero: calibrazione ${p.notti}/${p.servono} notti")
          is Prontezza.Banda -> {
            val b = p.baseline
            val sonno =
                repo.wellness(oggi, oggi).lastOrNull()?.get(CampiWellness.F_SLEEP_HOURS)?.takeIf { !it.isJsonNull }?.asDouble
            val e = Consiglio.calcola(b, sonno)
            val banda =
                when (b.banda) {
                  "verde" -> "HRV nella norma"
                  "giallo" -> "HRV sotto il range"
                  "rosso" -> "HRV molto sotto il range"
                  else -> "Non valutabile"
                }
            val parti = mutableListOf<String>()
            parti += if (e != null) "${Consiglio.emoji(e.livello)} " + t(Consiglio.titolo(e.livello)) else t(banda)
            if (e != null) parti += t(banda)
            b.rolling7Hrv?.let { parti += t("media 7 gg ${Formato.decimale(it)} ms") }
            e?.motivi?.forEach { parti += t(it) }
            parti.joinToString(" · ")
          }
        }
      } catch (e: Exception) {
        null
      }
}
