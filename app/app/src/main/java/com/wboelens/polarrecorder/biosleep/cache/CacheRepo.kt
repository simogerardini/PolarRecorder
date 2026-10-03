package com.wboelens.polarrecorder.biosleep.cache

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wboelens.polarrecorder.biosleep.readiness.BioBaseline
import com.wboelens.polarrecorder.biosleep.readiness.BioBaselineCalc
import com.wboelens.polarrecorder.biosleep.readiness.BioSleepSeries
import com.wboelens.polarrecorder.biosleep.readiness.FormaCalc
import com.wboelens.polarrecorder.biosleep.readiness.PyJson
import com.wboelens.polarrecorder.biosleep.readiness.RigaForma
import com.wboelens.polarrecorder.biosleep.readiness.StatoForma
import java.time.LocalDate

/** Riga CTL/ATL/TSB della home: oggi, ieri (per la freccia) e la serie per il grafico a 90 giorni. */
data class FormaHome(val oggi: StatoForma?, val ieri: StatoForma?, val serie: List<RigaForma>)

/** Cosa mostrare al posto del colore finche' le notti non bastano. */
sealed interface Prontezza {
  data class Calibrazione(val notti: Int, val servono: Int = NOTTI_CALIBRAZIONE) : Prontezza

  data class Banda(val baseline: BioBaseline) : Prontezza

  companion object {
    /** Sotto 7 notti la banda non si calcola, tra 7 e 13 e' poco affidabile. */
    const val NOTTI_CALIBRAZIONE = 14
  }
}

/**
 * Lettura della cache per le schermate. Solo database locale: funziona offline e risponde subito.
 * Da chiamare fuori dal main thread (es. withContext(Dispatchers.IO) in una schermata).
 */
class CacheRepo(private val db: CacheDb) {
  companion object {
    /** BIOSLEEP_LOOKBACK_DAYS del coach: la banda si calcola sugli stessi 60 giorni. */
    const val GG_BANDA = 60L

    fun get(context: Context) = CacheRepo(CacheDb.get(context))
  }

  private fun oggetti(t: Tabella, da: LocalDate, a: LocalDate): List<JsonObject> =
      db.leggi(t, da.toString(), a.toString()).map { JsonParser.parseString(it).asJsonObject }

  /** Opzione (b): valori di oggi sulle sole sedute eseguite (= forma_oggi del coach). */
  fun forma(oggi: LocalDate): FormaHome {
    val wellness = oggetti(Tabella.WELLNESS, oggi.minusDays(Finestre.GG_STORICO), oggi).map { PyJson.rigaForma(it) }
    val attivita =
        oggetti(Tabella.ATTIVITA, oggi.minusDays(FormaCalc.FINESTRA_GG), oggi).map { PyJson.caricoAttivita(it) }
    val serie = FormaCalc.serieApp(wellness, attivita, oggi)
    val fineIeri = oggi.toString()
    return FormaHome(
        oggi = FormaCalc.statoForma(serie),
        ieri = FormaCalc.statoForma(serie.filter { it.giorno < fineIeri }),
        serie = serie)
  }

  /** Banda biometrica come il coach: serie BioSleep degli ultimi 60 giorni, filtro qualita'. */
  fun baseline(oggi: LocalDate): BioBaseline {
    val righe = oggetti(Tabella.WELLNESS, oggi.minusDays(GG_BANDA), oggi).map { PyJson.wellnessBio(it) }
    return BioBaselineCalc.calcola(BioSleepSeries.daWellness(righe), oggi)
  }

  fun prontezza(oggi: LocalDate): Prontezza {
    val b = baseline(oggi)
    return if (!b.ok || b.nGiorniHrv < Prontezza.NOTTI_CALIBRAZIONE) Prontezza.Calibrazione(b.nGiorniHrv)
    else Prontezza.Banda(b)
  }

  /** Oggetti JSON grezzi: il calendario li trasformera' nei propri modelli. */
  fun eventi(da: LocalDate, a: LocalDate): List<JsonObject> = oggetti(Tabella.EVENTI, da, a)

  fun attivita(da: LocalDate, a: LocalDate): List<JsonObject> = oggetti(Tabella.ATTIVITA, da, a)

  fun wellness(da: LocalDate, a: LocalDate): List<JsonObject> = oggetti(Tabella.WELLNESS, da, a)
}
