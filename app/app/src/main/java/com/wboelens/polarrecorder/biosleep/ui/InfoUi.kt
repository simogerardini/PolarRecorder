package com.wboelens.polarrecorder.biosleep.ui

import com.wboelens.polarrecorder.R
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.produceState
import com.wboelens.polarrecorder.biosleep.cervello.Cervello
import com.wboelens.polarrecorder.BuildConfig
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.info.Componente
import com.wboelens.polarrecorder.biosleep.info.Licenze
import java.io.IOException

/** Apre una pagina web in una Custom Tab (o nel browser, se le Custom Tab non ci sono). */
fun apriPagina(context: Context, url: String) {
  try {
    CustomTabsIntent.Builder().build().apply { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }.launchUrl(context, Uri.parse(url))
  } catch (e: ActivityNotFoundException) {
    try {
      context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e2: ActivityNotFoundException) {
      // nessun browser installato
    }
  }
}

/** Collegamento all'informativa, da mettere accanto a ogni consenso (Intervals.icu, posizione). */
@Composable
fun RigaInformativa(testo: String = "Informativa sulla privacy") {
  val context = LocalContext.current
  TextButton(onClick = { apriPagina(context, Licenze.PRIVACY_URL) }) { Text(testo) }
}

/** Sezione "Informazioni" delle Impostazioni. */
@Composable
fun SezioneInformazioni(onApriLicenze: () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(stringResource(R.string.info_informazioni), style = MaterialTheme.typography.titleSmall)
    val context = LocalContext.current.applicationContext
    val versioneCoach by produceState<String?>(null) { value = withContext(Dispatchers.IO) { Cervello.versione(context) } }
    Text(
        "${BuildConfig.APP_NAME} ${BuildConfig.VERSION_NAME}" + (versioneCoach?.let { " · coach $it" } ?: ""),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    RigaInformativa()
    TextButton(onClick = onApriLicenze) { Text(stringResource(R.string.info_licenze_open_source)) }
  }
}

/** Polar Recorder, da cui nasce BioSleep, e i componenti di terze parti con le loro licenze. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenzeScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  var aperto by remember { mutableStateOf<Componente?>(null) }
  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.info_licenze_open_source)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } })
      },
  ) { padding ->
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(Licenze.ORIGINE.descrizione, style = MaterialTheme.typography.bodyMedium)
      RigaComponente(Licenze.ORIGINE) { aperto = it }
      HorizontalDivider()
      Text(stringResource(R.string.info_componenti_usati), style = MaterialTheme.typography.titleSmall)
      for (c in Licenze.COMPONENTI) RigaComponente(c) { aperto = it }
      HorizontalDivider()
      Text(Licenze.NOTA_GOOGLE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
  aperto?.let { c ->
    val testo = remember(c.file) { leggiAsset(context, "licenze/${c.file}") }
    AlertDialog(
        onDismissRequest = { aperto = null },
        title = { Text("${c.nome} · ${c.licenza}") },
        text = {
          Text(
              testo,
              fontFamily = FontFamily.Monospace,
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()))
        },
        confirmButton = { TextButton(onClick = { aperto = null }) { Text(stringResource(R.string.info_chiudi)) } },
        dismissButton = { TextButton(onClick = { apriPagina(context, c.url) }) { Text(stringResource(R.string.info_sito_del_progetto)) } },
    )
  }
}

@Composable
private fun RigaComponente(c: Componente, onApri: (Componente) -> Unit) {
  Column(Modifier.fillMaxWidth().clickable { onApri(c) }.padding(vertical = 4.dp)) {
    Text(c.nome, style = MaterialTheme.typography.bodyLarge)
    Text("${c.licenza} · ${c.descrizione}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

private fun leggiAsset(context: Context, percorso: String): String =
    try {
      context.assets.open(percorso).bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (e: IOException) {
      "Testo della licenza non trovato ($percorso)."
    }
