package com.wboelens.polarrecorder.biosleep.condivisione

import com.wboelens.polarrecorder.biosleep.sonno.NotteSonno
import com.wboelens.polarrecorder.biosleep.sonno.Punteggio
import java.io.Serializable
import java.time.LocalDate

/**
 * Dati di una card condivisibile. Ogni card riceve SOLO i valori che mostra:
 * niente FC, HRV, età anagrafica, componenti dell'età, mappe o orari.
 */
sealed interface CardCondivisibile : Serializable {
  /** Prefisso del nome file, solo minuscole ASCII. */
  val tipo: String
}

/** Punteggio del sonno. [fascia] arriva già tradotta: tr(PunteggioSonno.etichetta(p.totale)). */
data class CardSonno(
    val punteggio: Int,
    val fascia: String,
    val giorno: LocalDate,
    val sonnoMin: Int,
    val profondoMin: Int,
    val remMin: Int,
    val vegliaMin: Int,
) : CardCondivisibile {
  override val tipo: String get() = "sonno"

  companion object {
    fun da(notte: NotteSonno, punteggio: Punteggio, fasciaTradotta: String) =
        CardSonno(
            punteggio = punteggio.totale,
            fascia = fasciaTradotta,
            giorno = notte.giorno,
            sonnoMin = notte.sonnoMin,
            profondoMin = notte.profondoMin,
            remMin = notte.remMin,
            vegliaMin = notte.vegliaMin,
        )
  }
}

/**
 * Età NoctaliX. [differenza] = età NoctaliX - età anagrafica: compare sulla card solo se
 * l'utente attiva l'interruttore nell'anteprima. [datiGarmin] = il calcolo ha usato dati
 * di attività arrivati da Garmin (VO2max, zone): la card aggiunge l'attribuzione.
 */
data class CardEta(
    val eta: Double,
    val decimali: Int = 1,
    val differenza: Double? = null,
    val datiGarmin: Boolean = false,
) : CardCondivisibile {
  override val tipo: String get() = "eta"
}

/**
 * Seduta svolta. [sport] (già tradotto) e [nome] dalla seduta pianificata dal coach;
 * i valori numerici dall'attività svolta; [recupero] = banda HRV o consiglio del mattino,
 * già tradotto. [datiGarmin] = l'attività può venire da Garmin: la card aggiunge
 * l'attribuzione. Se l'origine non è certa, va passato true.
 * L'utente sceglie nell'anteprima quali valori mostrare (al massimo [CampoSeduta.MASSIMO]).
 */
data class CardSeduta(
    val sport: String,
    val nome: String,
    val durataMin: Int,
    val giorno: LocalDate,
    val distanzaKm: Double? = null,
    val tss: Int? = null,
    val recupero: String? = null,
    val datiGarmin: Boolean = true,
    /** Sport.name (CORSA, BICI, NUOTO…): serve per passo o velocità e per i metri nel nuoto. */
    val sportCodice: String? = null,
    /** Tempo in movimento in secondi: rende esatto il passo. Se null si usa durataMin. */
    val durataS: Int? = null,
    val dislivelloM: Int? = null,
    val potenzaW: Int? = null,
    val fcMedia: Int? = null,
    val calorie: Int? = null,
    /** Quanto la seduta svolta ha rispettato il piano, 0-100 (compliance di Intervals.icu). */
    val pianoPct: Int? = null,
) : CardCondivisibile {
  override val tipo: String get() = "seduta"

  /** Valori che questa seduta ha davvero, nell'ordine in cui compaiono tra le scelte. */
  fun disponibili(): List<CampoSeduta> = CampoSeduta.entries.filter { campo ->
    when (campo) {
      CampoSeduta.DISTANZA -> (distanzaKm ?: 0.0) > 0.0
      CampoSeduta.RITMO -> Calcoli.ritmo(sportCodice, distanzaKm, durataS ?: durataMin * 60) != null
      CampoSeduta.TSS -> (tss ?: 0) > 0
      CampoSeduta.DISLIVELLO -> (dislivelloM ?: 0) > 0
      CampoSeduta.POTENZA -> (potenzaW ?: 0) > 0
      CampoSeduta.FC_MEDIA -> (fcMedia ?: 0) > 0
      CampoSeduta.CALORIE -> (calorie ?: 0) > 0
      CampoSeduta.PIANO -> pianoPct != null && pianoPct > 0
      CampoSeduta.RECUPERO -> !recupero.isNullOrBlank()
    }
  }
}

/** Valori facoltativi della card seduta. L'ordine è quello dei chip nell'anteprima. */
enum class CampoSeduta {
  DISTANZA, RITMO, TSS, RECUPERO, POTENZA, DISLIVELLO, PIANO, FC_MEDIA, CALORIE;

  companion object {
    const val MASSIMO = 4
    /** Accesi alla prima condivisione. La FC resta spenta: è un dato del cuore. */
    val PREDEFINITI = listOf(DISTANZA, RITMO, TSS, RECUPERO)
  }
}

/** Formati dell'immagine, in pixel. Misure dalle linee grafiche della Parte 4. */
enum class Formato(val geometria: Geometria) {
  STORIA(Geometria(w = 1080, h = 1920, alto = 250, basso = 1670, margine = 96,
      icona = 64, marchio = 44, url = 30, numero = 300, etichetta = 40,
      secEtichetta = 32, secValore = 52, claim = 30)),
  POST(Geometria(w = 1080, h = 1080, alto = 72, basso = 1008, margine = 72,
      icona = 56, marchio = 38, url = 26, numero = 220, etichetta = 32,
      secEtichetta = 26, secValore = 42, claim = 26)),
  VERTICALE(Geometria(w = 1080, h = 1350, alto = 72, basso = 1278, margine = 72,
      icona = 56, marchio = 38, url = 26, numero = 260, etichetta = 34,
      secEtichetta = 28, secValore = 46, claim = 28)),
}

/** Misure in pixel: le card si disegnano con densità 1, quindi 1 dp = 1 sp = 1 px. */
data class Geometria(
    val w: Int,
    val h: Int,
    val alto: Int,
    val basso: Int,
    val margine: Int,
    val icona: Int,
    val marchio: Int,
    val url: Int,
    val numero: Int,
    val etichetta: Int,
    val secEtichetta: Int,
    val secValore: Int,
    val claim: Int,
)
