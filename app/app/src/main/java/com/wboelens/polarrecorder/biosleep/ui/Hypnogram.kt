package com.wboelens.polarrecorder.biosleep.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.SleepStages
import com.wboelens.polarrecorder.biosleep.Stage

/** Colori delle fasi, usati anche nelle schede dei minuti. */
val STAGE_COLORS =
    mapOf(
        Stage.WAKE to Color(0xFFFFA726),
        Stage.REM to Color(0xFF26C6DA),
        Stage.LIGHT to Color(0xFF64B5F6),
        Stage.DEEP to Color(0xFF3949AB),
    )

/** Ordine delle righe dall'alto: veglia, REM, leggero, profondo (come in un ipnogramma clinico). */
private val ROWS = listOf(Stage.WAKE, Stage.REM, Stage.LIGHT, Stage.DEEP)
private val ROW_LABELS = listOf("Veglia", "REM", "Leggero", "Profondo")

/** Solo per il disegno: blocchi da 5 minuti (10 epoche), fase prevalente in ciascuno. */
private const val DISPLAY_EPOCHS = 10

private data class Segment(val stage: Stage, val start: Int, val end: Int) // in blocchi

/**
 * Riduce l'ipnogramma (epoche da 30 s) a blocchi da 5 minuti con la fase prevalente e unisce i
 * blocchi uguali consecutivi. Cambia solo il disegno: minuti e statistiche restano quelli
 * calcolati sulle epoche da 30 s. Senza questo passaggio un singolo minuto di REM dentro il
 * leggero produrrebbe un frammento quasi invisibile.
 */
private fun segments(hyp: String): List<Segment> {
  val blocks = (hyp.length + DISPLAY_EPOCHS - 1) / DISPLAY_EPOCHS
  val stages =
      List(blocks) { b ->
        val chunk = hyp.substring(b * DISPLAY_EPOCHS, minOf(hyp.length, (b + 1) * DISPLAY_EPOCHS))
        val counts = chunk.groupingBy { it }.eachCount()
        // a parita' vince la fase "piu' sveglia" (ordine delle righe), come nei grafici clinici
        val best = ROWS.maxBy { s -> (counts[s.code] ?: 0) * 10 - ROWS.indexOf(s) }
        best
      }
  val out = mutableListOf<Segment>()
  var i = 0
  while (i < stages.size) {
    var j = i
    while (j < stages.size && stages[j] == stages[i]) j++
    out += Segment(stages[i], i, j)
    i = j
  }
  return out
}

/**
 * Ipnogramma a gradini continui: ogni fase e' una barra arrotondata sulla sua riga e le barre
 * consecutive sono unite da un raccordo verticale sfumato dal colore della fase precedente a
 * quello della successiva, cosi' la notte si legge come un'unica linea.
 */
@Composable
fun Hypnogram(stages: SleepStages, modifier: Modifier = Modifier) {
  val textMeasurer = rememberTextMeasurer()
  val labelStyle =
      MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
  val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
  val hyp = stages.hypnogram
  if (hyp.isEmpty()) return
  val segs = remember(hyp) { segments(hyp) }
  val blocks = (hyp.length + DISPLAY_EPOCHS - 1) / DISPLAY_EPOCHS
  val startMs = stages.hypnoStartMs
  val endMs = startMs + hyp.length * SleepStages.EPOCH_MS

  Canvas(modifier) {
    val left = 64.dp.toPx()
    val right = 8.dp.toPx()
    val bottom = 22.dp.toPx()
    val plotW = size.width - left - right
    val rowH = (size.height - bottom) / ROWS.size
    val barH = rowH * 0.42f
    val radius = CornerRadius(barH / 3, barH / 3)
    val connectorW = 3.dp.toPx()
    val blockW = plotW / blocks

    fun rowTop(stage: Stage) = rowH * ROWS.indexOf(stage) + (rowH - barH) / 2

    // Righe guida ed etichette
    ROWS.forEachIndexed { r, _ ->
      val yMid = rowH * r + rowH / 2
      drawLine(gridColor, Offset(left, yMid), Offset(size.width - right, yMid), 1.dp.toPx())
      val label = textMeasurer.measure(ROW_LABELS[r], labelStyle)
      drawText(label, topLeft = Offset(0f, yMid - label.size.height / 2f))
    }

    // 1. Raccordi verticali (disegnati prima, cosi' le barre li coprono alle estremita')
    for (k in 1 until segs.size) {
      val a = segs[k - 1]
      val b = segs[k]
      val x = left + b.start * blockW - connectorW / 2
      val ya = rowTop(a.stage) + barH / 2
      val yb = rowTop(b.stage) + barH / 2
      val top = minOf(ya, yb)
      val height = kotlin.math.abs(yb - ya)
      val colors =
          if (ya < yb) listOf(STAGE_COLORS.getValue(a.stage), STAGE_COLORS.getValue(b.stage))
          else listOf(STAGE_COLORS.getValue(b.stage), STAGE_COLORS.getValue(a.stage))
      drawRect(
          brush = Brush.verticalGradient(colors, startY = top, endY = top + height),
          topLeft = Offset(x, top),
          size = Size(connectorW, height),
      )
    }

    // 2. Barre delle fasi (si allungano di mezzo raccordo per agganciarsi senza spazi)
    for (s in segs) {
      val x0 = (left + s.start * blockW - connectorW / 2).coerceAtLeast(left)
      val x1 = (left + s.end * blockW + connectorW / 2).coerceAtMost(size.width - right)
      drawRoundRect(
          color = STAGE_COLORS.getValue(s.stage),
          topLeft = Offset(x0, rowTop(s.stage)),
          size = Size(x1 - x0, barH),
          cornerRadius = radius,
      )
    }

    // Orari sull'asse X: inizio, tre intermedi, fine
    for (k in 0..4) {
      val t = startMs + (endMs - startMs) * k / 4
      val label = textMeasurer.measure(hourLabel(t), labelStyle)
      val x = (left + plotW * k / 4f - label.size.width / 2f).coerceIn(0f, size.width - label.size.width)
      drawText(label, topLeft = Offset(x, size.height - label.size.height))
    }
  }
}
