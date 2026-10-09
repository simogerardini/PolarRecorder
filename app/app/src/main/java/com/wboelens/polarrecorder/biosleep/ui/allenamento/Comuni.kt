package com.wboelens.polarrecorder.biosleep.ui.allenamento

import com.wboelens.polarrecorder.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import com.wboelens.polarrecorder.biosleep.cache.CacheRepo
import com.wboelens.polarrecorder.biosleep.cache.CacheSync
import com.wboelens.polarrecorder.biosleep.cache.FormaHome
import com.wboelens.polarrecorder.biosleep.training.Allenamenti
import com.wboelens.polarrecorder.biosleep.training.Formato
import com.wboelens.polarrecorder.biosleep.training.Segmento
import com.wboelens.polarrecorder.biosleep.training.Sport
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// --- Colori ---------------------------------------------------------------------------------------

object ColoriBio {
  val verde = Color(0xFF2E7D32)
  val giallo = Color(0xFFF9A825)
  val arancio = Color(0xFFEF6C00)
  val rosso = Color(0xFFEF485B) // coral del sito
  val blu = Color(0xFF39A5C8) // azure del sito
  val grigio = Color(0xFF757575)

  val ctl = Color(0xFF1E88E5)
  val atl = Color(0xFFD81B60)
  val tsb = Color(0xFFF9A825)

  /** I nomi dei colori di zona del coach (stato_forma) e della banda biometrica. */
  fun daNome(nome: String?): Color =
      when (nome) {
        "verde" -> verde
        "giallo" -> giallo
        "rosso" -> rosso
        "blu" -> blu
        else -> grigio
      }

  fun sport(s: Sport): Color =
      when (s) {
        Sport.NUOTO -> Color(0xFF0288D1)
        Sport.BICI -> Color(0xFFEF6C00)
        Sport.CORSA -> Color(0xFF2E7D32)
        Sport.PALESTRA -> Color(0xFF6A1B9A)
        Sport.MULTISPORT -> Color(0xFF00897B)
        Sport.TRANSIZIONE, Sport.ALTRO -> Color(0xFF546E7A)
      }

  fun aderenza(a: Allenamenti.Aderenza): Color =
      when (a) {
        Allenamenti.Aderenza.VERDE -> verde
        Allenamenti.Aderenza.GIALLA -> giallo
        Allenamenti.Aderenza.ARANCIO -> arancio
        Allenamenti.Aderenza.NESSUNA -> grigio
      }
}

// --- Date in italiano -----------------------------------------------------------------------------

object DateIt {
  // Formati per lingua (Locale dell'utente, che con la lingua scelta per l'app segue quella).
  // Modelli scelti per ogni lingua: l'ordine giorno/mese e le parole cambiano (es. zh "10月9日").
  private fun modelli(l: Locale): Triple<String, String, String> =
      when (l.language) {
        "en" -> Triple("EEE, MMM d", "EEEE, MMMM d", "MMM d")
        "es" -> Triple("EEE d MMM", "EEEE, d 'de' MMMM", "d MMM")
        "zh" -> Triple("M月d日 EEE", "M月d日 EEEE", "M月d日")
        else -> Triple("EEE d MMM", "EEEE d MMMM", "d MMM")
      }

  private fun f(modello: (Triple<String, String, String>) -> String): DateTimeFormatter {
    val l = Locale.getDefault()
    return DateTimeFormatter.ofPattern(modello(modelli(l)), l)
  }

  fun breve(d: LocalDate): String = d.format(f { it.first })

  fun lunga(d: LocalDate): String = d.format(f { it.second }).replaceFirstChar { it.uppercase(Locale.getDefault()) }

  fun asse(d: LocalDate): String = d.format(f { it.third })

  /** Iniziale del giorno nella lingua dell'utente (it: L M M G V S D). */
  fun iniziale(d: LocalDate): String = d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()).uppercase(Locale.getDefault())
}

// --- Lettura della cache ---------------------------------------------------------------------------

/**
 * Carica dati dalla cache fuori dal main thread e li ricarica a ogni aggiornamento riuscito
 * (CacheSync.stato.versione). null finche' il primo caricamento non e' finito.
 */
@Composable
fun <T> rememberDallaCache(vararg chiavi: Any?, carica: (CacheRepo, LocalDate) -> T): T? {
  val context = LocalContext.current.applicationContext
  val stato by CacheSync.stato.collectAsState()
  val oggi = LocalDate.now()
  val valore by
      produceState<T?>(null, stato.versione, oggi, *chiavi) {
        value = withContext(Dispatchers.IO) { carica(CacheRepo.get(context), oggi) }
      }
  return valore
}

/** Tasto "aggiorna" della barra in alto: rilegge subito da Intervals.icu. */
@Composable
fun AzioneAggiorna() {
  val context = LocalContext.current
  val stato by CacheSync.stato.collectAsState()
  if (stato.inCorso) {
    CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
  } else {
    IconButton(onClick = { CacheSync.aggiornaInBackground(context, forza = true) }) {
      Icon(Icons.Filled.Refresh, "Aggiorna da Intervals.icu")
    }
  }
}

