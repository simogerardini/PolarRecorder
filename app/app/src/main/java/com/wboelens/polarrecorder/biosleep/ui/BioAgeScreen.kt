package com.wboelens.polarrecorder.biosleep.ui

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
            title = { Text("Età ${BuildConfig.APP_NAME}") },
            navigationIcon = {
              IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
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
                "Impossibile leggere le attività da Intervals.icu ($e)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error)
            Text(
                "Fitness e allenamento non sono calcolati finché la lettura non riesce. Se l'errore è 401 o 403, " +
                    "ricollega Intervals.icu dalle Impostazioni.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { reload++ }) { Text("Riprova") }
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
      TextButton(onClick = { editing = true }) { Text("Modifica data di nascita e sesso") }
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
      Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
  AgeRings(r, "", "tra ${fmt(r.low, 1)} e ${fmt(r.high, 1)} anni", dimmed = false)
  Text(
      "Età anagrafica ${fmt(r.chronologicalAge, 1)} · " +
          (if (delta <= 0) "${fmt(-delta, 1)} anni in meno" else "${fmt(delta, 1)} anni in più"),
      style = MaterialTheme.typography.titleMedium,
      color = if (delta <= 0) YOUNGER else OLDER,
  )
  OutlinedCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("Cosa la determina", style = MaterialTheme.typography.titleSmall)
      r.components.forEach { ComponentRow(it) }
      if (r.missing.isNotEmpty()) {
        Text(
            "Non disponibili: ${r.missing.joinToString("; ")}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
  OutlinedCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("Ritmo d'invecchiamento", style = MaterialTheme.typography.titleSmall)
      Text(
          d.pace?.let {
            "${fmt(it, 2)} anni biologici per anno di calendario " +
                (if (it < 1) "(stai rallentando)" else "(stai accelerando)")
          } ?: "Disponibile dopo 90 giorni di storico (ora ${d.historyDays} giorni con una stima).",
          style = MaterialTheme.typography.bodyMedium,
      )
    }
  }
}

@Composable
private fun ComponentRow(c: AgeComponent) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(c.label, style = MaterialTheme.typography.bodyMedium)
      Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.width(12.dp))
    Text(
        (if (c.years > 0) "+" else "") + fmt(c.years, 1) + " anni",
        style = MaterialTheme.typography.titleSmall,
        color = if (c.years <= 0) YOUNGER else OLDER,
    )
  }
}

@Composable
private fun CalibratingContent(o: BioAgeOutcome.Calibrating, d: BioAgeScreenData) {
  AgeRings(null, "${o.validNights}/${o.requiredNights}", "notti valide", dimmed = true)
  Text("In calibrazione", style = MaterialTheme.typography.titleMedium)
  OutlinedCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("Notti degli ultimi 28 giorni", style = MaterialTheme.typography.titleSmall)
      Text(
          "${d.nightsRecent} registrate · ${o.validNights} valide · " +
              "${d.nightsLowQuality} escluse per affidabilità sotto l'80% · " +
              "${d.nightsNoStages} escluse perché senza fasi del sonno",
          style = MaterialTheme.typography.bodyMedium,
      )
      Text(
          "Servono ${o.requiredNights} notti valide: con meno notti l'età cambierebbe troppo da " +
              "un giorno all'altro.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
  OutlinedCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("Anteprima (non ancora un'età)", style = MaterialTheme.typography.titleSmall)
      if (o.preview.isEmpty()) {
        Text("Nessuna componente ancora calcolabile.", style = MaterialTheme.typography.bodySmall)
      }
      o.preview.forEach { ComponentRow(it) }
      if (o.validNights < 3) {
        Text(
            "Sonno: compare da 3 notti valide.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (o.missing.isNotEmpty()) {
        Text(
            "Non disponibili: ${o.missing.joinToString("; ")}",
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
  Text("Il tuo profilo", style = MaterialTheme.typography.titleMedium)
  Text(
      "Servono per confrontarti con i valori tipici della tua età e del tuo sesso. Restano sul telefono.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  OutlinedTextField(
      value = text,
      onValueChange = { text = it },
      label = { Text("Data di nascita (AAAA-MM-GG)") },
      singleLine = true,
      isError = text.isNotBlank() && !validDate,
      modifier = Modifier.fillMaxWidth(),
  )
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    FilterChip(selected = chosen == Sex.MALE, onClick = { chosen = Sex.MALE }, label = { Text("Uomo") })
    FilterChip(selected = chosen == Sex.FEMALE, onClick = { chosen = Sex.FEMALE }, label = { Text("Donna") })
  }
  Button(onClick = { onSave(parsed!!, chosen!!) }, enabled = validDate && chosen != null) { Text("Salva") }
}

@Composable
private fun MethodNote() {
  Text(
      "Come è calcolata: ogni componente viene confrontata con una persona tipica della tua età e " +
          "del tuo sesso e convertita in anni con i rischi di mortalità pubblicati in studi su " +
          "centinaia di migliaia di persone (VO2max: Kodama 2009; allenamento: Arem 2015; durata " +
          "del sonno: Cappuccio 2010; regolarità: Windred 2024), assumendo che il rischio raddoppi " +
          "ogni ~8 anni. L'intervallo tiene conto dell'errore di misura e dell'incertezza degli " +
          "studi. FC a riposo e HRV non sono incluse: i riferimenti pubblicati sono misurati da " +
          "svegli e non sono confrontabili con i valori notturni. È una stima statistica di " +
          "benessere, non una valutazione medica.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}
