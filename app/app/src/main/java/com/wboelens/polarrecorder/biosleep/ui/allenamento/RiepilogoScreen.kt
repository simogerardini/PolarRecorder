package com.wboelens.polarrecorder.biosleep.ui.allenamento

import com.wboelens.polarrecorder.biosleep.riepilogo.TraduzioneMessaggi
import androidx.compose.ui.text.style.TextAlign
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.WbSunny
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.wboelens.polarrecorder.biosleep.cervello.AvvisiSoglie
import com.wboelens.polarrecorder.biosleep.readiness.FormaCalc
import com.wboelens.polarrecorder.biosleep.riepilogo.AltaIntensita
import com.wboelens.polarrecorder.biosleep.riepilogo.BiometriaCoach
import com.wboelens.polarrecorder.biosleep.riepilogo.Carico
import com.wboelens.polarrecorder.biosleep.riepilogo.FormaCoach
import com.wboelens.polarrecorder.biosleep.riepilogo.QuotaDisciplina
import com.wboelens.polarrecorder.biosleep.riepilogo.Riepilogo
import com.wboelens.polarrecorder.biosleep.riepilogo.RiepilogoDb
import com.wboelens.polarrecorder.biosleep.riepilogo.RiepilogoLink
import com.wboelens.polarrecorder.biosleep.riepilogo.RiepilogoLocale
import com.wboelens.polarrecorder.biosleep.riepilogo.SedutaPiano
import com.wboelens.polarrecorder.biosleep.riepilogo.Volume
import com.wboelens.polarrecorder.biosleep.training.Allenamenti
import com.wboelens.polarrecorder.biosleep.training.Formato
import com.wboelens.polarrecorder.biosleep.training.Sport
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// --- Dati ----------------------------------------------------------------------------------------

/** Il riepilogo di una data (null se non c'e'), riletto a ogni salvataggio. first = caricato. */
@Composable
fun rememberRiepilogo(data: String): Pair<Boolean, Riepilogo?> {
  val context = LocalContext.current.applicationContext
  val versione by RiepilogoDb.versione.collectAsState()
  val stato by
      produceState<Pair<Boolean, Riepilogo?>>(false to null, data, versione) {
        value = true to withContext(Dispatchers.IO) { RiepilogoDb.get(context).leggi(data) }
      }
  return stato
}

/** Titolo quando il riepilogo non porta una decisione con etichetta. */
private fun titolo(r: Riepilogo) =
    r.decisione.etichetta ?: if (r.settimanale) "Piano della settimana" else "Seduta di oggi aggiornata"

/** Sottotitolo: motivo della decisione, oppure il primo motivo, oppure la prima riga del testo. */
private fun motivo(r: Riepilogo) =
    r.decisione.motivo ?: motiviPiano(r).firstOrNull() ?: r.testo?.lines()?.firstOrNull { it.isNotBlank() }

private val FORZA_TOLTA = Regex("""forza del .* tolta""", RegexOption.IGNORE_CASE)

/** "forza del <data> tolta: ..." stanno nella sezione Settimana, non tra i motivi del piano. */
private fun forzaTolta(r: Riepilogo) = r.motivi.filter { FORZA_TOLTA.containsMatchIn(it) }

private fun motiviPiano(r: Riepilogo) = r.motivi.filterNot { FORZA_TOLTA.containsMatchIn(it) }

/** Da chiamare subito prima di NavHost: apre il riepilogo quando si tocca la notifica. */
@Composable
fun GestisciLinkRiepilogo(navController: NavController) {
  val richiesta by RiepilogoLink.richiesta.collectAsState()
  LaunchedEffect(richiesta) {
    val rotta = richiesta ?: return@LaunchedEffect
    RiepilogoLink.richiesta.value = null
    navController.navigate(rotta) { launchSingleTop = true }
  }
}

// --- Decisioni prese per un tag --------------------------------------------------------------------

private val PREFISSO_TAG = Regex("""^(\s*tag:\s*)+""", RegexOption.IGNORE_CASE)

