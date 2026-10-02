package com.wboelens.polarrecorder.biosleep.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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

/** Ipnogramma a blocchi: una barra per ogni tratto continuo della stessa fase. */
@Composable
fun Hypnogram(stages: SleepStages, modifier: Modifier = Modifier) {
  val textMeasurer = rememberTextMeasurer()
  val labelStyle =
      MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
  val gridColor = MaterialTheme.colorScheme.outlineVariant
  val hyp = stages.hypnogram
  if (hyp.isEmpty()) return
  val startMs = stages.hypnoStartMs
  val endMs = startMs + hyp.length * SleepStages.EPOCH_MS

  Canvas(modifier) {
    val left = 64.dp.toPx()
    val right = 8.dp.toPx()
    val bottom = 22.dp.toPx()
    val plotW = size.width - left - right
    val rowH = (size.height - bottom) / ROWS.size
    val barH = rowH * 0.7f

    ROWS.forEachIndexed { r, _ ->
      val yMid = rowH * r + rowH / 2
      drawLine(gridColor, Offset(left, yMid), Offset(size.width - right, yMid), 1.dp.toPx())
      val label = textMeasurer.measure(ROW_LABELS[r], labelStyle)
      drawText(label, topLeft = Offset(0f, yMid - label.size.height / 2f))
    }

    // Barre: raggruppa le epoche consecutive della stessa fase
    val epochW = plotW / hyp.length
    var i = 0
    while (i < hyp.length) {
      var j = i
      while (j < hyp.length && hyp[j] == hyp[i]) j++
      val stage = Stage.fromCode(hyp[i])
      val r = ROWS.indexOf(stage)
      drawRect(
          color = STAGE_COLORS.getValue(stage),
          topLeft = Offset(left + i * epochW, rowH * r + (rowH - barH) / 2),
          size = Size((j - i) * epochW, barH),
      )
      i = j
    }

    // Orari sull'asse X
    for (k in 0..4) {
      val t = startMs + (endMs - startMs) * k / 4
      val label = textMeasurer.measure(hourLabel(t), labelStyle)
      val x = (left + plotW * k / 4f - label.size.width / 2f).coerceIn(0f, size.width - label.size.width)
      drawText(label, topLeft = Offset(x, size.height - label.size.height))
    }
  }
}
