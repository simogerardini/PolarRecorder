package com.wboelens.polarrecorder.biosleep.ui

import com.wboelens.polarrecorder.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.cervello.Cervello
import com.wboelens.polarrecorder.biosleep.cervello.CoachStato
import com.wboelens.polarrecorder.biosleep.cervello.EsitoCss
import com.wboelens.polarrecorder.biosleep.cervello.SoglieRepo
import com.wboelens.polarrecorder.biosleep.cervello.StatoCoachFile
import com.wboelens.polarrecorder.biosleep.cervello.UltimoTest
import com.wboelens.polarrecorder.biosleep.cervello.secondiDaMmSs
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsSettings
import com.wboelens.polarrecorder.biosleep.intervals.OAuthIntervals
import com.wboelens.polarrecorder.biosleep.riepilogo.RiepilogoDb
import com.wboelens.polarrecorder.biosleep.ui.allenamento.DateIt
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * L'ultimo test dallo stato del coach (stato_coach.json), riletto dopo ogni run e ogni riepilogo.
 * Lo stato e' la fonte giusta: la riga "test in programma" c'e' solo nel riepilogo del giorno in
 * cui il test e' stato pianificato, e dopo registra_css la richiesta dei tempi va chiusa subito.
 */
@Composable
fun rememberUltimoTest(giro: Int = 0): UltimoTest? {
  val context = LocalContext.current.applicationContext
  val run by CoachStato.versione.collectAsState()
  val rie by RiepilogoDb.versione.collectAsState()
  val t by produceState<UltimoTest?>(null, run, rie, giro) { value = withContext(Dispatchers.IO) { StatoCoachFile.ultimoTest(context) } }
  return t
}

/** In Oggi: test in programma questa settimana e richiesta dei tempi del test CSS. */
@Composable
fun TestOggi() {
  var giro by remember { mutableIntStateOf(0) }
  val t = rememberUltimoTest(giro) ?: return
  val oggi = LocalDate.now()
  if (t.inProgramma(oggi)) {
    Card(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.test_test_in_programma, (DateIt.breve(t.data)).toString()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(t.nome, style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.test_il_protocollo_e_nella),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
  if (t.chiedeTempiCss) CardCss(t) { giro++ }
}

/** Tempi del test CSS (400 m e 200 m a tutta) -> cervello.registra_css. */
@Composable
private fun CardCss(t: UltimoTest, onSalvato: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var t400 by remember { mutableStateOf("") }
  var t200 by remember { mutableStateOf("") }
  var inCorso by remember { mutableStateOf(false) }
  var esito by remember { mutableStateOf<EsitoCss?>(null) }
  val s400 = secondiDaMmSs(t400)
  val s200 = secondiDaMmSs(t200)
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(stringResource(R.string.test_inserisci_i_tempi_del), style = MaterialTheme.typography.titleMedium)
      Text(
          stringResource(R.string.test_del_i_tempi_dei, t.nome.toString(), (DateIt.breve(t.data)).toString()),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = t400, onValueChange = { t400 = it; esito = null }, label = { Text("400 m") }, placeholder = { Text("6:20") },
            singleLine = true, isError = t400.isNotBlank() && s400 == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = t200, onValueChange = { t200 = it; esito = null }, label = { Text("200 m") }, placeholder = { Text("3:00") },
            singleLine = true, isError = t200.isNotBlank() && s200 == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.weight(1f))
      }
      when (esito?.esito) {
        EsitoCss.NON_VALIDI ->
            Text(esito?.errore ?: "Tempi incoerenti: il 400 deve durare circa il doppio del 200", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        EsitoCss.PERMESSO_MANCANTE -> {
          Text(stringResource(R.string.test_il_collegamento_non_permette), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
          Button(onClick = { OAuthIntervals.avvia(context) }) { Text(stringResource(R.string.test_ricollega_intervals_icu)) }
        }
        null, EsitoCss.OK -> Unit
        else -> Text("Non salvato: ${esito?.errore ?: "errore"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      }
      if (inCorso) CircularProgressIndicator()
      Button(
          onClick = conferma@{
            val a = s400 ?: return@conferma
            val b = s200 ?: return@conferma
            inCorso = true
            scope.launch {
              val app = context.applicationContext
              val r =
                  withContext(Dispatchers.IO) {
                    val c = IntervalsSettings(app).credenziali ?: return@withContext EsitoCss("errore", null, "Intervals.icu non collegato")
                    Cervello.registraCss(app, c, a, b).also { if (it.esito == EsitoCss.OK) SoglieRepo.controlla(app, forza = true) }
                  }
              inCorso = false
              esito = r
              if (r.esito == EsitoCss.OK) {
                CssSalvato.messaggio = "CSS ${r.css} salvato su Intervals.icu"
                onSalvato() // lo stato ora dice "test chiuso": la card sparisce
              }
            }
          },
          enabled = s400 != null && s200 != null && !inCorso,
      ) {
        Text(stringResource(R.string.test_conferma))
      }
    }
  }
}

/** Conferma del CSS salvato, mostrata una volta in Oggi dopo che la card sparisce. */
object CssSalvato {
  var messaggio by mutableStateOf<String?>(null)
}

@Composable
fun MessaggioCss() {
  CssSalvato.messaggio?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
}
