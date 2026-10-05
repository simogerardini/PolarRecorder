package com.wboelens.polarrecorder.biosleep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.cervello.CoachStato
import com.wboelens.polarrecorder.biosleep.cervello.CoachWorker
import com.wboelens.polarrecorder.biosleep.tag.TagDb
import com.wboelens.polarrecorder.biosleep.tag.Vocabolario
import com.wboelens.polarrecorder.biosleep.ui.allenamento.DateIt
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Gruppo di tag selezionabili. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipTag(chiavi: List<String>, scelti: Set<String>, etichetta: (String) -> String = Vocabolario::etichetta, onCambia: (Set<String>) -> Unit) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    for (k in chiavi) {
      FilterChip(
          selected = k in scelti,
          onClick = { onCambia(if (k in scelti) scelti - k else scelti + k) },
          label = { Text(etichetta(k)) })
    }
  }
}

/** Tag di giorno salvati, letti fuori dal main thread e riletti a ogni modifica. */
@Composable
private fun rememberTagGiorno(data: String): Set<String>? {
  val context = LocalContext.current.applicationContext
  val versione by TagDb.versione.collectAsState()
  val tag by produceState<Set<String>?>(null, data, versione) { value = withContext(Dispatchers.IO) { TagDb.get(context).giorno(data) } }
  return tag
}

/** Pulsanti rapidi della sera, sulla scheda Notte: valgono per la mattina del risveglio. */
@Composable
fun TagSera() {
  val context = LocalContext.current.applicationContext
  val data = remember { Vocabolario.mattinaDellaNotte(LocalDateTime.now()).toString() }
  val scelti = rememberTagGiorno(data) ?: return
  val scope = rememberCoroutineScope()
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text("Stasera", style = MaterialTheme.typography.labelLarge)
    ChipTag(Vocabolario.GIORNO_SERA, scelti) { nuovi ->
      // tutti gli altri tag di quel giorno restano: si cambiano solo quelli della sera
      scope.launch(Dispatchers.IO) { TagDb.get(context).impostaGiorno(data, nuovi) }
    }
  }
}

/** Riga nella schermata Oggi: i tag di oggi e il collegamento per modificarli. */
@Composable
fun RigaTagOggi(onApri: (String) -> Unit) {
  val oggi = LocalDate.now().toString()
  val scelti = rememberTagGiorno(oggi) ?: return
  Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(Icons.Filled.Sell, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        if (scelti.isEmpty()) "  Nessun tag per oggi" else "  " + scelti.joinToString(" · ") { Vocabolario.etichetta(it) },
        Modifier.weight(1f),
        style = MaterialTheme.typography.bodySmall)
    androidx.compose.material3.TextButton(onClick = { onApri(oggi) }) { Text(if (scelti.isEmpty()) "Aggiungi tag" else "Modifica") }
  }
}

/** Tag di una seduta svolta (pannello del calendario): si salvano subito a ogni tocco. */
@Composable
fun TagSeduta(id: String, data: String) {
  val context = LocalContext.current.applicationContext
  val versione by TagDb.versione.collectAsState()
  val scelti by produceState<Set<String>?>(null, id, versione) { value = withContext(Dispatchers.IO) { TagDb.get(context).seduta(id) } }
  val s = scelti ?: return
  val scope = rememberCoroutineScope()
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text("Tag per il coach", style = MaterialTheme.typography.labelLarge)
    ChipTag(Vocabolario.SEDUTA, s) { nuovi -> scope.launch(Dispatchers.IO) { TagDb.get(context).impostaSeduta(id, data, nuovi) } }
  }
}

private fun ora(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

/**
 * Tag di un giorno (anche futuro, per esempio un viaggio). Salvando i tag di oggi mentre il coach
 * li aspetta (fino a 15 minuti dall'invio della notte), il coach parte subito.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagScreen(dataIniziale: String, onBack: () -> Unit) {
  val context = LocalContext.current.applicationContext
  val scope = rememberCoroutineScope()
  var data by remember { mutableStateOf(LocalDate.parse(dataIniziale)) }
  val salvati = rememberTagGiorno(data.toString())
  var scelti by remember(data) { mutableStateOf<Set<String>?>(null) }
  LaunchedEffect(data, salvati) { if (scelti == null && salvati != null) scelti = salvati }
  var esito by remember(data) { mutableStateOf<String?>(null) }
  CoachStato.versione.collectAsState().value
  val stato = remember { CoachStato(context) }
  val oggi = LocalDate.now()

  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text("Tag") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } })
      },
  ) { padding ->
    val s = scelti
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { data = data.minusDays(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Giorno prima") }
        Text(
            (if (data == oggi) "Oggi · " else "") + DateIt.lunga(data),
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { data = data.plusDays(1) }, enabled = data < oggi.plusDays(14)) {
          Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Giorno dopo")
        }
      }
      // A che punto e' il coach di oggi: decide se i tag pesano gia' sulla seduta di oggi
      if (data == oggi) {
        val testo =
            when {
              stato.attesaTagFinoMs > System.currentTimeMillis() ->
                  "Il coach aspetta i tuoi tag fino alle ${ora(stato.attesaTagFinoMs)}: parte appena salvi."
              stato.fatto(oggi.toString()) -> "Il coach di oggi è già partito: questi tag valgono dal prossimo run."
              else -> "Il coach li userà al prossimo run."
            }
        Text(testo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      if (s == null) return@Column
      val cambia = { n: Set<String> ->
        scelti = n
        esito = null
      }
      Gruppo("Sera") { ChipTag(Vocabolario.GIORNO_SERA, s, onCambia = cambia) }
      Gruppo("Notte") { ChipTag(Vocabolario.GIORNO_NOTTE, s, onCambia = cambia) }
      Gruppo("Salute e contesto") { ChipTag(Vocabolario.GIORNO_CONTESTO, s, onCambia = cambia) }
      Gruppo("Corpo") {
        ChipTag(listOf("dolore_muscolare"), s, onCambia = cambia)
        Text("Infortunio", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChipTag(Vocabolario.ZONE.map { "infortunio:$it" }, s, { it.substringAfter(':').replaceFirstChar { c -> c.uppercase() } }, cambia)
      }
      Text(
          "Alcol, cena tardiva, caffeina, stress, viaggio, malattia, sonno disturbato, caldo e altitudine " +
              "escludono la notte dal calcolo della tua HRV abituale.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
      Button(
          onClick = {
            val giorno = data.toString()
            scope.launch {
              val anticipato =
                  withContext(Dispatchers.IO) {
                    TagDb.get(context).impostaGiorno(giorno, s)
                    giorno == LocalDate.now().toString() && CoachWorker.anticipaPerTag(context, giorno)
                  }
              esito = if (anticipato) "Salvato: il coach parte adesso" else "Salvato"
            }
          },
          enabled = s != salvati,
      ) {
        Text("Salva")
      }
      esito?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
  }
}

@Composable
private fun Gruppo(titolo: String, contenuto: @Composable () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(titolo, style = MaterialTheme.typography.titleSmall)
    contenuto()
  }
}
