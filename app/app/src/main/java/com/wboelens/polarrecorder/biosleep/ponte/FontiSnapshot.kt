package com.wboelens.polarrecorder.biosleep.ponte

import android.content.Context
import android.database.Cursor
import android.util.Log
import com.wboelens.polarrecorder.BuildConfig
import com.wboelens.polarrecorder.biosleep.SleepDb
import com.wboelens.polarrecorder.biosleep.age.AgeProfileStore
import com.wboelens.polarrecorder.biosleep.age.AgeRepository
import com.wboelens.polarrecorder.biosleep.age.BioAgeOutcome
import com.wboelens.polarrecorder.biosleep.auto.HabitLearner
import com.wboelens.polarrecorder.biosleep.sopravvivenza.EventiNotte
import com.wboelens.polarrecorder.biosleep.sopravvivenza.RilevaInterruzioni
import java.time.LocalDate
import java.time.ZoneId

/** Cio' che una notte riceve dalla Parte 3 (chiave: data del mattino YYYY-MM-DD). */
data class NotteParte3(
    val punteggio: Int? = null,
    /** Punteggio.contributi in JSON: [{"nome":..,"punti":..,"peso":..}]. */
    val contributiJson: String? = null,
    /** Chiavi del vocabolario dei tag (tag.ts). */
    val tag: List<String>? = null,
)

/**
 * I blocchi della copia che produce la Parte 3 con DatiParte3.perCopia(context), gia' nei nomi di
 * src/types/snapshot.ts: la Parte 1 li copia senza ricalcoli. Ogni campo puo' essere null.
 */
data class BlocchiParte3(
    /** ProfiloAtleta.json() + modalita + disponibilita_date. */
    val profiloJson: String? = null,
    /** Prontezza: biometria, calibrazione, forma, consiglio, motivi_codici. */
    val prontezzaJson: String? = null,
    /** SonnoAppreso senza fascia_letto e inizio_mattino (li aggiunge la Parte 1). */
    val sonnoJson: String? = null,
    /** Riepiloghi v1 in ordine crescente di data, con testo_locale in ogni Messaggio. */
    val riepiloghiJson: List<String> = emptyList(),
    val tagGiorni: Map<String, List<String>>? = null,
    val tagSedute: Map<String, List<String>>? = null,
    val notti: Map<String, NotteParte3> = emptyMap(),
)

/**
 * Aggancio con la Parte 3, impostato in Application.onCreate:
 *   Parte3Ponte.fonti = object : FontiParte3 {
 *     override fun perCopia(context: Context) = DatiParte3.perCopia(context)
 *     override fun intervals(context: Context) = ...   // athlete_id, access_token, scope
 *     override fun applica(context: Context, comando: Comando) = ...
 *   }
 * Finche' non e' impostato, i blocchi restano assenti e i comandi vengono rifiutati.
 */
interface FontiParte3 {
  fun perCopia(context: Context): BlocchiParte3? = null

  /** athlete_id, access_token, scope del login OAuth (null: login non fatto o API key). */
  fun intervals(context: Context): Triple<String, String, String>? = null

  fun nomeAtleta(context: Context): String? = null

  /** tag_giorno, tag_seduta, disponibilita_data. null = non gestito (-> rifiutato). */
  fun applica(context: Context, comando: Comando): EsitoComando? = null
}

object Parte3Ponte {
  @Volatile var fonti: FontiParte3 = object : FontiParte3 {}
}

/** Raccoglie dal telefono i dati della copia. Da chiamare fuori dal main thread. */
object FontiSnapshot {
  private const val TAG = "NoctalixPonte"
  private const val PREFS_CACHE = "noctalix_ponte_cache"

