package com.wboelens.polarrecorder.biosleep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.cervello.Cervello
import com.wboelens.polarrecorder.biosleep.lingua.tr
import com.wboelens.polarrecorder.biosleep.tag.TagAuto
import com.wboelens.polarrecorder.biosleep.tag.TagAutomatico
import com.wboelens.polarrecorder.biosleep.tag.TagDb
import com.wboelens.polarrecorder.biosleep.tag.Vocabolario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tag proposti dal coach per la notte del giorno [data] (riepilogo "tag_automatici"):
 * "Conferma" lo rende un tag di giorno, "Non era cosi'" lo nasconde e lo segnala al cervello.
 * Nessuna riga se non ci sono proposte.
 */
@Composable
fun ProposteTag(data: String) {
  val context = LocalContext.current.applicationContext
  // modalita' demo: le proposte vengono dai riepiloghi veri e il rifiuto si salva: niente proposte
  if (com.wboelens.polarrecorder.biosleep.demo.DemoParte3.attiva(context)) return
  val vTag by TagDb.versione.collectAsState()
  val vAuto by TagAuto.versione.collectAsState()
  val proposte by produceState(emptyList<TagAutomatico>(), data, vTag, vAuto) {
    value = withContext(Dispatchers.IO) {
      runCatching {
        TagAuto.daMostrare(TagAuto.dalRiepilogo(Cervello.cartella(context)), data, TagAuto.rifiutati(context), TagDb.get(context).giorno(data))
      }.getOrDefault(emptyList())
    }
  }
  if (proposte.isEmpty()) return
  val scope = rememberCoroutineScope()
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(tr("Proposti dal coach"), style = MaterialTheme.typography.labelLarge)
    for (p in proposte) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(tr(Vocabolario.etichetta(p.tag)), style = MaterialTheme.typography.bodyMedium)
          TagAuto.testo(p)?.let {
            Text(tr(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
        TextButton(onClick = { scope.launch(Dispatchers.IO) { TagAuto.conferma(context, p) } }) { Text(tr("Conferma")) }
        TextButton(onClick = { scope.launch(Dispatchers.IO) { TagAuto.rifiuta(context, p) } }) { Text(tr("Non era così")) }
      }
    }
  }
}
