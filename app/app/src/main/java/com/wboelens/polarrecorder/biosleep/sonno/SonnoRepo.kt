package com.wboelens.polarrecorder.biosleep.sonno

import com.wboelens.polarrecorder.biosleep.NightListItem
import com.wboelens.polarrecorder.biosleep.SleepDb
import com.wboelens.polarrecorder.biosleep.SleepStages
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Tutto quello che mostra la sezione Sonno, calcolato una volta dalle notti salvate. */
data class StatoSonno(
    val ultima: NotteSonno?,
    val punteggio: Punteggio?,
    val fabbisogno: Fabbisogno,
    val deficit: Deficit,
    val cronotipo: Cronotipo?,
    val scartoMin: Int?,
    /** Ore dormite negli ultimi 14 giorni (null = notte non registrata), dal piu' vecchio. */
    val ultimi14: List<Pair<LocalDate, Double?>>,
    val notti: Int,
)

object SonnoRepo {
  /** Notti piu' corte di 3 ore (registrazioni interrotte, pisolini) non entrano nelle metriche. */
  private const val SONNO_MINIMO_MIN = 180

  /** Una notte del database -> NotteSonno; null se non c'e' la stadiazione o e' troppo corta. */
  fun daNotte(n: NightListItem, zona: ZoneId = ZoneId.systemDefault()): NotteSonno? =
      n.stages?.let { daRegistrazione(n.summary.startMs, n.summary.endMs, it, zona) }

  /** Inizio e fine della registrazione + fasi della stadiazione -> NotteSonno. */
  fun daRegistrazione(startMs: Long, endMs: Long, st: SleepStages, zona: ZoneId = ZoneId.systemDefault()): NotteSonno? {
    if (st.tstMin < SONNO_MINIMO_MIN) return null
    fun t(ms: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zona)
    return NotteSonno(
        giorno = t(endMs).toLocalDate(), // mattina del risveglio, come la data della wellness
        inizio = t(st.sleepOnsetMs ?: startMs),
        fine = t(st.sleepEndMs ?: endMs),
        aLettoMin = ((endMs - startMs) / 60_000).toInt(),
        sonnoMin = st.tstMin,
        profondoMin = st.deepMin,
        remMin = st.remMin,
        vegliaMin = st.wakeMin,
        latenzaMin = st.sleepOnsetMs?.let { ((it - startMs) / 60_000).toInt().coerceAtLeast(0) },
    )
  }

  /** Una notte per mattina: se ce ne sono due (registrazione spezzata), conta la piu' lunga. */
  fun notti(items: List<NightListItem>, zona: ZoneId = ZoneId.systemDefault()): List<NotteSonno> =
      items.mapNotNull { daNotte(it, zona) }.groupBy { it.giorno }.values.map { g -> g.maxBy { it.sonnoMin } }.sortedBy { it.giorno }

  fun calcola(notti: List<NotteSonno>, oggi: LocalDate): StatoSonno {
    val fabbisogno = Debito.fabbisogno(notti, oggi)
    val cronotipo = OrologioBiologico.cronotipo(notti, oggi, fabbisogno.ore)
    val ultima = notti.lastOrNull { it.giorno <= oggi }
    val perGiorno = notti.associateBy { it.giorno }
    return StatoSonno(
        ultima = ultima,
        punteggio = ultima?.let { PunteggioSonno.calcola(it, fabbisogno.ore, cronotipo) },
        fabbisogno = fabbisogno,
        deficit = Debito.deficit(notti, oggi, fabbisogno.ore),
        cronotipo = cronotipo,
        scartoMin = if (ultima != null && cronotipo != null) OrologioBiologico.scarto(ultima, cronotipo) else null,
        ultimi14 = (13L downTo 0L).map { i -> oggi.minusDays(i).let { d -> d to perGiorno[d]?.let { it.sonnoMin / 60.0 } } },
        notti = notti.count { it.giorno > oggi.minusDays(90) },
    )
  }

  /** Da chiamare fuori dal main thread. */
  fun carica(db: SleepDb, oggi: LocalDate = LocalDate.now()): StatoSonno = calcola(notti(db.listNights()), oggi)
}
