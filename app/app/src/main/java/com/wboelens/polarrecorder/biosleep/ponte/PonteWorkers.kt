package com.wboelens.polarrecorder.biosleep.ponte

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Carica la copia cifrata per ogni browser collegato (lavoro unico "ponte-snapshot", REPLACE). */
class CopiaWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
  override suspend fun doWork(): Result {
    val archivio = ArchivioCollegamenti(applicationContext)
    val collegamenti = archivio.elenco()
    if (collegamenti.isEmpty()) return Result.success()
    val esiti = archivio.esitiInAttesa()
    val json = CopiaSnapshot.costruisci(FontiSnapshot.raccogli(applicationContext, esiti))
    val ponte = ClientePonte()
    var riprova = false
    for (c in collegamenti) {
      val busta = CriptoPonte.chiudi(CriptoPonte.da64(c.chiaveDati), c.id, "snapshot", json)
      when (val r = ponte.caricaCopia(c.id, c.segretoTelefono, busta)) {
        is EsitoPonte.Ok -> archivio.copiaInviata(c.id, System.currentTimeMillis(), busta.length)
        EsitoPonte.NonTrovato -> archivio.rimuovi(c.id) // il browser ha fatto Esci
        is EsitoPonte.Errore -> {
          Log.w(TAG, "Copia non caricata: ${r.messaggio}")
          riprova = true
        }
      }
    }
    Log.i(TAG, "Copia: JSON ${json.length / 1024} KB, busta ${CriptoPonte.gzip(json.toByteArray()).size / 1024} KB compressa")
    if (!riprova) archivio.togliEsiti(esiti)
    Ponte.pianifica(applicationContext)
    return if (riprova && runAttemptCount < MAX_TENTATIVI) Result.retry() else Result.success()
  }

  companion object {
    private const val TAG = "NoctalixPonte"
    private const val MAX_TENTATIVI = 5
  }
}

/** Legge e applica i comandi del web; l'esito va nella copia successiva. */
class ComandiWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
  override suspend fun doWork(): Result {
    val archivio = ArchivioCollegamenti(applicationContext)
    val collegamenti = archivio.elenco()
    if (collegamenti.isEmpty()) {
      Ponte.pianifica(applicationContext) // toglie il controllo periodico
      return Result.success()
    }
    val ponte = ClientePonte()
    var applicati = 0
    for (c in collegamenti) {
      when (val r = ponte.comandi(c.id, c.segretoTelefono)) {
        EsitoPonte.NonTrovato -> archivio.rimuovi(c.id)
        is EsitoPonte.Errore -> Log.w(TAG, "Comandi non letti: ${r.messaggio}")
        is EsitoPonte.Ok -> {
          if (r.valore.isEmpty()) continue
          val esiti = mutableListOf<EsitoComando>()
          for ((_, busta) in r.valore) {
            val comando =
                runCatching { Comandi.leggi(CriptoPonte.apri(CriptoPonte.da64(c.chiaveDati), c.id, "comando", busta)) }.getOrNull()
                    ?: continue // illeggibile: si toglie comunque dal ponte, sotto
            esiti += Comandi.applica(comando) { Parte3Ponte.fonti.applica(applicationContext, it) }
          }
          archivio.aggiungiEsiti(esiti)
          applicati += esiti.size
          // Fino all'ultimo seq letto, anche se illeggibile: altrimenti tornerebbe ogni 15 minuti
          ponte.cancellaComandi(c.id, c.segretoTelefono, r.valore.last().first)
        }
      }
    }
    if (applicati > 0) Ponte.richiediCopia(applicationContext)
    return Result.success()
  }

  companion object {
    private const val TAG = "NoctalixPonte"
  }
}
