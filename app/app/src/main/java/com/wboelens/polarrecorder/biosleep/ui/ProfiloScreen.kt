package com.wboelens.polarrecorder.biosleep.ui

import com.wboelens.polarrecorder.R
import androidx.compose.ui.res.stringResource
import com.wboelens.polarrecorder.BuildConfig
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.wboelens.polarrecorder.biosleep.cervello.Caldo
import com.wboelens.polarrecorder.biosleep.cervello.Detp
import com.wboelens.polarrecorder.biosleep.cervello.GIORNI
import com.wboelens.polarrecorder.biosleep.cervello.Palestra
import com.wboelens.polarrecorder.biosleep.cervello.PosizioneTelefono
import com.wboelens.polarrecorder.biosleep.cervello.ProfiloAtleta
import com.wboelens.polarrecorder.biosleep.cervello.ProfiloRepo
import com.wboelens.polarrecorder.biosleep.cervello.ProfiloStore
import com.wboelens.polarrecorder.biosleep.cervello.RipianificaWorker
import com.wboelens.polarrecorder.biosleep.cervello.RisultatoCervello
import com.wboelens.polarrecorder.biosleep.cervello.SettimanaTipo
import com.wboelens.polarrecorder.biosleep.cervello.SoglieRepo
import com.wboelens.polarrecorder.biosleep.cervello.Suggerimenti
import com.wboelens.polarrecorder.biosleep.riepilogo.Riepilogo
import com.wboelens.polarrecorder.biosleep.riepilogo.RiepilogoDb
import com.wboelens.polarrecorder.biosleep.riepilogo.RiepilogoLink
import com.wboelens.polarrecorder.biosleep.ui.allenamento.ColoriBio
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
fun ProfiloScreen(onBack: () -> Unit, onApriGare: () -> Unit = {}) {
  val context = LocalContext.current.applicationContext
  val store = remember { ProfiloStore(context) }
  val scope = rememberCoroutineScope()
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
  // Palestra
  var attrezzi by remember { mutableStateOf(iniziale.palestra.attrezzatura - Palestra.SEMPRE) }
  var livello by remember { mutableStateOf(iniziale.palestra.livello) }
  val palestra = Palestra(attrezzi, livello)
  var palestraSalvata by remember { mutableStateOf(iniziale.palestra) }
  // Caldo
  var converti by remember { mutableStateOf(iniziale.caldo.convertiCorsa) }
  var oraFeriale by remember { mutableIntStateOf(iniziale.caldo.oraFeriale) }
  var oraWeekend by remember { mutableIntStateOf(iniziale.caldo.oraWeekend) }
  var posizioneOk by remember { mutableStateOf(PosizioneTelefono.permesso(context)) }
  var posizioneNegata by remember { mutableStateOf(false) }
  // CORE 2 e DETP
  var core2 by remember { mutableStateOf(iniziale.detp.core2) }
  var detp by remember { mutableStateOf(iniziale.detp.attivo) }
  var stryd by remember { mutableStateOf(iniziale.stryd) }
  var strydSalvato by remember { mutableStateOf(iniziale.stryd) }
  val chiediPosizione =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        posizioneOk = ok
        posizioneNegata = !ok
      }
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
          settimana = settimana,
          palestra = palestra,
          caldo = Caldo(converti, oraFeriale, oraWeekend),
          detp = Detp(core2, detp),
          stryd = stryd)

  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.profilo_profilo_atleta)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } })
      },
  ) { padding ->
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
          stringResource(R.string.profilo_il_coach_usa_questi),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant)

      // Gare: la preparazione si costruisce su quella che detta la stagione
      OutlinedButton(onClick = onApriGare) { Text(stringResource(R.string.profilo_gare_in_programma)) }

      // Soglie di corsa, bici e nuoto su Intervals.icu (controllate a ogni apertura)
      SezioneSoglie()

      Text(stringResource(R.string.profilo_frequenza_cardiaca), style = MaterialTheme.typography.titleSmall)
      CampoNumero(fcMax, { fcMax = it; esito = null }, "FC massima (bpm)", errFcMax)
      suggerimenti?.fcMax?.let { s ->
        Proposta("Più alta nelle sedute degli ultimi 90 giorni: $s bpm (${suggerimenti?.fcMaxData})") { fcMax = s.toString() }
      }
      CampoNumero(fcRiposo, { fcRiposo = it; esito = null }, "FC a riposo (bpm)", errFcRiposo)
      val sr = suggerimenti
      when {
        sr?.fcRiposo != null ->
            Proposta("Mediana delle ultime ${sr.nottiFcRiposo} notti ${BuildConfig.APP_NAME}: ${sr.fcRiposo} bpm. Se lasci vuoto, il coach usa questa.") {
              fcRiposo = sr.fcRiposo.toString()
            }
        sr != null -> Nota(stringResource(R.string.profilo_con_almeno_5_notti, BuildConfig.APP_NAME, sr.nottiFcRiposo.toString()))
      }

      Text(stringResource(R.string.profilo_volume), style = MaterialTheme.typography.titleSmall)
      OutlinedTextField(
          value = tetto,
          onValueChange = { tetto = it; esito = null },
          label = { Text(stringResource(R.string.profilo_ore_cardio_massime_a)) },
          singleLine = true,
          isError = errTetto,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          modifier = Modifier.fillMaxWidth())

      Text(stringResource(R.string.profilo_disponibilita), style = MaterialTheme.typography.titleSmall)
      Nota(stringResource(R.string.profilo_minuti_disponibili_per_giorno))
      for (g in GIORNI) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(NOMI_GIORNI.getValue(g), Modifier.width(110.dp))
          OutlinedTextField(
              value = minuti.getValue(g),
              onValueChange = { v -> minuti = minuti + (g to v); esito = null },
              placeholder = { Text(stringResource(R.string.profilo_nessun_limite)) },
              suffix = { Text(stringResource(R.string.profilo_min)) },
              singleLine = true,
              isError = g in errGiorni,
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              modifier = Modifier.weight(1f))
        }
      }

      // --- Settimana tipo ------------------------------------------------------------------------
      Text(stringResource(R.string.profilo_settimana_tipo), style = MaterialTheme.typography.titleSmall)
      Nota(stringResource(R.string.profilo_nei_giorni_feriali_il))
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

      // --- Palestra ------------------------------------------------------------------------------
      Text(stringResource(R.string.profilo_palestra), style = MaterialTheme.typography.titleSmall)
      Nota(stringResource(R.string.profilo_attrezzatura_disponibile_il_coach))
      for ((k, etichetta) in Palestra.ATTREZZI) {
        val sempre = k in Palestra.SEMPRE
        Row(verticalAlignment = Alignment.CenterVertically) {
          Checkbox(
              checked = sempre || k in attrezzi,
              onCheckedChange = { on -> attrezzi = if (on) attrezzi + k else attrezzi - k; esito = null },
              enabled = !sempre)
          Text(etichetta + if (sempre) " (sempre incluso)" else "")
        }
      }
      Text(stringResource(R.string.profilo_livello), style = MaterialTheme.typography.labelLarge)
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((k, etichetta) in Palestra.LIVELLI) FilterChip(selected = livello == k, onClick = { livello = k; esito = null }, label = { Text(etichetta) })
      }
      Nota(stringResource(R.string.profilo_principiante_le_prime_4))
      // nel riepilogo piu' recente: sedute di forza tolte dal coach (e perche')
      ultimo?.motivi?.filter { Regex("""forza del .* tolta""", RegexOption.IGNORE_CASE).containsMatchIn(it) }?.forEach {
        Text("• " + it.trim(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }

      // --- Caldo --------------------------------------------------------------------------------
      Text(stringResource(R.string.profilo_caldo), style = MaterialTheme.typography.titleSmall)
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.profilo_nei_giorni_caldi_trasforma), Modifier.weight(1f))
        Switch(checked = converti, onCheckedChange = { converti = it; esito = null })
      }
      Text(stringResource(R.string.profilo_di_solito_mi_alleno), style = MaterialTheme.typography.labelLarge)
      OraAllenamento("Giorni feriali", oraFeriale) { oraFeriale = it; esito = null }
      OraAllenamento("Weekend", oraWeekend) { oraWeekend = it; esito = null }
      Nota(stringResource(R.string.profilo_il_coach_legge_la))
      if (posizioneOk) {
        Nota(stringResource(R.string.profilo_posizione_approssimativa_consentita_il))
      } else {
        Nota(stringResource(R.string.profilo_serve_per_le_previsioni))
        RigaInformativa("Come viene usata la posizione")
        OutlinedButton(onClick = { chiediPosizione.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) {
          Text(stringResource(R.string.profilo_consenti_la_posizione_approssimativa))
        }
        if (posizioneNegata) {
          Nota(stringResource(R.string.profilo_se_android_non_la, BuildConfig.APP_NAME))
        }
      }

      // --- Sensori: Stryd, CORE 2 e DETP ------------------------------------------------------------
      Text(stringResource(R.string.profilo_sensori), style = MaterialTheme.typography.titleSmall)
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.profilo_ho_uno_stryd), Modifier.weight(1f))
        Switch(checked = stryd, onCheckedChange = { stryd = it; esito = null })
      }
      Nota(
          stringResource(R.string.profilo_le_sedute_di_qualita))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.profilo_ho_un_sensore_core), Modifier.weight(1f))
        Switch(checked = core2, onCheckedChange = { core2 = it; if (!it) detp = false; esito = null })
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.profilo_applica_il_protocollo_detp), Modifier.weight(1f), color = if (core2) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(checked = detp, onCheckedChange = { detp = it; esito = null }, enabled = core2)
      }
      Nota(
          stringResource(R.string.profilo_con_il_detp_il))

      Button(
          onClick = {
            store.salva(profilo())
            // Stryd cambiato: la CP diventa (o smette di essere) necessaria, si ricontrollano le soglie
            if (stryd != strydSalvato) {
              strydSalvato = stryd
              scope.launch(Dispatchers.IO) { SoglieRepo.controlla(context, forza = true) }
            }
            esito = "Salvato: vale dalla prossima pianificazione settimanale"
            // palestra cambiata: le schede della settimana sono gia' scritte, si propone di rifarle
            if (palestra != palestraSalvata) conferma = true
            palestraSalvata = palestra
          },
          enabled = valido,
      ) {
        Text(stringResource(R.string.profilo_salva))
      }
      if (!valido) {
        Text(
            stringResource(R.string.profilo_valori_fuori_dai_limiti),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
      }
      esito?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

      // --- Ripianifica subito (solo su richiesta) -------------------------------------------------
      val inCorso = lavoro?.state == WorkInfo.State.RUNNING || lavoro?.state == WorkInfo.State.ENQUEUED
      OutlinedButton(onClick = { conferma = true }, enabled = valido && !inCorso) { Text(stringResource(R.string.profilo_ripianifica_questa_settimana)) }
      when {
        inCorso -> Nota(stringResource(R.string.profilo_ripianificazione_in_corso_richiede))
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
        title = { Text(stringResource(R.string.profilo_ripianificare_la_settimana)) },
        text = {
          Text(stringResource(R.string.profilo_le_sedute_da_oggi))
        },
        confirmButton = {
          TextButton(
              onClick = {
                conferma = false
                store.salva(profilo()) // si ripianifica con quello che vedi
                avviatoQui = true
                RipianificaWorker.avvia(context)
              }) {
                Text(stringResource(R.string.profilo_ripianifica))
              }
        },
        dismissButton = { TextButton(onClick = { conferma = false }) { Text(stringResource(R.string.profilo_annulla)) } },
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
      if (facoltativo) FilterChip(selected = scelto == null, onClick = { onScegli(null) }, label = { Text(stringResource(R.string.profilo_nessuno)) })
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

/** Ora di allenamento abituale (5:00-21:00), per la previsione del caldo. */
@Composable
private fun OraAllenamento(titolo: String, ora: Int, onCambia: (Int) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(titolo, Modifier.weight(1f))
    OutlinedButton(onClick = { onCambia(ora - 1) }, enabled = ora > Caldo.ORE.first) { Text("−") }
    Text("%02d:00".format(ora), Modifier.width(64.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    OutlinedButton(onClick = { onCambia(ora + 1) }, enabled = ora < Caldo.ORE.last) { Text("+") }
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
    TextButton(onClick = onUsa) { Text(stringResource(R.string.profilo_usa)) }
  }
}

@Composable
private fun Nota(testo: String) =
    Text(testo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
