package com.wboelens.polarrecorder.biosleep.web

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.wboelens.polarrecorder.biosleep.SleepDb
import com.wboelens.polarrecorder.biosleep.cache.CacheRepo
import com.wboelens.polarrecorder.biosleep.cache.Prontezza
import com.wboelens.polarrecorder.biosleep.cervello.DisponibilitaDate
import com.wboelens.polarrecorder.biosleep.cervello.ModalitaCoach
import com.wboelens.polarrecorder.biosleep.cervello.PianoCalendario
import com.wboelens.polarrecorder.biosleep.cervello.ProfiloRepo
import com.wboelens.polarrecorder.biosleep.intervals.CampiWellness
import com.wboelens.polarrecorder.biosleep.readiness.BioBaseline
import com.wboelens.polarrecorder.biosleep.readiness.Consiglio
import com.wboelens.polarrecorder.biosleep.riepilogo.Messaggi
import com.wboelens.polarrecorder.biosleep.riepilogo.TraduzioneMessaggi
import com.wboelens.polarrecorder.biosleep.sonno.NotteSonno
import com.wboelens.polarrecorder.biosleep.sonno.Punteggio
import com.wboelens.polarrecorder.biosleep.sonno.SonnoRepo
import com.wboelens.polarrecorder.biosleep.sonno.StatoSonno
import com.wboelens.polarrecorder.biosleep.tag.TagDb
import java.time.LocalDate

/**
 * Dati della Parte 3 per la copia cifrata del web (Parte 6, contratto src/types/snapshot.ts), gia'
 * pronti: la Parte 1 li copia nello snapshot senza ricalcoli. Nomi dei campi = contratto.
 * Da chiamare fuori dal main thread.
 */
