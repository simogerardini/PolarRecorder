package com.wboelens.polarrecorder.biosleep.riepilogo

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.wboelens.polarrecorder.biosleep.lingua.TestiSistema
import com.wboelens.polarrecorder.biosleep.ui.allenamento.DateIt
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 13d ter: notifiche del coach ricostruite riga per riga nella lingua dell'app. Il cervello manda
 * accanto a ogni notifica le sue righe codificate (notifiche_righe): qui ogni riga diventa testo
 * con le risorse nr_*, ns_* (sedute), ng_* (giorni), nd_* (distanze). Se una sola riga non e'
 * traducibile (codice o chiave sconosciuti) il risultato e' null e l'app usa la sintesi.
 */
object RigheNotifica {
  private val registro = ConcurrentHashMap<String, JsonArray>()

  /** Codici di riga e di azione che l'app sa comporre (RigheNotificaTest li confronta col cervello). */
  val CODICI =
      setOf("nr_intestazione_continuo", "nr_intestazione_fase", "nr_intestazione_taper", "nr_intestazione_gara",
          "nr_intestazione_recupero", "nr_distanza", "nr_volume", "nr_ripartizione", "nr_alta_intensita", "nr_banda",
          "nr_hrv", "nr_forma", "nr_motivi", "nr_riposo_consigliato", "nr_vuota", "nr_giorno_riposo", "nr_giorno_sedute",
          "nr_rimodulazione", "nr_az_brick_alleggerito", "nr_az_rimossa", "nr_az_resa_aerobica",
          "nr_az_rimossa_disponibilita", "nr_az_accorciata_disponibilita", "nr_az_companion_prevenzione")

