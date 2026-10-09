package com.wboelens.polarrecorder.biosleep.riepilogo

import android.content.Context
import com.wboelens.polarrecorder.biosleep.ui.allenamento.DateIt
import java.text.NumberFormat
import java.time.LocalDate

/** Testo del riepilogo -> testo nella lingua dell'app, tramite il codice del messaggio. */
object TraduzioneMessaggi {
  fun testo(context: Context, testo: String): String {
    val m = Messaggi.per(testo) ?: return testo
    val res = context.resources
    return Messaggi.traduci(
        m,
        stringa = { nome, args ->
          val id = res.getIdentifier(nome, "string", context.packageName)
          // sempre con gli argomenti, anche vuoti: cosi' "%%" diventa "%"
          if (id == 0) null else res.getString(id, *args)
        },
        data = { DateIt.breve(LocalDate.parse(it)) },
        numero = { numero(it) })
  }

  /** "12.3" -> "12,3" in italiano, "12.3" in inglese: stesse cifre decimali del cervello. */
  fun numero(x: String): String {
    val d = x.toDoubleOrNull() ?: return x
    val decimali = if ('.' in x) x.substringAfter('.').length else 0
    return NumberFormat.getNumberInstance().apply {
      minimumFractionDigits = decimali
      maximumFractionDigits = decimali
      isGroupingUsed = false
    }.format(d)
  }
}
