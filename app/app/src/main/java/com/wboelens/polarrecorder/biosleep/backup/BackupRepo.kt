package com.wboelens.polarrecorder.biosleep.backup

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wboelens.polarrecorder.BuildConfig
import com.wboelens.polarrecorder.biosleep.SleepDb
import com.wboelens.polarrecorder.biosleep.cache.CacheSync
import com.wboelens.polarrecorder.biosleep.cervello.Cervello
import com.wboelens.polarrecorder.biosleep.riepilogo.RiepilogoDb
import com.wboelens.polarrecorder.biosleep.tag.TagDb
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

sealed interface EsitoBackup {
  data class Ok(val messaggio: String) : EsitoBackup

  data class Errore(val messaggio: String) : EsitoBackup
}

/**
 * Backup cifrato (.biosleep), ripristino ed esportazione CSV. Tutto passa da file scelti
 * dall'utente con lo Storage Access Framework: nessun server di BioSleep.
 * Mai nel backup: token OAuth e chiave API (dopo il ripristino si ricollega Intervals.icu).
 * Da chiamare fuori dal main thread.
 */
object BackupRepo {
  private const val TAG = "BioSleepBackup"
  private const val DB = "biosleep.db"
  private const val PREFS_STATO = "biosleep_backup"

  /** File di preferenze nel backup e, per ciascuno, le chiavi ammesse (null = tutte). */
  private val PREFERENZE =
      mapOf(
          "biosleep_profilo" to null, // profilo atleta: FC, ore, disponibilita', settimana, palestra, caldo
          "biosleep_intervals" to setOf("auto_upload", "athlete_id"), // MAI token o API key
          "noctalix_disponibilita_date" to null, // tempo disponibile per data dal calendario
          "noctalix_lingua" to null, // lingua delle sedute
      )

  // --- Stato del promemoria ---------------------------------------------------------------------

  fun ultimoBackupMs(context: Context): Long =
      context.getSharedPreferences(PREFS_STATO, Context.MODE_PRIVATE).getLong("ultimo_ms", 0L)

  /** Promemoria se ci sono notti e l'ultimo backup ha piu' di 30 giorni (o non c'e'). */
  fun serveBackup(context: Context, adessoMs: Long = System.currentTimeMillis()): Boolean {
    val ultimo = ultimoBackupMs(context)
    if (ultimo > 0 && adessoMs - ultimo < 30L * 24 * 3_600_000) return false
    return runCatching { SleepDb.get(context).listNights().isNotEmpty() }.getOrDefault(false)
  }

  // --- Backup --------------------------------------------------------------------------------------

