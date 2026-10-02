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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsClient
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsResult
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Impostazioni Intervals.icu: campi da creare, API key, invio automatico, prova di connessione. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntervalsSettingsScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val settings = remember { IntervalsSettings(context) }
  val scope = rememberCoroutineScope()

  var apiKey by remember { mutableStateOf(settings.apiKey) }
  var showKey by remember { mutableStateOf(false) }
  var autoUpload by remember { mutableStateOf(settings.autoUpload) }
  var testing by remember { mutableStateOf(false) }
  var testResult by remember { mutableStateOf<IntervalsResult?>(null) }

  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text("Intervals.icu") },
            navigationIcon = {
              IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
              }
            },
        )
      }
  ) { padding ->
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text("1. Campi da creare in Intervals.icu", style = MaterialTheme.typography.titleSmall)
      Text(
          "Una volta sola: nel dialogo Wellness di un giorno premi \"Fields\" e crea un campo " +
              "di tipo numero per ogni riga, con esattamente questo codice. BioSleep scrive solo " +
              "qui: i campi standard (HRV, FC a riposo, sonno) restano a Oura.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        IntervalsClient.CUSTOM_FIELDS.forEach { (code, description) ->
          Row {
            Text(code, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text("  $description", style = MaterialTheme.typography.bodyMedium)
          }
        }
      }

      Text("2. API key", style = MaterialTheme.typography.titleSmall)
      OutlinedTextField(
          value = apiKey,
          onValueChange = {
            apiKey = it
            testResult = null
          },
          label = { Text("API key (Settings → Developer Settings)") },
          singleLine = true,
          visualTransformation =
              if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
          trailingIcon = {
            IconButton(onClick = { showKey = !showKey }) {
              Icon(
                  if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                  contentDescription = if (showKey) "Nascondi" else "Mostra",
              )
            }
          },
          modifier = Modifier.fillMaxWidth(),
      )

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

      Button(
          onClick = {
            settings.apiKey = apiKey
            testing = true
            testResult = null
            scope.launch {
              testResult = withContext(Dispatchers.IO) { IntervalsClient.testConnection(settings.apiKey) }
              testing = false
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
            color =
                if (it is IntervalsResult.Ok) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}
