package com.wboelens.polarrecorder.biosleep.condivisione

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.core.graphics.drawable.toBitmap
import com.wboelens.polarrecorder.R
import com.wboelens.polarrecorder.ui.theme.NoctalixTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Unico punto d'ingresso per le schermate: Condivisione.apri(context, CardSonno(...)). */
object Condivisione {
  internal const val EXTRA_CARD = "noctalix.condivisione.card"

  fun apri(context: Context, card: CardCondivisibile) {
    val i = Intent(context, CondivisioneActivity::class.java).putExtra(EXTRA_CARD, card)
    if (context !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(i)
  }
}

/** Anteprima della card, scelta del formato e invio al menu di condivisione di sistema. */
class CondivisioneActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val card = IntentCompat.getSerializableExtra(
        intent, Condivisione.EXTRA_CARD, CardCondivisibile::class.java)
    if (card == null) {
      finish()
      return
    }
    Thread { runCatching { FileCondivisi.pulisci(applicationContext) } }.start()
    setContent { NoctalixTheme(scuro = true) { SchermataCondivisione(card, ::finish) } }
  }
}

@Composable
private fun SchermataCondivisione(card: CardCondivisibile, chiudi: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val layer = rememberGraphicsLayer()
  val logo = remember { logoApp(context) }
  var formato by rememberSaveable { mutableStateOf(Formato.STORIA) }
  var mostraDifferenza by rememberSaveable { mutableStateOf(false) }
  var mostraFasi by rememberSaveable { mutableStateOf(false) }
  var inCorso by remember { mutableStateOf(false) }
  val titoloMenu = stringResource(R.string.condivisione_scegli)
  val errore = stringResource(R.string.condivisione_errore)

  Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = chiudi) {
          Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.condivisione_chiudi))
        }
        Text(stringResource(R.string.condivisione_azione),
            style = MaterialTheme.typography.titleLarge)
      }
      Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp),
          contentAlignment = Alignment.Center) {
        AnteprimaScalata(formato.geometria, layer,
            Modifier.clip(RoundedCornerShape(12.dp))) {
          CardVista(card, formato, Opzioni(mostraDifferenza, mostraFasi), logo)
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Formato.entries.forEach { f ->
          FilterChip(selected = f == formato, onClick = { formato = f },
              label = { Text(stringResource(etichettaFormato(f))) })
        }
      }
      if (card is CardEta && card.differenza != null) {
        Interruttore(stringResource(R.string.condivisione_mostra_differenza), mostraDifferenza) {
          mostraDifferenza = it
        }
      }
      if (card is CardSonno &&
          Calcoli.fasi(card.sonnoMin, card.profondoMin, card.remMin, card.vegliaMin) != null) {
        Interruttore(stringResource(R.string.condivisione_mostra_fasi), mostraFasi) {
          mostraFasi = it
        }
      }
      Text(stringResource(R.string.condivisione_nota_privacy),
          Modifier.padding(top = 8.dp),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.height(12.dp))
      Button(
          onClick = {
            inCorso = true
            scope.launch {
              val esito = runCatching {
                val immagine = layer.toImageBitmap().asAndroidBitmap()
                withContext(Dispatchers.IO) { FileCondivisi.salva(context, immagine, card.tipo) }
              }
              inCorso = false
              esito
                  .onSuccess { uri ->
                    context.startActivity(FileCondivisi.intent(context, uri, titoloMenu))
                  }
                  .onFailure { Toast.makeText(context, errore, Toast.LENGTH_LONG).show() }
            }
          },
          enabled = !inCorso,
          modifier = Modifier.fillMaxWidth(),
      ) {
        if (inCorso) CircularProgressIndicator(Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
        else Text(stringResource(R.string.condivisione_azione))
      }
    }
  }
}

/** Opzione della card: spenta di default, l'utente sceglie se mostrare di più. */
@Composable
private fun Interruttore(testo: String, acceso: Boolean, cambia: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(testo, Modifier.weight(1f))
    Spacer(Modifier.width(12.dp))
    Switch(checked = acceso, onCheckedChange = cambia)
  }
}

/**
 * Misura la card alla risoluzione piena (g.w x g.h pixel), la registra nel [layer]
 * e la mostra ridotta per stare nello spazio disponibile. Il layer resta a piena
 * risoluzione: layer.toImageBitmap() restituisce l'immagine da condividere.
 */
@Composable
private fun AnteprimaScalata(
    g: Geometria,
    layer: GraphicsLayer,
    modifier: Modifier = Modifier,
    contenuto: @Composable () -> Unit,
) {
  Layout(
      content = {
        Box(Modifier.drawWithContent {
          layer.record { this@drawWithContent.drawContent() }
          drawLayer(layer)
        }) { contenuto() }
      },
      modifier = modifier,
  ) { misurabili, vincoli ->
    val figlio = misurabili.first().measure(Constraints.fixed(g.w, g.h))
    val scala = minOf(
        if (vincoli.hasBoundedWidth) vincoli.maxWidth.toFloat() / g.w else 1f,
        if (vincoli.hasBoundedHeight) vincoli.maxHeight.toFloat() / g.h else 1f,
    )
    layout((g.w * scala).roundToInt(), (g.h * scala).roundToInt()) {
      figlio.placeWithLayer(0, 0) {
        scaleX = scala
        scaleY = scala
        transformOrigin = TransformOrigin(0f, 0f)
      }
    }
  }
}

private fun etichettaFormato(f: Formato): Int = when (f) {
  Formato.STORIA -> R.string.condivisione_formato_storia
  Formato.POST -> R.string.condivisione_formato_post
  Formato.VERTICALE -> R.string.condivisione_formato_verticale
}

/** L'icona dell'app così com'è installata: funziona anche con le icone adattive. */
private fun logoApp(context: Context): ImageBitmap =
    context.packageManager.getApplicationIcon(context.packageName)
        .toBitmap(256, 256).asImageBitmap()
