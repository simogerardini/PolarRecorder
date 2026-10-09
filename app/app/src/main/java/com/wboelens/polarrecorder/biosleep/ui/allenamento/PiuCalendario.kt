package com.wboelens.polarrecorder.biosleep.ui.allenamento

import com.wboelens.polarrecorder.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.cervello.Cervello
import com.wboelens.polarrecorder.biosleep.cervello.DisponibilitaDate
import com.wboelens.polarrecorder.biosleep.cervello.EsitoPausa
import com.wboelens.polarrecorder.biosleep.cervello.Pausa
import com.wboelens.polarrecorder.biosleep.cervello.Pause
import com.wboelens.polarrecorder.biosleep.cervello.PianoCalendario
import com.wboelens.polarrecorder.biosleep.cervello.RipianificaWorker
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsSettings
import com.wboelens.polarrecorder.biosleep.intervals.OAuthIntervals
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Colori delle fasce di pausa nel calendario. */
fun coloreTipoPausa(tipo: String): Color =
    when (tipo) {
      "malattia" -> Color(0xFFE0A030)
      "infortunio" -> Color(0xFFEF485B)
      else -> Color(0xFF39A5C8) // ferie
    }

/** Cosa e' aperto dal "+" del calendario. */
sealed interface ModuloCalendario {
  data class Menu(val dal: LocalDate, val al: LocalDate) : ModuloCalendario

  data class NuovaPausa(val dal: LocalDate, val al: LocalDate) : ModuloCalendario

  data class ModificaPausa(val p: Pausa) : ModuloCalendario

  data class Tempo(val data: LocalDate) : ModuloCalendario

  data class Gara(val data: LocalDate) : ModuloCalendario
}

/** Primo foglio del "+": le quattro voci. Tag e tempo valgono per il primo giorno dell'intervallo. */
@Composable
fun MenuPiu(dal: LocalDate, al: LocalDate, onScegli: (ModuloCalendario?) -> Unit, onTag: (LocalDate) -> Unit) {
  Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
        if (dal == al) DateIt.lunga(dal) else "Dal ${DateIt.breve(dal)} al ${DateIt.breve(al)}",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 8.dp))
    VocePiu(Icons.Filled.Weekend, "Ferie / Malattia / Infortunio") { onScegli(ModuloCalendario.NuovaPausa(dal, al)) }
    VocePiu(Icons.Filled.Sell, "Tag del giorno") { onScegli(null); onTag(dal) }
    VocePiu(Icons.Filled.EmojiEvents, "Gara") { onScegli(ModuloCalendario.Gara(dal)) }
    VocePiu(Icons.Filled.Schedule, "Tempo disponibile") { onScegli(ModuloCalendario.Tempo(dal)) }
  }
}

@Composable
private fun VocePiu(icona: ImageVector, testo: String, onClick: () -> Unit) {
  Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(icona, null, Modifier.padding(end = 16.dp), tint = MaterialTheme.colorScheme.primary)
    Text(testo, style = MaterialTheme.typography.bodyLarge)
  }
}

private fun utc(d: LocalDate) = d.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

