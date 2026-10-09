package com.wboelens.polarrecorder.biosleep.ui.allenamento

import com.wboelens.polarrecorder.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.cache.DettagliRepo
import com.wboelens.polarrecorder.biosleep.cache.EsitoDettaglio
import com.wboelens.polarrecorder.biosleep.training.DettaglioAttivita
import com.wboelens.polarrecorder.biosleep.training.Flussi
import com.wboelens.polarrecorder.biosleep.training.Formato
import com.wboelens.polarrecorder.biosleep.training.Ritmo
import com.wboelens.polarrecorder.biosleep.training.Sport
import com.wboelens.polarrecorder.biosleep.training.TempoZona
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Colori delle zone come Intervals.icu: dal grigio-blu (Z1) al viola (Z7). */
private val COLORI_ZONE =
    listOf(
        Color(0xFF9E9E9E), Color(0xFF42A5F5), Color(0xFF66BB6A), Color(0xFFFFCA28),
        Color(0xFFFF7043), Color(0xFFE53935), Color(0xFF8E24AA))

private fun coloreZona(i: Int) = COLORI_ZONE[i.coerceIn(0, COLORI_ZONE.lastIndex)]

private fun tempo(s: Int): String = if (s >= 3600) "${s / 3600}:${"%02d".format(s % 3600 / 60)}:${"%02d".format(s % 60)}" else "${s / 60}:${"%02d".format(s % 60)}"

/** Analisi di una seduta svolta: numeri, grafici nel tempo, zone e intervalli. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttivitaScreen(id: String, onBack: () -> Unit) {
  val context = LocalContext.current.applicationContext
  val esito by produceState<EsitoDettaglio?>(null, id) { value = withContext(Dispatchers.IO) { DettagliRepo.carica(context, id) } }
  val e = esito
  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text((e as? EsitoDettaglio.Pronto)?.attivita?.nome ?: "Seduta", maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } })
      },
  ) { padding ->
    when (e) {
      null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
      is EsitoDettaglio.Errore ->
          Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.attivita_seduta_non_disponibile, e.messaggio.toString()), color = MaterialTheme.colorScheme.error)
          }
      is EsitoDettaglio.Pronto ->
          Column(
              Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            Intestazione(e.attivita)
            Sezione("Riepilogo") { Numeri(e.attivita) }
            e.flussi?.takeIf { it.serie.isNotEmpty() }?.let { f -> Sezione("Andamento") { Grafici(e.attivita, f) } }
            if (e.attivita.zonePotenza.isNotEmpty()) Sezione("Zone di potenza") { Zone(e.attivita.zonePotenza) }
            if (e.attivita.zoneFc.any { it.secondi > 0 }) Sezione("Zone di frequenza cardiaca") { Zone(e.attivita.zoneFc) }
            if (e.attivita.zonePasso.any { it.secondi > 0 }) Sezione("Zone di passo") { Zone(e.attivita.zonePasso) }
            if (e.attivita.intervalli.size > 1) Sezione("Intervalli") { Intervalli(e.attivita) }
          }
    }
  }
}

@Composable
private fun Intestazione(a: DettaglioAttivita) {
  val quando = a.inizio?.format(DateTimeFormatter.ofPattern("EEEE d MMMM · HH:mm", Locale.ITALIAN))?.replaceFirstChar { it.uppercase() }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(listOfNotNull(quando, a.sport.etichetta).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
    a.dispositivo?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
  }
}

// --- Numeri --------------------------------------------------------------------------------------

@Composable
private fun Numeri(a: DettaglioAttivita) {
  val voci =
      listOfNotNull(
          a.durataS?.let { "Durata" to tempo(it) },
          a.distanzaM?.let { "Distanza" to Formato.distanza(it) },
          a.tss?.let { "Carico (TSS)" to "$it" },
          a.intensitaPct?.let { "Intensità" to "${it.toInt()}%" },
          a.velocitaMedia?.let { (if (a.sport == Sport.BICI) "Velocità" else "Passo") to Ritmo.perSport(it, a.sport) },
          a.wattNormalizzati?.let { "Potenza norm." to "$it W" },
          a.wattMedi?.let { "Potenza media" to "$it W" },
          a.fcMedia?.let { "FC media" to "$it bpm" },
          a.fcMax?.let { "FC max" to "$it bpm" },
          a.cadenza?.let { "Cadenza" to "$it" + if (a.sport == Sport.CORSA) " ppm" else " rpm" },
          a.dislivello?.let { "Dislivello" to "${it.toInt()} m" },
          a.calorie?.let { "Calorie" to "$it kcal" },
          a.disaccoppiamento?.let { "Disaccoppiamento" to Formato.conSegno(it) + "%" },
          a.efficienza?.let { "Fattore di efficienza" to Formato.decimale(it, 2) },
          a.rpe?.let { "Fatica percepita" to "$it/10" },
          a.aderenza?.let { "Aderenza al piano" to "${it.toInt()}%" },
          a.vascaM?.let { "Vasca" to "${it.toInt()} m" },
      )
  for (riga in voci.chunked(3)) {
    Row(Modifier.fillMaxWidth()) {
      for ((titolo, valore) in riga) {
        Column(Modifier.weight(1f)) {
          Text(valore, style = MaterialTheme.typography.titleMedium)
          Text(titolo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
      repeat(3 - riga.size) { Box(Modifier.weight(1f)) }
    }
  }
}

// --- Grafici nel tempo ---------------------------------------------------------------------------

private data class Traccia(
    val tipo: String,
    val titolo: String,
    val breve: String,
    val colore: Color,
    /** Con l'unita', per il valore sotto il cursore e la riga di riepilogo. */
    val formato: (Double) -> String,
    /** Solo il numero, per la scala a sinistra: l'unita' e' gia' nel titolo del grafico. */
    val asse: (Double) -> String = formato,
)

