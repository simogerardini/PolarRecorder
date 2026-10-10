package com.wboelens.polarrecorder.biosleep.ui

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.wboelens.polarrecorder.BuildConfig
import com.wboelens.polarrecorder.R
import com.wboelens.polarrecorder.biosleep.ponte.ArchivioCollegamenti
import com.wboelens.polarrecorder.biosleep.ponte.Collegamento
import com.wboelens.polarrecorder.biosleep.ponte.Ponte
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult

/** Impostazioni -> Dispositivi collegati: collega un browser (QR + codice), elenco, scollega. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DispositiviCollegatiScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  remember { ArchivioCollegamenti(context) } // carica lo stato
  val collegamenti by ArchivioCollegamenti.stato.collectAsState()
  val link by Ponte.linkInArrivo.collectAsState()
  var inCorso by remember { mutableStateOf<Ponte.InCorso?>(null) }
  var attesa by remember { mutableStateOf(false) }
  var errore by remember { mutableStateOf<String?>(null) }
  var fatto by remember { mutableStateOf(false) }
  var daScollegare by remember { mutableStateOf<Collegamento?>(null) }
  val qrNonValido = stringResource(R.string.dispositivi_qr_non_valido, BuildConfig.APP_NAME)

  val scanner =
      rememberLauncherForActivityResult(ScanContract()) { r ->
        val l = Ponte.leggiLink(r.contents)
        if (l != null) Ponte.linkInArrivo.value = l else if (r.contents != null) errore = qrNonValido
      }

  // Arrivato un link (App Link o scanner): tempo a) del collegamento
  LaunchedEffect(link) {
    val l = link ?: return@LaunchedEffect
    Ponte.linkInArrivo.value = null
    errore = null
    fatto = false
    attesa = true
    val r = withContext(Dispatchers.IO) { Ponte.avvia(l) }
    inCorso = r.getOrNull()
    errore = r.exceptionOrNull()?.message
    attesa = false
  }

  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.dispositivi_titolo)) },
            navigationIcon = {
              IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.dispositivi_indietro))
              }
            })
      }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
              Text(stringResource(R.string.dispositivi_spiegazione), style = MaterialTheme.typography.bodyMedium)

              val c = inCorso
              when {
                attesa -> CircularProgressIndicator()
                c != null ->
                    Card(Modifier.fillMaxWidth()) {
                      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.dispositivi_codice_domanda), style = MaterialTheme.typography.titleMedium)
                        Text(c.codice.chunked(3).joinToString(" "), fontSize = 40.sp, style = MaterialTheme.typography.displaySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                          Button(onClick = {
                            attesa = true
                            scope.launch {
                              val r = withContext(Dispatchers.IO) { Ponte.conferma(context, c) }
                              inCorso = null
                              attesa = false
                              errore = r.exceptionOrNull()?.message
                              fatto = r.isSuccess
                            }
                          }) { Text(stringResource(R.string.dispositivi_codice_si)) }
                          OutlinedButton(onClick = { inCorso = null }) { Text(stringResource(R.string.dispositivi_codice_no)) }
                        }
                      }
                    }
                else -> {
                  Text(stringResource(R.string.dispositivi_come_collegare), style = MaterialTheme.typography.bodySmall)
                  Button(onClick = {
                    scanner.launch(
                        ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false)
                            .setPrompt(context.getString(R.string.dispositivi_inquadra)).setOrientationLocked(false))
                  }) { Text(stringResource(R.string.dispositivi_scansiona)) }
                }
              }
              if (fatto) Text(stringResource(R.string.dispositivi_collegato_ok), color = MaterialTheme.colorScheme.primary)
              errore?.let { Text(it, color = MaterialTheme.colorScheme.error) }

              Text(stringResource(R.string.dispositivi_elenco), style = MaterialTheme.typography.titleMedium)
              if (collegamenti.isEmpty()) {
                Text(stringResource(R.string.dispositivi_nessuno), style = MaterialTheme.typography.bodySmall)
              }
              val f = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
              collegamenti.forEach { col ->
                Card(Modifier.fillMaxWidth()) {
                  Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                      Text(stringResource(R.string.dispositivi_browser_dal, f.format(Date(col.creatoMs))))
                      Text(
                          col.ultimaCopiaMs?.let {
                            stringResource(R.string.dispositivi_ultima_copia, f.format(Date(it)), ((col.byteCopia ?: 0) + 1023) / 1024)
                          } ?: stringResource(R.string.dispositivi_nessuna_copia),
                          style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { daScollegare = col }) { Text(stringResource(R.string.dispositivi_scollega)) }
                  }
                }
              }
            }
      }

  daScollegare?.let { col ->
    AlertDialog(
        onDismissRequest = { daScollegare = null },
        title = { Text(stringResource(R.string.dispositivi_scollega_titolo)) },
        text = { Text(stringResource(R.string.dispositivi_scollega_testo)) },
        confirmButton = {
          TextButton(onClick = {
            daScollegare = null
            scope.launch {
              val r = withContext(Dispatchers.IO) { Ponte.scollega(context, col) }
              errore = r.exceptionOrNull()?.message
            }
          }) { Text(stringResource(R.string.dispositivi_scollega)) }
        },
        dismissButton = { TextButton(onClick = { daScollegare = null }) { Text(stringResource(R.string.dispositivi_annulla)) } })
  }
}
