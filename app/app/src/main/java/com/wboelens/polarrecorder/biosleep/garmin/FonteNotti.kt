package com.wboelens.polarrecorder.biosleep.garmin

import android.content.Context
import com.wboelens.polarrecorder.biosleep.SleepDb
import com.wboelens.polarrecorder.biosleep.auto.NightProfileStore

/**
 * Parte 11 (test della release 1.0.9 senza fascia): da dove arrivano le notti. La scheda Notti
 * mostrava "Collega la tua fascia" a chi usa solo Garmin Connect, senza accesso all'elenco.
 * Regola: l'elenco si apre se c'e' una fonte (fascia o Garmin) o almeno una notte salvata;
 * "Collega la tua fascia" come schermata principale solo senza fonti e senza notti.
 */
object FonteNotti {
  data class Stato(val fascia: Boolean, val garmin: Boolean, val notti: Boolean) {
    val elenco: Boolean get() = fascia || garmin || notti
    val soloCollegaFascia: Boolean get() = !elenco
  }

  /** Da chiamare fuori dal main thread (legge il database delle notti). */
  fun stato(context: Context): Stato =
      Stato(
          fascia = runCatching { NightProfileStore(context).load() != null }.getOrDefault(false),
          garmin = runCatching { GarminSettings(context).collegato }.getOrDefault(false),
          notti = runCatching { SleepDb.get(context).listNights().isNotEmpty() }.getOrDefault(false))
}
