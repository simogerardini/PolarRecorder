package com.wboelens.polarrecorder.biosleep.training

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wboelens.polarrecorder.biosleep.readiness.PyJson
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/** Disciplina, ricavata dal "type" di Intervals.icu (Ride, VirtualRide, Run, Swim, ...). */
enum class Sport(val etichetta: String) {
  NUOTO("Nuoto"),
  BICI("Bici"),
  CORSA("Corsa"),
  PALESTRA("Palestra"),
  MULTISPORT("Multisport"),
  TRANSIZIONE("Transizione"),
  ALTRO("Altro");

  companion object {
    fun da(tipo: String?): Sport {
      val t = tipo ?: return ALTRO
      return when {
        t.contains("Swim") -> NUOTO
        t.contains("Ride") -> BICI
        t.contains("Run") -> CORSA
        t == "WeightTraining" -> PALESTRA
        t == "Brick" -> MULTISPORT
        t == "Transition" -> TRANSIZIONE
        else -> ALTRO
      }
    }
  }
}

/** Una parte di una seduta su Garmin Connect (es. bici 60', transizione 2', corsa 30'). */
data class ParteGarmin(val sport: Sport, val minuti: Int)

/**
 * Una seduta pianificata dal coach: evento WORKOUT o gara, oppure NOTE "specchio" di una seduta
 * che il coach ha messo su Garmin Connect (multisport), riconosciuta da [specchio].
 */
data class EventoCal(
    val id: String,
    val data: LocalDate,
    val categoria: String,
    val tipo: String?,
    val nome: String,
    val durataS: Int?,
    val tss: Int?,
    val distanzaM: Double?,
    val descrizione: String?,
    val attivitaId: String?,
    val passi: JsonArray?,
    val specchio: Boolean = false,
    val parti: List<ParteGarmin> = emptyList(),
    val externalId: String? = null,
) {
  val sport get() = Sport.da(tipo)

  /** NOTE scritta dal coach (riepilogo, registri): mai mostrata come nota dell'atleta. */
  val delCoach get() = externalId?.startsWith("coach:") == true

  val nota get() = categoria == "NOTE" && !specchio && !delCoach
}

/** Una seduta svolta (attivita' arrivata da Garmin Connect). */
data class AttivitaCal(
    val id: String,
    val data: LocalDate,
    val tipo: String?,
    val nome: String,
    val durataS: Int?,
    val tss: Int?,
    val distanzaM: Double?,
    val eventoId: String?,
    val compliance: Double?,
) {
  val sport get() = Sport.da(tipo)
}

/** Esito di una seduta pianificata, come i colori del calendario di TrainingPeaks. */
enum class Esito { SVOLTA, NON_SVOLTA, DA_FARE, PIANIFICATA }

/** Quanto e' stato svolto di una seduta pianificata (somma delle attivita' abbinate). */
data class Consuntivo(val durataS: Int?, val tss: Int?, val distanzaM: Double?, val compliance: Double?)

/**
 * svolte: di solito una sola attivita'; per il multisport le sue parti (bici, transizione, corsa),
 * che su Intervals.icu arrivano come attivita' separate.
 */
data class SedutaPianificata(val evento: EventoCal, val svolte: List<AttivitaCal>, val esito: Esito) {
  val svolta: AttivitaCal?
    get() = svolte.firstOrNull()

  val consuntivo: Consuntivo?
    get() {
      if (svolte.isEmpty()) return null
      val durata = svolte.sumOf { it.durataS ?: 0 }
      val tss = svolte.mapNotNull { it.tss }
      val distanze = svolte.mapNotNull { it.distanzaM }
      // Aderenza: quella calcolata da Intervals.icu per l'abbinamento ufficiale; per le sedute
      // specchio (una NOTE non si abbina) il rapporto tra durata svolta e pianificata.
      val aderenza =
          if (!evento.specchio && svolte.size == 1) svolte[0].compliance
          else evento.durataS?.takeIf { it > 0 && durata > 0 }?.let { durata * 100.0 / it }
      return Consuntivo(
          durata.takeIf { it > 0 }, tss.takeIf { it.isNotEmpty() }?.sum(), distanze.takeIf { it.isNotEmpty() }?.sum(), aderenza)
    }
}

