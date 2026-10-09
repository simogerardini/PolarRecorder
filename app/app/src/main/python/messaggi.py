# -*- coding: utf-8 -*-
"""
MESSAGGI — codici stabili per i testi del riepilogo (motivi, avvisi, notifiche).
Cervello NoctaliX, roadmap punto 13a (lingue), 07/10/2026, impostazione approvata da Simone.

L'app traduce i messaggi nelle sue lingue usando il CODICE e i VALORI, non il testo
italiano. Il cervello continua a scrivere i suoi testi in italiano dove nascono (nessun
modulo toccato): questo catalogo li riconosce e li trasforma in
{"codice", "valori", "testo"}. La suite verifica che ogni messaggio prodotto negli scenari
di prova abbia un codice: un testo nuovo o cambiato senza aggiornare il catalogo fa
fallire i test, cosi' l'app non riceve mai un messaggio che non sa tradurre.
Ordine: dal piu' specifico al piu' generico (vince il primo che corrisponde).
"""
import re

_D = r"(?P<data>\d{4}-\d{2}-\d{2})"
_NUM = r"-?\d+(?:\.\d+)?"
_METEO = (r"caldo del " + _D + r"(?: \(tag\)|: (?P<temp>-?\d+) °C, umidita' (?P<umidita>\d+)%"
          r"(?: alle (?P<ora>\d+))?)")

