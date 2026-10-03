package com.wboelens.polarrecorder.biosleep.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.coach.CoachSettings
import com.wboelens.polarrecorder.biosleep.coach.GitHubClient
import com.wboelens.polarrecorder.biosleep.coach.GitHubDispatch
import com.wboelens.polarrecorder.biosleep.coach.Problema
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sezione "Avvio del coach" delle impostazioni Intervals.icu: repository, token, verifica, stato. */
@Composable
fun SezioneAvvioCoach() {
  val context = LocalContext.current
  val settings = remember { CoachSettings(context) }
  val scope = rememberCoroutineScope()
  val versione by CoachSettings.versione.collectAsState()

  var repo by remember { mutableStateOf(settings.repo) }
  var token by remember { mutableStateOf("") }
  var mostra by remember { mutableStateOf(false) }
  var prova by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
  var inProva by remember { mutableStateOf(false) }

  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("3. Avvio del coach (GitHub)", style = MaterialTheme.typography.titleSmall)
    Text(
        "Dopo l'invio della notte di oggi l'app avvia il workflow del coach. Serve un token " +
            "fine-grained limitato al repository del coach, con il solo permesso Actions: Read and write.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(
        value = repo,
        onValueChange = {
          repo = it
          prova = null
        },
        label = { Text("Repository (owner/repo)") },
        singleLine = true,
        isError = !GitHubDispatch.repoValido(repo.trim()),
        modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        value = token,
        onValueChange = {
          token = it
          prova = null
        },
        label = { Text(if (settings.haToken) "Token GitHub (salvato: lascia vuoto per tenerlo)" else "Token GitHub") },
        singleLine = true,
        visualTransformation = if (mostra) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
          IconButton(onClick = { mostra = !mostra }) {
            Icon(if (mostra) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (mostra) "Nascondi" else "Mostra")
          }
        },
        modifier = Modifier.fillMaxWidth())
    Button(
        onClick = {
          settings.repo = repo.trim()
          if (token.isNotBlank()) settings.token = token
          token = ""
          inProva = true
          prova = null
          scope.launch {
            prova = withContext(Dispatchers.IO) { verifica(settings) }
            inProva = false
          }
        },
        enabled = GitHubDispatch.repoValido(repo.trim()) && (token.isNotBlank() || settings.haToken) && !inProva,
    ) {
      Text("Salva e verifica accesso")
    }
    if (inProva) CircularProgressIndicator()
    prova?.let { (ok, testo) ->
      Text(testo, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
    }
    StatoAvvio(settings, versione)
  }
}

/** Legge il workflow con il token: verifica token, repository e accesso senza avviare il coach. */
private fun verifica(s: CoachSettings): Pair<Boolean, String> {
  val token = s.token ?: return false to "Token non leggibile: inseriscilo di nuovo"
  val r = GitHubClient.prova(s.repo, token)
  s.registraScadenza(r.header("github-authentication-token-expiration"))
  return when (r.codice) {
    200 ->
        true to
            "Accesso al workflow verificato. Il permesso di avvio (Read and write) si conferma " +
                "solo al primo avvio vero."
    401 -> false to "Token non valido o scaduto"
    404 -> false to "Repository o workflow non trovati, oppure il token non ha accesso al repository"
    null -> false to "Connessione non riuscita"
    else -> false to "Risposta di GitHub: HTTP ${r.codice}"
  }
}

@Composable
private fun StatoAvvio(s: CoachSettings, @Suppress("UNUSED_PARAMETER") versione: Int) {
  val stile = MaterialTheme.typography.bodySmall
  val errore = MaterialTheme.colorScheme.error
  val giorni = GitHubDispatch.giorniSeInScadenza(s.scadenzaToken?.let { LocalDate.parse(it) }, LocalDate.now())
  if (giorni != null) {
    Text(
        if (giorni < 0) "Il token GitHub e' scaduto: creane uno nuovo" else "Il token GitHub scade fra $giorni giorni: rinnovalo",
        style = stile,
        color = errore)
  } else {
    s.scadenzaToken?.let { Text("Token valido fino al $it", style = stile) }
  }
  val p = s.problema
  val m = s.messaggio
  when {
    p != null && m != null -> Text("Ultimo avvio (${s.dataEsito}): $m", style = stile, color = errore)
    m != null -> Text("Ultimo avvio (${s.dataEsito}): $m", style = stile, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/**
 * Avviso persistente nella schermata Oggi: token da rinnovare, configurazione da correggere,
 * oppure coach non avviato oggi. Tocco = impostazioni.
 */
@Composable
fun AvvisoAvvioCoach(onApriImpostazioni: () -> Unit) {
  val context = LocalContext.current
  val s = remember { CoachSettings(context) }
  // leggere il valore iscrive la schermata ai cambi: l'avviso sparisce appena il problema e' risolto
  CoachSettings.versione.collectAsState().value
  val oggi = LocalDate.now().toString()
  val testo =
      when {
        !s.haToken -> "Avvio del coach dall'app non configurato: parte solo alle 10:30"
        s.problema == Problema.TOKEN -> "Token GitHub da rinnovare: il coach parte solo alle 10:30"
        s.problema == Problema.CONFIGURAZIONE -> "Avvio del coach da correggere: ${s.messaggio ?: ""}"
        s.problema == Problema.RETE && s.dataEsito == oggi -> "Coach non avviato dall'app oggi: partira' alle 10:30"
        else -> null
      } ?: return
  Text(
      testo,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.error,
      modifier = Modifier.clickable(onClick = onApriImpostazioni))
}
