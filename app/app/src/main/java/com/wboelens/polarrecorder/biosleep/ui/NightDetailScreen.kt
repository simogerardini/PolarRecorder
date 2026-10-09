package com.wboelens.polarrecorder.biosleep.ui

import com.wboelens.polarrecorder.biosleep.lingua.tr
import com.wboelens.polarrecorder.R
import androidx.compose.ui.res.stringResource
import android.database.SQLException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.NightListItem
import com.wboelens.polarrecorder.biosleep.SleepDb
import com.wboelens.polarrecorder.biosleep.WindowMetrics
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsSync
import com.wboelens.polarrecorder.biosleep.ui.allenamento.Sezione
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class NightDetail(val night: NightListItem, val windows: List<WindowMetrics>)

private val HR_COLOR = Color(0xFFE53935)
private val RMSSD_COLOR = Color(0xFF43A047)

/** Dettaglio di una notte: metriche principali, grafici FC e rMSSD, qualita' del segnale. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NightDetailScreen(sessionId: Long, onBack: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var askDelete by remember { mutableStateOf(false) }
  var syncing by remember { mutableStateOf(false) }
  var reloadKey by remember { mutableIntStateOf(0) } // cambiandolo si ricarica la notte dal database

  val state by
      produceState<LoadState<NightDetail>>(LoadState.Loading, sessionId, reloadKey) {
        value =
            try {
              withContext(Dispatchers.IO) {
                val db = SleepDb.get(context)
                val night = db.loadNight(sessionId)
                if (night == null) LoadState.Error("Notte non trovata")
                else LoadState.Ready(NightDetail(night, db.loadWindows(sessionId)))
              }
            } catch (e: SQLException) {
              LoadState.Error(e.message ?: "Errore del database")
            }
      }

  if (askDelete) {
    AlertDialog(
        onDismissRequest = { askDelete = false },
        title = { Text(stringResource(R.string.night_detail_eliminare_questa_notte)) },
        text = { Text(stringResource(R.string.night_detail_riepilogo_grafici_e_battiti)) },
        confirmButton = {
          TextButton(
              onClick = {
                askDelete = false
                scope.launch {
                  try {
                    withContext(Dispatchers.IO) { SleepDb.get(context).deleteSession(sessionId) }
                  } catch (e: SQLException) {
                    android.util.Log.e("BioSleep", "Eliminazione notte $sessionId fallita", e)
                  }
                  onBack()
                }
              }
          ) {
            Text(stringResource(R.string.night_detail_elimina))
          }
        },
        dismissButton = { TextButton(onClick = { askDelete = false }) { Text(stringResource(R.string.night_detail_annulla)) } },
    )
  }

  Scaffold(
      topBar = {
        TopAppBar(
            title = {
              val s = state
              Text(tr(if (s is LoadState.Ready) nightTitle(s.data.night.summary.endMs) else "Notte"))
            },
            navigationIcon = {
              IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Indietro"))
              }
            },
            actions = {
              if (state is LoadState.Ready) {
                IconButton(onClick = { askDelete = true }) {
                  Icon(Icons.Filled.Delete, contentDescription = tr("Elimina notte"))
                }
              }
            },
        )
      }
  ) { padding ->
    val modifier = Modifier.fillMaxSize().padding(padding)
    when (val s = state) {
      is LoadState.Loading ->
          Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
      is LoadState.Error -> CenteredText(modifier, s.message)
      is LoadState.Ready ->
          NightContent(
              detail = s.data,
              syncing = syncing,
              onSync = {
                syncing = true
                scope.launch {
                  withContext(Dispatchers.IO) { IntervalsSync.syncNight(context, sessionId) }
                  syncing = false
                  reloadKey++
                }
              },
              modifier = modifier,
          )
    }
  }
}

@Composable
private fun NightContent(
    detail: NightDetail,
    syncing: Boolean,
    onSync: () -> Unit,
    modifier: Modifier,
) {
  val s = detail.night.summary
  val w = detail.windows
  Column(
      modifier.verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
        nightTimes(s.startMs, s.endMs),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    MetricRow(Metric(stringResource(R.string.night_detail_fc_minima), fmt(s.hrMin), "bpm"), Metric(stringResource(R.string.night_detail_fc_a_riposo), fmt(s.restingHr), "bpm"))
    MetricRow(Metric(stringResource(R.string.night_detail_fc_media), fmt(s.hrAvg), "bpm"), Metric(stringResource(R.string.night_detail_rmssd), fmt(s.rmssd, 1), "ms"))
    MetricRow(Metric(stringResource(R.string.night_detail_sdnn), fmt(s.sdnn, 1), "ms"), Metric(stringResource(R.string.night_detail_pnn50), fmt(s.pnn50, 1), "%"))
    // Punto 10: fascia senza RR affidabili (solo FC o sensore ottico)
    if (detail.night.stages?.mode?.startsWith("FC") == true) {
      // dopo la notte: stesso avviso di prima della notte, piu' cosa non arriva a Intervals.icu
      AvvisoSenzaHrv()
      Text(
          stringResource(R.string.night_detail_l_hrv_di_questa),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    // Fase 7: fasi del sonno
    SleepStagesSection(detail.night)

    Text(stringResource(R.string.night_detail_frequenza_cardiaca_media_5), style = MaterialTheme.typography.titleSmall)
    TimeLineChart(
        times = w.map { it.startMs },
        values = w.map { it.hr },
        lineColor = HR_COLOR,
        modifier = Modifier.fillMaxWidth().height(180.dp),
    )

    Text(stringResource(R.string.night_detail_rmssd_5_minuti), style = MaterialTheme.typography.titleSmall)
    TimeLineChart(
        times = w.map { it.startMs },
        values = w.map { it.rmssd },
        lineColor = RMSSD_COLOR,
        modifier = Modifier.fillMaxWidth().height(180.dp),
    )

    Text(stringResource(R.string.night_detail_qualita_del_segnale), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.night_detail_affidabilita_notte_coperta_da, (fmt(s.qualityPct, 1)).toString(), s.beats.toString(), (fmt(s.pctGood, 1)).toString(), (fmt(s.pctCorrected, 1)).toString(), (fmt(s.pctDropped, 1)).toString(), s.gaps.toString(), s.windowsOk.toString(), s.windowsTotal.toString()),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // Fase 6: stato e invio manuale a Intervals.icu
    val night = detail.night
    Text(stringResource(R.string.night_detail_intervals_icu), style = MaterialTheme.typography.titleSmall)
    Text(
        tr(when {
          night.syncedAt != null -> "✓ Inviata il ${dateTimeLabel(night.syncedAt)}"
          night.syncStatus != null -> "Non inviata: ${night.syncStatus}"
          else -> "Non ancora inviata"
        }),
        style = MaterialTheme.typography.bodySmall,
        color =
            if (night.syncedAt == null && night.syncStatus != null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
      Button(onClick = onSync, enabled = !syncing) {
        Text(tr(if (night.syncedAt != null) "Invia di nuovo" else "Invia ora"))
      }
      if (syncing) {
        Spacer(Modifier.width(12.dp))
        CircularProgressIndicator(Modifier.size(24.dp))
      }
    }
  }
}

@Composable
private fun SleepStagesSection(night: NightListItem, titolo: Boolean = true) {
  val st = night.stages
  if (titolo) Text(stringResource(R.string.night_detail_fasi_del_sonno), style = MaterialTheme.typography.titleSmall)
  if (st == null || st.tstMin == 0) {
    Text(
        stringResource(R.string.night_detail_non_disponibili_per_questa),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    return
  }
  val s = night.summary
  val inBedMin = ((s.endMs - s.startMs) / 60_000).toInt().coerceAtLeast(1)
  val efficiency = 100.0 * st.tstMin / inBedMin
  val latency = st.sleepOnsetMs?.let { ((it - s.startMs) / 60_000).toInt().coerceAtLeast(0) }

  MetricRow(
      Metric(stringResource(R.string.night_detail_sonno_effettivo), hm(st.tstMin), ""),
      Metric(stringResource(R.string.night_detail_efficienza), fmt(efficiency), "%"),
  )
  Text(
      tr(buildString {
        st.sleepOnsetMs?.let { append("Addormentamento alle ${hourLabel(it)}") }
        latency?.let { append(" (dopo $it')") }
        st.sleepEndMs?.let { append(" · risveglio alle ${hourLabel(it)}") }
      }),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  Hypnogram(st, Modifier.fillMaxWidth().height(160.dp))
  MetricRow(
      Metric(stringResource(R.string.night_detail_profondo), hm(st.deepMin), ""),
      Metric(stringResource(R.string.night_detail_rem), hm(st.remMin), ""),
  )
  MetricRow(
      Metric(stringResource(R.string.night_detail_leggero), hm(st.lightMin), ""),
      Metric(stringResource(R.string.night_detail_veglia_notturna), hm(st.wakeMin), ""),
  )
  Text(
      tr(if (st.mode.startsWith("FC"))
          "Stima dalla sola frequenza cardiaca" + (if (st.mode.contains("ACC")) " e dal movimento" else ", senza dati di movimento") +
              ": la fascia non fornisce RR affidabili. È indicativa: non sostituisce una polisonnografia."
      else if (st.mode == "HRV+ACC+RESP")
          "Stima da frequenza cardiaca, HRV, movimento e respiro (accelerometro H10). " +
              "È indicativa: non sostituisce una polisonnografia."
      else if (st.mode == "HRV+ACC") "Stima da frequenza cardiaca, HRV e movimento (accelerometro H10). " +
          "È indicativa: non sostituisce una polisonnografia."
      else "Fasi stimate senza dati di movimento: frequenza cardiaca e HRV (fascia senza accelerometro). " +
          "È indicativa: non sostituisce una polisonnografia."),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

private data class Metric(val label: String, val value: String, val unit: String)

@Composable
private fun MetricRow(a: Metric, b: Metric) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    MetricCard(a, Modifier.weight(1f))
    MetricCard(b, Modifier.weight(1f))
  }
}

@Composable
private fun MetricCard(m: Metric, modifier: Modifier) {
  OutlinedCard(modifier) {
    Column(Modifier.padding(12.dp)) {
      Text(
          m.label,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(verticalAlignment = Alignment.Bottom) {
        Text(m.value, style = MaterialTheme.typography.headlineSmall)
        Text(
            tr(" ${m.unit}"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp),
        )
      }
    }
  }
}

// --- Riepilogo della notte per la schermata Sonno (stile Oura) --------------------------------

/**
 * Riepilogo completo di una notte, nell'ordine di Oura: orari, fasi del sonno, frequenza
 * cardiaca, HRV, qualita' del segnale e invio a Intervals.icu. Senza scorrimento proprio: si
 * inserisce in una schermata che scorre (la schermata Sonno). Il dettaglio in "Le mie notti"
 * resta com'e'.
 */
