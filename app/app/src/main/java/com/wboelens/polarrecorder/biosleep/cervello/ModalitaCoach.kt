package com.wboelens.polarrecorder.biosleep.cervello

import android.content.Context
import androidx.work.WorkManager
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Coach di allenamento acceso o spento. Spento ("solo biometria"): il coach non gira (CoachWorker e
 * RipianificaWorker escono subito), niente sedute su Intervals.icu, niente schermate di allenamento;
 * restano notte, sonno, recupero ed eta' biologica. Acceso di default: per chi lo usa gia' non
 * cambia nulla.
 */
object ModalitaCoach {
  private const val PREF = "noctalix_modalita"
  private const val K_COACH = "coach_attivo"
  private val stato = MutableStateFlow<Boolean?>(null)

  fun attivo(context: Context): Boolean =
      stato.value ?: context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(K_COACH, true).also { stato.value = it }

  fun flusso(context: Context): StateFlow<Boolean?> {
    attivo(context)
    return stato
  }

  fun imposta(context: Context, acceso: Boolean) {
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(K_COACH, acceso).apply()
    stato.value = acceso
    if (!acceso) {
      // i lavori gia' in coda escono comunque all'avvio; si annullano per non lasciare attese inutili
      val wm = WorkManager.getInstance(context)
      wm.cancelUniqueWork(RipianificaWorker.NOME)
      val oggi = LocalDate.now()
      for (d in listOf(oggi.minusDays(1), oggi, oggi.plusDays(1))) wm.cancelUniqueWork("coach-$d")
    }
  }
}
