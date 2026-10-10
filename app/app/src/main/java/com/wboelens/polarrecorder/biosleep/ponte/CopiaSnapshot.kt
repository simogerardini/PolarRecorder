package com.wboelens.polarrecorder.biosleep.ponte

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** Una finestra di 5 minuti (tabella windows). */
data class FinestraSorgente(
    val inizioMs: Long,
    val fc: Double?,
    val rmssd: Double?,
    val sdnn: Double?,
    val pnn50: Double?,
    val qualita: Double?,
)

/** Una notte come sta nel database, piu' cio' che la Parte 3 aggiunge (punteggio, tag). */
data class NotteSorgente(
    val inizioMs: Long,
    val fineMs: Long,
    val addormentamentoMs: Long? = null,
    val risveglioMs: Long? = null,
    val fcMin: Double? = null,
    val fc5MinBassi: Double? = null,
    val fcMedia: Double? = null,
    val rmssd: Double? = null,
    val sdnn: Double? = null,
    val qualitaPct: Double? = null,
    val ipnoInizioMs: Long? = null,
    val ipnogramma: String? = null,
    val sonnoMin: Int? = null,
    val profondoMin: Int? = null,
    val leggeroMin: Int? = null,
    val remMin: Int? = null,
    val vegliaMin: Int? = null,
    val metodoFasi: String? = null,
    val sensore: String? = null,
    val finestre: List<FinestraSorgente> = emptyList(),
    /** Buchi di RilevaInterruzioni [da, a) in ms; null se i battiti non sono piu' disponibili. */
    val buchi: List<Pair<Long, Long>>? = null,
    val interruzioni: List<Pair<Int, String?>> = emptyList(),
    val punteggioSonno: Int? = null,
    /** Punteggio.contributi della Parte 3, gia' in JSON: [{"nome":..,"punti":..,"peso":..}]. */
    val punteggioContributiJson: String? = null,
    val tag: List<String>? = null,
)

data class ComponenteEta(val chiave: String, val anni: Double?, val valore: Double? = null, val unita: String? = null)

data class EtaSorgente(
    val data: String,
    val eta: Double?,
    val anagrafica: Double?,
    val basso: Double?,
    val alto: Double?,
    val ritmo: Double?,
    val inTaratura: Boolean,
    val componenti: List<ComponenteEta>,
)

data class SonnoSorgente(
    val deficitH: Double? = null,
    val fabbisognoH: Double? = null,
    val cronotipo: String? = null,
    val lettoDa: String? = null,
    val lettoA: String? = null,
    val inizioMattino: String? = null,
)

data class EsitoComando(val id: String, val ok: Boolean, val errore: String? = null)

/** Tutto cio' che serve per una copia. I campi della Parte 3 restano null finche' non arrivano. */
data class DatiCopia(
    val generatoMs: Long,
    val zona: ZoneId,
    val versioneApp: String,
    val lingua: String,
    val atletaId: String,
    val atletaNome: String? = null,
    val notti: List<NotteSorgente>,
    val riepiloghiJson: List<String> = emptyList(),
    val profiloJson: String? = null,
    val prontezzaJson: String? = null,
    val tagGiorni: Map<String, List<String>>? = null,
    val tagSedute: Map<String, List<String>>? = null,
    val eta: EtaSorgente? = null,
    /** In alternativa a [eta]: l'eta' gia' pronta (cache del giorno). */
    val etaJson: String? = null,
    val sonno: SonnoSorgente? = null,
    /** Blocco sonno della Parte 3 (fabbisogno, deficit, notti, cronotipo): si aggiungono solo le fasce. */
    val sonnoJson: String? = null,
    val comandiApplicati: List<EsitoComando> = emptyList(),
)

/** Costruisce la copia (Snapshot v1 di src/types/snapshot.ts). Logica pura: testabile sulla JVM. */
object CopiaSnapshot {
  const val QUALITA_MIN = 80.0 // NOCTALIX_QUALITA_MIN del coach
  const val PASSO_IPNO_S = 30
  const val PASSO_SERIE_S = 300
  private val ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME

