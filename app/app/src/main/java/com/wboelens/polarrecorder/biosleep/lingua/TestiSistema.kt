package com.wboelens.polarrecorder.biosleep.lingua

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * 13d, testi di sistema: le frasi italiane che nascono nella logica (invio, avvio della notte,
 * associazione, interruzioni, Eta', protezione notturna, notifiche) si traducono solo quando
 * vengono MOSTRATE. La logica non cambia: continua a produrre e a confrontare l'italiano.
 * Ogni frase nota e' un modello (%s testo, %d numero) con la sua stringa sis_<chiave>; i valori
 * catturati si traducono a loro volta se sono frasi note (o elenchi separati da "; ").
 * Una frase sconosciuta resta in italiano.
 */
object TestiSistema {
  private const val NUMERO = """([+-]?\d[\d.,]*)"""

  class Voce(val chiave: String, val modello: String) {
    val rx: Regex = run {
      val segnaposto = Regex("%[sd]").findAll(modello).map { it.value }.toList()
      val testi = modello.split(Regex("%[sd]"))
      Regex(buildString {
        for (k in testi.indices) {
          append(Regex.escape(testi[k]))
          if (k < segnaposto.size) append(if (segnaposto[k] == "%d") NUMERO else "(.+?)")
        }
      }, RegexOption.DOT_MATCHES_ALL)
    }
  }

  /** Dal piu' specifico al piu' generico: vince il primo modello che corrisponde all'intera frase. */
  val VOCI: List<Voce> =
      listOf(
      Voce("stima_fc", "Stima dalla sola frequenza cardiaca, senza dati di movimento: la fascia non fornisce RR affidabili. È indicativa: non sostituisce una polisonnografia."),
      Voce("mancante_vo2", "VO2max (assente su Intervals.icu e non stimabile: servono almeno 3 corse in piano o uscite in bici con potenza, di 20+ minuti, negli ultimi 90 giorni)"),
      Voce("stima_senza_acc", "Fasi stimate senza dati di movimento: frequenza cardiaca e HRV (fascia senza accelerometro). È indicativa: non sostituisce una polisonnografia."),
      Voce("stima_fc_acc", "Stima dalla sola frequenza cardiaca e dal movimento: la fascia non fornisce RR affidabili. È indicativa: non sostituisce una polisonnografia."),
      Voce("avviso_senza_hrv", "Questa fascia non misura l'HRV in modo affidabile: avrai durata e fasi del sonno stimate dalla FC, ma niente HRV, prontezza e baseline."),
      Voce("associa_una_volta", "Associa la fascia una volta sola: Android avviserà l'app quando la indossi. Indossala ora (elettrodi bagnati) e premi il pulsante."),
      Voce("stima_hrv_acc_resp", "Stima da frequenza cardiaca, HRV, movimento e respiro (accelerometro H10). È indicativa: non sostituisce una polisonnografia."),
      Voce("stima_hrv_acc", "Stima da frequenza cardiaca, HRV e movimento (accelerometro H10). È indicativa: non sostituisce una polisonnografia."),
      Voce("campi_mancanti", "Intervals non ha salvato: %s. Mancano i campi %s: in Impostazioni premi \"Prepara i campi\" e reinvia la notte."),
      Voce("consiglio_fascia", "Per l'HRV usa una fascia cardio da petto che trasmette gli intervalli tra i battiti, come la Polar H10."),
      Voce("nessuna_notte", "Nessuna notte ancora.\nLe notti compaiono qui dopo lo stop di una registrazione di almeno 10 minuti."),
      Voce("voce_batteria_sp", "La registrazione dura 8 ore a schermo spento: con l'ottimizzazione attiva Android può fermarla."),
      Voce("istr_huawei_2", "Attiva tutte e tre le voci: avvio automatico, avvio secondario, esecuzione in background."),
      Voce("parte_da_sola", "Parte da sola tra %s e %s quando indossi la fascia e il telefono è in carica vicino a te."),
      Voce("indossa_e_premi", "Indossa la fascia e premi Avvia notte. Al mattino si ferma da sola quando la togli."),
      Voce("si_attivera", "Si attiverà dopo %d notti di apprendimento (ora %d). Fino ad allora usa \"Avvia notte\"."),
      Voce("accesso_non_valido", "Accesso a Intervals.icu non valido: ricollega o controlla la API key (HTTP %d)"),
      Voce("collegamento_elettrodi", "Collegamento non riuscito: la fascia è indossata con gli elettrodi bagnati?"),
      Voce("nessuna_impostazione", "Nessuna impostazione salvata: fai prima una registrazione dalle schermate"),
      Voce("voce_notifiche_sp", "Servono per la notifica della notte in corso e per gli avvisi del coach."),
      Voce("non_legge_funzioni", "Collegata, ma non riesco a leggere le funzioni della fascia. Riprova."),
      Voce("collegamento_fallito_std", "Collegamento alla fascia non riuscito (anche con il driver standard)"),
      Voce("disponibile_90", "Disponibile dopo 90 giorni di storico (ora %d giorni con una stima)."),
      Voce("istr_samsung_1", "Togli %s da \"App in sospensione profonda\" e da \"App in sospensione\"."),
      Voce("mancante_allenamento", "Allenamento (Intervals.icu non configurato o non raggiungibile)"),
      Voce("prima_registrazione", "Fai prima una registrazione: serve sapere quale fascia usare."),
      Voce("istr_pixel", "Basta l'ottimizzazione della batteria \"Senza restrizioni\"."),
      Voce("fascia_non_risponde", "La fascia non risponde. Avvicinala al telefono e riprova."),
      Voce("istr_oppo_1", "In Ottimizzazione batteria scegli \"Non ottimizzare\" per %s."),
      Voce("causa_contatto_ora", "nessun battito dalle %s (fascia spostata o senza contatto)"),
      Voce("registrazione_da", "Registrazione %s · si ferma da sola quando togli la fascia"),
      Voce("invio_in_corso", "Invio a Intervals.icu in corso (parte appena c'è rete)"),
      Voce("voce_allarmi_sp", "Servono per avviare e fermare la notte all'ora giusta."),
      Voce("det_allenamento", "%d min/settimana equivalenti (%sx il minimo raccomandato)"),
      Voce("voce_bt_sp", "La fascia trasmette via Bluetooth per tutta la notte."),
      Voce("niente_hrv_ottico", "Niente HRV: solo frequenza cardiaca (sensore ottico)"),
      Voce("hrv_verificata", "HRV verificata nei primi 5 minuti della prima notte"),
      Voce("istr_xiaomi_2", "In Risparmio batteria scegli \"Nessuna restrizione\"."),
      Voce("nessun_battito_da", "Nessun battito da %d minuti: la fascia è indossata?"),
      Voce("niente_hrv_verificato", "Niente HRV: solo frequenza cardiaca (verificato)"),
      Voce("addorm_completo", "Addormentamento alle %s (dopo %s') · risveglio alle %s"),
      Voce("registrazione_non_partita", "La registrazione non è partita, controlla l'app"),
      Voce("istr_altra", "Imposta la batteria di %s su \"Senza restrizioni\"."),
      Voce("istr_huawei_1", "In Gestione avvio porta %s su gestione manuale."),
      Voce("aggiungi_tag", "Aggiungi i tag: il coach li aspetta 15 minuti"),
      Voce("init_salvataggi_fallita", "Inizializzazione dei salvataggi non riuscita"),
      Voce("det_stimato", "stimato da %d corse e %d uscite in bici, FC max %d"),
      Voce("fascia_non_trovata", "Fascia %s non trovata: è indossata e vicina?"),
      Voce("det_vo2", "VO2max %s (%s) contro %s tipico per eta' e sesso"),
      Voce("puoi_reinviarla", "Puoi reinviarla dalla scheda della notte."),
      Voce("dati_rifiutati", "Dati rifiutati da Intervals (HTTP 422): %s"),
      Voce("ritmo", "%s anni biologici per anno di calendario %s"),
      Voce("det_regolarita", "orario del sonno variabile di +-%d minuti"),
      Voce("addorm_risveglio", "Addormentamento alle %s · risveglio alle %s"),
      Voce("istr_samsung_2", "Aggiungi %s ad \"App mai in sospensione\"."),
      Voce("collegamento_fallito", "Collegamento alla fascia non riuscito"),
      Voce("causa_portata_ora", "fascia fuori portata o spenta alle %s"),
      Voce("opzioni_avanzate", "Opzioni avanzate: API key personale"),
      Voce("collegamento_in_corso", "Collegamento alla fascia in corso…"),
      Voce("stato_fascia_nd", "Stato della fascia non disponibile"),
      Voce("notte_non_inviata", "Notte non inviata a Intervals.icu"),
      Voce("intervals_non_ancora", "Intervals.icu: non ancora inviata"),
      Voce("gestore_bt", "Gestore Bluetooth non disponibile"),
      Voce("richiede_android13", "Richiede Android 13 o successivo."),
      Voce("calcolo_invio_punti", "Calcolo e invio a Intervals.icu…"),
      Voce("calcolo_invio", "Calcolo e invio a Intervals.icu"),
      Voce("istr_xiaomi_1", "Attiva l'Avvio automatico per %s."),
      Voce("coach_analisi", "Analisi della notte e del piano"),
      Voce("avvio_non_riuscito_titolo", "Avvio della notte non riuscito"),
      Voce("impossibile_leggere_notti", "Impossibile leggere le notti:\n%s"),
      Voce("battito_senza_movimento", "Battito e HRV, senza movimento"),
      Voce("piano_settimana", "Piano della settimana: %d sedute%s"),
      Voce("intervals_non_inviata", "Intervals.icu: non inviata (%s)"),
      Voce("campi_pronti_creati", "Campi %s pronti: %d (%d creati ora)"),
      Voce("causa_app_ora", "app chiusa dal sistema alle %s"),
      Voce("intervals_inviata_il", "Intervals.icu: ✓ inviata il %s"),
      Voce("non_collegato", "Intervals.icu non collegato"),
      Voce("associazione_fallita", "Associazione non riuscita: %s"),
      Voce("inviata", "Inviata a Intervals.icu (%s)"),
      Voce("connessione_non_riuscita", "Connessione non riuscita: %s"),
      Voce("salvataggi_nd", "Salvataggi non disponibili"),
      Voce("impostazioni_intervals", "Impostazioni Intervals.icu"),
      Voce("voce_batteria", "Batteria senza restrizioni"),
      Voce("istr_oppo_2", "Attiva l'Avvio automatico."),
      Voce("notifica_sonno", "Sonno %s · profondo %s · REM %s"),
      Voce("voce_allarmi", "Allarmi esatti consentiti"),
      Voce("seduta_aggiornata", "Seduta di oggi aggiornata"),
      Voce("nascondi_avanzate", "Nascondi opzioni avanzate"),
      Voce("errore_http", "Errore Intervals HTTP %d: %s"),
      Voce("segnale_ok", "Segnale della fascia: OK"),
      Voce("conn_non_riuscita", "connessione non riuscita"),
      Voce("notifica_fc", "FC riposo %s · rMSSD %s ms"),
      Voce("causa_bt_ora", "Bluetooth spento alle %s"),
      Voce("causa_app", "app chiusa dal sistema"),
      Voce("com_e_andata", "Com'è andata la notte?"),
      Voce("collegato_atleta", "Collegato come atleta %s"),
      Voce("campi_non_preparati", "Campi %s non preparati: %s"),
      Voce("causa_contatto", "fascia senza contatto"),
      Voce("addormentamento_alle", "Addormentamento alle %s"),
      Voce("comp_regolarita", "Regolarita' del sonno"),
      Voce("causa_portata", "fascia fuori portata"),
      Voce("connessione_riuscita", "Connessione riuscita"),
      Voce("associazione_rimossa", "Associazione rimossa"),
      Voce("voce_notifiche", "Notifiche consentite"),
      Voce("errore_database", "Errore del database"),
      Voce("analisi_della_notte", "Analisi della notte"),
      Voce("movimento_respiro", "movimento e respiro"),
      Voce("anello_centro", "Centro: allenamento"),
      Voce("det_durata", "mediana %s h di sonno"),
      Voce("risposta_non_valida", "risposta non valida"),
      Voce("non_ancora_inviata", "Non ancora inviata"),
      Voce("risveglio_alle", " · risveglio alle %s"),
      Voce("eta_anagrafica", "Età anagrafica %s · %s"),
      Voce("stai_rallentando", "(stai rallentando)"),
      Voce("stai_accelerando", "(stai accelerando)"),
      Voce("causa_altra_1", "%s, +1 interruzione"),
      Voce("notte_non_trovata", "Notte non trovata"),
      Voce("avvio_della_notte", "Avvio della notte"),
      Voce("non_disponibili", "Non disponibili: %s"),
      Voce("causa_bt", "Bluetooth spento"),
      Voce("causa_altre", "%s, +%d interruzioni"),
      Voce("fascia_associata", "Fascia associata"),
      Voce("anello_esterno", "Esterno: fitness"),
      Voce("comp_fitness", "Fitness (VO2max)"),
      Voce("comp_durata_sonno", "Durata del sonno"),
      Voce("voce_bt", "Bluetooth attivo"),
      Voce("canale_riepilogo_notte", "Riepilogo notte"),
      Voce("avvio_in_corso", "Avvio in corso…"),
      Voce("il_tuo_telefono", "il tuo telefono"),
      Voce("coach_al_lavoro", "Coach al lavoro"),
      Voce("campi_pronti", "Campi %s pronti: %d"),
      Voce("interruzioni", "Interruzioni: %s"),
      Voce("connesso_come", "Connesso come %s"),
      Voce("invia_di_nuovo", "Invia di nuovo"),
      Voce("notte_in_corso", "Notte in corso"),
      Voce("anello_interno", "Interno: sonno"),
      Voce("notifica_ore", "%s h registrate"),
      Voce("inviata_il", "✓ Inviata il %s"),
      Voce("non_inviata", "Non inviata: %s"),
      Voce("elimina_notte", "Elimina notte"),
      Voce("battito_hrv", "battito e HRV"),
      Voce("anni_in_meno", "%s anni in meno"),
      Voce("la_tua_notte", "La tua notte"),
      Voce("le_mie_notti", "Le mie notti"),
      Voce("tra_anni", "tra %s e %s anni"),
      Voce("anni_in_piu", "%s anni in più"),
      Voce("notti_valide", "notti valide"),
      Voce("piano_pronto", "Piano pronto"),
      Voce("da_sistemare", "Da sistemare"),
      Voce("disattivato", "Disattivato"),
      Voce("avvia_notte", "Avvia notte"),
      Voce("comp_allenamento", "Allenamento"),
      Voce("minuti_persi", "%d' persi: %s"),
      Voce("dopo_minuti", " (dopo %s')"),
      Voce("annullata", "annullata"),
      Voce("letta_il", "letta il %s"),
      Voce("fc_riposo", "FC riposo"),
      Voce("movimento", "Movimento"),
      Voce("invia_ora", "Invia ora"),
      Voce("collegato", "Collegato"),
      Voce("indietro", "Indietro"),
      Voce("fc_media", "FC media"),
      Voce("riprova", "Riprova"),
      Voce("errore_codice", "errore %d"),
      Voce("a_posto", "A posto"),
      Voce("attivo", "Attivo"),
      Voce("errore", "errore"),
      Voce("notte", "Notte"),
      Voce("sonno", "Sonno"),
      Voce("anni", "%d anni"),
      Voce("canale_coach", "Coach"),
      Voce("causa_minuti", "%s (%d')"),
      Voce("eta_app", "Età %s"),
      Voce("da_durata", "da %s"),
      Voce("numerato", "%d. %s"),
      )

  /** Traduzione pura: [stringa] cerca la risorsa (nome, argomenti), null se non c'e'. */
  fun traduci(testo: String, stringa: (String, Array<String>) -> String?): String {
    if (testo.isBlank()) return testo
    for (v in VOCI) {
      val m = v.rx.matchEntire(testo) ?: continue
      val argomenti = m.groupValues.drop(1).map { parte(it, stringa) }
      return stringa("sis_" + v.chiave, argomenti.toTypedArray()) ?: testo
    }
    return testo
  }

  private fun parte(g: String, stringa: (String, Array<String>) -> String?): String {
    val t = traduci(g, stringa)
    if (t != g) return t
    return if ("; " in g) g.split("; ").joinToString("; ") { traduci(it, stringa) } else g
  }

  fun traduci(context: Context, testo: String): String =
      traduci(testo) { nome, args ->
        val id = context.resources.getIdentifier(nome, "string", context.packageName)
        if (id == 0) null else context.getString(id, *args)
      }

  /** Testo di piu' righe (notifiche): prima intero, poi riga per riga. */
  fun righe(context: Context, testo: String): String {
    val intero = traduci(context, testo)
    return if (intero != testo || "\n" !in testo) intero else testo.lines().joinToString("\n") { traduci(context, it) }
  }
}

/** Nelle schermate: la frase nella lingua dell'app (si ricalcola se la lingua cambia). */
@Composable
fun tr(testo: String): String {
  val context = LocalContext.current
  val configurazione = LocalConfiguration.current
  return remember(testo, configurazione) { TestiSistema.traduci(context, testo) }
}
