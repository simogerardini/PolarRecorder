package com.wboelens.polarrecorder.biosleep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.cervello.ModalitaCoach
import com.wboelens.polarrecorder.biosleep.lingua.tr

/** true se il coach di allenamento e' acceso; le schermate si aggiornano quando cambia. */
@Composable
fun coachAttivo(): Boolean {
  val context = LocalContext.current
  val v by ModalitaCoach.flusso(context).collectAsState()
  return v ?: true
}

/** Impostazioni: interruttore del coach con la spiegazione di cosa cambia. */
@Composable
fun InterruttoreCoach() {
  val context = LocalContext.current
  val acceso = coachAttivo()
  var riacceso by remember { mutableStateOf(false) }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(tr("Coach di allenamento"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
      Switch(
          checked = acceso,
          onCheckedChange = {
            ModalitaCoach.imposta(context.applicationContext, it)
            riacceso = it
          })
    }
    Text(
        tr(
            if (acceso) "Acceso: il coach pianifica le sedute su Intervals.icu e le adatta ai tuoi dati della notte."
            else "Spento: solo i dati biometrici della notte (HRV, FC a riposo, sonno, età biologica) con i loro semafori. Nessuna seduta viene scritta su Intervals.icu; quelle già scritte restano."),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (acceso && riacceso)
        Text(
            tr("Il coach riparte con la prossima notte, o subito con Ripianifica dal profilo atleta."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary)
  }
}
