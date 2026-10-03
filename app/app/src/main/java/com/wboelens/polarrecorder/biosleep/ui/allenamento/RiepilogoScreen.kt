package com.wboelens.polarrecorder.biosleep.ui.allenamento

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.wboelens.polarrecorder.biosleep.riepilogo.BiometriaCoach
import com.wboelens.polarrecorder.biosleep.riepilogo.FormaCoach
import com.wboelens.polarrecorder.biosleep.riepilogo.Riepilogo
import com.wboelens.polarrecorder.biosleep.riepilogo.RiepilogoDb
import com.wboelens.polarrecorder.biosleep.riepilogo.RiepilogoLink
import com.wboelens.polarrecorder.biosleep.training.Formato
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// --- Dati ----------------------------------------------------------------------------------------

/** Il riepilogo di una data e lo stato dell'attesa, riletti a ogni cambio del database. */
data class StatoRiepilogo(val riepilogo: Riepilogo?, val scaduto: Boolean)

@Composable
fun rememberRiepilogo(data: String): StatoRiepilogo? {
  val context = LocalContext.current.applicationContext
  val versione by RiepilogoDb.versione.collectAsState()
  val stato by
      produceState<StatoRiepilogo?>(null, data, versione) {
        value =
            withContext(Dispatchers.IO) {
              val db = RiepilogoDb.get(context)
              StatoRiepilogo(db.leggi(data), db.scaduta(data))
            }
      }
  return stato
}

/** Da chiamare subito prima di NavHost: apre il riepilogo quando si tocca la notifica. */
@Composable
fun GestisciLinkRiepilogo(navController: NavController) {
  val richiesta by RiepilogoLink.richiesta.collectAsState()
  LaunchedEffect(richiesta) {
    val data = richiesta ?: return@LaunchedEffect
    RiepilogoLink.richiesta.value = null
    navController.navigate("riepilogo/$data") { launchSingleTop = true }
  }
}

// --- Riquadro nella schermata Oggi ---------------------------------------------------------------

/** Decisione del coach di oggi (tocco = riepilogo), oppure una riga discreta se non e' arrivato. */
@Composable
fun RiquadroRiepilogo(oggi: LocalDate, onApri: (String) -> Unit) {
  val s = rememberRiepilogo(oggi.toString()) ?: return
  val r = s.riepilogo
  when {
    r != null ->
        Card(Modifier.fillMaxWidth().clickable { onApri(r.data) }) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Coach · ${r.ora ?: ""}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(r.decisione.etichetta ?: "Riepilogo del coach", style = MaterialTheme.typography.titleLarge)
            r.decisione.motivo?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3) }
            if (r.avvisi != null || r.nonScritte.isNotEmpty()) {
              Text("Ci sono avvisi: apri il riepilogo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
          }
        }
    s.scaduto ->
        Text(
            "Riepilogo del coach non ancora disponibile",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

// --- Schermata -----------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RiepilogoScreen(data: String, onBack: () -> Unit, onApriSeduta: (String) -> Unit) {
  val stato = rememberRiepilogo(data)
  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text("Riepilogo del ${DateIt.breve(LocalDate.parse(data))}") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
        )
      },
  ) { padding ->
    val r = stato?.riepilogo
    if (r == null) {
      Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        if (stato == null) CircularProgressIndicator()
        else Text("Riepilogo non disponibile per questa data", color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      return@Scaffold
    }
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Avvisi(r)
      Intestazione(r)
      r.oggi?.let { testo ->
        Sezione("Oggi") {
          Text(testo, style = MaterialTheme.typography.bodyMedium)
          TextButton(onClick = { onApriSeduta(r.data) }) { Text("Apri la seduta nel calendario") }
        }
      }
      r.biometria?.let { Sezione("Biometria") { Biometria(it) } }
      r.forma?.let { Sezione("Forma") { Forma(it) } }
      if (r.discipline.isNotEmpty()) Sezione("Discipline") { Discipline(r) }
      r.intensita?.takeIf { it.pctFacile != null || it.pctIntenso != null }?.let { i ->
        Sezione("Intensità") { Intensita(i.pctFacile, i.pctIntenso) }
      }
      if (r.volume != null || r.carico != null) Sezione("Settimana") { Settimana(r) }
      r.testo?.let { t ->
        Sezione("Messaggio completo") { SelectionContainer { Text(t, style = MaterialTheme.typography.bodySmall) } }
      }
    }
  }
}

@Composable
private fun Avvisi(r: Riepilogo) {
  if (r.avvisi == null && r.nonScritte.isEmpty()) return
  Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      val colore = MaterialTheme.colorScheme.onErrorContainer
      Text("Avvisi", style = MaterialTheme.typography.titleSmall, color = colore, fontWeight = FontWeight.Bold)
      r.avvisi?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colore) }
      if (r.nonScritte.isNotEmpty()) {
        Text("Sedute che il coach non è riuscito a scrivere a calendario:", style = MaterialTheme.typography.bodyMedium, color = colore)
        for (s in r.nonScritte) Text("• $s", style = MaterialTheme.typography.bodyMedium, color = colore)
      }
    }
  }
}

