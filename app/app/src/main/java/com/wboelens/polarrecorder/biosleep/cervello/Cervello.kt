package com.wboelens.polarrecorder.biosleep.cervello

import android.content.Context
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wboelens.polarrecorder.biosleep.intervals.Credenziali
import java.io.File

/** Parametri di una chiamata a cervello.esegui_app (contratto del pacchetto Python). */
data class ConfigCervello(
    val credenziali: Credenziali,
    val cartella: String,
    val modo: String = "auto",
    val dryRun: Boolean = false,
    val senzaAttesa: Boolean = false,
    /** true = rifa' il lavoro anche se gia' fatto (solo "Ripianifica questa settimana"). */
    val forza: Boolean = false,
    /** Posizione approssimativa per le previsioni del caldo; null = campo omesso. */
    val posizione: Posizione? = null,
    /** Profilo dell'atleta: le FC dell'app hanno la precedenza su quelle di Intervals.icu. */
    val profilo: ProfiloAtleta? = null,
    /** {"giorni": {data: [chiavi]}, "sedute": {id: [chiavi]}} dal TagDb. */
    val tag: com.google.gson.JsonObject? = null,
) {
  /** JSON costruito con Gson: le virgolette nella API key non possono rompere il formato. */
  fun json(): String =
      JsonObject()
          .apply {
            when (val c = credenziali) {
              // OAuth: il cervello usa il Bearer e l'atleta "0"
              is Credenziali.Token -> addProperty("intervals_token", c.token)
              is Credenziali.Chiave -> {
                addProperty("intervals_api_key", c.chiave)
                addProperty("intervals_athlete_id", c.atleta)
              }
            }
            addProperty("cartella", cartella)
            addProperty("modo", modo)
            addProperty("dry_run", dryRun)
            addProperty("senza_attesa", senzaAttesa)
            if (forza) addProperty("forza", true)
            posizione?.let { p ->
              add("posizione", JsonObject().apply {
                addProperty("lat", p.lat)
                addProperty("lon", p.lon)
              })
            }
            profilo?.let { add("profilo", it.json()) }
            tag?.let { add("tag", it) }
          }
          .toString()

  /** Per i log: mai la API key. */
  /** Per i log: niente credenziali ne' posizione. */
  override fun toString() =
      "ConfigCervello(credenziali=$credenziali, cartella=$cartella, modo=$modo, dryRun=$dryRun, senzaAttesa=$senzaAttesa, posizione=${if (posizione != null) "si" else "no"})"
}

/**
 * Esito di un run: pianificata | fatto | niente | attesa | gia_fatto | errore.
 * "niente": run giornaliero senza sedute da rimodulare; il riepilogo c'e' comunque, vale come "fatto".
 */
data class RisultatoCervello(
    val esito: String,
    val notifiche: List<String>,
    val riepilogoFile: String?,
    val logFile: String?,
    val errore: String?,
) {
  companion object {
    const val PIANIFICATA = "pianificata"
    const val FATTO = "fatto"
    const val NIENTE = "niente"
    const val ATTESA = "attesa"
    const val GIA_FATTO = "gia_fatto"
    const val ERRORE = "errore"

    /** Legge il JSON restituito dal modulo; un JSON illeggibile diventa un esito "errore". */
    fun da(testo: String): RisultatoCervello =
        try {
          val o = JsonParser.parseString(testo).asJsonObject
          fun str(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString
          RisultatoCervello(
              esito = str("esito") ?: ERRORE,
              notifiche =
                  (o.get("notifiche")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray())
                      .filter { it.isJsonPrimitive }
                      .map { it.asString }
                      .filter { it.isNotBlank() },
              riepilogoFile = str("riepilogo_file"),
              logFile = str("log_file"),
              errore = str("errore"),
          )
        } catch (e: RuntimeException) {
          RisultatoCervello(ERRORE, emptyList(), null, null, "Risposta del cervello illeggibile: ${e.message}")
        }
  }
}

/** Esito di cervello.prepara_account: campi BioSleep creati o gia' presenti su Intervals.icu. */
data class EsitoPrepara(val esito: String, val creati: List<String>, val esistenti: List<String>, val errore: String?) {
  companion object {
    const val OK = "ok"
    const val PERMESSO_MANCANTE = "permesso_mancante"
    const val ERRORE = "errore"

    fun da(testo: String): EsitoPrepara =
        try {
          val o = JsonParser.parseString(testo).asJsonObject
          fun lista(k: String) = o.get(k)?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString }.orEmpty()
          EsitoPrepara(
              o.get("esito")?.takeIf { it.isJsonPrimitive }?.asString ?: ERRORE, lista("creati"), lista("esistenti"),
              o.get("errore")?.takeIf { it.isJsonPrimitive }?.asString)
        } catch (e: RuntimeException) {
          EsitoPrepara(ERRORE, emptyList(), emptyList(), "Risposta illeggibile: ${e.message}")
        }
  }
}

