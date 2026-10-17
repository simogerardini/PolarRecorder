package com.wboelens.polarrecorder.biosleep.demo

import com.wboelens.polarrecorder.biosleep.tag.TagDb
import android.content.Context
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.wboelens.polarrecorder.biosleep.cache.CacheDb
import com.wboelens.polarrecorder.biosleep.cache.CacheRepo
import com.wboelens.polarrecorder.biosleep.cache.CacheSync
import com.wboelens.polarrecorder.biosleep.cache.EsitoDettaglio
import com.wboelens.polarrecorder.biosleep.cache.Lettura
import com.wboelens.polarrecorder.biosleep.readiness.BioBaseline
import com.wboelens.polarrecorder.biosleep.readiness.FormaCalc
import com.wboelens.polarrecorder.biosleep.readiness.PyJson
import com.wboelens.polarrecorder.biosleep.riepilogo.RiepilogoDb
import com.wboelens.polarrecorder.biosleep.training.DettaglioParser
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Modalita' demo, lato Parte 3: Oggi, Calendario, Grafici e coach con dati di esempio.
 *
 * Niente scritture nei dati veri: con la demo attiva CacheDb.get e RiepilogoDb.get restituiscono
 * database IN MEMORIA (file null), riempiti con l'insieme di esempio passando per lo stesso
 * percorso della lettura reale (CacheSync.aggiorna + CachePayload). La wellness Noctalix* viene dalle
 * notti DEMO della Parte 1 (NottiLocali le unisce come per le notti non inviate). Uscendo si
 * buttano i database in memoria: la cache e i riepiloghi veri non sono mai stati toccati.
 */
object DemoParte3 {
  private const val TAG = "NoctalixDemo3"
  @Volatile private var cache: CacheDb? = null
  @Volatile private var riepiloghi: RiepilogoDb? = null
  @Volatile private var tag: TagDb? = null
  @Volatile private var giorno: LocalDate? = null
  /**
   * Stato visto dalla Parte 3: null all'avvio del processo (si segue Demo.attiva), poi quello
   * ricevuto da Demo.parte3. Serve perche' Demo.esci chiama parte3(false) PRIMA di spegnere il
   * proprio stato: da quel momento la Parte 3 e' gia' fuori dalla demo.
   */
  @Volatile private var qui: Boolean? = null

  /** Aggancio Demo.parte3 (fuori dal main thread). */
  fun cambia(context: Context, attiva: Boolean) {
    qui = attiva
    if (attiva) prepara(context.applicationContext, LocalDate.now()) else esci(context.applicationContext)
  }

  /** All'avvio del processo (Application.onCreate): lo stato della demo e' noto anche senza Context. */
  fun inizializza(context: Context) {
    if (qui == null) qui = Demo.attiva(context)
  }

  /** Scritture verso Intervals.icu vietate (IntervalsClient, che non ha un Context). */
  fun scrittureBloccate(): Boolean = qui == true

  /** Solo per i test. */
  internal fun perTest(attiva: Boolean?) {
    qui = attiva
  }

  /** Demo attiva per la Parte 3 (cache, riepiloghi, coach). */
  fun attiva(context: Context): Boolean = qui ?: Demo.attiva(context)

  /** La cache di esempio (null fuori dalla demo): CacheDb.get la usa al posto di quella vera. */
  fun cacheDb(context: Context): CacheDb? {
    if (!attiva(context)) return null
    prepara(context.applicationContext, LocalDate.now())
    return cache
  }

  /** I riepiloghi di esempio (null fuori dalla demo): RiepilogoDb.get li usa al posto di quelli veri. */
  fun riepilogoDb(context: Context): RiepilogoDb? {
    if (!attiva(context)) return null
    prepara(context.applicationContext, LocalDate.now())
    return riepiloghi
  }

