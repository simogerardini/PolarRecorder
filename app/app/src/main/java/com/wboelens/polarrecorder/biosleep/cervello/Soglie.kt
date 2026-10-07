package com.wboelens.polarrecorder.biosleep.cervello

import android.content.Context
import com.google.gson.JsonParser
import com.wboelens.polarrecorder.biosleep.intervals.IntervalsSettings
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Una soglia mancante su Intervals.icu (cervello.controlla_soglie). */
data class SogliaMancante(val disciplina: String, val campo: String, val bloccante: Boolean, val effetto: String) {
  /** "Corsa · Passo soglia", "Nuoto · Impostazioni nuoto". */
  val titolo: String
    get() = Soglie.disciplina(disciplina) + " · " + Soglie.campo(campo, disciplina)
}

data class EsitoSoglie(
    val esito: String,
    val mancanti: List<SogliaMancante>,
    /** disciplina -> campo -> valore leggibile (i passi arrivano gia' formattati dal cervello). */
    val valori: Map<String, Map<String, String>>,
    val link: String,
    val errore: String?,
) {
  val daCompletare: Boolean get() = esito == Soglie.DA_COMPLETARE
}

object Soglie {
  const val OK = "ok"
  const val DA_COMPLETARE = "da_completare"
  const val PERMESSO_MANCANTE = "permesso_mancante"
  const val ERRORE = "errore"
  private const val LINK_PREDEFINITO = "https://intervals.icu/settings"

  fun disciplina(d: String) = d.replaceFirstChar { it.uppercase() }

  fun campo(c: String, disciplina: String) =
      when (c) {
        "passo_soglia" -> "Passo soglia"
        "css" -> "CSS nuoto"
        "lthr" -> "FC soglia (LTHR)"
        "ftp" -> "FTP"
        "cp" -> "CP corsa (Stryd)"
        "sport" -> "Impostazioni $disciplina"
        else -> c
      }

  /** "LTHR 167 · passo soglia 4:30/km" per una disciplina. */
  fun valoriLeggibili(campi: Map<String, String>): String =
      listOf("lthr", "passo_soglia", "cp", "ftp", "css")
          .mapNotNull { k ->
            campi[k]?.let { v ->
              when (k) {
                "lthr" -> "LTHR $v"
                "passo_soglia" -> "passo soglia $v"
                "ftp" -> "FTP $v W"
                "cp" -> "CP ${v.toDoubleOrNull()?.let { Math.round(it).toString() } ?: v} W"
                "css" -> "CSS $v"
                else -> "$k $v"
              }
            }
          }
          .joinToString(" · ")

  fun da(testo: String): EsitoSoglie =
      try {
        val o = JsonParser.parseString(testo).asJsonObject
        fun s(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString
        val mancanti =
            o.get("mancanti")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { el ->
              val m = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
              fun ms(k: String) = m.get(k)?.takeIf { it.isJsonPrimitive }?.asString
              SogliaMancante(ms("disciplina") ?: return@mapNotNull null, ms("campo") ?: "", ms("gravita") == "bloccante", ms("effetto") ?: "")
            }.orEmpty()
        val valori =
            o.get("valori")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.associate { (d, campi) ->
              d to (campi.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.associate { (k, v) -> k to v.asString }.orEmpty())
            }.orEmpty()
        EsitoSoglie(s("esito") ?: ERRORE, mancanti, valori, s("link") ?: LINK_PREDEFINITO, s("errore"))
      } catch (e: RuntimeException) {
        EsitoSoglie(ERRORE, emptyList(), emptyMap(), LINK_PREDEFINITO, "Risposta illeggibile: ${e.message}")
      }
}

/**
 * Controllo delle soglie: subito dopo prepara_account, all'apertura del Profilo e, in automatico,
 * al massimo una volta al giorno. L'ultimo esito resta salvato per le card di Oggi e Profilo.
 */
object SoglieRepo {
  private const val PREFS = "biosleep_soglie"
  private val _stato = MutableStateFlow<EsitoSoglie?>(null)
  val stato: StateFlow<EsitoSoglie?> = _stato.asStateFlow()

  /** Ultimo esito salvato (all'avvio, prima di un nuovo controllo). Fuori dal main thread. */
  fun carica(context: Context): EsitoSoglie? {
    if (_stato.value == null) {
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("ultimo", null)?.let { _stato.value = Soglie.da(it) }
    }
    return _stato.value
  }

  /**
   * forza = false: solo se oggi non si e' ancora controllato. Fuori dal main thread.
   * Un errore (rete) non sostituisce l'ultimo esito buono: la card non sparisce per un errore.
   */
  fun controlla(context: Context, forza: Boolean): EsitoSoglie? {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val oggi = LocalDate.now().toString()
    if (!forza && prefs.getString("giorno", null) == oggi) return carica(context)
    val c = IntervalsSettings(context).credenziali ?: return carica(context)
    val e = Cervello.controllaSoglie(context, c, ProfiloRepo.effettivo(context))
    if (e.esito == Soglie.ERRORE) return carica(context) ?: e
    prefs.edit().putString("ultimo", Cervello.ultimaRispostaSoglie).putString("giorno", oggi).apply()
    _stato.value = e
    return e
  }
}
