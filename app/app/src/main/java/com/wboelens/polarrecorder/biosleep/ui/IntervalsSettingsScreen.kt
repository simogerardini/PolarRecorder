package com.wboelens.polarrecorder.biosleep.ui

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
fun IntervalsSettingsScreen(onBack: () -> Unit, onApriProfilo: () -> Unit = {}) {
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
            title = { Text("Impostazioni") },
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
      Text("1. Intervals.icu", style = MaterialTheme.typography.titleSmall)
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
              Text("Scollega")
            }
      } else {
        Text(
            "Collega il tuo account: BioSleep potrà inviare le notti, leggere sedute e calendario e " +
                "creare da solo i campi BioSleep.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { OAuthIntervals.avvia(context) }) { Text("Collega Intervals.icu") }
      }
      stato.messaggio?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

      // Campi BioSleep su Intervals.icu (cervello.prepara_account)
      if (stato.inCorso) CircularProgressIndicator()
      stato.esito?.let { e ->
        when (e.esito) {
          EsitoPrepara.OK ->
              Text(
                  "Campi BioSleep pronti: ${e.creati.size + e.esistenti.size}" +
                      (if (e.creati.isNotEmpty()) " (${e.creati.size} creati ora)" else ""),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.primary)
          EsitoPrepara.PERMESSO_MANCANTE -> {
            Text(
                "Il collegamento non ha il permesso di creare i campi (SETTINGS:WRITE): ricollega e accetta tutti i permessi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            Button(onClick = { OAuthIntervals.avvia(context) }) { Text("Ricollega") }
          }
          else -> {
            Text("Campi BioSleep non preparati: ${e.errore ?: "errore"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { prepara() }, enabled = !stato.inCorso) { Text("Riprova") }
          }
        }
      }
      if (settings.isConfigured && stato.esito == null && !stato.inCorso) {
        TextButton(onClick = { prepara() }) { Text("Prepara i campi BioSleep") }
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
        Text("Invia automaticamente ogni notte dopo lo stop")
      }

      // --- Opzione avanzata: API key -----------------------------------------------------------
      TextButton(onClick = { avanzate = !avanzate }) {
        Text(if (avanzate) "Nascondi opzioni avanzate" else "Opzioni avanzate: API key personale")
      }
      if (avanzate) {
        Text(
            "Alternativa al collegamento (utile finché l'app non è approvata su Intervals.icu). " +
                "Se sei collegato, il collegamento ha la precedenza.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = apiKey,
            onValueChange = {
              apiKey = it
              testResult = null
            },
            label = { Text("API key (Settings → Developer Settings)") },
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
          Text("Salva e prova connessione")
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
      SezioneCoach(onApriProfilo)
    }
  }
}
