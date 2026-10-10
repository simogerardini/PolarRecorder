package com.wboelens.polarrecorder.biosleep.ponte

/**
 * JSON minimo, senza dipendenze: gira uguale nell'app e nei test JVM (dove org.json di Android
 * non e' disponibile). Scrittura: le chiavi con valore null delle mappe vengono omesse (il
 * contratto dice "campo assente o null"), i null dentro le liste restano (buchi delle serie).
 * Lettura: oggetti -> Map, array -> List, numeri -> Double.
 */
object Json {
  /** JSON gia' pronto da inserire cosi' com'e' (es. i riepiloghi della Parte 3). */
  class Grezzo(val json: String)

  fun scrivi(v: Any?): String = StringBuilder().also { w(it, v) }.toString()

  private fun w(sb: StringBuilder, v: Any?) {
    when (v) {
      null -> sb.append("null")
      is Grezzo -> sb.append(v.json)
      is String -> stringa(sb, v)
      is Boolean -> sb.append(v)
      is Int, is Long, is Short, is Byte -> sb.append(v)
      is Float -> w(sb, v.toDouble())
      is Double ->
          when {
            v.isNaN() || v.isInfinite() -> sb.append("null")
            v == Math.floor(v) && Math.abs(v) < 1e15 -> sb.append(v.toLong())
            else -> sb.append(v)
          }
      is Map<*, *> -> {
        sb.append('{')
        var primo = true
        for ((k, x) in v) {
          if (x == null) continue
          if (!primo) sb.append(',')
          primo = false
          stringa(sb, k.toString())
          sb.append(':')
          w(sb, x)
        }
        sb.append('}')
      }
      is Iterable<*> -> {
        sb.append('[')
        v.forEachIndexed { i, x ->
          if (i > 0) sb.append(',')
          w(sb, x)
        }
        sb.append(']')
      }
      else -> stringa(sb, v.toString())
    }
  }

  private fun stringa(sb: StringBuilder, s: String) {
    sb.append('"')
    for (c in s) {
      when (c) {
        '"' -> sb.append("\\\"")
        '\\' -> sb.append("\\\\")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
      }
    }
    sb.append('"')
  }

  class Errore(msg: String) : Exception(msg)

  fun leggi(s: String): Any? = Lettore(s).run { val v = valore(); spazi(); if (i != s.length) throw Errore("testo dopo il JSON"); v }

  private class Lettore(val s: String) {
    var i = 0

    fun spazi() {
      while (i < s.length && s[i].isWhitespace()) i++
    }

    fun valore(): Any? {
      spazi()
      if (i >= s.length) throw Errore("JSON troncato")
      return when (val c = s[i]) {
        '{' -> oggetto()
        '[' -> lista()
        '"' -> stringa()
        't' -> parola("true", true)
        'f' -> parola("false", false)
        'n' -> parola("null", null)
        else -> if (c == '-' || c.isDigit()) numero() else throw Errore("carattere inatteso '$c' a $i")
      }
    }

    fun parola(p: String, v: Any?): Any? {
      if (!s.startsWith(p, i)) throw Errore("atteso $p a $i")
      i += p.length
      return v
    }

    fun numero(): Double {
      val inizio = i
      while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
      return s.substring(inizio, i).toDoubleOrNull() ?: throw Errore("numero non valido a $inizio")
    }

    fun stringa(): String {
      i++ // "
      val sb = StringBuilder()
      while (true) {
        if (i >= s.length) throw Errore("stringa non chiusa")
        val c = s[i++]
        when (c) {
          '"' -> return sb.toString()
          '\\' -> {
            val e = s[i++]
            when (e) {
              'n' -> sb.append('\n')
              'r' -> sb.append('\r')
              't' -> sb.append('\t')
              'b' -> sb.append('\b')
              'f' -> sb.append('\u000c')
              'u' -> {
                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                i += 4
              }
              else -> sb.append(e)
            }
          }
          else -> sb.append(c)
        }
      }
    }

    fun lista(): List<Any?> {
      i++
      val out = ArrayList<Any?>()
      spazi()
      if (s[i] == ']') return out.also { i++ }
      while (true) {
        out += valore()
        spazi()
        when (s[i++]) {
          ',' -> continue
          ']' -> return out
          else -> throw Errore("atteso , o ] a ${i - 1}")
        }
      }
    }

    fun oggetto(): Map<String, Any?> {
      i++
      val out = LinkedHashMap<String, Any?>()
      spazi()
      if (s[i] == '}') return out.also { i++ }
      while (true) {
        spazi()
        val k = stringa()
        spazi()
        if (s[i++] != ':') throw Errore("atteso : a ${i - 1}")
        out[k] = valore()
        spazi()
        when (s[i++]) {
          ',' -> continue
          '}' -> return out
          else -> throw Errore("atteso , o } a ${i - 1}")
        }
      }
    }
  }
}
