package com.wboelens.polarrecorder.biosleep.ui

import com.wboelens.polarrecorder.R
import androidx.compose.ui.res.stringResource
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.cervello.EsitoSoglie
import com.wboelens.polarrecorder.biosleep.cervello.Soglie
import com.wboelens.polarrecorder.biosleep.cervello.SoglieRepo
import com.wboelens.polarrecorder.biosleep.intervals.OAuthIntervals
import com.wboelens.polarrecorder.biosleep.riepilogo.RiepilogoDb
import com.wboelens.polarrecorder.biosleep.ui.allenamento.DateIt
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun apri(context: Context, link: String) {
  try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  } catch (e: ActivityNotFoundException) {
    // nessun browser: niente da fare
  }
}

/** La riga degli avvisi di oggi sulle soglie ("soglie da completare su Intervals.icu: ..."). */
@Composable
private fun avvisoSoglieDiOggi(): String? {
  val context = LocalContext.current.applicationContext
  val versione by RiepilogoDb.versione.collectAsState()
  val avviso by
      produceState<String?>(null, versione) {
        value =
            withContext(Dispatchers.IO) {
              RiepilogoDb.get(context).leggi(LocalDate.now().toString())?.avvisi?.lines()
                  ?.firstOrNull { it.contains("soglie da completare", ignoreCase = true) }?.trim()
            }
      }
  return avviso
}

/**
 * Card "Soglie da completare": per ogni soglia mancante disciplina, campo e conseguenza; le
 * bloccanti in evidenza, le consigliate in grigio. Pulsante per sistemarle su Intervals.icu.
 * Con il permesso mancante chiede di ricollegare Intervals.icu.
 */
@Composable
fun CardSoglie(e: EsitoSoglie?, avviso: String? = null) {
  val context = LocalContext.current
  when {
    e?.esito == Soglie.PERMESSO_MANCANTE ->
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.soglie_soglie_non_controllabili), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                stringResource(R.string.soglie_il_collegamento_a_intervals),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer)
            Button(onClick = { OAuthIntervals.avvia(context) }) { Text(stringResource(R.string.soglie_ricollega_intervals_icu)) }
          }
        }
    e?.daCompletare == true || avviso != null ->
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.soglie_soglie_da_completare), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            val mancanti = e?.takeIf { it.daCompletare }?.mancanti.orEmpty().sortedBy { !it.bloccante }
            if (mancanti.isEmpty()) {
              avviso?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer) }
            }
            for (m in mancanti) {
              val colore = if (m.bloccante) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
              Column {
                Text(
                    m.titolo + if (m.bloccante) "" else " (consigliato)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (m.bloccante) FontWeight.Bold else FontWeight.Normal,
                    color = colore)
                Text(m.effetto.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall, color = colore)
              }
            }
            Button(onClick = { apri(context, e?.link ?: "https://intervals.icu/settings") }) { Text(stringResource(R.string.soglie_apri_intervals_icu)) }
          }
        }
  }
}

/** Nella schermata Oggi: controllo al massimo una volta al giorno, card solo se serve. */
@Composable
fun SoglieOggi() {
  val context = LocalContext.current.applicationContext
  val stato by SoglieRepo.stato.collectAsState()
  val avviso = avvisoSoglieDiOggi()
  LaunchedEffect(Unit) { withContext(Dispatchers.IO) { SoglieRepo.carica(context); SoglieRepo.controlla(context, forza = false) } }
  // il riepilogo di oggi segnala soglie mancanti ma l'ultimo controllo no: si ricontrolla
  LaunchedEffect(avviso) {
    if (avviso != null && stato?.daCompletare != true) withContext(Dispatchers.IO) { SoglieRepo.controlla(context, forza = true) }
  }
  CardSoglie(stato, avviso)
}

/** Nel Profilo: controllo a ogni apertura; con le soglie a posto, i valori letti da Intervals.icu. */
@Composable
fun SezioneSoglie() {
  val context = LocalContext.current.applicationContext
  val stato by SoglieRepo.stato.collectAsState()
  val avviso = avvisoSoglieDiOggi()
  LaunchedEffect(Unit) { withContext(Dispatchers.IO) { SoglieRepo.carica(context); SoglieRepo.controlla(context, forza = true) } }
  val ultimoTest = rememberUltimoTest()
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(stringResource(R.string.soglie_soglie_su_intervals_icu), style = MaterialTheme.typography.titleSmall)
    ultimoTest?.let {
      Text(stringResource(R.string.soglie_ultimo_test, it.nome.toString(), (DateIt.breve(it.data)).toString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val e = stato
    if (e == null || e.esito == Soglie.ERRORE) {
      Text(
          e?.errore?.let { "Controllo non riuscito: $it" } ?: "Controllo in corso…",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    CardSoglie(e, avviso?.takeIf { e?.esito != Soglie.OK })
    if (e != null && e.esito == Soglie.OK) {
      for ((disciplina, campi) in e.valori) {
        Text("${Soglie.disciplina(disciplina)}: ${Soglie.valoriLeggibili(campi)}", style = MaterialTheme.typography.bodyMedium)
      }
      for (m in e.mancanti) {
        Text(
            stringResource(R.string.soglie_consigliato, m.titolo.toString(), m.effetto.toString()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      OutlinedButton(onClick = { apri(context, e.link) }) { Text(stringResource(R.string.soglie_apri_intervals_icu)) }
    }
  }
}
