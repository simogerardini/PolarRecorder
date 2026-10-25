package com.wboelens.polarrecorder.biosleep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.wboelens.polarrecorder.biosleep.garmin.GarminSettings
import com.wboelens.polarrecorder.biosleep.garmin.ProvaGarmin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Parte 11, passo A: collegamento a Garmin Connect con la Personal API key dell'utente.
 * Per ora salva la chiave e prova l'accesso; la scelta della sorgente della notte arriva nel passo B.
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
        TextButton(onClick = {
          settings.scollega()
          collegato = false
          esito = null
        }) { Text(stringResource(R.string.garmin_scollega)) }
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
      Button(
          onClick = {
            settings.chiave = chiave
            chiave = ""
            collegato = settings.collegato
            prova()
          },
          enabled = chiave.isNotBlank() && !inCorso,
      ) { Text(stringResource(R.string.garmin_salva_e_prova)) }
    }
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
}
