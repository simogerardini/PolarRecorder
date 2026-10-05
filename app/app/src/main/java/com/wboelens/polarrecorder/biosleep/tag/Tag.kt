package com.wboelens.polarrecorder.biosleep.tag

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Vocabolario dei tag concordato con il cervello (coach_settimanale.TAG_GIORNO / TAG_SEDUTA):
 * chiavi fisse, perche' il cervello e' deterministico. Una chiave diversa verrebbe ignorata e
 * segnalata negli avvisi ("tag sconosciuti ignorati").
 */
object Vocabolario {
  val ZONE = listOf("ginocchio", "caviglia", "piede", "polpaccio", "anca", "schiena", "spalla")

  /** Tag di giorno per gruppo (titolo -> chiavi), nell'ordine in cui si mostrano. */
  val GIORNO_SERA = listOf("alcol", "cena_tardiva", "caffeina_tardi", "stress")
  val GIORNO_NOTTE = listOf("sonno_disturbato")
  val GIORNO_CONTESTO = listOf("malattia", "viaggio", "caldo", "altitudine")
  val GIORNO_CORPO = listOf("dolore_muscolare") + ZONE.map { "infortunio:$it" }
  val SEDUTA = listOf("fatica_alta", "gambe_pesanti", "malessere") + ZONE.map { "dolore:$it" }

  val GIORNO = GIORNO_SERA + GIORNO_NOTTE + GIORNO_CONTESTO + GIORNO_CORPO

  private val ETICHETTE =
      mapOf(
          "alcol" to "Alcol", "cena_tardiva" to "Cena tardiva", "caffeina_tardi" to "Caffeina tardi",
          "stress" to "Stress", "viaggio" to "Viaggio", "malattia" to "Malattia",
          "sonno_disturbato" to "Sonno disturbato", "caldo" to "Caldo", "altitudine" to "Altitudine",
          "dolore_muscolare" to "Dolore muscolare", "fatica_alta" to "Fatica alta",
          "gambe_pesanti" to "Gambe pesanti", "malessere" to "Malessere")

  fun etichetta(chiave: String): String =
      ETICHETTE[chiave]
          ?: when {
            chiave.startsWith("infortunio:") -> "Infortunio — " + chiave.substringAfter(':')
            chiave.startsWith("dolore:") -> "Dolore — " + chiave.substringAfter(':')
            else -> chiave
          }

  /** Data dei tag della notte che sta per iniziare: la mattina del risveglio (come la wellness). */
  fun mattinaDellaNotte(adesso: LocalDateTime): LocalDate =
      if (adesso.hour >= 12) adesso.toLocalDate().plusDays(1) else adesso.toLocalDate()

  /** Il blocco "tag" del configJson; null se non c'e' nessun tag. */
  fun json(giorni: Map<String, Set<String>>, sedute: Map<String, Set<String>>): JsonObject? {
    if (giorni.isEmpty() && sedute.isEmpty()) return null
    fun gruppo(m: Map<String, Set<String>>, ordine: List<String>) =
        JsonObject().apply {
          for ((k, chiavi) in m.toSortedMap()) {
            add(k, JsonArray().apply { chiavi.sortedBy { ordine.indexOf(it) }.forEach { add(it) } })
          }
        }
    return JsonObject().apply {
      add("giorni", gruppo(giorni, GIORNO))
      add("sedute", gruppo(sedute, SEDUTA))
    }
  }
}

/** I tag salvati: sono dati tuoi, non si rileggono da Intervals.icu, quindi non stanno nella cache. */
class TagDb private constructor(context: Context) : SQLiteOpenHelper(context, "biosleep_tag.db", null, 1) {
  companion object {
    @Volatile private var instance: TagDb? = null

    fun get(context: Context): TagDb =
        instance ?: synchronized(this) { instance ?: TagDb(context.applicationContext).also { instance = it } }

    private val _versione = MutableStateFlow(0)
    val versione: StateFlow<Int> = _versione.asStateFlow()
  }

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL("CREATE TABLE giorni(data TEXT NOT NULL, chiave TEXT NOT NULL, PRIMARY KEY(data, chiave))")
    db.execSQL("CREATE TABLE sedute(id TEXT NOT NULL, data TEXT NOT NULL, chiave TEXT NOT NULL, PRIMARY KEY(id, chiave))")
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    // Versione 1. Le versioni future dovranno MIGRARE: i tag non si possono ricostruire.
  }

  fun giorno(data: String): Set<String> = leggi("SELECT chiave FROM giorni WHERE data = ?", arrayOf(data))

  fun seduta(id: String): Set<String> = leggi("SELECT chiave FROM sedute WHERE id = ?", arrayOf(id))

  private fun leggi(sql: String, arg: Array<String>): Set<String> {
    val out = LinkedHashSet<String>()
    readableDatabase.rawQuery(sql, arg).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
    return out
  }

  fun impostaGiorno(data: String, chiavi: Set<String>) {
    val db = writableDatabase
    db.beginTransaction()
    try {
      db.delete("giorni", "data = ?", arrayOf(data))
      for (k in chiavi) db.insert("giorni", null, ContentValues().apply { put("data", data); put("chiave", k) })
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    _versione.update { it + 1 }
  }

  fun impostaSeduta(id: String, data: String, chiavi: Set<String>) {
    val db = writableDatabase
    db.beginTransaction()
    try {
      db.delete("sedute", "id = ?", arrayOf(id))
      for (k in chiavi) {
        db.insert("sedute", null, ContentValues().apply { put("id", id); put("data", data); put("chiave", k) })
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    _versione.update { it + 1 }
  }

  /** Tag di giorno in [da, a], per data. */
  fun giorni(da: LocalDate, a: LocalDate): Map<String, Set<String>> {
    val out = LinkedHashMap<String, MutableSet<String>>()
    readableDatabase.rawQuery(
        "SELECT data, chiave FROM giorni WHERE data BETWEEN ? AND ? ORDER BY data", arrayOf(da.toString(), a.toString()))
        .use { c -> while (c.moveToNext()) out.getOrPut(c.getString(0)) { LinkedHashSet() }.add(c.getString(1)) }
    return out
  }

  /** Tag di seduta delle sedute svolte da [da] in poi, per id. */
  fun sedute(da: LocalDate): Map<String, Set<String>> {
    val out = LinkedHashMap<String, MutableSet<String>>()
    readableDatabase.rawQuery("SELECT id, chiave FROM sedute WHERE data >= ?", arrayOf(da.toString()))
        .use { c -> while (c.moveToNext()) out.getOrPut(c.getString(0)) { LinkedHashSet() }.add(c.getString(1)) }
    return out
  }

  /** Il campo "tag" per il cervello: giorni da 90 giorni fa a 14 avanti, sedute delle ultime 4 settimane. */
  fun perCervello(oggi: LocalDate = LocalDate.now()): JsonObject? =
      Vocabolario.json(giorni(oggi.minusDays(90), oggi.plusDays(14)), sedute(oggi.minusDays(28)))
}
