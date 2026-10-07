package com.wboelens.polarrecorder.biosleep.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wboelens.polarrecorder.biosleep.BioSleepDataSaver
import com.wboelens.polarrecorder.biosleep.hal.Fasce
import androidx.compose.runtime.collectAsState
import com.wboelens.polarrecorder.biosleep.live.RespiroAcc
import com.wboelens.polarrecorder.biosleep.live.RespiroRsa
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// --- Batteria della fascia -------------------------------------------------------------------------

/** Sotto questa soglia la fascia potrebbe non arrivare al mattino: la H10 scende a gradini, poi in fretta. */
const val BATTERIA_BASSA = 20

/** Ultimo livello letto, per mostrarlo la sera prima di avviare la notte (la fascia e' scollegata). */
object BatteriaFascia {
  private const val PREFS = "biosleep_batteria"

  fun ricorda(context: Context, livello: Int) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putInt("livello", livello)
        .putLong("ms", System.currentTimeMillis())
        .apply()
  }

  /** (livello, istante della lettura) oppure null. */
  fun ultima(context: Context): Pair<Int, Long>? {
    val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (!p.contains("livello")) return null
    return p.getInt("livello", 0) to p.getLong("ms", 0L)
  }
}

private fun iconaBatteria(livello: Int): ImageVector =
    when {
      livello <= BATTERIA_BASSA -> Icons.Filled.BatteryAlert
      livello <= 40 -> Icons.Filled.Battery2Bar
      livello <= 65 -> Icons.Filled.Battery4Bar
      livello <= 90 -> Icons.Filled.Battery6Bar
      else -> Icons.Filled.BatteryFull
    }

/**
 * Livello di batteria della fascia. dettaglio: testo aggiuntivo (es. "letta ieri 22:40").
 * Sotto il 20% diventa rosso con l'avviso.
 */
@Composable
fun RigaBatteria(livello: Int?, dettaglio: String? = null, colore: Color = MaterialTheme.colorScheme.onSurface) {
  val bassa = livello != null && livello <= BATTERIA_BASSA
  val c = if (bassa) MaterialTheme.colorScheme.error else colore
  Column {
    Row(verticalAlignment = Alignment.CenterVertically) {
      if (livello != null) Icon(iconaBatteria(livello), null, Modifier.size(18.dp), tint = c)
      Spacer(Modifier.width(6.dp))
      Text(
          if (livello == null) "Batteria fascia: si legge al collegamento"
          else "Batteria fascia: $livello%" + (dettaglio?.let { " · $it" } ?: ""),
          style = MaterialTheme.typography.bodySmall,
          color = c)
    }
    if (bassa) {
      Text(
          "Potrebbe spegnersi durante la notte: sostituisci la pila (CR2025).",
          style = MaterialTheme.typography.bodySmall,
          color = c)
    }
  }
}

fun quandoLetta(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ITALIAN))

// --- Fiore che respira -----------------------------------------------------------------------------

private val PETALO_A = Color(0xFF4DD0E1)
private val PETALO_B = Color(0xFF81C784)
private val SFONDO = Color(0xFF05080C)
private val TESTO = Color(0xFFCFD8DC)

/** SEGUI: il fiore segue il tuo respiro (accelerometro H10). GUIDA: tu segui il fiore, 6 respiri al minuto. */
enum class ModoFiore { SEGUI, GUIDA }

/** Apertura all'istante t, interpolata tra i campioni della linea (istanti crescenti). */
private fun interpola(linea: ArrayDeque<Pair<Long, Float>>, t: Long): Float? {
  if (linea.isEmpty()) return null
  if (t <= linea.first().first) return linea.first().second
  if (t >= linea.last().first) return linea.last().second
  for (i in linea.size - 1 downTo 1) {
    val (t0, v0) = linea[i - 1]
    val (t1, v1) = linea[i]
    if (t in t0..t1) return if (t1 == t0) v1 else v0 + (v1 - v0) * (t - t0) / (t1 - t0)
  }
  return null
}

/**
 * Fiore a schermo intero, in due modi.
 * - SEGUI: si apre e si chiude con il tuo respiro, letto dall'accelerometro della H10 sul torace
 *   (RespiroAcc): stessa fase e stessa ampiezza, un respiro profondo lo apre di piu'. I dati
 *   arrivano dalla fascia a pacchetti (circa uno al secondo): per un movimento fluido il fiore li
 *   riproduce con il ritardo di un pacchetto.
 * - GUIDA: come "Respira" di Apple Watch, il ritmo lo da' il fiore (6 respiri al minuto) e tu lo
 *   segui. Anche Apple Watch funziona cosi': l'animazione non legge il respiro, lo guida.
 * In entrambi i modi pulsa leggermente a ogni battito. Gira solo a schermo acceso.
 */