/**
 * Motivi nati da un tag. Il cervello li segna con "tag:" negli avvisi; nei motivi il prefisso c'e'
 * nei run giornalieri e manca in quelli settimanali, quindi si riconoscono anche confrontandoli
 * con gli avvisi "tag: ...".
 */
private fun motiviDaTag(r: Riepilogo): Set<String> {
  val dagliAvvisi =
      r.avvisi?.lines()?.filter { PREFISSO_TAG.containsMatchIn(it) }?.map { it.replace(PREFISSO_TAG, "").trim() }.orEmpty().toSet()
  return r.motivi.filter { PREFISSO_TAG.containsMatchIn(it) || it.replace(PREFISSO_TAG, "").trim() in dagliAvvisi }.toSet()
}

/**
 * Una riga di motivo o avviso: se viene da un tag, con l'icona del tag e senza il prefisso "tag:";
 * se e' una regola del caldo ("caldo del ..."), con l'icona del sole; se e' del protocollo DETP,
 * con il termometro.
 */
@Composable
private fun RigaConTag(
    testo: String,
    stile: androidx.compose.ui.text.TextStyle,
    colore: Color,
    daTag: Boolean = PREFISSO_TAG.containsMatchIn(testo),
    puntato: Boolean = false,
) {
  val pulito = testo.replace(PREFISSO_TAG, "").trim()
  // le icone si scelgono sul testo del cervello; si mostra quello tradotto per codice
  val mostrato = TraduzioneMessaggi.testo(LocalContext.current, testo).let { if (it == testo) pulito else it }
  val caldo = pulito.startsWith("caldo del", ignoreCase = true)
  val detp = pulito.startsWith("DETP", ignoreCase = true)
  if (!daTag && !caldo && !detp) {
    Text(if (puntato) "• $mostrato" else mostrato, style = stile, color = colore)
    return
  }
  Row(verticalAlignment = Alignment.Top) {
    if (detp) {
      Icon(Icons.Filled.Thermostat, "DETP", Modifier.padding(top = 2.dp, end = 6.dp).size(16.dp), tint = ColoriBio.rosso)
    } else if (caldo) {
      Icon(Icons.Filled.WbSunny, "Caldo", Modifier.padding(top = 2.dp, end = 6.dp).size(16.dp), tint = ColoriBio.giallo)
    } else {
      Icon(Icons.Filled.Sell, "Tag", Modifier.padding(top = 2.dp, end = 6.dp).size(16.dp), tint = colore)
    }
    Text(mostrato.replaceFirstChar { it.uppercase() }, style = stile, color = colore)
  }
}

// --- Riquadro nella schermata Oggi ---------------------------------------------------------------

