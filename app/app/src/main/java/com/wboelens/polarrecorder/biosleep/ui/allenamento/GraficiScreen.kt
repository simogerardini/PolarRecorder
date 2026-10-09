package com.wboelens.polarrecorder.biosleep.ui.allenamento

import com.wboelens.polarrecorder.R
import androidx.compose.ui.res.stringResource
import com.wboelens.polarrecorder.biosleep.intervals.CampiWellness
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.cache.CacheRepo
import com.wboelens.polarrecorder.biosleep.cache.Finestre
import com.wboelens.polarrecorder.biosleep.cache.Prontezza
import com.wboelens.polarrecorder.biosleep.readiness.FormaCalc
import com.wboelens.polarrecorder.biosleep.readiness.PyJson
import com.wboelens.polarrecorder.biosleep.readiness.StatoForma
import com.wboelens.polarrecorder.biosleep.training.Formato
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
    val formaOggi: StatoForma?,
) {
  companion object {
    fun carica(repo: CacheRepo, oggi: LocalDate): DatiGrafici {
      val da = oggi.minusDays(Finestre.GG_STORICO - 1)
      val giorni = generateSequence(da) { it.plusDays(1) }.takeWhile { !it.isAfter(oggi) }.toList()
      val home = repo.forma(oggi)
      val forma: Map<String, RigaFormaDati> = home.serie.associateBy { it.giorno }
      val wellness = repo.wellness(da, oggi).associateBy { PyJson.str(it.get("id")) ?: "" }
      fun campo(nome: String) = giorni.map { d -> wellness[d.toString()]?.let { PyJson.num(it.get(nome))?.v } }
      val rmssd = campo(CampiWellness.F_RMSSD)
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
          fc = campo(CampiWellness.F_AVG_HR),
          sonno = campo(CampiWellness.F_SLEEP_HOURS),
          rangeHrv = range,
          formaOggi = home.oggi,
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
      topBar = { TopAppBar(title = { Text(stringResource(R.string.grafici_grafici)) }, actions = { AzioneAggiorna() }) },
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
        for (p in listOf(30, 90)) FilterChip(selected = p == periodo, onClick = { periodo = p }, label = { Text(stringResource(R.string.grafici_giorni, p.toString())) })
      }
      Sezione("Forma fisica e stanchezza") {
        GraficoLinee(
            d.giorni,
            listOf(SerieGrafico("CTL forma fisica", ColoriBio.ctl, d.ctl), SerieGrafico("ATL stanchezza", ColoriBio.atl, d.atl)),
            altezza)
        Nota(stringResource(R.string.grafici_quando_la_stanchezza_atl))
      }
      Sezione("Forma (TSB) e zone") { FormaZone(d) }
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

/** Fasce di zona del coach (stato_forma, sul TSB): Transizione > 20, Fresco, Grigia, Ottimale, Alto rischio < -30. */
private fun fasceZona(): List<FasciaGrafico> {
  val soglie = FormaCalc.BANDE.map { it.first } // 20, 5, -10, -30
  val out = ArrayList<FasciaGrafico>()
  var sopra = Double.POSITIVE_INFINITY
  for ((soglia, _, colore) in FormaCalc.BANDE) {
    out.add(FasciaGrafico(soglia, sopra, ColoriBio.daNome(colore).copy(alpha = 0.18f)))
    sopra = soglia
  }
  out.add(FasciaGrafico(Double.NEGATIVE_INFINITY, soglie.last(), ColoriBio.rosso.copy(alpha = 0.18f)))
  return out
}

@Composable
private fun FormaZone(d: DatiGrafici) {
  d.formaOggi?.let { f ->
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(12.dp).background(ColoriBio.daNome(f.colore), CircleShape))
      Text(
          "  Oggi TSB ${Formato.conSegno(f.tsb)} · zona ${f.zona} da ${f.giorniInZona} " +
              if (f.giorniInZona == 1) "giorno" else "giorni",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold)
    }
  }
  GraficoLinee(
      d.giorni,
      listOf(SerieGrafico("TSB", MaterialTheme.colorScheme.onSurface, d.tsb)),
      Modifier.fillMaxWidth().height(200.dp),
      lineaZero = true,
      fasce = fasceZona(),
      includi = listOf(-15.0, 10.0), // almeno le zone vicine allo zero sempre visibili
      legenda = false)
  // Legenda delle zone, dall'alto in basso come nel grafico
  val voci = FormaCalc.BANDE.map { (soglia, nome, colore) -> Triple(nome, colore, soglia) }
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    var sopra: Double? = null
    for ((nome, colore, soglia) in voci) {
      VoceZona(colore, nome, if (sopra == null) "oltre ${Formato.conSegno(soglia, 0)}" else "da ${Formato.conSegno(soglia, 0)} a ${Formato.conSegno(sopra, 0)}")
      sopra = soglia
    }
    VoceZona("rosso", "Alto rischio", "sotto ${Formato.conSegno(voci.last().third, 0)}")
  }
}

@Composable
private fun VoceZona(colore: String, nome: String, intervallo: String) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(10.dp).background(ColoriBio.daNome(colore).copy(alpha = 0.5f), CircleShape))
    Text("  $nome", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(110.dp))
    Text(intervallo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun Nota(testo: String) {
  Text(testo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