/**
 * Il coach in Python (Chaquopy) dentro l'app. Ingresso unico: cervello.esegui_app(config_json).
 *
 * Una chiamata alla volta, sempre: il lucchetto serve anche quando WorkManager interrompe un
 * lavoro (limite di 10 minuti) e lo rilancia. Il Python gia' in corso non si puo' fermare, quindi
 * il secondo run aspetta il primo e poi trova il flag del giorno ("gia_fatto").
 */
object Cervello {
  private val lucchetto = Any()

  /** Cartella privata del coach: stato, flag, riepiloghi, log. */
  fun cartella(context: Context): File = File(context.filesDir, "coach").apply { mkdirs() }

  /** cervello.prepara_account: crea i campi BioSleep mancanti (non tocca gli esistenti). */
  fun preparaAccount(context: Context, c: Credenziali): EsitoPrepara =
      synchronized(lucchetto) {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        val cfg =
            JsonObject()
                .apply {
                  when (c) {
                    is Credenziali.Token -> addProperty("intervals_token", c.token)
                    is Credenziali.Chiave -> {
                      addProperty("intervals_api_key", c.chiave)
                      addProperty("intervals_athlete_id", c.atleta)
                    }
                  }
                }
                .toString()
        try {
          EsitoPrepara.da(Python.getInstance().getModule("cervello").callAttr("prepara_account", cfg).toString())
        } catch (e: PyException) {
          EsitoPrepara(EsitoPrepara.ERRORE, emptyList(), emptyList(), "Python: ${e.message}")
        }
      }

  /** Testo grezzo dell'ultima risposta di controlla_soglie (per salvarla cosi' com'e'). */
  @Volatile var ultimaRispostaSoglie: String = "{}"
    private set

  /** cervello.controlla_soglie: soglie di corsa, bici e nuoto su Intervals.icu. */
  fun controllaSoglie(context: Context, c: Credenziali, profilo: ProfiloAtleta? = null): EsitoSoglie =
      synchronized(lucchetto) {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        try {
          val testo =
              Python.getInstance().getModule("cervello")
                  // il profilo serve a sapere se la CP di corsa e' necessaria (Stryd)
                  .callAttr("controlla_soglie", ConfigCervello(c, cartella(context).absolutePath, profilo = profilo).json()).toString()
          ultimaRispostaSoglie = testo
          Soglie.da(testo)
        } catch (e: PyException) {
          EsitoSoglie(Soglie.ERRORE, emptyList(), emptyMap(), "https://intervals.icu/settings", "Python: ${e.message}")
        }
      }

  /** cervello.registra_css: tempi del test CSS (secondi) -> CSS su Intervals.icu, test chiuso. */
  fun registraCss(context: Context, c: Credenziali, t400: Int, t200: Int): EsitoCss =
      synchronized(lucchetto) {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        val cfg = JsonParser.parseString(ConfigCervello(c, cartella(context).absolutePath).json()).asJsonObject
        cfg.addProperty("t400", t400)
        cfg.addProperty("t200", t200)
        try {
          EsitoCss.da(Python.getInstance().getModule("cervello").callAttr("registra_css", cfg.toString()).toString())
        } catch (e: PyException) {
          EsitoCss("errore", null, "Python: ${e.message}")
        }
      }