  /** I tag di esempio (null fuori dalla demo): TagDb.get li usa al posto di quelli veri. */
  @Synchronized
  fun tagDb(context: Context): TagDb? {
    if (!attiva(context)) return null
    return tag ?: TagDb.inMemoria(context.applicationContext).also { tag = it }
  }

  /** CacheSync in demo: nessuna rete, si rigenerano i dati di esempio (date relative a oggi). */
  fun ricarica(context: Context) {
    synchronized(this) { giorno = null }
    prepara(context.applicationContext, LocalDate.now())
  }

  @Synchronized
  private fun prepara(app: Context, oggi: LocalDate) {
    if (giorno == oggi && cache != null && riepiloghi != null) return
    val db = cache ?: CacheDb(app, null).also { cache = it }
    val errore = CacheSync.aggiorna(db, { risorsa, da, a -> Lettura.Ok(DatiDemo.json(risorsa, da, a, oggi)) }, oggi)
    if (errore != null) Log.w(TAG, "Cache di esempio: $errore")
    val rie = riepiloghi ?: RiepilogoDb(app, null).also { riepiloghi = it }
    giorno = oggi
    runCatching {
      val repo = CacheRepo.get(app) // in demo legge la cache di esempio appena scritta
      rie.salva(oggi.toString(), DatiDemo.riepilogo(oggi, repo.baseline(oggi), repo.forma(oggi).oggi, DatiDemo.sedute(oggi)))
    }.onFailure { Log.w(TAG, "Riepilogo di esempio: ${it.message}") }
  }

  /**
   * Dettaglio di un'attivita' di esempio, senza Intervals.icu: numeri e zone dalla cache di
   * esempio, niente grafici nel tempo (i flussi esistono solo sul server).
   */
  fun dettaglio(context: Context, id: String): EsitoDettaglio {
    val oggi = LocalDate.now()
    val o = CacheRepo.get(context).attivita(oggi.minusDays(35), oggi).firstOrNull { PyJson.str(it.get("id")) == id }
    val a = o?.let { DettaglioParser.attivita(it) } ?: return EsitoDettaglio.Errore("demo: $id")
    return EsitoDettaglio.Pronto(a, null)
  }

  /** "Esci dalla demo": via i database in memoria; le schermate rileggono quelli veri. */
  @Synchronized
  fun esci(context: Context) {
    runCatching { cache?.close() }
    runCatching { riepiloghi?.close() }
    runCatching { tag?.close() }
    tag = null
    cache = null
    riepiloghi = null
    giorno = null
    RiepilogoDb.avvisaCambio()
    TagDb.avvisaCambio()
    CacheSync.aggiornaInBackground(context.applicationContext, forza = true)
  }
}

/** Insieme di esempio, deterministico e relativo a oggi. Nessun dato reale. */
internal object DatiDemo {
  /** Seduta tipo per giorno della settimana: sport, nome, minuti, TSS, distanza (m), FC media. */
  data class Tipo(val sport: String, val nome: String, val minuti: Int, val tss: Int, val metri: Int, val fc: Int, val passi: String)

  private val SETTIMANA =
      mapOf(
          DayOfWeek.MONDAY to Tipo("Swim", "Swim · Endurance", 45, 35, 2200, 128,
              "Warm up\n- 10m Z1\n\nMain set 6x\n- 3m Z2\n- 1m Z1\n\nCool down\n- 11m Z1"),
          DayOfWeek.TUESDAY to Tipo("Ride", "Bike · VO2max 5x3'", 60, 75, 30000, 148,
              "Warm up\n- 15m Z2\n\nMain set 5x\n- 3m Z5\n- 3m Z1\n\nCool down\n- 15m Z1"),
          DayOfWeek.WEDNESDAY to Tipo("Run", "Run · Endurance Z2", 45, 45, 8500, 141,
              "Warm up\n- 10m Z1\n\nMain set\n- 30m Z2\n\nCool down\n- 5m Z1"),
          DayOfWeek.THURSDAY to Tipo("Swim", "Swim · Technique", 40, 30, 1900, 125,
              "Warm up\n- 10m Z1\n\nMain set 8x\n- 2m Z2\n- 1m Z1\n\nCool down\n- 6m Z1"),
          DayOfWeek.FRIDAY to Tipo("Run", "Run · Tempo 3x10'", 55, 62, 10500, 152,
              "Warm up\n- 12m Z1\n\nMain set 3x\n- 10m Z3\n- 2m Z1\n\nCool down\n- 7m Z1"),
          DayOfWeek.SATURDAY to Tipo("Ride", "Bike · Long endurance", 120, 105, 60000, 136,
              "Main set\n- 120m Z2"),
          DayOfWeek.SUNDAY to Tipo("Run", "Run · Long run", 75, 80, 14500, 143,
              "Warm up\n- 10m Z1\n\nMain set\n- 60m Z2\n\nCool down\n- 5m Z1"),
      )

