package com.wboelens.polarrecorder.biosleep.ui.allenamento

import com.wboelens.polarrecorder.R
import androidx.compose.ui.res.stringResource
import com.wboelens.polarrecorder.biosleep.intervals.CampiWellness
import com.wboelens.polarrecorder.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wboelens.polarrecorder.biosleep.cache.CacheRepo
import com.wboelens.polarrecorder.biosleep.cache.FormaHome
import com.wboelens.polarrecorder.biosleep.cache.Prontezza
import com.wboelens.polarrecorder.biosleep.readiness.BioBaseline
import com.wboelens.polarrecorder.biosleep.readiness.PyJson
import com.wboelens.polarrecorder.biosleep.training.Allenamenti
import com.wboelens.polarrecorder.biosleep.training.AttivitaCal
import com.wboelens.polarrecorder.biosleep.training.Esito
import com.wboelens.polarrecorder.biosleep.training.EventoCal
import com.wboelens.polarrecorder.biosleep.training.Formato
import com.wboelens.polarrecorder.biosleep.training.GiornoCal
import com.wboelens.polarrecorder.biosleep.training.Metrica
import com.wboelens.polarrecorder.biosleep.training.SedutaPianificata
import com.wboelens.polarrecorder.biosleep.ui.RigaTagOggi
import com.wboelens.polarrecorder.biosleep.ui.CardInterruzione
import com.wboelens.polarrecorder.biosleep.ui.CardProtezione
import com.wboelens.polarrecorder.biosleep.ui.CardSweat
import com.wboelens.polarrecorder.biosleep.ui.MessaggioCss
import com.wboelens.polarrecorder.biosleep.ui.SoglieOggi
import com.wboelens.polarrecorder.biosleep.ui.TestOggi
import java.time.LocalDate

/** Una notte dalla wellness: valori BioSleep inviati da questa app. */
data class NotteBio(val data: LocalDate, val rmssd: Double?, val fcMedia: Double?, val sonnoOre: Double?)

/** Tutto quello che serve alla schermata, letto in una volta dalla cache. */
data class DatiOggi(
    val oggi: LocalDate,
    val forma: FormaHome,
    val prontezza: Prontezza,
    val baseline: BioBaseline,
    val notti: List<NotteBio>,
    val giorno: GiornoCal,
    val prossima: SedutaPianificata?,
    val eventiSettimana: List<EventoCal>,
    val attivitaSettimana: List<AttivitaCal>,
) {
  companion object {
    fun carica(repo: CacheRepo, oggi: LocalDate): DatiOggi {
      val lunedi = Allenamenti.lunedi(oggi)
      val fine = oggi.plusDays(14) // per la prossima seduta
      val da = minOf(lunedi, oggi.minusDays(6))
      val eventi = repo.eventi(da, fine).mapNotNull { Allenamenti.evento(it) }
      val attivita = repo.attivita(da, oggi).mapNotNull { Allenamenti.attivita(it) }
      val giorni = Allenamenti.giorni(eventi, attivita, oggi, fine, oggi)
      val notti =
          repo.wellness(oggi.minusDays(6), oggi).mapNotNull { o ->
            val d = PyJson.str(o.get("id"))?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
            NotteBio(
                d,
                PyJson.num(o.get(CampiWellness.F_RMSSD))?.v,
                PyJson.num(o.get(CampiWellness.F_AVG_HR))?.v,
                PyJson.num(o.get(CampiWellness.F_SLEEP_HOURS))?.v)
          }
      val prontezza = repo.prontezza(oggi)
      return DatiOggi(
          oggi = oggi,
          forma = repo.forma(oggi),
          prontezza = prontezza,
          baseline = (prontezza as? Prontezza.Banda)?.baseline ?: repo.baseline(oggi),
          notti = notti,
          giorno = giorni.first(),
          prossima = giorni.drop(1).flatMap { it.pianificate }.firstOrNull(),
          eventiSettimana = eventi.filter { it.data >= lunedi && it.data < lunedi.plusDays(7) },
          attivitaSettimana = attivita.filter { it.data >= lunedi },
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OggiScreen(
    bottomBar: @Composable () -> Unit,
    onApriSeduta: (LocalDate, String?) -> Unit,
    onApriRiepilogo: (String) -> Unit,
    onApriImpostazioni: () -> Unit = {},
    onApriTag: (String) -> Unit = {},
    onApriProtezione: () -> Unit = {},
) {
  val dati = rememberDallaCache { repo, oggi -> DatiOggi.carica(repo, oggi) }
  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.oggi_oggi)) },
            actions = {
              AzioneAggiorna()
              // Impostazioni di tutta l'app (Intervals.icu, coach, profilo atleta): dalla home
              IconButton(onClick = onApriImpostazioni) { Icon(Icons.Filled.Settings, "Impostazioni") }
            },
        )
      },
      bottomBar = bottomBar,
  ) { padding ->
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      StatoAggiornamento()
      // registrazione notturna: notte interrotta stamattina, protezione incompleta
      CardInterruzione(onApriProtezione)
      CardProtezione(onApriProtezione)
      RiquadroRiepilogo(LocalDate.now(), onApriRiepilogo)
      // soglie mancanti su Intervals.icu: card solo se servono
      SoglieOggi()
      // test periodici: test in programma questa settimana, tempi del test CSS
      TestOggi()
      // DETP: dati dello sweat test, dal giorno del test
      CardSweat()
      MessaggioCss()
      RigaTagOggi(onApriTag)
      if (dati == null) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return@Column
      }
      Sezione("Prestazioni") { RigaForma(dati.forma) }
      Sezione("Analisi del recupero") { Recupero(dati) }
      Sezione("Allenamento di oggi") { AllenamentoDiOggi(dati, onApriSeduta) }
      Sezione("Settimana") { Settimana(dati) }
    }
  }
}

