package com.wboelens.polarrecorder.biosleep.ui

import com.wboelens.polarrecorder.biosleep.lingua.tr
import com.wboelens.polarrecorder.R
import androidx.compose.ui.res.stringResource
import com.wboelens.polarrecorder.BuildConfig
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wboelens.polarrecorder.biosleep.age.AgeComponent
import com.wboelens.polarrecorder.biosleep.age.AgeProfileStore
import com.wboelens.polarrecorder.biosleep.age.AgeRepository
import com.wboelens.polarrecorder.biosleep.age.BioAgeOutcome
import com.wboelens.polarrecorder.biosleep.age.BioAgeResult
import com.wboelens.polarrecorder.biosleep.age.BioAgeScreenData
import com.wboelens.polarrecorder.biosleep.age.Sex
import com.wboelens.polarrecorder.biosleep.condivisione.CardEta
import com.wboelens.polarrecorder.biosleep.condivisione.Condivisione
import java.time.LocalDate
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val YOUNGER = Color(0xFF43A047)
private val OLDER = Color(0xFFFB8C00)

/** Schermata "Eta' BioSleep". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BioAgeScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val store = remember { AgeProfileStore(context) }
  var birth by remember { mutableStateOf(store.birthDate) }
  var sex by remember { mutableStateOf(store.sex) }
  var editing by remember { mutableStateOf(birth == null || sex == null) }
  var reload by remember { mutableIntStateOf(0) }

  val data by
      produceState<BioAgeScreenData?>(null, birth, sex, reload, editing) {
        val b = birth
        val s = sex
        value =
            if (editing || b == null || s == null) null
            else withContext(Dispatchers.IO) { AgeRepository.load(context, b, s) }
      }

  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.bio_age_eta, BuildConfig.APP_NAME)) },
            navigationIcon = {
              IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Indietro"))
              }
            },
            actions = {
              // Parte 5: condivisione, solo con l'eta' pronta (non in taratura)
              val pronta = data?.outcome as? BioAgeOutcome.Ready
              if (pronta != null && !editing) {
                IconButton(onClick = { Condivisione.apri(context, cardEta(pronta.result)) }) {
                  Icon(Icons.Filled.Share,
                      contentDescription = stringResource(R.string.condivisione_azione))
                }
              }
            },
        )
      }
  ) { padding ->
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      if (editing) {
        ProfileForm(birth, sex) { b, s ->
          store.birthDate = b
          store.sex = s
          birth = b
          sex = s
          editing = false
          reload++
        }
        return@Column
      }
      // Errore di lettura da Intervals.icu: diverso da "nessun dato", con Riprova
      data?.erroreIntervals?.let { e ->
        OutlinedCard(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.bio_age_impossibile_leggere_le_attivita, e.toString()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error)
            Text(
                stringResource(R.string.bio_age_fitness_e_allenamento_non),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { reload++ }) { Text(stringResource(R.string.bio_age_riprova)) }
          }
        }
      }
      when (val d = data) {
        null ->
            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
              CircularProgressIndicator()
            }
        else ->
            when (val o = d.outcome) {
              is BioAgeOutcome.Calibrating -> CalibratingContent(o, d)
              is BioAgeOutcome.Ready -> ReadyContent(o.result, d)
            }
      }
      TextButton(onClick = { editing = true }) { Text(stringResource(R.string.bio_age_modifica_data_di_nascita)) }
      MethodNote()
    }
  }
}

// --- Anelli concentrici ----------------------------------------------------------------------

/**
 * Tre anelli (fitness, allenamento, sonno). All'apertura girano a velocita' diverse e si
 * fermano tutti con l'inizio dell'arco in alto, "allineati". La lunghezza dell'arco e' quanto
 * quella componente sposta l'eta' (fino a 6 anni = anello pieno); verde = piu' giovane,
 * arancio = piu' vecchio. Al centro l'eta' scorre dall'anagrafica a quella stimata.
 */