  fun raccogli(context: Context, esiti: List<EsitoComando>): DatiCopia {
    val p3 = Parte3Ponte.fonti
    val zona = ZoneId.systemDefault()
    val blocchi =
        runCatching { p3.perCopia(context) }
            .onFailure { Log.w(TAG, "Blocchi della Parte 3 non disponibili: ${it.message}") }
            .getOrNull()
    val notti = notti(context, zona).map { n ->
      val extra = blocchi?.notti?.get(CopiaSnapshot.dataMattino(n.fineMs, zona))
      n.copy(punteggioSonno = extra?.punteggio, punteggioContributiJson = extra?.contributiJson, tag = extra?.tag)
    }
    return DatiCopia(
        generatoMs = System.currentTimeMillis(),
        zona = zona,
        versioneApp = BuildConfig.VERSION_NAME,
        lingua = lingua(context),
        atletaId = runCatching { p3.intervals(context)?.first }.getOrNull() ?: "",
        atletaNome = runCatching { p3.nomeAtleta(context) }.getOrNull(),
        notti = notti,
        riepiloghiJson = blocchi?.riepiloghiJson.orEmpty(),
        profiloJson = blocchi?.profiloJson,
        prontezzaJson = blocchi?.prontezzaJson,
        tagGiorni = blocchi?.tagGiorni,
        tagSedute = blocchi?.tagSedute,
        etaJson = etaDelGiorno(context, zona),
        sonno = fasceSonno(context),
        sonnoJson = blocchi?.sonnoJson,
        comandiApplicati = esiti,
    )
  }

  private fun lingua(context: Context): String =
      when (context.resources.configuration.locales[0].language) {
        "en" -> "en"
        "es" -> "es"
        "zh" -> "zh"
        else -> "it"
      }

  private fun Cursor.d(i: Int): Double? = if (isNull(i)) null else getDouble(i)

  private fun Cursor.l(i: Int): Long? = if (isNull(i)) null else getLong(i)

  private fun Cursor.n(i: Int): Int? = if (isNull(i)) null else getInt(i)

  private fun Cursor.s(i: Int): String? = if (isNull(i)) null else getString(i)

  fun notti(context: Context, zona: ZoneId): List<NotteSorgente> {
    val sleep = SleepDb.get(context)
    val db = sleep.readableDatabase
    val eventi = EventiNotte.get(context)
    val out = mutableListOf<NotteSorgente>()
    db.rawQuery(
        """SELECT n.session_id, n.start_ms, n.end_ms, n.hr_min, n.resting_hr, n.hr_avg, n.rmssd, n.sdnn,
                  n.quality_pct, n.hypno_start_ms, n.hypnogram, n.sleep_onset_ms, n.sleep_end_ms, n.tst_min,
                  n.deep_min, n.light_min, n.rem_min, n.wake_min, n.staging_mode, s.source
           FROM nights n LEFT JOIN sessions s ON s.id = n.session_id
           WHERE n.start_ms IS NOT NULL AND n.end_ms IS NOT NULL ORDER BY n.end_ms""",
        null).use { c ->
      while (c.moveToNext()) {
        val id = c.getLong(0)
        val inizio = c.getLong(1)
        val fine = c.getLong(2)
        val (buchi, interruzioni) = interruzioni(context, sleep, eventi, id, inizio, fine, zona)
        out += NotteSorgente(
            inizioMs = inizio, fineMs = fine,
            fcMin = c.d(3), fc5MinBassi = c.d(4), fcMedia = c.d(5), rmssd = c.d(6), sdnn = c.d(7),
            qualitaPct = c.d(8), ipnoInizioMs = c.l(9), ipnogramma = c.s(10),
            addormentamentoMs = c.l(11), risveglioMs = c.l(12), sonnoMin = c.n(13),
            profondoMin = c.n(14), leggeroMin = c.n(15), remMin = c.n(16), vegliaMin = c.n(17),
            metodoFasi = c.s(18), sensore = c.s(19),
            finestre = finestre(db, id), buchi = buchi, interruzioni = interruzioni)
      }
    }
    return out
  }