CATALOGO = [
    # ── biometria e freni ─────────────────────────────────────────────────────────
    ("hrv_sotto_range_forte", rf"HRV: media 7gg oltre 1 SD sotto il normal range \((?P<z>{_NUM}|None)\)"),
    ("hrv_sotto_range", rf"HRV: media 7gg sotto il normal range \((?P<z>{_NUM}|None) SD\)"),
    ("hrv_sotto_range_persistente", r"HRV sotto il normal range da (?P<giorni>\d+)gg"),
    ("banda_rossa_persistente", r"banda rossa persistente: la curva biometrica prescrive recupero"),
    ("sonno_medio_basso", rf"sonno medio 7gg (?P<ore>{_NUM})h"),
    ("temperatura_alta", rf"deviazione temperatura \+(?P<gradi>{_NUM})°C \(possibile infiammazione\)"),
    ("readiness_bassa", rf"readiness media (?P<valore>{_NUM})"),
    ("fc_riposo_alta", rf"FC (?:a )?riposo \+(?P<bpm>{_NUM}|None) ?bpm sulla baseline"),
    ("rampa_ctl_alta", rf"rampa CTL \+(?P<rampa>{_NUM})/7gg"),
    ("tsb_basso", rf"TSB (?P<tsb>{_NUM})"),
    ("baseline_poche_notti", r"Solo (?P<giorni>\d+) giorni di HRV disponibili: servono ~14\+ giorni per una "
                             r"baseline affidabile\. Non prendere decisioni drastiche sul solo dato di oggi\."),
    ("baseline_date_illeggibili", r"Date dei dati Oura illeggibili: baseline non calcolabile\."),
    ("baseline_non_disponibile", r"baseline biometrica non disponibile"),
    ("dato_biometrico_vecchio", r"ultimo dato biometrico di (?P<giorni>\d+) giorni fa"),
    # ── settimana e forza ─────────────────────────────────────────────────────────
    ("settimana_senza_spazio", r"(?P<seduta>.+): non c'e' spazio nella settimana tipo"),
    ("ripianificata_per_assenza", rf"settimana ripianificata da oggi: assenza a calendario dal {_D}"),
    ("forza_tolta_tempo", _D.join([r"forza del ", r" tolta: meno di 30' disponibili: nessuna scheda di forza fino a 20'"])),
    ("forza_tolta_profilo", _D.join([r"forza del ", r" tolta: nessuna scheda compatibile con attrezzatura e livello del profilo"])),
    ("companion_prevenzione", r"companion sostituita con (?P<scheda>.+) \(prevenzione (?P<zona>\w+)\)"),
    # ── tag ───────────────────────────────────────────────────────────────────────
    ("tag_malattia_riposo", rf"tag malattia del {_D}: riposo"),
    ("tag_malattia_leggero", rf"tag malattia: {_D} solo attivita' leggera"),
    ("tag_infortunio", rf"tag infortunio (?P<zona>\w+): {_D} senza (?P<discipline>.+)"),
    ("tag_viaggio", rf"tag viaggio del {_D}: niente qualita' ne' forza, max 45'"),
    ("tag_dolore_muscolare", rf"tag dolore muscolare del {_D}: niente qualita'"),
    ("tag_dolore_seduta", rf"tag dolore (?P<zona>\w+) nella seduta del (?P<data_seduta>\d{{4}}-\d{{2}}-\d{{2}}): "
                          rf"{_D} (?:senza (?P<discipline>.+?))?; companion di prevenzione"),
    ("tag_fatica_seduta", rf"tag di fatica sulla seduta del (?P<data_seduta>\d{{4}}-\d{{2}}-\d{{2}}): "
                          rf"(?P<seduta>.+) del {_D} resa aerobica"),
    ("tag_fatica", r"tag di fatica sulla seduta del (?P<data_seduta>\d{4}-\d{2}-\d{2})"),
    ("tag_sconosciuti", r"tag sconosciuti ignorati: (?P<chiavi>.+)"),
    # ── caldo ─────────────────────────────────────────────────────────────────────
    ("caldo_corsa_convertita", _METEO + r": corsa facile convertita in bici indoor (?P<minuti>\d+)' "
                                        rf"\(x(?P<fattore>{_NUM})\)"),
    ("caldo_corsa_accorciata", _METEO + r": (?:(?P<seduta>.+?) )?accorciata a (?P<minuti>\d+)' — esci nelle ore "
                                        r"piu' fresche, idratazione ed elettroliti"),
    # ── DETP ──────────────────────────────────────────────────────────────────────
    ("detp_sospeso", r"DETP: heat block sospeso, banda biometrica (?P<banda>\w+)"),
    ("detp_gara_calda", rf"DETP: (?P<n>\d+) heat block per la gara calda del {_D}"),
    ("detp_sweat_test", rf"DETP: sweat test il {_D}"),
    ("detp_heat_block", rf"DETP: heat block il {_D}"),
    ("sweat_test_programmato", rf"sweat test CORE in programma il {_D}"),
    # ── soglie e test ─────────────────────────────────────────────────────────────
    ("lthr_aggiornata", r"LTHR (?P<sport>\w+) (?P<da>\w+) -> (?P<a>\d+) aggiornata su Intervals\.icu dagli sforzi reali"),
    ("ftp_aggiornata", r"FTP bici (?P<da>\w+) -> (?P<a>\d+) W aggiornata su Intervals\.icu"),
    ("cp_aggiornata", r"CP corsa (?P<da>\w+) -> (?P<a>\d+) W aggiornata su Intervals\.icu"),
    ("test_programmato", rf"test in programma il {_D}: (?P<nome>.+)"),
    ("css_tempi_richiesti", r"test CSS svolto: inserisci nell'app i tempi dei 400 e dei 200 metri"),
    ("passo_soglia_aggiornato", rf"passo soglia corsa (?P<passo>\d+:\d{{2}}/km) dal test del {_D}, aggiornato su Intervals\.icu"),
    ("passo_soglia_da_confermare", rf"test di corsa del {_D}: passo (?P<passo>\d+:\d{{2}}/km) troppo diverso "
                                   r"dall'attuale (?P<attuale>\d+:\d{2}/km) \(oltre il 15%\): non scritto, "
                                   r"conferma tu il valore su Intervals\.icu"),
    ("passo_soglia_non_misurabile", rf"test di corsa del {_D}: passo non misurabile, passo soglia invariato"),
    ("passo_soglia_scrittura_fallita", rf"test di corsa del {_D}: scrittura del passo soglia non riuscita"),
    ("test_corsa_non_trovato", rf"test di corsa del {_D} non trovato tra le attivita': passo soglia invariato"),
    ("soglie_da_completare", r"soglie da completare su Intervals\.icu: (?P<elenco>.+)"),
    # ── profilo e sistema ─────────────────────────────────────────────────────────
    ("settimana_tipo_non_valida", r"settimana tipo non valida, uso quella predefinita: (?P<errori>.+)"),
    ("fuso_non_valido", r"fuso orario non valido \((?P<fuso>.+)\): uso Europe/Rome"),
    ("tsb_fuori_fascia", rf"TSB previsto a domenica (?P<tsb>{_NUM}) fuori dalla fascia attesa "
                         rf"(?P<min>{_NUM})/(?P<max>{_NUM})"),
    ("sedute_non_scritte", r"(?P<n>\d+) sedute non scritte a calendario"),
    ("seduta_non_scritta", rf"{_D} — (?P<seduta>.+?): (?P<errore>.*)"),
    # "+" sul calendario (08/10/2026): minuti per data
    ("disponibilita_data_zero", rf"{_D}: non disponibile"),
    ("disponibilita_data_ridotta", rf"{_D}: solo (?P<minuti>\d+)' disponibili"),
    # ── rimodulazione del mattino ─────────────────────────────────────────────────
    ("brick_alleggerito", r"brick alleggerito: corsa di qualita' di ieri oltre il 125% del TSS pianificato"),
    ("seduta_accorciata_disponibilita", r"(?P<seduta>.+) accorciata a (?P<minuti>\d+)' — disponibilita' del giorno"),
    ("seduta_rimossa", r"(?P<seduta>.+) rimossa — (?P<motivo>.+)"),
    ("seduta_resa_aerobica", r"(?P<seduta>.+) convertita in aerobica — (?P<motivo>.+)"),
]
# ── campi del riepilogo mostrati dall'app (13d bis, 09/10/2026, richiesta della Parte 3) ──
# Riconosciuti solo con il loro "tipo" (vedi TIPI_CAMPO): un testo breve come "stabile" o
# "Riposo" non deve mai essere scambiato per un motivo.
_N = r"-?\d+(?:\.\d+)?"
_NOTA_SAT = (r"(?P<nota_saturazione> NOTA: media 7gg oltre \+1 SD sopra la baseline: se coincide con FC riposo "
             r"bassa e gambe pesanti, leggila come possibile saturazione parasimpatica \(overreaching\), non come "
             r"freschezza garantita\.)?")
