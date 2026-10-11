package com.wboelens.polarrecorder.biosleep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.cervello.AttesaTag
import com.wboelens.polarrecorder.biosleep.lingua.tr

/** Impostazioni (coach acceso): quanto il coach aspetta i tag del mattino. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SceltaAttesaTag() {
  val context = LocalContext.current
  val scelta by AttesaTag.flusso(context).collectAsState()
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(tr("Attesa dei tag del mattino"), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      for (m in AttesaTag.OPZIONI) {
        FilterChip(
            selected = m == scelta,
            onClick = { AttesaTag.imposta(context.applicationContext, m) },
            label = { Text(if (m == 0) tr("Subito") else tr("$m min")) })
      }
    }
    Text(
        tr(
            if (scelta == 0) "Subito: il coach parte senza aspettare e non chiede i tag; quelli aggiunti dopo valgono dal run successivo."
            else "Dopo l'invio della notte il coach aspetta i tuoi tag prima di ripianificare; parte appena li salvi."),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}
