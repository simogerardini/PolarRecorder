package com.wboelens.polarrecorder.biosleep.cervello

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/** Posizione approssimativa per le previsioni del caldo: arrotondata a 2 decimali (~1 km). */
data class Posizione(val lat: Double, val lon: Double) {
  companion object {
    /** 2 decimali = ~1,1 km: la posizione precisa non entra nemmeno nel cervello. */
    fun arrotondata(lat: Double, lon: Double) = Posizione(tondo(lat), tondo(lon))

    private fun tondo(v: Double) = (v * 100).roundToLong() / 100.0
  }
}

/**
 * Ultima posizione nota del telefono, solo se l'utente ha dato la posizione approssimativa.
 * Niente GPS acceso, niente tracciamento: getLastLocation legge quella che il sistema ha gia'.
 * Fuori dal main thread (attende al massimo 5 secondi). null = nessuna regola del caldo.
 */
object PosizioneTelefono {
  fun permesso(context: Context) =
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

  fun ultima(context: Context): Posizione? {
    if (!permesso(context)) return null
    return try {
      val l = Tasks.await(LocationServices.getFusedLocationProviderClient(context).lastLocation, 5, TimeUnit.SECONDS) ?: return null
      Posizione.arrotondata(l.latitude, l.longitude)
    } catch (e: Exception) {
      // SecurityException, timeout, Play Services assenti: il coach va avanti senza caldo
      Log.i("BioSleepCoach", "Posizione non disponibile: ${e.javaClass.simpleName}")
      null
    }
  }
}
