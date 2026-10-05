package com.wboelens.polarrecorder.biosleep.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.BioSleepDataSaver
import com.wboelens.polarrecorder.biosleep.SleepDb
import com.wboelens.polarrecorder.biosleep.sonno.Allineamento
import com.wboelens.polarrecorder.biosleep.sonno.Debito
import com.wboelens.polarrecorder.biosleep.sonno.LivelloDeficit
import com.wboelens.polarrecorder.biosleep.sonno.OrologioBiologico
import com.wboelens.polarrecorder.biosleep.sonno.PunteggioSonno
import com.wboelens.polarrecorder.biosleep.sonno.SonnoRepo
import com.wboelens.polarrecorder.biosleep.sonno.StatoSonno
import com.wboelens.polarrecorder.biosleep.sonno.minutiNotte
import com.wboelens.polarrecorder.biosleep.sonno.orario
import com.wboelens.polarrecorder.biosleep.ui.allenamento.ColoriBio
import com.wboelens.polarrecorder.biosleep.ui.allenamento.DateIt
import com.wboelens.polarrecorder.biosleep.ui.allenamento.Sezione
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val BLU = Color(0xFF64B5F6)
private val BLU_SCURO = Color(0xFF546E7A)

/** Stato del sonno dalle notti salvate; si ricarica a fine analisi di una notte nuova. */
@Composable
fun rememberStatoSonno(): StatoSonno? {
  val context = LocalContext.current.applicationContext
  val inAnalisi by BioSleepDataSaver.analyzing.collectAsState()
  val stato by
      produceState<StatoSonno?>(null, inAnalisi, LocalDate.now()) {
        value = withContext(Dispatchers.IO) { SonnoRepo.carica(SleepDb.get(context)) }
      }
  return stato
}

private fun durata(ore: Double): String {
  val min = (ore * 60).toInt()
  return if (min < 60) "${min}m" else "${min / 60}h ${"%02d".format(min % 60)}m"
}

private fun scartoTesto(min: Int): String {
  val a = abs(min)
  val t = if (a >= 60) "${a / 60} h ${a % 60} min" else "$a min"
  return when (OrologioBiologico.allineamento(min)) {
    Allineamento.IN_LINEA -> "in linea con il tuo cronotipo"
    Allineamento.IN_RITARDO -> "$t dopo rispetto al tuo cronotipo"
    Allineamento.IN_ANTICIPO -> "$t prima rispetto al tuo cronotipo"
  }
}

private fun coloreDeficit(l: LivelloDeficit) =
    when (l) {
      LivelloDeficit.NESSUNO -> BLU
      LivelloDeficit.BASSO -> ColoriBio.verde
      LivelloDeficit.MODERATO -> ColoriBio.giallo
      LivelloDeficit.ALTO -> ColoriBio.rosso
    }

// --- Riquadro nella scheda Notte -----------------------------------------------------------------

/** Punteggio di stanotte, deficit e orologio biologico in una riga; tocco = dettaglio. */
@Composable
fun RiquadroSonno(onApri: () -> Unit) {
  val s = rememberStatoSonno() ?: return
  if (s.ultima == null) return
  Card(Modifier.fillMaxWidth().clickable(onClick = onApri)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Sonno · ${DateIt.breve(s.ultima.giorno)}", style = MaterialTheme.typography.titleMedium)
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Mini("Punteggio", s.punteggio?.totale?.toString() ?: "—", s.punteggio?.let { PunteggioSonno.etichetta(it.totale) })
        Mini(
            "Deficit", if (s.deficit.ore < 0.02) "0m" else durata(s.deficit.ore),
            if (s.fabbisogno.appreso) s.deficit.livello.etichetta else "${s.fabbisogno.notti}/${Debito.NOTTI_MINIME} notti")
        Mini(
            "Orologio",
            s.scartoMin?.let { OrologioBiologico.allineamento(it).etichetta } ?: "—",
            if (s.cronotipo == null) "${s.notti}/${OrologioBiologico.NOTTI_MINIME} notti" else null)
      }
    }
  }
}

