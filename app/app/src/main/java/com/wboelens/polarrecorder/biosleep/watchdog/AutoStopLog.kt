package com.wboelens.polarrecorder.biosleep.watchdog

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Registro su file della notte (files/biosleep_autostop.log): avvio, ogni controllo dello stop,
 * stop, chiusura e analisi, recuperi. A differenza del logcat non viene sovrascritto dal resto
 * del telefono. Oltre 256 KB il file passa a .1 (si tiene una copia precedente).
 * Recupero dal Mac:  adb exec-out run-as it.biosleep.recorder cat files/biosleep_autostop.log
 */
object AutoStopLog {
  private const val FILE = "biosleep_autostop.log"
  private const val MAX_BYTES = 256 * 1024
  private val FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

  /**
   * Modalita' di prova (solo da adb, vedi test_stop_automatico.sh): basta una registrazione di
   * 15 minuti, la fascia del mattino non conta e la notte di prova NON viene inviata a
   * Intervals.icu (non deve sovrascrivere la notte vera ne' avviare il coach).
   */
  fun testMode(context: Context): Boolean = File(context.applicationContext.filesDir, TEST_FLAG).exists()

  private const val TEST_FLAG = "autostop_test"

  @Synchronized
  fun write(context: Context, message: String) {
    val line = "${LocalDateTime.now().format(FMT)} $message"
    Log.i("BioSleepStop", message)
    try {
      val f = File(context.applicationContext.filesDir, FILE)
      if (f.length() > MAX_BYTES) f.renameTo(File(f.path + ".1"))
      f.appendText(line + "\n")
    } catch (e: IOException) {
      Log.w("BioSleepStop", "Registro non scrivibile: ${e.message}")
    }
  }
}
