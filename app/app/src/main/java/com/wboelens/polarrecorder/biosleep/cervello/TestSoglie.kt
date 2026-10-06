package com.wboelens.polarrecorder.biosleep.cervello

import android.content.Context
import com.google.gson.JsonParser
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** stato_coach.json -> ultimo_test: il test periodico piu' recente messo a calendario dal coach. */
data class UltimoTest(
    val data: LocalDate,
    val chiave: String,
    val nome: String,
    val elaborato: Boolean,
    val attesaTempi: Boolean,
) {
  /** Card "test in programma" nella settimana del test, fino al giorno del test compreso. */
  fun inProgramma(oggi: LocalDate): Boolean {
    val lunedi = oggi.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return !elaborato && data >= oggi && data <= lunedi.plusDays(6)
  }

  /** Test CSS svolto di cui mancano i tempi (soglie.test_da_completare del cervello). */
  val chiedeTempiCss: Boolean get() = chiave == "swim_css" && attesaTempi
}

/** Esito di cervello.registra_css. */
data class EsitoCss(val esito: String, val css: String?, val errore: String?) {
  companion object {
    const val OK = "ok"
    const val NON_VALIDI = "valori_non_validi"
    const val PERMESSO_MANCANTE = "permesso_mancante"

    fun da(testo: String): EsitoCss =
        try {
          val o = JsonParser.parseString(testo).asJsonObject
          fun s(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString
          EsitoCss(s("esito") ?: "errore", s("css"), s("errore"))
        } catch (e: RuntimeException) {
          EsitoCss("errore", null, "Risposta illeggibile: ${e.message}")
        }
  }
}

object StatoCoachFile {
  /** ultimo_test dallo stato del coach (testo di stato_coach.json); null se assente o illeggibile. */
  fun ultimoTest(testo: String): UltimoTest? =
      try {
        val t = JsonParser.parseString(testo).asJsonObject.get("ultimo_test")?.takeIf { it.isJsonObject }?.asJsonObject
        t?.let {
          fun s(k: String) = it.get(k)?.takeIf { e -> e.isJsonPrimitive }?.asString
          fun b(k: String) = it.get(k)?.takeIf { e -> e.isJsonPrimitive && e.asJsonPrimitive.isBoolean }?.asBoolean == true
          UltimoTest(LocalDate.parse(s("data") ?: return null), s("chiave") ?: "", s("nome") ?: "Test", b("elaborato"), b("attesa_tempi"))
        }
      } catch (e: RuntimeException) {
        null
      }

  /** Fuori dal main thread. */
  fun ultimoTest(context: Context): UltimoTest? =
      File(Cervello.cartella(context), "stato_coach.json").takeIf { it.exists() }?.let { f ->
        runCatching { f.readText(Charsets.UTF_8) }.getOrNull()?.let { ultimoTest(it) }
      }
}

/** "6:12" -> 372 secondi; null se non e' mm:ss (secondi 0-59). */
fun secondiDaMmSs(t: String): Int? {
  val m = Regex("""^\s*(\d{1,2}):([0-5]\d)\s*$""").find(t) ?: return null
  val s = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
  return s.takeIf { it > 0 }
}

/** Le righe degli avvisi del cervello che dicono che una soglia e' stata aggiornata. */
object AvvisiSoglie {
  private val AGGIORNATA = Regex("""aggiornat[ao] su intervals\.icu""", RegexOption.IGNORE_CASE)

  fun aggiornate(avvisi: String?): List<String> = avvisi?.lines()?.map { it.trim() }?.filter { AGGIORNATA.containsMatchIn(it) }.orEmpty()
}