@Composable
private fun Mini(titolo: String, valore: String, nota: String?) {
  Column {
    Text(titolo, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(valore, style = MaterialTheme.typography.titleLarge)
    nota?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
  }
}

// --- Schermata ----------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SonnoScreen(onBack: () -> Unit) {
  val s = rememberStatoSonno()
  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text("Sonno") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } })
      },
  ) { padding ->
    if (s == null) {
      Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
      return@Scaffold
    }
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Sezione("Punteggio del sonno") { Punteggio(s) }
      Sezione("Deficit di sonno") { Deficit(s) }
      Sezione("Orologio biologico") { Orologio(s) }
      Sezione("Cronotipo") { Cronotipo(s) }
    }
  }
}

@Composable
private fun Nota(t: String) =
    Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Punteggio(s: StatoSonno) {
  val p = s.punteggio
  if (p == null || s.ultima == null) {
    Nota("Nessuna notte registrata con le fasi del sonno.")
    return
  }
  Row(verticalAlignment = Alignment.Bottom) {
    Text("${p.totale}", style = MaterialTheme.typography.displayMedium)
    Spacer(Modifier.width(10.dp))
    Text(PunteggioSonno.etichetta(p.totale), style = MaterialTheme.typography.titleMedium, color = BLU, modifier = Modifier.padding(bottom = 10.dp))
  }
  Nota("Notte del ${DateIt.breve(s.ultima.giorno)} · ${durata(s.ultima.sonnoMin / 60.0)} di sonno")
  for (c in p.contributi) {
    Column {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(c.nome, style = MaterialTheme.typography.bodyMedium)
        Text("${c.punti}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
      }
      LinearProgressIndicator(
          progress = { c.punti / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp), color = BLU, strokeCap = StrokeCap.Round)
    }
  }
  Nota(
      "Media pesata: durata rispetto al tuo fabbisogno, efficienza, sonno profondo e REM, minuti svegli e orario. " +
          "Profondo e REM sono stime da battito e respiro: pesano meno della durata.")
}

@Composable
private fun Deficit(s: StatoSonno) {
  val d = s.deficit
  val colore = coloreDeficit(d.livello)
  Row(verticalAlignment = Alignment.Bottom) {
    Text(if (d.ore < 0.02) "0m" else durata(d.ore), style = MaterialTheme.typography.displaySmall)
    Spacer(Modifier.width(10.dp))
    Text(d.livello.etichetta.uppercase(), color = colore, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
  }
  // quattro livelli, come Oura: Nessuno, Basso, Moderato, Alto
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    for (l in LivelloDeficit.entries) {
      Box(
          Modifier.weight(1f).height(6.dp).background(
              if (l == d.livello) colore else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp)))
    }
  }
  // ultimi 14 giorni: barre delle ore dormite e linea del fabbisogno
  val massimo = maxOf(s.fabbisogno.ore + 1, s.ultimi14.mapNotNull { it.second }.maxOrNull() ?: 0.0)
  val linea = MaterialTheme.colorScheme.onSurface
  val vuoto = MaterialTheme.colorScheme.surfaceVariant
  Canvas(Modifier.fillMaxWidth().height(90.dp)) {
    val passo = size.width / s.ultimi14.size
    s.ultimi14.forEachIndexed { i, (_, ore) ->
      val h = ((ore ?: 0.0) / massimo * size.height).toFloat()
      val c = if (ore == null) vuoto else if (ore >= s.fabbisogno.ore) BLU else ColoriBio.giallo
      drawRect(c, Offset(i * passo + passo * 0.2f, size.height - maxOf(h, 2f)), Size(passo * 0.6f, maxOf(h, 2f)))
    }
    val y = (size.height * (1 - s.fabbisogno.ore / massimo)).toFloat()
    drawLine(linea, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx())
  }
  Nota(
      "Linea: fabbisogno ${durata(s.fabbisogno.ore)}" +
          (if (s.fabbisogno.appreso) " (appreso dalle tue notti)" else " (8 h finché le notti sono meno di 14: ${s.fabbisogno.notti}/14)") +
          ". Barre grigie: notti non registrate, non contano. Una notte più lunga del fabbisogno riduce il deficit.")
}

