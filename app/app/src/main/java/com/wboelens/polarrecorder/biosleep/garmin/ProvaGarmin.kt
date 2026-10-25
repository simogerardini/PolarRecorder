package com.wboelens.polarrecorder.biosleep.garmin

import android.content.Context
import java.time.LocalDate

/** Esito della prova di collegamento mostrato nelle impostazioni. */
sealed interface EsitoProvaGarmin {
  /** Collegato; ultima notte con HRV negli ultimi 30 giorni (null = nessuna). */
  data class Ok(val ultimaHrvMs: Int?, val giorno: String?) : EsitoProvaGarmin

  data object ChiaveNonValida : EsitoProvaGarmin

  data object TroppeRichieste : EsitoProvaGarmin

  data class Errore(val messaggio: String) : EsitoProvaGarmin
}

/** Parte 11: prova il collegamento leggendo il riepilogo HRV degli ultimi 30 giorni. */
object ProvaGarmin {
  fun prova(context: Context, oggi: LocalDate = LocalDate.now()): EsitoProvaGarmin =
      when (val r = GarminClient.leggi(context, "/hrv-service/hrv/daily/${oggi.minusDays(30)}/$oggi")) {
        is LetturaGarmin.Ok -> {
          val ultima =
              runCatching {
                r.json.asJsonObject.getAsJsonArray("hrvSummaries")
                    .mapNotNull { it.asJsonObject }
                    .filter { o -> o.get("lastNightAvg")?.takeIf { !it.isJsonNull }?.asInt?.let { it > 0 } == true }
                    .maxByOrNull { it.get("calendarDate").asString }
              }.getOrNull()
          EsitoProvaGarmin.Ok(ultima?.get("lastNightAvg")?.asInt, ultima?.get("calendarDate")?.asString)
        }
        LetturaGarmin.Vuoto -> EsitoProvaGarmin.Ok(null, null)
        LetturaGarmin.ChiaveNonValida -> EsitoProvaGarmin.ChiaveNonValida
        LetturaGarmin.TroppeRichieste -> EsitoProvaGarmin.TroppeRichieste
        is LetturaGarmin.Errore -> EsitoProvaGarmin.Errore(r.messaggio)
      }
}
