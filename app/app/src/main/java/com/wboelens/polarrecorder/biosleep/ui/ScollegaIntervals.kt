package com.wboelens.polarrecorder.biosleep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.BuildConfig
import com.wboelens.polarrecorder.biosleep.cervello.Cervello
import com.wboelens.polarrecorder.biosleep.cervello.EsitoPulizia
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsSettings
import com.wboelens.polarrecorder.biosleep.intervals.OAuthIntervals
import com.wboelens.polarrecorder.biosleep.lingua.TestiSistema
import com.wboelens.polarrecorder.biosleep.lingua.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Scollega Intervals.icu" (cervello 2026.10.09-privacy): prima mostra cosa l'app ha scritto su
 * Intervals.icu (cervello.pulisci_intervals in prova), poi alla conferma lo elimina e solo dopo
 * revoca il token. Se l'eliminazione non riesce del tutto il token resta: senza token non si
 * potrebbe piu' riprovare.
 */
@Composable
fun DialogoScollega(onChiudi: () -> Unit, onFatto: (String) -> Unit) {
  val context = LocalContext.current
  val settings = remember { IntervalsSettings(context) }
  val scope = rememberCoroutineScope()
  var campi by remember { mutableStateOf(false) }
  var passato by remember { mutableStateOf(false) }
  var prova by remember { mutableStateOf<EsitoPulizia?>(null) }
  var lavoro by remember { mutableStateOf(false) }

  suspend fun pulisci(conferma: Boolean): EsitoPulizia =
      withContext(Dispatchers.IO) {
        val c = settings.credenziali ?: return@withContext EsitoPulizia.errore("non collegato")
        Cervello.pulisciIntervals(context.applicationContext, c, conferma = conferma, campi = campi, anchePassato = passato)
      }

  // prova ogni volta che cambiano le opzioni
  LaunchedEffect(campi, passato) {
    prova = null
    prova = pulisci(conferma = false)
  }

  fun scollegaSoltanto() {
    lavoro = true
    scope.launch {
      OAuthIntervals.scollega(context.applicationContext) // il suo messaggio compare nelle impostazioni
      onFatto("")
    }
  }

  fun eliminaEScollega() {
    lavoro = true
    scope.launch {
      val e = pulisci(conferma = true)
      if (e.esito != EsitoPulizia.OK) {
        // senza eliminazione il token resta: serve per riprovare
        onFatto(TestiSistema.traduci(context, "Eliminazione non riuscita: non ho scollegato. Riprova."))
        return@launch
      }
      val riepilogo =
          if (e.completo) "Eliminati da Intervals.icu: ${e.eventiEliminati} eventi e ${e.campiEliminati} campi."
          else "Eliminati ${e.eventiEliminati} eventi su ${e.eventiDaEliminare} e ${e.campiEliminati} campi su ${e.campiDaEliminare}: controlla il resto su Intervals.icu."
      OAuthIntervals.scollega(context.applicationContext)
      onFatto(TestiSistema.traduci(context, riepilogo))
    }
  }

  val p = prova
  val qualcosa = p != null && p.ok && (p.eventiDaEliminare > 0 || p.campiDaEliminare > 0)
  AlertDialog(
      onDismissRequest = { if (!lavoro) onChiudi() },
      title = { Text(tr("Scollega Intervals.icu")) },
      text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          when {
            lavoro -> Riga { Text(tr("Eliminazione in corso…")) }
            p == null -> Riga { Text(tr("Controllo di cosa c'è su Intervals.icu…")) }
            p.esito == EsitoPulizia.PERMESSO_MANCANTE ->
                Text(tr("Intervals.icu non permette di leggere il calendario: puoi scollegare senza eliminare."))
            !p.ok -> Text(tr("Controllo non riuscito: ${p.errore ?: p.esito}"))
            !qualcosa -> Text(tr("Niente da eliminare."))
            else -> {
              Text(tr("Eventi creati dall'app da eliminare: ${p.eventiDaEliminare}"), style = MaterialTheme.typography.bodyLarge)
              p.perTipo?.let { t ->
                Text(
                    tr("${t["sedute"] ?: 0} sedute · ${t["gare"] ?: 0} gare · ${t["pause"] ?: 0} pause"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              if (campi) Text(tr("Campi dell'app nel profilo da eliminare: ${p.campiDaEliminare}"))
            }
          }
          Opzione(tr("Elimina anche i campi ${BuildConfig.APP_NAME} dal mio profilo"), campi, !lavoro) { campi = it }
          Opzione(tr("Anche lo storico passato"), passato, !lavoro) { passato = it }
          Text(
              tr("Le soglie aggiornate dal coach (FTP, LTHR, passo, CSS) restano come sono: non tornano ai valori precedenti."),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      },
      confirmButton = {
        if (qualcosa) TextButton(onClick = { eliminaEScollega() }, enabled = !lavoro) { Text(tr("Elimina e scollega")) }
        else TextButton(onClick = { scollegaSoltanto() }, enabled = !lavoro && p != null) { Text(tr("Scollega Intervals.icu")) }
      },
      dismissButton = {
        Row {
          if (qualcosa) TextButton(onClick = { scollegaSoltanto() }, enabled = !lavoro) { Text(tr("Scollega senza eliminare")) }
          TextButton(onClick = onChiudi, enabled = !lavoro) { Text(tr("Annulla")) }
        }
      },
  )
}

@Composable
private fun Riga(contenuto: @Composable () -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    contenuto()
  }
}

@Composable
private fun Opzione(testo: String, valore: Boolean, attivo: Boolean, cambia: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
    Checkbox(checked = valore, onCheckedChange = cambia, enabled = attivo)
    Text(testo, style = MaterialTheme.typography.bodyMedium)
  }
}