private fun daUtc(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

/** Pulsante con data che apre il calendario di sistema. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CampoData(etichetta: String, data: LocalDate, modifier: Modifier, onCambia: (LocalDate) -> Unit) {
  var aperto by remember { mutableStateOf(false) }
  Column(modifier) {
    Text(etichetta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = { aperto = true }, modifier = Modifier.fillMaxWidth()) { Text(DateIt.breve(data)) }
  }
  if (aperto) {
    val stato = rememberDatePickerState(initialSelectedDateMillis = utc(data))
    DatePickerDialog(
        onDismissRequest = { aperto = false },
        confirmButton = {
          TextButton(onClick = { stato.selectedDateMillis?.let { onCambia(daUtc(it)) }; aperto = false }) { Text(stringResource(R.string.piu_calendario_ok)) }
        },
        dismissButton = { TextButton(onClick = { aperto = false }) { Text(stringResource(R.string.piu_calendario_annulla)) } },
    ) {
      DatePicker(state = stato)
    }
  }
}

/**
 * Ferie, malattia o infortunio: nuova (date dal "+") o modifica. Le pause inserite su Intervals.icu
 * (dallApp = false) si possono solo eliminare, con l'avviso che vengono da li'.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModuloPausa(
    esistente: Pausa?,
    dalIniziale: LocalDate,
    alIniziale: LocalDate,
    onChiudi: () -> Unit,
    onFatto: (EsitoPausa, LocalDate, LocalDate) -> Unit,
) {
  val context = LocalContext.current.applicationContext
  val scope = rememberCoroutineScope()
  val soloElimina = esistente != null && !esistente.dallApp
  var dal by remember { mutableStateOf(esistente?.dal ?: dalIniziale) }
  var al by remember { mutableStateOf(esistente?.al ?: alIniziale) }
  var tipo by remember { mutableStateOf(esistente?.tipo) }
  var nota by remember { mutableStateOf(esistente?.nota ?: "") }
  var inCorso by remember { mutableStateOf(false) }
  var errore by remember { mutableStateOf<String?>(null) }
  var permesso by remember { mutableStateOf(false) }
  val errori = Pause.errori(dal, al, tipo)

  fun esegui(azione: (com.wboelens.polarrecorder.biosleep.intervals.Credenziali) -> EsitoPausa) {
    inCorso = true
    errore = null
    permesso = false
    scope.launch {
      val r = withContext(Dispatchers.IO) {
        IntervalsSettings(context).credenziali?.let(azione) ?: EsitoPausa("errore", null, false, "Intervals.icu non collegato")
      }
      inCorso = false
      when (r.esito) {
        Pause.OK, Pause.NON_TROVATA -> onFatto(r, dal, al)
        Pause.NON_VALIDI -> errore = r.errore ?: "Valori non validi"
        Pause.PERMESSO_MANCANTE -> permesso = true
        else -> errore = "Non riuscito: ${r.errore ?: "errore"}. Riprova."
      }
    }
  }

  AlertDialog(
      onDismissRequest = { if (!inCorso) onChiudi() },
      title = { Text(if (esistente == null) "Ferie / Malattia / Infortunio" else Pause.etichetta(esistente.tipo)) },
      text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          if (soloElimina) {
            Text(
                stringResource(R.string.piu_calendario_questa_pausa_e_stata, (DateIt.breve(dal)).toString(), (DateIt.breve(al)).toString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
          } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              CampoData("Dal", dal, Modifier.weight(1f)) { dal = it; if (al.isBefore(it)) al = it }
              CampoData("Al", al, Modifier.weight(1f)) { al = it }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              for ((k, etichetta) in Pause.TIPI) FilterChip(selected = tipo == k, onClick = { tipo = k }, label = { Text(etichetta) })
            }
            OutlinedTextField(
                value = nota, onValueChange = { nota = it.take(80) }, label = { Text(stringResource(R.string.piu_calendario_nota_facoltativa)) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.piu_calendario_il_coach_non_pianifica),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          errore?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
          if (permesso) {
            Text(stringResource(R.string.piu_calendario_il_collegamento_non_permette), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Button(onClick = { OAuthIntervals.avvia(context) }) { Text(stringResource(R.string.piu_calendario_ricollega_intervals_icu)) }
          }
          if (inCorso) CircularProgressIndicator()
          if (esistente != null) {
            TextButton(onClick = { esegui { c -> Cervello.eliminaPausa(context, c, esistente.id) } }, enabled = !inCorso) {
              Text(stringResource(R.string.piu_calendario_elimina), color = MaterialTheme.colorScheme.error)
            }
          }
        }
      },
      confirmButton = {
        if (!soloElimina) {
          TextButton(
              onClick = salva@{
                val t = tipo ?: return@salva
                esegui { c -> Cervello.salvaPausa(context, c, dal, al, t, nota.trim(), esistente?.id) }
              },
              enabled = errori.isEmpty() && !inCorso) {
                Text(stringResource(R.string.piu_calendario_salva))
              }
        }
      },
      dismissButton = { TextButton(onClick = onChiudi, enabled = !inCorso) { Text(stringResource(R.string.piu_calendario_annulla)) } },
  )
}

/** Minuti disponibili per una data (0 = non disponibile), salvati nell'app. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModuloTempo(data: LocalDate, attuale: Int?, onChiudi: () -> Unit, onFatto: () -> Unit) {
  val context = LocalContext.current.applicationContext
  var testo by remember { mutableStateOf(attuale?.toString() ?: "") }
  val minuti = testo.trim().toIntOrNull()?.takeIf { it in 0..600 }
  AlertDialog(
      onDismissRequest = onChiudi,
      title = { Text(stringResource(R.string.piu_calendario_tempo_disponibile)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(DateIt.lunga(data), style = MaterialTheme.typography.bodyMedium)
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in PianoCalendario.SCORCIATOIE) {
              FilterChip(selected = minuti == m, onClick = { testo = m.toString() }, label = { Text(if (m == 0) "Non disponibile" else "$m'") })
            }
          }
          OutlinedTextField(
              value = testo, onValueChange = { testo = it.filter(Char::isDigit).take(3) }, label = { Text(stringResource(R.string.piu_calendario_minuti)) },
              singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
          Text(
              stringResource(R.string.piu_calendario_vale_solo_per_questo),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant)
          if (attuale != null) {
            TextButton(onClick = { DisponibilitaDate.salva(context, data, null); onFatto() }) { Text(stringResource(R.string.piu_calendario_torna_alla_settimana_tipo)) }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { DisponibilitaDate.salva(context, data, minuti); onFatto() }, enabled = minuti != null) { Text(stringResource(R.string.piu_calendario_salva)) }
      },
      dismissButton = { TextButton(onClick = onChiudi) { Text(stringResource(R.string.piu_calendario_annulla)) } },
  )
}

/**
 * Dopo un salvataggio: nella settimana in corso (da oggi a domenica) si propone di ripianificare
 * subito; altrimenti vale dalla prossima pianificazione.
 */
@Composable
fun ChiediRipianifica(onChiudi: (String) -> Unit) {
  val context = LocalContext.current.applicationContext
  AlertDialog(
      onDismissRequest = { onChiudi("Varrà dalla prossima pianificazione") },
      title = { Text(stringResource(R.string.piu_calendario_aggiorno_subito_il_piano)) },
      text = { Text(stringResource(R.string.piu_calendario_le_sedute_da_oggi)) },
      confirmButton = {
        TextButton(onClick = {
          RipianificaWorker.avvia(context)
          onChiudi("Ripianificazione in corso: il riepilogo arriverà con la notifica del coach")
        }) { Text(stringResource(R.string.piu_calendario_si_aggiorna)) }
      },
      dismissButton = { TextButton(onClick = { onChiudi("Varrà dalla prossima pianificazione") }) { Text(stringResource(R.string.piu_calendario_no)) } },
  )
}
