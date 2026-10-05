package com.wboelens.polarrecorder.biosleep.watchdog

/** Esito di un controllo dello stop automatico (anche per il registro su file). */
data class AutoStopDecision(val stop: Boolean, val reason: String)

/**
 * Regola dello stop automatico del mattino, senza dipendenze Android (testabile sul PC).
 * Si ferma quando, insieme:
 *  - la registrazione dura da almeno 2 ore;
 *  - siamo nella fascia del mattino (appresa, o dalle 04:00 durante l'apprendimento);
 *  - da 10 minuti non arriva una sequenza di battiti plausibili (vedi BeatLiveness).
 * Non dipende dall'arrivo dei pacchetti: con la fascia spenta non arriva nulla, ed e' proprio
 * il caso da riconoscere. lastPacketMs serve solo per il registro.
 */
object AutoStopRule {
  const val NO_BEATS_MS = 10 * 60_000L
  const val MIN_RECORDING_MS = 2 * 3_600_000L
  const val TEST_MIN_RECORDING_MS = 15 * 60_000L

  fun decide(
      nowMs: Long,
      recordingStartMs: Long,
      lastValidBeatMs: Long,
      lastPacketMs: Long,
      isMorning: Boolean,
      testMode: Boolean = false,
  ): AutoStopDecision {
    val minRecording = if (testMode) TEST_MIN_RECORDING_MS else MIN_RECORDING_MS
    val morning = isMorning || testMode
    val recordedMin = (nowMs - recordingStartMs) / 60_000
    val lastBeat = maxOf(lastValidBeatMs, recordingStartMs)
    val silentMs = nowMs - lastBeat
    val packetAgo = if (lastPacketMs > 0) "${(nowMs - lastPacketMs) / 1000}s" else "mai"
    val facts =
        "registrazione ${recordedMin}min, ultimo battito valido ${silentMs / 1000}s fa, " +
            "ultimo pacchetto $packetAgo fa, mattino=$morning" + if (testMode) " [PROVA]" else ""
    return when {
      nowMs - recordingStartMs < minRecording -> AutoStopDecision(false, "$facts -> continua (durata minima non raggiunta)")
      !morning -> AutoStopDecision(false, "$facts -> continua (fuori fascia mattino)")
      silentMs < NO_BEATS_MS -> AutoStopDecision(false, "$facts -> continua")
      else -> AutoStopDecision(true, "$facts -> STOP (fascia tolta)")
    }
  }
}

/**
 * Decide se la fascia sta davvero misurando un cuore: non basta che segnali il contatto.
 * Un battito conta se e' tra 300 e 2000 ms, differisce meno del 15% dal battito precedente (un
 * cuore vero cambia poco da un battito all'altro) e meno del 20% dalla mediana degli ultimi 10
 * battiti accettati. La fascia e' "viva" se nell'ultimo minuto ci sono almeno 25 battiti cosi' (a 40 bpm sono 40).
 * Il rumore di una fascia tolta o appoggiata sulle lenzuola non produce una sequenza simile.
 */
class BeatLiveness {
  private val recentRr = ArrayDeque<Int>()
  private val acceptedTimes = ArrayDeque<Long>()
  private var previousRaw = -1

  /** Ultimo istante in cui la sequenza era plausibile (0 = mai). */
  var lastSustainedMs: Long = 0L
    private set

  fun reset(nowMs: Long) {
    recentRr.clear()
    acceptedTimes.clear()
    previousRaw = -1
    lastSustainedMs = nowMs
  }

  fun add(rrMs: Int, atMs: Long) {
    val prev = previousRaw
    previousRaw = rrMs
    if (rrMs !in MIN_RR..MAX_RR) return
    if (prev > 0 && kotlin.math.abs(rrMs - prev) > prev * MAX_STEP) return
    if (recentRr.size >= 3) {
      val med = recentRr.sorted()[recentRr.size / 2]
      if (kotlin.math.abs(rrMs - med) > med * MAX_DEVIATION) {
        // battito anomalo: non entra nella mediana, ma se la sequenza cambia davvero (es. risveglio)
        // la finestra si svuota e la mediana si ricostruisce
        if (acceptedTimes.isNotEmpty() && atMs - acceptedTimes.last() > WINDOW_MS) recentRr.clear()
        return
      }
    }
    recentRr.addLast(rrMs)
    if (recentRr.size > MEDIAN_OF) recentRr.removeFirst()
    acceptedTimes.addLast(atMs)
    while (acceptedTimes.isNotEmpty() && atMs - acceptedTimes.first() > WINDOW_MS) acceptedTimes.removeFirst()
    if (acceptedTimes.size >= MIN_BEATS_IN_WINDOW) lastSustainedMs = atMs
  }

  companion object {
    private const val MIN_RR = 300
    private const val MAX_RR = 2000
    private const val MAX_DEVIATION = 0.20
    private const val MAX_STEP = 0.15
    private const val MEDIAN_OF = 10
    private const val WINDOW_MS = 60_000L
    private const val MIN_BEATS_IN_WINDOW = 25
  }
}
