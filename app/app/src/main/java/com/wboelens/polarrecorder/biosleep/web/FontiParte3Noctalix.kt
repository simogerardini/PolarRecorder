package com.wboelens.polarrecorder.biosleep.web

import android.content.Context
import com.google.gson.JsonParser
import com.wboelens.polarrecorder.biosleep.cache.CacheRepo
import com.wboelens.polarrecorder.biosleep.cervello.Cervello
import com.wboelens.polarrecorder.biosleep.cervello.DisponibilitaDate
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsSettings
import com.wboelens.polarrecorder.biosleep.ponte.BlocchiParte3
import com.wboelens.polarrecorder.biosleep.ponte.Comando
import com.wboelens.polarrecorder.biosleep.ponte.EsitoComando
import com.wboelens.polarrecorder.biosleep.ponte.FontiParte3
import com.wboelens.polarrecorder.biosleep.ponte.NotteParte3
import com.wboelens.polarrecorder.biosleep.readiness.PyJson
import com.wboelens.polarrecorder.biosleep.tag.TagDb
import com.wboelens.polarrecorder.biosleep.tag.Vocabolario
import java.time.LocalDate

/**
 * La Parte 3 per il ponte web (Parte 1, biosleep.ponte): blocchi della copia cifrata, token di
 * Intervals.icu per il browser e comandi in arrivo dal sito (tag, disponibilita' per data).
 * Agganciato in PolarRecorderApplication.onCreate: Parte3Ponte.fonti = FontiParte3Noctalix.
 * Tutte le funzioni girano in un worker, fuori dal main thread.
 */
object FontiParte3Noctalix : FontiParte3 {
  private val FILE_RIEPILOGO = Regex("""riepilogo_(\d{4}-\d{2}-\d{2})\.json""")

  override fun perCopia(context: Context): BlocchiParte3? {
    val oggi = LocalDate.now()
    val d = DatiParte3.perCopia(context, oggi)
    val limite = oggi.minusDays(DatiParte3.GIORNI.toLong()).toString()
    val riepiloghi =
        Cervello.cartella(context).listFiles().orEmpty()
            .mapNotNull { f -> FILE_RIEPILOGO.matchEntire(f.name)?.groupValues?.get(1)?.takeIf { it >= limite }?.let { it to f } }
            .sortedBy { it.first }
            .mapNotNull { (_, f) ->
              runCatching { DatiParte3.conTestiLocali(context, JsonParser.parseString(f.readText(Charsets.UTF_8)).asJsonObject).toString() }.getOrNull()
            }
    fun lista(o: com.google.gson.JsonObject) = o.entrySet().associate { (k, v) -> k to v.asJsonArray.map { it.asString } }
    return BlocchiParte3(
        profiloJson = d.profilo.toString(),
        prontezzaJson = d.prontezza?.toString(),
        sonnoJson = d.sonno.toString(),
        riepiloghiJson = riepiloghi,
        tagGiorni = lista(d.tagGiorni),
        tagSedute = lista(d.tagSedute),
        // tag della notte: restano in tagGiorni (data della sera), non si duplicano qui
        notti = d.punteggi.mapValues { (_, p) ->
          NotteParte3(punteggio = p.get("punteggio_sonno")?.asInt, contributiJson = p.get("punteggio_contributi")?.toString())
        },
    )
  }

  override fun intervals(context: Context): Triple<String, String, String>? {
    val s = IntervalsSettings(context)
    val token = s.token.takeIf { it.isNotEmpty() } ?: return null // API key: niente token per il browser
    return Triple(s.atletaCollegato, token, s.scopeCollegato)
  }

  override fun applica(context: Context, comando: Comando): EsitoComando? {
    fun ko(e: String) = EsitoComando(comando.id, false, e)
    fun tag(ammessi: List<String>): Set<String>? {
      val t = (comando.dati["tag"] as? List<*>)?.map { it as? String ?: return null }?.toSet() ?: return null
      return t.takeIf { ammessi.containsAll(it) }
    }
    return try {
      when (comando.tipo) {
        "tag_giorno" -> {
          val data = (comando.dati["data"] as? String)?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess } ?: return ko("data non valida")
          val t = tag(Vocabolario.GIORNO) ?: return ko("tag non validi")
          TagDb.get(context).impostaGiorno(data, t)
          EsitoComando(comando.id, true)
        }
        "tag_seduta" -> {
          val id = comando.dati["attivita_id"] as? String ?: return ko("attivita_id mancante")
          val t = tag(Vocabolario.SEDUTA) ?: return ko("tag non validi")
          TagDb.get(context).impostaSeduta(id, dataAttivita(context, id) ?: LocalDate.now().toString(), t)
          EsitoComando(comando.id, true)
        }
        "disponibilita_data" -> {
          val data = (comando.dati["data"] as? String)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return ko("data non valida")
          val minuti = (comando.dati["minuti"] as? Number)?.toInt()
          if (minuti != null && minuti !in 0..1440) return ko("minuti non validi")
          DisponibilitaDate.salva(context, data, minuti)
          EsitoComando(comando.id, true)
        }
        else -> null // aggiorna_ora e sconosciuti: li gestisce la Parte 1
      }
    } catch (e: Exception) {
      ko(e.message ?: e.javaClass.simpleName)
    }
  }

  /** Data dell'attivita' dalla cache di Intervals.icu (ultimi 120 giorni). */
  private fun dataAttivita(context: Context, id: String): String? {
    val oggi = LocalDate.now()
    return CacheRepo.get(context).attivita(oggi.minusDays(DatiParte3.GIORNI.toLong()), oggi)
        .firstOrNull { PyJson.str(it.get("id")) == id }
        ?.let { PyJson.str(it.get("start_date_local"))?.take(10) }
  }
}
