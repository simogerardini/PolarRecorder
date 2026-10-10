package com.wboelens.polarrecorder.biosleep.ponte

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Risposta HTTP essenziale. */
data class RispostaHttp(val codice: Int, val corpo: String)

/** Il trasporto e' sostituibile: nei test si simulano le risposte del ponte. */
fun interface Trasporto {
  @Throws(IOException::class)
  fun chiama(metodo: String, url: String, intestazioni: Map<String, String>, corpo: String?): RispostaHttp
}

sealed interface EsitoPonte<out T> {
  data class Ok<T>(val valore: T) : EsitoPonte<T>

  /** 404 sul collegamento: eliminato dal browser (Esci) o dal ponte. */
  data object NonTrovato : EsitoPonte<Nothing>

  data class Errore(val codice: Int?, val messaggio: String) : EsitoPonte<Nothing>
}

/** Client del ponte (https://noctalix.com/ponte/v1), secondo docs/protocollo_collegamento.md. */
class ClientePonte(
    private val base: String = BASE,
    private val trasporto: Trasporto = HttpTrasporto(),
) {
  companion object {
    const val BASE = "https://noctalix.com/ponte/v1"
  }

  private val json = mapOf("Content-Type" to "application/json; charset=utf-8")

  private fun bearer(segreto: String) = mapOf("Authorization" to "Bearer $segreto")

  private fun <T> chiama(metodo: String, percorso: String, h: Map<String, String>, corpo: String?, ok: (String) -> T): EsitoPonte<T> =
      try {
        val r = trasporto.chiama(metodo, base + percorso, h, corpo)
        when {
          r.codice in 200..299 -> EsitoPonte.Ok(ok(r.corpo))
          r.codice == 404 -> EsitoPonte.NonTrovato
          else -> EsitoPonte.Errore(r.codice, "HTTP ${r.codice}: ${r.corpo.take(200)}")
        }
      } catch (e: IOException) {
        EsitoPonte.Errore(null, "rete: ${e.message}")
      } catch (e: Json.Errore) {
        EsitoPonte.Errore(null, "risposta non valida: ${e.message}")
      }

  private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

  /** Collegamento, tempo a): la chiave pubblica del telefono. */
  fun inviaChiaveTelefono(sessione: String, pubTelefono: String): EsitoPonte<Unit> =
      chiama("POST", "/sessioni/${enc(sessione)}/telefono", json, Json.scrivi(mapOf("pub_telefono" to pubTelefono))) {}

  /** Collegamento, tempo b): registra il collegamento (solo dentro una sessione QR viva). */
  fun creaCollegamento(sessione: String, id: String, hashTelefono: String, hashBrowser: String): EsitoPonte<Unit> =
      chiama("POST", "/collegamenti", json,
          Json.scrivi(linkedMapOf("sessione" to sessione, "id" to id, "hash_telefono" to hashTelefono, "hash_browser" to hashBrowser))) {}

  fun inviaRisposta(sessione: String, busta: String): EsitoPonte<Unit> =
      chiama("POST", "/sessioni/${enc(sessione)}/risposta", json, Json.scrivi(mapOf("busta" to busta))) {}

  fun caricaCopia(id: String, segretoTelefono: String, busta: String): EsitoPonte<Unit> =
      chiama("PUT", "/c/${enc(id)}/snapshot", bearer(segretoTelefono) + ("Content-Type" to "text/plain; charset=utf-8"), busta) {}

  /** Comandi in attesa: [{"seq": n, "busta": "..."}] in ordine di seq (ponte v1). */
  fun comandi(id: String, segretoTelefono: String): EsitoPonte<List<Pair<Long, String>>> =
      chiama("GET", "/c/${enc(id)}/comandi", bearer(segretoTelefono), null) { corpo ->
        if (corpo.isBlank()) emptyList()
        else (Json.leggi(corpo) as? List<*> ?: emptyList<Any?>()).mapNotNull { x ->
          val m = x as? Map<*, *> ?: return@mapNotNull null
          val seq = (m["seq"] as? Double)?.toLong() ?: return@mapNotNull null
          val busta = m["busta"] as? String ?: return@mapNotNull null
          seq to busta
        }.sortedBy { it.first }
      }

  /** Toglie dal ponte i comandi fino a [seq] compreso (anche quelli illeggibili). */
  fun cancellaComandi(id: String, segretoTelefono: String, seq: Long): EsitoPonte<Unit> =
      chiama("DELETE", "/c/${enc(id)}/comandi?fino=$seq", bearer(segretoTelefono), null) {}

  /** Scollegamento dal telefono. Un 404 vuol dire che non c'era piu': va bene lo stesso. */
  fun scollega(id: String, segretoTelefono: String): EsitoPonte<Unit> =
      when (val r = chiama("DELETE", "/c/${enc(id)}", bearer(segretoTelefono), null) {}) {
        EsitoPonte.NonTrovato -> EsitoPonte.Ok(Unit)
        else -> r
      }
}

class HttpTrasporto : Trasporto {
  override fun chiama(metodo: String, url: String, intestazioni: Map<String, String>, corpo: String?): RispostaHttp {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
      c.requestMethod = metodo
      c.connectTimeout = 15_000
      c.readTimeout = 30_000
      intestazioni.forEach { (k, v) -> c.setRequestProperty(k, v) }
      if (corpo != null) {
        c.doOutput = true
        c.outputStream.use { it.write(corpo.toByteArray(Charsets.UTF_8)) }
      }
      val codice = c.responseCode
      val s = if (codice in 200..299) c.inputStream else c.errorStream
      return RispostaHttp(codice, s?.bufferedReader()?.use { it.readText() } ?: "")
    } finally {
      c.disconnect()
    }
  }
}
