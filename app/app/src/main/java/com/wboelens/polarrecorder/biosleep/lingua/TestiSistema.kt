package com.wboelens.polarrecorder.biosleep.lingua

import android.content.Context
import android.content.res.Configuration
import com.wboelens.polarrecorder.biosleep.cervello.Lingua
import java.util.Locale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.wboelens.polarrecorder.biosleep.riepilogo.Messaggi
import com.wboelens.polarrecorder.biosleep.riepilogo.TraduzioneMessaggi

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
      Voce("linea_8h", "Linea: fabbisogno %s (8 h finché le notti sono meno di 14: %d/14). Barre grigie: notti non registrate, non contano. Una notte più lunga del fabbisogno riduce il deficit."),
      Voce("stima_fc", "Stima dalla sola frequenza cardiaca, senza dati di movimento: la fascia non fornisce RR affidabili. È indicativa: non sostituisce una polisonnografia."),
      Voce("mancante_vo2", "VO2max (assente su Intervals.icu e non stimabile: servono almeno 3 corse in piano o uscite in bici con potenza, di 20+ minuti, negli ultimi 90 giorni)"),
      Voce("linea_appreso", "Linea: fabbisogno %s (appreso dalle tue notti). Barre grigie: notti non registrate, non contano. Una notte più lunga del fabbisogno riduce il deficit."),
      Voce("stima_senza_acc", "Fasi stimate senza dati di movimento: frequenza cardiaca e HRV (fascia senza accelerometro). È indicativa: non sostituisce una polisonnografia."),
      Voce("stima_fc_acc", "Stima dalla sola frequenza cardiaca e dal movimento: la fascia non fornisce RR affidabili. È indicativa: non sostituisce una polisonnografia."),
      Voce("avviso_senza_hrv", "Questa fascia non misura l'HRV in modo affidabile: avrai durata e fasi del sonno stimate dalla FC, ma niente HRV, prontezza e baseline."),
      Voce("associa_una_volta", "Associa la fascia una volta sola: Android avviserà l'app quando la indossi. Indossala ora (elettrodi bagnati) e premi il pulsante."),
      Voce("stima_hrv_acc_resp", "Stima da frequenza cardiaca, HRV, movimento e respiro (accelerometro H10). È indicativa: non sostituisce una polisonnografia."),
      Voce("stima_hrv_acc", "Stima da frequenza cardiaca, HRV e movimento (accelerometro H10). È indicativa: non sostituisce una polisonnografia."),
      Voce("lingua_sedute", "Lingua delle sedute che il coach scrive sul calendario e sull'orologio. L'app segue la lingua del telefono."),
      Voce("sc_soglie", "Le soglie aggiornate dal coach (FTP, LTHR, passo, CSS) restano come sono: non tornano ai valori precedenti."),
      Voce("campi_mancanti", "Intervals non ha salvato: %s. Mancano i campi %s: in Impostazioni premi \"Prepara i campi\" e reinvia la notte."),
      Voce("consiglio_fascia", "Per l'HRV usa una fascia cardio da petto che trasmette gli intervalli tra i battiti, come la Polar H10."),
      Voce("nessuna_notte", "Nessuna notte ancora.\nLe notti compaiono qui dopo lo stop di una registrazione di almeno 10 minuti."),
      Voce("voce_batteria_sp", "La registrazione dura 8 ore a schermo spento: con l'ottimizzazione attiva Android può fermarla."),
      Voce("istr_huawei_2", "Attiva tutte e tre le voci: avvio automatico, avvio secondario, esecuzione in background."),
      Voce("parte_da_sola", "Parte da sola tra %s e %s quando indossi la fascia e il telefono è in carica vicino a te."),
      Voce("sc_permesso", "Intervals.icu non permette di leggere il calendario: puoi scollegare senza eliminare."),
      Voce("indossa_e_premi", "Indossa la fascia e premi Avvia notte. Al mattino si ferma da sola quando la togli."),
      Voce("resto_aerobico", "Il resto del volume è aerobico facile. La linea è il tetto che il coach non supera."),
      Voce("si_attivera", "Si attiverà dopo %d notti di apprendimento (ora %d). Fino ad allora usa \"Avvia notte\"."),
      Voce("lingua_app_sedute", "Lingua dell'app e delle sedute che il coach scrive sul calendario e sull'orologio."),
      Voce("polarizzazione", "Polarizzazione 80/20: al massimo il 20% del tempo di bici e corsa sopra la soglia"),
      Voce("prep_gara", "Preparazione gara: al massimo il 10% del tempo di bici e corsa sopra la soglia"),
      Voce("accesso_non_valido", "Accesso a Intervals.icu non valido: ricollega o controlla la API key (HTTP %d)"),
      Voce("collegamento_elettrodi", "Collegamento non riuscito: la fascia è indossata con gli elettrodi bagnati?"),
      Voce("ripianificazione_in_corso", "Ripianificazione in corso: il riepilogo arriverà con la notifica del coach"),
      Voce("nessuna_impostazione", "Nessuna impostazione salvata: fai prima una registrazione dalle schermate"),
      Voce("voce_notifiche_sp", "Servono per la notifica della notte in corso e per gli avvisi del coach."),
      Voce("sc_parziale", "Eliminati %d eventi su %d e %d campi su %d: controlla il resto su Intervals.icu."),
      Voce("non_legge_funzioni", "Collegata, ma non riesco a leggere le funzioni della fascia. Riprova."),
      Voce("collegamento_fallito_std", "Collegamento alla fascia non riuscito (anche con il driver standard)"),
      Voce("coach_partito", "Il coach di oggi è già partito: questi tag valgono dal prossimo run."),
      Voce("disponibile_90", "Disponibile dopo 90 giorni di storico (ora %d giorni con una stima)."),
      Voce("istr_samsung_1", "Togli %s da \"App in sospensione profonda\" e da \"App in sospensione\"."),
      Voce("range_media7", "Il range si confronta con la media 7 gg, non con la singola notte."),
      Voce("pila", "Potrebbe spegnersi durante la notte: sostituisci la pila (CR2025)."),
      Voce("mancante_allenamento", "Allenamento (Intervals.icu non configurato o non raggiungibile)"),
      Voce("tocca_grafico", "Tocca o trascina un grafico per leggere i valori in quel punto"),
      Voce("prima_registrazione", "Fai prima una registrazione: serve sapere quale fascia usare."),
      Voce("fiore_senza_rr", "Questa fascia non misura gli intervalli RR: il fiore ti guida"),
      Voce("pausa_sparita", "La pausa non c'è più su Intervals.icu: calendario aggiornato"),
      Voce("fiore_seguira", "Il fiore seguirà il tuo respiro appena la fascia è collegata"),
      Voce("coach_aspetta", "Il coach aspetta i tuoi tag fino alle %s: parte appena salvi."),
      Voce("istr_pixel", "Basta l'ottimizzazione della batteria \"Senza restrizioni\"."),
      Voce("fascia_non_risponde", "La fascia non risponde. Avvicinala al telefono e riprova."),
      Voce("istr_oppo_1", "In Ottimizzazione batteria scegli \"Non ottimizzare\" per %s."),
      Voce("causa_contatto_ora", "nessun battito dalle %s (fascia spostata o senza contatto)"),
      Voce("registrazione_da", "Registrazione %s · si ferma da sola quando togli la fascia"),
      Voce("invio_in_corso", "Invio a Intervals.icu in corso (parte appena c'è rete)"),
      Voce("voce_allarmi_sp", "Servono per avviare e fermare la notte all'ora giusta."),
      Voce("sc_non_eliminato", "Eliminazione non riuscita: non ho scollegato. Riprova."),
      Voce("det_allenamento", "%d min/settimana equivalenti (%sx il minimo raccomandato)"),
      Voce("voce_bt_sp", "La fascia trasmette via Bluetooth per tutta la notte."),
      Voce("niente_hrv_ottico", "Niente HRV: solo frequenza cardiaca (sensore ottico)"),
      Voce("hrv_verificata", "HRV verificata nei primi 5 minuti della prima notte"),
      Voce("istr_xiaomi_2", "In Risparmio batteria scegli \"Nessuna restrizione\"."),
      Voce("piano_rimodulato_n", "🗓️ Piano settimanale — rimodulazione del %s, banda %s:"),
      Voce("nessun_battito_da", "Nessun battito da %d minuti: la fascia è indossata?"),
      Voce("fiore_taratura", "Il fiore segue il tuo respiro · taratura in corso"),
      Voce("niente_hrv_verificato", "Niente HRV: solo frequenza cardiaca (verificato)"),
      Voce("addorm_completo", "Addormentamento alle %s (dopo %s') · risveglio alle %s"),
      Voce("fiore_dal_battito", "Il fiore segue il tuo respiro, letto dal battito"),
      Voce("registrazione_non_partita", "La registrazione non è partita, controlla l'app"),
      Voce("istr_altra", "Imposta la batteria di %s su \"Senza restrizioni\"."),
      Voce("fiore_guida", "Inspira mentre si apre, espira mentre si chiude"),
      Voce("obiettivo_domenica_fascia", "Obiettivo a domenica: TSB %s (fascia attesa %s / %s)"),
      Voce("istr_huawei_1", "In Gestione avvio porta %s su gestione manuale."),
      Voce("aggiungi_tag", "Aggiungi i tag: il coach li aspetta 15 minuti"),
      Voce("attesa_battito", "In attesa del battito: la fascia è indossata?"),
      Voce("sc_eliminati", "Eliminati da Intervals.icu: %d eventi e %d campi."),
      Voce("init_salvataggi_fallita", "Inizializzazione dei salvataggi non riuscita"),
      Voce("det_stimato", "stimato da %d corse e %d uscite in bici, FC max %d"),
      Voce("fascia_non_trovata", "Fascia %s non trovata: è indossata e vicina?"),
      Voce("det_vo2", "VO2max %s (%s) contro %s tipico per eta' e sesso"),
      Voce("puoi_reinviarla", "Puoi reinviarla dalla scheda della notte."),
      Voce("batt_al_collegamento", "Batteria fascia: si legge al collegamento"),
      Voce("sc_campi_n", "Campi dell'app nel profilo da eliminare: %d"),
      Voce("dati_rifiutati", "Dati rifiutati da Intervals (HTTP 422): %s"),
      Voce("ritmo", "%s anni biologici per anno di calendario %s"),
      Voce("det_regolarita", "orario del sonno variabile di +-%d minuti"),
      Voce("addorm_risveglio", "Addormentamento alle %s · risveglio alle %s"),
      Voce("sc_controllo", "Controllo di cosa c'è su Intervals.icu…"),
      Voce("istr_samsung_2", "Aggiungi %s ad \"App mai in sospensione\"."),
      Voce("range_dopo14", "Il range compare dopo 14 notti valide."),
      Voce("sc_opz_campi", "Elimina anche i campi %s dal mio profilo"),
      Voce("collegamento_fallito", "Collegamento alla fascia non riuscito"),
      Voce("sc_eventi", "Eventi creati dall'app da eliminare: %d"),
      Voce("causa_portata_ora", "fascia fuori portata o spenta alle %s"),
      Voce("opzioni_avanzate", "Opzioni avanzate: API key personale"),
      Voce("ultima_notte_giorno", "Ultima notte registrata: %d giorno fa"),
      Voce("ultima_notte_giorni", "Ultima notte registrata: %d giorni fa"),
      Voce("varra_prossima", "Varrà dalla prossima pianificazione"),
      Voce("licenza_non_trovata", "Testo della licenza non trovato (%s)."),
      Voce("collegamento_in_corso", "Collegamento alla fascia in corso…"),
      Voce("stato_fascia_nd", "Stato della fascia non disponibile"),
      Voce("riquadro", "riquadro: fascia attesa a domenica"),
      Voce("coach_prossimo", "Il coach li userà al prossimo run."),
      Voce("fiore_attesa_respiro", "In attesa del respiro dalla fascia"),
      Voce("notte_non_inviata", "Notte non inviata a Intervals.icu"),
      Voce("intervals_non_ancora", "Intervals.icu: non ancora inviata"),
      Voce("gestore_bt", "Gestore Bluetooth non disponibile"),
      Voce("richiede_android13", "Richiede Android 13 o successivo."),
      Voce("calcolo_invio_punti", "Calcolo e invio a Intervals.icu…"),
      Voce("tratteggio", "tratteggio: previsione del coach"),
      Voce("sc_prima", "%s prima rispetto al tuo cronotipo"),
      Voce("calcolo_invio", "Calcolo e invio a Intervals.icu"),
      Voce("istr_xiaomi_1", "Attiva l'Avvio automatico per %s."),
      Voce("coach_analisi", "Analisi della notte e del piano"),
      Voce("sc_dopo", "%s dopo rispetto al tuo cronotipo"),
      Voce("avvio_non_riuscito_titolo", "Avvio della notte non riuscito"),
      Voce("impossibile_leggere_notti", "Impossibile leggere le notti:\n%s"),
      Voce("battito_senza_movimento", "Battito e HRV, senza movimento"),
      Voce("piano_settimana", "Piano della settimana: %d sedute%s"),
      Voce("salvato_parte", "Salvato: il coach parte adesso"),
      Voce("piano_n_sedute", "Piano della settimana: %d sedute"),
      Voce("intervals_non_inviata", "Intervals.icu: non inviata (%s)"),
      Voce("campi_pronti_creati", "Campi %s pronti: %d (%d creati ora)"),
      Voce("ferie_malattia", "Ferie / Malattia / Infortunio"),
      Voce("fiore_segue", "Il fiore segue il tuo respiro"),
      Voce("sc_in_linea", "in linea con il tuo cronotipo"),
      Voce("causa_app_ora", "app chiusa dal sistema alle %s"),
      Voce("intervals_inviata_il", "Intervals.icu: ✓ inviata il %s"),
      Voce("oggi_tsb_giorno", "Oggi TSB %s · zona %s da %d giorno"),
      Voce("oggi_tsb_giorni", "Oggi TSB %s · zona %s da %d giorni"),
      Voce("tss_calendario", "%d con le sedute in calendario"),
      Voce("non_collegato", "Intervals.icu non collegato"),
      Voce("associazione_fallita", "Associazione non riuscita: %s"),
      Voce("inviata", "Inviata a Intervals.icu (%s)"),
      Voce("connessione_non_riuscita", "Connessione non riuscita: %s"),
      Voce("salvataggi_nd", "Salvataggi non disponibili"),
      Voce("impostazioni_intervals", "Impostazioni Intervals.icu"),
      Voce("voce_batteria", "Batteria senza restrizioni"),
      Voce("istr_oppo_2", "Attiva l'Avvio automatico."),
      Voce("obiettivo_domenica", "Obiettivo a domenica: TSB %s"),
      Voce("notifica_sonno", "Sonno %s · profondo %s · REM %s"),
      Voce("voce_allarmi", "Allarmi esatti consentiti"),
      Voce("seduta_aggiornata", "Seduta di oggi aggiornata"),
      Voce("nascondi_avanzate", "Nascondi opzioni avanzate"),
      Voce("aggiorna_da_intervals", "Aggiorna da Intervals.icu"),
      Voce("informativa", "Informativa sulla privacy"),
      Voce("collegamento_breve", "Collegamento alla fascia…"),
      Voce("errore_http", "Errore Intervals HTTP %d: %s"),
      Voce("segnale_ok", "Segnale della fascia: OK"),
      Voce("conn_non_riuscita", "connessione non riuscita"),
      Voce("tr_fc", "Frequenza cardiaca (bpm)"),
      Voce("non_riuscito", "Non riuscito: %s. Riprova."),
      Voce("sc_per_tipo", "%d sedute · %d gare · %d pause"),
      Voce("sc_opz_passato", "Anche lo storico passato"),
      Voce("sc_errore", "Controllo non riuscito: %s"),
      Voce("sc_solo", "Scollega senza eliminare"),
      Voce("attesa_fase", "attesa in questa fase: %s"),
      Voce("linea_14", "linea: ultimi 14 giorni"),
      Voce("notifica_fc", "FC riposo %s · rMSSD %s ms"),
      Voce("causa_bt_ora", "Bluetooth spento alle %s"),
      Voce("causa_app", "app chiusa dal sistema"),
      Voce("com_e_andata", "Com'è andata la notte?"),
      Voce("collegato_atleta", "Collegato come atleta %s"),
      Voce("campi_non_preparati", "Campi %s non preparati: %s"),
      Voce("rampa_max", "Rampa CTL %s/sett (max %s)"),
      Voce("sc_titolo", "Scollega Intervals.icu"),
      Voce("sc_in_corso", "Eliminazione in corso…"),
      Voce("causa_contatto", "fascia senza contatto"),
      Voce("addormentamento_alle", "Addormentamento alle %s"),
      Voce("comp_regolarita", "Regolarita' del sonno"),
      Voce("piano_della_settimana", "Piano della settimana"),
      Voce("fc7", "FC a riposo 7 gg %s bpm"),
      Voce("sotto_range_gg", "sotto il range da %d gg"),
      Voce("fattore_efficienza", "Fattore di efficienza"),
      Voce("cr_molto_matt", "Decisamente mattutino"),
      Voce("causa_portata", "fascia fuori portata"),
      Voce("connessione_riuscita", "Connessione riuscita"),
      Voce("associazione_rimossa", "Associazione rimossa"),
      Voce("voce_notifiche", "Notifiche consentite"),
      Voce("fc_riposo_stanotte", "FC a riposo stanotte"),
      Voce("analisi_seduta", "Analisi della seduta"),
      Voce("obiettivo_settimana", "obiettivo settimana %s"),
      Voce("lettura_battito", "Lettura del battito…"),
      Voce("segui_respiro", "Segui il mio respiro"),
      Voce("piano_settimanale_n", "🗓️ Piano settimanale"),
      Voce("sc_niente", "Niente da eliminare."),
      Voce("errore_database", "Errore del database"),
      Voce("analisi_della_notte", "Analisi della notte"),
      Voce("movimento_respiro", "movimento e respiro"),
      Voce("anello_centro", "Centro: allenamento"),
      Voce("det_durata", "mediana %s h di sonno"),
      Voce("risposta_non_valida", "risposta non valida"),
      Voce("da_intervals", "%s (da Intervals.icu)"),
      Voce("ultima_notte_gg", "ultima notte %d gg fa"),
      Voce("nessun_tag_oggi", "Nessun tag per oggi"),
      Voce("non_ancora_inviata", "Non ancora inviata"),
      Voce("risveglio_alle", " · risveglio alle %s"),
      Voce("eta_anagrafica", "Età anagrafica %s · %s"),
      Voce("stai_rallentando", "(stai rallentando)"),
      Voce("stai_accelerando", "(stai accelerando)"),
      Voce("batt_livello", "Batteria fascia: %d%"),
      Voce("notte_in_corso_da", "Notte in corso da %s"),
      Voce("cr_molto_serale", "Decisamente serale"),
      Voce("sc_elimina_scollega", "Elimina e scollega"),
      Voce("causa_altra_1", "%s, +1 interruzione"),
      Voce("notte_non_trovata", "Notte non trovata"),
      Voce("avvio_della_notte", "Avvio della notte"),
      Voce("non_disponibili", "Non disponibili: %s"),
      Voce("aderenza_piano", "Aderenza al piano"),
      Voce("tempo_disponibile", "Tempo disponibile"),
      Voce("valori_non_validi", "Valori non validi"),
      Voce("salute_contesto", "Salute e contesto"),
      Voce("causa_bt", "Bluetooth spento"),
      Voce("causa_altre", "%s, +%d interruzioni"),
      Voce("fascia_associata", "Fascia associata"),
      Voce("anello_esterno", "Esterno: fitness"),
      Voce("comp_fitness", "Fitness (VO2max)"),
      Voce("comp_durata_sonno", "Durata del sonno"),
      Voce("voce_bt", "Bluetooth attivo"),
      Voce("zona_giorno", "Zona %s da %d giorno"),
      Voce("zona_giorni", "Zona %s da %d giorni"),
      Voce("ctl_forma_fisica", "CTL forma fisica"),
      Voce("disaccoppiamento", "Disaccoppiamento"),
      Voce("fatica_percepita", "Fatica percepita"),
      Voce("t_sonno", "Sonno disturbato"),
      Voce("t_dolore", "Dolore muscolare"),
      Voce("canale_riepilogo_notte", "Riepilogo notte"),
      Voce("avvio_in_corso", "Avvio in corso…"),
      Voce("il_tuo_telefono", "il tuo telefono"),
      Voce("coach_al_lavoro", "Coach al lavoro"),
      Voce("campi_pronti", "Campi %s pronti: %d"),
      Voce("non_pianificata", "non pianificata"),
      Voce("rampa", "Rampa CTL %s/sett"),
      Voce("restano_gg", "restano %s in %d gg"),
      Voce("tss_fatti_di", "TSS: %d fatti di %d"),
      Voce("tr_velocita", "Velocità (km/h)"),
      Voce("non_disponibile", "Non disponibile"),
      Voce("interruzioni", "Interruzioni: %s"),
      Voce("connesso_come", "Connesso come %s"),
      Voce("invia_di_nuovo", "Invia di nuovo"),
      Voce("notte_in_corso", "Notte in corso"),
      Voce("anello_interno", "Interno: sonno"),
      Voce("non_valutabile", "Non valutabile"),
      Voce("media7_ms", "Media 7 gg %s ms"),
      Voce("sonno_stanotte", "Sonno stanotte"),
      Voce("atl_stanchezza", "ATL stanchezza"),
      Voce("tr_altitudine", "Altitudine (m)"),
      Voce("tag_del_giorno", "Tag del giorno"),
      Voce("t_caffeina", "Caffeina tardi"),
      Voce("c_profondo", "Sonno profondo"),
      Voce("notifica_ore", "%s h registrate"),
      Voce("inviata_il", "✓ Inviata il %s"),
      Voce("non_inviata", "Non inviata: %s"),
      Voce("elimina_notte", "Elimina notte"),
      Voce("battito_hrv", "battito e HRV"),
      Voce("anni_in_meno", "%s anni in meno"),
      Voce("range_normale", "range normale"),
      Voce("potenza_norm", "Potenza norm."),
      Voce("potenza_media", "Potenza media"),
      Voce("tr_passo100", "Passo (/100m)"),
      Voce("t_gambe", "Gambe pesanti"),
      Voce("la_tua_notte", "La tua notte"),
      Voce("le_mie_notti", "Le mie notti"),
      Voce("tra_anni", "tra %s e %s anni"),
      Voce("anni_in_piu", "%s anni in più"),
      Voce("notti_valide", "notti valide"),
      Voce("piano_pronto", "Piano pronto"),
      Voce("da_sistemare", "Da sistemare"),
      Voce("impostazioni", "Impostazioni"),
      Voce("hrv_stanotte", "HRV stanotte"),
      Voce("forma_fisica", "Forma fisica"),
      Voce("z_alto_rischio", "Alto rischio"),
      Voce("hrv7", "HRV 7 gg %s ms"),
      Voce("carico_tss", "Carico (TSS)"),
      Voce("aggiungi_tag_btn", "Aggiungi tag"),
      Voce("giorno_prima", "Giorno prima"),
      Voce("t_cena", "Cena tardiva"),
      Voce("disattivato", "Disattivato"),
      Voce("avvia_notte", "Avvia notte"),
      Voce("comp_allenamento", "Allenamento"),
      Voce("media7", "media 7 gg %s"),
      Voce("pianificata", "pianificata"),
      Voce("transizione", "Transizione"),
      Voce("pianificato", "Pianificato"),
      Voce("in_crescita", "in crescita"),
      Voce("tss_fatti", "TSS: %d fatti"),
      Voce("alleggerita", "alleggerita"),
      Voce("tr_potenza", "Potenza (W)"),
      Voce("tr_passokm", "Passo (/km)"),
      Voce("giorno_dopo", "Giorno dopo"),
      Voce("t_fatica", "Fatica alta"),
      Voce("a_anticipo", "In anticipo"),
      Voce("calendario", "Calendario"),
      Voce("range_ms", "range %s–%s ms"),
      Voce("non_svolta", "non svolta"),
      Voce("stanchezza", "Stanchezza"),
      Voce("multisport", "Multisport"),
      Voce("infortunio", "Infortunio"),
      Voce("media_7gg", "media 7 gg"),
      Voce("senza_gara", "senza_gara"),
      Voce("andamento", "andamento %s"),
      Voce("in_aumento", "in aumento"),
      Voce("palestra_val", "Palestra: %s"),
      Voce("aggiornata", "Aggiornata"),
      Voce("dislivello", "Dislivello"),
      Voce("fc_soglia_pct", "%s FC soglia"),
      Voce("t_altitudine", "Altitudine"),
      Voce("c_efficienza", "Efficienza"),
      Voce("c_continuita", "Continuità"),
      Voce("a_ritardo", "In ritardo"),
      Voce("cr_intermedio", "Intermedio"),
      Voce("minuti_persi", "%d' persi: %s"),
      Voce("dopo_minuti", " (dopo %s')"),
      Voce("annullata", "annullata"),
      Voce("letta_il", "letta il %s"),
      Voce("fc_riposo", "FC riposo"),
      Voce("movimento", "Movimento"),
      Voce("invia_ora", "Invia ora"),
      Voce("collegato", "Collegato"),
      Voce("abituale", "abituale %s"),
      Voce("baseline", "baseline %s"),
      Voce("b_arancione", "Arancione"),
      Voce("ore_di", "Ore: %s di %s"),
      Voce("intensita", "Intensità"),
      Voce("t_malessere", "Malessere"),
      Voce("zc_ginocchio", "Ginocchio"),
      Voce("zc_polpaccio", "Polpaccio"),
      Voce("da_curare", "Da curare"),
      Voce("c_rem", "Sonno REM"),
      Voce("cr_matt", "Mattutino"),
      Voce("banda_arancione_min", "arancione"),
      Voce("indietro", "Indietro"),
      Voce("fc_media", "FC media"),
      Voce("aggiungi", "Aggiungi"),
      Voce("coach_ora", "Coach · %s"),
      Voce("svolta_pct", "svolta %d%"),
      Voce("distanza", "Distanza"),
      Voce("palestra", "Palestra"),
      Voce("z_ottimale", "Ottimale"),
      Voce("fino_al", "fino al %s"),
      Voce("malattia", "Malattia"),
      Voce("restano", "restano %s"),
      Voce("velocita", "Velocità"),
      Voce("recupero", "Recupero"),
      Voce("dal_al", "Dal %s al %s"),
      Voce("scelti", "Scelti: %s"),
      Voce("modifica", "Modifica"),
      Voce("zc_caviglia", "Caviglia"),
      Voce("l_moderato", "MODERATO"),
      Voce("l_moderato_m", "Moderato"),
      Voce("a_in_linea", "In linea"),
      Voce("riprova", "Riprova"),
      Voce("errore_codice", "errore %d"),
      Voce("a_posto", "A posto"),
      Voce("grafici", "Grafici"),
      Voce("oggi_data", "Oggi · %s"),
      Voce("da_fare", "da fare"),
      Voce("stabile", "stabile"),
      Voce("in_calo", "in calo"),
      Voce("qualita", "qualità"),
      Voce("cadenza", "Cadenza"),
      Voce("calorie", "Calorie"),
      Voce("fc_max_pct", "%s FC max"),
      Voce("t_viaggio", "Viaggio"),
      Voce("zc_schiena", "Schiena"),
      Voce("salvato", "Salvato"),
      Voce("guidami", "Guidami"),
      Voce("c_latenza", "Latenza"),
      Voce("l_nessuno", "NESSUNO"),
      Voce("l_nessuno_m", "Nessuno"),
      Voce("notti_n_su", "%d/%d notti"),
      Voce("annulla", "Annulla"),
      Voce("attivo", "Attivo"),
      Voce("errore", "errore"),
      Voce("svolta", "svolta"),
      Voce("durata", "Durata"),
      Voce("seduta", "Seduta"),
      Voce("z_fresco", "Fresco"),
      Voce("z_grigia", "Grigia"),
      Voce("svolto", "Svolto"),
      Voce("oltre", "oltre %s"),
      Voce("da_a", "da %s a %s"),
      Voce("sotto", "sotto %s"),
      Voce("gara_nome", "gara: %s"),
      Voce("b_gialla", "Gialla"),
      Voce("b_grigio", "Grigio"),
      Voce("g28_val", "28 gg %s"),
      Voce("tetto", "tetto %s"),
      Voce("fc_max", "FC max"),
      Voce("tr_passo_breve", "Passo %s"),
      Voce("lavoro", "Lavoro"),
      Voce("passo_pct", "%s passo"),
      Voce("rampa_step", "rampa %s"),
      Voce("t_stress", "Stress"),
      Voce("zc_spalla", "Spalla"),
      Voce("chiudi", "Chiudi"),
      Voce("ottimo", "Ottimo"),
      Voce("c_orario", "Orario"),
      Voce("cr_serale", "Serale"),
      Voce("banda_gialla_min", "gialla"),
      Voce("banda_grigia_min", "grigia"),
      Voce("notte", "Notte"),
      Voce("sonno", "Sonno"),
      Voce("anni", "%d anni"),
      Voce("canale_coach", "Coach"),
      Voce("forma", "Forma"),
      Voce("altro", "Altro"),
      Voce("corsa", "Corsa"),
      Voce("nuoto", "Nuoto"),
      Voce("ferie", "Ferie"),
      Voce("b_verde", "Verde"),
      Voce("b_rossa", "Rossa"),
      Voce("zona_x", "zona %s"),
      Voce("g28", "28 gg"),
      Voce("g7_val", "7 gg %s"),
      Voce("caldo", "Caldo"),
      Voce("passo", "Passo"),
      Voce("vasca", "Vasca"),
      Voce("tr_pot_breve", "Pot. %s"),
      Voce("tr_vel_breve", "Vel. %s"),
      Voce("tr_cad_breve", "Cad. %s"),
      Voce("tr_alt_breve", "Alt. %s"),
      Voce("corpo", "Corpo"),
      Voce("t_alcol", "Alcol"),
      Voce("zc_piede", "Piede"),
      Voce("notte_min", "notte"),
      Voce("buono", "Buono"),
      Voce("l_basso", "BASSO"),
      Voce("l_basso_m", "Basso"),
      Voce("banda_verde_min", "verde"),
      Voce("banda_rossa_min", "rossa"),
      Voce("causa_minuti", "%s (%d')"),
      Voce("eta_app", "Età %s"),
      Voce("oggi", "Oggi"),
      Voce("bici", "Bici"),
      Voce("ore_ora", "ore %s"),
      Voce("g7", "7 gg"),
      Voce("dettp", "DETP"),
      Voce("gara", "Gara"),
      Voce("sera", "Sera"),
      Voce("zc_anca", "Anca"),
      Voce("l_alto", "ALTO"),
      Voce("l_alto_m", "Alto"),
      Voce("da_durata", "da %s"),
      Voce("tag", "Tag"),
      Voce("tr_fc_breve", "FC %s"),
      Voce("dal", "Dal"),
      Voce("numerato", "%d. %s"),
      Voce("al", "Al"),
      )

  /**
   * Traduzione pura: [stringa] cerca la risorsa (nome, argomenti), null se non c'e'. Gli spazi
   * attorno restano; una frase composta con " · " che non corrisponde a un modello intero si
   * traduce pezzo per pezzo (es. "Corsa · 48' · 47 TSS").
   */
  fun traduci(testo: String, stringa: (String, Array<String>) -> String?, altro: (String) -> String? = { null }): String {
    if (testo.isBlank()) return testo
    val nucleo = testo.trim(' ')
    if (nucleo.length != testo.length) {
      val inizio = testo.substring(0, testo.indexOf(nucleo))
      return inizio + traduci(nucleo, stringa, altro) + testo.substring(inizio.length + nucleo.length)
    }
    // prima i messaggi del cervello con codice (riepilogo letto): stesso testo, traduzione per codice
    altro(testo)?.let { return it }
    // frase composta con " · ": solo i modelli composti la prendono intera; altrimenti pezzo per
    // pezzo (un modello corto come "FC %s" non deve "mangiarsi" tutta la riga)
    val composta = " · " in testo
    for (v in VOCI) {
      if (composta && " · " !in v.modello) continue
      val m = v.rx.matchEntire(testo) ?: continue
      val argomenti = m.groupValues.drop(1).map { parte(it, stringa, altro) }
      return stringa("sis_" + v.chiave, argomenti.toTypedArray()) ?: testo
    }
    if (" · " in testo) return testo.split(" · ").joinToString(" · ") { traduci(it, stringa, altro) }
    return testo
  }

  private fun parte(g: String, stringa: (String, Array<String>) -> String?, altro: (String) -> String?): String {
    val t = traduci(g, stringa, altro)
    if (t != g) return t
    return if ("; " in g) g.split("; ").joinToString("; ") { traduci(it, stringa, altro) } else g
  }

  /**
   * Contesto nella lingua scelta per l'app. Le notifiche nascono anche in processi avviati in
   * background (worker, servizio), dove non va dato per scontato che la configurazione abbia gia'
   * la lingua dell'app: qui la si impone, cosi' notifiche e schermate parlano la stessa lingua.
   */
  fun localizzato(context: Context): Context {
    if (!Lingua.perAppDisponibile()) return context
    val codice = Lingua.scelta(context) ?: return context
    val voluto = Locale.forLanguageTag(Lingua.tag(codice))
    val attuale = context.resources.configuration.locales.get(0)
    if (attuale != null && attuale.language == voluto.language) return context
    return context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(voluto) })
  }

  fun traduci(context: Context, testo: String): String {
    val c = localizzato(context)
    return traduci(testo, { nome, args ->
      val id = c.resources.getIdentifier(nome, "string", c.packageName)
      // senza argomenti la stringa si usa com'e': formattarla romperebbe un "%" letterale
      if (id == 0) null else if (args.isEmpty()) c.getString(id) else c.getString(id, *args)
    }, { t -> if (Messaggi.per(t) != null) TraduzioneMessaggi.testo(c, t) else null })
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
