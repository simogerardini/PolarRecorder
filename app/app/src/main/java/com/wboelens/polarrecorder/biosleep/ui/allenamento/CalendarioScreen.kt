package com.wboelens.polarrecorder.biosleep.ui.allenamento

import com.wboelens.polarrecorder.biosleep.lingua.tr
import com.wboelens.polarrecorder.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.wboelens.polarrecorder.biosleep.cervello.Cervello
import com.wboelens.polarrecorder.biosleep.cervello.DisponibilitaDate
import com.wboelens.polarrecorder.biosleep.cervello.Pausa
import com.wboelens.polarrecorder.biosleep.cervello.Pause
import com.wboelens.polarrecorder.biosleep.cervello.PianoCalendario
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsSettings
import com.wboelens.polarrecorder.biosleep.ui.NuovaGaraDaCalendario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import androidx.compose.material3.TextButton
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
import com.wboelens.polarrecorder.biosleep.ui.TagSeduta
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
fun CalendarioScreen(
    data: LocalDate?,
    evento: String?,
    bottomBar: @Composable () -> Unit,
    onApriAttivita: (String) -> Unit = {},
    onApriTag: (String) -> Unit = {},
) {
  val context = LocalContext.current.applicationContext
  val dati = rememberDallaCache { repo, oggi -> DatiCalendario.carica(repo, oggi) }
  var dettaglio by remember { mutableStateOf<Dettaglio?>(null) }
  val lista = rememberLazyListState()
  // "+" sui giorni: pause, tag, gara, tempo disponibile
  var modulo by remember { mutableStateOf<ModuloCalendario?>(null) }
  var chiedi by remember { mutableStateOf(false) }
  var messaggio by remember { mutableStateOf<String?>(null) }
  var giroPause by remember { mutableIntStateOf(0) }
  val pause by
      produceState(emptyList<Pausa>(), giroPause) {
        value = withContext(Dispatchers.IO) {
          IntervalsSettings(context).credenziali?.let { Cervello.pause(context, it).pause }.orEmpty()
        }
      }
  val versioneTempo by DisponibilitaDate.versione.collectAsState()
  val tempo by produceState(emptyMap<String, Int>(), versioneTempo) { value = withContext(Dispatchers.IO) { DisponibilitaDate.tutte(context) } }
  // selezione di un intervallo: tieni premuto su un giorno e trascina
  var selDa by remember { mutableStateOf<LocalDate?>(null) }
  var selA by remember { mutableStateOf<LocalDate?>(null) }
  fun giornoA(y: Float): LocalDate? =
      lista.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }?.key?.let {
        runCatching { LocalDate.parse(it as String) }.getOrNull()
      }

  /** Dopo un salvataggio: ripianificare subito se tocca la settimana in corso. */
  fun dopo(dal: LocalDate, al: LocalDate, ripianifica: Boolean = true) {
    modulo = null
    if (ripianifica && PianoCalendario.inSettimana(dal, al)) chiedi = true else messaggio = "Varrà dalla prossima pianificazione"
  }

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
      topBar = { TopAppBar(title = { Text(stringResource(R.string.calendario_calendario)) }, actions = { AzioneAggiorna() }) },
      bottomBar = bottomBar,
  ) { padding ->
    if (dati == null) {
      Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
      return@Scaffold
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(padding).pointerInput(Unit) {
          detectDragGesturesAfterLongPress(
              onDragStart = { o -> giornoA(o.y)?.let { selDa = it; selA = it } },
              onDrag = { c, _ -> giornoA(c.position.y)?.let { selA = it } },
              onDragEnd = {
                val a = selDa
                val b = selA
                if (a != null && b != null) modulo = ModuloCalendario.Menu(minOf(a, b), maxOf(a, b))
                selDa = null
                selA = null
              },
              onDragCancel = { selDa = null; selA = null })
        },
        state = lista,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      item {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          StatoAggiornamento()
          messaggio?.let { Text(tr(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
          RigaForma(dati.forma)
        }
      }
      items(dati.giorni, key = { it.data.toString() }) { g ->
        val a = selDa
        val b = selA
        val selezionato = a != null && b != null && !g.data.isBefore(minOf(a, b)) && !g.data.isAfter(maxOf(a, b))
        Giorno(
            g, dati, onApri = { dettaglio = it },
            pausa = pause.firstOrNull { it.contiene(g.data) },
            tempo = tempo[g.data.toString()],
            selezionato = selezionato,
            onPiu = { modulo = ModuloCalendario.Menu(g.data, g.data) },
            onPausa = { modulo = ModuloCalendario.ModificaPausa(it) })
      }
    }
  }

  dettaglio?.let { det ->
    ModalBottomSheet(onDismissRequest = { dettaglio = null }) { SchedaDettaglio(det, onApriAttivita) }
  }

  when (val m = modulo) {
    is ModuloCalendario.Menu ->
        ModalBottomSheet(onDismissRequest = { modulo = null }) {
          MenuPiu(m.dal, m.al, onScegli = { modulo = it }, onTag = { onApriTag(it.toString()) })
        }
    is ModuloCalendario.NuovaPausa ->
        ModuloPausa(null, m.dal, m.al, onChiudi = { modulo = null }) { r, dal, al ->
          giroPause++
          dopo(dal, al, r.ripianifica)
        }
    is ModuloCalendario.ModificaPausa ->
        ModuloPausa(m.p, m.p.dal, m.p.al, onChiudi = { modulo = null }) { r, dal, al ->
          giroPause++
          if (r.esito == Pause.NON_TROVATA) {
            modulo = null
            messaggio = "La pausa non c'è più su Intervals.icu: calendario aggiornato"
          } else {
            // modifica: conta anche il periodo di prima
            dopo(minOf(dal, m.p.dal), maxOf(al, m.p.al), r.ripianifica)
          }
        }
    is ModuloCalendario.Tempo ->
        ModuloTempo(m.data, tempo[m.data.toString()], onChiudi = { modulo = null }) { dopo(m.data, m.data) }
    is ModuloCalendario.Gara ->
        NuovaGaraDaCalendario(m.data) { r ->
          if (r == null) modulo = null else dopo(m.data, m.data, r.ripianifica)
        }
    null -> Unit
  }
  if (chiedi) ChiediRipianifica { chiedi = false; messaggio = it }
}

@Composable
private fun Giorno(
    g: GiornoCal,
    dati: DatiCalendario,
    onApri: (Dettaglio) -> Unit,
    pausa: Pausa?,
    tempo: Int?,
    selezionato: Boolean,
    onPiu: () -> Unit,
    onPausa: (Pausa) -> Unit,
) {
  val oggi = g.data == dati.oggi
  val fascia = pausa?.let { coloreTipoPausa(it.tipo) }
  val evidenza = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
  Column(
      Modifier.fillMaxWidth()
          .then(if (selezionato) Modifier.background(evidenza) else Modifier)
          // fascia colorata a sinistra sui giorni di pausa
          .drawBehind { if (fascia != null) drawRect(fascia, size = Size(4.dp.toPx(), size.height)) }
          .padding(horizontal = 16.dp, vertical = 4.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (g.data.dayOfWeek == DayOfWeek.MONDAY) {
      val (svolto, pianificato) = dati.settimane[g.data] ?: (0.0 to 0.0)
      HorizontalDivider(Modifier.padding(top = 8.dp))
      Text(
          stringResource(R.string.calendario_settimana_dal_tss_svolto, (DateIt.asse(g.data)).toString(), (svolto.toInt()).toString(), (pianificato.toInt()).toString()),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
          tr((if (oggi) "Oggi · " else "") + DateIt.breve(g.data)),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = if (oggi) FontWeight.Bold else FontWeight.Normal,
          color = if (oggi) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
      // tempo disponibile per questa data: "30'" o "—" (non disponibile)
      PianoCalendario.badge(tempo)?.let {
        Text(
            tr(it),
            Modifier.padding(start = 8.dp).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Box(Modifier.weight(1f))
      // il "+" solo da oggi in poi: il passato non si pianifica
      if (!g.data.isBefore(dati.oggi)) {
        IconButton(onClick = onPiu, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Add, tr("Aggiungi"), tint = MaterialTheme.colorScheme.primary) }
      }
    }
    if (pausa != null && fascia != null && (g.data == pausa.dal || g.data == dati.oggi)) {
      Text(
          tr(Pause.etichetta(pausa.tipo) + (if (pausa.nota.isNotBlank()) " · ${pausa.nota}" else "") +
              " · fino al ${DateIt.breve(pausa.al)}" + (if (!pausa.dallApp) " (da Intervals.icu)" else "")),
          Modifier.clickable { onPausa(pausa) },
          style = MaterialTheme.typography.labelMedium,
          color = fascia)
    }
    for (n in g.note) Text(tr(n.nome), style = MaterialTheme.typography.bodySmall)
    for (s in g.pianificate) CardSeduta(s, onClick = { onApri(Dettaglio.Pianificata(s)) })
    for (a in g.nonPianificate) CardAttivita(a, onClick = { onApri(Dettaglio.Libera(a)) })
  }
}

@Composable
private fun SchedaDettaglio(det: Dettaglio, onApriAttivita: (String) -> Unit) {
  Column(
      Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    when (det) {
      is Dettaglio.Pianificata -> {
        val e = det.s.evento
        Text(tr(e.nome), style = MaterialTheme.typography.titleLarge)
        Text(tr("${DateIt.lunga(e.data)} · ${e.sport.etichetta}"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Blocco("Pianificato", e.durataS, e.tss, e.distanzaM, null)
        val segmenti = remember(e.id) { Struttura.segmenti(e.passi) }
        GraficoSeduta(segmenti, ColoriBio.sport(e.sport), Modifier.fillMaxWidth().height(80.dp))
        val righe = remember(e.id) { Struttura.righe(e.passi) }
        for (r in righe) {
          Text(
              tr(r.testo),
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = if (r.livello == 0 && r.testo.endsWith("x")) FontWeight.Bold else FontWeight.Normal,
              modifier = Modifier.padding(start = (16 * r.livello).dp))
        }
        if (righe.isEmpty() && !e.descrizione.isNullOrBlank()) Text(tr(e.descrizione), style = MaterialTheme.typography.bodyMedium)
        if (e.specchio) {
          // Seduta su Garmin Connect (multisport): le parti, la guida strutturata e' sull'orologio
          for (p in e.parti) Text(tr("${p.sport.etichetta} · ${Formato.durata(p.minuti * 60)}"), style = MaterialTheme.typography.bodyMedium)
          Text(
              stringResource(R.string.calendario_seduta_su_garmin_connect),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val consuntivo = det.s.consuntivo
        when {
          consuntivo != null -> {
            Blocco("Svolto", consuntivo.durataS, consuntivo.tss, consuntivo.distanzaM, consuntivo.compliance)
            // analisi della seduta svolta (per il multisport: una per parte)
            for (a in det.s.svolte) {
              TextButton(onClick = { onApriAttivita(a.id) }) {
                Text(tr("Analisi della seduta" + if (det.s.svolte.size > 1) " · ${a.sport.etichetta}" else ""))
              }
            }
            // tag per il coach: per il multisport sulla prima parte svolta
            det.s.svolte.firstOrNull()?.let { a -> TagSeduta(a.id, a.data.toString()) }
          }
          det.s.esito == Esito.NON_SVOLTA ->
              Text(stringResource(R.string.calendario_non_svolta), color = ColoriBio.rosso, fontWeight = FontWeight.Bold)
          else -> {}
        }
      }
      is Dettaglio.Libera -> {
        val a = det.a
        Text(tr(a.nome), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.calendario_non_pianificata, (DateIt.lunga(a.data)).toString(), tr(a.sport.etichetta)), color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { onApriAttivita(a.id) }) { Text(stringResource(R.string.calendario_analisi_della_seduta)) }
        TagSeduta(a.id, a.data.toString())
        Blocco("Svolto", a.durataS, a.tss, a.distanzaM, null)
      }
    }
  }
}

@Composable
private fun Blocco(titolo: String, durataS: Int?, tss: Int?, distanzaM: Double?, compliance: Double?) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(tr(titolo), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Cifra(stringResource(R.string.calendario_durata), Formato.durata(durataS))
      Cifra(stringResource(R.string.calendario_tss), tss?.toString() ?: "—")
      Cifra(stringResource(R.string.calendario_distanza), Formato.distanza(distanzaM))
      if (compliance != null && compliance > 0) Cifra(stringResource(R.string.calendario_aderenza), "${compliance.toInt()}%", ColoriBio.aderenza(Allenamenti.aderenza(compliance)))
    }
  }
}

@Composable
private fun Cifra(etichetta: String, valore: String, colore: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(tr(valore), style = MaterialTheme.typography.titleMedium, color = colore)
    Text(tr(etichetta), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}