_NOTA_CV = (r"(?P<nota_cv> NOTA: la variabilita' giorno-per-giorno dell'HRV si e' quasi azzerata\. In un atleta "
            r"allenato un CV in collasso non e' stabilita' ma uno dei marcatori di overreaching non funzionale "
            r"\(Plews 2013\): non aumentare il carico questa settimana anche se la banda e' verde\.)?")
CATALOGO += [
    ("decisione_recupero", r"🔴 RECUPERO"),
    ("decisione_riduci", r"🟠 RIDUCI"),
    ("decisione_rispetta_piano", r"🟢 RISPETTA IL PIANO"),
    ("decisione_puoi_spingere", r"🔵 PUOI SPINGERE"),
    ("motivo_banda_segue_piano", r"banda (?P<banda>\w+): si segue il piano"),
    ("motivo_calibrazione_segue_piano", r"baseline in calibrazione: si segue il piano"),
    ("biometria_procedi", rf"Procedi con la seduta pianificata \(media 7gg dentro il normal range (?P<min>{_N})-(?P<max>{_N})ms\)\.{_NOTA_SAT}{_NOTA_CV}"),
    ("biometria_riduci", rf"Riduci intensita'/volume o converti la qualita' in Z1-Z2 \(media 7gg sotto il normal range: (?P<z>[+-]?{_N}) SD, limite inferiore (?P<limite>{_N})ms\)\.{_NOTA_SAT}{_NOTA_CV}"),
    ("biometria_recupero", rf"Recupero attivo o riposo, specie se persistente su piu' giorni \(media 7gg (?P<z>[+-]?{_N}) SD sotto baseline, oltre una deviazione standard\)\.{_NOTA_SAT}{_NOTA_CV}"),
    ("biometria_procedi_pct", r"Procedi con la seduta pianificata \(media 7gg entro -10% dalla baseline\)\."),
    ("biometria_riduci_pct", r"Riduci intensita'/volume o converti la qualita' in Z1-Z2 \(media 7gg fra 10 e 20% sotto baseline\)\."),
    ("biometria_recupero_pct", r"Recupero attivo o riposo, specie se persistente \(media 7gg oltre 20% sotto baseline\)\."),
    ("biometria_dati_insufficienti", r"Dati insufficienti per una banda affidabile\."),
    ("direzione_in_salita", r"in salita"), ("direzione_in_calo", r"in calo"),
    ("direzione_stabile", r"stabile"), ("direzione_non_determinabile", r"non determinabile"),
    ("zona_fresco", r"Fresco"), ("zona_ottimale", r"Ottimale"), ("zona_grigia", r"Grigia"),
    ("zona_transizione", r"Transizione"), ("zona_alto_rischio", r"Alto rischio"),
    ("fase_base", r"base"), ("fase_build", r"build"), ("fase_peak", r"peak"), ("fase_taper", r"taper"),
    ("fase_gara", r"gara"), ("fase_recupero", r"recupero"), ("fase_senza_gara", r"senza_gara"),
    ("regola_intensita_8020", r"polarizzazione 80/20: al massimo il 20% del tempo di bici e corsa sopra la soglia"),
    ("regola_intensita_gara", r"preparazione gara: al massimo il 10% del tempo di bici e corsa sopra la soglia"),
    ("oggi_riposo", r"Riposo"),
]
# tipo del campo -> codici ammessi (prefisso o elenco). "motivo_decisione" ammette anche tutti i
# motivi normali (la decisione elenca i motivi uniti da "; ").
TIPI_CAMPO = {"decisione": ("decisione_",), "fase": ("fase_",), "direzione": ("direzione_",),
              "zona": ("zona_",), "regola_intensita": ("regola_intensita_",), "oggi": ("oggi_",),
              "biometria_azione": ("biometria_",),
              "biometria_nota": ("baseline_poche_notti", "baseline_date_illeggibili", "baseline_non_disponibile")}
