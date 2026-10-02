package com.wboelens.polarrecorder.biosleep.auto

import android.app.AlarmManager
import android.app.PendingIntent
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.wboelens.polarrecorder.PolarRecorderApplication
import com.wboelens.polarrecorder.biosleep.SleepDb
import com.wboelens.polarrecorder.services.RecordingService
import java.util.concurrent.Executors
import java.util.regex.Pattern

/** Stato dell'avvio automatico, salvato nella memoria privata dell'app. */
class AutoStartStore(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  var enabled: Boolean
    get() = prefs.getBoolean(KEY_ENABLED, true)
    set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

  /** Id dell'associazione con la fascia (-1 = non associata). */
  var associationId: Int
    get() = prefs.getInt(KEY_ASSOC, -1)
    set(v) = prefs.edit().putInt(KEY_ASSOC, v).apply()

  /** Vero quando Android vede la fascia (cioe' e' indossata: la H10 si accende a contatto). */
  var strapPresent: Boolean
    get() = prefs.getBoolean(KEY_PRESENT, false)
    set(v) = prefs.edit().putBoolean(KEY_PRESENT, v).apply()

  var lastAutoStartMs: Long
    get() = prefs.getLong(KEY_LAST, 0L)
    set(v) = prefs.edit().putLong(KEY_LAST, v).apply()

  val isAssociated: Boolean
    get() = associationId >= 0

  companion object {
    private const val PREFS = "biosleep_autostart"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ASSOC = "association_id"
    private const val KEY_PRESENT = "strap_present"
    private const val KEY_LAST = "last_auto_start"
  }
}

/**
 * Avvio automatico della notte. Condizioni (tutte vere):
 *  - abitudini apprese (7 notti) e ora nella fascia serale appresa
 *  - fascia indossata e vicina al telefono (Android la "vede" tramite l'associazione)
 *  - telefono in carica
 *  - nessuna registrazione in corso e nessun avvio automatico nelle ultime 12 ore
 * Se la fascia c'e' ma manca una condizione (es. telefono non ancora in carica), si ricontrolla
 * ogni 10 minuti finche' la fascia serale non finisce.
 */
object AutoStart {
  private const val TAG = "BioSleep"
  private const val RECHECK_MS = 10 * 60_000L
  private const val COOLDOWN_MS = 12 * 3_600_000L
  private const val ALARM_REQUEST = 3001

  /**
   * SOLO PER PROVE: true = ignora abitudini, fascia oraria e pausa di 12 ore, cosi' l'avvio
   * automatico si puo' provare subito (restano fascia indossata e telefono in carica).
   * Rimettere false dopo la prova.
   */
  private const val TEST_MODE = false

  /** L'associazione richiede Android 13 (API 33) o successivo. */
  val isSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

  private val worker = Executors.newSingleThreadExecutor()

  /** Valuta le condizioni in background (lettura del database inclusa). */
  fun evaluateAsync(context: Context, reason: String) {
    val app = context.applicationContext
    worker.execute {
      @Suppress("TooGenericExceptionCaught")
      try {
        evaluate(app, reason)
      } catch (e: Exception) {
        Log.e(TAG, "Avvio automatico: valutazione fallita", e)
      }
    }
  }

