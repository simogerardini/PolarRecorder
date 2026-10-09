package com.wboelens.polarrecorder.biosleep.ui

import com.wboelens.polarrecorder.biosleep.lingua.tr
import com.wboelens.polarrecorder.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.cervello.Lingua
import com.wboelens.polarrecorder.biosleep.cervello.RipianificaWorker
import java.util.Locale

/**
 * Impostazioni -> Lingua: lingua dell'app (Android 13+) e delle sedute sul calendario e
 * sull'orologio. Cambiando lingua Android ricrea la schermata: la domanda sul piano compare dopo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SezioneLingua() {
  val context = LocalContext.current.applicationContext
  val scelta = remember { Lingua.scelta(context) }
  var chiedi by remember { mutableStateOf(Lingua.ripianificaInSospeso(context)) }
  var messaggio by remember { mutableStateOf<String?>(null) }
  val effettiva = scelta ?: Lingua.daLocale(Locale.getDefault().language)

  fun cambia(nuova: String?) {
    if (nuova == scelta) return
    val dopo = nuova ?: Lingua.daLocale(Locale.getDefault().language)
    Lingua.salva(context, nuova, chiediRipianifica = dopo != effettiva)
    if (!Lingua.perAppDisponibile()) chiedi = dopo != effettiva // senza ricreazione: subito
  }

  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(stringResource(R.string.lingua_lingua), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      FilterChip(selected = scelta == null, onClick = { cambia(null) }, label = { Text(stringResource(R.string.lingua_come_il_telefono)) })
      for ((codice, nome) in Lingua.LINGUE) FilterChip(selected = scelta == codice, onClick = { cambia(codice) }, label = { Text(tr(nome)) })
    }
    Text(
        tr(if (Lingua.perAppDisponibile()) "Lingua dell'app e delle sedute che il coach scrive sul calendario e sull'orologio."
        else "Lingua delle sedute che il coach scrive sul calendario e sull'orologio. L'app segue la lingua del telefono."),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (Lingua.calendarioInInglese(effettiva)) {
      Text(
          stringResource(R.string.lingua_le_sedute_sull_orologio),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary)
    }
    messaggio?.let { Text(tr(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
  }

  if (chiedi) {
    AlertDialog(
        onDismissRequest = { chiedi = false; messaggio = "Varrà dalla prossima pianificazione" },
        title = { Text(stringResource(R.string.lingua_aggiorno_il_piano_della)) },
        text = { Text(stringResource(R.string.lingua_le_sedute_da_oggi)) },
        confirmButton = {
          TextButton(onClick = {
            chiedi = false
            RipianificaWorker.avvia(context)
            messaggio = "Ripianificazione in corso: il riepilogo arriverà con la notifica del coach"
          }) { Text(stringResource(R.string.lingua_si_aggiorna)) }
        },
        dismissButton = { TextButton(onClick = { chiedi = false; messaggio = "Varrà dalla prossima pianificazione" }) { Text(stringResource(R.string.lingua_no)) } },
    )
  }
}
