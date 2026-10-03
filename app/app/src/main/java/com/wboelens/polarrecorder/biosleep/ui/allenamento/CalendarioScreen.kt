package com.wboelens.polarrecorder.biosleep.ui.allenamento

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.cache.CacheRepo
import com.wboelens.polarrecorder.biosleep.cache.FormaHome
import com.wboelens.polarrecorder.biosleep.training.Allenamenti
import com.wboelens.polarrecorder.biosleep.training.AttivitaCal
import com.wboelens.polarrecorder.biosleep.training.Esito
import com.wboelens.polarrecorder.biosleep.training.Formato
import com.wboelens.polarrecorder.biosleep.training.GiornoCal
import com.wboelens.polarrecorder.biosleep.training.Metrica
import com.wboelens.polarrecorder.biosleep.training.SedutaPianificata
import com.wboelens.polarrecorder.biosleep.training.Struttura
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class DatiCalendario(
    val oggi: LocalDate,
    val forma: FormaHome,
    val giorni: List<GiornoCal>,
    /** Lunedi' -> (TSS svolto, TSS pianificato) della settimana. */
    val settimane: Map<LocalDate, Pair<Double, Double>>,
) {
  companion object {
    const val GG_INDIETRO = 56L
    const val GG_AVANTI = 42L

    fun carica(repo: CacheRepo, oggi: LocalDate): DatiCalendario {
      val da = Allenamenti.lunedi(oggi.minusDays(GG_INDIETRO))
      val a = oggi.plusDays(GG_AVANTI)
      val eventi = repo.eventi(da, a).mapNotNull { Allenamenti.evento(it) }
      val attivita = repo.attivita(da, oggi).mapNotNull { Allenamenti.attivita(it) }
      val settimane =
          generateSequence(da) { it.plusWeeks(1) }
              .takeWhile { !it.isAfter(a) }
              .associateWith { l ->
                val b = Allenamenti.settimana(eventi, attivita, l, Metrica.TSS)
                b.sumOf { it.svolto } to b.sumOf { it.pianificato }
              }
      return DatiCalendario(oggi, repo.forma(oggi), Allenamenti.giorni(eventi, attivita, da, a, oggi), settimane)
    }
  }
}

/** Cosa mostrare nella scheda di dettaglio. */
sealed interface Dettaglio {
  data class Pianificata(val s: SedutaPianificata) : Dettaglio

  data class Libera(val a: AttivitaCal) : Dettaglio
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarioScreen(data: LocalDate?, evento: String?, bottomBar: @Composable () -> Unit) {
  val dati = rememberDallaCache { repo, oggi -> DatiCalendario.carica(repo, oggi) }
  var dettaglio by remember { mutableStateOf<Dettaglio?>(null) }
  val lista = rememberLazyListState()

  // All'arrivo dei dati: scorre al giorno richiesto (o a oggi) e apre la seduta richiesta.
  LaunchedEffect(dati != null) {
    val d = dati ?: return@LaunchedEffect
    val obiettivo = data ?: d.oggi
    val indice = ChronoUnit.DAYS.between(d.giorni.first().data, obiettivo).toInt().coerceIn(0, d.giorni.lastIndex)
    lista.scrollToItem(1 + indice) // l'elemento 0 e' la riga della forma
    if (evento != null) {
      d.giorni.getOrNull(indice)?.pianificate?.firstOrNull { it.evento.id == evento }?.let {
        dettaglio = Dettaglio.Pianificata(it)
      }
    }
  }

  Scaffold(
      topBar = { TopAppBar(title = { Text("Calendario") }, actions = { AzioneAggiorna() }) },
      bottomBar = bottomBar,
  ) { padding ->
    if (dati == null) {
      Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
      return@Scaffold
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        state = lista,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      item {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          StatoAggiornamento()
          RigaForma(dati.forma)
        }
      }
      items(dati.giorni, key = { it.data.toString() }) { g ->
        Giorno(g, dati, onApri = { dettaglio = it })
      }
    }
  }

  dettaglio?.let { det ->
    ModalBottomSheet(onDismissRequest = { dettaglio = null }) { SchedaDettaglio(det) }
  }
}

