package com.wboelens.polarrecorder.biosleep.demo

import android.content.ContentValues
import android.content.Context
import android.util.Log
import com.wboelens.polarrecorder.biosleep.SleepDb
import com.wboelens.polarrecorder.biosleep.age.AgeProfileStore
import com.wboelens.polarrecorder.biosleep.age.Sex
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Modalita' demo: 14 notti di esempio per chi non ha la fascia (revisori del Play Store,
 * schermate del negozio). Le notti hanno sessions.source = "DEMO": non vengono mai inviate a
 * Intervals.icu ne' copiate per il sito, e "Esci dalla demo" cancella solo loro.
 */
object Demo {
  const val SORGENTE = "DEMO"
  /** Valori di esempio per l'Eta' NoctaliX (senza Intervals.icu). */
  const val VO2_ESEMPIO = 49.0
  val SETTIMANE_ESEMPIO = listOf(240.0, 275.0, 210.0, 260.0)

  private const val TAG = "NoctalixDemo"
  private const val PREFS = "noctalix_demo"
  private const val K_ATTIVA = "attiva"
  private const val K_PROFILO_DEMO = "profilo_eta_demo"

  /**
   * Aggancio per la Parte 3 (cache di Intervals.icu di esempio per Oggi, Calendario, Grafici;
   * coach fermo): chiamato con true all'ingresso e false all'uscita, fuori dal main thread.
   */
  @Volatile var parte3: (Context, Boolean) -> Unit = { _, _ -> }

  private val _stato = MutableStateFlow(false)
  @Volatile private var letto = false

  fun stato(context: Context): StateFlow<Boolean> {
    if (!letto) {
      _stato.value = prefs(context).getBoolean(K_ATTIVA, false)
      letto = true
    }
    return _stato.asStateFlow()
  }

  fun attiva(context: Context): Boolean = stato(context).value

  private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /** La sessione e' una notte di esempio? */
  fun eNotteDemo(context: Context, sessionId: Long): Boolean =
      runCatching {
        SleepDb.get(context).readableDatabase.rawQuery(
            "SELECT source FROM sessions WHERE id = ?", arrayOf(sessionId.toString())).use { c ->
          c.moveToFirst() && c.getString(0) == SORGENTE
        }
      }.getOrDefault(false)

  fun entraInBackground(context: Context) = Thread { entra(context.applicationContext) }.start()

  fun esciInBackground(context: Context) = Thread { esci(context.applicationContext) }.start()

  /** Crea le notti di esempio (fino a stamattina) e un profilo per l'eta', se non c'e'. */
  @Synchronized
  fun entra(context: Context) {
    val db = SleepDb.get(context)
    cancellaNotti(db)
    val zona = ZoneId.systemDefault()
    for (n in NottiDemo.genera(LocalDate.now(zona), zona)) inserisci(db, n)
    val eta = AgeProfileStore(context)
    if (eta.birthDate == null || eta.sex == null) {
      eta.birthDate = LocalDate.now(zona).minusYears(40).withDayOfYear(150)
      eta.sex = Sex.entries.first()
      prefs(context).edit().putBoolean(K_PROFILO_DEMO, true).apply()
    }
    runCatching { parte3(context, true) }.onFailure { Log.w(TAG, "Demo Parte 3: ${it.message}") }
    prefs(context).edit().putBoolean(K_ATTIVA, true).apply()
    _stato.value = true
  }

  /** Toglie tutto cio' che la demo ha creato. I dati veri non vengono toccati. */
  @Synchronized
  fun esci(context: Context) {
    cancellaNotti(SleepDb.get(context))
    if (prefs(context).getBoolean(K_PROFILO_DEMO, false)) {
      val eta = AgeProfileStore(context)
      eta.birthDate = null
      eta.sex = null
    }
    runCatching { parte3(context, false) }.onFailure { Log.w(TAG, "Demo Parte 3: ${it.message}") }
    prefs(context).edit().clear().apply()
    _stato.value = false
  }

  private fun cancellaNotti(db: SleepDb) {
    val ids =
        db.readableDatabase.rawQuery("SELECT id FROM sessions WHERE source = ?", arrayOf(SORGENTE)).use { c ->
          buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }
    ids.forEach { db.deleteSession(it) }
  }

  private fun inserisci(db: SleepDb, n: NotteDemo) {
    val id = db.createSession("notte_demo_${n.data}", SORGENTE, n.inizioMs, SORGENTE)
    db.closeSession(id, n.fineMs)
    val w = db.writableDatabase
    w.beginTransaction()
    try {
      w.insertOrThrow("nights", null, ContentValues().apply {
        put("session_id", id)
        put("start_ms", n.inizioMs)
        put("end_ms", n.fineMs)
        put("beats", n.battiti)
        put("gaps", 0)
        put("pct_good", n.qualitaPct)
        put("pct_corrected", 1.0)
        put("pct_dropped", (100 - n.qualitaPct - 1).coerceAtLeast(0.0))
        put("hr_min", n.fcMin)
        put("resting_hr", n.fc5MinBassi)
        put("hr_avg", n.fcMedia)
        put("rmssd", n.rmssd)
        put("sdnn", n.sdnn)
        put("pnn50", n.pnn50)
        put("windows_ok", n.finestre.size)
        put("windows_total", n.finestre.size)
        put("quality_pct", n.qualitaPct)
        put("analyzed_at", n.fineMs)
        put("hypno_start_ms", n.ipnoInizioMs)
        put("hypnogram", n.ipnogramma)
        put("sleep_onset_ms", n.addormentamentoMs)
        put("sleep_end_ms", n.risveglioMs)
        put("tst_min", n.sonnoMin)
        put("deep_min", n.profondoMin)
        put("light_min", n.leggeroMin)
        put("rem_min", n.remMin)
        put("wake_min", n.vegliaMin)
        put("staging_mode", "HRV+ACC+RESP")
      })
      for (f in n.finestre) {
        w.insertOrThrow("windows", null, ContentValues().apply {
          put("session_id", id)
          put("start_ms", f.inizioMs)
          put("hr", f.fc)
          put("rmssd", f.rmssd)
          put("sdnn", f.sdnn)
          put("pnn50", f.pnn50)
          put("quality", f.qualita)
        })
      }
      w.setTransactionSuccessful()
    } finally {
      w.endTransaction()
    }
  }
}