@Composable
private fun Intestazione(r: Riepilogo) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(r.decisione.etichetta ?: "Riepilogo del coach", style = MaterialTheme.typography.headlineMedium)
    r.decisione.motivo?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
    val sotto = listOfNotNull(r.fase, r.gara?.let { "gara: $it" }, r.ora?.let { "ore $it" })
    if (sotto.isNotEmpty()) {
      Text(sotto.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

// --- Biometria -----------------------------------------------------------------------------------

@Composable
private fun Biometria(b: BiometriaCoach) {
  val nota = MaterialTheme.typography.bodySmall
  val grigio = MaterialTheme.colorScheme.onSurfaceVariant
  if (b.ok == false) {
    Text("Baseline in calibrazione", fontWeight = FontWeight.Bold)
    b.nota?.let { Text(it, style = nota, color = grigio) }
    return
  }
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(14.dp).background(ColoriBio.daNome(b.banda), CircleShape))
    Spacer(Modifier.width(8.dp))
    Text(
        listOfNotNull(b.banda?.replaceFirstChar { it.uppercase() }, b.azione).joinToString(" — "),
        fontWeight = FontWeight.Bold)
  }
  b.nota?.let { Text(it, style = nota, color = grigio) }
  if (b.hrv7gg != null) {
    Text(
        "HRV 7 gg ${Formato.decimale(b.hrv7gg)} ms" +
            (b.hrvBaseline?.let { " · baseline ${Formato.decimale(it)}" } ?: "") +
            (b.pctVsBaseline?.let { " (${Formato.conSegno(it)}%)" } ?: ""),
        style = MaterialTheme.typography.bodyMedium)
    b.rangeMs?.let { BarraRange(b.hrv7gg, it, ColoriBio.daNome(b.banda)) }
  }
  val andamento =
      listOfNotNull(
          b.direzione?.let { "andamento $it" },
          b.ggSottoRange?.takeIf { it > 0 }?.let { "sotto il range da $it gg" },
          b.ggRitardo?.takeIf { it > 0 }?.let { "ultima notte $it gg fa" })
  if (andamento.isNotEmpty()) Text(andamento.joinToString(" · "), style = nota, color = grigio)
  if (b.fc7gg != null) {
    Text(
        "FC a riposo 7 gg ${Formato.decimale(b.fc7gg)} bpm" +
            (b.fcBaseline?.let { " · baseline ${Formato.decimale(it)}" } ?: "") +
            (b.fcDelta?.let { " (${Formato.conSegno(it)})" } ?: ""),
        style = MaterialTheme.typography.bodyMedium,
        color = if (b.fcAllarme == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
  }
}

/** Barra orizzontale: il range normale evidenziato e un marcatore sul valore. */
@Composable
private fun BarraRange(valore: Double, range: Pair<Double, Double>, colore: Color) {
  val (lo, hi) = range
  val ampiezza = max(hi - lo, 1.0)
  val inizio = min(lo - ampiezza, valore - ampiezza * 0.2)
  val fine = max(hi + ampiezza, valore + ampiezza * 0.2)
  val fondo = MaterialTheme.colorScheme.surfaceVariant
  val fascia = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
  val segno = MaterialTheme.colorScheme.onSurface
  Column {
    Canvas(Modifier.fillMaxWidth().height(22.dp)) {
      fun x(v: Double) = (size.width * ((v - inizio) / (fine - inizio))).toFloat()
      val y = size.height / 2
      val h = 10.dp.toPx()
      drawRect(fondo, Offset(0f, y - h / 2), Size(size.width, h))
      drawRect(fascia, Offset(x(lo), y - h / 2), Size(x(hi) - x(lo), h))
      drawLine(segno, Offset(x(valore), 0f), Offset(x(valore), size.height), strokeWidth = 3.dp.toPx())
      drawCircle(colore, 5.dp.toPx(), Offset(x(valore), y))
    }
    Text(
        "range ${Formato.decimale(lo)}–${Formato.decimale(hi)} ms",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

// --- Forma ---------------------------------------------------------------------------------------

@Composable
private fun Forma(f: FormaCoach) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    f.ctl?.let { Cifra("CTL", Formato.decimale(it), ColoriBio.ctl) }
    f.atl?.let { Cifra("ATL", Formato.decimale(it), ColoriBio.atl) }
    f.tsb?.let { Cifra("TSB", Formato.conSegno(it) + (f.formPct?.let { p -> " (${Formato.conSegno(p)}%)" } ?: ""), ColoriBio.tsb) }
  }
  val zona = listOfNotNull(f.zona?.let { "zona $it" }, f.zonaAttesa?.let { "attesa in questa fase: $it" })
  if (zona.isNotEmpty()) Text(zona.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
  if (f.tsb != null && (f.fascia != null || f.tsbDomenica != null)) GraficoTsb(f)
  f.tsbObiettivo?.let {
    Text("Obiettivo a domenica: TSB ${Formato.conSegno(it)}" + (f.fascia?.let { (a, b) -> " (fascia ${Formato.conSegno(a)} / ${Formato.conSegno(b)})" } ?: ""),
        style = MaterialTheme.typography.bodySmall)
  }
  if (f.ramp != null) {
    Text(
        "Rampa CTL ${Formato.conSegno(f.ramp)}/sett" + (f.rampMax?.let { " (max ${Formato.decimale(it)})" } ?: ""),
        style = MaterialTheme.typography.bodyMedium)
    f.rampMax?.takeIf { it > 0 }?.let { BarraAvanzamento(f.ramp, it, null) }
  }
}

/** TSB oggi -> domenica, con la fascia obiettivo colorata e la linea dello zero. */
@Composable
private fun GraficoTsb(f: FormaCoach) {
  val tsb = f.tsb ?: return
  val valori = listOfNotNull(tsb, f.tsbDomenica, f.fascia?.first, f.fascia?.second, 0.0)
  val lo = valori.min() - 3
  val hi = valori.max() + 3
  val fascia = ColoriBio.verde.copy(alpha = 0.2f)
  val linea = ColoriBio.tsb
  val zero = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
  Column {
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
      fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
      val x0 = 12.dp.toPx()
      val x1 = size.width - 12.dp.toPx()
      f.fascia?.let { (a, b) -> drawRect(fascia, Offset(0f, y(b)), Size(size.width, y(a) - y(b))) }
      drawLine(zero, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 1.dp.toPx())
      f.tsbDomenica?.let { d ->
        drawLine(linea, Offset(x0, y(tsb)), Offset(x1, y(d)), strokeWidth = 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
        drawCircle(linea, 5.dp.toPx(), Offset(x1, y(d)), style = Stroke(2.dp.toPx()))
      }
      drawCircle(linea, 5.dp.toPx(), Offset(x0, y(tsb)))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text("oggi ${Formato.conSegno(tsb)}", style = MaterialTheme.typography.labelSmall)
      f.tsbDomenica?.let { Text("domenica ${Formato.conSegno(it)}", style = MaterialTheme.typography.labelSmall) }
    }
  }
}

// --- Discipline e intensita' --------------------------------------------------------------------

private val SPORT = listOf("nuoto" to "Nuoto", "bici" to "Bici", "corsa" to "Corsa")

@Composable
private fun Discipline(r: Riepilogo) {
  val sett = r.discipline["7gg"].orEmpty()
  val mese = r.discipline["28gg"].orEmpty()
  for ((chiave, nome) in SPORT) {
    val a = sett[chiave]
    val b = mese[chiave]
    if (a?.pct == null && b?.pct == null) continue
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(nome, style = MaterialTheme.typography.bodyMedium)
      a?.pct?.let { RigaQuota("7 gg", it, a.targetPct, a.ore) }
      b?.pct?.let { RigaQuota("28 gg", it, b.targetPct, b.ore) }
    }
  }
  val p7 = sett["palestra"]?.ore
  val p28 = mese["palestra"]?.ore
  if (p7 != null || p28 != null) {
    Text(
        "Palestra: " + listOfNotNull(p7?.let { "7 gg ${ore(it)}" }, p28?.let { "28 gg ${ore(it)}" }).joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium)
  }
}

private fun ore(h: Double) = Formato.durata((h * 3600).toInt()).let { if (it == "—") "0'" else it }

@Composable
private fun RigaQuota(etichetta: String, pct: Double, target: Double?, ore: Double?) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(etichetta, Modifier.width(44.dp), style = MaterialTheme.typography.labelSmall)
    Box(Modifier.weight(1f)) { BarraAvanzamento(pct, 100.0, target) }
    Text(
        " ${pct.toInt()}%" + (ore?.let { " · ${ore(it)}" } ?: ""),
        Modifier.width(90.dp),
        style = MaterialTheme.typography.labelSmall)
  }
}

/** Anello facile/intenso con il riferimento 80/20 segnato. */
@Composable
private fun Intensita(facile: Double?, intenso: Double?) {
  val f = facile ?: intenso?.let { 100 - it } ?: return
  val i = intenso ?: (100 - f)
  val verde = ColoriBio.verde
  val rosso = ColoriBio.rosso
  val rif = MaterialTheme.colorScheme.onSurface
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Canvas(Modifier.size(96.dp)) {
      val spessore = 14.dp.toPx()
      val lato = size.minDimension - spessore
      val tl = Offset(spessore / 2, spessore / 2)
      val totale = (f + i).takeIf { it > 0 } ?: 100.0
      val angoloF = (360 * f / totale).toFloat()
      drawArc(verde, -90f, angoloF, false, tl, Size(lato, lato), style = Stroke(spessore))
      drawArc(rosso, -90f + angoloF, 360f - angoloF, false, tl, Size(lato, lato), style = Stroke(spessore))
      // riferimento 80%: tacca sul bordo
      val a = Math.toRadians(-90.0 + 360 * 0.8)
      val c = Offset(size.width / 2, size.height / 2)
      val r1 = lato / 2 - spessore
      val r2 = lato / 2 + spessore
      drawLine(rif, Offset(c.x + (r1 * Math.cos(a)).toFloat(), c.y + (r1 * Math.sin(a)).toFloat()),
          Offset(c.x + (r2 * Math.cos(a)).toFloat(), c.y + (r2 * Math.sin(a)).toFloat()), strokeWidth = 2.dp.toPx())
    }
    Column {
      facile?.let { Text("Facile ${Formato.decimale(it, 0)}%", color = verde, fontWeight = FontWeight.Bold) }
      intenso?.let { Text("Intenso ${Formato.decimale(it, 0)}%", color = rosso, fontWeight = FontWeight.Bold) }
      Text("riferimento 80/20", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

// --- Settimana -----------------------------------------------------------------------------------

@Composable
private fun Settimana(r: Riepilogo) {
  r.volume?.let { v ->
    val fatte = v.fatteH
    val target = v.targetH
    if (fatte != null && target != null) {
      Text(
          "Ore: ${ore(fatte)} di ${ore(target)}" + (v.tettoH?.let { " · tetto ${ore(it)}" } ?: "") +
              (v.restanoH?.let { r2 -> " · restano ${ore(r2)}" + (v.giorni?.let { " in $it gg" } ?: "") } ?: ""),
          style = MaterialTheme.typography.bodyMedium)
      BarraAvanzamento(fatte, max(v.tettoH ?: target, target), target, tetto = v.tettoH)
    }
  }
  r.carico?.let { c ->
    val fatti = c.fatti
    val tetto = c.tetto
    if (fatti != null && tetto != null) {
      Text(
          "TSS: ${fatti.toInt()} di ${tetto.toInt()}" + (c.calendario?.let { " · a calendario ${it.toInt()}" } ?: ""),
          style = MaterialTheme.typography.bodyMedium)
      BarraAvanzamento(fatti, max(tetto, c.calendario ?: 0.0), c.sostenibile, previsione = c.calendario, tetto = tetto)
    }
  }
}

/**
 * Barra di avanzamento: pieno = fatto, chiaro = previsione, linea = obiettivo, linea rossa = tetto.
 * Il valore pieno non supera la barra; la scala parte da zero.
 */
@Composable
fun BarraAvanzamento(valore: Double, massimo: Double, obiettivo: Double?, previsione: Double? = null, tetto: Double? = null) {
  val fondo = MaterialTheme.colorScheme.surfaceVariant
  val pieno = MaterialTheme.colorScheme.primary
  val chiaro = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
  val linea = MaterialTheme.colorScheme.onSurface
  val scala = max(massimo, max(valore, previsione ?: 0.0)).takeIf { it > 0 } ?: 1.0
  Canvas(Modifier.fillMaxWidth().height(14.dp)) {
    fun x(v: Double) = (size.width * (v / scala).coerceIn(0.0, 1.0)).toFloat()
    val h = 8.dp.toPx()
    val y = (size.height - h) / 2
    drawRoundRect(fondo, Offset(0f, y), Size(size.width, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
    previsione?.let { drawRoundRect(chiaro, Offset(0f, y), Size(x(it), h), androidx.compose.ui.geometry.CornerRadius(h / 2)) }
    drawRoundRect(pieno, Offset(0f, y), Size(x(valore), h), androidx.compose.ui.geometry.CornerRadius(h / 2))
    obiettivo?.let { drawLine(linea, Offset(x(it), 0f), Offset(x(it), size.height), strokeWidth = 2.dp.toPx()) }
    tetto?.let { drawLine(ColoriBio.rosso, Offset(x(it), 0f), Offset(x(it), size.height), strokeWidth = 2.dp.toPx()) }
  }
}

@Composable
private fun Cifra(etichetta: String, valore: String, colore: Color) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(etichetta, style = MaterialTheme.typography.labelMedium, color = colore, fontWeight = FontWeight.Bold)
    Text(valore, style = MaterialTheme.typography.titleLarge)
  }
}
