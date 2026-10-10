package com.wboelens.polarrecorder.biosleep.ponte

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.wboelens.polarrecorder.BuildConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Collegamento telefono <-> browser (docs/protocollo_collegamento.md).
 * Chiamate di rete: da eseguire fuori dal main thread.
 */
object Ponte {
  const val LAVORO_COPIA = "ponte-snapshot"
  const val LAVORO_COMANDI = "ponte-comandi"
  private const val HOST = "noctalix.com"
  private const val PERCORSO = "/collega"
  const val SCADENZA_MS = 120_000L

  /** Dati del QR: https://noctalix.com/collega#s=<sessione>&k=<pub_browser>. */
  data class LinkCollegamento(val sessione: String, val pubBrowser: String)

  /** Un collegamento in corso, in attesa della conferma del codice. */
  class InCorso(val link: LinkCollegamento, internal val chiave: ByteArray, val codice: String, val avviatoMs: Long)

  /** Link arrivato dall'App Link o dallo scanner: la schermata lo raccoglie. */
  val linkInArrivo = MutableStateFlow<LinkCollegamento?>(null)

  fun leggiLink(testo: String?): LinkCollegamento? {
    val uri = runCatching { Uri.parse(testo ?: return null) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.host != HOST || uri.path != PERCORSO) return null
    val parti = (uri.encodedFragment ?: return null).split('&').mapNotNull {
      val i = it.indexOf('=')
      if (i <= 0) null else it.substring(0, i) to Uri.decode(it.substring(i + 1))
    }.toMap()
    val s = parti["s"]?.takeIf { it.isNotBlank() } ?: return null
    val k = parti["k"]?.takeIf { it.isNotBlank() } ?: return null
    return LinkCollegamento(s, k)
  }

  /** Da MainActivity (onCreate e onNewIntent): true se l'intent era un link di collegamento. */
  fun daIntent(intent: Intent?): Boolean {
    if (intent?.action != Intent.ACTION_VIEW) return false
    val link = leggiLink(intent.dataString) ?: return false
    linkInArrivo.value = link
    return true
  }

  /** Tempo a): chiave del telefono al ponte, chiave di collegamento e codice di 6 cifre. */
  fun avvia(link: LinkCollegamento, ponte: ClientePonte = ClientePonte()): Result<InCorso> = runCatching {
    val coppia = CriptoPonte.nuovaCoppia() // non salvata
    val chiave = CriptoPonte.chiaveCollegamento(coppia.private, CriptoPonte.da64(link.pubBrowser), link.sessione)
    when (val r = ponte.inviaChiaveTelefono(link.sessione, CriptoPonte.b64(CriptoPonte.pubRaw(coppia.public)))) {
      is EsitoPonte.Ok -> InCorso(link, chiave, CriptoPonte.codiceVerifica(chiave), System.currentTimeMillis())
      EsitoPonte.NonTrovato -> throw IllegalStateException("QR scaduto: generane uno nuovo nel browser")
      is EsitoPonte.Errore -> throw IllegalStateException(r.messaggio)
    }
  }

  /** Tempo b), dopo che l'utente ha confermato che il codice coincide. */
  fun conferma(context: Context, c: InCorso, ponte: ClientePonte = ClientePonte()): Result<Unit> = runCatching {
    check(System.currentTimeMillis() - c.avviatoMs < SCADENZA_MS) { "QR scaduto: generane uno nuovo nel browser" }
    val (atleta, token, scope) =
        Parte3Ponte.fonti.intervals(context)
            ?: throw IllegalStateException("Collega Intervals.icu con il login prima di collegare un browser")
    val id = CriptoPonte.b64(CriptoPonte.byteCasuali(16))
    val segretoTel = CriptoPonte.b64(CriptoPonte.byteCasuali(32))
    val segretoBr = CriptoPonte.b64(CriptoPonte.byteCasuali(32))
    val chiaveDati = CriptoPonte.b64(CriptoPonte.byteCasuali(32))
    ponte.creaCollegamento(c.link.sessione, id, CriptoPonte.hashSegreto(segretoTel), CriptoPonte.hashSegreto(segretoBr)).ok()
    val pacchetto =
        linkedMapOf(
            "v" to 1, "id_collegamento" to id, "segreto_browser" to segretoBr, "chiave_dati" to chiaveDati,
            "intervals" to linkedMapOf("athlete_id" to atleta, "access_token" to token, "scope" to scope),
            "telefono" to linkedMapOf("nome" to "${Build.MANUFACTURER} ${Build.MODEL}", "app_versione" to BuildConfig.VERSION_NAME))
    val busta = CriptoPonte.chiudi(c.chiave, c.link.sessione, "collegamento", Json.scrivi(pacchetto))
    ponte.inviaRisposta(c.link.sessione, busta).ok()
    ArchivioCollegamenti(context).aggiungi(Collegamento(id, segretoTel, chiaveDati, System.currentTimeMillis()))
    richiediCopia(context) // la prima copia subito
    pianifica(context)
  }

  /** Scollega dal telefono: avvisa il ponte (il browser ricevera' 404) e dimentica i segreti. */
  fun scollega(context: Context, c: Collegamento, ponte: ClientePonte = ClientePonte()): Result<Unit> = runCatching {
    ponte.scollega(c.id, c.segretoTelefono).ok()
    ArchivioCollegamenti(context).rimuovi(c.id)
    pianifica(context)
  }

  private fun EsitoPonte<Unit>.ok() {
    when (this) {
      is EsitoPonte.Ok -> Unit
      EsitoPonte.NonTrovato -> throw IllegalStateException("QR scaduto o collegamento non trovato")
      is EsitoPonte.Errore -> throw IllegalStateException(messaggio)
    }
  }

  private val rete = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

  /**
   * Nuova copia per i browser collegati: dopo l'invio della notte, dopo il coach, all'apertura
   * dell'app, dopo i comandi. Senza collegamenti non fa nulla.
   */
  fun richiediCopia(context: Context) {
    if (ArchivioCollegamenti(context).elenco().isEmpty()) return
    WorkManager.getInstance(context)
        .enqueueUniqueWork(LAVORO_COPIA, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CopiaWorker>().setConstraints(rete).build())
  }

  /** Controllo dei comandi ogni 15 minuti, solo se esiste almeno un collegamento. */
  fun pianifica(context: Context) {
    val wm = WorkManager.getInstance(context)
    if (ArchivioCollegamenti(context).elenco().isEmpty()) {
      wm.cancelUniqueWork(LAVORO_COMANDI)
      return
    }
    wm.enqueueUniquePeriodicWork(LAVORO_COMANDI, ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<ComandiWorker>(15, TimeUnit.MINUTES).setConstraints(rete).build())
  }

  /** All'apertura dell'app: comandi subito e copia aggiornata. */
  fun allApertura(context: Context) {
    if (ArchivioCollegamenti(context).elenco().isEmpty()) return
    WorkManager.getInstance(context)
        .enqueueUniqueWork("$LAVORO_COMANDI-ora", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ComandiWorker>().setConstraints(rete).build())
    richiediCopia(context)
  }
}