  fun crea(context: Context, destinazione: Uri, password: CharArray, registrazioneInCorso: Boolean): EsitoBackup {
    if (registrazioneInCorso) return EsitoBackup.Errore("Una notte è in registrazione: fai il backup a registrazione finita")
    if (password.size < CifraturaBackup.PASSWORD_MINIMA) return EsitoBackup.Errore("Password troppo corta")
    val lavoro = File(context.cacheDir, "backup").apply { deleteRecursively(); mkdirs() }
    try {
      // 1. database coerente: si riversa il WAL nel file principale, poi si copia
      val sleep = SleepDb.get(context)
      sleep.writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
      val dbCopia = File(lavoro, DB)
      context.getDatabasePath(DB).copyTo(dbCopia, overwrite = true)
      val notti = sleep.listNights().size
      // 2. stato del cervello
      val cervello = File(lavoro, "cervello.json")
      val e = Cervello.chiamaFile(context, "esporta_stato", cervello)
      if (e?.get("esito")?.asString != "ok") {
        return EsitoBackup.Errore("Stato del coach non esportato: ${e?.get("errore")?.asString ?: "Python non disponibile"}")
      }
      // 3. impostazioni (senza credenziali) e tag
      val impostazioni =
          JsonObject().apply {
            add("preferenze", JsonObject().apply { for ((nome, chiavi) in PREFERENZE) add(nome, PrefsJson.esporta(context.getSharedPreferences(nome, Context.MODE_PRIVATE), chiavi)) })
            add("tag", TagDb.get(context).esporta())
          }
      val manifest =
          JsonObject().apply {
            addProperty("formato", ManifestBackup.FORMATO)
            addProperty("versione", ManifestBackup.VERSIONE)
            addProperty("app_version", BuildConfig.VERSION_NAME)
            addProperty("creato", LocalDateTime.now().withNano(0).toString())
            addProperty("notti", notti)
          }
      // 4. zip cifrato direttamente nel file scelto dall'utente
      val out = context.contentResolver.openOutputStream(destinazione, "wt") ?: return EsitoBackup.Errore("File di destinazione non scrivibile")
      out.use { o ->
        ZipOutputStream(CifraturaBackup.cifra(o, password)).use { zip ->
          fun voce(nome: String, scrivi: (java.io.OutputStream) -> Unit) {
            zip.putNextEntry(ZipEntry(nome))
            scrivi(zip)
            zip.closeEntry()
          }
          voce("manifest.json") { it.write(manifest.toString().toByteArray(Charsets.UTF_8)) }
          voce("db/$DB") { z -> dbCopia.inputStream().use { it.copyTo(z) } }
          voce("cervello.json") { z -> cervello.inputStream().use { it.copyTo(z) } }
          voce("impostazioni.json") { it.write(impostazioni.toString().toByteArray(Charsets.UTF_8)) }
        }
      }
      context.getSharedPreferences(PREFS_STATO, Context.MODE_PRIVATE).edit().putLong("ultimo_ms", System.currentTimeMillis()).apply()
      return EsitoBackup.Ok("Backup creato: $notti notti, stato del coach e impostazioni")
    } catch (e: IOException) {
      Log.w(TAG, "Backup non riuscito", e)
      return EsitoBackup.Errore("Backup non riuscito: ${e.message}")
    } finally {
      password.fill(' ')
      lavoro.deleteRecursively() // la copia in chiaro del database non resta sul telefono
    }
  }

  // --- Ripristino ----------------------------------------------------------------------------------

  /** Contenuto di un backup decifrato e controllato, pronto da applicare. */
  class Pronto internal constructor(internal val cartella: File, val manifest: ManifestBackup, val notti: Int) {
    fun scarta() {
      cartella.deleteRecursively()
    }
  }

