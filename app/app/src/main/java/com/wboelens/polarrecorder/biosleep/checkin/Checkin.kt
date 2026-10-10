package com.wboelens.polarrecorder.biosleep.checkin

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wboelens.polarrecorder.biosleep.cache.CacheRepo
import com.wboelens.polarrecorder.biosleep.training.Allenamenti
import com.wboelens.polarrecorder.biosleep.training.EventoCal
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * Check-in dopo la seduta (cervello 2026.10.10-degradato): per ogni seduta del coach passata
 * l'utente dice se l'ha fatta, modificata o saltata, con la fatica (RPE 1-10), i minuti effettivi
 * (se modificata) e i tag di seduta. Serve al cervello quando Intervals.icu non legge le attivita'
 * (modalita' degradata); si chiede solo in quel caso, o se l'utente lo attiva in Impostazioni.
 * Al cervello: "checkin": {"<external_id>": {"stato", "rpe", "minuti", "data", "tag"}}, 28 giorni.
 */
object Checkin {
  const val FATTA = "fatta"
  const val MODIFICATA = "modificata"
  const val SALTATA = "saltata"

  private const val PREF = "noctalix_checkin"
  private const val K_RISPOSTE = "risposte"
  private const val K_SEMPRE = "chiedi_sempre"
  private const val K_DEG_DATA = "degradato_data"
  private const val K_DEG = "degradato"
  const val GIORNI_CERVELLO = 28L
  const val GIORNI_DOMANDA = 7L
  /** La seduta di oggi si chiede da mezzogiorno. */
  val ORA_OGGI: LocalTime = LocalTime.NOON

  data class Risposta(val stato: String, val rpe: Int?, val minuti: Int?, val data: String, val tag: Set<String> = emptySet())

  private fun pref(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

  // --- quando chiederlo -------------------------------------------------------------------------

  /** Interruttore "Chiedi la fatica dopo ogni seduta" (spento di default). */
  fun sempre(c: Context) = pref(c).getBoolean(K_SEMPRE, false)

  fun impostaSempre(c: Context, v: Boolean) = pref(c).edit().putBoolean(K_SEMPRE, v).apply()

  /** Modalita' degradata secondo l'ultimo riepilogo del cervello (avviso attivita_non_leggibili). */
  fun degradato(c: Context) = pref(c).getBoolean(K_DEG, false)

  fun richiesto(c: Context) = sempre(c) || degradato(c)

  /** Dopo ogni run: legge dal riepilogo appena scritto se il cervello e' in modalita' degradata. */
  fun notaRiepilogo(c: Context, file: File) {
    val j = runCatching { JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject }.getOrNull() ?: return
    val data = j.get("data")?.takeIf { it.isJsonPrimitive }?.asString ?: return
    if (data < (pref(c).getString(K_DEG_DATA, "") ?: "")) return // un riepilogo vecchio non decide
    pref(c).edit().putString(K_DEG_DATA, data).putBoolean(K_DEG, degradatoNel(j)).apply()
  }

  fun degradatoNel(riepilogo: JsonObject): Boolean =
      riepilogo.get("messaggi")?.takeIf { it.isJsonArray }?.asJsonArray?.any { e ->
        e.isJsonObject && e.asJsonObject.get("codice")?.takeIf { it.isJsonPrimitive }?.asString == "attivita_non_leggibili"
      } == true

  // --- risposte -------------------------------------------------------------------------------

  fun risposte(c: Context): Map<String, Risposta> = leggi(pref(c).getString(K_RISPOSTE, null))

  fun salva(c: Context, externalId: String, r: Risposta) {
    val tutte = risposte(c).toMutableMap()
    tutte[externalId] = r
    val limite = LocalDate.now().minusDays(GIORNI_CERVELLO * 2).toString()
    pref(c).edit().putString(K_RISPOSTE, scrivi(tutte.filterValues { it.data >= limite }).toString()).apply()
  }

  /** Blocco "checkin" del configJson: ultimi 28 giorni; null se vuoto. */
  fun perCervello(c: Context, oggi: LocalDate = LocalDate.now()): JsonObject? =
      scrivi(risposte(c).filterValues { it.data >= oggi.minusDays(GIORNI_CERVELLO).toString() }).takeIf { it.size() > 0 }

  /** Sedute del coach da chiedere: passate (oggi da mezzogiorno), ultimi 7 giorni, senza risposta. */
  fun daChiedere(c: Context, oggi: LocalDate = LocalDate.now(), ora: LocalTime = LocalTime.now()): List<EventoCal> {
    val eventi = CacheRepo.get(c).eventi(oggi.minusDays(GIORNI_DOMANDA), oggi).mapNotNull { Allenamenti.evento(it) }
    return daChiedere(eventi, risposte(c).keys, oggi, ora)
  }

  fun daChiedere(eventi: List<EventoCal>, risposte: Set<String>, oggi: LocalDate, ora: LocalTime): List<EventoCal> =
      eventi
          .filter { it.categoria == "WORKOUT" && it.externalId?.startsWith("sw:") == true && it.externalId !in risposte }
          .filter { it.data < oggi || (it.data == oggi && !ora.isBefore(ORA_OGGI)) }
          .filter { it.data >= oggi.minusDays(GIORNI_DOMANDA) }
          .sortedWith(compareBy({ it.data }, { it.nome }))

  // --- formato --------------------------------------------------------------------------------

  fun scrivi(m: Map<String, Risposta>): JsonObject =
      JsonObject().apply {
        for ((id, r) in m) {
          add(id, JsonObject().apply {
            addProperty("stato", r.stato)
            r.rpe?.let { addProperty("rpe", it) }
            r.minuti?.let { addProperty("minuti", it) }
            addProperty("data", r.data)
            if (r.tag.isNotEmpty()) add("tag", JsonArray().apply { r.tag.sorted().forEach { add(it) } })
          })
        }
      }

  fun leggi(json: String?): Map<String, Risposta> {
    val o = runCatching { JsonParser.parseString(json ?: return emptyMap()).asJsonObject }.getOrNull() ?: return emptyMap()
    return o.entrySet().mapNotNull { (id, v) ->
      val r = v.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
      fun s(k: String) = r.get(k)?.takeIf { it.isJsonPrimitive }?.asString
      fun i(k: String) = r.get(k)?.takeIf { it.isJsonPrimitive }?.asInt
      val tag = r.get("tag")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString }?.toSet().orEmpty()
      id to Risposta(s("stato") ?: return@mapNotNull null, i("rpe"), i("minuti"), s("data") ?: return@mapNotNull null, tag)
    }.toMap()
  }
}
