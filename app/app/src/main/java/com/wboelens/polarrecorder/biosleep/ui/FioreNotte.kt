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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.wboelens.polarrecorder.biosleep.live.RespiroRsa
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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

/**
 * Fiore a schermo intero stile "Respira" di Apple Watch, guidato dalla fascia:
 * - si apre e si chiude con il respiro letto dagli RR (RespiroRsa);
 * - pulsa leggermente a ogni battito, al ritmo degli RR ricevuti;
 * - senza battiti (collegamento in corso, fascia staccata) respira piano da solo, a 6 atti al minuto.
 * Gira solo a schermo acceso: allo spegnimento l'animazione si ferma, la registrazione no.
 */
@Composable
fun FioreNotte(
    inRegistrazione: Boolean,
    inizioMs: Long?,
    batteria: Int?,
    onChiudi: () -> Unit,
) {
  val apertura = remember { Animatable(0.4f) }
  val impulso = remember { Animatable(0f) }
  var bpm by remember { mutableStateOf<Int?>(null) }
  var ultimoBattito by remember { mutableLongStateOf(0L) }
  var adesso by remember { mutableLongStateOf(System.currentTimeMillis()) }

  LaunchedEffect(Unit) {
    while (true) {
      adesso = System.currentTimeMillis()
      delay(1_000)
    }
  }
  LaunchedEffect(Unit) {
    val modello = RespiroRsa()
    // I battiti arrivano a pacchetti (circa uno al secondo): li si "suona" uno dopo l'altro, ognuno
    // lungo quanto il suo RR, cosi' l'impulso cade al ritmo vero del cuore.
    val coda = Channel<Int>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    launch { BioSleepDataSaver.battiti.collect { coda.trySend(it) } }
    for (rr in coda) {
      ultimoBattito = System.currentTimeMillis()
      modello.aggiungi(rr)?.let { obiettivo ->
        launch { apertura.animateTo(obiettivo, tween(rr, easing = FastOutSlowInEasing)) }
      }
      modello.bpm?.let { bpm = it }
      launch {
        impulso.snapTo(1f)
        impulso.animateTo(0f, tween(min(rr, 600), easing = LinearOutSlowInEasing))
      }
      delay((rr * 0.95).toLong()) // leggermente piu' veloce del cuore: la coda non si accumula
    }
  }

  val calmo by
      rememberInfiniteTransition(label = "respiro_calmo")
          .animateFloat(0.15f, 0.85f, infiniteRepeatable(tween(5_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "calmo")
  val conDati = adesso - ultimoBattito < 5_000
  val e = if (conDati) apertura.value else calmo
  val p = if (conDati) impulso.value else 0f

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
              conDati -> bpm?.let { "$it bpm" } ?: "Lettura del battito…"
              else -> "In attesa del battito: la fascia è indossata?"
            },
            color = TESTO,
            fontSize = 22.sp,
            fontWeight = FontWeight.Light)
        Text(
            if (conDati) "Il fiore respira con te: segue il tuo battito" else "Respira con il fiore",
            color = TESTO.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall)
        if (inizioMs != null && inRegistrazione) {
          val min = ((adesso - inizioMs) / 60_000).toInt()
          Text("Notte in corso da ${min / 60}h${"%02d".format(min % 60)}", color = TESTO.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        RigaBatteria(batteria, colore = TESTO.copy(alpha = 0.8f))
      }
    }
  }
}