@Composable
private fun AgeRings(result: BioAgeResult?, centerTop: String, centerBottom: String, dimmed: Boolean) {
  val rings = ringValues(result)
  val starts = listOf(540f, -420f, 300f)
  val rotations = remember { starts.map { Animatable(it) } }
  val shown = remember { Animatable(result?.chronologicalAge?.toFloat() ?: 0f) }
  LaunchedEffect(result) {
    rotations.forEachIndexed { i, a ->
      launch {
        a.snapTo(starts[i])
        a.animateTo(0f, spring(dampingRatio = 0.55f + 0.1f * i, stiffness = Spring.StiffnessVeryLow))
      }
    }
    result?.let { shown.animateTo(it.age.toFloat(), tween(durationMillis = 1800)) }
  }
  val track = MaterialTheme.colorScheme.surfaceVariant
  val neutral = MaterialTheme.colorScheme.outline // in calibrazione: niente verde/arancio
  Box(Modifier.fillMaxWidth().size(280.dp), contentAlignment = Alignment.Center) {
    Canvas(Modifier.size(280.dp)) {
      val stroke = 18.dp.toPx()
      rings.forEachIndexed { i, years ->
        val inset = stroke / 2 + i * (stroke + 8.dp.toPx())
        val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
        val topLeft = Offset(inset, inset)
        drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
        val sweep = (abs(years) / 6.0).coerceIn(0.03, 1.0).toFloat() * 360f
        val color = if (years <= 0) YOUNGER else OLDER
        rotate(rotations[i].value) {
          drawArc(
              if (dimmed) neutral.copy(alpha = 0.5f) else color,
              -90f,
              sweep,
              false,
              topLeft,
              arcSize,
              style = Stroke(stroke, cap = StrokeCap.Round),
          )
        }
      }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
          if (result == null) centerTop else fmt(shown.value.toDouble(), 1),
          fontSize = 52.sp,
          fontWeight = FontWeight.Bold,
      )
      Text(centerBottom, style = MaterialTheme.typography.bodySmall)
    }
  }
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
    listOf("Esterno: fitness", "Centro: allenamento", "Interno: sonno").forEach {
      Text(tr(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

/** Anni per anello: fitness (VO2max), allenamento, sonno (durata + regolarita'). */
private fun ringValues(r: BioAgeResult?): List<Double> {
  if (r == null) return listOf(2.0, 3.0, 4.0)
  fun y(k: String) = r.components.filter { it.key == k }.sumOf { it.years }
  return listOf(y("vo2"), y("activity"), y("sleep_duration") + y("sleep_regularity"))
}

// --- Contenuti --------------------------------------------------------------------------------

@Composable
private fun ReadyContent(r: BioAgeResult, d: BioAgeScreenData) {
  val delta = r.age - r.chronologicalAge
  AgeRings(r, "", tr("tra ${fmt(r.low, 1)} e ${fmt(r.high, 1)} anni"), dimmed = false)
  Text(
      tr("Età anagrafica ${fmt(r.chronologicalAge, 1)} · " +
          (if (delta <= 0) "${fmt(-delta, 1)} anni in meno" else "${fmt(delta, 1)} anni in più")),
      style = MaterialTheme.typography.titleMedium,
      color = if (delta <= 0) YOUNGER else OLDER,
  )
  OutlinedCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(stringResource(R.string.bio_age_cosa_la_determina), style = MaterialTheme.typography.titleSmall)
      r.components.forEach { ComponentRow(it) }
      if (r.missing.isNotEmpty()) {
        Text(
            tr("Non disponibili: ${r.missing.joinToString("; ")}"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
  OutlinedCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(stringResource(R.string.bio_age_ritmo_d_invecchiamento), style = MaterialTheme.typography.titleSmall)
      Text(
          tr(d.pace?.let {
            "${fmt(it, 2)} anni biologici per anno di calendario " +
                (if (it < 1) "(stai rallentando)" else "(stai accelerando)")
          } ?: "Disponibile dopo 90 giorni di storico (ora ${d.historyDays} giorni con una stima)."),
          style = MaterialTheme.typography.bodyMedium,
      )
    }
  }
}

@Composable
private fun ComponentRow(c: AgeComponent) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(tr(c.label), style = MaterialTheme.typography.bodyMedium)
      Text(tr(c.detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.width(12.dp))
    Text(
        tr((if (c.years > 0) "+" else "") + fmt(c.years, 1) + " anni"),
        style = MaterialTheme.typography.titleSmall,
        color = if (c.years <= 0) YOUNGER else OLDER,
    )
  }
}

@Composable
private fun CalibratingContent(o: BioAgeOutcome.Calibrating, d: BioAgeScreenData) {
  AgeRings(null, "${o.validNights}/${o.requiredNights}", tr("notti valide"), dimmed = true)
  Text(stringResource(R.string.bio_age_in_calibrazione), style = MaterialTheme.typography.titleMedium)
  OutlinedCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(stringResource(R.string.bio_age_notti_degli_ultimi_28), style = MaterialTheme.typography.titleSmall)
      Text(
          stringResource(R.string.bio_age_registrate_valide_escluse_per, d.nightsRecent.toString(), o.validNights.toString(), d.nightsLowQuality.toString(), d.nightsNoStages.toString()),
          style = MaterialTheme.typography.bodyMedium,
      )
      Text(
          stringResource(R.string.bio_age_servono_notti_valide_con, o.requiredNights.toString()),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
  OutlinedCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(stringResource(R.string.bio_age_anteprima_non_ancora_un), style = MaterialTheme.typography.titleSmall)
      if (o.preview.isEmpty()) {
        Text(stringResource(R.string.bio_age_nessuna_componente_ancora_calcolabile), style = MaterialTheme.typography.bodySmall)
      }
      o.preview.forEach { ComponentRow(it) }
      if (o.validNights < 3) {
        Text(
            stringResource(R.string.bio_age_sonno_compare_da_3),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (o.missing.isNotEmpty()) {
        Text(
            tr("Non disponibili: ${o.missing.joinToString("; ")}"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun ProfileForm(birth: LocalDate?, sex: Sex?, onSave: (LocalDate, Sex) -> Unit) {
  var text by remember { mutableStateOf(birth?.toString() ?: "") }
  var chosen by remember { mutableStateOf(sex) }
  val parsed = runCatching { LocalDate.parse(text.trim()) }.getOrNull()
  val validDate = parsed != null && parsed.isBefore(LocalDate.now().minusYears(15))
  Text(stringResource(R.string.bio_age_il_tuo_profilo), style = MaterialTheme.typography.titleMedium)
  Text(
      stringResource(R.string.bio_age_servono_per_confrontarti_con),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  OutlinedTextField(
      value = text,
      onValueChange = { text = it },
      label = { Text(stringResource(R.string.bio_age_data_di_nascita_aaaa)) },
      singleLine = true,
      isError = text.isNotBlank() && !validDate,
      modifier = Modifier.fillMaxWidth(),
  )
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    FilterChip(selected = chosen == Sex.MALE, onClick = { chosen = Sex.MALE }, label = { Text(stringResource(R.string.bio_age_uomo)) })
    FilterChip(selected = chosen == Sex.FEMALE, onClick = { chosen = Sex.FEMALE }, label = { Text(stringResource(R.string.bio_age_donna)) })
  }
  Button(onClick = { onSave(parsed!!, chosen!!) }, enabled = validDate && chosen != null) { Text(stringResource(R.string.bio_age_salva)) }
}

@Composable
private fun MethodNote() {
  Text(
      stringResource(R.string.bio_age_come_e_calcolata_ogni),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

/**
 * Card condivisibile (Parte 5): solo l'eta' con un decimale, come fmt(..., 1) negli anelli.
 * La differenza compare solo se l'utente la attiva nell'anteprima. Componenti ed eta'
 * anagrafica non escono da qui. vo2 e activity vengono dalle attivita' di Intervals.icu,
 * che possono essere Garmin: in quel caso la card porta l'attribuzione.
 */
private fun cardEta(r: BioAgeResult) =
    CardEta(
        eta = r.age,
        decimali = 1,
        differenza = r.age - r.chronologicalAge,
        datiGarmin = r.components.any { it.key == "vo2" || it.key == "activity" },
    )
