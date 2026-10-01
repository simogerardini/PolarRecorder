package com.wboelens.polarrecorder.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.managers.PolarManager
import com.wboelens.polarrecorder.ui.components.DeviceList
import com.wboelens.polarrecorder.viewModels.DeviceViewModel
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Card

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSelectionScreen(
    deviceViewModel: DeviceViewModel,
    polarManager: PolarManager,
    onContinue: () -> Unit,
    onOpenNights: () -> Unit,
    nightDeviceName: String?,
    onStartNight: () -> Unit,
) {
  var nightStarting by remember { mutableStateOf(false) }
  val selectedDevices by deviceViewModel.selectedDevices.observeAsState(emptyList())
  val state = rememberPullToRefreshState()
  val coroutineScope = rememberCoroutineScope()
  val isRefreshing = polarManager.isRefreshing
  val isBLEEnabled = polarManager.isBLEEnabled

  // Simplified refresh function
  val onRefresh: () -> Unit = { coroutineScope.launch { polarManager.scanForDevices() } }

  MaterialTheme {
    Scaffold(
        topBar = {
          TopAppBar(
              title = { Text("Select Devices") },
              actions = {
                IconButton(onClick = onOpenNights) { Icon(Icons.Filled.Bedtime, "Le mie notti") }
                IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, "Trigger Refresh") }
              },
          )
        }
    ) { paddingValues ->
      PullToRefreshBox(
          modifier = Modifier.fillMaxSize().padding(paddingValues),
          state = state,
          isRefreshing = isRefreshing.value && isBLEEnabled.value,
          onRefresh = onRefresh,
      ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
          // BioSleep: avvio della notte con un tocco (impostazioni dell'ultima registrazione)
          if (nightDeviceName != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
              Column(
                  Modifier.padding(16.dp),
                  verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Text("Notte", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (nightStarting) "Avvio in corso: segui la notifica."
                    else "Fascia $nightDeviceName. Indossala, poi premi Avvia notte.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = {
                      nightStarting = true
                      polarManager.stopPeriodicScanning()
                      onStartNight()
                    },
                    enabled = !nightStarting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                  Text(if (nightStarting) "Avvio in corso…" else "Avvia notte")
                }
              }
            }
            Spacer(modifier = Modifier.height(16.dp))
          }

          DeviceList(
              deviceViewModel = deviceViewModel,
              isBLEEnabled = polarManager.isBLEEnabled.value,
          )

          Spacer(modifier = Modifier.weight(1f))

          Button(
              onClick = {
                polarManager.stopPeriodicScanning()
                onContinue()
              },
              enabled = selectedDevices.isNotEmpty(),
              modifier = Modifier.align(Alignment.End),
          ) {
            Text("Connect Devices")
          }
        }
      }
    }
  }
}
