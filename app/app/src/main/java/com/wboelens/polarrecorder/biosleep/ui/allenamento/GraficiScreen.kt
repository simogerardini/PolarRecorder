package com.wboelens.polarrecorder.biosleep.ui.allenamento

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.cache.CacheRepo
import com.wboelens.polarrecorder.biosleep.cache.Finestre
import com.wboelens.polarrecorder.biosleep.cache.Prontezza
import com.wboelens.polarrecorder.biosleep.readiness.PyJson
import com.wboelens.polarrecorder.biosleep.readiness.RigaForma as RigaFormaDati
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln

data class DatiGrafici(
    val giorni: List<LocalDate>,
    val ctl: List<Double?>,
    val atl: List<Double?>,
    val tsb: List<Double?>,
    val rmssd: List<Double?>,
    val rmssd7: List<Double?>,
    val fc: List<Double?>,
    val sonno: List<Double?>,
    val rangeHrv: Pair<Double, Double>?,
) {
  companion object {
    fun carica(repo: CacheRepo, oggi: LocalDate): DatiGrafici {
      val da = oggi.minusDays(Finestre.GG_STORICO - 1)
      val giorni = generateSequence(da) { it.plusDays(1) }.takeWhile { !it.isAfter(oggi) }.toList()
      val forma: Map<String, RigaFormaDati> = repo.forma(oggi).serie.associateBy { it.giorno }
      val wellness = repo.wellness(da, oggi).associateBy { PyJson.str(it.get("id")) ?: "" }
      fun campo(nome: String) = giorni.map { d -> wellness[d.toString()]?.let { PyJson.num(it.get(nome))?.v } }
      val rmssd = campo("BioSleepRMSSD")
      // Media 7 giorni come il coach: media geometrica (exp della media dei logaritmi), almeno 3 notti
      val rmssd7 =
          giorni.indices.map { i ->
            val finestra = (maxOf(0, i - 6)..i).mapNotNull { rmssd[it] }.filter { it > 0 }
            if (finestra.size >= 3) exp(finestra.sumOf { ln(it) } / finestra.size) else null
          }
      val range = (repo.prontezza(oggi) as? Prontezza.Banda)?.baseline?.normalRangeMs
      return DatiGrafici(
          giorni = giorni,
          ctl = giorni.map { forma[it.toString()]?.ctl },
          atl = giorni.map { forma[it.toString()]?.atl },
          tsb = giorni.map { d -> forma[d.toString()]?.let { r -> if (r.ctl != null && r.atl != null) r.ctl - r.atl else null } },
          rmssd = rmssd,
          rmssd7 = rmssd7,
          fc = campo("BioSleepAvgHR"),
          sonno = campo("BioSleepSleepHours"),
          rangeHrv = range,
      )
    }
  }

  /** Gli ultimi n giorni di ogni serie. */
  fun ultimi(n: Int): DatiGrafici {
    fun <T> List<T>.coda() = takeLast(n)
    return copy(
        giorni = giorni.coda(), ctl = ctl.coda(), atl = atl.coda(), tsb = tsb.coda(), rmssd = rmssd.coda(),
        rmssd7 = rmssd7.coda(), fc = fc.coda(), sonno = sonno.coda())
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraficiScreen(bottomBar: @Composable () -> Unit) {
  val tutti = rememberDallaCache { repo, oggi -> DatiGrafici.carica(repo, oggi) }
  var periodo by rememberSaveable { mutableIntStateOf(90) }
  Scaffold(
      topBar = { TopAppBar(title = { Text("Grafici") }, actions = { AzioneAggiorna() }) },
      bottomBar = bottomBar,
  ) { padding ->
    if (tutti == null) {
      Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
      return@Scaffold
    }
    val d = tutti.ultimi(periodo)
    val altezza = Modifier.fillMaxWidth().height(200.dp)
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (p in listOf(30, 90)) FilterChip(selected = p == periodo, onClick = { periodo = p }, label = { Text("$p giorni") })
      }
      Sezione("Carico di allenamento") {
        GraficoLinee(
            d.giorni,
            listOf(
                SerieGrafico("CTL forma fisica", ColoriBio.ctl, d.ctl),
                SerieGrafico("ATL stanchezza", ColoriBio.atl, d.atl),
                SerieGrafico("TSB forma", ColoriBio.tsb, d.tsb)),
            altezza,
            lineaZero = true)
        Nota("Ultimi 14 giorni ricalcolati sulle sole sedute svolte, come il coach.")
      }
      Sezione("HRV notturno (rMSSD, ms)") {
        GraficoLinee(
            d.giorni,
            listOf(
                SerieGrafico("notte", Color(0xFF7E57C2).copy(alpha = 0.6f), d.rmssd, punti = true),
                SerieGrafico("media 7 gg", Color(0xFF5E35B1), d.rmssd7)),
            altezza,
            banda = d.rangeHrv)
        Nota(
            if (d.rangeHrv != null) "Il range si confronta con la media 7 gg, non con la singola notte."
            else "Il range compare dopo 14 notti valide.")
      }
      Sezione("FC a riposo (media notte, bpm)") {
        GraficoLinee(d.giorni, listOf(SerieGrafico("notte", ColoriBio.rosso, d.fc, punti = true)), altezza)
      }
      Sezione("Sonno (ore)") {
        GraficoLinee(d.giorni, listOf(SerieGrafico("notte", ColoriBio.blu, d.sonno, punti = true)), altezza)
      }
    }
  }
}

@Composable
private fun Nota(testo: String) {
  Text(testo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
