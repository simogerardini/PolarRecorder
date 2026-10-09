package com.wboelens.polarrecorder.biosleep.ui

import com.wboelens.polarrecorder.R
import androidx.compose.ui.res.stringResource
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.auto.AutoStart
import com.wboelens.polarrecorder.biosleep.auto.AutoStartStore
import com.wboelens.polarrecorder.biosleep.auto.HabitLearner
import com.wboelens.polarrecorder.biosleep.auto.Habits
import com.wboelens.polarrecorder.biosleep.auto.NightProfileStore

/** Scheda "Avvio automatico": associazione della fascia, interruttore, stato. */
@Composable
fun AutoStartCard(habits: Habits) {
  val context = LocalContext.current
  val store = remember { AutoStartStore(context) }
  val profile = remember { NightProfileStore(context).load() }
  var associated by remember { mutableStateOf(store.isAssociated) }
  var enabled by remember { mutableStateOf(store.enabled) }
  var message by remember { mutableStateOf<String?>(null) }

  // Il dialogo di sistema per confermare l'associazione
  val launcher =
      rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}

  OutlinedCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(stringResource(R.string.auto_start_card_avvio_automatico), style = MaterialTheme.typography.titleSmall)
      when {
        !AutoStart.isSupported -> InfoText("Richiede Android 13 o successivo.")
        profile == null -> InfoText("Fai prima una registrazione: serve sapere quale fascia usare.")
        !associated -> {
          InfoText(
              "Associa la fascia una volta sola: Android avviserà l'app quando la indossi. " +
                  "Indossala ora (elettrodi bagnati) e premi il pulsante.")
          Button(
              onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                  AutoStart.associate(
                      context,
                      profile.deviceId,
                      onPending = { sender ->
                        launcher.launch(IntentSenderRequest.Builder(sender).build())
                      },
                      onDone = { ok, text ->
                        associated = ok
                        message = text
                      },
                  )
                }
              }) {
                Text(stringResource(R.string.auto_start_card_associa_la_fascia))
              }
        }
        else -> {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = enabled,
                onCheckedChange = {
                  enabled = it
                  store.enabled = it
                },
            )
            Spacer(Modifier.width(12.dp))
            Text(if (enabled) "Attivo" else "Disattivato")
          }
          InfoText(
              if (habits.learned) {
                "Parte da sola tra ${HabitLearner.noonMinutesToClock(habits.bedtimeFromNoon!!)} e " +
                    "${HabitLearner.noonMinutesToClock(habits.bedtimeToNoon!!)} quando indossi la " +
                    "fascia e il telefono è in carica vicino a te."
              } else {
                "Si attiverà dopo ${habits.nightsRequired} notti di apprendimento " +
                    "(ora ${habits.nightsUsed}). Fino ad allora usa \"Avvia notte\"."
              })
          TextButton(
              onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                  AutoStart.disassociate(context)
                  associated = false
                  message = "Associazione rimossa"
                }
              }) {
                Text(stringResource(R.string.auto_start_card_rimuovi_associazione))
              }
        }
      }
      message?.let { InfoText(it) }
    }
  }
}

@Composable
private fun InfoText(text: String) {
  Text(
      text,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}
