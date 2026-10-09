package com.wboelens.polarrecorder.biosleep.ui

import com.wboelens.polarrecorder.biosleep.lingua.tr
import com.wboelens.polarrecorder.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.auto.NightProfileStore
import com.wboelens.polarrecorder.biosleep.hal.DriverRegistry
import com.wboelens.polarrecorder.biosleep.hal.Fasce
import com.wboelens.polarrecorder.biosleep.hal.InfoFascia
import com.wboelens.polarrecorder.biosleep.hal.PrevisioneFascia
import com.wboelens.polarrecorder.biosleep.hal.RapportoFascia
import com.wboelens.polarrecorder.biosleep.hal.testo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Un badge: pieno se la capacita' c'e', bordato se da verificare ("HRV?"). */
@Composable
private fun Badge(testo: String, pieno: Boolean) {
  val colore = MaterialTheme.colorScheme.primary
  val forma = RoundedCornerShape(6.dp)
  val m =
      if (pieno) Modifier.background(colore, forma)
      else Modifier.border(1.dp, colore, forma)
  Text(
      tr(testo),
      m.padding(horizontal = 8.dp, vertical = 2.dp),
      style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.SemiBold,
      color = if (pieno) MaterialTheme.colorScheme.onPrimary else colore)
}

/** Badge "HRV" (o "HRV?" da verificare) e "Movimento"; nessun badge HRV se non la misura. */
@Composable
fun BadgeFascia(p: PrevisioneFascia) {
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    when (p.hrv) {
      true -> Badge("HRV", true)
      null -> Badge("HRV?", false)
      false -> Unit
    }
    if (p.movimento) Badge("Movimento", true)
  }
}

/** Riga sotto il nome nella schermata "Collega": cosa aspettarsi da questa fascia. */
fun descrizioneFascia(p: PrevisioneFascia): String? =
    when {
      p.hrv == false -> "Niente HRV: solo frequenza cardiaca" + if (p.verificata) " (verificato)" else " (sensore ottico)"
      p.hrv == null -> "HRV verificata nei primi 5 minuti della prima notte"
      !p.movimento -> "Battito e HRV, senza movimento"
      else -> null
    }

/** Avviso per le fasce senza HRV affidabile, prima e dopo la notte. */
@Composable
fun AvvisoSenzaHrv() {
  Column(
      Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp)).padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(tr(InfoFascia.AVVISO_SENZA_HRV), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        Text(tr(InfoFascia.CONSIGLIO), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
      }
}

/** Durante la notte: appena il driver decide che gli RR non servono (dopo ~5 minuti). */
@Composable
fun AvvisoFasciaInCorso() {
  val s by Fasce.sessione.collectAsState()
  if (s?.senzaHrv == true) AvvisoSenzaHrv()
}

/** Impostazioni -> Fascia: fascia in uso, capacita', ultimo rapporto da condividere. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FasciaScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val dati by
      produceState<Pair<String?, RapportoFascia?>?>(null) {
        value =
            withContext(Dispatchers.IO) {
              runCatching { NightProfileStore(context).load()?.deviceName }.getOrNull() to Fasce.rapportoUltimaSessione(context)
            }
      }
  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.fascia_fascia)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } })
      },
  ) { padding ->
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      val d = dati ?: return@Column
      val (nome, rapporto) = d
      if (nome == null) {
        Text(stringResource(R.string.fascia_nessuna_fascia_collegata_collegala), style = MaterialTheme.typography.bodyMedium)
        return@Column
      }
      Text(nome, style = MaterialTheme.typography.titleLarge)
      val p = InfoFascia.prevedi(nome, DriverRegistry.tipo(nome), rapporto)
      BadgeFascia(p)
      descrizioneFascia(p)?.let { Text(tr(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
      if (p.hrv == false) AvvisoSenzaHrv()
      Text(stringResource(R.string.fascia_per_cambiare_fascia_scheda), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      HorizontalDivider()
      Text(stringResource(R.string.fascia_rapporto_fascia_dell_ultima), style = MaterialTheme.typography.titleSmall)
      if (rapporto == null) {
        Text(stringResource(R.string.fascia_disponibile_dopo_la_prima), style = MaterialTheme.typography.bodySmall)
      } else {
        Text(
            stringResource(R.string.fascia_descrive_la_fascia_e),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            rapporto.testo(),
            Modifier.fillMaxWidth().heightIn(max = 360.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(12.dp).verticalScroll(rememberScrollState()),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall)
        Button(onClick = { Fasce.condividi(context) }) { Text(stringResource(R.string.fascia_condividi_il_rapporto)) }
      }
    }
  }
}
