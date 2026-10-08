package com.wboelens.polarrecorder.biosleep.ui

import com.wboelens.polarrecorder.BuildConfig
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.PolarRecorderApplication
import com.wboelens.polarrecorder.biosleep.backup.BackupRepo
import com.wboelens.polarrecorder.biosleep.backup.CifraturaBackup
import com.wboelens.polarrecorder.biosleep.backup.EsitoBackup
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Azione { BACKUP, RIPRISTINO }

/** Card discreta nelle Impostazioni: nessun backup negli ultimi 30 giorni. */
@Composable
fun PromemoriaBackup(onApri: () -> Unit) {
  val context = LocalContext.current.applicationContext
  val serve by produceState(false) { value = withContext(Dispatchers.IO) { BackupRepo.serveBackup(context) } }
  if (!serve) return
  Card(Modifier.fillMaxWidth()) {
    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
      Text("Nessun backup delle notti negli ultimi 30 giorni.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
      TextButton(onClick = onApri) { Text("Fai il backup") }
    }
  }
}

/**
 * Backup cifrato con password, ripristino, esportazione CSV. I file li sceglie l'utente (Drive,
 * memoria del telefono, ...): BioSleep non ha server.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit) {
  val context = LocalContext.current.applicationContext
  val scope = rememberCoroutineScope()
  var password by remember { mutableStateOf("") }
  var conferma by remember { mutableStateOf("") }
  var chiediPassword by remember { mutableStateOf<Azione?>(null) }
  var inCorso by remember { mutableStateOf(false) }
  var esito by remember { mutableStateOf<EsitoBackup?>(null) }
  var pronto by remember { mutableStateOf<BackupRepo.Pronto?>(null) }
  var conRr by remember { mutableStateOf(false) }
  var giro by remember { mutableStateOf(0) }
  val ultimo by produceState(0L, giro) { value = withContext(Dispatchers.IO) { BackupRepo.ultimoBackupMs(context) } }
  fun registrando() = (context as? PolarRecorderApplication)?.isRecordingActive == true

  fun lavora(blocco: suspend () -> EsitoBackup) {
    inCorso = true
    esito = null
    scope.launch {
      esito = withContext(Dispatchers.IO) { blocco() }
      inCorso = false
      giro++
    }
  }

  val creaFile =
      rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pw = password.toCharArray()
        password = ""
        conferma = ""
        if (uri != null) lavora { BackupRepo.crea(context, uri, pw, registrando()) }
      }
  val apriFile =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pw = password.toCharArray()
        password = ""
        if (uri == null) return@rememberLauncherForActivityResult
        inCorso = true
        esito = null
        scope.launch {
          val (p, errore) = withContext(Dispatchers.IO) { BackupRepo.prepara(context, uri, pw) }
          inCorso = false
          if (p != null) pronto = p else esito = EsitoBackup.Errore(errore ?: "Backup non valido")
        }
      }
  val esportaCsv =
      rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(if (conRr) "application/zip" else "text/csv")) { uri ->
        if (uri != null) lavora { BackupRepo.esportaCsv(context, uri, conRr) }
      }

  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text("Backup e ripristino") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } })
      },
  ) { padding ->
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("Backup", style = MaterialTheme.typography.titleSmall)
      Text(
          "Notti, stato del coach, profilo e tag in un unico file cifrato con una password che scegli tu. " +
              "Lo salvi dove vuoi: Google Drive, memoria del telefono, computer. Non contiene il collegamento a " +
              "Intervals.icu: dopo un ripristino su un telefono nuovo va ricollegato.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(
          if (ultimo > 0) "Ultimo backup: " + Instant.ofEpochMilli(ultimo).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))
          else "Nessun backup fatto da questo telefono",
          style = MaterialTheme.typography.bodySmall)
      Button(onClick = { chiediPassword = Azione.BACKUP }, enabled = !inCorso) { Text("Crea backup") }

      HorizontalDivider()
      Text("Ripristino", style = MaterialTheme.typography.titleSmall)
      Text(
          "Da un file .noctalix (o .biosleep, dei backup fatti prima del cambio di nome). Prima del ripristino vedrai quante notti contiene e potrai annullare.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
      OutlinedButton(onClick = { chiediPassword = Azione.RIPRISTINO }, enabled = !inCorso) { Text("Ripristina da un backup") }

      HorizontalDivider()
      Text("Esporta notti", style = MaterialTheme.typography.titleSmall)
      Text(
          "Un CSV con una riga per notte, da aprire in un foglio di calcolo. Il file NON è cifrato: chiunque lo " +
              "apra vede i tuoi dati di sonno e frequenza cardiaca.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error)
      Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = conRr, onCheckedChange = { conRr = it })
        Text("Aggiungi gli intervalli RR di ogni notte (file zip, più grande)")
      }
      OutlinedButton(
          onClick = { esportaCsv.launch(if (conRr) "noctalix_notti_${LocalDate.now()}.zip" else "noctalix_notti_${LocalDate.now()}.csv") },
          enabled = !inCorso) {
            Text("Esporta")
          }

      if (inCorso) CircularProgressIndicator()
      when (val e = esito) {
        is EsitoBackup.Ok -> Text(e.messaggio, color = MaterialTheme.colorScheme.primary)
        is EsitoBackup.Errore -> Text(e.messaggio, color = MaterialTheme.colorScheme.error)
        null -> Unit
      }
    }
  }

  // Password: per il backup va scelta e ripetuta; per il ripristino basta inserirla
  chiediPassword?.let { azione ->
    val nuovo = azione == Azione.BACKUP
    val corta = password.length < CifraturaBackup.PASSWORD_MINIMA
    val diverse = nuovo && password != conferma
    AlertDialog(
        onDismissRequest = { chiediPassword = null; password = ""; conferma = "" },
        title = { Text(if (nuovo) "Password del backup" else "Password del backup da ripristinare") },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (nuovo) {
              Text(
                  "Serve per riaprire il backup. Se la dimentichi il backup non si può più aprire: nessuno può recuperarla.",
                  style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = password, onValueChange = { password = it }, label = { Text("Password (almeno ${CifraturaBackup.PASSWORD_MINIMA} caratteri)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            if (nuovo) {
              OutlinedTextField(
                  value = conferma, onValueChange = { conferma = it }, label = { Text("Ripeti la password") },
                  singleLine = true, visualTransformation = PasswordVisualTransformation(), isError = conferma.isNotEmpty() && diverse,
                  modifier = Modifier.fillMaxWidth())
            }
          }
        },
        confirmButton = {
          TextButton(
              onClick = {
                chiediPassword = null
                if (nuovo) creaFile.launch("noctalix_${LocalDate.now()}.noctalix") else apriFile.launch(arrayOf("*/*"))
              },
              enabled = !corta && !diverse) {
                Text("Continua")
              }
        },
        dismissButton = { TextButton(onClick = { chiediPassword = null; password = ""; conferma = "" }) { Text("Annulla") } },
    )
  }

  // Conferma prima di sovrascrivere le notti del telefono
  pronto?.let { p ->
    AlertDialog(
        onDismissRequest = { p.scarta(); pronto = null },
        title = { Text("Ripristinare questo backup?") },
        text = {
          Text(
              "Backup del ${p.manifest.creato.replace('T', ' ')} (${BuildConfig.APP_NAME} ${p.manifest.appVersion}), con ${p.notti} notti.\n\n" +
                  "Le notti di questo telefono verranno sostituite da quelle del backup, insieme allo stato del coach, al profilo e ai tag.")
        },
        confirmButton = {
          TextButton(
              onClick = {
                pronto = null
                lavora { BackupRepo.applica(context, p, registrando()) }
              }) {
                Text("Sostituisci", color = MaterialTheme.colorScheme.error)
              }
        },
        dismissButton = { TextButton(onClick = { p.scarta(); pronto = null }) { Text("Annulla") } },
    )
  }
}