  private fun evaluate(context: Context, reason: String) {
    val store = AutoStartStore(context)
    if (!store.enabled || !store.isAssociated || !store.strapPresent) {
      cancelRecheck(context)
      return
    }
    val now = System.currentTimeMillis()
    val habits = HabitLearner.learn(SleepDb.get(context).nightTimes(), now)
    if (!habits.learned && !TEST_MODE) {
      Log.i(TAG, "Avvio automatico ($reason): abitudini non ancora apprese")
      return
    }
    val app = context as? PolarRecorderApplication ?: context.applicationContext as PolarRecorderApplication
    if (app.isRecordingActive || (!TEST_MODE && now - store.lastAutoStartMs < COOLDOWN_MS)) {
      cancelRecheck(context)
      return
    }
    val nowNoon = HabitLearner.minutesAfterNoon(now, java.time.ZoneId.systemDefault())
    val inWindow = TEST_MODE || HabitLearner.isBedtime(now, habits)
    val charging = isCharging(context)
    Log.i(TAG, "Avvio automatico ($reason): fascia=sì, orario=$inWindow, in carica=$charging")

    when {
      inWindow && charging -> {
        store.lastAutoStartMs = now
        cancelRecheck(context)
        val intent =
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_START_NIGHT)
        ContextCompat.startForegroundService(context, intent)
        Log.i(TAG, "Avvio automatico della notte")
      }
      TEST_MODE -> scheduleRecheck(context, now + RECHECK_MS)
      nowNoon < habits.bedtimeFromNoon!! -> {
        // Troppo presto: ricontrolla all'inizio della fascia serale
        scheduleRecheck(context, now + (habits.bedtimeFromNoon - nowNoon) * 60_000L)
      }
      nowNoon <= habits.bedtimeToNoon!! -> scheduleRecheck(context, now + RECHECK_MS)
      else -> cancelRecheck(context) // fascia serale finita: niente avvio stanotte
    }
  }

  private fun isCharging(context: Context): Boolean =
      context.getSystemService(BatteryManager::class.java)?.isCharging == true

  private fun recheckIntent(context: Context): PendingIntent =
      PendingIntent.getBroadcast(
          context,
          ALARM_REQUEST,
          Intent(context, AutoStartAlarmReceiver::class.java),
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

  private fun scheduleRecheck(context: Context, atMs: Long) {
    val am = context.getSystemService(AlarmManager::class.java) ?: return
    // Allarme non esatto: puo' slittare di qualche minuto, non serve il permesso "sveglie esatte"
    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, recheckIntent(context))
  }

  private fun cancelRecheck(context: Context) {
    context.getSystemService(AlarmManager::class.java)?.cancel(recheckIntent(context))
  }

  // --- Associazione con la fascia (una volta sola) ---------------------------------------------

  /**
   * Chiede ad Android di associare la fascia. Android mostra un dialogo di conferma: [onPending]
   * riceve l'IntentSender da lanciare. Al termine [onDone] riceve l'esito.
   */
  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  fun associate(
      context: Context,
      deviceId: String,
      onPending: (IntentSender) -> Unit,
      onDone: (Boolean, String) -> Unit,
  ) {
    val cdm = context.getSystemService(CompanionDeviceManager::class.java)
    val filter =
        BluetoothLeDeviceFilter.Builder()
            .setNamePattern(Pattern.compile("Polar H10 ${Pattern.quote(deviceId)}"))
            .build()
    val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(true).build()
    cdm.associate(
        request,
        ContextCompat.getMainExecutor(context),
        object : CompanionDeviceManager.Callback() {
          override fun onAssociationPending(intentSender: IntentSender) = onPending(intentSender)

          override fun onAssociationCreated(associationInfo: AssociationInfo) {
            val store = AutoStartStore(context)
            store.associationId = associationInfo.id
            startObserving(context, associationInfo)
            onDone(true, "Fascia associata")
          }

          override fun onFailure(error: CharSequence?) {
            onDone(false, "Associazione non riuscita: ${error ?: "annullata"}")
          }
        },
    )
  }

  /** Chiede ad Android di avvisare l'app quando la fascia compare o scompare. */
  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private fun startObserving(context: Context, info: AssociationInfo) {
    val cdm = context.getSystemService(CompanionDeviceManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
      cdm.startObservingDevicePresence(
          ObservingDevicePresenceRequest.Builder().setAssociationId(info.id).build())
    } else {
      val mac = info.deviceMacAddress?.toString()?.uppercase() ?: return
      @Suppress("DEPRECATION") cdm.startObservingDevicePresence(mac)
    }
  }

  /** Rimuove l'associazione: l'avvio automatico si ferma. */
  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  fun disassociate(context: Context) {
    val store = AutoStartStore(context)
    if (store.associationId >= 0) {
      context.getSystemService(CompanionDeviceManager::class.java).disassociate(store.associationId)
    }
    store.associationId = -1
    store.strapPresent = false
    cancelRecheck(context)
  }
}