  fun iso(ms: Long, zona: ZoneId): String =
      ISO.format(Instant.ofEpochMilli(ms).atZone(zona).truncatedTo(ChronoUnit.SECONDS).toOffsetDateTime())

  /** Il mattino del risveglio, come IntervalsClient.morningDate. */
  fun dataMattino(fineMs: Long, zona: ZoneId): String = Instant.ofEpochMilli(fineMs).atZone(zona).toLocalDate().toString()

  fun valida(n: NotteSorgente): Boolean =
      (n.qualitaPct ?: 0.0) >= QUALITA_MIN && (n.sonnoMin ?: 0) > 0 && n.rmssd != null

  /** '-' sulle epoche il cui centro cade in un buco. */
  fun ipnogrammaConBuchi(fasi: String, inizioMs: Long, buchi: List<Pair<Long, Long>>): String {
    if (buchi.isEmpty()) return fasi
    val passo = PASSO_IPNO_S * 1000L
    val sb = StringBuilder(fasi)
    for (k in fasi.indices) {
      val centro = inizioMs + k * passo + passo / 2
      if (buchi.any { centro >= it.first && centro < it.second }) sb.setCharAt(k, '-')
    }
    return sb.toString()
  }

  /** Finestre riallineate a passo fisso dalla prima; null dove manca la finestra. */
  fun serie(finestre: List<FinestraSorgente>, zona: ZoneId): Map<String, Any?>? {
    if (finestre.isEmpty()) return null
    val ord = finestre.sortedBy { it.inizioMs }
    val t0 = ord.first().inizioMs
    val passo = PASSO_SERIE_S * 1000.0
    val n = ((ord.last().inizioMs - t0) / passo).roundToInt() + 1
    val fc = arrayOfNulls<Double>(n)
    val rmssd = arrayOfNulls<Double>(n)
    val sdnn = arrayOfNulls<Double>(n)
    val pnn50 = arrayOfNulls<Double>(n)
    val qual = arrayOfNulls<Double>(n)
    for (f in ord) {
      val k = ((f.inizioMs - t0) / passo).roundToInt()
      fc[k] = f.fc?.let { r1(it) }
      rmssd[k] = f.rmssd?.let { r1(it) }
      sdnn[k] = f.sdnn?.let { r1(it) }
      pnn50[k] = f.pnn50?.let { r1(it) }
      qual[k] = f.qualita?.let { r1(it) }
    }
    return linkedMapOf(
        "inizio" to iso(t0, zona), "passo_s" to PASSO_SERIE_S,
        "fc" to fc.toList(), "rmssd" to rmssd.toList(), "sdnn" to sdnn.toList(),
        "pnn50" to pnn50.toList(), "qualita" to qual.toList())
  }

  fun notte(n: NotteSorgente, zona: ZoneId): Map<String, Any?> {
    val fasi =
        if (listOf(n.profondoMin, n.leggeroMin, n.remMin, n.vegliaMin).all { it == null }) null
        else linkedMapOf("profondo" to n.profondoMin, "leggero" to n.leggeroMin, "rem" to n.remMin, "veglia" to n.vegliaMin)
    val ipno =
        if (n.ipnogramma.isNullOrEmpty() || n.ipnoInizioMs == null) null
        else linkedMapOf(
            "inizio" to iso(n.ipnoInizioMs, zona), "passo_s" to PASSO_IPNO_S,
            "fasi" to ipnogrammaConBuchi(n.ipnogramma, n.ipnoInizioMs, n.buchi.orEmpty()))
    return linkedMapOf(
        "data" to dataMattino(n.fineMs, zona),
        "inizio" to iso(n.inizioMs, zona),
        "fine" to iso(n.fineMs, zona),
        "addormentamento" to n.addormentamentoMs?.let { iso(it, zona) },
        "risveglio" to n.risveglioMs?.let { iso(it, zona) },
        "ore_registrazione" to r2((n.fineMs - n.inizioMs) / 3_600_000.0),
        "ore_sonno" to n.sonnoMin?.let { r2(it / 60.0) },
        "qualita_pct" to n.qualitaPct?.let { r1(it) },
        "valida" to valida(n),
        "rmssd" to n.rmssd?.let { r1(it) },
        "sdnn" to n.sdnn?.let { r1(it) },
        "fc_5min_bassi" to n.fc5MinBassi?.let { r1(it) },
        "fc_min" to n.fcMin?.let { r1(it) },
        "fc_media" to n.fcMedia?.let { r1(it) },
        "fasi_min" to fasi,
        "punteggio_sonno" to n.punteggioSonno,
        "punteggio_contributi" to n.punteggioContributiJson?.let { Json.Grezzo(it) },
        "ipnogramma" to ipno,
        "serie" to serie(n.finestre, zona),
        "metodo_fasi" to n.metodoFasi,
        "sensore" to n.sensore,
        "interruzioni" to n.interruzioni.takeIf { it.isNotEmpty() }?.map { (m, c) -> linkedMapOf("minuti" to m, "causa" to c) },
        "tag" to n.tag,
    )
  }

