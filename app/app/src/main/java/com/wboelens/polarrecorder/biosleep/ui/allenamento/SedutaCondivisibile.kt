package com.wboelens.polarrecorder.biosleep.ui.allenamento

import android.content.Context
import android.util.Log
import com.wboelens.polarrecorder.biosleep.cache.CacheRepo
import com.wboelens.polarrecorder.biosleep.cache.Prontezza
import com.wboelens.polarrecorder.biosleep.cervello.ModalitaCoach
import com.wboelens.polarrecorder.biosleep.condivisione.CardSeduta
import com.wboelens.polarrecorder.biosleep.intervals.CampiWellness
import com.wboelens.polarrecorder.biosleep.lingua.TestiSistema
import com.wboelens.polarrecorder.biosleep.readiness.Consiglio
import com.wboelens.polarrecorder.biosleep.readiness.PyJson
import com.wboelens.polarrecorder.biosleep.training.Allenamenti
import com.wboelens.polarrecorder.biosleep.training.DettaglioAttivita

/**
 * Dati della card "seduta svolta" (modulo condivisione, Parte 5), dalla cache: sport e nome dalla
 * seduta PIANIFICATA abbinata (coach NoctaliX), durata/distanza/TSS dall'attivita', recupero del
 * mattino da NoctaliX. null se l'attivita' non ha una seduta pianificata abbinata: niente pulsante.
 * Da chiamare fuori dal main thread.
 */
object SedutaCondivisibile {
  private const val TAG = "Condivisione"

  fun carica(context: Context, a: DettaglioAttivita): CardSeduta? =
      try {
        val giorno = a.inizio?.toLocalDate() ?: return null
        val repo = CacheRepo.get(context)
        val att = repo.attivita(giorno, giorno).firstOrNull { PyJson.str(it.get("id")) == a.id } ?: return null
        val eventoId = PyJson.str(att.get("paired_event_id")) ?: return null
        val evento = repo.eventi(giorno, giorno).mapNotNull { Allenamenti.evento(it) }.firstOrNull { it.id == eventoId } ?: return null
        // Origine dell'attivita': attribuzione Garmin tranne che per un inserimento a mano.
        // Il valore va nel log per verificarlo su dati reali (adb logcat -s Condivisione).
        val origine = PyJson.str(att.get("source"))
        Log.i(TAG, "attivita' ${a.id}: source=$origine, device=${PyJson.str(att.get("device_name"))}")
        fun t(x: String) = TestiSistema.traduci(context, x)
        CardSeduta(
            sport = t(evento.sport.etichetta),
            nome = evento.nome,
            durataMin = (a.durataS ?: evento.durataS ?: 0) / 60,
            giorno = giorno,
            distanzaKm = a.distanzaM?.let { it / 1000.0 },
            tss = a.tss,
            recupero = recupero(context, repo, giorno, ::t),
            datiGarmin = origine == null || !origine.equals("MANUAL", ignoreCase = true),
            // valori a scelta nell'anteprima (Parte 5): tutto quello che l'attivita' ha, null se manca
            sportCodice = evento.sport.name,
            durataS = a.durataS,                                   // moving_time
            dislivelloM = a.dislivello?.toInt(),                   // total_elevation_gain
            potenzaW = a.wattMedi,                                 // icu_average_watts (media, non normalizzata)
            fcMedia = a.fcMedia,
            calorie = a.calorie,
            // compliance di Intervals.icu, gia' in percentuale (puo' superare 100)
            pianoPct = (a.aderenza ?: PyJson.num(att.get("compliance"))?.v)?.takeIf { it > 0 }?.let { Math.round(it).toInt() },
        )
      } catch (e: Exception) {
        Log.w(TAG, "card della seduta non disponibile", e)
        null
      }

  /** Banda HRV del mattino (coach acceso) o consiglio del giorno (solo biometria); null in calibrazione. */
  private fun recupero(context: Context, repo: CacheRepo, giorno: java.time.LocalDate, t: (String) -> String): String? {
    val b = (repo.prontezza(giorno) as? Prontezza.Banda)?.baseline ?: return null
    if (!ModalitaCoach.attivo(context)) {
      val sonno = repo.wellness(giorno, giorno).lastOrNull()?.get(CampiWellness.F_SLEEP_HOURS)?.takeIf { !it.isJsonNull }?.asDouble
      Consiglio.calcola(b, sonno)?.let { return t(Consiglio.titolo(it.livello)) }
    }
    return when (b.banda) {
      "verde" -> t("HRV nella norma")
      "giallo" -> t("HRV sotto il range")
      "rosso" -> t("HRV molto sotto il range")
      else -> null
    }
  }
}