data class GiornoCal(
    val data: LocalDate,
    val pianificate: List<SedutaPianificata>,
    val nonPianificate: List<AttivitaCal>,
    val note: List<EventoCal>,
)

enum class Metrica(val etichetta: String) {
  DURATA("Durata"),
  DISTANZA("Distanza"),
  TSS("TSS"),
}

data class BarraGiorno(val data: LocalDate, val svolto: Double, val pianificato: Double)

/** Lettura degli oggetti JSON della cache e logica del calendario. Nessuna dipendenza da Android. */
object Allenamenti {

  private fun num(e: JsonElement?) = PyJson.num(e)?.v

  private fun data(o: JsonObject): LocalDate? {
    val testo = PyJson.str(o.get("start_date_local"))?.take(10) ?: return null
    return try {
      LocalDate.parse(testo)
    } catch (e: java.time.format.DateTimeParseException) {
      null
    }
  }

  /** external_id delle NOTE specchio scritte dal coach (allinea_specchi_garmin). */
  const val PREFISSO_SPECCHIO = "coach:SpecchioGarmin:"

  // Stessa espressione del test del coach (test_specchio_garmin): JSON su una riga dopo il tag.
  private val TAG_SEDUTA = Regex("""\[\[seduta_garmin:(\{.*\})\]\]""")

  /** Il JSON del tag [[seduta_garmin:{...}]]: tipo, nome, durata_min, parti [{sport, min}]. */
  fun sedutaGarmin(descrizione: String?): JsonObject? {
    val testo = TAG_SEDUTA.find(descrizione ?: return null)?.groupValues?.get(1) ?: return null
    return runCatching { JsonParser.parseString(testo).asJsonObject }.getOrNull()
  }

  fun evento(o: JsonObject): EventoCal? {
    val id = PyJson.str(o.get("id")) ?: return null
    if (PyJson.str(o.get("category")) == "NOTE" &&
        PyJson.str(o.get("external_id"))?.startsWith(PREFISSO_SPECCHIO) == true) {
      specchio(o, id)?.let { return it } // tag illeggibile: resta una nota normale
    }
    val doc = o.get("workout_doc")?.takeIf { it.isJsonObject }?.asJsonObject
    return EventoCal(
        id = id,
        data = data(o) ?: return null,
        categoria = PyJson.str(o.get("category")) ?: "",
        tipo = PyJson.str(o.get("type")),
        nome = PyJson.str(o.get("name"))?.takeIf { it.isNotBlank() } ?: "Seduta",
        durataS = num(o.get("moving_time"))?.roundToInt(),
        tss = num(o.get("icu_training_load"))?.roundToInt(),
        distanzaM = num(o.get("distance"))?.takeIf { it > 0 },
        descrizione = PyJson.str(o.get("description")),
        attivitaId = PyJson.str(o.get("paired_activity_id")),
        passi = doc?.get("steps")?.takeIf { it.isJsonArray }?.asJsonArray,
        externalId = PyJson.str(o.get("external_id")),
    )
  }

