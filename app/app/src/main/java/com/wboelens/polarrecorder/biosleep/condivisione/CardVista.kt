package com.wboelens.polarrecorder.biosleep.condivisione

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wboelens.polarrecorder.BuildConfig
import com.wboelens.polarrecorder.R
import com.wboelens.polarrecorder.ui.theme.CIFRE_TABULARI
import com.wboelens.polarrecorder.ui.theme.Inter
import com.wboelens.polarrecorder.ui.theme.NoctalixColori
import com.wboelens.polarrecorder.ui.theme.NoctalixTheme
import com.wboelens.polarrecorder.ui.theme.SpaceGrotesk
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private const val DOMINIO = "noctalix.com"

// Colori della barra delle fasi (linee grafiche Parte 4)
private val FASE_PROFONDO = Color(0xFF256B87)
private val FASE_LEGGERO = Color(0xFF39A5C8)
private val FASE_REM = Color(0xFF8FD3E8)
private val FASE_VEGLIA = Color(0xFF7F97AA)

/** Scelte fatte dall'utente nell'anteprima. */
data class Opzioni(val mostraDifferenza: Boolean = false, val mostraFasi: Boolean = false)

/**
 * Card a piena risoluzione: occupa g.w x g.h pixel. Si disegna con densità 1 e fontScale 1,
 * così le misure in pixel delle linee grafiche valgono così come sono, e la dimensione
 * del testo scelta nelle impostazioni del telefono non deforma l'immagine.
 */
@Composable
fun CardVista(card: CardCondivisibile, formato: Formato, opzioni: Opzioni, logo: ImageBitmap) {
  val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
  NoctalixTheme(scuro = true) {
    CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
      val g = formato.geometria
      when (card) {
        is CardSonno -> Cornice(g, logo, stringResource(R.string.card_claim), null) {
          ContenutoSonno(card, g, locale, opzioni)
        }
        is CardEta -> Cornice(
            g, logo, stringResource(R.string.card_claim),
            if (card.datiGarmin) stringResource(R.string.card_garmin) else null,
        ) {
          ContenutoEta(card, g, locale, opzioni)
        }
        is CardSeduta -> Cornice(
            g, logo, null,
            if (card.datiGarmin) stringResource(R.string.card_garmin) else null,
        ) {
          ContenutoSeduta(card, g, locale)
        }
      }
    }
  }
}

@Composable
private fun Cornice(
    g: Geometria,
    logo: ImageBitmap,
    claim: String?,
    nota: String?,
    contenuto: @Composable ColumnScope.() -> Unit,
) {
  Box(Modifier.fillMaxSize().background(NoctalixColori.bg)) {
    Nuvola(g)
    Column(
        Modifier.fillMaxSize()
            .padding(start = g.margine.dp, end = g.margine.dp, top = g.alto.dp,
                bottom = (g.h - g.basso).dp),
    ) {
      Marchio(g, logo)
      Spacer(Modifier.weight(1f))
      contenuto()
      Spacer(Modifier.weight(1f))
      if (nota != null) {
        Text(nota, Modifier.fillMaxWidth(), style = testo(Inter, FontWeight.Medium, g.url,
            NoctalixColori.muted), textAlign = TextAlign.Center)
        Spacer(Modifier.height((g.url / 2).dp))
      }
      if (claim != null) {
        Text(claim, Modifier.fillMaxWidth(), style = testo(Inter, FontWeight.Medium, g.claim,
            NoctalixColori.soft), textAlign = TextAlign.Center)
        Spacer(Modifier.height((g.url / 2).dp))
      }
      Text(DOMINIO, Modifier.fillMaxWidth(), style = testo(Inter, FontWeight.Medium, g.url,
          NoctalixColori.muted), textAlign = TextAlign.Center)
    }
  }
}

@Composable
private fun Marchio(g: Geometria, logo: ImageBitmap) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Image(logo, contentDescription = null,
        modifier = Modifier.size(g.icona.dp).clip(RoundedCornerShape((g.icona * 0.22f).dp)))
    Spacer(Modifier.width((g.icona / 4).dp))
    Text(BuildConfig.APP_NAME, style = testo(SpaceGrotesk, FontWeight.Bold, g.marchio,
        NoctalixColori.text))
  }
}

