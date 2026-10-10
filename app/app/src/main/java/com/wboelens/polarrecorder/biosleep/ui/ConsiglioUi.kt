package com.wboelens.polarrecorder.biosleep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.cache.Prontezza
import com.wboelens.polarrecorder.biosleep.lingua.tr
import com.wboelens.polarrecorder.biosleep.readiness.Consiglio

/** Oggi, solo biometria: il consiglio del giorno dai dati della notte (Consiglio.calcola). */
@Composable
fun CardConsiglio(p: Prontezza, sonnoStanotteOre: Double?) {
  val grigio = MaterialTheme.colorScheme.onSurfaceVariant
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(tr("Consiglio di oggi"), style = MaterialTheme.typography.titleMedium)
      when (p) {
        is Prontezza.Calibrazione ->
            Text(tr("In calibrazione: il consiglio arriva dopo ${p.servono} notti valide (${p.notti}/${p.servono})."), color = grigio)
        is Prontezza.Banda -> {
          val e = Consiglio.calcola(p.baseline, sonnoStanotteOre)
          if (e == null) {
            Text(tr("Non valutabile"), color = grigio)
          } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(Consiglio.emoji(e.livello), style = MaterialTheme.typography.headlineSmall)
              Spacer(Modifier.width(10.dp))
              Text(tr(Consiglio.titolo(e.livello)), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Text(tr(Consiglio.spiegazione(e.livello)))
            for (m in e.motivi) Text("• " + tr(m), style = MaterialTheme.typography.bodySmall, color = grigio)
          }
        }
      }
    }
  }
}