_SOLO_CAMPO = tuple(p for v in TIPI_CAMPO.values() for p in v if p.endswith("_")) + ("motivo_banda_segue_piano",
                                                                                     "motivo_calibrazione_segue_piano")


# motivi della rimodulazione del mattino (coach_settimanale.rimodula_seduta)
CATALOGO += [
    ("rm_riposo_biometrico", r"la curva biometrica prescrive riposo"),
    ("rm_banda_rossa_forza", r"banda rossa: recupero, niente circuito di forza"),
    ("rm_banda", r"banda (?P<banda>verde|giallo|rosso|grigio)"),
]

# ── righe delle notifiche del coach (13d ter, 09/10/2026): codificate DOVE NASCONO
# (coach_settimanale.riepilogo_righe e nota_rimodulazione), non riconosciute dal testo.
# Elenco per l'app e per i test: codice -> valori (le parole di elenco chiuso sono codici).
RIGHE_NOTIFICA = {
    "nr_intestazione_continuo": "blocco (A|B), tipo_settimana (carico|scarico), carico (1-4, solo carico)",
    "nr_intestazione_fase": "fase (base|build|peak), n, tot, scarico (si|no), settimane, gara (nome)",
    "nr_intestazione_taper": "settimane",
    "nr_intestazione_gara": "gara (nome)",
    "nr_intestazione_recupero": "categoria (RACE_A|RACE_B|RACE_C), gara (nome)",
    "nr_distanza": "distanza (olimpico|70.3|full), fase (codice fase), tipo_settimana (normale|scarico)",
    "nr_volume": "ore, minuti, target (ore), fattore",
    "nr_ripartizione": "nuoto, bici, corsa (percentuali)",
    "nr_alta_intensita": "min, tot, pct, tetto",
    "nr_banda": "banda (verde|giallo|rosso|grigio)",
    "nr_hrv": "media, baseline, min, max (ms), direzione (in_salita|in_calo|stabile|non_determinabile)",
    "nr_forma": "ctl, atl, tsb, rampa (facoltativa, con segno)",
    "nr_motivi": "messaggi (motivi codificati, in ordine)",
    "nr_riposo_consigliato": "giorno (lun..dom)",
    "nr_vuota": "(riga vuota)",
    "nr_giorno_riposo": "giorno (lun..dom), data (gg/mm)",
    "nr_giorno_sedute": "giorno, data, sedute [{chiave (seduta del coach), minuti, aerobica (si|no)}]",
    "nr_rimodulazione": "data, banda, azioni [{codice, valori, testo}] (codici nr_az_* o del CATALOGO)",
    "nr_az_brick_alleggerito": "(nessuno)",
    "nr_az_rimossa": "chiave (seduta del coach), motivo {codice, valori, testo}",
    "nr_az_resa_aerobica": "chiave (seduta del coach), motivo {codice, valori, testo}",
    "nr_az_rimossa_disponibilita": "chiave, nome (nome dell'evento)",
    "nr_az_accorciata_disponibilita": "chiave, nome, minuti",
    "nr_az_companion_prevenzione": "scheda (titolo), zona",
}