  /** Ogni 4a settimana di scarico (70%); una seduta saltata 10 giorni fa, per realismo. */
  private fun fattore(d: LocalDate, oggi: LocalDate): Double {
    val settimane = java.time.temporal.ChronoUnit.WEEKS.between(d.with(DayOfWeek.MONDAY), oggi.with(DayOfWeek.MONDAY))
    return if (settimane % 4 == 3L) 0.7 else 1.0
  }

  private fun eseguita(d: LocalDate, oggi: LocalDate) = d < oggi && d != oggi.minusDays(10)

  private fun carico(d: LocalDate, oggi: LocalDate): Double =
      if (eseguita(d, oggi)) SETTIMANA.getValue(d.dayOfWeek).tss * fattore(d, oggi) else 0.0

  fun sedute(oggi: LocalDate): List<String> =
      SETTIMANA.getValue(oggi.dayOfWeek).let { listOf("${it.nome} (${it.minuti}min)") }

  fun json(risorsa: String, da: LocalDate, a: LocalDate, oggi: LocalDate): String {
    val out = JsonArray()
    when (risorsa) {
      "wellness" -> {
        // CTL/ATL come Intervals.icu (stesse costanti della forma dell'app), da 200 giorni prima
        var ctl = 45.0
        var atl = 45.0
        val ctlGiorno = HashMap<LocalDate, Double>()
        var d = oggi.minusDays(200)
        while (!d.isAfter(oggi)) {
          val c = carico(d, oggi)
          ctl = ctl * FormaCalc.EWMA_CTL + c * (1 - FormaCalc.EWMA_CTL)
          atl = atl * FormaCalc.EWMA_ATL + c * (1 - FormaCalc.EWMA_ATL)
          ctlGiorno[d] = ctl
          if (!d.isBefore(da) && !d.isAfter(a)) {
            out.add(JsonObject().apply {
              addProperty("id", d.toString())
              addProperty("ctl", r1(ctl))
              addProperty("atl", r1(atl))
              ctlGiorno[d.minusDays(7)]?.let { addProperty("rampRate", r1(ctl - it)) }
            })
          }
          d = d.plusDays(1)
        }
      }
      "activities" -> {
        var d = maxOf(da, oggi.minusDays(28))
        while (!d.isAfter(minOf(a, oggi))) {
          if (eseguita(d, oggi)) {
            val t = SETTIMANA.getValue(d.dayOfWeek)
            val f = fattore(d, oggi)
            val settimanaCorrente = !d.isBefore(oggi.with(DayOfWeek.MONDAY))
            out.add(JsonObject().apply {
              addProperty("id", "demo_a_$d")
              addProperty("start_date_local", "${d}T07:00:00")
              addProperty("type", t.sport)
              addProperty("name", t.nome)
              addProperty("moving_time", (t.minuti * 60 * f).toInt())
              addProperty("elapsed_time", (t.minuti * 60 * f).toInt() + 120)
              addProperty("distance", r1(t.metri * f))
              addProperty("icu_training_load", (t.tss * f).toInt())
              addProperty("average_heartrate", t.fc)
              addProperty("max_heartrate", t.fc + 22)
              addProperty("total_elevation_gain", if (t.sport == "Swim") 0.0 else r1(t.metri / 150.0))
              addProperty("calories", (t.minuti * f * 10).toInt())
              addProperty("icu_intensity", r1(55.0 + t.tss * 100.0 / t.minuti / 3))
              addProperty("source", "MANUAL")
              addProperty("average_speed", r1(t.metri / (t.minuti * 60.0) * 100) / 100)
              // zone FC (soglie superiori in bpm) e tempo in zona, secondo il tipo di seduta
              add("icu_hr_zones", JsonArray().apply { listOf(138, 151, 160, 169, 185).forEach { add(it) } })
              val quote = when {
                "VO2max" in t.nome -> listOf(0.30, 0.40, 0.10, 0.10, 0.10)
                "Tempo" in t.nome -> listOf(0.25, 0.35, 0.35, 0.05, 0.0)
                else -> listOf(0.25, 0.70, 0.05, 0.0, 0.0)
              }
              val sec = t.minuti * 60 * f
              add("icu_hr_zone_times", JsonArray().apply { quote.forEach { q -> add((sec * q).toInt()) } })
              if (settimanaCorrente) {
                addProperty("paired_event_id", "demo_e_$d")
                addProperty("compliance", 97.0)
              }
            })
          }
          d = d.plusDays(1)
        }
      }
      "events" -> {
        val lunedi = oggi.with(DayOfWeek.MONDAY)
        for (i in 0L..6L) {
          val d = lunedi.plusDays(i)
          if (d.isBefore(da) || d.isAfter(a)) continue
          val t = SETTIMANA.getValue(d.dayOfWeek)
          out.add(JsonObject().apply {
            addProperty("id", "demo_e_$d")
            addProperty("start_date_local", "${d}T00:00:00")
            addProperty("category", "WORKOUT")
            addProperty("type", t.sport)
            addProperty("name", t.nome)
            addProperty("description", t.passi)
            addProperty("moving_time", t.minuti * 60)
            addProperty("icu_training_load", t.tss)
            addProperty("external_id", "sw:demo_$d")
            if (eseguita(d, oggi)) addProperty("paired_activity_id", "demo_a_$d")
          })
        }
      }
    }
    return out.toString()
  }

