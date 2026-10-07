package com.wboelens.polarrecorder.biosleep.hal

import android.content.Context
import android.content.Intent
import android.os.Build
import com.wboelens.polarrecorder.BuildConfig
import com.wboelens.polarrecorder.PolarRecorderApplication
import com.wboelens.polarrecorder.biosleep.sopravvivenza.EventiNotte
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Interfaccia per la Parte 3 (schermate): fasce trovate, fascia della sessione in corso, rapporto.
 *  - Fasce.trovate(context): elenco dei dispositivi trovati con tipo e driver;
 *  - Fasce.sessione: stato della fascia nella notte in corso (capacita', "solo FC", "RR non
 *    affidabili"), null se non si registra;
 *  - Fasce.condividi(context): apre la condivisione del rapporto fascia dell'ultima sessione.
 */
object Fasce {
  private val _sessione = MutableStateFlow<StatoFasciaSessione?>(null)
  val sessione: StateFlow<StatoFasciaSessione?> = _sessione.asStateFlow()

  internal fun aggiorna(s: StatoFasciaSessione?) {
    _sessione.value = s
  }

  fun trovate(context: Context): List<FasciaTrovata> {
    val app = context.applicationContext as PolarRecorderApplication
    return app.deviceState.allDevices.value.mapNotNull { d ->
      val gatt = FasceGatt.isGatt(context, d.info.deviceId) && !d.info.name.startsWith("Polar", true)
      val catena =
          if (gatt) listOf(TipoDriver.GATT_180D)
          else DriverRegistry.catena(d.info.name, setOf(DriverRegistry.UUID_HR_SERVICE))
      if (catena.isEmpty()) null
      else FasciaTrovata(d.info.deviceId, d.info.name, DriverRegistry.tipo(d.info.name), catena, d.info.rssi)
    }
  }

  /** Rapporto fascia dell'ultima sessione registrata, senza dati sanitari (null se nessuna). */
  fun rapportoUltimaSessione(context: Context): RapportoFascia? =
      EventiNotte.get(context).ultimoRapportoFascia()?.let { RapportoFascia.daJson(it) }

  fun condividi(context: Context): Boolean {
    val r = rapportoUltimaSessione(context) ?: return false
    val invio =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Rapporto fascia BioSleep")
            .putExtra(Intent.EXTRA_TEXT, r.testo() + "\n\n" + r.json())
    context.startActivity(Intent.createChooser(invio, "Invia il rapporto fascia").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return true
  }

  internal fun telefono() = "${Build.MANUFACTURER} ${Build.MODEL}"

  internal fun versioneApp() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
}
