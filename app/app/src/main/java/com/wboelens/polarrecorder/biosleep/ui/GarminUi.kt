package com.wboelens.polarrecorder.biosleep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.BuildConfig
import com.wboelens.polarrecorder.R
import com.wboelens.polarrecorder.biosleep.garmin.EsitoProvaGarmin
import com.wboelens.polarrecorder.biosleep.garmin.GarminClient
import com.wboelens.polarrecorder.biosleep.garmin.GarminScelta
import com.wboelens.polarrecorder.biosleep.garmin.GarminSettings
import com.wboelens.polarrecorder.biosleep.NightSummary
import com.wboelens.polarrecorder.biosleep.garmin.ProvaGarmin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Parte 11: collegamento a Garmin Connect con la Personal API key dell'utente e scelta della
 * sorgente della notte (fascia cardio o Garmin: una sola, la banda non le mescola).
 */
@Composable
fun SezioneGarmin() {
  val context = LocalContext.current
  val uri = LocalUriHandler.current
  val scope = rememberCoroutineScope()
  val settings = remember { GarminSettings(context) }
  var collegato by remember { mutableStateOf(settings.collegato) }
  var chiave by remember { mutableStateOf("") }
  var mostra by remember { mutableStateOf(false) }
  var inCorso by remember { mutableStateOf(false) }
  var esito by remember { mutableStateOf<EsitoProvaGarmin?>(null) }
  var sorgente by remember { mutableStateOf(GarminScelta.sorgente(context)) }
  var durata by remember { mutableStateOf(365) }
  var chiediScollega by remember { mutableStateOf(false) }

  fun prova() {
    inCorso = true
    esito = null
    scope.launch {
      esito = withContext(Dispatchers.IO) { ProvaGarmin.prova(context.applicationContext) }
      inCorso = false
    }
  }

  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(stringResource(R.string.garmin_titolo), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.garmin_spiegazione, BuildConfig.APP_NAME),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (collegato) {
      Text(stringResource(R.string.garmin_collegato), color = MaterialTheme.colorScheme.primary)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { prova() }, enabled = !inCorso) { Text(stringResource(R.string.garmin_prova)) }
        TextButton(onClick = { chiediScollega = true }) { Text(stringResource(R.string.garmin_scollega)) }
      }
      // scadenza stimata dalla durata scelta su Garmin Connect
      settings.scadenzaMs?.let { ms ->
        val giorno = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        Text(stringResource(R.string.garmin_scade_il, giorno.toString()), style = MaterialTheme.typography.bodySmall)
      }
      // Sorgente della notte
      Text(stringResource(R.string.garmin_sorgente_titolo), style = MaterialTheme.typography.labelLarge)
      for ((valore, etichetta) in listOf(
          NightSummary.SORGENTE_FASCIA to R.string.garmin_sorgente_fascia,
          NightSummary.SORGENTE_GARMIN to R.string.garmin_sorgente_garmin)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          RadioButton(selected = sorgente == valore, onClick = {
            sorgente = valore
            GarminScelta.imposta(context, valore)
          })
          Text(stringResource(etichetta))
        }
      }
      Text(
          stringResource(R.string.garmin_sorgente_nota),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
      if (sorgente == NightSummary.SORGENTE_GARMIN) {
        val e = remember(sorgente) { GarminScelta.esito(context) }
        val testo =
            when {
              e == null -> null
              e.startsWith("salvata:") -> stringResource(R.string.garmin_esito_salvata, e.removePrefix("salvata:"))
              e == "attesa" -> stringResource(R.string.garmin_esito_attesa)
              e == "chiave" -> stringResource(R.string.garmin_chiave_non_valida)
              e == "limite" -> stringResource(R.string.garmin_troppe_richieste)
              else -> stringResource(R.string.garmin_esito_errore)
            }
        testo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
      }
    } else {
      TextButton(onClick = { runCatching { uri.openUri(GarminClient.PAGINA_CHIAVE) } }) {
        Text(stringResource(R.string.garmin_crea_chiave))
      }
      OutlinedTextField(
          value = chiave,
          onValueChange = {
            chiave = it
            esito = null
          },
          label = { Text(stringResource(R.string.garmin_chiave)) },
          singleLine = true,
          visualTransformation = if (mostra) VisualTransformation.None else PasswordVisualTransformation(),
          trailingIcon = {
            IconButton(onClick = { mostra = !mostra }) {
              Icon(if (mostra) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null)
            }
          },
          modifier = Modifier.fillMaxWidth(),
      )
      // durata scelta su Garmin Connect: serve all'avviso 14 giorni prima della scadenza
      Text(stringResource(R.string.garmin_durata_titolo), style = MaterialTheme.typography.labelLarge)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (g in GarminSettings.DURATE) {
          FilterChip(selected = durata == g, onClick = { durata = g }, label = { Text(stringResource(R.string.garmin_durata_giorni, g)) })
        }
      }
      Button(
          onClick = {
            settings.chiave = chiave
            settings.salvaDurata(durata)
            chiave = ""
            collegato = settings.collegato
            prova()
          },
          enabled = chiave.isNotBlank() && !inCorso,
      ) { Text(stringResource(R.string.garmin_salva_e_prova)) }
    }
    Text(
        stringResource(R.string.garmin_resta_sul_telefono),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        stringResource(R.string.garmin_privacy, BuildConfig.APP_NAME),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (inCorso) CircularProgressIndicator()
    esito?.let { e ->
      val (testo, ok) =
          when (e) {
            is EsitoProvaGarmin.Ok ->
                (if (e.ultimaHrvMs != null && e.giorno != null) stringResource(R.string.garmin_ok_hrv, e.ultimaHrvMs, e.giorno)
                else stringResource(R.string.garmin_ok_senza_notti)) to true
            EsitoProvaGarmin.ChiaveNonValida -> stringResource(R.string.garmin_chiave_non_valida) to false
            EsitoProvaGarmin.TroppeRichieste -> stringResource(R.string.garmin_troppe_richieste) to false
            is EsitoProvaGarmin.Errore -> stringResource(R.string.garmin_errore, e.messaggio) to false
          }
      Text(testo, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
    }
  }
  if (chiediScollega) {
    fun fatto(cancella: Boolean) {
      chiediScollega = false
      scope.launch {
        withContext(Dispatchers.IO) { GarminScelta.scollega(context.applicationContext, cancella) }
        sorgente = NightSummary.SORGENTE_FASCIA
        collegato = false
        esito = null
      }
    }
    AlertDialog(
        onDismissRequest = { chiediScollega = false },
        title = { Text(stringResource(R.string.garmin_scollega_titolo)) },
        text = { Text(stringResource(R.string.garmin_scollega_testo)) },
        confirmButton = { TextButton(onClick = { fatto(cancella = true) }) { Text(stringResource(R.string.garmin_cancella_notti)) } },
        dismissButton = {
          Row {
            TextButton(onClick = { chiediScollega = false }) { Text(stringResource(R.string.garmin_annulla)) }
            TextButton(onClick = { fatto(cancella = false) }) { Text(stringResource(R.string.garmin_tieni_notti)) }
          }
        })
  }

}

/** Parte 11: al posto della qualita' del segnale (battiti), per le notti lette da Garmin Connect. */
@Composable
fun GarminFonteNotte(s: NightSummary) {
  Text(
      stringResource(R.string.garmin_fonte_notte, s.windowsOk, s.windowsTotal),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant)
  Text(
      stringResource(R.string.garmin_resta_sul_telefono),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant)
}