# notifiche (testi lunghi, multiriga): riconosciute dall'inizio
NOTIFICHE = [
    ("piano_rimodulato", r"🗓️ Piano settimanale — rimodulazione del " + _D + r", banda (?P<banda>\w+):"),
    ("piano_settimanale", r"🗓️ Piano settimanale"),
]
_COMPILATI = [(c, re.compile(r"^" + p + r"$", re.S)) for c, p in CATALOGO]
_NOTIFICHE = [(c, re.compile(r"^" + p, re.S)) for c, p in NOTIFICHE]
NON_CODIFICATO = "non_codificato"


def codifica(testo, tipo="motivo"):
    """{"tipo", "codice", "valori", "testo"}. Testi uniti da "; " (rimodulazione del
    mattino) -> codice "multiplo" con i messaggi in "valori.messaggi"."""
    t = (testo or "").strip()
    if tipo == "avviso" and t.startswith("tag: "):
        t = t[5:]
    if tipo in TIPI_CAMPO:
        elenco = [(c, rx) for c, rx in _COMPILATI if c.startswith(TIPI_CAMPO[tipo])]
    elif tipo == "motivo_decisione":
        elenco = _COMPILATI
    else:          # motivi, avvisi, notifiche: mai i codici riservati ai campi del riepilogo
        elenco = [(c, rx) for c, rx in _COMPILATI if not c.startswith(_SOLO_CAMPO)]
        if tipo == "notifica":
            elenco = _NOTIFICHE + elenco
    for codice, rx in elenco:
        m = rx.match(t)
        if m:
            valori = {k: ("si" if k.startswith("nota_") else v) for k, v in m.groupdict().items() if v is not None}
            return {"tipo": tipo, "codice": codice, "valori": valori, "testo": testo}
    if "; " in t:
        parti = [codifica(p, tipo) for p in t.split("; ")]
        if all(p["codice"] != NON_CODIFICATO for p in parti):
            return {"tipo": tipo, "codice": "multiplo", "valori": {"messaggi": parti}, "testo": testo}
    return {"tipo": tipo, "codice": NON_CODIFICATO, "valori": {}, "testo": testo}


CAMPI_RIEPILOGO = [("decisione.etichetta", "decisione"), ("decisione.motivo", "motivo_decisione"),
                   ("fase", "fase"), ("biometria.azione", "biometria_azione"), ("biometria.nota", "biometria_nota"),
                   ("biometria.direzione_7v7", "direzione"), ("forma.zona", "zona"), ("forma.zona_attesa", "zona"),
                   ("intensita.regola", "regola_intensita"), ("oggi", "oggi")]


def _campo(rie, percorso):
    x = rie
    for k in percorso.split("."):
        x = x.get(k) if isinstance(x, dict) else None
    return x


def messaggi_riepilogo(rie, notifiche=()):
    """Lista "messaggi" del riepilogo: motivi, righe degli avvisi, notifiche e (13d bis) i
    campi di testo mostrati dall'app, con "testo" identico al campo. "oggi" solo se "Riposo"
    (altrimenti sono nomi di sedute, gia' nella lingua del calendario)."""
    out = [codifica(m, "motivo") for m in rie.get("motivi") or []]
    out += [codifica(r, "avviso") for r in (rie.get("avvisi") or "").splitlines() if r.strip()]
    out += [codifica(n, "notifica") for n in notifiche or []]
    visti = set()
    for percorso, tipo in CAMPI_RIEPILOGO:
        testo = _campo(rie, percorso)
        if not isinstance(testo, str) or not testo or (tipo == "oggi" and testo != "Riposo"):
            continue
        if (tipo, testo) not in visti:
            visti.add((tipo, testo))
            out.append(codifica(testo, tipo))
    return out