  private fun specchio(o: JsonObject, id: String): EventoCal? {
    val g = sedutaGarmin(PyJson.str(o.get("description"))) ?: return null
    val parti =
        g.get("parti")?.takeIf { it.isJsonArray }?.asJsonArray.orEmpty().mapNotNull { el ->
          val p = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
          val min = num(p.get("min"))?.roundToInt() ?: return@mapNotNull null
          ParteGarmin(Sport.da(PyJson.str(p.get("sport"))), min)
        }
    val minuti = num(g.get("durata_min"))?.roundToInt() ?: parti.sumOf { it.minuti }.takeIf { it > 0 }
    return EventoCal(
        id = id,
        data = data(o) ?: return null,
        categoria = "NOTE",
        tipo = PyJson.str(g.get("tipo")),
        nome = PyJson.str(g.get("nome"))?.takeIf { it.isNotBlank() } ?: PyJson.str(o.get("name")) ?: "Seduta su Garmin",
        durataS = minuti?.let { it * 60 },
        tss = num(g.get("tss"))?.roundToInt(), // oggi il coach non lo scrive: se lo aggiunge, l'app lo usa
        distanzaM = null,
        descrizione = null,
        attivitaId = null,
        passi = null,
        specchio = true,
        parti = parti,
        externalId = PyJson.str(o.get("external_id")),
    )
  }

  private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()

  fun attivita(o: JsonObject): AttivitaCal? {
    val id = PyJson.str(o.get("id")) ?: return null
    return AttivitaCal(
        id = id,
        data = data(o) ?: return null,
        tipo = PyJson.str(o.get("type")),
        nome = PyJson.str(o.get("name"))?.takeIf { it.isNotBlank() } ?: "Attivita'",
        durataS = num(o.get("moving_time"))?.roundToInt(),
        tss = num(o.get("icu_training_load"))?.roundToInt(),
        distanzaM = num(o.get("distance"))?.takeIf { it > 0 },
        eventoId = PyJson.str(o.get("paired_event_id")),
        compliance = num(o.get("compliance")),
    )
  }

  private val CATEGORIE_SEDUTA = setOf("WORKOUT", "RACE_A", "RACE_B", "RACE_C")

  /** WORKOUT, gare e NOTE specchio: tutto cio' che conta come seduta pianificata. */
  fun seduta(e: EventoCal) = e.specchio || e.categoria in CATEGORIE_SEDUTA

  /**
   * Un giorno per ogni data in [da, a]. L'abbinamento pianificato-svolto usa gli id che
   * Intervals.icu mette su entrambi i lati (paired_activity_id / paired_event_id).
   * Le NOTE specchio non hanno abbinamento su Intervals.icu: prendono, dopo gli abbinamenti
   * ufficiali, le attivita' libere dello stesso giorno degli sport delle loro parti
   * (per il multisport anche la transizione).
   */
  fun giorni(eventi: List<EventoCal>, attivita: List<AttivitaCal>, da: LocalDate, a: LocalDate, oggi: LocalDate):
      List<GiornoCal> {
    val attivitaPerId = attivita.associateBy { it.id }
    val perEvento = attivita.filter { it.eventoId != null }.associateBy { it.eventoId!! }
    val usate = HashSet<String>()
    val pianificatePerGiorno = HashMap<LocalDate, MutableList<SedutaPianificata>>()
    val notePerGiorno = HashMap<LocalDate, MutableList<EventoCal>>()
    fun esito(e: EventoCal, svolte: List<AttivitaCal>) =
        when {
          svolte.isNotEmpty() -> Esito.SVOLTA
          e.data < oggi -> Esito.NON_SVOLTA
          e.data == oggi -> Esito.DA_FARE
          else -> Esito.PIANIFICATA
        }
    for (e in eventi) {
      if (e.nota) {
        notePerGiorno.getOrPut(e.data) { ArrayList() }.add(e)
        continue
      }
      if (!seduta(e) || e.specchio) continue
      val svolte = listOfNotNull(e.attivitaId?.let { attivitaPerId[it] } ?: perEvento[e.id])
      svolte.forEach { usate.add(it.id) }
      pianificatePerGiorno.getOrPut(e.data) { ArrayList() }.add(SedutaPianificata(e, svolte, esito(e, svolte)))
    }
    for (e in eventi.filter { it.specchio }) {
      val sport = e.parti.map { it.sport }.toMutableSet()
      if (sport.isEmpty()) sport.add(e.sport)
      if (e.sport == Sport.MULTISPORT) sport.add(Sport.TRANSIZIONE)
      val svolte =
          attivita.filter { it.data == e.data && it.id !in usate && it.sport in sport }
              .sortedBy { it.id }
      svolte.forEach { usate.add(it.id) }
      pianificatePerGiorno.getOrPut(e.data) { ArrayList() }.add(SedutaPianificata(e, svolte, esito(e, svolte)))
    }
    val libere = attivita.filter { it.id !in usate }.groupBy { it.data }
    val out = ArrayList<GiornoCal>()
    var d = da
    while (!d.isAfter(a)) {
      out.add(
          GiornoCal(
              d, pianificatePerGiorno[d].orEmpty(), libere[d].orEmpty().sortedBy { it.id }, notePerGiorno[d].orEmpty()))
      d = d.plusDays(1)
    }
    return out
  }