  private fun finestre(db: android.database.sqlite.SQLiteDatabase, id: Long): List<FinestraSorgente> =
      db.rawQuery("SELECT start_ms, hr, rmssd, sdnn, pnn50, quality FROM windows WHERE session_id = ? ORDER BY start_ms",
          arrayOf(id.toString())).use { c ->
        buildList {
          while (c.moveToNext()) add(FinestraSorgente(c.getLong(0), c.d(1), c.d(2), c.d(3), c.d(4), c.d(5)))
        }
      }

  /**
   * Buchi della notte. Solo per le notti con minuti persi: i battiti si rileggono (e si possono
   * rianalizzare) finche' l'archivio esiste; dopo, resta il totale salvato senza i buchi.
   */
  private fun interruzioni(
      context: Context, sleep: SleepDb, eventi: EventiNotte, id: Long, inizio: Long, fine: Long, zona: ZoneId,
  ): Pair<List<Pair<Long, Long>>?, List<Pair<Int, String?>>> {
    val salvata =
        eventi.readableDatabase.rawQuery("SELECT minuti_persi, descrizione FROM interruzioni WHERE session_id = ?",
            arrayOf(id.toString())).use { c -> if (c.moveToFirst()) c.getInt(0) to c.s(1) else null }
    if (salvata == null || salvata.first <= 0) return emptyList<Pair<Long, Long>>() to emptyList()
    val battiti = runCatching { sleep.loadRr(id).first }.getOrNull()
    if (battiti == null || battiti.isEmpty()) return null to listOf(salvata)
    return try {
      val r = RilevaInterruzioni.trova(battiti, eventi.eventiTra(inizio, fine), zona)
      r.buchi.map { it.daMs to it.aMs } to
          r.buchi.map { (((it.aMs - it.daMs) / 60_000L).toInt()) to it.breve }
    } catch (e: RuntimeException) {
      Log.w(TAG, "Buchi della notte $id non ricalcolati: ${e.message}")
      null to listOf(salvata)
    }
  }

  /** L'eta' si calcola al piu' una volta al giorno: legge anche Intervals.icu (VO2max, attivita'). */
  private fun etaDelGiorno(context: Context, zona: ZoneId): String? {
    val prefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
    val oggi = LocalDate.now(zona).toString()
    if (prefs.getString("eta_data", null) == oggi) return prefs.getString("eta_json", null)
    val profilo = AgeProfileStore(context)
    val nascita = profilo.birthDate ?: return null
    val sesso = profilo.sex ?: return null
    val dati = runCatching { AgeRepository.load(context, nascita, sesso) }.getOrNull() ?: return prefs.getString("eta_json", null)
    val e =
        when (val o = dati.outcome) {
          is BioAgeOutcome.Ready ->
              EtaSorgente(oggi, o.result.age, o.result.chronologicalAge, o.result.low, o.result.high, dati.pace, false,
                  o.result.components.map { ComponenteEta(it.key, it.years, it.valore, it.unita) })
          is BioAgeOutcome.Calibrating ->
              EtaSorgente(oggi, null, null, null, null, null, true, o.preview.map { ComponenteEta(it.key, it.years, it.valore, it.unita) })
        }
    val json = Json.scrivi(CopiaSnapshot.eta(e))
    prefs.edit().putString("eta_data", oggi).putString("eta_json", json).apply()
    return json
  }

  /** Solo le fasce apprese (HabitLearner); fabbisogno, deficit e cronotipo sono della Parte 3. */
  private fun fasceSonno(context: Context): SonnoSorgente? {
    val h = runCatching { HabitLearner.learn(SleepDb.get(context).nightTimes(), System.currentTimeMillis()) }.getOrNull()
    if (h == null || !h.learned) return null
    return SonnoSorgente(
        lettoDa = h.bedtimeFromNoon?.let { HabitLearner.noonMinutesToClock(it) },
        lettoA = h.bedtimeToNoon?.let { HabitLearner.noonMinutesToClock(it) },
        inizioMattino = HabitLearner.minutesToClock(h.morningFromMinute),
    )
  }
}
