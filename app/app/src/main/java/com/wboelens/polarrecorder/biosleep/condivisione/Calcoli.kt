package com.wboelens.polarrecorder.biosleep.condivisione

import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Logica pura delle card, senza Android: coperta da CalcoliCondivisioneTest. */
object Calcoli {

  /** Quote della barra delle fasi, nell'ordine profondo, leggero, REM, veglia. */
  data class Fasi(val profondo: Float, val leggero: Float, val rem: Float, val veglia: Float)

  /** Il leggero è il sonno meno profondo e REM. null se non c'è nulla da disegnare. */
  fun fasi(sonnoMin: Int, profondoMin: Int, remMin: Int, vegliaMin: Int): Fasi? {
    val p = max(0, profondoMin)
    val r = max(0, remMin)
    val v = max(0, vegliaMin)
    val l = max(0, sonnoMin - p - r)
    val tot = (p + l + r + v).toFloat()
    if (tot <= 0f) return null
    return Fasi(p / tot, l / tot, r / tot, v / tot)
  }

  /** Minuti in ore e minuti. */
  fun oreMinuti(minuti: Int): Pair<Int, Int> {
    val m = max(0, minuti)
    return m / 60 to m % 60
  }

  /** Arrotondamento a metà in su, lo stesso usato per mostrare e per classificare. */
  fun arrotonda(x: Double, decimali: Int): BigDecimal =
      BigDecimal.valueOf(x).setScale(max(0, decimali), RoundingMode.HALF_UP)

  fun numero(x: Double, decimali: Int, locale: Locale): String {
    val f = NumberFormat.getNumberInstance(locale)
    f.minimumFractionDigits = max(0, decimali)
    f.maximumFractionDigits = max(0, decimali)
    f.isGroupingUsed = false
    return f.format(arrotonda(x, decimali))
  }

  enum class Differenza { PIU_GIOVANE, PIU_VECCHIO, UGUALE }

  /** Uguale quando la differenza arrotondata come sulla card vale zero. */
  fun differenza(d: Double, decimali: Int): Differenza {
    val a = arrotonda(d, decimali)
    return when {
      a.signum() == 0 -> Differenza.UGUALE
      a.signum() < 0 -> Differenza.PIU_GIOVANE
      else -> Differenza.PIU_VECCHIO
    }
  }

  fun differenzaAssoluta(d: Double, decimali: Int, locale: Locale): String =
      numero(abs(d), decimali, locale)

  /** Metri sotto 1 km e sempre nel nuoto ("850 m", "1500 m"), altrimenti km con un decimale. */
  fun distanza(km: Double, locale: Locale, sportCodice: String? = null): Pair<Boolean, String> =
      if (km < 1.0 || sportCodice == "NUOTO") true to numero(km * 1000.0, 0, locale)
      else false to numero(km, 1, locale)

  enum class TipoRitmo { PASSO_KM, PASSO_100M, VELOCITA }
  data class Ritmo(val tipo: TipoRitmo, val testo: String)

  /**
   * Corsa: passo min/km. Nuoto: passo per 100 m. Bici: velocità km/h.
   * Altri sport (palestra, multisport…) o dati mancanti: null.
   */
  fun ritmo(sportCodice: String?, distanzaKm: Double?, durataS: Int, locale: Locale = Locale.ROOT): Ritmo? {
    val km = distanzaKm ?: return null
    if (km <= 0.0 || durataS <= 0) return null
    return when (sportCodice) {
      "CORSA" -> Ritmo(TipoRitmo.PASSO_KM, minSec(durataS / km))
      "NUOTO" -> Ritmo(TipoRitmo.PASSO_100M, minSec(durataS / (km * 10.0)))
      "BICI" -> Ritmo(TipoRitmo.VELOCITA, numero(km / (durataS / 3600.0), 1, locale))
      else -> null
    }
  }

  /** Secondi in m:ss, arrotondati al secondo. */
  fun minSec(secondi: Double): String {
    val s = Math.round(secondi).toInt()
    return "%d:%02d".format(Locale.ROOT, s / 60, s % 60)
  }

  /** "DISTANZA,TSS" -> campi, ignorando nomi sconosciuti e doppioni. */
  fun campiDaTesto(testo: String?): List<CampoSeduta> =
      testo.orEmpty().split(',').mapNotNull { n ->
        CampoSeduta.entries.firstOrNull { it.name == n.trim() }
      }.distinct()

  /** Scelta salvata ("DISTANZA,TSS"), filtrata su ciò che la seduta ha; vuota -> predefiniti. */
  fun campiIniziali(salvati: String?, disponibili: List<CampoSeduta>): List<CampoSeduta> {
    val scelti = campiDaTesto(salvati).filter { it in disponibili }.take(CampoSeduta.MASSIMO)
    if (scelti.isNotEmpty()) return scelti
    val base = CampoSeduta.PREDEFINITI.filter { it in disponibili }
    val altri = disponibili.filter { it !in base && it != CampoSeduta.FC_MEDIA }
    return (base + altri).take(CampoSeduta.MASSIMO)
  }

  /** Tocco su un chip: toglie, oppure aggiunge se c'è posto. Ordine sempre quello dei chip. */
  fun alterna(scelti: List<CampoSeduta>, campo: CampoSeduta): List<CampoSeduta> = when {
    campo in scelti -> scelti - campo
    scelti.size >= CampoSeduta.MASSIMO -> scelti
    else -> CampoSeduta.entries.filter { it in scelti || it == campo }
  }

  fun nomeFile(tipo: String, istanteMs: Long): String {
    val t = Normalizer.normalize(tipo, Normalizer.Form.NFD).lowercase(Locale.ROOT).filter { it in 'a'..'z' }.ifEmpty { "card" }
    return "noctalix_${t}_$istanteMs.png"
  }

  /** Cancella i file più vecchi di [maxEtaMs]. Restituisce quanti ne ha cancellati. */
  fun pulisci(cartella: File, oraMs: Long, maxEtaMs: Long): Int {
    val vecchi = cartella.listFiles()?.filter { it.isFile && oraMs - it.lastModified() > maxEtaMs }
        ?: return 0
    return vecchi.count { it.delete() }
  }
}