@Composable
fun Sezione(titolo: String, contenuto: @Composable ColumnScope.() -> Unit) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(titolo, style = MaterialTheme.typography.titleMedium)
      contenuto()
    }
  }
}

// --- Recupero ------------------------------------------------------------------------------------

private fun etichettaBanda(banda: String?) =
    when (banda) {
      "verde" -> "HRV nella norma"
      "giallo" -> "HRV sotto il range"
      "rosso" -> "HRV molto sotto il range"
      else -> "Non valutabile"
    }

@Composable
private fun Recupero(d: DatiOggi) {
  val stileNota = MaterialTheme.typography.bodySmall
  val grigio = MaterialTheme.colorScheme.onSurfaceVariant
  when (val p = d.prontezza) {
    is Prontezza.Calibrazione -> {
      Text(stringResource(R.string.oggi_calibrazione_notti, p.notti.toString(), p.servono.toString()), fontWeight = FontWeight.Bold)
      LinearProgressIndicator(
          progress = { (p.notti.toFloat() / p.servono).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
      Text(
          stringResource(R.string.oggi_il_range_personale_si, BuildConfig.APP_NAME),
          style = stileNota,
          color = grigio)
    }
    is Prontezza.Banda -> {
      val b = p.baseline
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).background(ColoriBio.daNome(b.banda), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(etichettaBanda(b.banda), fontWeight = FontWeight.Bold)
      }
      val r = b.normalRangeMs
      Text(
          "Media 7 gg ${b.rolling7Hrv?.let { Formato.decimale(it) } ?: "—"} ms" +
              (r?.let { " · range ${Formato.decimale(it.first)}–${Formato.decimale(it.second)} ms" } ?: ""),
          style = stileNota)
      if (b.persistenzaGgSotto >= 2) Text(stringResource(R.string.oggi_sotto_il_range_da, b.persistenzaGgSotto.toString()), style = stileNota)
    }
  }
  if (d.baseline.ok && d.baseline.ggRitardo >= 1) {
    Text(
        "Ultima notte registrata: ${d.baseline.ggRitardo} ${if (d.baseline.ggRitardo == 1) "giorno" else "giorni"} fa",
        style = stileNota,
        color = MaterialTheme.colorScheme.error)
  }

  val stanotte = d.notti.lastOrNull { it.data == d.oggi }
  fun media(f: (NotteBio) -> Double?): Double? = d.notti.mapNotNull(f).takeIf { it.isNotEmpty() }?.average()
  val fc = d.baseline.fcRiposo
  Valore(
      "HRV stanotte",
      stanotte?.rmssd?.let { "${Formato.decimale(it)} ms" } ?: "—",
      media { it.rmssd }?.let { "media 7 gg ${Formato.decimale(it)} ms" })
  Valore(
      "FC a riposo stanotte",
      stanotte?.fcMedia?.let { "${Formato.decimale(it)} bpm" } ?: "—",
      fc?.let { f ->
        "media 7 gg ${Formato.decimale(f.rolling7)}" +
            (f.baseline?.let { " · abituale ${Formato.decimale(it)}" } ?: "") +
            (f.delta?.let { " (${Formato.conSegno(it)})" } ?: "")
      },
      allarme = fc?.allarme == true)
  Valore(
      "Sonno stanotte",
      stanotte?.sonnoOre?.let { Formato.durata((it * 3600).toInt()) } ?: "—",
      media { it.sonnoOre }?.let { "media 7 gg ${Formato.durata((it * 3600).toInt())}" })
}

@Composable
private fun Valore(titolo: String, valore: String, nota: String?, allarme: Boolean = false) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(titolo, style = MaterialTheme.typography.bodyMedium)
      if (nota != null) {
        Text(
            nota,
            style = MaterialTheme.typography.bodySmall,
            color = if (allarme) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    Text(valore, style = MaterialTheme.typography.titleMedium)
  }
}

// --- Allenamento di oggi --------------------------------------------------------------------------

@Composable
private fun AllenamentoDiOggi(d: DatiOggi, onApriSeduta: (LocalDate, String?) -> Unit) {
  val g = d.giorno
  if (g.pianificate.isEmpty() && g.nonPianificate.isEmpty()) {
    Text(stringResource(R.string.oggi_riposo), style = MaterialTheme.typography.bodyLarge)
  }
  for (s in g.pianificate) CardSeduta(s, onClick = { onApriSeduta(g.data, s.evento.id) })
  for (a in g.nonPianificate) CardAttivita(a, onClick = { onApriSeduta(g.data, null) })
  d.prossima?.let { p ->
    Text(
        stringResource(R.string.oggi_prossima, (DateIt.breve(p.evento.data)).toString(), p.evento.nome.toString(), (Formato.durata(p.evento.durataS)).toString()),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable { onApriSeduta(p.evento.data, p.evento.id) })
  }
}

private fun riepilogo(durataS: Int?, tss: Int?, distanzaM: Double?): String =
    listOfNotNull(
            Formato.durata(durataS).takeIf { it != "—" },
            tss?.let { "$it TSS" },
            distanzaM?.let { Formato.distanza(it) })
        .joinToString(" · ")
        .ifEmpty { "—" }

/** Seduta pianificata: bordo = da fare, pieno = svolta, rosso = saltata (come TrainingPeaks). */
@Composable
fun CardSeduta(s: SedutaPianificata, onClick: () -> Unit) {
  val e = s.evento
  val coloreSport = ColoriBio.sport(e.sport)
  val (coloreEsito, testoEsito) =
      when (s.esito) {
        Esito.SVOLTA -> {
          val c = s.consuntivo?.compliance
          ColoriBio.aderenza(Allenamenti.aderenza(c)) to (c?.takeIf { it > 0 }?.let { "svolta ${it.toInt()}%" } ?: "svolta")
        }
        Esito.NON_SVOLTA -> ColoriBio.rosso to "non svolta"
        Esito.DA_FARE -> MaterialTheme.colorScheme.primary to "da fare"
        Esito.PIANIFICATA -> MaterialTheme.colorScheme.onSurfaceVariant to "pianificata"
      }
  RigaSeduta(
      coloreSport = coloreSport,
      sport = e.sport.etichetta,
      nome = e.nome,
      dettaglio =
          s.consuntivo?.let { c -> riepilogo(c.durataS, c.tss, c.distanzaM) } ?: riepilogo(e.durataS, e.tss, e.distanzaM),
      coloreEsito = coloreEsito,
      testoEsito = testoEsito,
      pieno = s.esito == Esito.SVOLTA,
      onClick = onClick)
}

@Composable
fun CardAttivita(a: AttivitaCal, onClick: () -> Unit) {
  RigaSeduta(
      coloreSport = ColoriBio.sport(a.sport),
      sport = a.sport.etichetta,
      nome = a.nome,
      dettaglio = riepilogo(a.durataS, a.tss, a.distanzaM),
      coloreEsito = ColoriBio.grigio,
      testoEsito = "non pianificata",
      pieno = true,
      onClick = onClick)
}

@Composable
private fun RigaSeduta(
    coloreSport: Color,
    sport: String,
    nome: String,
    dettaglio: String,
    coloreEsito: Color,
    testoEsito: String,
    pieno: Boolean,
    onClick: () -> Unit,
) {
  val forma = RoundedCornerShape(10.dp)
  val sfondo = if (pieno) coloreSport.copy(alpha = 0.12f) else Color.Transparent
  Row(
      Modifier.fillMaxWidth()
          .background(sfondo, forma)
          .border(1.dp, coloreSport.copy(alpha = 0.6f), forma)
          .clickable(onClick = onClick)
          .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(36.dp).background(coloreSport, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
          Text(nome, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
          Text("$sport · $dettaglio", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(testoEsito, style = MaterialTheme.typography.labelSmall, color = coloreEsito)
      }
}

// --- Settimana ------------------------------------------------------------------------------------

@Composable
private fun Settimana(d: DatiOggi) {
  var metrica by rememberSaveable { mutableStateOf(Metrica.DURATA) }
  val barre =
      remember(d, metrica) {
        Allenamenti.settimana(d.eventiSettimana, d.attivitaSettimana, Allenamenti.lunedi(d.oggi), metrica)
      }
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    for (m in Metrica.entries) FilterChip(selected = m == metrica, onClick = { metrica = m }, label = { Text(m.etichetta) })
  }
  val massimo = barre.maxOf { maxOf(it.svolto, it.pianificato) }.takeIf { it > 0 } ?: 1.0
  val primario = MaterialTheme.colorScheme.primary
  val bordo = MaterialTheme.colorScheme.outline
  Row(Modifier.fillMaxWidth().height(120.dp)) {
    for (b in barre) {
      Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
              Box(
                  Modifier.width(9.dp)
                      .fillMaxHeight((b.pianificato / massimo).toFloat())
                      .border(1.dp, bordo, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
              Box(
                  Modifier.width(9.dp)
                      .fillMaxHeight((b.svolto / massimo).toFloat())
                      .background(primario, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
            }
        Text(
            DateIt.iniziale(b.data),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (b.data == d.oggi) FontWeight.Bold else FontWeight.Normal,
            color = if (b.data == d.oggi) primario else MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
  Text(
      stringResource(R.string.oggi_svolto_pianificato, (Formato.metrica(barre.sumOf { it.svolto }, metrica)).toString(), (Formato.metrica(barre.sumOf { it.pianificato }, metrica)).toString()),
      style = MaterialTheme.typography.bodySmall)
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(10.dp).border(1.dp, bordo))
    Text(stringResource(R.string.oggi_pianificato), style = MaterialTheme.typography.labelSmall)
    Box(Modifier.size(10.dp).background(primario))
    Text(stringResource(R.string.oggi_svolto), style = MaterialTheme.typography.labelSmall)
  }
}