/** "Aggiornato alle 07:42", oppure l'ultimo errore in rosso. */
@Composable
fun StatoAggiornamento() {
  val stato by CacheSync.stato.collectAsState()
  val stile = MaterialTheme.typography.labelSmall
  val errore = stato.errore
  when {
    errore != null ->
        Text(stringResource(R.string.comuni_dati_non_aggiornati, errore.toString()), style = stile, color = MaterialTheme.colorScheme.error)
    stato.aggiornatoMs != null -> {
      val ora =
          java.time.Instant.ofEpochMilli(stato.aggiornatoMs!!)
              .atZone(java.time.ZoneId.systemDefault())
              .format(DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ITALIAN))
      Text(stringResource(R.string.comuni_aggiornato, ora.toString()), style = stile, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

// --- Barra di navigazione --------------------------------------------------------------------------

private data class Voce(val rotta: String, val etichetta: String, val icona: ImageVector)

private val VOCI =
    listOf(
        Voce("oggi", "Oggi", Icons.Filled.Today),
        Voce("calendario", "Calendario", Icons.Filled.CalendarMonth),
        Voce("grafici", "Grafici", Icons.AutoMirrored.Filled.ShowChart),
        Voce("home", "Notte", Icons.Filled.Bedtime),
    )

@Composable
fun BarraBioSleep(navController: NavController) {
  val voce by navController.currentBackStackEntryAsState()
  val corrente = voce?.destination?.route?.substringBefore('?')
  NavigationBar {
    for (v in VOCI) {
      NavigationBarItem(
          selected = corrente == v.rotta,
          onClick = {
            // Ogni scheda riparte dalla sua schermata principale. Niente saveState/restoreState:
            // salvando la pila con popUpTo(Oggi), Navigation la associava anche a "Oggi" stessa, e
            // il tocco su Oggi ripristinava la pila del Calendario (con il dettaglio della seduta).
            navController.navigate(v.rotta) {
              popUpTo(navController.graph.findStartDestination().id)
              launchSingleTop = true
            }
          },
          icon = { Icon(v.icona, null) },
          label = { Text(v.etichetta) },
      )
    }
  }
}

// --- Riga CTL / ATL / TSB --------------------------------------------------------------------------

@Composable
fun RigaForma(forma: FormaHome?, modifier: Modifier = Modifier) {
  val oggi = forma?.oggi
  if (oggi == null) {
    Text(
        stringResource(R.string.comuni_forma_non_disponibile_servono),
        modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    return
  }
  val ieri = forma?.ieri
  Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Indicatore("Forma fisica", "CTL", oggi.ctl, ieri?.ctl, ColoriBio.ctl)
      Indicatore("Stanchezza", "ATL", oggi.atl, ieri?.atl, ColoriBio.atl)
      Indicatore("Forma", "TSB", oggi.tsb, ieri?.tsb, ColoriBio.tsb)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(10.dp).background(ColoriBio.daNome(oggi.colore), CircleShape))
      Spacer(Modifier.width(6.dp))
      Text(
          "Zona ${oggi.zona} da ${oggi.giorniInZona} ${if (oggi.giorniInZona == 1) "giorno" else "giorni"}",
          style = MaterialTheme.typography.bodySmall)
    }
  }
}

@Composable
private fun Indicatore(titolo: String, sigla: String, valore: Double, ieri: Double?, colore: Color) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(sigla, style = MaterialTheme.typography.labelMedium, color = colore, fontWeight = FontWeight.Bold)
    Text(Formato.decimale(valore), style = MaterialTheme.typography.headlineSmall)
    if (ieri != null) {
      val delta = valore - ieri
      val icona =
          when {
            delta >= 0.05 -> Icons.AutoMirrored.Filled.TrendingUp
            delta <= -0.05 -> Icons.AutoMirrored.Filled.TrendingDown
            else -> Icons.AutoMirrored.Filled.TrendingFlat
          }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icona, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            " ${Formato.conSegno(delta)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    Text(titolo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

// --- Grafico a linee -------------------------------------------------------------------------------

data class SerieGrafico(val nome: String, val colore: Color, val valori: List<Double?>, val punti: Boolean = false)

/** Fascia orizzontale colorata tra due valori (infiniti ammessi: viene tagliata al grafico). */
data class FasciaGrafico(val da: Double, val a: Double, val colore: Color)

/**
 * Linee su giorni consecutivi. Un valore null interrompe la linea; punti = true disegna anche
 * ogni valore (utile per le notti, che possono avere buchi). banda = fascia colorata [basso, alto].
 */
@Composable
fun GraficoLinee(
    giorni: List<LocalDate>,
    serie: List<SerieGrafico>,
    modifier: Modifier = Modifier,
    banda: Pair<Double, Double>? = null,
    lineaZero: Boolean = false,
    fasce: List<FasciaGrafico> = emptyList(),
    /** Valori da includere comunque nella scala (per mostrare le fasce vicine). */
    includi: List<Double> = emptyList(),
    legenda: Boolean = true,
) {
  val misuratore = rememberTextMeasurer()
  val stile = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
  val griglia = MaterialTheme.colorScheme.outlineVariant
  val coloreBanda = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
  val coloreZero = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
  val tutti =
      serie.flatMap { it.valori.filterNotNull() } +
          listOfNotNull(banda?.first, banda?.second) +
          (if (lineaZero) listOf(0.0) else emptyList()) +
          includi
  if (giorni.size < 2 || serie.all { s -> s.valori.all { it == null } }) {
    Box(modifier, contentAlignment = Alignment.Center) { Text(stringResource(R.string.comuni_dati_insufficienti_per_il), style = stile) }
    return
  }
  val minV = tutti.min()
  val maxV = tutti.max()
  val margine = max((maxV - minV) * 0.08, 1.0)
  val yMin = minV - margine
  val yMax = maxV + margine
  Column(modifier) {
    Canvas(Modifier.fillMaxWidth().weight(1f)) {
      val sinistra = 36.dp.toPx()
      val destra = 8.dp.toPx()
      val sopra = 6.dp.toPx()
      val sotto = 20.dp.toPx()
      val w = size.width - sinistra - destra
      val h = size.height - sopra - sotto
      fun x(i: Int) = sinistra + w * i / (giorni.size - 1).toFloat()
      fun y(v: Double) = sopra + h * (1f - ((v - yMin) / (yMax - yMin)).toFloat())

      for (f in fasce) {
        val alto = y(minOf(f.a, yMax))
        val basso = y(maxOf(f.da, yMin))
        if (basso > alto) drawRect(f.colore, topLeft = Offset(sinistra, alto), size = Size(w, basso - alto))
      }
      banda?.let { (basso, alto) ->
        drawRect(coloreBanda, topLeft = Offset(sinistra, y(alto)), size = Size(w, y(basso) - y(alto)))
      }
      for (v in listOf(minV, (minV + maxV) / 2, maxV)) {
        val yy = y(v)
        drawLine(griglia, Offset(sinistra, yy), Offset(size.width - destra, yy), strokeWidth = 1.dp.toPx())
        val t = misuratore.measure(Formato.decimale(v, 0), stile)
        drawText(t, topLeft = Offset(sinistra - t.size.width - 4.dp.toPx(), yy - t.size.height / 2f))
      }
      if (lineaZero) {
        drawLine(coloreZero, Offset(sinistra, y(0.0)), Offset(size.width - destra, y(0.0)), strokeWidth = 1.dp.toPx())
      }
      for (i in listOf(0, giorni.size / 2, giorni.lastIndex).distinct()) {
        val t = misuratore.measure(DateIt.asse(giorni[i]), stile)
        val xx = (x(i) - t.size.width / 2f).coerceIn(sinistra, size.width - t.size.width.toFloat())
        drawText(t, topLeft = Offset(xx, sopra + h + 4.dp.toPx()))
      }
      for (s in serie) {
        val path = Path()
        var aperta = false
        s.valori.forEachIndexed { i, v ->
          if (v == null) {
            aperta = false
          } else if (!aperta) {
            path.moveTo(x(i), y(v))
            aperta = true
          } else {
            path.lineTo(x(i), y(v))
          }
        }
        drawPath(path, s.colore, style = Stroke(width = 2.dp.toPx()))
        if (s.punti) s.valori.forEachIndexed { i, v -> if (v != null) drawCircle(s.colore, 3.dp.toPx(), Offset(x(i), y(v))) }
      }
    }
    if (legenda && (serie.size > 1 || banda != null)) {
      Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (s in serie) Legenda(s.colore, s.nome)
        if (banda != null) Legenda(coloreBanda.copy(alpha = 0.4f), "range normale")
      }
    }
  }
}

@Composable
private fun Legenda(colore: Color, testo: String) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(10.dp).background(colore, CircleShape))
    Text(" $testo", style = MaterialTheme.typography.labelSmall)
  }
}

// --- Grafico della struttura di una seduta ---------------------------------------------------------

/** Blocchi larghi quanto la durata e alti quanto l'intensita', come il grafico di TrainingPeaks. */
@Composable
fun GraficoSeduta(segmenti: List<Segmento>, colore: Color, modifier: Modifier = Modifier) {
  if (segmenti.isEmpty()) return
  val totale = segmenti.sumOf { it.durataS }.toFloat()
  val massimo = max(120.0, segmenti.maxOf { max(it.da, it.a) })
  Canvas(modifier) {
    var x0 = 0f
    for (s in segmenti) {
      val x1 = x0 + size.width * s.durataS / totale
      fun y(v: Double) = size.height * (1f - (v / massimo).toFloat())
      val p = Path().apply {
        moveTo(x0, size.height)
        lineTo(x0, y(s.da))
        lineTo(x1, y(s.a))
        lineTo(x1, size.height)
        close()
      }
      drawPath(p, colore.copy(alpha = if (s.recupero) 0.35f else 0.85f))
      x0 = x1
    }
  }
}