/** Decisione del coach di oggi (tocco = riepilogo). Niente se oggi il coach non ha ancora scritto. */
@Composable
fun RiquadroRiepilogo(oggi: LocalDate, onApri: (String) -> Unit) {
  val r = rememberRiepilogo(oggi.toString()).second ?: return
  Card(Modifier.fillMaxWidth().clickable { onApri(r.data) }) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("Coach" + (r.ora?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(titolo(r), style = MaterialTheme.typography.titleLarge)
      motivo(r)?.let { RigaConTag(it, MaterialTheme.typography.bodyMedium, MaterialTheme.colorScheme.onSurface, it in motiviDaTag(r)) }
      if (r.avvisi != null || r.nonScritte.isNotEmpty()) {
        Text("Ci sono avvisi: apri il riepilogo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      }
    }
  }
}

// --- Schermata -----------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RiepilogoScreen(data: String, onBack: () -> Unit, onApriSeduta: (String) -> Unit) {
  val (caricato, r) = rememberRiepilogo(data)
  // Dall'app solo la seduta del giorno (calendario) e la storia della TSB: il resto e' del coach
  val locale = rememberDallaCache(data) { repo, oggi -> RiepilogoLocale.calcola(repo, LocalDate.parse(data), oggi) }
  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text("Coach · ${DateIt.breve(LocalDate.parse(data))}") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
        )
      },
  ) { padding ->
    if (r == null) {
      Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        if (!caricato) CircularProgressIndicator()
        else Text("Riepilogo non disponibile per questa data", color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      return@Scaffold
    }
    val biometria = r.biometria
    val forma = r.forma
    val discipline = r.discipline
    val volume = r.volume
    val carico = r.carico
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Avvisi(r)
      Intestazione(r)
      // LTHR, FTP e passo soglia aggiornati dal coach su Intervals.icu (avvisi del riepilogo)
      AvvisiSoglie.aggiornate(r.avvisi).takeIf { it.isNotEmpty() }?.let { righe ->
        Sezione("Soglie aggiornate") {
          for (riga in righe) {
            Row(verticalAlignment = Alignment.Top) {
              Icon(Icons.Filled.Update, "Aggiornata", Modifier.padding(top = 2.dp, end = 6.dp).size(16.dp), tint = MaterialTheme.colorScheme.primary)
              Text(TraduzioneMessaggi.testo(LocalContext.current, riga).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium)
            }
          }
        }
      }
      val giorno = locale?.giorno
      if (r.oggi != null || giorno != null) {
        Sezione("Oggi") {
          // dal calendario (con lo stato svolta/da fare); il testo del coach solo se il calendario non c'e'
          if (giorno == null) r.oggi?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
          giorno?.let { g ->
            if (g.pianificate.isEmpty() && g.nonPianificate.isEmpty()) Text("Riposo", style = MaterialTheme.typography.bodyMedium)
            for (s in g.pianificate) CardSeduta(s, onClick = { onApriSeduta(data) })
            for (a in g.nonPianificate) CardAttivita(a, onClick = { onApriSeduta(data) })
          }
          TextButton(onClick = { onApriSeduta(data) }) { Text("Apri nel calendario") }
        }
      }
      biometria?.let { Sezione("Biometria") { Biometria(it) } }
      forma?.let { Sezione("Forma") { Forma(it, LocalDate.parse(data), locale?.storicoTsb.orEmpty(), r.avvisi) } }
      if (discipline.isNotEmpty()) Sezione("Discipline") { Discipline(discipline) }
      when {
        r.altaIntensita != null -> Sezione("Intensità") { AltaIntensitaSettimana(r.altaIntensita) }
        r.intensita != null -> Sezione("Intensità") { FacileIntenso(r.intensita.pctFacile, r.intensita.pctIntenso) }
      }
      val forza = forzaTolta(r)
      if (volume != null || carico != null || forza.isNotEmpty()) {
        Sezione("Settimana") {
          Settimana(volume, carico)
          // sedute di forza che il coach non ha potuto mettere, con il motivo
          for (m in forza) Text("• " + m.trim().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
      if (r.sedute.isNotEmpty()) Sezione("Sedute della settimana") { SeduteDellaSettimana(r.sedute, onApriSeduta) }
    }
  }
}

@Composable
private fun Avvisi(r: Riepilogo) {
  // le soglie aggiornate hanno una sezione loro: qui solo gli avvisi veri
  val aggiornate = AvvisiSoglie.aggiornate(r.avvisi).toSet()
  val righe = r.avvisi?.lines()?.filter { it.isNotBlank() && it.trim() !in aggiornate }.orEmpty()
  if (righe.isEmpty() && r.nonScritte.isEmpty()) return
  Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      val colore = MaterialTheme.colorScheme.onErrorContainer
      Text("Avvisi", style = MaterialTheme.typography.titleSmall, color = colore, fontWeight = FontWeight.Bold)
      righe.forEach { RigaConTag(it, MaterialTheme.typography.bodyMedium, colore) }
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
    Text(titolo(r), style = MaterialTheme.typography.headlineMedium)
    val daTag = motiviDaTag(r)
    motivo(r)?.let { RigaConTag(it, MaterialTheme.typography.bodyLarge, MaterialTheme.colorScheme.onSurface, it in daTag) }
    // gli altri motivi del piano (il primo e' gia' sopra se manca la decisione)
    val altri = if (r.decisione.motivo == null) motiviPiano(r).drop(1) else motiviPiano(r)
    for (m in altri) RigaConTag(m, MaterialTheme.typography.bodyMedium, MaterialTheme.colorScheme.onSurface, m in daTag, puntato = true)
    val sotto =
        listOfNotNull(
            r.fase, r.gara?.let { "gara: $it" },
            r.oreTarget?.let { "obiettivo settimana ${ore(it)}" }, r.ora?.let { "ore $it" })
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
  // "Procedi con la seduta pianificata (media 7gg dentro il normal range 54.0-59.4ms)."
  // -> titolo corto sulla riga del pallino, dettaglio tra parentesi sotto, piu' piccolo e giustificato
  val (azione, dettaglio) = dividiAzione(b.azione)
  Row(verticalAlignment = Alignment.Top) {
    Box(Modifier.padding(top = 5.dp).size(12.dp).background(ColoriBio.daNome(b.banda), CircleShape))
    Spacer(Modifier.width(10.dp))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
          listOfNotNull(b.banda?.replaceFirstChar { it.uppercase() }, azione).joinToString(" · "),
          style = MaterialTheme.typography.titleMedium)
      dettaglio?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = grigio, textAlign = TextAlign.Justify)
      }
    }
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