  /**
   * Primo passo: decifra e controlla tutto, senza toccare nulla dell'app. La conferma
   * ("Le notti di questo telefono verranno sostituite") va chiesta dopo, con il numero di notti.
   */
  fun prepara(context: Context, sorgente: Uri, password: CharArray): Pair<Pronto?, String?> {
    val lavoro = File(context.cacheDir, "ripristino").apply { deleteRecursively(); mkdirs() }
    try {
      val zip = File(lavoro, "backup.zip")
      val input = context.contentResolver.openInputStream(sorgente) ?: return null to "File non leggibile"
      input.use { i -> zip.outputStream().use { o -> CifraturaBackup.leggiTutto(CifraturaBackup.decifra(i, password), o) } }
      // estrazione: solo i nomi attesi, niente percorsi arbitrari
      val ammessi = setOf("manifest.json", "db/$DB", "cervello.json", "impostazioni.json")
      ZipInputStream(zip.inputStream()).use { z ->
        while (true) {
          val e = z.nextEntry ?: break
          if (e.name !in ammessi) continue
          File(lavoro, e.name.substringAfterLast('/')).outputStream().use { z.copyTo(it) }
        }
      }
      zip.delete()
      // 1. il cervello controlla il suo file senza scrivere nulla
      val cervello = File(lavoro, "cervello.json")
      if (!cervello.exists()) {
        lavoro.deleteRecursively()
        return null to "Backup incompleto: manca lo stato del coach"
      }
      if (!supportaVerifica(context, lavoro)) {
        lavoro.deleteRecursively()
        return null to "Il cervello installato non supporta la verifica del ripristino: aggiorna i file Python dell'app e riprova"
      }
      val v = Cervello.chiamaFile(context, "importa_stato", cervello) { addProperty("verifica", true) }
      if (v?.get("esito")?.asString != "ok") {
        lavoro.deleteRecursively()
        return null to "Stato del coach non valido: ${v?.get("errore")?.asString ?: "Python non disponibile"}"
      }
      // 2. manifest, database e impostazioni
      val fileManifest = File(lavoro, "manifest.json")
      if (!fileManifest.exists()) {
        lavoro.deleteRecursively()
        return null to "Backup incompleto: manca il manifest"
      }
      val m = JsonParser.parseString(fileManifest.readText()).asJsonObject
      val manifest = ManifestBackup(m.get("formato")?.asString ?: "", m.get("versione")?.asInt ?: 0, m.get("app_version")?.asString ?: "?", m.get("creato")?.asString ?: "?")
      manifest.problema()?.let { lavoro.deleteRecursively(); return null to it }
      for (f in listOf(DB, "cervello.json", "impostazioni.json")) {
        if (!File(lavoro, f).exists()) {
          lavoro.deleteRecursively()
          return null to "Backup incompleto: manca $f"
        }
      }
      // database: leggibile, con le tabelle delle notti e non piu' nuovo di quello dell'app
      val versioneApp = SleepDb.get(context).readableDatabase.version
      val notti =
          SQLiteDatabase.openDatabase(File(lavoro, DB).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            if (db.version > versioneApp) {
              lavoro.deleteRecursively()
              return null to "Il database viene da una versione più recente di ${BuildConfig.APP_NAME}: aggiorna l'app"
            }
            db.rawQuery("SELECT COUNT(*) FROM nights", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
          }
      JsonParser.parseString(File(lavoro, "impostazioni.json").readText()).asJsonObject
      return Pronto(lavoro, manifest, notti) to null
    } catch (e: CifraturaBackup.PasswordErrata) {
      lavoro.deleteRecursively()
      return null to "Password errata o file danneggiato"
    } catch (e: CifraturaBackup.NonUnBackup) {
      lavoro.deleteRecursively()
      return null to "Questo file non è un backup di ${BuildConfig.APP_NAME}"
    } catch (e: Exception) {
      // IOException, SQLiteException, JSON non valido: il backup non si usa
      Log.w(TAG, "Backup non valido", e)
      lavoro.deleteRecursively()
      return null to "Backup non valido: ${e.message}"
    } finally {
      password.fill(' ')
    }
  }

  /**
   * Prova innocua prima di passare il backup vero: un pacchetto con un solo riepilogo fittizio
   * (1999-01-01) e "verifica": true. Un cervello senza la verifica lo scriverebbe davvero: in quel
   * caso il file fittizio compare, si cancella e il ripristino non parte (il backup vero non viene
   * mai toccato da un cervello vecchio).
   */
  private fun supportaVerifica(context: Context, lavoro: File): Boolean {
    val sonda = File(lavoro, "sonda.json")
    sonda.writeText(
        """{"formato": "biosleep-cervello", "versione": 1, "creato": "1999-01-01T00:00:00", "file": {"riepilogo_1999-01-01.json": {"v": 1, "data": "1999-01-01"}}}""")
    val traccia = File(Cervello.cartella(context), "riepilogo_1999-01-01.json")
    val esisteva = traccia.exists()
    val r = Cervello.chiamaFile(context, "importa_stato", sonda) { addProperty("verifica", true) }
    sonda.delete()
    if (!esisteva && traccia.exists()) {
      traccia.delete()
      return false
    }
    return r?.get("esito")?.asString == "ok"
  }

  /** Database (dopo il checkpoint del WAL), preferenze e tag, per tornare indietro. */
  private class Copia(val db: File, val impostazioni: JsonObject)

  private fun copiaAttuale(context: Context, cartella: File): Copia {
    SleepDb.get(context).writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
    val db = File(cartella, "prima_$DB")
    context.getDatabasePath(DB).copyTo(db, overwrite = true)
    return Copia(db, impostazioni(context))
  }

  private fun impostazioni(context: Context) =
      JsonObject().apply {
        add("preferenze", JsonObject().apply { for ((nome, chiavi) in PREFERENZE) add(nome, PrefsJson.esporta(context.getSharedPreferences(nome, Context.MODE_PRIVATE), chiavi)) })
        add("tag", TagDb.get(context).esporta())
      }

  /** Sostituisce il database con [sorgente]: chiusura, copia accanto, rinomina, via WAL e SHM. */
  private fun sostituisciDb(context: Context, sorgente: File) {
    SleepDb.get(context).close()
    val dest = context.getDatabasePath(DB)
    val tmp = File(dest.parentFile, "$DB.ripristino")
    sorgente.copyTo(tmp, overwrite = true)
    File(dest.path + "-wal").delete()
    File(dest.path + "-shm").delete()
    if (!tmp.renameTo(dest)) {
      tmp.delete()
      throw IOException("rinomina del database non riuscita")
    }
  }

  private fun applicaImpostazioni(context: Context, imp: JsonObject) {
    imp.getAsJsonObject("preferenze")?.let { pref ->
      for ((nome, chiavi) in PREFERENZE) {
        pref.getAsJsonObject(nome)?.let { PrefsJson.importa(context.getSharedPreferences(nome, Context.MODE_PRIVATE), it, chiavi) }
      }
    }
    imp.getAsJsonObject("tag")?.let { TagDb.get(context).importa(it) }
  }

  /**
   * Secondo passo, dopo la conferma (cervello, database e impostazioni sono gia' stati verificati
   * da [prepara]):
   * 1. copia dello stato attuale (database e impostazioni);
   * 2. database e impostazioni del backup;
   * 3. importa_stato del cervello. Se non riesce, lo stato del cervello e' rimasto quello di prima:
   *    si rimettono database e impostazioni dalla copia, cosi' l'app resta coerente.
   */
  fun applica(context: Context, p: Pronto, registrazioneInCorso: Boolean): EsitoBackup {
    if (registrazioneInCorso) {
      p.scarta()
      return EsitoBackup.Errore("Una notte è in registrazione: ripristina a registrazione finita")
    }
    var copia: Copia? = null
    try {
      copia = copiaAttuale(context, p.cartella)
      sostituisciDb(context, File(p.cartella, DB))
      applicaImpostazioni(context, JsonParser.parseString(File(p.cartella, "impostazioni.json").readText()).asJsonObject)
      val stato = Cervello.chiamaFile(context, "importa_stato", File(p.cartella, "cervello.json")) { addProperty("verifica", false) }
      if (stato?.get("esito")?.asString != "ok") {
        torna(context, copia)
        return EsitoBackup.Errore(
            "Ripristino annullato: lo stato del coach non è stato scritto (${stato?.get("errore")?.asString ?: "Python non disponibile"}). " +
                "Notti e impostazioni sono tornate com'erano.")
      }
      // riepiloghi della schermata del coach: dai file appena ripristinati
      val db = RiepilogoDb.get(context)
      Cervello.cartella(context).listFiles { f -> f.name.matches(Regex("""riepilogo_\d{4}-\d{2}-\d{2}\.json""")) }?.forEach { f ->
        db.salva(f.name.removePrefix("riepilogo_").removeSuffix(".json"), f.readText())
        db.segnaNotificato(f.name.removePrefix("riepilogo_").removeSuffix(".json"))
      }
      CacheSync.aggiornaInBackground(context, forza = true)
      return EsitoBackup.Ok("Ripristinate ${p.notti} notti, lo stato del coach e le impostazioni. Ricollega Intervals.icu se è un telefono nuovo.")
    } catch (e: Exception) {
      Log.w(TAG, "Ripristino non riuscito", e)
      val tornato = copia?.let { runCatching { torna(context, it) }.isSuccess } ?: true
      return EsitoBackup.Errore(
          "Ripristino non riuscito: ${e.message}. " +
              if (tornato) "Notti e impostazioni sono tornate com'erano." else "Non è stato possibile tornare allo stato di prima: riprova il ripristino.")
    } finally {
      p.scarta() // cancella anche la copia di sicurezza, che stava nella stessa cartella
    }
  }

  /** Rimette database e impostazioni com'erano prima del ripristino. */
  private fun torna(context: Context, copia: Copia) {
    sostituisciDb(context, copia.db)
    applicaImpostazioni(context, copia.impostazioni)
  }

  // --- Esportazione CSV (NON cifrata) -------------------------------------------------------------

  fun esportaCsv(context: Context, destinazione: Uri, conRr: Boolean): EsitoBackup {
    val sleep = SleepDb.get(context)
    val notti = sleep.listNights()
    if (notti.isEmpty()) return EsitoBackup.Errore("Nessuna notte da esportare")
    val righe =
        notti.map { n ->
          val s = n.summary
          val st = n.stages
          NotteCsv(
              s.startMs, s.endMs, st?.tstMin, st?.deepMin, st?.remMin, st?.lightMin, st?.wakeMin,
              s.hrAvg, s.hrMin, s.restingHr, s.rmssd, s.sdnn, s.qualityPct)
        }
    return try {
      val out = context.contentResolver.openOutputStream(destinazione, "wt") ?: return EsitoBackup.Errore("File non scrivibile")
      out.use { o ->
        if (!conRr) {
          o.write(Csv.notti(righe).toByteArray(Charsets.UTF_8))
        } else {
          // con gli RR: uno zip con notti.csv e un CSV per notte in rr/
          ZipOutputStream(o).use { zip ->
            zip.putNextEntry(ZipEntry("notti.csv"))
            zip.write(Csv.notti(righe).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (n in notti) {
              val (t, rr) = sleep.loadRr(n.sessionId)
              if (rr.isEmpty()) continue
              val giorno = java.time.Instant.ofEpochMilli(n.summary.endMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
              zip.putNextEntry(ZipEntry("rr/rr_${giorno}_${n.sessionId}.csv"))
              zip.write(Csv.rr(t, rr).toByteArray(Charsets.UTF_8))
              zip.closeEntry()
            }
          }
        }
      }
      EsitoBackup.Ok("Esportate ${notti.size} notti" + if (conRr) " con gli intervalli RR" else "")
    } catch (e: IOException) {
      EsitoBackup.Errore("Esportazione non riuscita: ${e.message}")
    }
  }
}

/** SharedPreferences <-> JSON con il tipo di ogni valore, per ripristinarle identiche. */
object PrefsJson {
  fun esporta(p: SharedPreferences, ammesse: Set<String>?): JsonObject =
      JsonObject().apply {
        for ((k, v) in p.all) {
          if (ammesse != null && k !in ammesse) continue
          val o = JsonObject()
          when (v) {
            is Boolean -> { o.addProperty("t", "b"); o.addProperty("v", v) }
            is Int -> { o.addProperty("t", "i"); o.addProperty("v", v) }
            is Long -> { o.addProperty("t", "l"); o.addProperty("v", v) }
            is Float -> { o.addProperty("t", "f"); o.addProperty("v", v) }
            is String -> { o.addProperty("t", "s"); o.addProperty("v", v) }
            is Set<*> -> { o.addProperty("t", "set"); o.add("v", com.google.gson.JsonArray().apply { v.forEach { add(it.toString()) } }) }
            else -> continue
          }
          add(k, o)
        }
      }

  fun importa(p: SharedPreferences, o: JsonObject, ammesse: Set<String>?) {
    val e = p.edit()
    // le chiavi ammesse assenti dal backup si tolgono: il risultato e' quello del backup
    for (k in p.all.keys) if ((ammesse == null || k in ammesse) && !o.has(k)) e.remove(k)
    for ((k, el) in o.entrySet()) {
      if (ammesse != null && k !in ammesse) continue
      val x = el.asJsonObject
      val v = x.get("v")
      when (x.get("t").asString) {
        "b" -> e.putBoolean(k, v.asBoolean)
        "i" -> e.putInt(k, v.asInt)
        "l" -> e.putLong(k, v.asLong)
        "f" -> e.putFloat(k, v.asFloat)
        "s" -> e.putString(k, v.asString)
        "set" -> e.putStringSet(k, v.asJsonArray.map { it.asString }.toSet())
      }
    }
    e.apply()
  }
}
