package com.wboelens.polarrecorder.biosleep.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.cervello.Cervello
import com.wboelens.polarrecorder.biosleep.cervello.CoachStato
import com.wboelens.polarrecorder.biosleep.cervello.ConfigCervello
import com.wboelens.polarrecorder.biosleep.cervello.ProfiloRepo
import com.wboelens.polarrecorder.biosleep.cervello.RisultatoCervello
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsSettings
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun durata(ms: Long): String {
  val s = ms / 1000
  return if (s < 60) "$s s" else "${s / 60} min ${s % 60} s"
}

/**
 * Sezione "Coach" delle impostazioni Intervals.icu: id atleta, stato dell'ultimo run, apertura del
 * log se non e' riuscito, misura della durata di un run settimanale (prova che non scrive nulla).
 */
@Composable
fun SezioneCoach(onApriProfilo: () -> Unit = {}) {
  val context = LocalContext.current
  val settings = remember { IntervalsSettings(context) }
  val stato = remember { CoachStato(context) }
  CoachStato.versione.collectAsState().value // rilegge lo stato a ogni run
  val scope = rememberCoroutineScope()
  var atleta by remember { mutableStateOf(settings.athleteId) }
  var misura by remember { mutableStateOf<String?>(null) }
  var inMisura by remember { mutableStateOf(false) }
  var log by remember { mutableStateOf<String?>(null) }

  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("3. Coach", style = MaterialTheme.typography.titleSmall)
    Text(
        "Il coach gira nell'app subito dopo l'invio della notte, e alle 10:30 se la notte non è " +
            "arrivata. Usa la stessa API key di Intervals.icu.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(
        value = atleta,
        onValueChange = {
          atleta = it
          settings.athleteId = it
        },
        label = { Text("Id atleta Intervals.icu (0 = quello della API key)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth())

    val esito = stato.esito
    when {
      esito == null -> Text("Il coach non ha ancora girato su questo telefono", style = MaterialTheme.typography.bodySmall)
      esito == RisultatoCervello.ERRORE ->
          Text(
              "Ultimo run del coach non riuscito (${stato.data}): tocca per il log",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
              modifier =
                  Modifier.clickable {
                    log =
                        stato.logFile?.let { f ->
                          try {
                            File(f).readText().takeLast(12_000)
                          } catch (e: IOException) {
                            null
                          }
                        } ?: (stato.errore ?: "Log non disponibile")
                  })
      else ->
          Text(
              "Ultimo run (${stato.data}): $esito in ${durata(stato.durataMs)}",
              style = MaterialTheme.typography.bodySmall)
    }
    if (stato.durataSettimanaleMs > 0) {
      Text("Durata di un run settimanale: ${durata(stato.durataSettimanaleMs)}", style = MaterialTheme.typography.bodySmall)
    }

    OutlinedButton(onClick = onApriProfilo) { Text("Profilo atleta: FC, ore massime, disponibilità") }
    OutlinedButton(
        onClick = {
          inMisura = true
          misura = null
          scope.launch {
            val ctx = context.applicationContext
            val t0 = System.currentTimeMillis()
            val r =
                withContext(Dispatchers.IO) {
                  Cervello.esegui(
                      ctx,
                      ConfigCervello(
                          settings.apiKey, settings.athleteId, Cervello.cartella(ctx).absolutePath,
                          modo = "settimanale", dryRun = true, senzaAttesa = true,
                          profilo = ProfiloRepo.effettivo(ctx)))
                }
            val ms = System.currentTimeMillis() - t0
            if (r.esito == RisultatoCervello.PIANIFICATA) stato.registraProva(ms)
            misura = "Prova settimanale: ${r.esito} in ${durata(ms)}" + (r.errore?.let { " · $it" } ?: "")
            inMisura = false
          }
        },
        enabled = !inMisura && settings.isConfigured,
    ) {
      Text("Misura un run settimanale (prova, non scrive nulla)")
    }
    if (inMisura) CircularProgressIndicator()
    misura?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
  }

  log?.let { testo ->
    AlertDialog(
        onDismissRequest = { log = null },
        title = { Text("Log del coach") },
        text = {
          Text(
              testo,
              fontFamily = FontFamily.Monospace,
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()))
        },
        confirmButton = { TextButton(onClick = { log = null }) { Text("Chiudi") } },
    )
  }
}
