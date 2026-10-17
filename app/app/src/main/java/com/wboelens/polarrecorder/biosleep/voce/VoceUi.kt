package com.wboelens.polarrecorder.biosleep.voce

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.wboelens.polarrecorder.BuildConfig
import com.wboelens.polarrecorder.R
import com.wboelens.polarrecorder.biosleep.NightListItem
import com.wboelens.polarrecorder.biosleep.cache.CacheRepo
import com.wboelens.polarrecorder.biosleep.cervello.Cervello
import com.wboelens.polarrecorder.biosleep.lingua.tr
import com.wboelens.polarrecorder.biosleep.riepilogo.Riepilogo
import com.wboelens.polarrecorder.biosleep.tag.Vocabolario
import com.wboelens.polarrecorder.voce.DatiCoachVocale
import com.wboelens.polarrecorder.voce.DatiEtaVocale
import com.wboelens.polarrecorder.voce.DatiNotteVocale
import com.wboelens.polarrecorder.voce.Elemento
import com.wboelens.polarrecorder.voce.FrasarioCoach
import com.wboelens.polarrecorder.voce.FrasarioEta
import com.wboelens.polarrecorder.voce.FrasarioNotte
import com.wboelens.polarrecorder.voce.FrasarioRiepilogo
import com.wboelens.polarrecorder.voce.VoceNoctalix
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun Any?.numero(): Double? = (this as? Number)?.toDouble()

/**
 * Pulsante voce nella barra in alto + lettura alla prima apertura (Parte 10).
 * - legge da sola solo se voce attiva, toggle della pagina acceso e contenuto mai letto
 *   (VoceNoctalix.leggiAllApertura);
 * - pulsante visibile solo con la voce attiva: altoparlante = ripeti, Stop = ferma;
 * - uscendo dalla pagina o mettendo l'app in background la voce si ferma.
 * Va messo sempre nella barra (anche a dati non pronti: elementi = null), cosi' gli effetti restano.
 */
@Composable
fun AzioneVoce(pagina: VoceNoctalix.Pagina, chiaveContenuto: String?, elementi: List<Elemento>?) {
  val ctx = LocalContext.current // contesto dell'Activity: serve per la lingua
  val attiva = remember { VoceNoctalix.voceAttiva(ctx) }
  var parla by remember { mutableStateOf(VoceNoctalix.inRiproduzione()) }
  val ciclo = LocalLifecycleOwner.current
  DisposableEffect(ciclo) {
    val ascolta: (Boolean) -> Unit = { parla = it }
    VoceNoctalix.aggiungiAscoltatore(ascolta)
    val osservatore = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) VoceNoctalix.ferma() }
    ciclo.lifecycle.addObserver(osservatore)
    onDispose {
      VoceNoctalix.rimuoviAscoltatore(ascolta)
      ciclo.lifecycle.removeObserver(osservatore)
      VoceNoctalix.ferma()
    }
  }
  LaunchedEffect(chiaveContenuto, elementi != null) {
    if (chiaveContenuto != null && !elementi.isNullOrEmpty()) VoceNoctalix.leggiAllApertura(ctx, pagina, chiaveContenuto, elementi)
  }
  if (!attiva || elementi.isNullOrEmpty()) return
  IconButton(onClick = { if (parla) VoceNoctalix.ferma() else VoceNoctalix.ripeti(ctx, elementi) }) {
    if (parla) Icon(Icons.Filled.Stop, tr("Ferma la voce"))
    else Icon(Icons.AutoMirrored.Filled.VolumeUp, tr("Ripeti a voce"))
  }
}

/** Oggi: notte piu' recente analizzata, semaforo della banda, sedute di oggi (non in solo biometria). */
@Composable
fun AzioneVoceOggi(conSedute: Boolean) {
  val ctx = LocalContext.current
  val dati by produceState<Pair<String, List<Elemento>>?>(null, conSedute) {
    value = withContext(Dispatchers.IO) {
      runCatching { VoceDati.oggi(CacheRepo.get(ctx), LocalDate.now(), conSedute) }.getOrNull()
          ?.let { (k, d) -> k to FrasarioRiepilogo.componi(d) }
    }
  }
  AzioneVoce(VoceNoctalix.Pagina.OGGI, dati?.first, dati?.second)
}

