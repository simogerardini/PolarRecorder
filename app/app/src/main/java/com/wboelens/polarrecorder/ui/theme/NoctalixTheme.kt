package com.wboelens.polarrecorder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.wboelens.polarrecorder.R

/**
 * Stile NoctaliX, dal sito noctalix.com (Parte 4, ottobre 2026): colori, caratteri e forme.
 * Il tema scuro e' quello del sito; il chiaro e' ricavato dalla stessa tavolozza. Segue il
 * telefono (chiaro/scuro). L'ipnogramma e i grafici mantengono i loro colori.
 */
object NoctalixColori {
  // Scuro: valori esatti del sito
  val bg = Color(0xFF000000)
  val panel = Color(0xFF0B1620)
  val line = Color(0xFF16293A)
  val border = Color(0xFF1E3A52)
  val borderInput = Color(0xFF23415A)
  val text = Color(0xFFE8F1F5)
  val soft = Color(0xFFA9BCC9)
  val muted = Color(0xFF7F97AA)
  val azure = Color(0xFF39A5C8)
  val azureHi = Color(0xFF5DAEC8)
  val azureLo = Color(0xFF256B87)
  val coral = Color(0xFFEF485B)
  val coralHover = Color(0xFFFF5A6C)
  val erroreScuro = Color(0xFFFF8A96)

  // Chiaro: ricavato (proposta), contrasti AA su bianco
  val bgChiaro = Color(0xFFF7FAFC)
  val panelChiaro = Color(0xFFEDF3F7)
  val lineChiaro = Color(0xFFD6E1E9)
  val borderChiaro = Color(0xFFBFD0DC)
  val textChiaro = Color(0xFF0B1620)
  val softChiaro = Color(0xFF3E5568)
  val coralChiaro = Color(0xFFD93A4E)
}

private val Inter =
    FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_semibold, FontWeight.SemiBold),
    )

private val SpaceGrotesk =
    FontFamily(
        Font(R.font.space_grotesk_medium, FontWeight.Medium),
        Font(R.font.space_grotesk_bold, FontWeight.Bold),
    )

/** Cifre tabulari per i valori (es. 64 ms, 46 bpm), come nella card del mattino del sito. */
const val CIFRE_TABULARI = "tnum"

private fun titolo(peso: FontWeight, size: Int, interlinea: Double, spaziatura: Double = -0.02) =
    TextStyle(fontFamily = SpaceGrotesk, fontWeight = peso, fontSize = size.sp, lineHeight = (size * interlinea).sp, letterSpacing = spaziatura.em)

private fun testo(peso: FontWeight, size: Int, interlinea: Double, cifre: Boolean = false) =
    TextStyle(
        fontFamily = Inter,
        fontWeight = peso,
        fontSize = size.sp,
        lineHeight = (size * interlinea).sp,
        fontFeatureSettings = if (cifre) CIFRE_TABULARI else null)

val NoctalixTipografia =
    Typography(
        // numero della prontezza e numeri grandi
        displayLarge = titolo(FontWeight.Bold, 64, 1.0),
        displayMedium = titolo(FontWeight.Bold, 52, 1.0),
        displaySmall = titolo(FontWeight.Bold, 40, 1.02),
        // titoli
        headlineLarge = titolo(FontWeight.Bold, 32, 1.08),
        headlineMedium = titolo(FontWeight.Bold, 28, 1.08),
        headlineSmall = titolo(FontWeight.Bold, 24, 1.1),
        titleLarge = titolo(FontWeight.Bold, 22, 1.2, -0.01),
        titleMedium = titolo(FontWeight.Medium, 18, 1.25, -0.01),
        titleSmall = titolo(FontWeight.Medium, 15, 1.3, 0.0),
        // testo
        bodyLarge = testo(FontWeight.Normal, 17, 1.6),
        bodyMedium = testo(FontWeight.Normal, 15, 1.5),
        bodySmall = testo(FontWeight.Normal, 13, 1.45),
        // pulsanti, valori, etichette
        labelLarge = testo(FontWeight.SemiBold, 15, 1.3, cifre = true),
        labelMedium = testo(FontWeight.Medium, 13, 1.3, cifre = true),
        labelSmall = testo(FontWeight.Medium, 11, 1.3, cifre = true),
    )

val NoctalixForme =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(14.dp), // campi e pulsanti
        medium = RoundedCornerShape(18.dp), // riquadri
        large = RoundedCornerShape(20.dp), // card del mattino
        extraLarge = RoundedCornerShape(22.dp), // riquadro in evidenza
    )

val NoctalixScuro: ColorScheme =
    with(NoctalixColori) {
      darkColorScheme(
          primary = azure,
          onPrimary = bg,
          primaryContainer = Color(0xFF0E3445),
          onPrimaryContainer = text,
          secondary = azureHi,
          onSecondary = bg,
          secondaryContainer = Color(0xFF12293A),
          onSecondaryContainer = text,
          tertiary = coral,
          onTertiary = Color.White,
          background = bg,
          onBackground = text,
          surface = bg,
          onSurface = text,
          surfaceVariant = panel,
          onSurfaceVariant = soft,
          surfaceContainerLowest = bg,
          surfaceContainerLow = Color(0xFF070F16),
          surfaceContainer = panel,
          surfaceContainerHigh = panel,
          surfaceContainerHighest = Color(0xFF0F1C28),
          inverseSurface = text,
          inverseOnSurface = bg,
          outline = border,
          outlineVariant = line,
          error = erroreScuro,
          onError = bg,
          errorContainer = Color(0xFF3A1218),
          onErrorContainer = Color(0xFFFFD9DD),
      )
    }

val NoctalixChiaro: ColorScheme =
    with(NoctalixColori) {
      lightColorScheme(
          primary = azureLo,
          onPrimary = Color.White,
          primaryContainer = Color(0xFFD3EAF3),
          onPrimaryContainer = textChiaro,
          secondary = azureLo,
          onSecondary = Color.White,
          secondaryContainer = Color(0xFFE1EEF4),
          onSecondaryContainer = textChiaro,
          tertiary = coralChiaro,
          onTertiary = Color.White,
          background = bgChiaro,
          onBackground = textChiaro,
          surface = bgChiaro,
          onSurface = textChiaro,
          surfaceVariant = panelChiaro,
          onSurfaceVariant = softChiaro,
          surfaceContainerLowest = Color.White,
          surfaceContainerLow = Color(0xFFF2F6F9),
          surfaceContainer = panelChiaro,
          surfaceContainerHigh = panelChiaro,
          surfaceContainerHighest = Color(0xFFE4ECF2),
          outline = borderChiaro,
          outlineVariant = lineChiaro,
          error = coralChiaro,
          onError = Color.White,
          errorContainer = Color(0xFFFBDDE1),
          onErrorContainer = Color(0xFF5A0A16),
      )
    }

/** Tema dell'app: segue chiaro/scuro del telefono, con caratteri e forme del sito. */
@Composable
fun NoctalixTheme(scuro: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  MaterialTheme(
      colorScheme = if (scuro) NoctalixScuro else NoctalixChiaro,
      typography = NoctalixTipografia,
      shapes = NoctalixForme,
      content = content,
  )
}