@Composable
fun FioreNotte(
    inRegistrazione: Boolean,
    inizioMs: Long?,
    batteria: Int?,
    onChiudi: () -> Unit,
) {
  var modo by rememberSaveable { mutableStateOf(ModoFiore.SEGUI) }
  val impulso = remember { Animatable(0f) }
  var bpm by remember { mutableStateOf<Int?>(null) }
  var ultimoBattito by remember { mutableLongStateOf(0L) }
  var ultimoAcc by remember { mutableLongStateOf(0L) }
  var tarato by remember { mutableStateOf(false) }
  var adesso by remember { mutableLongStateOf(System.currentTimeMillis()) }
  val linea = remember { ArrayDeque<Pair<Long, Float>>() }
  var ritardo by remember { mutableLongStateOf(1_200L) }
  var aperturaRespiro by remember { mutableFloatStateOf(0.5f) }
  // Punto 10: senza accelerometro (fasce non H10) il fiore segue il respiro letto dagli RR
  var aperturaRsa by remember { mutableFloatStateOf(0.5f) }
  var ultimoRsa by remember { mutableLongStateOf(0L) }
  val fascia by Fasce.sessione.collectAsState()
  val senzaRr = fascia?.senzaHrv == true

  LaunchedEffect(Unit) {
    val respiro = RespiroAcc()
    val cuore = RespiroRsa() // solo per i bpm, con il suo filtro degli artefatti
    launch {
      BioSleepDataSaver.battiti.collect { rr ->
        val t = System.currentTimeMillis()
        ultimoBattito = t
        respiro.battito(t, rr)
        cuore.aggiungi(rr)?.let {
          aperturaRsa = it
          ultimoRsa = t
        }
        cuore.bpm?.let { bpm = it }
        launch {
          impulso.snapTo(1f)
          impulso.animateTo(0f, tween(min(rr, 600), easing = LinearOutSlowInEasing))
        }
      }
    }
    launch {
      BioSleepDataSaver.accLive.collect { pacchetto ->
        if (pacchetto.isEmpty()) return@collect
        val arrivo = System.currentTimeMillis()
        for (c in pacchetto) {
          respiro.campione(c.tMs, c.x.toDouble(), c.y.toDouble(), c.z.toDouble())?.let { linea.addLast(c.tMs to it) }
        }
        while (linea.isNotEmpty() && linea.first().first < arrivo - 10_000) linea.removeFirst()
        // ritardo di riproduzione: quanto copre un pacchetto, piu' un margine per le irregolarita'
        val copertura = arrivo - pacchetto.first().tMs + 150
        ritardo = (0.8 * ritardo + 0.2 * copertura).toLong().coerceIn(300L, 3_000L)
        ultimoAcc = arrivo
        tarato = respiro.versoConfermato
      }
    }
    while (isActive) {
      withFrameMillis {}
      adesso = System.currentTimeMillis()
      interpola(linea, adesso - ritardo)?.let { aperturaRespiro = it }
    }
  }

  val guida by
      rememberInfiniteTransition(label = "guida")
          .animateFloat(
              0.15f,
              0.85f,
              infiniteRepeatable(tween(5_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
              label = "guida")
  val conBattito = adesso - ultimoBattito < 5_000
  val conRespiro = adesso - ultimoAcc < 5_000
  val conRsa = !senzaRr && adesso - ultimoRsa < 5_000
  val segue = modo == ModoFiore.SEGUI && (conRespiro || conRsa)
  val e = if (segue) (if (conRespiro) aperturaRespiro else aperturaRsa) else guida
  val p = if (conBattito) impulso.value else 0f

  Dialog(onDismissRequest = onChiudi, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Box(Modifier.fillMaxSize().background(SFONDO)) {
      IconButton(onClick = onChiudi, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
        Icon(Icons.Filled.Close, "Chiudi", tint = TESTO)
      }
      Column(
          Modifier.align(Alignment.Center).padding(24.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        Canvas(Modifier.size(300.dp)) {
          val raggioMax = size.minDimension / 2
          val raggioPetalo = raggioMax * (0.30f + 0.20f * e) * (1f + 0.05f * p)
          val distanza = raggioMax * 0.48f * e
          rotate(degrees = 45f * e) {
            for (i in 0 until 6) {
              val a = Math.toRadians(i * 60.0)
              val centroPetalo = center + Offset((cos(a) * distanza).toFloat(), (sin(a) * distanza).toFloat())
              drawCircle(if (i % 2 == 0) PETALO_A else PETALO_B, raggioPetalo, centroPetalo, alpha = 0.42f)
            }
          }
        }
        Text(
            when {
              !inRegistrazione -> "Collegamento alla fascia…"
              conBattito -> bpm?.let { "$it bpm" } ?: "Lettura del battito…"
              else -> "In attesa del battito: la fascia è indossata?"
            },
            color = TESTO,
            fontSize = 22.sp,
            fontWeight = FontWeight.Light)
        Text(
            when {
              modo == ModoFiore.GUIDA -> "Inspira mentre si apre, espira mentre si chiude"
              !inRegistrazione -> "Il fiore seguirà il tuo respiro appena la fascia è collegata"
              senzaRr -> "Questa fascia non misura gli intervalli RR: il fiore ti guida"
              !conRespiro && conRsa -> "Il fiore segue il tuo respiro, letto dal battito"
              !conRespiro -> "In attesa del respiro dalla fascia"
              !tarato -> "Il fiore segue il tuo respiro · taratura in corso"
              else -> "Il fiore segue il tuo respiro"
            },
            color = TESTO.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          for ((m, etichetta) in listOf(ModoFiore.SEGUI to "Segui il mio respiro", ModoFiore.GUIDA to "Guidami")) {
            FilterChip(
                selected = modo == m,
                onClick = { modo = m },
                label = { Text(etichetta) },
                colors =
                    FilterChipDefaults.filterChipColors(
                        labelColor = TESTO.copy(alpha = 0.7f),
                        selectedLabelColor = SFONDO,
                        selectedContainerColor = PETALO_A),
            )
          }
        }
        if (inizioMs != null && inRegistrazione) {
          val min = ((adesso - inizioMs) / 60_000).toInt()
          Text(
              "Notte in corso da ${min / 60}h${"%02d".format(min % 60)}",
              color = TESTO.copy(alpha = 0.7f),
              style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        RigaBatteria(batteria, colore = TESTO.copy(alpha = 0.8f))
      }
    }
  }
}