/** Notti: la notte mostrata (data = giorno del risveglio), confronto HRV con la banda di quel giorno. */
@Composable
fun AzioneVoceNotte(night: NightListItem?) {
  val ctx = LocalContext.current
  val data = night?.let { Vocabolario.mattinaDaFine(it.summary.endMs) }
  // (caricato, range): la lettura parte solo con il confronto pronto
  val range by produceState<Pair<Boolean, Pair<Double, Double>?>>(false to null, data) {
    value = true to data?.let { d -> withContext(Dispatchers.IO) { runCatching { CacheRepo.get(ctx).baseline(d).normalRangeMs }.getOrNull() } }
  }
  val elementi =
      if (night == null || !range.first) null
      else {
        val s = night.summary
        val st = night.stages
        val rmssd = s.rmssd.numero()
        FrasarioNotte.componi(
            DatiNotteVocale(
                sonnoMin = st?.tstMin.numero()?.roundToInt(),
                profondoMin = st?.deepMin.numero()?.roundToInt(),
                remMin = st?.remMin.numero()?.roundToInt(),
                leggeroMin = st?.lightMin.numero()?.roundToInt(),
                vegliaMin = st?.wakeMin.numero()?.roundToInt(),
                hrvMs = rmssd?.roundToInt(),
                hrvConfronto = VoceDati.confronto(rmssd, range.second),
                fcMediaSonno = s.hrAvg.numero()?.roundToInt(),
                fcRiposo = s.restingHr.numero()?.roundToInt(),
            ))
      }
  AzioneVoce(VoceNoctalix.Pagina.NOTTI, data?.toString(), elementi)
}

/** Coach: decisione e motivo (codici del cervello), semaforo, sedute di oggi o riposo. */
@Composable
fun AzioneVoceCoach(r: Riepilogo?) {
  val ctx = LocalContext.current
  val codici by produceState<List<String>?>(null, r?.data) {
    value = r?.let { rr ->
      withContext(Dispatchers.IO) {
        val f = File(Cervello.cartella(ctx), "riepilogo_${rr.data}.json")
        VoceDati.codiciCoach(runCatching { f.takeIf { it.exists() }?.readText() }.getOrNull(), rr.decisione.codice)
      }
    }
  }
  val elementi =
      r?.let { rr ->
        codici?.let { c ->
          val sedute = if (rr.settimanale) emptyList() else VoceDati.seduteDaRiepilogo(rr.oggi)
          FrasarioCoach.componi(
              DatiCoachVocale(
                  codici = c,
                  semaforo = VoceDati.semaforo(rr.biometria?.takeIf { it.ok == true }?.banda),
                  sedute = sedute,
                  giornoDiRiposo = !rr.settimanale && rr.oggi.isNullOrBlank()))
        }
      }
  AzioneVoce(VoceNoctalix.Pagina.COACH, r?.data, elementi)
}

/** Eta': eta' NoctaliX e anagrafica arrotondate; chiave = giorno del calcolo. */
@Composable
fun AzioneVoceEta(eta: Double?, anagrafica: Double?) {
  val elementi = eta?.let { FrasarioEta.componi(DatiEtaVocale(it.roundToInt(), anagrafica?.roundToInt())) }
  AzioneVoce(VoceNoctalix.Pagina.ETA, eta?.let { LocalDate.now().toString() }, elementi)
}

/** Impostazioni: scelta della voce, anteprima, lettura automatica per pagina. */
@Composable
fun SezioneVoce() {
  val ctx = LocalContext.current
  var voce by remember { mutableStateOf(VoceNoctalix.voceScelta(ctx)) }
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(tr("Voce"), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      for ((v, etichetta) in
          listOf(
              VoceNoctalix.Voce.FEMMINILE to "Femminile",
              VoceNoctalix.Voce.MASCHILE to "Maschile",
              VoceNoctalix.Voce.DISATTIVATA to "Disattivata")) {
        FilterChip(
            selected = voce == v,
            onClick = {
              VoceNoctalix.impostaVoce(ctx, v)
              voce = v
            },
            label = { Text(tr(etichetta)) })
      }
    }
    if (voce != VoceNoctalix.Voce.DISATTIVATA) {
      OutlinedButton(onClick = { VoceNoctalix.anteprima(ctx, voce) }) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, null)
        Spacer(Modifier.width(8.dp))
        Text(tr("Ascolta"))
      }
      Text(tr("Leggi in automatico"), style = MaterialTheme.typography.labelLarge)
      val pagine =
          listOf(
              VoceNoctalix.Pagina.COACH to tr("Coach"),
              VoceNoctalix.Pagina.NOTTI to tr("Notti"),
              VoceNoctalix.Pagina.OGGI to tr("Oggi"),
              VoceNoctalix.Pagina.ETA to stringResource(R.string.bio_age_eta, BuildConfig.APP_NAME))
      for ((p, etichetta) in pagine) {
        var acceso by remember(p) { mutableStateOf(VoceNoctalix.letturaAutomatica(ctx, p)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text(etichetta, Modifier.weight(1f))
          Switch(
              checked = acceso,
              onCheckedChange = {
                VoceNoctalix.impostaLetturaAutomatica(ctx, p, it)
                acceso = it
              })
        }
      }
      Text(
          tr("La pagina viene letta una volta, alla prima apertura con un contenuto nuovo. Il pulsante con l'altoparlante la ripete."),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}