  fun eta(e: EtaSorgente): Map<String, Any?> =
      linkedMapOf(
          "data" to e.data,
          "eta" to e.eta?.let { r1(it) },
          "eta_anagrafica" to e.anagrafica?.let { r1(it) },
          "intervallo_80" to if (e.basso != null && e.alto != null) listOf(r1(e.basso), r1(e.alto)) else null,
          "ritmo" to e.ritmo?.let { r2(it) },
          "in_taratura" to e.inTaratura,
          "componenti" to e.componenti.map {
            linkedMapOf("chiave" to it.chiave, "anni" to it.anni?.let { a -> r1(a) }, "valore" to it.valore, "unita" to it.unita)
          },
      )

  fun sonno(s: SonnoSorgente): Map<String, Any?> =
      linkedMapOf(
          "deficit_h" to s.deficitH, "fabbisogno_h" to s.fabbisognoH, "cronotipo" to s.cronotipo,
          "fascia_letto" to if (s.lettoDa != null && s.lettoA != null) linkedMapOf("da" to s.lettoDa, "a" to s.lettoA) else null,
          "inizio_mattino" to s.inizioMattino)

  /** Il blocco della Parte 3 cosi' com'e', piu' fascia_letto e inizio_mattino (HabitLearner). */
  fun sonnoUnito(parte3: String?, mio: SonnoSorgente?): Map<String, Any?>? {
    @Suppress("UNCHECKED_CAST")
    val base = LinkedHashMap((parte3?.let { runCatching { Json.leggi(it) as? Map<String, Any?> }.getOrNull() }).orEmpty())
    mio?.let { sonno(it) }?.forEach { (k, v) -> if (v != null) base[k] = v }
    return base.takeIf { it.isNotEmpty() }
  }

  fun costruisci(d: DatiCopia): String {
    val notti = d.notti.sortedBy { it.fineMs }.map { notte(it, d.zona) }
    val mappa =
        linkedMapOf(
            "v" to 1,
            "generato" to iso(d.generatoMs, d.zona),
            "app" to linkedMapOf("versione" to d.versioneApp, "lingua" to d.lingua, "fuso" to d.zona.id),
            "atleta" to linkedMapOf("intervals_id" to d.atletaId, "nome" to d.atletaNome),
            "profilo" to d.profiloJson?.let { Json.Grezzo(it) },
            "prontezza" to d.prontezzaJson?.let { Json.Grezzo(it) },
            "notti" to notti,
            "riepiloghi" to d.riepiloghiJson.map { Json.Grezzo(it) },
            "tag_giorni" to d.tagGiorni,
            "tag_sedute" to d.tagSedute,
            "eta" to (d.eta?.let { eta(it) } ?: d.etaJson?.let { Json.Grezzo(it) }),
            "sonno" to sonnoUnito(d.sonnoJson, d.sonno),
            "comandi_applicati" to d.comandiApplicati.takeIf { it.isNotEmpty() }?.map {
              linkedMapOf("id" to it.id, "esito" to if (it.ok) "ok" else "rifiutato", "errore" to it.errore)
            },
        )
    return Json.scrivi(mappa)
  }

  private fun r1(v: Double) = Math.round(v * 10) / 10.0

  private fun r2(v: Double) = Math.round(v * 100) / 100.0
}
