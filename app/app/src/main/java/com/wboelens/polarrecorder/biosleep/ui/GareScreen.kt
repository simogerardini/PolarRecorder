package com.wboelens.polarrecorder.biosleep.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.cervello.Cervello
import com.wboelens.polarrecorder.biosleep.cervello.EsitoGara
import com.wboelens.polarrecorder.biosleep.cervello.EsitoGare
import com.wboelens.polarrecorder.biosleep.cervello.Gara
import com.wboelens.polarrecorder.biosleep.cervello.Gare
import com.wboelens.polarrecorder.biosleep.cervello.RipianificaWorker
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsSettings
import com.wboelens.polarrecorder.biosleep.intervals.OAuthIntervals
import com.wboelens.polarrecorder.biosleep.ui.allenamento.ColoriBio
import com.wboelens.polarrecorder.biosleep.ui.allenamento.DateIt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Modulo aperto: nuova gara (gara = null) o modifica. */
private data class Modulo(val gara: Gara?)

/**
 * Gare su Intervals.icu, lette e scritte dal cervello: elenco con quella che detta la
 * preparazione, modulo per crearne o modificarne una, eliminazione. Dopo un salvataggio il
 * coach puo' chiedere di ripianificare subito la settimana.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GareScreen(onBack: () -> Unit) {
  val context = LocalContext.current.applicationContext
  var giro by remember { mutableIntStateOf(0) }
  var elenco by remember { mutableStateOf<EsitoGare?>(null) }
  var modulo by remember { mutableStateOf<Modulo?>(null) }
  var chiediRipianifica by remember { mutableStateOf(false) }
  var messaggio by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(giro) {
    elenco = null
    elenco =
        withContext(Dispatchers.IO) {
          val c = IntervalsSettings(context).credenziali ?: return@withContext EsitoGare("errore", emptyList(), "Intervals.icu non collegato")
          Cervello.gare(context, c)
        }
  }

  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text("Gare") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } })
      },
      floatingActionButton = { FloatingActionButton(onClick = { modulo = Modulo(null) }) { Icon(Icons.Filled.Add, "Nuova gara") } },
  ) { padding ->
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      messaggio?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
      val e = elenco
      when {
        e == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        e.esito == Gare.PERMESSO_MANCANTE -> {
          Text("Il collegamento non permette di leggere il calendario: ricollega Intervals.icu.", color = MaterialTheme.colorScheme.error)
          Button(onClick = { OAuthIntervals.avvia(context) }) { Text("Ricollega Intervals.icu") }
        }
        e.esito != Gare.OK -> {
          Text("Gare non lette: ${e.errore ?: "errore"}", color = MaterialTheme.colorScheme.error)
          OutlinedButton(onClick = { giro++ }) { Text("Riprova") }
        }
        e.gare.isEmpty() ->
            Text(
                "Nessuna gara in programma: il coach segue il ciclo continuo. Aggiungine una con +.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> for (g in e.gare) CardGara(g) { modulo = Modulo(g) }
      }
    }
  }

  modulo?.let { m ->
    ModuloGara(
        m.gara,
        onChiudi = { modulo = null },
        onSalvata = { r ->
          modulo = null
          giro++
          if (r.ripianifica) chiediRipianifica = true
        },
        onNonTrovata = {
          modulo = null
          messaggio = "La gara non c'è più su Intervals.icu: elenco aggiornato"
          giro++
        })
  }

  if (chiediRipianifica) {
    AlertDialog(
        onDismissRequest = {
          chiediRipianifica = false
          messaggio = "La modifica vale dalla prossima pianificazione settimanale"
        },
        title = { Text("Aggiorno subito il piano della settimana?") },
        text = { Text("Le sedute da oggi a domenica verranno ricalcolate tenendo conto delle gare. Le sedute passate restano invariate.") },
        confirmButton = {
          TextButton(
              onClick = {
                chiediRipianifica = false
                RipianificaWorker.avvia(context)
                messaggio = "Ripianificazione in corso: richiede qualche minuto, il riepilogo arriverà con la notifica del coach"
              }) {
                Text("Sì, ripianifica")
              }
        },
        dismissButton = {
          TextButton(
              onClick = {
                chiediRipianifica = false
                messaggio = "La modifica vale dalla prossima pianificazione settimanale"
              }) {
                Text("No")
              }
        },
    )
  }
}

@Composable
private fun CardGara(g: Gara, onApri: () -> Unit) {
  val colori =
      if (g.obiettivo) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()
  Card(Modifier.fillMaxWidth().clickable(onClick = onApri), colors = colori) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (g.obiettivo) Icon(Icons.Filled.Flag, null, Modifier.padding(end = 6.dp), tint = MaterialTheme.colorScheme.primary)
        Text(g.nome, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (g.calda) Icon(Icons.Filled.WbSunny, "Gara calda", Modifier.padding(end = 6.dp), tint = ColoriBio.giallo)
        Text("Gara ${g.priorita}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
      }
      Text(
          DateIt.lunga(g.data) + (g.settimane?.let { " · tra $it settimane" } ?: ""),
          style = MaterialTheme.typography.bodyMedium)
      if (g.obiettivo) Text("Detta la preparazione", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
      val d = Gare.distanzaPerModulo(g)
      if (d == null) {
        Text(
            "Distanza non riconosciuta: tocca per indicarla, il coach la usa per la preparazione",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
      } else {
        Text(Gare.DISTANZE.first { it.first == d }.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

private fun utc(d: LocalDate) = d.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

/** Nuova gara o modifica (con Elimina). Gli esiti del cervello restano nel modulo. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ModuloGara(gara: Gara?, onChiudi: () -> Unit, onSalvata: (EsitoGara) -> Unit, onNonTrovata: () -> Unit) {
  val context = LocalContext.current.applicationContext
  val scope = rememberCoroutineScope()
  val oggi = remember { LocalDate.now() }
  var nome by remember { mutableStateOf(gara?.let { Gare.nomeBase(it.nome) } ?: "") }
  var data by remember { mutableStateOf(gara?.data) }
  var priorita by remember { mutableStateOf(gara?.priorita) }
  var distanza by remember { mutableStateOf(gara?.let { Gare.distanzaPerModulo(it) }) }
  var calda by remember { mutableStateOf(gara?.calda ?: false) }
  var calendario by remember { mutableStateOf(false) }
  var inCorso by remember { mutableStateOf(false) }
  var errore by remember { mutableStateOf<String?>(null) }
  var permesso by remember { mutableStateOf(false) }
  var confermaElimina by remember { mutableStateOf(false) }
  val errori = Gare.errori(nome, data, priorita, distanza, oggi)

  fun esegui(azione: (com.wboelens.polarrecorder.biosleep.intervals.Credenziali) -> EsitoGara) {
    inCorso = true
    errore = null
    permesso = false
    scope.launch {
      val r =
          withContext(Dispatchers.IO) {
            IntervalsSettings(context).credenziali?.let(azione) ?: EsitoGara("errore", null, false, "Intervals.icu non collegato")
          }
      inCorso = false
      when (r.esito) {
        Gare.OK -> onSalvata(r)
        Gare.NON_VALIDI -> errore = r.errore ?: "Valori non validi"
        Gare.PERMESSO_MANCANTE -> permesso = true
        Gare.NON_TROVATA -> onNonTrovata()
        else -> errore = "Non riuscito: ${r.errore ?: "errore"}. Riprova."
      }
    }
  }

  AlertDialog(
      onDismissRequest = { if (!inCorso) onChiudi() },
      title = { Text(if (gara == null) "Nuova gara" else "Modifica gara") },
      text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          OutlinedTextField(value = nome, onValueChange = { nome = it.take(60) }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
          OutlinedButton(onClick = { calendario = true }, modifier = Modifier.fillMaxWidth()) {
            Text(data?.let { DateIt.lunga(it) } ?: "Scegli la data")
          }
          Text("Priorità", style = MaterialTheme.typography.labelLarge)
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((p, _) in Gare.PRIORITA) FilterChip(selected = priorita == p, onClick = { priorita = p }, label = { Text(p) })
          }
          Text(
              Gare.PRIORITA.joinToString("\n") { (p, d) -> "$p = $d" },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text("Distanza", style = MaterialTheme.typography.labelLarge)
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((k, etichetta) in Gare.DISTANZE) FilterChip(selected = distanza == k, onClick = { distanza = k }, label = { Text(etichetta) })
          }
          Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = calda, onCheckedChange = { calda = it })
            Text("Gara calda (prevista con caldo)")
          }
          errore?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
          if (permesso) {
            Text("Il collegamento non permette di scrivere il calendario.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Button(onClick = { OAuthIntervals.avvia(context) }) { Text("Ricollega Intervals.icu") }
          }
          if (inCorso) CircularProgressIndicator()
          if (gara != null) {
            TextButton(onClick = { confermaElimina = true }, enabled = !inCorso) {
              Text("Elimina", color = MaterialTheme.colorScheme.error)
            }
          }
        }
      },
      confirmButton = {
        TextButton(
            onClick = salva@{
              val d = data ?: return@salva
              val p = priorita ?: return@salva
              val dist = distanza ?: return@salva
              esegui { c -> Cervello.salvaGara(context, c, nome.trim(), d.toString(), p, dist, gara?.id, calda) }
            },
            enabled = errori.isEmpty() && !inCorso) {
              Text("Salva")
            }
      },
      dismissButton = { TextButton(onClick = onChiudi, enabled = !inCorso) { Text("Annulla") } },
  )

  if (calendario) {
    val limite = oggi.plusDays(Gare.MESI_MAX * 31)
    val stato =
        rememberDatePickerState(
            initialSelectedDateMillis = data?.let { utc(it) },
            selectableDates =
                object : SelectableDates {
                  override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val d = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return d >= oggi && d <= limite
                  }

                  override fun isSelectableYear(year: Int) = year in oggi.year..limite.year
                })
    DatePickerDialog(
        onDismissRequest = { calendario = false },
        confirmButton = {
          TextButton(
              onClick = {
                stato.selectedDateMillis?.let { data = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                calendario = false
              }) {
                Text("OK")
              }
        },
        dismissButton = { TextButton(onClick = { calendario = false }) { Text("Annulla") } },
    ) {
      DatePicker(state = stato)
    }
  }

  if (confermaElimina && gara != null) {
    AlertDialog(
        onDismissRequest = { confermaElimina = false },
        title = { Text("Eliminare la gara?") },
        text = { Text("${gara.nome} sparisce dal calendario di Intervals.icu.") },
        confirmButton = {
          TextButton(
              onClick = {
                confermaElimina = false
                esegui { c -> Cervello.eliminaGara(context, c, gara.id) }
              }) {
                Text("Elimina", color = MaterialTheme.colorScheme.error)
              }
        },
        dismissButton = { TextButton(onClick = { confermaElimina = false }) { Text("Annulla") } },
    )
  }
}