  /** Dal riepilogo: le notifiche (messaggi di tipo "notifica", in ordine) e le loro righe. */
  fun registra(riepilogo: JsonObject) {
    val righe = riepilogo.get("notifiche_righe")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
    val testi =
        riepilogo.get("messaggi")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e ->
          val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
          if (o.get("tipo")?.asString == "notifica") o.get("testo")?.asString else null
        }.orEmpty()
    for ((i, t) in testi.withIndex()) {
      val r = righe.get(i).takeIf { i < righe.size() && it.isJsonArray }?.asJsonArray ?: continue
      registro[t.trim()] = r
    }
  }

  fun per(testo: String): JsonArray? = registro[testo.trim()]

  /** Nella lingua dell'app; null se la notifica non ha righe o una riga non si traduce. */
  fun testo(context: Context, testo: String): String? {
    val righe = per(testo) ?: return null
    val c = TestiSistema.localizzato(context)
    val res = c.resources
    val stringa = { nome: String, args: Array<String> ->
      val id = res.getIdentifier(nome, "string", c.packageName)
      if (id == 0) null else res.getString(id, *args)
    }
    return componi(righe, stringa, { DateIt.breve(LocalDate.parse(it)) }, { TraduzioneMessaggi.numero(it) }, c.resources.configuration.locales.get(0) ?: Locale.getDefault())
  }

  // --- traduzione pura (testabile sulla JVM) ---------------------------------------------------

  fun componi(
      righe: JsonArray,
      stringa: (String, Array<String>) -> String?,
      data: (String) -> String,
      numero: (String) -> String,
      locale: Locale,
      oggi: LocalDate = LocalDate.now(),
  ): String? {
    val out = mutableListOf<String>()
    for (e in righe) {
      val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return null
      out += riga(o, stringa, data, numero, locale, oggi) ?: return null
    }
    return out.joinToString("\n")
  }

  private fun JsonObject.s(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString

  private fun riga(
      o: JsonObject,
      stringa: (String, Array<String>) -> String?,
      data: (String) -> String,
      numero: (String) -> String,
      locale: Locale,
      oggi: LocalDate,
  ): String? {
    val codice = o.s("codice") ?: return null
    val v = o.get("valori")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
    fun s(nome: String, vararg a: String) = stringa(nome, arrayOf(*a))
    fun x(k: String) = v.s(k)
    fun fase(f: String?) = f?.let { s("msg_fase_$it") }
    fun banda(b: String?) = b?.let { s("voc_$it") }?.uppercase(locale)
    fun giorno(g: String?) = g?.let { s("ng_$it") }
    fun seduta(chiave: String?) = chiave?.let { s("ns_$it") }
    fun messaggio(m: JsonElement?): String? {
      val mm = m?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
      val msg = Messaggi.leggi(JsonArray().apply { add(mm) }).firstOrNull() ?: return null
      if (msg.codice == Messaggi.NON_CODIFICATO || (msg.codice != Messaggi.MULTIPLO && msg.codice !in Messaggi.ORDINE)) return null
      return Messaggi.traduci(msg, stringa, data, numero)
    }
    /** "05/01" -> data localizzata, con l'anno piu' vicino a oggi */
    fun ggMm(t: String?): String? {
      val p = t?.split("/")?.takeIf { it.size == 2 } ?: return null
      val g = p[0].toIntOrNull() ?: return null
      val m = p[1].toIntOrNull() ?: return null
      val d = listOf(oggi.year - 1, oggi.year, oggi.year + 1).mapNotNull { runCatching { LocalDate.of(it, m, g) }.getOrNull() }
          .minByOrNull { kotlin.math.abs(it.toEpochDay() - oggi.toEpochDay()) } ?: return null
      return data(d.toString())
    }
    return when (codice) {
      "nr_intestazione_continuo" ->
          if (x("tipo_settimana") == "scarico") s("nr_int_continuo_scarico", x("blocco") ?: return null)
          else s("nr_int_continuo_carico", x("blocco") ?: return null, x("carico") ?: return null)
      "nr_intestazione_fase" ->
          s("nr_int_fase", fase(x("fase")) ?: return null, x("n") ?: return null, x("tot") ?: return null,
              if (x("scarico") == "si") s("nr_frammento_scarico") ?: return null else "",
              x("settimane") ?: return null, x("gara") ?: "")
      "nr_intestazione_taper" -> s("nr_int_taper", x("settimane") ?: return null)
      "nr_intestazione_gara" -> s("nr_int_gara", x("gara") ?: "")
      "nr_intestazione_recupero" -> s("nr_int_recupero", x("categoria") ?: "", x("gara") ?: "")
      "nr_distanza" ->
          s("nr_distanza", s("nd_" + (x("distanza") ?: return null).replace('.', '_')) ?: return null,
              fase(x("fase")) ?: return null,
              if (x("tipo_settimana") == "scarico") s("nr_frammento_scarico_maiusc") ?: return null else "")
      "nr_volume" -> s("nr_volume", x("ore") ?: return null, x("minuti") ?: return null, numero(x("target") ?: return null), numero(x("fattore") ?: return null))
      "nr_ripartizione" -> s("nr_ripartizione", x("nuoto") ?: return null, x("bici") ?: return null, x("corsa") ?: return null)
      "nr_alta_intensita" -> s("nr_alta_intensita", x("min") ?: return null, x("tot") ?: return null, numero(x("pct") ?: return null), x("tetto") ?: return null)
      "nr_banda" -> s("nr_banda", banda(x("banda")) ?: return null)
      "nr_hrv" ->
          s("nr_hrv", numero(x("media") ?: return null), numero(x("baseline") ?: return null), numero(x("min") ?: return null),
              numero(x("max") ?: return null), s("msg_direzione_" + (x("direzione") ?: return null)) ?: return null)
      "nr_forma" ->
          if (x("rampa") == null) s("nr_forma", numero(x("ctl") ?: return null), numero(x("atl") ?: return null), numero(x("tsb") ?: return null))
          else s("nr_forma_rampa", numero(x("ctl") ?: return null), numero(x("atl") ?: return null), numero(x("tsb") ?: return null), x("rampa")!!.let { r -> (if (r.startsWith("+")) "+" else "") + numero(r.removePrefix("+")) })
      "nr_motivi" -> {
        val m = v.get("messaggi")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        s("nr_motivi", m.map { messaggio(it) ?: return null }.joinToString("; "))
      }
      "nr_riposo_consigliato" -> s("nr_riposo_consigliato", giorno(x("giorno")) ?: return null)
      "nr_vuota" -> ""
      "nr_giorno_riposo" -> s("nr_giorno_riposo", giorno(x("giorno")) ?: return null, ggMm(x("data")) ?: return null)
      "nr_giorno_sedute" -> {
        val sedute = v.get("sedute")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val elenco =
            sedute.map { e ->
              val sd = e.takeIf { it.isJsonObject }?.asJsonObject ?: return null
              (s("nr_seduta", seduta(sd.s("chiave")) ?: return null, sd.s("minuti") ?: return null) ?: return null) +
                  (if (sd.s("aerobica") == "si") s("nr_frammento_aerobica") ?: return null else "")
            }
        s("nr_giorno_sedute", giorno(x("giorno")) ?: return null, ggMm(x("data")) ?: return null, elenco.joinToString(" + "))
      }
      "nr_rimodulazione" -> {
        val azioni = v.get("azioni")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val testi =
            azioni.map { e ->
              val a = e.takeIf { it.isJsonObject }?.asJsonObject ?: return null
              azione(a, ::s, ::seduta, ::messaggio, ::banda, stringa, data, numero) ?: return null
            }
        s("nr_rimodulazione", data(x("data") ?: return null), banda(x("banda")) ?: return null, testi.joinToString("; "))
      }
      else -> messaggio(o) // notifica breve: un codice del catalogo
    }
  }

  private fun azione(
      a: JsonObject,
      s: (String, Array<out String>) -> String?,
      seduta: (String?) -> String?,
      messaggio: (JsonElement?) -> String?,
      @Suppress("UNUSED_PARAMETER") banda: (String?) -> String?,
      stringa: (String, Array<String>) -> String?,
      data: (String) -> String,
      numero: (String) -> String,
  ): String? {
    val v = a.get("valori")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
    fun x(k: String) = v.get(k)?.takeIf { it.isJsonPrimitive }?.asString
    fun nome() = seduta(x("chiave")) ?: x("nome")
    return when (a.s("codice")) {
      "nr_az_brick_alleggerito" -> s("msg_brick_alleggerito", arrayOf())
      "nr_az_rimossa" -> s("nr_az_rimossa", arrayOf(seduta(x("chiave")) ?: return null, messaggio(v.get("motivo")) ?: return null))
      "nr_az_resa_aerobica" -> s("nr_az_resa_aerobica", arrayOf(seduta(x("chiave")) ?: return null, messaggio(v.get("motivo")) ?: return null))
      "nr_az_rimossa_disponibilita" -> s("nr_az_rimossa_disponibilita", arrayOf(nome() ?: return null))
      "nr_az_accorciata_disponibilita" -> s("nr_az_accorciata_disponibilita", arrayOf(nome() ?: return null, x("minuti") ?: return null))
      "nr_az_companion_prevenzione" ->
          s("msg_companion_prevenzione", arrayOf(x("scheda") ?: return null, x("zona")?.let { z -> stringa("voc_$z", emptyArray()) ?: z } ?: return null))
      else -> messaggio(a.deepCopy().apply { if (!has("tipo")) addProperty("tipo", "motivo") })
    }
  }
}
