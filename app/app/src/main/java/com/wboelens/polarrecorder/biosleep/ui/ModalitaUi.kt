package com.wboelens.polarrecorder.biosleep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.cervello.Cervello
import com.wboelens.polarrecorder.biosleep.cervello.EsitoPulizia
import com.wboelens.polarrecorder.biosleep.cervello.ModalitaCoach
import com.wboelens.polarrecorder.biosleep.demo.DemoParte3
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsSettings
import com.wboelens.polarrecorder.biosleep.lingua.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** true se il coach di allenamento e' acceso; le schermate si aggiornano quando cambia. */
@Composable
fun coachAttivo(): Boolean {
  val context = LocalContext.current
  val v by ModalitaCoach.flusso(context).collectAsState()
  return v ?: true
}

/**
 * Impostazioni: interruttore del coach con la spiegazione di cosa cambia.
 * Spegnendo: prova di pulizia (sedute del coach da oggi, non ancora svolte); se ce ne sono, si chiede
 * conferma e poi si tolgono da Intervals.icu. Gare, pause, sedute svolte e inserite a mano restano.
 */
@Composable
fun InterruttoreCoach() {
  val context = LocalContext.current.applicationContext
  val acceso = coachAttivo()
  val scope = rememberCoroutineScope()
  var riacceso by remember { mutableStateOf(false) }
  var controllo by remember { mutableStateOf(false) }
  var daTogliere by remember { mutableStateOf<Int?>(null) }
  var esito by remember { mutableStateOf<String?>(null) } // testo italiano, tradotto quando si mostra

  fun spegni(togli: Boolean) {
    ModalitaCoach.imposta(context, false) // prima si ferma il coach, cosi' non riscrive nulla
    riacceso = false
    if (!togli) return
    scope.launch {
      val e = withContext(Dispatchers.IO) {
        IntervalsSettings(context).credenziali?.let {
          Cervello.pulisciIntervals(context, it, conferma = true, campi = false, anchePassato = false, soloSedute = true, saltaAbbinate = true)
        }
      }
      esito =
          when {
            e == null -> null
            e.esito == EsitoPulizia.OK -> "Coach spento: tolte %d sedute dal calendario.".format(e.eventiEliminati)
            else -> "Coach spento, ma non sono riuscito a togliere le sedute: %s".format(e.errore ?: e.esito)
          }
    }
  }

  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(tr("Coach di allenamento"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
      Switch(
          checked = acceso,
          enabled = !controllo,
          onCheckedChange = { on ->
            esito = null
            if (on) {
              ModalitaCoach.imposta(context, true)
              riacceso = true
              return@Switch
            }
            val credenziali = IntervalsSettings(context).credenziali
            if (credenziali == null || DemoParte3.attiva(context)) {
              spegni(togli = false)
              return@Switch
            }
            controllo = true
            scope.launch {
              val prova = withContext(Dispatchers.IO) {
                Cervello.pulisciIntervals(context, credenziali, conferma = false, campi = false, anchePassato = false, soloSedute = true, saltaAbbinate = true)
              }
              controllo = false
              when {
                !prova.ok -> {
                  spegni(togli = false)
                  esito = "Coach spento, ma non sono riuscito a togliere le sedute: %s".format(prova.errore ?: prova.esito)
                }
                prova.eventiDaEliminare == 0 -> spegni(togli = false)
                else -> daTogliere = prova.eventiDaEliminare
              }
            }
          })
    }
    Text(
        if (acceso) tr("Acceso: il coach pianifica le sedute su Intervals.icu e le adatta ai tuoi dati della notte.")
        else tr("Spento: solo i dati biometrici della notte (HRV, FC a riposo, sonno, età biologica) con i loro semafori. Allo spegnimento le sedute future non ancora svolte vengono tolte da Intervals.icu."),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (controllo)
        Text(tr("Controllo il calendario…"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    if (acceso && riacceso)
        Text(
            tr("Coach riacceso: ripianifico la settimana su Intervals.icu."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary)
    esito?.let { Text(tr(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
  }

  daTogliere?.let { n ->
    AlertDialog(
        onDismissRequest = { daTogliere = null },
        title = { Text(tr("Spegnere il coach?")) },
        text = { Text(tr("Tolgo dal calendario di Intervals.icu %d sedute future non ancora svolte. Gare, pause e sedute già fatte restano.".format(n))) },
        confirmButton = {
          TextButton(onClick = {
            daTogliere = null
            spegni(togli = true)
          }) { Text(tr("Spegni e togli")) }
        },
        dismissButton = { TextButton(onClick = { daTogliere = null }) { Text(tr("Annulla")) } })
  }
}
