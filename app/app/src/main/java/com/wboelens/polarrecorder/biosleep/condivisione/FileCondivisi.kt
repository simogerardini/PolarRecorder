package com.wboelens.polarrecorder.biosleep.condivisione

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.wboelens.polarrecorder.BuildConfig
import java.io.File
import java.io.FileOutputStream

/**
 * Immagini delle card in cacheDir/condivisioni, esposte solo con FileProvider
 * (authority applicationId + ".condivisione"). Restano al massimo 10 minuti:
 * il tempo per l'app che riceve di leggerle.
 */
object FileCondivisi {
  private const val CARTELLA = "condivisioni"
  const val MAX_ETA_MS = 10 * 60_000L

  fun cartella(context: Context): File = File(context.cacheDir, CARTELLA).apply { mkdirs() }

  fun pulisci(context: Context): Int =
      Calcoli.pulisci(cartella(context), System.currentTimeMillis(), MAX_ETA_MS)

  /** Da chiamare fuori dal main thread. */
  fun salva(context: Context, immagine: Bitmap, tipo: String): Uri {
    pulisci(context)
    val bmp =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            immagine.config == Bitmap.Config.HARDWARE) {
          immagine.copy(Bitmap.Config.ARGB_8888, false)
        } else {
          immagine
        }
    val file = File(cartella(context), Calcoli.nomeFile(tipo, System.currentTimeMillis()))
    FileOutputStream(file).use { out ->
      check(bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG non scritto" }
    }
    return FileProvider.getUriForFile(context, "${context.packageName}.condivisione", file)
  }

  /** Menu di condivisione di sistema: solo l'app scelta riceve il permesso di lettura. */
  fun intent(context: Context, uri: Uri, titolo: String): Intent {
    val invio =
        Intent(Intent.ACTION_SEND).apply {
          type = "image/png"
          putExtra(Intent.EXTRA_STREAM, uri)
          clipData = ClipData.newUri(context.contentResolver, BuildConfig.APP_NAME, uri)
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    return Intent.createChooser(invio, titolo)
  }
}
