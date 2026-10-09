package com.wboelens.polarrecorder.biosleep.ui

import com.wboelens.polarrecorder.R
import androidx.compose.ui.res.stringResource
import com.wboelens.polarrecorder.BuildConfig
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.cervello.EsitoPrepara
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsAuth
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsClient
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsResult
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsSettings
import com.wboelens.polarrecorder.biosleep.intervals.OAuthIntervals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Impostazioni dell'app: collegamento a Intervals.icu (OAuth, con la API key come opzione
 * avanzata), preparazione dei campi BioSleep, invio automatico, coach (SezioneCoach).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntervalsSettingsScreen(
    onBack: () -> Unit,
    onApriProfilo: () -> Unit = {},
    onApriGare: () -> Unit = {},
    onApriLicenze: () -> Unit = {},
    onApriBackup: () -> Unit = {},
    onApriProtezione: () -> Unit = {},
    onApriFascia: () -> Unit = {},
) {
  val context = LocalContext.current
  val settings = remember { IntervalsSettings(context) }
  val scope = rememberCoroutineScope()
  val stato by OAuthIntervals.stato.collectAsState()
  // rilettura dopo collega/scollega/salva
  var giro by remember { mutableIntStateOf(0) }
  val collegato = remember(giro, stato) { settings.collegato }

  var apiKey by remember { mutableStateOf(settings.apiKey) }
  var showKey by remember { mutableStateOf(false) }
  var avanzate by remember { mutableStateOf(!settings.collegato && settings.apiKey.isNotEmpty()) }
  var autoUpload by remember { mutableStateOf(settings.autoUpload) }
  var testing by remember { mutableStateOf(false) }
  var testResult by remember { mutableStateOf<IntervalsResult?>(null) }

  fun prepara() {
    scope.launch(Dispatchers.IO) { OAuthIntervals.prepara(context.applicationContext) }
  }

  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.intervals_settings_impostazioni)) },
            navigationIcon = {
              IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro") }
            },
        )
      }
  ) { padding ->
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // --- 1. Collegamento ---------------------------------------------------------------------
      Text(stringResource(R.string.intervals_settings_1_intervals_icu), style = MaterialTheme.typography.titleSmall)
      if (collegato) {
        Text(
            "Collegato" + settings.atletaCollegato.takeIf { it.isNotBlank() }?.let { " come atleta $it" }.orEmpty(),
            color = MaterialTheme.colorScheme.primary)
        OutlinedButton(
            onClick = {
              scope.launch {
                OAuthIntervals.scollega(context.applicationContext)
                giro++
              }
            }) {
              Text(stringResource(R.string.intervals_settings_scollega))
            }
      } else {
        Text(
            stringResource(R.string.intervals_settings_collega_il_tuo_account, BuildConfig.APP_NAME, BuildConfig.APP_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        RigaInformativa()
        Button(onClick = { OAuthIntervals.avvia(context) }) { Text(stringResource(R.string.intervals_settings_collega_intervals_icu)) }
      }
      stato.messaggio?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

      // Campi BioSleep su Intervals.icu (cervello.prepara_account)
      if (stato.inCorso) CircularProgressIndicator()
      stato.esito?.let { e ->
        when (e.esito) {
          EsitoPrepara.OK ->
              Text(
                  "Campi ${BuildConfig.APP_NAME} pronti: ${e.creati.size + e.esistenti.size}" +
                      (if (e.creati.isNotEmpty()) " (${e.creati.size} creati ora)" else ""),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.primary)
          EsitoPrepara.PERMESSO_MANCANTE -> {
            Text(
                stringResource(R.string.intervals_settings_il_collegamento_non_ha),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            Button(onClick = { OAuthIntervals.avvia(context) }) { Text(stringResource(R.string.intervals_settings_ricollega)) }
          }
          else -> {
            Text("Campi ${BuildConfig.APP_NAME} non preparati: ${e.errore ?: "errore"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { prepara() }, enabled = !stato.inCorso) { Text(stringResource(R.string.intervals_settings_riprova)) }
          }
        }
      }
      if (settings.isConfigured && stato.esito == null && !stato.inCorso) {
        TextButton(onClick = { prepara() }) { Text(stringResource(R.string.intervals_settings_prepara_i_campi, BuildConfig.APP_NAME)) }
      }

      Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = autoUpload,
            onCheckedChange = {
              autoUpload = it
              settings.autoUpload = it
            },
        )
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.intervals_settings_invia_automaticamente_ogni_notte))
      }

      // --- Opzione avanzata: API key -----------------------------------------------------------
      TextButton(onClick = { avanzate = !avanzate }) {
        Text(if (avanzate) "Nascondi opzioni avanzate" else "Opzioni avanzate: API key personale")
      }
      if (avanzate) {
        Text(
            stringResource(R.string.intervals_settings_alternativa_al_collegamento_utile),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = apiKey,
            onValueChange = {
              apiKey = it
              testResult = null
            },
            label = { Text(stringResource(R.string.intervals_settings_api_key_settings_developer)) },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
              IconButton(onClick = { showKey = !showKey }) {
                Icon(if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (showKey) "Nascondi" else "Mostra")
              }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
              settings.apiKey = apiKey
              testing = true
              testResult = null
              scope.launch {
                testResult =
                    withContext(Dispatchers.IO) {
                      val c = settings.credenziali
                      if (c == null) IntervalsResult.Failed("Nessuna credenziale") else IntervalsClient.testConnection(IntervalsAuth.header(c))
                    }
                testing = false
                giro++
                // con l'accesso valido si preparano subito i campi (anche con la API key)
                if (testResult is IntervalsResult.Ok) prepara()
              }
            },
            enabled = apiKey.isNotBlank() && !testing,
        ) {
          Text(stringResource(R.string.intervals_settings_salva_e_prova_connessione))
        }
        if (testing) CircularProgressIndicator()
        testResult?.let {
          Text(
              it.message,
              color = if (it is IntervalsResult.Ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
      }

      HorizontalDivider()
      // Il coach nell'app (cervello Python): id atleta per la API key, ultimo run, profilo, misura
      SezioneCoach(onApriProfilo, onApriGare)

      HorizontalDivider()
      // Registrazione notturna: batteria, notifiche, Bluetooth, istruzioni per la marca
      TextButton(onClick = onApriProtezione) { Text(stringResource(R.string.intervals_settings_protezione_notturna)) }
      TextButton(onClick = onApriFascia) { Text(stringResource(R.string.intervals_settings_fascia)) }

      HorizontalDivider()
      // Lingua delle sedute sul calendario e sull'orologio
      SezioneLingua()

      HorizontalDivider()
      // Dati: backup cifrato, ripristino, esportazione CSV
      Text(stringResource(R.string.intervals_settings_dati), style = MaterialTheme.typography.titleSmall)
      PromemoriaBackup(onApriBackup)
      TextButton(onClick = onApriBackup) { Text(stringResource(R.string.intervals_settings_backup_e_ripristino)) }
      TextButton(onClick = onApriBackup) { Text(stringResource(R.string.intervals_settings_esporta_notti)) }

      HorizontalDivider()
      SezioneInformazioni(onApriLicenze)
    }
  }
}
