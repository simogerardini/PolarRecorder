package com.wboelens.polarrecorder.biosleep.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wboelens.polarrecorder.biosleep.cervello.Cervello
import com.wboelens.polarrecorder.biosleep.lingua.tr
import com.wboelens.polarrecorder.biosleep.tag.Vocabolario
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scheda Notte (cervello 2026.10.19-notte-attesa): HRV della notte rispetto a quello atteso dal
 * modello personale (carico di ieri e tag). Dal riepilogo del giorno del risveglio; nessuna riga se
 * il modello e' spento o la notte non ha la stima.
 */
object NotteAttesa {
  data class Valori(val reale: Double, val atteso: Double, val intervallo: Double, val carico: Int?)

  fun leggi(riepilogo: JsonObject): Valori? {
    val m = riepilogo.get("modello")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    if (m.get("attivo")?.takeIf { it.isJsonPrimitive }?.asBoolean != true) return null
    val n = riepilogo.get("notte_attesa")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    fun d(k: String) = n.get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
    return Valori(d("reale_pct") ?: return null, d("atteso_pct") ?: return null, d("intervallo_pct") ?: return null,
        d("carico_ieri")?.roundToInt())
  }

  /** Riga italiana per tr(): "HRV di stanotte -12% · ..." senza separatori " · " (frase unica). */
  fun riga(v: Valori): String {
    fun pct(x: Double) = String.format(Locale.ROOT, "%+.0f%%", x)
    val atteso = pct(v.atteso) + " ±" + String.format(Locale.ROOT, "%.0f%%", v.intervallo)
    return "HRV di stanotte ${pct(v.reale)}, atteso $atteso dal carico di ieri (${v.carico ?: "—"} TSS)"
  }
}

@Composable
fun RigaNotteAttesa(fineNotteMs: Long) {
  val context = LocalContext.current.applicationContext
  val v by produceState<NotteAttesa.Valori?>(null, fineNotteMs) {
    value = withContext(Dispatchers.IO) {
      runCatching {
        val data = Vocabolario.mattinaDaFine(fineNotteMs)
        val f = File(Cervello.cartella(context), "riepilogo_$data.json")
        if (f.exists()) NotteAttesa.leggi(JsonParser.parseString(f.readText()).asJsonObject) else null
      }.getOrNull()
    }
  }
  v?.let {
    Text(tr(NotteAttesa.riga(it)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}