data class DatiParte3(
    /** snapshot.profilo: ProfiloAtleta.json() + modalita + disponibilita_date */
    val profilo: JsonObject,
    /** snapshot.prontezza (null se non c'e' nessuna notte) */
    val prontezza: JsonObject?,
    /** snapshot.sonno: fabbisogno, deficit, cronotipo (fascia_letto e inizio_mattino: Parte 1) */
    val sonno: JsonObject,
    /** Per notte (data del mattino): {"punteggio_sonno": n, "punteggio_contributi": [...]} da unire a Notte */
    val punteggi: Map<String, JsonObject>,
    /** snapshot.tag_giorni */
    val tagGiorni: JsonObject,
    /** snapshot.tag_sedute (chiave: id attivita' Intervals.icu) */
    val tagSedute: JsonObject,
) {
  companion object {
    /** Finestra dei tag e dei punteggi: la stessa della copia (le notti oltre restano senza). */
    const val GIORNI = 120

    fun perCopia(context: Context, oggi: LocalDate = LocalDate.now()): DatiParte3 {
      val ctx = context.applicationContext
      val notti = runCatching { SonnoRepo.notti(SleepDb.get(ctx).listNights()) }.getOrDefault(emptyList())
      val stato = SonnoRepo.calcola(notti, oggi)
      val tag = runCatching { TagDb.get(ctx) }.getOrNull()
      return DatiParte3(
          profilo = profilo(ctx),
          prontezza = runCatching { prontezza(ctx, oggi) }.getOrNull(),
          sonno = sonno(stato),
          punteggi = punteggi(notti, oggi.minusDays(GIORNI.toLong()), oggi),
          tagGiorni = tag?.let { mappa(it.giorni(oggi.minusDays(GIORNI.toLong()), oggi)) } ?: JsonObject(),
          tagSedute = tag?.let { mappa(it.sedute(oggi.minusDays(GIORNI.toLong()))) } ?: JsonObject(),
      )
    }

    private fun profilo(ctx: Context): JsonObject =
        ProfiloRepo.effettivo(ctx).json().deepCopy().apply {
          addProperty("modalita", if (ModalitaCoach.attivo(ctx)) "coach" else "solo_biometria")
          DisponibilitaDate.perCervello(ctx)?.takeIf { it.isNotEmpty() }?.let { add("disponibilita_date", PianoCalendario.json(it)) }
        }

    private fun prontezza(ctx: Context, oggi: LocalDate): JsonObject {
      val repo = CacheRepo.get(ctx)
      val o = JsonObject()
      o.addProperty("data", oggi.toString())
      when (val p = repo.prontezza(oggi)) {
        is Prontezza.Calibrazione -> {
          o.add("biometria", JsonObject().apply { addProperty("ok", false) })
          o.add("calibrazione", JsonObject().apply { addProperty("notti", p.notti); addProperty("servono", p.servono) })
        }
        is Prontezza.Banda -> {
          o.add("biometria", biometria(p.baseline))
          if (!ModalitaCoach.attivo(ctx)) {
            val sonno = repo.wellness(oggi, oggi).lastOrNull()?.get(CampiWellness.F_SLEEP_HOURS)?.takeIf { !it.isJsonNull }?.asDouble
            Consiglio.calcola(p.baseline, sonno)?.let { e ->
              o.addProperty("consiglio", Consiglio.codice(e.livello))
              if (e.codici.isNotEmpty()) o.add("motivi_codici", JsonArray().apply { e.codici.forEach { add(it) } })
            }
          }
        }
      }
      val fh = repo.forma(oggi)
      fh.oggi?.let { f ->
        o.add("forma", JsonObject().apply {
          addProperty("ctl", f.ctl)
          addProperty("atl", f.atl)
          addProperty("tsb", f.tsb)
          addProperty("zona", f.zona)
          addProperty("colore", f.colore)
          addProperty("giorni_in_zona", f.giorniInZona)
          addProperty("form_pct", f.formPct)
        })
      }
      // Ultimi 42 giorni per frecce e grafico TSB del web (stessa serie della riga Forma di Oggi)
      val da = oggi.minusDays(41).toString()
      val a = oggi.toString()
      val serie = JsonArray()
      fh.serie.map { it.giorno.take(10) to it }
          .filter { (g, r) -> g in da..a && r.ctl != null && r.atl != null }
          .sortedBy { it.first }
          .forEach { (g, r) ->
            serie.add(JsonObject().apply {
              addProperty("data", g)
              addProperty("ctl", Math.round(r.ctl!! * 10) / 10.0)
              addProperty("atl", Math.round(r.atl!! * 10) / 10.0)
            })
          }
      if (serie.size() > 0) o.add("forma_serie", serie)
      return o
    }

    /** BioBaseline -> blocco "biometria" con i nomi del riepilogo del cervello (riepilogo.ts). */
    fun biometria(b: BioBaseline): JsonObject =
        JsonObject().apply {
          addProperty("ok", b.ok)
          b.banda?.let { addProperty("banda", it) }
          b.rolling7Hrv?.let { addProperty("hrv_7gg", it) }
          b.baselineHrv?.let { addProperty("hrv_baseline", it) }
          b.pctVsBaseline?.let { addProperty("pct_vs_baseline", it) }
          b.zLn?.let { addProperty("z_ln", it) }
          b.normalRangeMs?.let { (lo, hi) -> add("range_ms", JsonArray().apply { add(lo); add(hi) }) }
          b.direzione7v7?.let { addProperty("direzione_7v7", it) }
          addProperty("gg_sotto_range", b.persistenzaGgSotto)
          b.fcRiposo?.let { f ->
            addProperty("fc_7gg", f.rolling7)
            f.baseline?.let { addProperty("fc_baseline", it) }
            f.delta?.let { addProperty("fc_delta", it) }
            addProperty("fc_allarme", f.allarme)
          }
          addProperty("gg_ritardo", b.ggRitardo)
        }

    fun sonno(s: StatoSonno): JsonObject =
        JsonObject().apply {
          addProperty("fabbisogno_h", s.fabbisogno.ore)
          addProperty("fabbisogno_appreso", s.fabbisogno.appreso)
          addProperty("deficit_h", s.deficit.ore)
          addProperty("deficit_livello", s.deficit.livello.name.lowercase())
          addProperty("notti", s.deficit.notti)
          s.cronotipo?.let { addProperty("cronotipo", it.tipo.name.lowercase()) }
        }

    /** Punteggio di ogni notte con risveglio in [da, a], calcolato con le notti fino a quel giorno. */
    fun punteggi(notti: List<NotteSonno>, da: LocalDate, a: LocalDate): Map<String, JsonObject> =
        notti.filter { it.giorno in da..a }.associate { n ->
          val p = SonnoRepo.calcola(notti.filter { it.giorno <= n.giorno }, n.giorno).punteggio
          n.giorno.toString() to (p?.let { punteggio(it) } ?: JsonObject())
        }.filterValues { it.size() > 0 }

    fun punteggio(p: Punteggio): JsonObject =
        JsonObject().apply {
          addProperty("punteggio_sonno", p.totale)
          add("punteggio_contributi", JsonArray().apply {
            for (c in p.contributi) add(JsonObject().apply { addProperty("nome", c.nome); addProperty("punti", c.punti); addProperty("peso", c.peso) })
          })
        }

    private fun mappa(m: Map<String, Set<String>>): JsonObject =
        JsonObject().apply { for ((k, v) in m) add(k, JsonArray().apply { v.forEach { add(it) } }) }

    /**
     * Copia di un riepilogo con "testo_locale" (lingua dell'app) in ogni messaggio. I messaggi con
     * codice si traducono per codice; quelli senza restano uguali al testo.
     */
    fun conTestiLocali(context: Context, riepilogo: JsonObject): JsonObject {
      val o = riepilogo.deepCopy()
      Messaggi.registra(Messaggi.leggi(o.get("messaggi")?.takeIf { it.isJsonArray }?.asJsonArray))
      o.get("messaggi")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { e ->
        val m = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
        val t = m.get("testo")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
        m.addProperty("testo_locale", TraduzioneMessaggi.testo(context, t))
      }
      return o
    }
  }
}