/** Nuvola di cerchi nell'angolo in basso a destra, fuori dalle righe di testo. */
@Composable
private fun Nuvola(g: Geometria) {
  // (distanza dal bordo destro, distanza dal bordo basso, raggio) a 1080 px di larghezza
  val cerchi = listOf(
      Triple(110f, 120f, 70f), Triple(200f, 70f, 46f), Triple(60f, 230f, 40f),
      Triple(180f, 190f, 28f), Triple(40f, 60f, 30f),
  )
  Canvas(Modifier.fillMaxSize()) {
    val k = size.width / 1080f
    cerchi.forEachIndexed { i, (dx, dy, r) ->
      val c = Offset(size.width - dx * k, size.height - dy * k)
      if (i % 2 == 0) {
        drawCircle(NoctalixColori.azure.copy(alpha = 0.35f), r * k, c, style = Stroke(3f * k))
      } else {
        drawCircle(NoctalixColori.azureLo.copy(alpha = 0.35f), r * k, c)
      }
    }
  }
}

@Composable
private fun NumeroGrande(testoNumero: String, g: Geometria, unita: String? = null) {
  Row(
      Modifier.drawBehind {
        val c = Offset(g.numero * 0.6f, size.height / 2f)
        val r = g.numero * 1.1f
        drawCircle(
            Brush.radialGradient(listOf(NoctalixColori.azureLo.copy(alpha = 0.25f),
                Color.Transparent), c, r),
            r, c,
        )
      },
  ) {
    Text(testoNumero, Modifier.alignByBaseline(),
        style = testo(SpaceGrotesk, FontWeight.Bold, g.numero, NoctalixColori.text, cifre = true)
            .copy(lineHeight = g.numero.sp,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center,
                    LineHeightStyle.Trim.Both)))
    if (unita != null) {
      Spacer(Modifier.width((g.secValore / 3).dp))
      Text(unita, Modifier.alignByBaseline(),
          style = testo(Inter, FontWeight.Medium, g.secValore, NoctalixColori.soft))
    }
  }
}

@Composable
private fun Etichetta(t: String, g: Geometria) {
  Text(t, style = testo(Inter, FontWeight.Medium, g.etichetta, NoctalixColori.soft))
  Spacer(Modifier.height((g.etichetta / 2).dp))
}

@Composable
private fun Valori(g: Geometria, valori: List<Pair<String, String>>) {
  Row(horizontalArrangement = Arrangement.spacedBy((g.secValore * 1.4f).dp)) {
    valori.take(3).forEach { (nome, valore) ->
      Column {
        Text(nome, style = testo(Inter, FontWeight.Medium, g.secEtichetta, NoctalixColori.soft))
        Text(valore, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = testo(Inter, FontWeight.SemiBold, g.secValore, NoctalixColori.text,
                cifre = true))
      }
    }
  }
}

@Composable
private fun ColumnScope.ContenutoSonno(c: CardSonno, g: Geometria, locale: Locale, o: Opzioni) {
  Etichetta(stringResource(R.string.card_sonno_etichetta, data(c.giorno, locale)), g)
  NumeroGrande(c.punteggio.coerceIn(0, 100).toString(), g)
  Text(c.fascia, style = testo(SpaceGrotesk, FontWeight.Medium, g.secValore,
      NoctalixColori.azure))
  Spacer(Modifier.height(g.secValore.dp))
  val (h, m) = Calcoli.oreMinuti(c.sonnoMin)
  Valori(g, listOf(stringResource(R.string.card_sonno_durata) to
      stringResource(R.string.card_durata_hm, h, m)))
  if (!o.mostraFasi) return
  val fasi = Calcoli.fasi(c.sonnoMin, c.profondoMin, c.remMin, c.vegliaMin) ?: return
  Spacer(Modifier.height(g.secValore.dp))
  BarraFasi(fasi)
  Spacer(Modifier.height((g.secEtichetta / 2).dp))
  Row(horizontalArrangement = Arrangement.spacedBy(g.secEtichetta.dp)) {
    VoceLegenda(FASE_PROFONDO, stringResource(R.string.card_fase_profondo), g)
    VoceLegenda(FASE_LEGGERO, stringResource(R.string.card_fase_leggero), g)
    VoceLegenda(FASE_REM, stringResource(R.string.card_fase_rem), g)
    VoceLegenda(FASE_VEGLIA, stringResource(R.string.card_fase_veglia), g)
  }
}

