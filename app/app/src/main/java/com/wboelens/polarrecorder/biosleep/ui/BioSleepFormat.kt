package com.wboelens.polarrecorder.biosleep.ui

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Stato di caricamento di una schermata: in corso, pronto, errore. */
sealed interface LoadState<out T> {
  data object Loading : LoadState<Nothing>

  data class Ready<T>(val data: T) : LoadState<T>

  data class Error(val message: String) : LoadState<Nothing>
}

private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN)
private val HOUR_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.ITALIAN)

fun Long.toLocalTime(): ZonedDateTime = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())

/** Titolo della notte: il giorno del risveglio, es. "Mercoledì 30 settembre". */
fun nightTitle(endMs: Long): String =
    endMs.toLocalTime().format(DAY_FORMAT).replaceFirstChar { it.titlecase(Locale.ITALIAN) }

/** Es. "22:18 → 06:40 · 8,4 h". */
fun nightTimes(startMs: Long, endMs: Long): String {
  val hours = (endMs - startMs) / 3_600_000.0
  return "${startMs.toLocalTime().format(HOUR_FORMAT)} → " +
      "${endMs.toLocalTime().format(HOUR_FORMAT)} · ${fmt(hours, 1)} h"
}

fun hourLabel(ms: Long): String = ms.toLocalTime().format(HOUR_FORMAT)

private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("dd/MM 'alle' HH:mm", Locale.ITALIAN)

/** Es. "01/10 alle 06:46". */
fun dateTimeLabel(ms: Long): String = ms.toLocalTime().format(DATE_TIME_FORMAT)

/** Numero con virgola italiana; "–" se il valore manca. */
fun fmt(value: Double?, decimals: Int = 0): String =
    if (value == null || value.isNaN()) "–" else String.format(Locale.ITALY, "%.${decimals}f", value)

/** Minuti in formato "7 h 12'" (o "45'" sotto l'ora). */
fun hm(minutes: Int): String =
    if (minutes >= 60) "${minutes / 60} h ${"%02d".format(minutes % 60)}'" else "$minutes'"
