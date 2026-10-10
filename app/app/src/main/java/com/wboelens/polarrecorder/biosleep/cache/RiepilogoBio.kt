package com.wboelens.polarrecorder.biosleep.cache

import android.content.Context
import com.wboelens.polarrecorder.biosleep.lingua.TestiSistema
import com.wboelens.polarrecorder.biosleep.training.Formato
import java.time.LocalDate

/**
 * Riga "Recupero" della notifica del mattino in modalita' solo biometria: semaforo della banda HRV
 * (stesso calcolo del coach, su cache + notti locali) e FC a riposo sopra l'abituale.
 */
object RiepilogoBio {
  fun riga(context: Context, oggi: LocalDate = LocalDate.now()): String? =
      try {
        // tradotta qui: gli emoji in testa non farebbero riconoscere la frase piu' tardi
        fun t(x: String) = TestiSistema.traduci(context, x)
        when (val p = CacheRepo.get(context).prontezza(oggi)) {
          is Prontezza.Calibrazione -> t("Recupero: calibrazione ${p.notti}/${p.servono} notti")
          is Prontezza.Banda -> {
            val b = p.baseline
            val (emoji, etichetta) =
                when (b.banda) {
                  "verde" -> "🟢" to "HRV nella norma"
                  "giallo" -> "🟡" to "HRV sotto il range"
                  "rosso" -> "🔴" to "HRV molto sotto il range"
                  else -> "⚪" to "Non valutabile"
                }
            val parti = mutableListOf("$emoji " + t("Recupero: $etichetta"))
            b.rolling7Hrv?.let { parti += t("media 7 gg ${Formato.decimale(it)} ms") }
            b.fcRiposo?.takeIf { it.allarme }?.let { f ->
              parti += "⚠️ " + t("FC a riposo sopra l'abituale (${f.delta?.let { Formato.conSegno(it) } ?: Formato.decimale(f.rolling7)} bpm)")
            }
            parti.joinToString(" · ")
          }
        }
      } catch (e: Exception) {
        null
      }
}
