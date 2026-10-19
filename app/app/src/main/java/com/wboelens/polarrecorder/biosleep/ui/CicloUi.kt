package com.wboelens.polarrecorder.biosleep.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.lingua.tr
import com.wboelens.polarrecorder.biosleep.ui.allenamento.DateIt
import java.time.LocalDate

/**
 * Profilo atleta: ciclo mestruale (facoltativo). Interruttore + date di inizio delle mestruazioni.
 * Dato sanitario: resta sul telefono e va solo al cervello locale, mai su Intervals.icu.
 */
@Composable
fun SezioneCiclo(attivo: Boolean, inizi: List<String>, onAttivo: (Boolean) -> Unit, onInizi: (List<String>) -> Unit) {
  val activity = LocalContext.current // il calendario vuole il contesto dell'Activity
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(tr("Ciclo mestruale"), style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(tr("Considera il ciclo nella valutazione"), Modifier.weight(1f))
      Switch(checked = attivo, onCheckedChange = onAttivo)
    }
    if (attivo) {
      if (inizi.isEmpty()) {
        Text(tr("Nessuna data inserita"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      for (d in inizi.sortedDescending()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text(runCatching { DateIt.lunga(LocalDate.parse(d)) }.getOrDefault(d), Modifier.weight(1f))
          IconButton(onClick = { onInizi(inizi - d) }) { Icon(Icons.Filled.Close, tr("Rimuovi")) }
        }
      }
      OutlinedButton(onClick = {
        val o = LocalDate.now()
        DatePickerDialog(activity, { _, y, m, g ->
          val scelto = LocalDate.of(y, m + 1, g)
          if (!scelto.isAfter(LocalDate.now())) onInizi((inizi + scelto.toString()).distinct().sorted())
        }, o.year, o.monthValue - 1, o.dayOfMonth).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
      }) { Text(tr("Aggiungi inizio")) }
      Text(
          tr("Inserisci il primo giorno di ogni mestruazione. I 7 giorni prima non pesano sulla baseline dell'HRV. Resta sul telefono: non va su Intervals.icu."),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}
