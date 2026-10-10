package com.wboelens.polarrecorder.biosleep.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.checkin.Checkin
import com.wboelens.polarrecorder.biosleep.lingua.tr
import com.wboelens.polarrecorder.biosleep.tag.Vocabolario
import com.wboelens.polarrecorder.biosleep.training.EventoCal
import com.wboelens.polarrecorder.biosleep.ui.allenamento.DateIt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Oggi: check-in della prima seduta del coach passata senza risposta. Compare solo in modalita'
 * degradata o con "Chiedi la fatica dopo ogni seduta" acceso. "Piu' tardi" la nasconde fino alla
 * prossima apertura dell'app; dopo 7 giorni non si chiede piu'.
 */
@Composable
fun CardCheckin() {
  val context = LocalContext.current.applicationContext
  val scope = rememberCoroutineScope()
  var versione by remember { mutableIntStateOf(0) }
  val rinviati = remember { mutableStateListOf<String>() }
  val sedute by
      produceState<List<EventoCal>?>(null, versione) {
        value = withContext(Dispatchers.IO) { runCatching { if (Checkin.richiesto(context)) Checkin.daChiedere(context) else emptyList() }.getOrDefault(emptyList()) }
      }
  val lista = sedute?.filter { it.externalId !in rinviati } ?: return
  val e = lista.firstOrNull() ?: return
  val id = e.externalId ?: return
  key(id) {
    var stato by remember { mutableStateOf<String?>(null) }
    var rpe by remember { mutableStateOf<Int?>(null) }
    var minuti by remember { mutableStateOf(((e.durataS ?: 0) / 60).takeIf { it > 0 }?.toString() ?: "") }
    var tag by remember { mutableStateOf(setOf<String>()) }
    val grigio = MaterialTheme.colorScheme.onSurfaceVariant
    Card(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Com'è andata?"), style = MaterialTheme.typography.titleMedium)
        Text("${DateIt.breve(e.data)} · ${e.nome}", style = MaterialTheme.typography.bodyLarge)
        if (Checkin.degradato(context))
            Text(
                tr("Intervals.icu non legge le attività: il coach ricostruisce il carico dalle tue risposte."),
                style = MaterialTheme.typography.bodySmall,
                color = grigio)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          for ((s, testo) in listOf(Checkin.FATTA to "Fatta", Checkin.MODIFICATA to "Modificata", Checkin.SALTATA to "Saltata"))
              FilterChip(selected = stato == s, onClick = { stato = s }, label = { Text(tr(testo)) })
        }
        if (stato == Checkin.FATTA || stato == Checkin.MODIFICATA) {
          Text(tr("Fatica percepita (1 = facilissima, 10 = massimo sforzo)"), style = MaterialTheme.typography.bodySmall, color = grigio)
          Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (v in 1..10) FilterChip(selected = rpe == v, onClick = { rpe = v }, label = { Text("$v") })
          }
          if (stato == Checkin.MODIFICATA)
              OutlinedTextField(
                  value = minuti,
                  onValueChange = { minuti = it.filter(Char::isDigit).take(3) },
                  label = { Text(tr("Minuti effettivi")) },
                  singleLine = true,
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
          ChipTag(Vocabolario.SEDUTA, tag) { tag = it }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
          TextButton(onClick = { rinviati += id }) { Text(tr("Più tardi")) }
          val pronto =
              stato == Checkin.SALTATA || (stato == Checkin.FATTA && rpe != null) ||
                  (stato == Checkin.MODIFICATA && rpe != null && (minuti.toIntOrNull() ?: 0) > 0)
          Button(
              enabled = pronto,
              onClick = {
                val s = stato ?: return@Button
                val r =
                    Checkin.Risposta(
                        stato = s,
                        rpe = rpe.takeIf { s != Checkin.SALTATA },
                        minuti = minuti.toIntOrNull().takeIf { s == Checkin.MODIFICATA },
                        data = e.data.toString(),
                        tag = if (s == Checkin.SALTATA) emptySet() else tag)
                scope.launch(Dispatchers.IO) {
                  Checkin.salva(context, id, r)
                  versione++
                }
              }) {
                Text(tr("Salva"))
              }
        }
        if (lista.size > 1) Text(tr("Altre ${lista.size - 1} sedute da registrare"), style = MaterialTheme.typography.bodySmall, color = grigio)
      }
    }
  }
}

/** Impostazioni: chiedere il check-in anche quando le attivita' sono leggibili. */
@Composable
fun InterruttoreCheckin() {
  val context = LocalContext.current.applicationContext
  var acceso by remember { mutableStateOf(Checkin.sempre(context)) }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(tr("Chiedi la fatica dopo ogni seduta"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
      Switch(checked = acceso, onCheckedChange = { acceso = it; Checkin.impostaSempre(context, it) })
    }
    Text(
        tr(
            if (acceso) "Acceso: dopo ogni seduta del coach, Oggi chiede se l'hai fatta e con che fatica."
            else "Spento: la chiede solo se Intervals.icu non riesce a leggere le attività."),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}
