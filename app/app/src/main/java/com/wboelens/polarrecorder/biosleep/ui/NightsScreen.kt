package com.wboelens.polarrecorder.biosleep.ui

import com.wboelens.polarrecorder.biosleep.lingua.tr
import com.wboelens.polarrecorder.R
import androidx.compose.ui.res.stringResource
import com.wboelens.polarrecorder.BuildConfig
import android.database.SQLException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.NightListItem
import com.wboelens.polarrecorder.biosleep.BioSleepDataSaver
import com.wboelens.polarrecorder.biosleep.SleepDb
import com.wboelens.polarrecorder.biosleep.auto.HabitLearner
import com.wboelens.polarrecorder.biosleep.auto.Habits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Schermata "Le mie notti": elenco delle notti analizzate, dalla piu' recente. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NightsScreen(
    onBack: () -> Unit,
    onOpenNight: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBioAge: () -> Unit,
) {
  val context = LocalContext.current
  // Il database si legge in background (Dispatchers.IO) per non bloccare l'interfaccia
  // Si ricarica da sola quando una notte nuova finisce l'analisi
  val newNight by BioSleepDataSaver.newNightReady.collectAsState()
  val analyzing by BioSleepDataSaver.analyzing.collectAsState()
  val state by
      produceState<LoadState<NightsData>>(LoadState.Loading, newNight) {
        value =
            try {
              LoadState.Ready(
                  withContext(Dispatchers.IO) {
                    val db = SleepDb.get(context)
                    NightsData(
                        nights = db.listNights(),
                        habits = HabitLearner.learn(db.nightTimes(), System.currentTimeMillis()),
                    )
                  })
            } catch (e: SQLException) {
              LoadState.Error(e.message ?: "Errore del database")
            }
      }

  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.nights_le_mie_notti)) },
            navigationIcon = {
              IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Indietro"))
              }
            },
            actions = {
              IconButton(onClick = onOpenBioAge) {
                Icon(Icons.Filled.HourglassTop, contentDescription = tr("Età ${BuildConfig.APP_NAME}"))
              }
              IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = tr("Impostazioni Intervals.icu"))
              }
            },
        )
      }
  ) { padding ->
    val modifier = Modifier.fillMaxSize().padding(padding)
    when (val s = state) {
      is LoadState.Loading ->
          Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
      is LoadState.Error -> CenteredText(modifier, "Impossibile leggere le notti:\n${s.message}")
      is LoadState.Ready ->
          if (s.data.nights.isEmpty()) {
            CenteredText(
                modifier,
                "Nessuna notte ancora.\nLe notti compaiono qui dopo lo stop di una registrazione " +
                    "di almeno 10 minuti.",
            )
          } else {
            LazyColumn(
                modifier = modifier,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              if (analyzing) item { AnalyzingCard() }
              item { HabitsCard(s.data.habits) }
              item { AutoStartCard(s.data.habits) }
              items(s.data.nights, key = { it.sessionId }) { night ->
                NightCard(night, onClick = { onOpenNight(night.sessionId) })
              }
            }
          }
    }
  }
}

@Composable
private fun AnalyzingCard() {
  OutlinedCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(stringResource(R.string.nights_analisi_della_notte_in), style = MaterialTheme.typography.titleSmall)
      LinearProgressIndicator(Modifier.fillMaxWidth())
    }
  }
}

private data class NightsData(val nights: List<NightListItem>, val habits: Habits)

/** Stato dell'apprendimento delle abitudini di sonno. */
@Composable
private fun HabitsCard(h: Habits) {
  OutlinedCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      if (h.learned) {
        Text(stringResource(R.string.nights_abitudini_apprese), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.nights_a_letto_di_solito, (HabitLearner.noonMinutesToClock(h.bedtimeFromNoon!!)).toString(), (HabitLearner.noonMinutesToClock(h.bedtimeToNoon!!)).toString(), (HabitLearner.minutesToClock(h.morningFromMinute)).toString()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        Text(
            stringResource(R.string.nights_apprendimento_abitudini_di_notti, h.nightsUsed.toString(), h.nightsRequired.toString()),
            style = MaterialTheme.typography.titleSmall,
        )
        LinearProgressIndicator(
            progress = { h.nightsUsed.toFloat() / h.nightsRequired },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        Text(
            stringResource(R.string.nights_contano_le_notti_di),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun NightCard(night: NightListItem, onClick: () -> Unit) {
  val s = night.summary
  Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(nightTitle(s.endMs), style = MaterialTheme.typography.titleMedium)
      Text(
          nightTimes(s.startMs, s.endMs),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        SmallMetric("FC riposo", "${fmt(s.restingHr)} bpm")
        val st = night.stages
        if (st != null && st.tstMin > 0) SmallMetric("Sonno", hm(st.tstMin))
        else SmallMetric("FC media", "${fmt(s.hrAvg)} bpm")
        SmallMetric("rMSSD", "${fmt(s.rmssd, 1)} ms")
      }
    }
  }
}

@Composable
private fun SmallMetric(label: String, value: String) {
  Column {
    Text(
        tr(label),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(value, style = MaterialTheme.typography.titleSmall)
  }
}

@Composable
internal fun CenteredText(modifier: Modifier, text: String) {
  Box(modifier.padding(32.dp), contentAlignment = Alignment.Center) {
    Text(tr(text), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
  }
}
