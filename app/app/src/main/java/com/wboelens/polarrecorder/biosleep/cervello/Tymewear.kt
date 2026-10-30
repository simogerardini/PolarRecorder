package com.wboelens.polarrecorder.biosleep.cervello

import android.content.Context
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.LocalDate

/** Una soglia ventilatoria: FC, potenza, passo (s/km, solo corsa), ventilazione (L/min). */
data class SogliaVt(val fc: Double? = null, val w: Double? = null, val passo: Double? = null, val ve: Double? = null) {
  val vuota get() = fc == null && w == null && passo == null && ve == null
}

/** Test Tymewear di una disciplina. fonte: "manuale" (inserito a mano) o "auto" (dal cervello). */
data class TestTymewear(
    val data: String? = null,
    val fonte: String = Tymewear.MANUALE,
    val vt1: SogliaVt = SogliaVt(),
    val vt2: SogliaVt = SogliaVt(),
)

/**
 * Tymewear (cervello 2026.10.30-tymewear): VT1/VT2 per bici e corsa. Va nel configJson
 * (profilo.tymewear) solo se attivo.
 */
data class Tymewear(val attivo: Boolean = false, val bici: TestTymewear? = null, val corsa: TestTymewear? = null) {
  fun test(disciplina: String) = if (disciplina == "bici") bici else corsa

  fun con(disciplina: String, t: TestTymewear?) = if (disciplina == "bici") copy(bici = t) else copy(corsa = t)

  fun json(): JsonObject =
      JsonObject().apply {
        addProperty("attivo", attivo)
        for (d in DISCIPLINE) test(d)?.let { add(d, testJson(it, d)) }
      }

  companion object {
    const val MANUALE = "manuale"
    const val AUTO = "auto"
    val DISCIPLINE = listOf("bici", "corsa")

    private fun n(v: Double?): JsonElement = v?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE

    private fun vtJson(v: SogliaVt, d: String) =
        JsonObject().apply {
          add("fc", n(v.fc))
          if (d == "corsa" && v.w == null) add("passo", n(v.passo)) else add("w", n(v.w))
          add("ve", n(v.ve))
        }

    private fun testJson(t: TestTymewear, d: String) =
        JsonObject().apply {
          if (t.data != null) addProperty("data", t.data) else add("data", JsonNull.INSTANCE)
          addProperty("fonte", t.fonte)
          add("vt1", vtJson(t.vt1, d))
          add("vt2", vtJson(t.vt2, d))
        }

    private fun num(o: JsonObject?, k: String) =
        o?.get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

    private fun vt(o: JsonObject?) = SogliaVt(num(o, "fc"), num(o, "w"), num(o, "passo"), num(o, "ve"))

    fun testDa(o: JsonObject?, fonte: String? = null): TestTymewear? {
      o ?: return null
      fun obj(k: String) = o.get(k)?.takeIf { it.isJsonObject }?.asJsonObject
      return TestTymewear(
          o.get("data")?.takeIf { it.isJsonPrimitive }?.asString?.take(10),
          fonte ?: o.get("fonte")?.takeIf { it.isJsonPrimitive }?.asString ?: MANUALE,
          vt(obj("vt1")),
          vt(obj("vt2")))
    }

    fun da(testo: String?): Tymewear =
        runCatching {
              val o = JsonParser.parseString(testo ?: return Tymewear()).asJsonObject
              Tymewear(
                  o.get("attivo")?.asBoolean ?: false,
                  testDa(o.get("bici")?.takeIf { it.isJsonObject }?.asJsonObject),
                  testDa(o.get("corsa")?.takeIf { it.isJsonObject }?.asJsonObject))
            }
            .getOrDefault(Tymewear())

    /** "4:30" -> 270 s/km; null se non leggibile. */
    fun passo(testo: String): Double? {
      val p = testo.trim().split(":")
      if (p.size != 2) return null
      val m = p[0].toIntOrNull() ?: return null
      val s = p[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
      return (m * 60 + s).toDouble().takeIf { it in 120.0..900.0 }
    }

    fun passoTxt(sKm: Double?) = sKm?.let { "%d:%02d".format(it.toInt() / 60, it.toInt() % 60) }.orEmpty()

    /**
     * Soglie rilevate dal cervello ("tymewear_soglie", fase futura): si prendono con fonte "auto" solo
     * se il test e' piu' recente di quello salvato; a parita' di data vince il manuale.
     * null = nessun cambiamento. Campo assente = nessuna azione.
     */
    fun unisci(attuale: Tymewear, dalCervello: JsonObject?): Tymewear? {
      dalCervello ?: return null
      var t = attuale
      var cambiato = false
      for (d in DISCIPLINE) {
        val nuovo = testDa(dalCervello.get(d)?.takeIf { it.isJsonObject }?.asJsonObject, AUTO) ?: continue
        val dn = nuovo.data?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: continue
        val da = t.test(d)?.data?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (da == null || dn.isAfter(da)) {
          t = t.con(d, nuovo)
          cambiato = true
        }
      }
      return if (cambiato) t else null
    }
  }
}

/** Fase futura: soglie Tymewear rilevate dal cervello nell'ultimo riepilogo. Inattivo senza il campo. */
object TymewearAuto {
  /** true se il profilo e' stato aggiornato (allora si ricontrollano le soglie). Fuori dal main thread. */
  fun applica(context: Context): Boolean {
    val store = ProfiloStore(context)
    val p = store.leggi()
    if (!p.tymewear.attivo) return false
    val f = Cervello.cartella(context).listFiles { x -> x.name.startsWith("riepilogo_") && x.name.endsWith(".json") }
        ?.maxByOrNull { it.name } ?: return false
    val campo = runCatching { JsonParser.parseString(f.readText()).asJsonObject.get("tymewear_soglie") }.getOrNull()
        ?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
    val nuovo = Tymewear.unisci(p.tymewear, campo) ?: return false
    store.salva(p.copy(tymewear = nuovo))
    return true
  }
}