@Composable
private fun Grafici(a: DettaglioAttivita, f: Flussi) {
  val nuoto = a.sport == Sport.NUOTO
  val tracce =
      listOf(
          Traccia("heartrate", "Frequenza cardiaca (bpm)", "FC", Color(0xFFE53935), { "${it.toInt()} bpm" }, { "${it.toInt()}" }),
          Traccia("watts", "Potenza (W)", "Pot.", Color(0xFF8E24AA), { "${it.toInt()} W" }, { "${it.toInt()}" }),
          Traccia(
              "velocity_smooth",
              if (a.sport == Sport.BICI || a.sport == Sport.ALTRO) "Velocità (km/h)" else if (nuoto) "Passo (/100m)" else "Passo (/km)",
              if (a.sport == Sport.BICI || a.sport == Sport.ALTRO) "Vel." else "Passo",
              Color(0xFF1E88E5),
              formato = { v ->
                if (a.sport == Sport.BICI || a.sport == Sport.ALTRO) Formato.decimale(v * 3.6) else Ritmo.passo(v, nuoto, conUnita = false)
              }),
          Traccia("cadence", "Cadenza", "Cad.", Color(0xFF00897B), formato = { "${it.toInt()}" }),
          Traccia("altitude", "Altitudine (m)", "Alt.", Color(0xFF6D4C41), { "${it.toInt()} m" }, { "${it.toInt()}" }),
      )
  val lavoro = a.intervalli.filter { it.lavoro }.map { it.inizioS to it.fineS }
  val presenti = tracce.filter { f.serie[it.tipo] != null }
  // Cursore condiviso da tutti i grafici, come su Intervals.icu: tocca o trascina su un grafico
  var cursore by remember(f) { mutableStateOf<Int?>(null) }
  val i = cursore
  Text(
      if (i == null) "Tocca o trascina un grafico per leggere i valori in quel punto"
      else "${tempo(f.tempoS[i])} · " +
          presenti.mapNotNull { t -> f.serie[t.tipo]?.getOrNull(i)?.takeIf { it > 0 }?.let { "${t.breve} ${t.formato(it)}" } }
              .joinToString(" · "),
      style = MaterialTheme.typography.bodySmall,
      fontWeight = if (i == null) FontWeight.Normal else FontWeight.SemiBold,
      color = if (i == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
  for (t in presenti) {
    val valori = f.serie[t.tipo] ?: continue
    Text(t.titolo, style = MaterialTheme.typography.labelLarge)
    GraficoTempo(f.tempoS, valori, t.colore, t.formato, t.asse, lavoro, cursore) { cursore = it }
  }
  if (lavoro.isNotEmpty()) {
    Text(
        stringResource(R.string.attivita_le_fasce_chiare_sono),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/** Una serie nel tempo, con le fasce degli intervalli di lavoro e tre etichette per asse. */
@Composable
private fun GraficoTempo(
    tempi: List<Int>,
    valori: List<Double?>,
    colore: Color,
    formato: (Double) -> String,
    formatoAsse: (Double) -> String,
    lavoro: List<Pair<Int, Int>>,
    cursore: Int?,
    onCursore: (Int) -> Unit,
) {
  val validi = valori.filterNotNull().filter { it > 0 }
  if (validi.isEmpty() || tempi.size < 2) return
  // il passo e' "piu' alto = piu' lento": si disegna la velocita', le etichette mostrano il passo
  val lo = validi.min()
  val hi = validi.max().takeIf { it > lo } ?: (lo + 1)
  val misuratore = rememberTextMeasurer()
  val stile = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
  val fascia = colore.copy(alpha = 0.10f)
  val griglia = MaterialTheme.colorScheme.outlineVariant
  val fine = tempi.last().coerceAtLeast(1)
  val lineaCursore = MaterialTheme.colorScheme.onSurface
  val densita = LocalDensity.current
  // Scala a sinistra: tre etichette (solo numeri); il grafico parte dopo la piu' larga
  val livelli = listOf(lo, (lo + hi) / 2, hi)
  val etichette = livelli.map { misuratore.measure(formatoAsse(it), stile) }
  val sinistraPx = etichette.maxOf { it.size.width } + with(densita) { 8.dp.toPx() }
  // dal punto toccato all'indice del campione piu' vicino nel tempo
  fun indice(x: Float, larghezza: Int): Int {
    val s = ((x - sinistraPx) / (larghezza - sinistraPx) * fine).toInt().coerceIn(0, fine)
    val k = tempi.binarySearch(s)
    return (if (k >= 0) k else (-k - 1)).coerceIn(0, tempi.lastIndex)
  }
  Canvas(
      Modifier.fillMaxWidth()
          .height(120.dp)
          .pointerInput(tempi) { detectTapGestures { p -> onCursore(indice(p.x, size.width)) } }
          .pointerInput(tempi) {
            detectHorizontalDragGestures(
                onDragStart = { p -> onCursore(indice(p.x, size.width)) },
                onHorizontalDrag = { change, _ ->
                  change.consume()
                  onCursore(indice(change.position.x, size.width))
                })
          }) {
    val sinistra = sinistraPx
    val sotto = 16.dp.toPx()
    val w = size.width - sinistra
    val h = size.height - sotto
    fun x(s: Int) = sinistra + w * s / fine
    fun y(v: Double) = (h * (1 - (v - lo) / (hi - lo))).toFloat()
    for ((da, a) in lavoro) drawRect(fascia, Offset(x(da), 0f), Size(x(a) - x(da), h))
    livelli.forEachIndexed { k, v ->
      drawLine(griglia, Offset(sinistra, y(v)), Offset(size.width, y(v)), strokeWidth = 1f)
      val t = etichette[k]
      // allineate a destra, appena prima dell'inizio del grafico
      drawText(t, topLeft = Offset(sinistra - t.size.width - 4.dp.toPx(), (y(v) - t.size.height / 2f).coerceIn(0f, h - t.size.height)))
    }
    for (s in listOf(0, fine / 2, fine)) {
      val t = misuratore.measure(tempo(s), stile)
      drawText(t, topLeft = Offset((x(s) - t.size.width / 2f).coerceIn(sinistra, size.width - t.size.width), h + 2.dp.toPx()))
    }
    val p = Path()
    var aperta = false
    valori.forEachIndexed { i, v ->
      if (v == null || v <= 0 || i >= tempi.size) {
        aperta = false
      } else if (!aperta) {
        p.moveTo(x(tempi[i]), y(v))
        aperta = true
      } else {
        p.lineTo(x(tempi[i]), y(v))
      }
    }
    drawPath(p, colore, style = Stroke(1.5.dp.toPx()))
    // linea verticale e punto sul valore, nello stesso istante su tutti i grafici
    cursore?.takeIf { it in tempi.indices }?.let { k ->
      val xc = x(tempi[k])
      drawLine(lineaCursore, Offset(xc, 0f), Offset(xc, h), strokeWidth = 1.dp.toPx())
      valori.getOrNull(k)?.takeIf { it > 0 }?.let { v ->
        drawCircle(colore, 4.dp.toPx(), Offset(xc, y(v)))
        val t = misuratore.measure(formato(v), stile.copy(color = lineaCursore, fontWeight = FontWeight.Bold))
        val xt = if (xc + 6.dp.toPx() + t.size.width < size.width) xc + 6.dp.toPx() else xc - 6.dp.toPx() - t.size.width
        drawText(t, topLeft = Offset(xt, (y(v) - t.size.height - 2.dp.toPx()).coerceAtLeast(0f)))
      }
    }
  }
}

// --- Zone e intervalli ---------------------------------------------------------------------------

/** Una barra per zona, lunga quanto il tempo passato in quella zona, come Intervals.icu. */
@Composable
private fun Zone(zone: List<TempoZona>) {
  val totale = zone.sumOf { it.secondi }.coerceAtLeast(1)
  zone.forEachIndexed { i, z ->
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(z.nome, Modifier.width(28.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
      Box(Modifier.weight(1f).height(14.dp)) {
        Box(
            Modifier.fillMaxWidth(z.secondi.toFloat() / totale).height(14.dp)
                .background(coloreZona(i), RoundedCornerShape(3.dp)))
      }
      Text(
          " ${tempo(z.secondi)} · ${z.secondi * 100 / totale}%",
          Modifier.width(92.dp),
          style = MaterialTheme.typography.labelSmall)
    }
    z.intervallo?.let {
      Text(it, Modifier.padding(start = 28.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun Intervalli(a: DettaglioAttivita) {
  val nuoto = a.sport == Sport.NUOTO
  a.intervalli.forEachIndexed { i, v ->
    val dati =
        listOfNotNull(
            tempo(v.durataS),
            v.distanzaM?.let { Formato.distanza(it) },
            v.watt?.let { "$it W" },
            v.fcMedia?.let { "$it bpm" + (v.fcMax?.let { m -> " (max $m)" } ?: "") },
            v.velocita?.takeIf { it > 0 }?.let { if (a.sport == Sport.BICI) Ritmo.kmh(it) else Ritmo.passo(it, nuoto) },
        )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.width(6.dp).height(32.dp).background(coloreZona((v.zona ?: 1) - 1), RoundedCornerShape(2.dp)))
      Column(Modifier.padding(start = 10.dp).weight(1f)) {
        Text(
            "${i + 1}. " + (if (v.lavoro) "Lavoro" else "Recupero") + (v.zona?.let { " · Z$it" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (v.lavoro) FontWeight.SemiBold else FontWeight.Normal)
        Text(dati.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}
