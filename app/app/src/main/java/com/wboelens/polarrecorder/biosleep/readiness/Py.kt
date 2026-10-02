package com.wboelens.polarrecorder.biosleep.readiness

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Un numero come lo vede Python: valore + "era un intero" (50 e 50.0 sono diversi per sum()).
 * Serve perche' la banda dell'app deve coincidere al decimale con intervals_coach.py.
 */
data class PyNum(val v: Double, val isInt: Boolean) {
  companion object {
    fun of(x: Double) = PyNum(x, false)

    fun ofInt(x: Long) = PyNum(x.toDouble(), true)
  }
}

/** Le tre operazioni di Python in cui Kotlin, scritto "normale", darebbe numeri diversi. */
object Py {

  /**
   * sum() di CPython >= 3.12: interi sommati esatti finche' arriva il primo float, poi
   * somma compensata di Neumaier sui float; gli interi incontrati dopo si sommano senza
   * compensazione (Python/bltinmodule.c, builtin_sum_impl).
   */
  fun sum(xs: List<PyNum>): PyNum {
    var iResult = 0L
    var i = 0
    while (i < xs.size && xs[i].isInt) {
      iResult += xs[i].v.toLong()
      i++
    }
    if (i == xs.size) return PyNum(iResult.toDouble(), true)
    var f = iResult.toDouble() + xs[i].v
    i++
    var c = 0.0
    while (i < xs.size) {
      val x = xs[i]
      if (x.isInt) {
        f += x.v
      } else {
        val t = f + x.v
        c += if (Math.abs(f) >= Math.abs(x.v)) (f - t) + x.v else (x.v - t) + f
        f = t
      }
      i++
    }
    if (c != 0.0 && c.isFinite()) f += c
    return PyNum(f, false)
  }

  fun sumF(xs: List<Double>): Double = sum(xs.map { PyNum(it, false) }).v

  /** round(x, n) di Python: arrotondamento esatto del valore binario, pari sui pareggi. */
  fun round(x: Double, n: Int): Double {
    val r = BigDecimal(x).setScale(n, RoundingMode.HALF_EVEN).toPlainString().toDouble()
    return if (r == 0.0) Math.copySign(0.0, x) else r // round(-0.04, 1) e' -0.0 in Python
  }

  private val DATA = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""")

  /** datetime.strptime(str(d)[:10], "%Y-%m-%d"); null dove Python solleverebbe ValueError. */
  fun parseDate(s: String?): LocalDate? {
    if (s == null) return null
    val m = DATA.matchEntire(s.take(10)) ?: return null
    return try {
      LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    } catch (e: java.time.DateTimeException) {
      null
    }
  }
}
