package com.wboelens.polarrecorder.biosleep.ui

import com.wboelens.polarrecorder.R
import androidx.compose.ui.res.stringResource
import android.content.Context
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
import com.wboelens.polarrecorder.biosleep.cervello.DatiSweat
import com.wboelens.polarrecorder.biosleep.cervello.SweatTest
import com.wboelens.polarrecorder.biosleep.riepilogo.RiepilogoDb
import com.wboelens.polarrecorder.biosleep.ui.allenamento.DateIt
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREFS = "biosleep_sweat"

private fun registrato(context: Context): LocalDate? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("test", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/**
 * In Oggi: dal giorno dello sweat test pianificato dal coach ("DETP: sweat test il <data>" nei
 * motivi degli ultimi riepiloghi) finche' non si inseriscono i dati, per 14 giorni al massimo.
 */
@Composable
fun CardSweat() {
  val context = LocalContext.current.applicationContext
  val versione by RiepilogoDb.versione.collectAsState()
  var giro by remember { mutableStateOf(0) }
  val test by
      produceState<LocalDate?>(null, versione, giro) {
        value =
            withContext(Dispatchers.IO) {
              val t = SweatTest.data(RiepilogoDb.get(context).recenti(21).flatMap { it.motivi })
              t.takeIf { SweatTest.daChiedere(it, registrato(context), LocalDate.now()) }
            }
      }
  var salvato by remember { mutableStateOf<String?>(null) }
  salvato?.let {
    Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
    return
  }
  val giorno = test ?: return
  val scope = rememberCoroutineScope()
  var p1 by remember { mutableStateOf("") }
  var p2 by remember { mutableStateOf("") }
  var b1 by remember { mutableStateOf("") }
  var b2 by remember { mutableStateOf("") }
  var urine by remember { mutableStateOf("0") }
  var minuti by remember { mutableStateOf("") }
  var sodio by remember { mutableStateOf("") }
  var errore by remember { mutableStateOf<String?>(null) }
  var inCorso by remember { mutableStateOf(false) }
  val n = SweatTest::numero
  val dati =
      if (listOf(p1, p2, b1, b2, urine, minuti).any { n(it) == null } || (sodio.isNotBlank() && n(sodio) == null)) null
      else DatiSweat(n(p1)!!, n(p2)!!, n(b1)!!, n(b2)!!, n(urine)!!, n(minuti)!!, sodio.takeIf { it.isNotBlank() }?.let(n))

  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(stringResource(R.string.sweat_inserisci_i_dati_dello), style = MaterialTheme.typography.titleMedium)
      Text(
          stringResource(R.string.sweat_test_del_pesi_nudo, (DateIt.breve(giorno)).toString()),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Campo("Peso prima (kg)", p1, Modifier.weight(1f)) { p1 = it; errore = null }
        Campo("Peso dopo (kg)", p2, Modifier.weight(1f)) { p2 = it; errore = null }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Campo("Borraccia prima (kg)", b1, Modifier.weight(1f)) { b1 = it; errore = null }
        Campo("Borraccia dopo (kg)", b2, Modifier.weight(1f)) { b2 = it; errore = null }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Campo("Urine (kg)", urine, Modifier.weight(1f)) { urine = it; errore = null }
        Campo("Durata (min)", minuti, Modifier.weight(1f)) { minuti = it; errore = null }
      }
      Campo("Sodio nel sudore (mg/L, facoltativo)", sodio, Modifier.fillMaxWidth()) { sodio = it; errore = null }
      val problema = dati?.problema()
      (errore ?: problema)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
      if (inCorso) CircularProgressIndicator()
      Button(
          onClick = salva@{
            val d = dati ?: return@salva
            inCorso = true
            scope.launch {
              val r = withContext(Dispatchers.IO) { Cervello.registraSweat(context, d) }
              inCorso = false
              if (r.esito == "ok") {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("test", giorno.toString()).apply()
                salvato = "Sudorazione ${"%.2f".format(r.litriOra ?: d.litriOra)} L/h salvata"
                giro++
              } else {
                errore = r.errore ?: "Non salvato"
              }
            }
          },
          enabled = dati != null && problema == null && !inCorso) {
            Text(stringResource(R.string.sweat_salva))
          }
    }
  }
}

@Composable
private fun Campo(etichetta: String, valore: String, modifier: Modifier, onCambia: (String) -> Unit) {
  OutlinedTextField(
      value = valore,
      onValueChange = onCambia,
      label = { Text(etichetta) },
      singleLine = true,
      isError = valore.isNotBlank() && SweatTest.numero(valore) == null,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
      modifier = modifier)
}
