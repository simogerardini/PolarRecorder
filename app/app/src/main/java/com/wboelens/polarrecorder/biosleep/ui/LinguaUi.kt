package com.wboelens.polarrecorder.biosleep.ui

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

/** Impostazioni -> Lingua: lingua delle sedute sul calendario e sull'orologio. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SezioneLingua() {
  val context = LocalContext.current.applicationContext
  var scelta by remember { mutableStateOf(Lingua.scelta(context)) }
  var chiedi by remember { mutableStateOf(false) }
  var messaggio by remember { mutableStateOf<String?>(null) }
  val effettiva = scelta ?: Lingua.daLocale(java.util.Locale.getDefault().language)

  fun cambia(nuova: String?) {
    if (nuova == scelta) return
    val prima = effettiva
    scelta = nuova
    Lingua.salva(context, nuova)
    messaggio = null
    if ((nuova ?: Lingua.daLocale(java.util.Locale.getDefault().language)) != prima) chiedi = true
  }

  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text("Lingua", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      FilterChip(selected = scelta == null, onClick = { cambia(null) }, label = { Text("Come il telefono") })
      for ((codice, nome) in Lingua.LINGUE) FilterChip(selected = scelta == codice, onClick = { cambia(codice) }, label = { Text(nome) })
    }
    Text(
        "Vale per le sedute che il coach scrive sul calendario e sull'orologio: nomi, note degli step, test. " +
            "L'app per il momento è in italiano.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (Lingua.calendarioInInglese(effettiva)) {
      Text(
          "Le sedute sull'orologio saranno in inglese: molti orologi non mostrano i caratteri cinesi.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary)
    }
    messaggio?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
  }

  if (chiedi) {
    AlertDialog(
        onDismissRequest = { chiedi = false; messaggio = "Varrà dalla prossima pianificazione" },
        title = { Text("Aggiorno il piano della settimana nella nuova lingua?") },
        text = { Text("Le sedute da oggi a domenica verranno riscritte. I giorni passati non si toccano.") },
        confirmButton = {
          TextButton(onClick = {
            chiedi = false
            RipianificaWorker.avvia(context)
            messaggio = "Ripianificazione in corso: il riepilogo arriverà con la notifica del coach"
          }) { Text("Sì, aggiorna") }
        },
        dismissButton = { TextButton(onClick = { chiedi = false; messaggio = "Varrà dalla prossima pianificazione" }) { Text("No") } },
    )
  }
}