@Composable
private fun BarraFasi(f: Calcoli.Fasi) {
  Canvas(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(14.dp))) {
    var x = 0f
    listOf(f.profondo to FASE_PROFONDO, f.leggero to FASE_LEGGERO, f.rem to FASE_REM,
        f.veglia to FASE_VEGLIA).forEach { (quota, colore) ->
      val w = quota * size.width
      if (w > 0f) drawRect(colore, Offset(x, 0f), Size(w, size.height))
      x += w
    }
  }
}

@Composable
private fun VoceLegenda(colore: Color, nome: String, g: Geometria) {
  val lato = (g.secEtichetta * 0.6f).dp
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(lato).clip(CircleShape).background(colore))
    Spacer(Modifier.width((g.secEtichetta / 3).dp))
    Text(nome, style = testo(Inter, FontWeight.Medium, (g.secEtichetta * 0.85f).toInt(),
        NoctalixColori.soft))
  }
}

@Composable
private fun ColumnScope.ContenutoEta(c: CardEta, g: Geometria, locale: Locale, o: Opzioni) {
  Etichetta(stringResource(R.string.card_eta_etichetta, BuildConfig.APP_NAME), g)
  NumeroGrande(Calcoli.numero(c.eta, c.decimali, locale), g,
      stringResource(R.string.card_eta_anni))
  val d = c.differenza
  if (o.mostraDifferenza && d != null) {
    Spacer(Modifier.height((g.secValore / 2).dp))
    val testoDiff = when (Calcoli.differenza(d, c.decimali)) {
      Calcoli.Differenza.UGUALE -> stringResource(R.string.card_eta_uguale)
      Calcoli.Differenza.PIU_GIOVANE -> stringResource(R.string.card_eta_piu_giovane,
          Calcoli.differenzaAssoluta(d, c.decimali, locale))
      Calcoli.Differenza.PIU_VECCHIO -> stringResource(R.string.card_eta_piu_vecchio,
          Calcoli.differenzaAssoluta(d, c.decimali, locale))
    }
    Text(testoDiff, style = testo(SpaceGrotesk, FontWeight.Medium, g.secValore,
        NoctalixColori.azure))
  }
}

@Composable
private fun ColumnScope.ContenutoSeduta(c: CardSeduta, g: Geometria, locale: Locale) {
  Etichetta(stringResource(R.string.card_seduta_etichetta, c.sport, data(c.giorno, locale)), g)
  Text(c.nome, maxLines = 2, overflow = TextOverflow.Ellipsis,
      style = testo(SpaceGrotesk, FontWeight.Bold, (g.secValore * 1.2f).toInt(),
          NoctalixColori.text))
  Spacer(Modifier.height((g.secValore / 2).dp))
  NumeroGrande(c.durataMin.coerceAtLeast(0).toString(), g,
      stringResource(R.string.card_seduta_min))
  Spacer(Modifier.height(g.secValore.dp))
  val valori = buildList {
    c.distanzaKm?.takeIf { it > 0.0 }?.let { km ->
      val (metri, testo) = Calcoli.distanza(km, locale)
      add(stringResource(R.string.card_seduta_distanza) to
          stringResource(if (metri) R.string.card_m else R.string.card_km, testo))
    }
    c.tss?.takeIf { it > 0 }?.let {
      add(stringResource(R.string.card_seduta_tss) to it.toString())
    }
    c.recupero?.takeIf { it.isNotBlank() }?.let {
      add(stringResource(R.string.card_seduta_recupero) to it)
    }
  }
  if (valori.isNotEmpty()) Valori(g, valori)
}

private fun data(giorno: LocalDate, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(giorno)

private fun testo(
    famiglia: FontFamily,
    peso: FontWeight,
    px: Int,
    colore: Color,
    cifre: Boolean = false,
) = TextStyle(
    fontFamily = famiglia,
    fontWeight = peso,
    fontSize = px.sp,
    color = colore,
    fontFeatureSettings = if (cifre) CIFRE_TABULARI else null,
)