  /** Riepilogo giornaliero di esempio nel formato del cervello, coerente con banda e forma calcolate. */
  fun riepilogo(oggi: LocalDate, b: BioBaseline, forma: com.wboelens.polarrecorder.biosleep.readiness.StatoForma?, sedute: List<String>): String {
    val messaggi = JsonArray()
    fun msg(tipo: String, codice: String, testo: String, valori: Map<String, String> = emptyMap()) =
        messaggi.add(JsonObject().apply {
          addProperty("tipo", tipo)
          addProperty("codice", codice)
          add("valori", JsonObject().apply { valori.forEach { (k, v) -> addProperty(k, v) } })
          addProperty("testo", testo)
        })
    val banda = if (b.ok) b.banda else null
    val (cod, etichetta, codMsg) =
        when (banda) {
          "giallo" -> Triple("RIDUCI", "🟠 RIDUCI", "decisione_riduci")
          "rosso" -> Triple("RECUPERO", "🔴 RECUPERO", "decisione_recupero")
          else -> Triple("RISPETTA", "🟢 RISPETTA IL PIANO", "decisione_rispetta_piano")
        }
    val decisione = JsonObject().apply {
      addProperty("codice", cod)
      addProperty("etichetta", etichetta)
      if (banda == "verde") {
        addProperty("motivo", "banda verde: si segue il piano")
        addProperty("motivo_codice", "banda_segue_piano")
        add("motivo_valori", JsonObject().apply { addProperty("banda", "verde") })
      }
    }
    msg("decisione", codMsg, etichetta)
    if (banda == "verde") msg("motivo_decisione", "motivo_banda_segue_piano", "banda verde: si segue il piano", mapOf("banda" to "verde"))
    val bio = JsonObject().apply {
      addProperty("ok", b.ok)
      addProperty("banda", banda)
      b.rolling7Hrv?.let { addProperty("hrv_7gg", r1(it)) }
      b.baselineHrv?.let { addProperty("hrv_baseline", r1(it)) }
      b.pctVsBaseline?.let { addProperty("pct_vs_baseline", it) }
      b.zLn?.let { addProperty("z_ln", it) }
      b.normalRangeMs?.let { (lo, hi) -> add("range_ms", JsonArray().apply { add(lo); add(hi) }) }
      b.direzione7v7?.let { addProperty("direzione_7v7", it) }
      addProperty("gg_sotto_range", b.persistenzaGgSotto)
      addProperty("gg_ritardo", b.ggRitardo)
    }
    val (lo, hi) = b.normalRangeMs ?: (0.0 to 0.0)
    val z = b.zLn ?: 0.0
    when (banda) {
      "verde" -> {
        val t = "Procedi con la seduta pianificata (media 7gg dentro il normal range ${r1(lo)}-${r1(hi)}ms)."
        bio.addProperty("azione", t)
        bio.addProperty("azione_codice", "procedi")
        msg("biometria_azione", "biometria_procedi", t, mapOf("min" to "${r1(lo)}", "max" to "${r1(hi)}"))
      }
      "giallo" -> {
        val t = "Riduci intensita'/volume o converti la qualita' in Z1-Z2 (media 7gg sotto il normal range: $z SD, limite inferiore ${r1(lo)}ms)."
        bio.addProperty("azione", t)
        bio.addProperty("azione_codice", "riduci")
        msg("biometria_azione", "biometria_riduci", t, mapOf("z" to "$z", "limite" to "${r1(lo)}"))
      }
      "rosso" -> {
        val t = "Recupero attivo o riposo, specie se persistente su piu' giorni (media 7gg $z SD sotto baseline, oltre una deviazione standard)."
        bio.addProperty("azione", t)
        bio.addProperty("azione_codice", "recupero")
        msg("biometria_azione", "biometria_recupero", t, mapOf("z" to "$z"))
      }
    }
    b.direzione7v7?.let { d ->
      val c = "direzione_" + d.replace(' ', '_')
      bio.addProperty("direzione_7v7_codice", d.replace(' ', '_'))
      msg("direzione", c, d)
    }
    val formaJ = JsonObject()
    forma?.let { f ->
      formaJ.addProperty("ctl", f.ctl)
      formaJ.addProperty("atl", f.atl)
      formaJ.addProperty("tsb", f.tsb)
      formaJ.addProperty("form_pct", f.formPct)
      formaJ.addProperty("zona", f.zona)
      val zc = f.zona.lowercase().replace(' ', '_')
      formaJ.addProperty("zona_codice", zc)
      msg("zona", "zona_$zc", f.zona)
    }
    return JsonObject().apply {
      addProperty("v", 1)
      addProperty("data", oggi.toString())
      addProperty("tipo", "giornaliero")
      addProperty("esito", "fatto")
      add("sedute", JsonArray())
      addProperty("testo", "")
      addProperty("fase_codice", "senza_gara")
      add("decisione", decisione)
      add("biometria", bio)
      add("forma", formaJ)
      addProperty("oggi", sedute.joinToString("; "))
      add("non_scritte", JsonArray())
      add("messaggi", messaggi)
      add("notifiche_righe", JsonArray())
    }.toString()
  }

  private fun r1(x: Double) = Math.round(x * 10) / 10.0
}

/** Scrittura verso Intervals.icu rifiutata in modalita' demo (nessuna connessione aperta). */
class DemoNonConsentito : java.io.IOException("Non disponibile nei dati di esempio")
