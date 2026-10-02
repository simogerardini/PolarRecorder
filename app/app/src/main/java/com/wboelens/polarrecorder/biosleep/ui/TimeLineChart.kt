package com.wboelens.polarrecorder.biosleep.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * Grafico a linea semplice, disegnato a mano (nessuna libreria esterna).
 * times: istanti in ms; values: valori (null = dato mancante, la linea si interrompe).
 */
@Composable
fun TimeLineChart(
    times: List<Long>,
    values: List<Double?>,
    lineColor: Color,
    modifier: Modifier = Modifier,
) {
  val textMeasurer = rememberTextMeasurer()
  val labelStyle =
      MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
  val gridColor = MaterialTheme.colorScheme.outlineVariant

  val valid = values.filterNotNull()
  if (times.size < 2 || valid.isEmpty() || times.first() == times.last()) {
    Box(modifier, contentAlignment = Alignment.Center) {
      Text("Dati insufficienti per il grafico", style = labelStyle)
    }
    return
  }

  val dataMin = valid.min()
  val dataMax = valid.max()
  val range = dataMax - dataMin
  val pad = max(range * 0.1, 1.0)
  // Con escursioni piccole (es. 60,4-61,2 bpm) i numeri interi si ripeterebbero: serve un decimale
  val labelDecimals = if (range < 5) 1 else 0
  val yMin = dataMin - pad
  val yMax = dataMax + pad
  val tMin = times.first()
  val tMax = times.last()

  Canvas(modifier) {
    val left = 36.dp.toPx()
    val right = 12.dp.toPx()
    val top = 8.dp.toPx()
    val bottom = 22.dp.toPx()
    val plotW = size.width - left - right
    val plotH = size.height - top - bottom

    fun xOf(t: Long): Float = left + plotW * ((t - tMin).toFloat() / (tMax - tMin).toFloat())

    fun yOf(v: Double): Float = top + plotH * (1f - ((v - yMin) / (yMax - yMin)).toFloat())

    // Griglia orizzontale ed etichette: minimo, medio, massimo dei dati
    for (v in listOf(dataMin, (dataMin + dataMax) / 2, dataMax)) {
      val y = yOf(v)
      drawLine(gridColor, Offset(left, y), Offset(size.width - right, y), strokeWidth = 1.dp.toPx())
      val label = textMeasurer.measure(fmt(v, labelDecimals), labelStyle)
      drawText(label, topLeft = Offset(0f, y - label.size.height / 2f))
    }

    // Etichette orarie sull'asse X (5 tacche)
    for (k in 0..4) {
      val t = tMin + (tMax - tMin) * k / 4
      val label = textMeasurer.measure(hourLabel(t), labelStyle)
      val x = (xOf(t) - label.size.width / 2f).coerceIn(0f, size.width - label.size.width)
      drawText(label, topLeft = Offset(x, size.height - label.size.height))
    }

    // Linea dei dati, interrotta dove mancano valori
    val path = Path()
    var penDown = false
    for (i in times.indices) {
      val v = values[i]
      if (v == null) {
        penDown = false
        continue
      }
      val x = xOf(times[i])
      val y = yOf(v)
      if (penDown) path.lineTo(x, y) else path.moveTo(x, y)
      penDown = true
      drawCircle(lineColor, radius = 2.dp.toPx(), center = Offset(x, y))
    }
    drawPath(
        path,
        lineColor,
        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
  }
}
