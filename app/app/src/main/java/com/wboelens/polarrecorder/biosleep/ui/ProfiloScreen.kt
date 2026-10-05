package com.wboelens.polarrecorder.biosleep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.cervello.GIORNI
import com.wboelens.polarrecorder.biosleep.cervello.ProfiloAtleta
import com.wboelens.polarrecorder.biosleep.cervello.ProfiloRepo
import com.wboelens.polarrecorder.biosleep.cervello.ProfiloStore
import com.wboelens.polarrecorder.biosleep.cervello.Suggerimenti
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val NOMI_GIORNI =
    mapOf("lun" to "Lunedì", "mar" to "Martedì", "mer" to "Mercoledì", "gio" to "Giovedì", "ven" to "Venerdì", "sab" to "Sabato", "dom" to "Domenica")

private fun numeroIntero(t: String, limiti: IntRange): Int? = t.trim().toIntOrNull()?.takeIf { it in limiti }

/**
 * Profilo atleta per il cervello del coach: FC massima e a riposo (hanno la precedenza su quelle di
 * Intervals.icu), ore cardio massime a settimana, minuti disponibili per giorno.
 * Campo vuoto = non impostato: per le FC vale Intervals.icu, per i giorni nessun limite.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfiloScreen(onBack: () -> Unit) {
  val context = LocalContext.current.applicationContext
  val store = remember { ProfiloStore(context) }
  val iniziale = remember { store.leggi() }
  val suggerimenti by produceState<Suggerimenti?>(null) { value = withContext(Dispatchers.IO) { ProfiloRepo.suggerimenti(context) } }

  var fcMax by remember { mutableStateOf(iniziale.fcMax?.toString() ?: "") }
  var fcRiposo by remember { mutableStateOf(iniziale.fcRiposo?.toString() ?: "") }
  var tetto by remember { mutableStateOf(iniziale.tettoOre?.toString()?.replace('.', ',') ?: "") }
  var minuti by remember { mutableStateOf(GIORNI.associateWith { iniziale.disponibilita[it]?.toString() ?: "" }) }
  var esito by remember { mutableStateOf<String?>(null) }

  val errFcMax = fcMax.isNotBlank() && numeroIntero(fcMax, ProfiloAtleta.LIMITI_FC_MAX) == null
  val errFcRiposo = fcRiposo.isNotBlank() && numeroIntero(fcRiposo, ProfiloAtleta.LIMITI_FC_RIPOSO) == null
  val tettoNum = tetto.trim().replace(',', '.').toDoubleOrNull()
  val errTetto = tetto.isNotBlank() && (tettoNum == null || tettoNum !in ProfiloAtleta.TETTO_MIN..ProfiloAtleta.TETTO_MAX)
  val errGiorni = minuti.filterValues { it.isNotBlank() && numeroIntero(it, ProfiloAtleta.LIMITI_MINUTI) == null }.keys
  val valido = !errFcMax && !errFcRiposo && !errTetto && errGiorni.isEmpty()

  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text("Profilo atleta") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } })
      },
  ) { padding ->
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
          "Il coach usa questi valori al posto di quelli di Intervals.icu. Un campo vuoto non viene mandato.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant)

      Text("Frequenza cardiaca", style = MaterialTheme.typography.titleSmall)
      CampoNumero(fcMax, { fcMax = it; esito = null }, "FC massima (bpm)", errFcMax)
      suggerimenti?.fcMax?.let { s ->
        Proposta("Più alta nelle sedute degli ultimi 90 giorni: $s bpm (${suggerimenti?.fcMaxData})") { fcMax = s.toString() }
      }
      CampoNumero(fcRiposo, { fcRiposo = it; esito = null }, "FC a riposo (bpm)", errFcRiposo)
      val sr = suggerimenti
      when {
        sr?.fcRiposo != null ->
            Proposta("Mediana delle ultime ${sr.nottiFcRiposo} notti BioSleep: ${sr.fcRiposo} bpm. Se lasci vuoto, il coach usa questa.") {
              fcRiposo = sr.fcRiposo.toString()
            }
        sr != null -> Nota("Con almeno 5 notti BioSleep l'app propone la FC a riposo misurata (ora ${sr.nottiFcRiposo}).")
      }

      Text("Volume", style = MaterialTheme.typography.titleSmall)
      OutlinedTextField(
          value = tetto,
          onValueChange = { tetto = it; esito = null },
          label = { Text("Ore cardio massime a settimana (palestra esclusa)") },
          singleLine = true,
          isError = errTetto,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          modifier = Modifier.fillMaxWidth())

      Text("Disponibilità", style = MaterialTheme.typography.titleSmall)
      Nota("Minuti disponibili per giorno. 0 = giorno non disponibile, vuoto = nessun limite.")
      for (g in GIORNI) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(NOMI_GIORNI.getValue(g), Modifier.width(110.dp))
          OutlinedTextField(
              value = minuti.getValue(g),
              onValueChange = { v -> minuti = minuti + (g to v); esito = null },
              placeholder = { Text("nessun limite") },
              suffix = { Text("min") },
              singleLine = true,
              isError = g in errGiorni,
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              modifier = Modifier.weight(1f))
        }
      }

      Button(
          onClick = {
            store.salva(
                ProfiloAtleta(
                    fcMax = numeroIntero(fcMax, ProfiloAtleta.LIMITI_FC_MAX),
                    fcRiposo = numeroIntero(fcRiposo, ProfiloAtleta.LIMITI_FC_RIPOSO),
                    tettoOre = tettoNum.takeIf { tetto.isNotBlank() },
                    disponibilita =
                        minuti.mapNotNull { (g, v) -> numeroIntero(v, ProfiloAtleta.LIMITI_MINUTI)?.let { g to it } }.toMap()))
            esito = "Salvato: vale dal prossimo run del coach"
          },
          enabled = valido,
      ) {
        Text("Salva")
      }
      if (!valido) {
        Text(
            "Valori fuori dai limiti: FC massima 120–230, FC a riposo 30–100, ore 1–30, minuti 0–600.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
      }
      esito?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
  }
}

@Composable
private fun CampoNumero(valore: String, onCambia: (String) -> Unit, etichetta: String, errore: Boolean) {
  OutlinedTextField(
      value = valore,
      onValueChange = onCambia,
      label = { Text(etichetta) },
      singleLine = true,
      isError = errore,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Proposta(testo: String, onUsa: () -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(testo, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick = onUsa) { Text("Usa") }
  }
}

@Composable
private fun Nota(testo: String) =
    Text(testo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