  /** Chiama una funzione del modulo con le credenziali piu' i campi in [extra]. */
  private fun chiama(context: Context, funzione: String, c: Credenziali, extra: JsonObject.() -> Unit): String? =
      synchronized(lucchetto) {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        val cfg = JsonParser.parseString(ConfigCervello(c, cartella(context).absolutePath).json()).asJsonObject.apply(extra)
        try {
          Python.getInstance().getModule("cervello").callAttr(funzione, cfg.toString()).toString()
        } catch (e: PyException) {
          null
        }
      }

  /** cervello.gare: gare future con distanza riconosciuta e quella che detta la preparazione. */
  fun gare(context: Context, c: Credenziali): EsitoGare =
      chiama(context, "gare", c) {}?.let { Gare.elenco(it) } ?: EsitoGare("errore", emptyList(), "Python non disponibile")

  /** cervello.salva_gara: nuova gara, o modifica con [id]. */
  fun salvaGara(
      context: Context,
      c: Credenziali,
      nome: String,
      data: String,
      priorita: String,
      distanza: String,
      id: String?,
      calda: Boolean = false,
  ): EsitoGara =
      chiama(context, "salva_gara", c) {
        addProperty("nome", nome)
        addProperty("data", data)
        addProperty("priorita", priorita)
        addProperty("distanza", distanza)
        if (calda) addProperty("calda", true)
        id?.let { addProperty("id", it) }
      }?.let { Gare.esito(it) } ?: EsitoGara("errore", null, false, "Python non disponibile")

  /** cervello.elimina_gara: solo eventi che sono davvero gare. */
  fun eliminaGara(context: Context, c: Credenziali, id: String): EsitoGara =
      chiama(context, "elimina_gara", c) { addProperty("id", id) }?.let { Gare.esito(it) }
          ?: EsitoGara("errore", null, false, "Python non disponibile")

  /** Funzioni senza credenziali (esporta_stato, importa_stato): {"esito", ...} o null se Python fallisce. */
  fun chiamaFile(context: Context, funzione: String, file: java.io.File, extra: JsonObject.() -> Unit = {}): JsonObject? =
      synchronized(lucchetto) {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        val cfg =
            JsonObject().apply {
              addProperty("cartella", cartella(context).absolutePath)
              addProperty("file", file.absolutePath)
              extra()
            }
        try {
          JsonParser.parseString(Python.getInstance().getModule("cervello").callAttr(funzione, cfg.toString()).toString()).asJsonObject
        } catch (e: PyException) {
          null
        } catch (e: RuntimeException) {
          null
        }
      }

  /** cervello.registra_sweat: dati dello sweat test -> sudorazione nello stato del coach. */
  fun registraSweat(context: Context, d: DatiSweat): EsitoSweat =
      synchronized(lucchetto) {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        val cfg = d.json().apply { addProperty("cartella", cartella(context).absolutePath) }
        try {
          EsitoSweat.da(Python.getInstance().getModule("cervello").callAttr("registra_sweat", cfg.toString()).toString())
        } catch (e: PyException) {
          EsitoSweat("errore", null, "Python: ${e.message}")
        }
      }

  /** Versione del cervello (cervello.VERSIONE), senza eseguire il coach. null se Python non parte. */
  fun versione(context: Context): String? =
      synchronized(lucchetto) {
        try {
          if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
          Python.getInstance().getModule("cervello").get("VERSIONE")?.toString()
        } catch (e: PyException) {
          null
        }
      }

  fun esegui(context: Context, config: ConfigCervello): RisultatoCervello =
      synchronized(lucchetto) {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        try {
          val testo = Python.getInstance().getModule("cervello").callAttr("esegui_app", config.json()).toString()
          RisultatoCervello.da(testo)
        } catch (e: PyException) {
          // esegui_app intercetta gia' i propri errori: qui arrivano solo quelli di import/avvio
          RisultatoCervello(RisultatoCervello.ERRORE, emptyList(), null, null, "Python: ${e.message}")
        }
      }
}