/** Quadrante di 24 ore: fuori la notte di ieri, dentro gli orari ottimali del cronotipo. */
@Composable
private fun Orologio(s: StatoSonno) {
  val c = s.cronotipo
  val n = s.ultima
  if (c == null) {
    Nota("Calibrazione: ${s.notti}/${OrologioBiologico.NOTTI_MINIME} notti negli ultimi 90 giorni. Servono anche alcune notti del weekend.")
  }
  val misuratore = rememberTextMeasurer()
  val stile = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
  val fondo = MaterialTheme.colorScheme.surfaceVariant
  val punto = MaterialTheme.colorScheme.onSurface
  Canvas(Modifier.fillMaxWidth().height(260.dp)) {
    val r = minOf(size.width, size.height) / 2 - 28.dp.toPx()
    val centro = Offset(size.width / 2, size.height / 2)
    fun angolo(minuti: Int) = minuti / 1440f * 360f - 90f
    fun pos(minuti: Int, raggio: Float): Offset {
      val a = Math.toRadians(angolo(minuti).toDouble())
      return centro + Offset((cos(a) * raggio).toFloat(), (sin(a) * raggio).toFloat())
    }
    fun arco(da: Int, a: Int, raggio: Float, colore: Color, spessore: Float) {
      val dur = ((a - da) % 1440 + 1440) % 1440
      drawArc(colore, angolo(da), dur / 1440f * 360f, false, centro - Offset(raggio, raggio), Size(2 * raggio, 2 * raggio),
          style = Stroke(spessore, cap = StrokeCap.Round))
    }
    val spessore = 10.dp.toPx()
    drawCircle(fondo, r, centro, style = Stroke(spessore))
    drawCircle(fondo, r - 18.dp.toPx(), centro, style = Stroke(spessore))
    for (h in 0 until 24 step 3) {
      val t = misuratore.measure("$h", stile)
      val p = pos(h * 60, r + 18.dp.toPx())
      drawText(t, topLeft = Offset(p.x - t.size.width / 2f, p.y - t.size.height / 2f))
    }
    if (c != null) {
      arco(c.sonnoMin, c.svegliaMin, r - 18.dp.toPx(), BLU_SCURO, spessore)
      drawCircle(punto, 6.dp.toPx(), pos(c.centroMin, r - 18.dp.toPx()))
    }
    if (n != null) {
      arco(minutiNotte(n.inizio), minutiNotte(n.fine), r, BLU, spessore)
      drawCircle(punto, 6.dp.toPx(), pos(minutiNotte(n.centro), r))
    }
  }
  if (n != null) {
    Text(
        "Stanotte: ${orario(minutiNotte(n.inizio))} → ${orario(minutiNotte(n.fine))}, punto centrale alle ${orario(minutiNotte(n.centro))}",
        style = MaterialTheme.typography.bodyMedium)
  }
  s.scartoMin?.let { scarto ->
    Text(OrologioBiologico.allineamento(scarto).etichetta, style = MaterialTheme.typography.titleLarge)
    Text("Il punto centrale del tuo sonno è caduto ${scartoTesto(scarto)}.", style = MaterialTheme.typography.bodyMedium)
  }
  Nota("Anello esterno (azzurro): la notte appena passata. Anello interno: gli orari ottimali per il tuo cronotipo.")
}

@Composable
private fun Cronotipo(s: StatoSonno) {
  val c = s.cronotipo
  if (c == null) {
    Nota(
        "Il cronotipo si calcola dal punto centrale del sonno nei giorni liberi, dopo almeno ${OrologioBiologico.NOTTI_MINIME} " +
            "notti (ora ${s.notti}).")
    return
  }
  Text(c.tipo.etichetta, style = MaterialTheme.typography.headlineSmall)
  Text("Orari di sonno ottimali per te", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Mini("Sonno", orario(c.sonnoMin).toString(), null)
    Mini("Punto centrale", orario(c.centroMin).toString(), null)
    Mini("Sveglia", orario(c.svegliaMin).toString(), null)
  }
  Nota(
      "Dal punto centrale del sonno nelle notti libere (venerdì e sabato), corretto per il sonno recuperato nel " +
          "weekend, sulle ultime ${c.notti} notti. Sveglia e addormentamento: il punto centrale più e meno metà del " +
          "tuo fabbisogno.")
}
