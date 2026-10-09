package com.wboelens.polarrecorder.biosleep.riepilogo

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import java.util.concurrent.ConcurrentHashMap

/**
 * Messaggi del cervello con codice e valori (punto 13a): l'app li traduce con il codice e
 * inserisce i valori; "testo" (italiano) resta come ripiego. Catalogo: python/messaggi.py.
 */
data class Messaggio(
    val tipo: String,
    val codice: String,
    val valori: Map<String, String>,
    /** Solo per "multiplo": i messaggi uniti da "; ". */
    val parti: List<Messaggio>,
    val testo: String,
)

object Messaggi {
  const val NON_CODIFICATO = "non_codificato"
  const val MULTIPLO = "multiplo"

  /**
   * Ordine degli argomenti di ogni stringa msg_<codice> (%1$s, %2$s, ...). "meteo", "seduta_opz"
   * e "senza_opz" sono frammenti costruiti dall'app per le parti facoltative del testo.
   */
  val ORDINE: Map<String, List<String>> =
      mapOf(
          "hrv_sotto_range_forte" to listOf("z"),
          "hrv_sotto_range" to listOf("z"),
          "hrv_sotto_range_persistente" to listOf("giorni"),
          "banda_rossa_persistente" to listOf(),
          "sonno_medio_basso" to listOf("ore"),
          "temperatura_alta" to listOf("gradi"),
          "readiness_bassa" to listOf("valore"),
          "fc_riposo_alta" to listOf("bpm"),
          "rampa_ctl_alta" to listOf("rampa"),
          "tsb_basso" to listOf("tsb"),
          "baseline_poche_notti" to listOf("giorni"),
          "baseline_date_illeggibili" to listOf(),
          "baseline_non_disponibile" to listOf(),
          "dato_biometrico_vecchio" to listOf("giorni"),
          "settimana_senza_spazio" to listOf("seduta"),
          "ripianificata_per_assenza" to listOf("data"),
          "forza_tolta_tempo" to listOf("data"),
          "forza_tolta_profilo" to listOf("data"),
          "companion_prevenzione" to listOf("scheda", "zona"),
          "tag_malattia_riposo" to listOf("data"),
          "tag_malattia_leggero" to listOf("data"),
          "tag_infortunio" to listOf("zona", "data", "discipline"),
          "tag_viaggio" to listOf("data"),
          "tag_dolore_muscolare" to listOf("data"),
          "tag_dolore_seduta" to listOf("zona", "data_seduta", "data", "senza_opz"),
          "tag_fatica_seduta" to listOf("data_seduta", "seduta", "data"),
          "tag_fatica" to listOf("data_seduta"),
          "tag_sconosciuti" to listOf("chiavi"),
          "caldo_corsa_convertita" to listOf("data", "meteo", "minuti", "fattore"),
          "caldo_corsa_accorciata" to listOf("data", "meteo", "seduta_opz", "minuti"),
          "detp_sospeso" to listOf("banda"),
          "detp_gara_calda" to listOf("n", "data"),
          "detp_sweat_test" to listOf("data"),
          "detp_heat_block" to listOf("data"),
          "sweat_test_programmato" to listOf("data"),
          "lthr_aggiornata" to listOf("sport", "da", "a"),
          "ftp_aggiornata" to listOf("da", "a"),
          "cp_aggiornata" to listOf("da", "a"),
          "test_programmato" to listOf("data", "nome"),
          "css_tempi_richiesti" to listOf(),
          "passo_soglia_aggiornato" to listOf("passo", "data"),
          "passo_soglia_da_confermare" to listOf("data", "passo", "attuale"),
          "passo_soglia_non_misurabile" to listOf("data"),
          "passo_soglia_scrittura_fallita" to listOf("data"),
          "test_corsa_non_trovato" to listOf("data"),
          "soglie_da_completare" to listOf("elenco"),
          "settimana_tipo_non_valida" to listOf("errori"),
          "fuso_non_valido" to listOf("fuso"),
          "tsb_fuori_fascia" to listOf("tsb", "min", "max"),
          "sedute_non_scritte" to listOf("n"),
          "seduta_non_scritta" to listOf("data", "seduta", "errore"),
          "disponibilita_data_zero" to listOf("data"),
          "disponibilita_data_ridotta" to listOf("data", "minuti"),
          "brick_alleggerito" to listOf(),
          "seduta_accorciata_disponibilita" to listOf("seduta", "minuti"),
          "seduta_rimossa" to listOf("seduta", "motivo"),
          "seduta_resa_aerobica" to listOf("seduta", "motivo"),
          "piano_rimodulato" to listOf("data", "banda"),
          "piano_settimanale" to listOf(),
      )

