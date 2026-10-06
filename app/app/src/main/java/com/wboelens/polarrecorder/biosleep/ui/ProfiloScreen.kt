package com.wboelens.polarrecorder.biosleep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.wboelens.polarrecorder.biosleep.cervello.GIORNI
import com.wboelens.polarrecorder.biosleep.cervello.ProfiloAtleta
import com.wboelens.polarrecorder.biosleep.cervello.ProfiloRepo
import com.wboelens.polarrecorder.biosleep.cervello.ProfiloStore
import com.wboelens.polarrecorder.biosleep.cervello.RipianificaWorker
import com.wboelens.polarrecorder.biosleep.cervello.RisultatoCervello
import com.wboelens.polarrecorder.biosleep.cervello.SettimanaTipo
import com.wboelens.polarrecorder.biosleep.cervello.Suggerimenti
import com.wboelens.polarrecorder.biosleep.riepilogo.Riepilogo
import com.wboelens.polarrecorder.biosleep.riepilogo.RiepilogoDb
import com.wboelens.polarrecorder.biosleep.riepilogo.RiepilogoLink
import com.wboelens.polarrecorder.biosleep.ui.allenamento.ColoriBio
import java.time.LocalDate
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
  // Settimana tipo
  var lungoBici by remember { mutableStateOf(iniziale.settimana.lungoBici) }
  var lungoCorsa by remember { mutableStateOf(iniziale.settimana.lungoCorsa) }
  var riposo by remember { mutableStateOf(iniziale.settimana.riposo) }
  var sedute by remember { mutableStateOf(iniziale.settimana.sedute) }
  val settimana = SettimanaTipo(lungoBici, lungoCorsa, riposo, sedute)
  // Avvisi del coach sulla settimana tipo, dal riepilogo piu' recente
  val versioneRie by RiepilogoDb.versione.collectAsState()
  val ultimo by produceState<Riepilogo?>(null, versioneRie) { value = withContext(Dispatchers.IO) { RiepilogoDb.get(context).ultimo() } }
  // Ripianificazione (WorkManager): stato e apertura del riepilogo a fine run
  val lavori by remember { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(RipianificaWorker.NOME) }.collectAsState(emptyList())
  val lavoro = lavori.lastOrNull()
  var avviatoQui by remember { mutableStateOf(false) }
  var conferma by remember { mutableStateOf(false) }
  LaunchedEffect(lavoro?.state) {
    if (avviatoQui && lavoro?.state == WorkInfo.State.SUCCEEDED) {
      avviatoQui = false
      val out = lavoro.outputData
      if (out.getString(RipianificaWorker.K_ESITO) == RisultatoCervello.PIANIFICATA) {
        RiepilogoLink.richiesta.value = "riepilogo/" + (out.getString(RipianificaWorker.K_DATA) ?: LocalDate.now().toString())
      }
    }
  }

  val errFcMax = fcMax.isNotBlank() && numeroIntero(fcMax, ProfiloAtleta.LIMITI_FC_MAX) == null
  val errFcRiposo = fcRiposo.isNotBlank() && numeroIntero(fcRiposo, ProfiloAtleta.LIMITI_FC_RIPOSO) == null
  val tettoNum = tetto.trim().replace(',', '.').toDoubleOrNull()
  val errTetto = tetto.isNotBlank() && (tettoNum == null || tettoNum !in ProfiloAtleta.TETTO_MIN..ProfiloAtleta.TETTO_MAX)
  val errGiorni = minuti.filterValues { it.isNotBlank() && numeroIntero(it, ProfiloAtleta.LIMITI_MINUTI) == null }.keys
  val valido = !errFcMax && !errFcRiposo && !errTetto && errGiorni.isEmpty() && settimana.errori().isEmpty()

  fun profilo() =
      ProfiloAtleta(
          fcMax = numeroIntero(fcMax, ProfiloAtleta.LIMITI_FC_MAX),
          fcRiposo = numeroIntero(fcRiposo, ProfiloAtleta.LIMITI_FC_RIPOSO),
          tettoOre = tettoNum.takeIf { tetto.isNotBlank() },
          disponibilita = minuti.mapNotNull { (g, v) -> numeroIntero(v, ProfiloAtleta.LIMITI_MINUTI)?.let { g to it } }.toMap(),
          settimana = settimana)

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

      // Soglie di corsa, bici e nuoto su Intervals.icu (controllate a ogni apertura)
      SezioneSoglie()

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

      // --- Settimana tipo ------------------------------------------------------------------------
      Text("Settimana tipo", style = MaterialTheme.typography.titleSmall)
      Nota("Nei giorni feriali il coach mette al massimo una seduta cardio, più la forza.")
      SceltaGiorno("Lungo in bici", lungoBici, vietati = setOfNotNull(lungoCorsa, riposo)) { lungoBici = it!!; esito = null }
      SceltaGiorno("Lungo di corsa", lungoCorsa, vietati = setOfNotNull(lungoBici, riposo)) { lungoCorsa = it!!; esito = null }
      SceltaGiorno("Riposo", riposo, vietati = setOf(lungoBici, lungoCorsa), facoltativo = true) { riposo = it; esito = null }
      for (f in SettimanaTipo.DISCIPLINE) {
        val l = SettimanaTipo.LIMITI.getValue(f)
        Contatore(NOMI_DISCIPLINE.getValue(f), sedute.getValue(f), l) { n -> sedute = sedute + (f to n); esito = null }
      }
      // Avvisi del coach sull'ultima pianificazione
      ultimo?.avvisi?.lines()?.filter { it.contains("settimana tipo non valida", ignoreCase = true) }?.forEach {
        Text(it.trim(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      }
      val senzaSpazio = ultimo?.motivi?.filter { it.contains("spazio nella settimana tipo", ignoreCase = true) }.orEmpty()
      if (senzaSpazio.isNotEmpty()) {
        Text(
            senzaSpazio.joinToString("\n") { "• " + it.trim() } +
                "\nLibera un giorno (togli il riposo o sposta un lungo) oppure riduci le sedute.",
            style = MaterialTheme.typography.bodySmall,
            color = ColoriBio.giallo)
      }

      Button(
          onClick = {
            store.salva(profilo())
            esito = "Salvato: vale dalla prossima pianificazione settimanale"
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

      // --- Ripianifica subito (solo su richiesta) -------------------------------------------------
      val inCorso = lavoro?.state == WorkInfo.State.RUNNING || lavoro?.state == WorkInfo.State.ENQUEUED
      OutlinedButton(onClick = { conferma = true }, enabled = valido && !inCorso) { Text("Ripianifica questa settimana") }
      when {
        inCorso -> Nota("Ripianificazione in corso: richiede qualche minuto, puoi lasciare questa schermata.")
        lavoro?.state == WorkInfo.State.SUCCEEDED -> {
          val e = lavoro.outputData.getString(RipianificaWorker.K_ESITO)
          if (e != RisultatoCervello.PIANIFICATA) {
            Text(
                "Ripianificazione non riuscita: " + (lavoro.outputData.getString(RipianificaWorker.K_ERRORE) ?: e ?: "errore"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
          }
        }
      }
    }
  }

  if (conferma) {
    AlertDialog(
        onDismissRequest = { conferma = false },
        title = { Text("Ripianificare la settimana?") },
        text = {
          Text("Le sedute da oggi a domenica verranno ricalcolate con questo profilo. Le sedute passate restano invariate.")
        },
        confirmButton = {
          TextButton(
              onClick = {
                conferma = false
                store.salva(profilo()) // si ripianifica con quello che vedi
                avviatoQui = true
                RipianificaWorker.avvia(context)
              }) {
                Text("Ripianifica")
              }
        },
        dismissButton = { TextButton(onClick = { conferma = false }) { Text("Annulla") } },
    )
  }
}

private val NOMI_DISCIPLINE = mapOf("nuoto" to "Sedute di nuoto", "bici" to "Sedute in bici", "corsa" to "Sedute di corsa", "forza" to "Sedute di forza")
private val SIGLE = mapOf("lun" to "Lun", "mar" to "Mar", "mer" to "Mer", "gio" to "Gio", "ven" to "Ven", "sab" to "Sab", "dom" to "Dom")

/** Un giorno della settimana tra sette chip; quelli gia' usati da un'altra scelta sono disattivati. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SceltaGiorno(titolo: String, scelto: String?, vietati: Set<String>, facoltativo: Boolean = false, onScegli: (String?) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(titolo, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      if (facoltativo) FilterChip(selected = scelto == null, onClick = { onScegli(null) }, label = { Text("Nessuno") })
      for (g in GIORNI) {
        FilterChip(
            selected = scelto == g,
            onClick = { onScegli(g) },
            enabled = g !in vietati || scelto == g,
            label = { Text(SIGLE.getValue(g)) })
      }
    }
  }
}

/** Numero di sedute con i limiti del coach. */
@Composable
private fun Contatore(titolo: String, valore: Int, limiti: IntRange, onCambia: (Int) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(titolo, Modifier.weight(1f))
    OutlinedButton(onClick = { onCambia(valore - 1) }, enabled = valore > limiti.first) { Text("−") }
    Text("$valore", Modifier.width(36.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    OutlinedButton(onClick = { onCambia(valore + 1) }, enabled = valore < limiti.last) { Text("+") }
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
