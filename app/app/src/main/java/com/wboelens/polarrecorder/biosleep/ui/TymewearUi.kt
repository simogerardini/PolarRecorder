package com.wboelens.polarrecorder.biosleep.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.cervello.SogliaVt
import com.wboelens.polarrecorder.biosleep.cervello.TestTymewear
import com.wboelens.polarrecorder.biosleep.cervello.Tymewear
import com.wboelens.polarrecorder.biosleep.lingua.tr
import com.wboelens.polarrecorder.biosleep.ui.allenamento.DateIt
import java.time.LocalDate

/**
 * Profilo atleta: soglie ventilatorie Tymewear (VT1/VT2) per bici e corsa, inserite a mano.
 * Ogni modifica a mano rende il test "manuale" anche se era stato rilevato dal Garmin.
 */
@Composable
fun SezioneTymewear(t: Tymewear, stryd: Boolean, onCambia: (Tymewear) -> Unit) {
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text("Tymewear", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
      Switch(checked = t.attivo, onCheckedChange = { onCambia(t.copy(attivo = it)) })
    }
    if (!t.attivo) return@Column
    Text(
        tr("Soglie ventilatorie dal test Tymewear (VT1 e VT2). Il coach le usa come tetto per le sedute aerobiche; FTP e LTHR non vengono cambiate."),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    for (d in Tymewear.DISCIPLINE) {
      TestDisciplina(d, t.test(d) ?: TestTymewear(), usaWatt = d == "bici" || stryd) { onCambia(t.con(d, it)) }
    }
  }
}

@Composable
private fun TestDisciplina(d: String, test: TestTymewear, usaWatt: Boolean, onCambia: (TestTymewear) -> Unit) {
  val activity = LocalContext.current
  // modifica a mano = "manuale"
  fun cambia(x: TestTymewear) = onCambia(x.copy(fonte = Tymewear.MANUALE))
  Text(tr(if (d == "bici") "Bici" else "Corsa"), style = MaterialTheme.typography.labelLarge)
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedButton(onClick = {
      val o = test.data?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
      DatePickerDialog(activity, { _, y, m, g -> cambia(test.copy(data = LocalDate.of(y, m + 1, g).toString())) },
          o.year, o.monthValue - 1, o.dayOfMonth).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
    }) {
      Text(test.data?.let { runCatching { DateIt.lunga(LocalDate.parse(it)) }.getOrDefault(it) } ?: tr("Data del test"))
    }
    if (test.data != null) {
      Text(
          tr(if (test.fonte == Tymewear.AUTO) "rilevato dal Garmin" else "inserito a mano"),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
  RigaVt("VT1", test.vt1, d, usaWatt) { cambia(test.copy(vt1 = it)) }
  RigaVt("VT2", test.vt2, d, usaWatt) { cambia(test.copy(vt2 = it)) }
}

@Composable
private fun RigaVt(nome: String, v: SogliaVt, d: String, usaWatt: Boolean, onCambia: (SogliaVt) -> Unit) {
  var fc by remember(nome, d) { mutableStateOf(v.fc?.let { "%.0f".format(it) }.orEmpty()) }
  var sec by remember(nome, d, usaWatt) {
    mutableStateOf(if (usaWatt) v.w?.let { "%.0f".format(it) }.orEmpty() else Tymewear.passoTxt(v.passo))
  }
  var ve by remember(nome, d) { mutableStateOf(v.ve?.let { "%.0f".format(it) }.orEmpty()) }
  fun num(s: String) = s.replace(',', '.').toDoubleOrNull()
  fun aggiorna() =
      onCambia(
          if (usaWatt) SogliaVt(num(fc), num(sec), null, num(ve))
          else SogliaVt(num(fc), null, Tymewear.passo(sec), num(ve)))
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(nome, Modifier.width(36.dp), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(fc, { fc = it; aggiorna() }, Modifier.weight(1f), singleLine = true,
        label = { Text(tr("FC (bpm)")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    OutlinedTextField(sec, { sec = it; aggiorna() }, Modifier.weight(1f), singleLine = true,
        label = { Text(if (usaWatt) tr("Potenza (W)") else tr("Passo (min/km)")) },
        keyboardOptions = KeyboardOptions(keyboardType = if (usaWatt) KeyboardType.Number else KeyboardType.Text))
    OutlinedTextField(ve, { ve = it; aggiorna() }, Modifier.weight(1f), singleLine = true,
        label = { Text(tr("VE (L/min)")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
  }
}