  /** Valori da tradurre con un piccolo vocabolario (voc_<parola>), se la parola c'e'. */
  private val VOCABOLARIO = setOf("banda", "zona", "sport")

  private val NUMERI = setOf("z", "ore", "gradi", "valore", "bpm", "rampa", "tsb", "min", "max", "temp")

  fun leggi(arr: JsonArray?): List<Messaggio> = arr?.mapNotNull { leggiUno(it) }.orEmpty()

  private fun leggiUno(e: JsonElement): Messaggio? {
    val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    fun s(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString
    val valoriJ = o.get("valori")?.takeIf { it.isJsonObject }?.asJsonObject
    val parti = valoriJ?.get("messaggi")?.takeIf { it.isJsonArray }?.asJsonArray?.let { leggi(it) }.orEmpty()
    val valori =
        valoriJ?.entrySet()?.filter { it.value.isJsonPrimitive }?.associate { it.key to it.value.asString }.orEmpty()
    return Messaggio(s("tipo") ?: "motivo", s("codice") ?: NON_CODIFICATO, valori, parti, s("testo") ?: return null)
  }

  /**
   * Traduzione. [stringa] cerca la risorsa (nome, argomenti) e restituisce null se non c'e';
   * [data] e [numero] formattano con il Locale dell'utente. Senza risorsa: il testo italiano.
   */
  fun traduci(
      m: Messaggio,
      stringa: (String, Array<String>) -> String?,
      data: (String) -> String,
      numero: (String) -> String,
  ): String {
    if (m.codice == NON_CODIFICATO) return m.testo
    if (m.codice == MULTIPLO) return m.parti.joinToString("; ") { traduci(it, stringa, data, numero) }
    val ordine = ORDINE[m.codice] ?: return m.testo
    val v = m.valori
    fun valore(k: String): String {
      val x = v[k] ?: return ""
      return when {
        x == "None" -> "—"
        k.startsWith("data") -> runCatching { data(x) }.getOrDefault(x)
        k in NUMERI -> numero(x)
        k in VOCABOLARIO -> stringa("voc_" + x.lowercase(), emptyArray()) ?: x
        else -> x
      }
    }
    val argomenti =
        ordine.map { k ->
          when (k) {
            "meteo" ->
                if (v["temp"] == null) stringa("msg_frammento_caldo_tag", emptyArray()) ?: " (tag)"
                else
                    (stringa("msg_frammento_caldo_meteo", arrayOf(valore("temp"), valore("umidita"))) ?: "") +
                        (v["ora"]?.let { stringa("msg_frammento_alle", arrayOf(it)) } ?: "")
            "seduta_opz" -> v["seduta"]?.let { stringa("msg_frammento_seduta", arrayOf(it)) } ?: ""
            "senza_opz" -> v["discipline"]?.let { stringa("msg_frammento_senza", arrayOf(it)) } ?: ""
            else -> valore(k)
          }
        }
    return stringa("msg_" + m.codice, argomenti.toTypedArray()) ?: m.testo
  }

  // --- Registro: i testi italiani dei riepiloghi letti -> messaggio con codice -------------------

  private val registro = ConcurrentHashMap<String, Messaggio>()

  /** Chiamato dal parser del riepilogo: le schermate traducono poi a partire dal testo mostrato. */
  fun registra(messaggi: List<Messaggio>) {
    for (m in messaggi) {
      registro[m.testo.trim()] = m
      registro[m.testo.trim().removePrefix("tag: ").trim()] = m
    }
  }

  /** Il messaggio con codice per un testo italiano del riepilogo, se il cervello l'ha codificato. */
  fun per(testo: String): Messaggio? = registro[testo.trim()]
}