  /** Lunedi' della settimana che contiene il giorno dato. */
  fun lunedi(giorno: LocalDate): LocalDate = giorno.minusDays((giorno.dayOfWeek.value - 1).toLong())

  /** Sette barre (lun-dom): svolto = attivita' del giorno, pianificato = sedute del coach. */
  fun settimana(eventi: List<EventoCal>, attivita: List<AttivitaCal>, lunedi: LocalDate, m: Metrica): List<BarraGiorno> {
    fun valore(durataS: Int?, distanzaM: Double?, tss: Int?): Double =
        when (m) {
          Metrica.DURATA -> (durataS ?: 0) / 3600.0
          Metrica.DISTANZA -> (distanzaM ?: 0.0) / 1000.0
          Metrica.TSS -> (tss ?: 0).toDouble()
        }
    return (0L until 7L).map { i ->
      val d = lunedi.plusDays(i)
      BarraGiorno(
          d,
          svolto = attivita.filter { it.data == d }.sumOf { valore(it.durataS, it.distanzaM, it.tss) },
          pianificato =
              eventi.filter { it.data == d && seduta(it) }
                  .sumOf { valore(it.durataS, it.distanzaM, it.tss) })
    }
  }

  /** Colore di aderenza come TrainingPeaks: 80-120% verde, 50-150% giallo, oltre arancio. */
  enum class Aderenza { VERDE, GIALLA, ARANCIO, NESSUNA }

  fun aderenza(compliance: Double?): Aderenza =
      when {
        compliance == null || compliance <= 0 -> Aderenza.NESSUNA
        compliance in 80.0..120.0 -> Aderenza.VERDE
        compliance in 50.0..150.0 -> Aderenza.GIALLA
        else -> Aderenza.ARANCIO
      }
}

/** Formati italiani per durate, distanze e numeri. */
object Formato {
  fun durata(s: Int?): String {
    if (s == null || s <= 0) return "—"
    val min = (s + 30) / 60
    return if (min < 60) "$min'" else "${min / 60}h${"%02d".format(min % 60)}"
  }

  fun distanza(m: Double?): String =
      when {
        m == null || m <= 0 -> "—"
        m < 1000 -> "${m.roundToInt()} m"
        else -> String.format(Locale.ITALY, "%.1f km", m / 1000)
      }

  fun decimale(v: Double, cifre: Int = 1): String = String.format(Locale.ITALY, "%.${cifre}f", v)

  fun conSegno(v: Double, cifre: Int = 1): String = (if (v > 0) "+" else "") + decimale(v, cifre)

  fun metrica(v: Double, m: Metrica): String =
      when (m) {
        Metrica.DURATA -> durata((v * 3600).roundToInt())
        Metrica.DISTANZA -> if (v <= 0) "—" else String.format(Locale.ITALY, "%.1f km", v)
        Metrica.TSS -> v.roundToInt().toString()
      }
}