/**
 * Azione della banda dal cervello: parte principale e dettaglio tra parentesi, con i decimali
 * all'italiana ("54.0-59.4ms" -> "54,0–59,4 ms").
 */
internal fun dividiAzione(azione: String?): Pair<String?, String?> {
  if (azione == null) return null to null
  val t = azione.trim().removeSuffix(".")
  val i = t.indexOf(" (")
  val principale = if (i > 0) t.substring(0, i).trim() else t
  val dettaglio =
      if (i > 0) t.substring(i + 2).removeSuffix(")").trim().replaceFirstChar { it.uppercase() } else null
  fun italiano(s: String) =
      s.replace(Regex("""(\d)\.(\d)"""), "$1,$2").replace(Regex("""(\d)-(\d)"""), "$1–$2").replace(Regex("""(\d)ms\b"""), "$1 ms")
  return italiano(principale) to dettaglio?.let { italiano(it) }
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
private fun Forma(f: FormaCoach, giorno: LocalDate, storico: List<Pair<LocalDate, Double>>, avvisi: String?) {
  // Avviso del coach sulla fascia: in evidenza qui, accanto al grafico a cui si riferisce
  val fuoriFascia =
      avvisi?.lines()?.filter { it.contains("fuori dalla fascia", ignoreCase = true) }?.map { it.trim() }.orEmpty()
  if (fuoriFascia.isNotEmpty()) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text("Fuori dalla fascia attesa", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
          for (riga in fuoriFascia) Text(TraduzioneMessaggi.testo(LocalContext.current, riga), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
  }
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    f.ctl?.let { Cifra("CTL", Formato.decimale(it), ColoriBio.ctl) }
    f.atl?.let { Cifra("ATL", Formato.decimale(it), ColoriBio.atl) }
    f.tsb?.let { Cifra("TSB", Formato.conSegno(it) + (f.formPct?.let { p -> " (${Formato.conSegno(p)}%)" } ?: ""), ColoriBio.tsb) }
  }
  val zona = listOfNotNull(f.zona?.let { "zona $it" }, f.zonaAttesa?.let { "attesa in questa fase: $it" })
  if (zona.isNotEmpty()) Text(zona.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
  if (f.tsb != null) GraficoTsb(f, giorno, storico)
  f.tsbObiettivo?.let {
    Text("Obiettivo a domenica: TSB ${Formato.conSegno(it)}" + (f.fascia?.let { (a, b) -> " (fascia attesa ${Formato.conSegno(a)} / ${Formato.conSegno(b)})" } ?: ""),
        style = MaterialTheme.typography.bodySmall)
  }
  if (f.ramp != null) {
    Text(
        "Rampa CTL ${Formato.conSegno(f.ramp)}/sett" + (f.rampMax?.let { " (max ${Formato.decimale(it)})" } ?: ""),
        style = MaterialTheme.typography.bodyMedium)
    f.rampMax?.takeIf { it > 0 }?.let { BarraAvanzamento(f.ramp, it, null) }
  }
}

/**
 * TSB: gli ultimi 14 giorni (linea piena), oggi (punto) e la previsione del coach a domenica
 * (tratteggio). Sullo sfondo le zone di forma del coach con i loro colori, come nella schermata
 * Grafici; l'obiettivo della settimana e' un riquadro tratteggiato, non un'altra zona colorata.
 * La scala include sempre le zone vicine, cosi' la direzione si legge anche quando la TSB varia poco.
 */
@Composable
private fun GraficoTsb(f: FormaCoach, giorno: LocalDate, storico: List<Pair<LocalDate, Double>>) {
  val tsb = f.tsb ?: return
  val passato = storico.filter { it.first < giorno }.takeLast(14)
  val domenica = Allenamenti.lunedi(giorno).plusDays(6)
  val inizio = passato.firstOrNull()?.first ?: giorno
  val fine = if (f.tsbDomenica != null && domenica > giorno) domenica else giorno
  val valori = passato.map { it.second } + listOfNotNull(tsb, f.tsbDomenica, f.fascia?.first, f.fascia?.second)
  val lo = minOf(valori.min() - 6, -15.0)
  val hi = maxOf(valori.max() + 6, 10.0)
  val misuratore = rememberTextMeasurer()
  val stileZona = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
  val linea = MaterialTheme.colorScheme.onSurface
  val obiettivo = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
  val giorni = ChronoUnit.DAYS.between(inizio, fine).coerceAtLeast(1).toFloat()
  Column {
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
      fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
      fun x(d: LocalDate) = size.width * ChronoUnit.DAYS.between(inizio, d) / giorni
      // zone del coach (stato_forma), dall'alto: Transizione, Fresco, Grigia, Ottimale, Alto rischio
      var sopra = hi
      for ((soglia, nome, colore) in FormaCalc.BANDE + Triple(lo, "Alto rischio", "rosso")) {
        val alto = y(minOf(sopra, hi))
        val basso = y(maxOf(soglia, lo))
        if (basso > alto) {
          drawRect(ColoriBio.daNome(colore).copy(alpha = 0.16f), Offset(0f, alto), Size(size.width, basso - alto))
          val t = misuratore.measure(nome, stileZona)
          // nome della zona a sinistra: a destra c'e' il riquadro dell'obiettivo
          if (basso - alto > t.size.height) drawText(t, topLeft = Offset(4.dp.toPx(), alto + 2.dp.toPx()))
        }
        sopra = soglia
        if (soglia <= lo) break
      }
      drawLine(linea.copy(alpha = 0.35f), Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 1.dp.toPx())
      // obiettivo a domenica: riquadro tratteggiato sulla parte della settimana ancora da fare
      f.fascia?.let { (a, b) ->
        val x0 = x(giorno)
        val tratteggio = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))
        drawRect(obiettivo.copy(alpha = 0.08f), Offset(x0, y(b)), Size(size.width - x0, y(a) - y(b)))
        drawRect(obiettivo, Offset(x0, y(b)), Size(size.width - x0, y(a) - y(b)), style = Stroke(1.5.dp.toPx(), pathEffect = tratteggio))
      }
      // ultimi 14 giorni
      val punti = passato.map { Offset(x(it.first), y(it.second)) } + Offset(x(giorno), y(tsb))
      for (k in 1 until punti.size) drawLine(linea, punti[k - 1], punti[k], strokeWidth = 2.5.dp.toPx())
      // previsione del coach a domenica
      f.tsbDomenica?.takeIf { fine > giorno }?.let { d ->
        drawLine(linea, Offset(x(giorno), y(tsb)), Offset(x(fine), y(d)), strokeWidth = 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
        val fuori = f.fascia?.let { (a, b) -> d < a || d > b } == true
        drawCircle(if (fuori) ColoriBio.rosso else linea, 6.dp.toPx(), Offset(x(fine), y(d)), style = Stroke(2.5.dp.toPx()))
      }
      drawCircle(linea, 5.dp.toPx(), Offset(x(giorno), y(tsb)))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(DateIt.asse(inizio), style = MaterialTheme.typography.labelSmall)
      Text("oggi ${Formato.conSegno(tsb)}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
      f.tsbDomenica?.takeIf { fine > giorno }?.let {
        val fuori = f.fascia?.let { (a, b) -> it < a || it > b } == true
        Text(
            "domenica ${Formato.conSegno(it)} (previsto)",
            style = MaterialTheme.typography.labelSmall,
            color = if (fuori) ColoriBio.rosso else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (fuori) FontWeight.Bold else FontWeight.Normal)
      }
    }
    val legenda =
        listOfNotNull(
            "linea: ultimi 14 giorni",
            "tratteggio: previsione del coach".takeIf { f.tsbDomenica != null },
            "riquadro: fascia attesa a domenica".takeIf { f.fascia != null })
    Text(legenda.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

// --- Discipline e intensita' --------------------------------------------------------------------

private val SPORT = listOf("nuoto" to "Nuoto", "bici" to "Bici", "corsa" to "Corsa")

@Composable
private fun Discipline(discipline: Map<String, Map<String, QuotaDisciplina>>) {
  val sett = discipline["7gg"].orEmpty()
  val mese = discipline["28gg"].orEmpty()
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

/**
 * Facile / intenso su una barra sola: verde il facile, rosso l'intenso, e la linea dell'80% che
 * indica dove dovrebbe finire il verde secondo la regola 80/20.
 */
@Composable
private fun FacileIntenso(facile: Double?, intenso: Double?) {
  val f = facile ?: intenso?.let { 100 - it } ?: return
  val i = intenso ?: (100 - f)
  val totale = (f + i).takeIf { it > 0 } ?: 100.0
  val verde = ColoriBio.verde
  val rosso = ColoriBio.rosso
  val segno = MaterialTheme.colorScheme.onSurface
  Canvas(Modifier.fillMaxWidth().height(28.dp)) {
    val h = 16.dp.toPx()
    val y = (size.height - h) / 2
    val xF = (size.width * f / totale).toFloat()
    drawRoundRect(verde, Offset(0f, y), Size(xF, h), CornerRadius(4.dp.toPx()))
    drawRoundRect(rosso, Offset(xF, y), Size(size.width - xF, h), CornerRadius(4.dp.toPx()))
    val x80 = size.width * 0.8f
    drawLine(segno, Offset(x80, 0f), Offset(x80, size.height), strokeWidth = 2.dp.toPx())
  }
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text("Facile ${Formato.decimale(f, 0)}%", color = verde, fontWeight = FontWeight.Bold)
    Text("Intenso ${Formato.decimale(i, 0)}%", color = rosso, fontWeight = FontWeight.Bold)
  }
  Text(
      "La linea segna l'80%: con la regola 80/20 il verde dovrebbe arrivare fin lì.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * La quota di alta intensita' come la controlla il coach: minuti di lavoro intenso su minuti di
 * bici e corsa. Barra = quota pianificata, linea = tetto della fase dato dal coach (tetto_pct).
 * Rosso solo se la quota supera davvero il tetto.
 */
@Composable
private fun AltaIntensitaSettimana(a: AltaIntensita) {
  val colore = if (a.sopraTetto) ColoriBio.rosso else MaterialTheme.colorScheme.primary
  val fondo = MaterialTheme.colorScheme.surfaceVariant
  val segno = MaterialTheme.colorScheme.onSurface
  val tetto = a.tettoPct
  val scala = maxOf((tetto ?: a.pct) * 2, a.pct * 1.2, 1.0)
  Text(
      "${Formato.decimale(a.pct)}% di alta intensità",
      style = MaterialTheme.typography.titleMedium,
      color = colore,
      fontWeight = FontWeight.Bold)
  Canvas(Modifier.fillMaxWidth().height(28.dp)) {
    val h = 14.dp.toPx()
    val y = (size.height - h) / 2
    val r = CornerRadius(h / 2)
    drawRoundRect(fondo, Offset(0f, y), Size(size.width, h), r)
    drawRoundRect(colore, Offset(0f, y), Size((size.width * a.pct / scala).toFloat().coerceAtLeast(h), h), r)
    if (tetto != null) {
      val xt = (size.width * tetto / scala).toFloat()
      drawLine(segno, Offset(xt, 0f), Offset(xt, size.height), strokeWidth = 2.dp.toPx())
    }
  }
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text("${a.minuti}' intensi su ${a.suMinuti}' di bici e corsa", style = MaterialTheme.typography.bodySmall)
    tetto?.let {
      Text(
          "tetto ${Formato.decimale(it, 0)}%",
          style = MaterialTheme.typography.bodySmall,
          fontWeight = FontWeight.Bold,
          color = if (a.sopraTetto) ColoriBio.rosso else MaterialTheme.colorScheme.onSurface)
    }
  }
  Text(
      // la regola della fase scritta dal coach; nei riepiloghi vecchi, la frase di prima
      a.regola?.replaceFirstChar { it.uppercase() }
          ?: "Il resto del volume è aerobico facile. La linea è il tetto che il coach non supera.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// --- Settimana -----------------------------------------------------------------------------------

@Composable
private fun Settimana(volume: Volume?, carico: Carico?) {
  if (volume == null && carico == null) return
  Text(
      "Pieno = fatto · chiaro = con le sedute ancora in calendario · linea = obiettivo · rosso = tetto",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant)
  volume?.let { v ->
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
  carico?.let { c ->
    val fatti = c.fatti
    val tetto = c.tetto
    if (fatti != null) {
      Text(
          "TSS: ${fatti.toInt()} fatti" + (tetto?.let { " di ${it.toInt()}" } ?: "") +
              (c.calendario?.let { " · ${it.toInt()} con le sedute in calendario" } ?: ""),
          style = MaterialTheme.typography.bodyMedium)
      val scala = max(tetto ?: 0.0, c.calendario ?: 0.0)
      if (scala > 0) BarraAvanzamento(fatti, scala, c.sostenibile, previsione = c.calendario, tetto = tetto)
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

// --- Sedute per giorno ---------------------------------------------------------------------------

@Composable
private fun SeduteDellaSettimana(sedute: List<SedutaPiano>, onApriSeduta: (String) -> Unit) {
  for ((giorno, delGiorno) in sedute.groupBy { it.data }) {
    val d = runCatching { LocalDate.parse(giorno) }.getOrNull()
    Text(
        d?.let { DateIt.breve(it) } ?: giorno,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 4.dp))
    for (s in delGiorno) Seduta(s, onApri = { onApriSeduta(s.data) })
  }
}

@Composable
private fun Seduta(s: SedutaPiano, onApri: () -> Unit) {
  var aperta by remember { mutableStateOf(false) }
  val sport = Sport.da(s.tipo)
  val colore = ColoriBio.sport(sport)
  val forma = RoundedCornerShape(10.dp)
  Column(
      Modifier.fillMaxWidth()
          .background(colore.copy(alpha = 0.08f), forma)
          .clickable { aperta = !aperta }
          .padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.width(4.dp).height(32.dp).background(colore, RoundedCornerShape(2.dp)))
      Spacer(Modifier.width(10.dp))
      Column(Modifier.weight(1f)) {
        Text(s.nome, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            listOfNotNull(sport.etichetta, s.durataMin?.let { Formato.durata(it * 60) }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      if (s.qualita) Etichetta("qualità", ColoriBio.rosso)
      if (s.declassata) Etichetta("alleggerita", ColoriBio.giallo)
    }
    if (aperta) {
      s.descrizione?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
      Text(
          "Apri nel calendario",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.clickable(onClick = onApri).padding(vertical = 4.dp))
    }
  }
}

@Composable
private fun Etichetta(testo: String, colore: Color) {
  Text(
      testo,
      style = MaterialTheme.typography.labelSmall,
      color = colore,
      modifier = Modifier.padding(start = 6.dp).background(colore.copy(alpha = 0.12f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
}
