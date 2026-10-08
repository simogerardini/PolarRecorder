package com.wboelens.polarrecorder.biosleep.ui

import com.wboelens.polarrecorder.BuildConfig
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.protezione.Interruzione
import com.wboelens.polarrecorder.biosleep.protezione.Marca
import com.wboelens.polarrecorder.biosleep.protezione.Protezione
import com.wboelens.polarrecorder.biosleep.protezione.VoceProtezione
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Stato della checklist che si aggiorna da solo: ogni 2 secondi mentre la schermata e' visibile,
 * cosi' tornando dalle impostazioni di sistema (o accendendo il Bluetooth) la voce diventa verde.
 */
@Composable
fun rememberStatoProtezione(): List<VoceProtezione> {
  val context = LocalContext.current.applicationContext
  var stato by remember { mutableStateOf(Protezione.stato(context)) }
  LaunchedEffect(Unit) {
    while (isActive) {
      delay(2_000)
      stato = withContext(Dispatchers.Default) { Protezione.stato(context) }
    }
  }
  return stato
}

/** In Oggi: finche' una voce e' rossa. */
@Composable
fun CardProtezione(onApri: () -> Unit) {
  val stato = rememberStatoProtezione()
  val rosse = stato.filter { !it.ok }
  if (rosse.isEmpty()) return
  Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("Protezione notturna incompleta", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
      Text(
          "Da sistemare: " + rosse.joinToString(", ") { it.titolo.lowercase() } + ". Android potrebbe interrompere la registrazione.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onErrorContainer)
      Button(onClick = onApri) { Text("Sistema") }
    }
  }
}

/** In Oggi, al mattino: la notte e' stata interrotta (dato scritto dalla registrazione, Parte 1). */
@Composable
fun CardInterruzione(onApriIstruzioni: () -> Unit) {
  val context = LocalContext.current.applicationContext
  var i by remember { mutableStateOf<Interruzione?>(null) }
  var giro by remember { mutableStateOf(0) }
  LaunchedEffect(giro) {
    i = withContext(Dispatchers.IO) {
      Protezione.ultimaInterruzione(context)?.takeIf { it.data != Protezione.interruzioneChiusa(context) }
    }
  }
  val x = i ?: return
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
          "Registrazione interrotta per ${x.minuti} minuti (${x.causa})",
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.error)
      Text(
          "Succede soprattutto quando il sistema chiude le app in background per risparmiare batteria.",
          style = MaterialTheme.typography.bodySmall)
      Row {
        TextButton(onClick = onApriIstruzioni) { Text("Istruzioni per ${Marca.da(Build.MANUFACTURER).nome}") }
        TextButton(onClick = { Protezione.chiudiInterruzione(context, x.data); giro++ }) { Text("Chiudi") }
      }
    }
  }
}

/** Guida al primo avvio e voce delle Impostazioni: checklist e istruzioni per la marca. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtezioneScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val stato = rememberStatoProtezione()
  val marca = remember { Marca.da(Build.MANUFACTURER) }
  var marcaNonAperta by remember { mutableStateOf(false) }
  // vista una volta: dal prossimo avvio non si apre piu' da sola (resta in Impostazioni)
  DisposableEffect(Unit) { onDispose { Protezione.segnaGuidaVista(context.applicationContext) } }
  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text("Protezione notturna") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } })
      },
  ) { padding ->
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
          "${BuildConfig.APP_NAME} registra per 8 ore con lo schermo spento. Android tende a chiudere le app che lavorano a lungo " +
              "in background: queste impostazioni lo impediscono.",
          style = MaterialTheme.typography.bodyMedium)
      for (v in stato) VoceChecklist(v) { Protezione.apri(context, v.id) }
      HorizontalDivider()
      Text("Istruzioni per ${marca.nome}", style = MaterialTheme.typography.titleSmall)
      for ((k, riga) in marca.istruzioni.withIndex()) Text("${k + 1}. $riga", style = MaterialTheme.typography.bodyMedium)
      if (marca != Marca.PIXEL && marca != Marca.ALTRA) {
        Button(onClick = { marcaNonAperta = !Protezione.apriMarca(context, marca) }) { Text("Apri le impostazioni di ${marca.nome}") }
        if (marcaNonAperta) {
          Text("Su questa versione la schermata non si apre direttamente: segui la guida.", style = MaterialTheme.typography.bodySmall)
        }
      }
      OutlinedButton(onClick = { apriPagina(context, marca.dontKillMyApp) }) { Text("Guida dettagliata su dontkillmyapp.com") }
    }
  }
}

@Composable
private fun VoceChecklist(v: VoceProtezione, onSistema: () -> Unit) {
  Row(verticalAlignment = Alignment.Top) {
    Icon(
        if (v.ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
        if (v.ok) "A posto" else "Da sistemare",
        Modifier.padding(top = 2.dp, end = 10.dp).size(22.dp),
        tint = if (v.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
    Column(Modifier.weight(1f)) {
      Text(v.titolo, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
      Text(v.spiegazione, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      if (!v.ok) TextButton(onClick = onSistema) { Text("Sistema") }
    }
  }
}
