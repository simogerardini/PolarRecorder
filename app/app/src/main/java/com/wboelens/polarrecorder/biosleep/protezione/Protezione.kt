package com.wboelens.polarrecorder.biosleep.protezione

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.gson.JsonParser

/** Una voce della checklist "Protezione notturna". */
data class VoceProtezione(val id: String, val titolo: String, val spiegazione: String, val ok: Boolean)

/** Marche con istruzioni proprie (Build.MANUFACTURER). */
enum class Marca(val nome: String, val pagina: String, val istruzioni: List<String>) {
  XIAOMI(
      "Xiaomi, Redmi, POCO", "xiaomi",
      listOf("Attiva l'Avvio automatico per BioSleep.", "In Risparmio batteria scegli \"Nessuna restrizione\".")),
  SAMSUNG(
      "Samsung", "samsung",
      listOf("Togli BioSleep da \"App in sospensione profonda\" e da \"App in sospensione\".", "Aggiungi BioSleep ad \"App mai in sospensione\".")),
  HUAWEI(
      "Huawei, Honor", "huawei",
      listOf("In Gestione avvio porta BioSleep su gestione manuale.", "Attiva tutte e tre le voci: avvio automatico, avvio secondario, esecuzione in background.")),
  OPPO(
      "OnePlus, Oppo, Realme", "oneplus",
      listOf("In Ottimizzazione batteria scegli \"Non ottimizzare\" per BioSleep.", "Attiva l'Avvio automatico.")),
  PIXEL("Google Pixel", "google", listOf("Basta l'ottimizzazione della batteria \"Senza restrizioni\".")),
  ALTRA("il tuo telefono", "", listOf("Imposta la batteria di BioSleep su \"Senza restrizioni\"."));

  val dontKillMyApp: String get() = "https://dontkillmyapp.com/" + pagina

  companion object {
    fun da(produttore: String): Marca =
        when (produttore.lowercase()) {
          "xiaomi", "redmi", "poco" -> XIAOMI
          "samsung" -> SAMSUNG
          "huawei", "honor" -> HUAWEI
          "oneplus", "oppo", "realme" -> OPPO
          "google" -> PIXEL
          else -> ALTRA
        }
  }
}

/** Notte interrotta segnalata dalla registrazione (Parte 1): data, minuti persi, causa. */
data class Interruzione(val data: String, val minuti: Int, val causa: String) {
  companion object {
    /** {"data": "2026-10-07", "minuti": 42, "causa": "app chiusa dal sistema"}; null se illeggibile. */
    fun da(testo: String?): Interruzione? =
        runCatching {
              val o = JsonParser.parseString(testo ?: return null).asJsonObject
              Interruzione(o.get("data")!!.asString, o.get("minuti")!!.asInt, o.get("causa")?.asString ?: "causa sconosciuta")
            }
            .getOrNull()
            ?.takeIf { it.minuti > 0 }
  }
}

object Protezione {
  /** Allarmi esatti: in checklist solo se l'app li usa davvero (permesso dichiarato nel manifest). */
  private fun usaAllarmiEsatti(context: Context): Boolean =
      runCatching {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions?.contains(Manifest.permission.SCHEDULE_EXACT_ALARM) == true
          }
          .getOrDefault(false)

  @SuppressLint("MissingPermission")
  fun stato(context: Context): List<VoceProtezione> = buildList {
    val pm = context.getSystemService(PowerManager::class.java)
    add(
        VoceProtezione(
            "batteria", "Batteria senza restrizioni",
            "La registrazione dura 8 ore a schermo spento: con l'ottimizzazione attiva Android può fermarla.",
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      add(
          VoceProtezione(
              "notifiche", "Notifiche consentite",
              "Servono per la notifica della notte in corso e per gli avvisi del coach.",
              ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
                  NotificationManagerCompat.from(context).areNotificationsEnabled()))
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && usaAllarmiEsatti(context)) {
      add(
          VoceProtezione(
              "allarmi", "Allarmi esatti consentiti", "Servono per avviare e fermare la notte all'ora giusta.",
              context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true))
    }
    val bt = context.getSystemService(BluetoothManager::class.java)?.adapter
    add(VoceProtezione("bluetooth", "Bluetooth attivo", "La fascia trasmette via Bluetooth per tutta la notte.", bt?.isEnabled == true))
  }

  fun completa(context: Context) = stato(context).all { it.ok }

  /** Apre la schermata di sistema per sistemare una voce. */
  @SuppressLint("BatteryLife") // registrazione continua da dispositivo connesso: vedi dichiarazione Play
  fun apri(context: Context, id: String) {
    val pkg = Uri.parse("package:" + context.packageName)
    val intent =
        when (id) {
          "batteria" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
          "notifiche" ->
              Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
          "allarmi" -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
          "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
          else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        }
    avvia(context, listOf(intent, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)))
  }

  /**
   * Schermate della marca, in ordine di tentativo (cambiano tra versioni del sistema: se nessuna
   * si apre, il dettaglio dell'app e poi dontkillmyapp.com). true se qualcosa si e' aperto.
   */
  fun apriMarca(context: Context, marca: Marca): Boolean {
    fun c(p: String, cls: String) = Intent().setComponent(ComponentName(p, cls))
    val pkg = Uri.parse("package:" + context.packageName)
    val tentativi =
        when (marca) {
          Marca.XIAOMI ->
              listOf(
                  c("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                  c("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                      .putExtra("package_name", context.packageName).putExtra("package_label", "BioSleep"))
          Marca.SAMSUNG ->
              listOf(
                  c("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                  c("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"))
          Marca.HUAWEI ->
              listOf(
                  c("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                  c("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                  c("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"))
          Marca.OPPO ->
              listOf(
                  c("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                  c("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                  c("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"))
          Marca.PIXEL, Marca.ALTRA -> listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    return avvia(context, tentativi + Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
  }

  private fun avvia(context: Context, intents: List<Intent>): Boolean {
    for (i in intents) {
      try {
        context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
      } catch (e: ActivityNotFoundException) {
        // prossimo tentativo
      } catch (e: SecurityException) {
        // componente non esportato su questa versione: prossimo tentativo
      }
    }
    return false
  }

  // --- Stato persistente: primo avvio e ultima interruzione (scritta dalla Parte 1) ---------------

  private const val PREFS = "biosleep_protezione"

  fun guidaVista(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("guida_vista", false)

  fun segnaGuidaVista(context: Context) =
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("guida_vista", true).apply()

  /** Contratto con la Parte 1: SharedPreferences "biosleep_interruzioni", chiave "ultima" (JSON). */
  fun ultimaInterruzione(context: Context): Interruzione? =
      Interruzione.da(context.getSharedPreferences("biosleep_interruzioni", Context.MODE_PRIVATE).getString("ultima", null))

  fun chiudiInterruzione(context: Context, data: String) =
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("interruzione_chiusa", data).apply()

  fun interruzioneChiusa(context: Context): String? =
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("interruzione_chiusa", null)
}
