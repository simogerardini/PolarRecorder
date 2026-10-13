package com.wboelens.polarrecorder.biosleep.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.BuildConfig
import com.wboelens.polarrecorder.R

/** Sotto "Collega la tua fascia": entrata nella demo, ben visibile per i revisori. */
@Composable
fun DemoIngresso(onEntra: () -> Unit) {
  OutlinedButton(onClick = onEntra, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.demo_prova)) }
}

/** Al posto della scheda della fascia, con la demo attiva. */
@Composable
fun DemoCard(onOpenNights: () -> Unit, onOpenBioAge: () -> Unit, onEsci: () -> Unit) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(stringResource(R.string.demo_titolo), style = MaterialTheme.typography.headlineSmall)
      Text(stringResource(R.string.demo_spiegazione), style = MaterialTheme.typography.bodyMedium)
      Button(onClick = onOpenNights, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.demo_notti)) }
      OutlinedButton(onClick = onOpenBioAge, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.demo_eta, BuildConfig.APP_NAME))
      }
      TextButton(onClick = onEsci) { Text(stringResource(R.string.demo_esci)) }
    }
  }
}