@Composable
fun RiepilogoNotte(sessionId: Long) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var syncing by remember { mutableStateOf(false) }
  var reloadKey by remember { mutableIntStateOf(0) }
  val state by
      produceState<LoadState<NightDetail>>(LoadState.Loading, sessionId, reloadKey) {
        value =
            try {
              withContext(Dispatchers.IO) {
                val db = SleepDb.get(context)
                db.loadNight(sessionId)?.let { LoadState.Ready(NightDetail(it, db.loadWindows(sessionId))) }
                    ?: LoadState.Error("Notte non trovata")
              }
            } catch (e: SQLException) {
              LoadState.Error(e.message ?: "Errore del database")
            }
      }
  when (val st = state) {
    is LoadState.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    is LoadState.Error -> Text(tr(st.message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    is LoadState.Ready -> {
      val night = st.data.night
      val s = night.summary
      val w = st.data.windows
      val soloFc = night.stages?.mode?.startsWith("FC") == true

      Sezione(stringResource(R.string.night_detail_fasi_del_sonno)) {
        Text(nightTimes(s.startMs, s.endMs), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SleepStagesSection(night, titolo = false)
      }

      Sezione(stringResource(R.string.night_detail_frequenza_cardiaca)) {
        MetricRow(Metric(stringResource(R.string.night_detail_fc_a_riposo), fmt(s.restingHr), "bpm"), Metric(stringResource(R.string.night_detail_fc_minima), fmt(s.hrMin), "bpm"))
        Text(stringResource(R.string.night_detail_media_della_notte_bpm, (fmt(s.hrAvg)).toString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TimeLineChart(times = w.map { it.startMs }, values = w.map { it.hr }, lineColor = HR_COLOR, modifier = Modifier.fillMaxWidth().height(160.dp))
      }

      Sezione(stringResource(R.string.night_detail_variabilita_cardiaca_hrv)) {
        if (soloFc) {
          AvvisoSenzaHrv()
          Text(
              stringResource(R.string.night_detail_l_hrv_di_questa),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
          MetricRow(Metric(stringResource(R.string.night_detail_rmssd), fmt(s.rmssd, 1), "ms"), Metric(stringResource(R.string.night_detail_sdnn), fmt(s.sdnn, 1), "ms"))
          Text(stringResource(R.string.night_detail_pnn50_rmssd_ogni_5, (fmt(s.pnn50, 1)).toString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          TimeLineChart(times = w.map { it.startMs }, values = w.map { it.rmssd }, lineColor = RMSSD_COLOR, modifier = Modifier.fillMaxWidth().height(160.dp))
        }
      }

      Sezione(stringResource(R.string.night_detail_registrazione)) {
        Text(
            stringResource(R.string.night_detail_affidabilita_battiti_scartati_interruzioni, (fmt(s.qualityPct, 1)).toString(), s.beats.toString(), (fmt(s.pctDropped, 1)).toString(), s.gaps.toString(), s.windowsOk.toString(), s.windowsTotal.toString()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            tr(when {
              night.syncedAt != null -> "Intervals.icu: ✓ inviata il ${dateTimeLabel(night.syncedAt)}"
              night.syncStatus != null -> "Intervals.icu: non inviata (${night.syncStatus})"
              else -> "Intervals.icu: non ancora inviata"
            }),
            style = MaterialTheme.typography.bodySmall,
            color =
                if (night.syncedAt == null && night.syncStatus != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant)
        if (night.syncedAt == null) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                  syncing = true
                  scope.launch {
                    withContext(Dispatchers.IO) { IntervalsSync.syncNight(context, sessionId) }
                    syncing = false
                    reloadKey++
                  }
                },
                enabled = !syncing) {
                  Text(stringResource(R.string.night_detail_invia_ora))
                }
            if (syncing) {
              Spacer(Modifier.width(12.dp))
              CircularProgressIndicator(Modifier.size(24.dp))
            }
          }
        }
      }
    }
  }
}