@Composable
private fun Giorno(g: GiornoCal, dati: DatiCalendario, onApri: (Dettaglio) -> Unit) {
  val oggi = g.data == dati.oggi
  Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (g.data.dayOfWeek == DayOfWeek.MONDAY) {
      val (svolto, pianificato) = dati.settimane[g.data] ?: (0.0 to 0.0)
      HorizontalDivider(Modifier.padding(top = 8.dp))
      Text(
          "Settimana dal ${DateIt.asse(g.data)} · TSS ${svolto.toInt()} svolto / ${pianificato.toInt()} pianificato",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(
        (if (oggi) "Oggi · " else "") + DateIt.breve(g.data),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = if (oggi) FontWeight.Bold else FontWeight.Normal,
        color = if (oggi) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    for (n in g.note) Text(n.nome, style = MaterialTheme.typography.bodySmall)
    for (s in g.pianificate) CardSeduta(s, onClick = { onApri(Dettaglio.Pianificata(s)) })
    for (a in g.nonPianificate) CardAttivita(a, onClick = { onApri(Dettaglio.Libera(a)) })
  }
}

@Composable
private fun SchedaDettaglio(det: Dettaglio) {
  Column(
      Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    when (det) {
      is Dettaglio.Pianificata -> {
        val e = det.s.evento
        Text(e.nome, style = MaterialTheme.typography.titleLarge)
        Text("${DateIt.lunga(e.data)} · ${e.sport.etichetta}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Blocco("Pianificato", e.durataS, e.tss, e.distanzaM, null)
        val segmenti = remember(e.id) { Struttura.segmenti(e.passi) }
        GraficoSeduta(segmenti, ColoriBio.sport(e.sport), Modifier.fillMaxWidth().height(80.dp))
        val righe = remember(e.id) { Struttura.righe(e.passi) }
        for (r in righe) {
          Text(
              r.testo,
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = if (r.livello == 0 && r.testo.endsWith("x")) FontWeight.Bold else FontWeight.Normal,
              modifier = Modifier.padding(start = (16 * r.livello).dp))
        }
        if (righe.isEmpty() && !e.descrizione.isNullOrBlank()) Text(e.descrizione, style = MaterialTheme.typography.bodyMedium)
        if (e.specchio) {
          // Seduta su Garmin Connect (multisport): le parti, la guida strutturata e' sull'orologio
          for (p in e.parti) Text("${p.sport.etichetta} · ${Formato.durata(p.minuti * 60)}", style = MaterialTheme.typography.bodyMedium)
          Text(
              "Seduta su Garmin Connect: la guida strutturata e' sull'orologio.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val consuntivo = det.s.consuntivo
        when {
          consuntivo != null ->
              Blocco("Svolto", consuntivo.durataS, consuntivo.tss, consuntivo.distanzaM, consuntivo.compliance)
          det.s.esito == Esito.NON_SVOLTA ->
              Text("Non svolta", color = ColoriBio.rosso, fontWeight = FontWeight.Bold)
          else -> {}
        }
      }
      is Dettaglio.Libera -> {
        val a = det.a
        Text(a.nome, style = MaterialTheme.typography.titleLarge)
        Text("${DateIt.lunga(a.data)} · ${a.sport.etichetta} · non pianificata", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Blocco("Svolto", a.durataS, a.tss, a.distanzaM, null)
      }
    }
  }
}

@Composable
private fun Blocco(titolo: String, durataS: Int?, tss: Int?, distanzaM: Double?, compliance: Double?) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(titolo, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Cifra("Durata", Formato.durata(durataS))
      Cifra("TSS", tss?.toString() ?: "—")
      Cifra("Distanza", Formato.distanza(distanzaM))
      if (compliance != null && compliance > 0) Cifra("Aderenza", "${compliance.toInt()}%", ColoriBio.aderenza(Allenamenti.aderenza(compliance)))
    }
  }
}

@Composable
private fun Cifra(etichetta: String, valore: String, colore: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(valore, style = MaterialTheme.typography.titleMedium, color = colore)
    Text(etichetta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}
