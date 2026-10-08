# -*- coding: utf-8 -*-
"""
SEDUTE — libreria e sintassi delle sedute di allenamento (cervello BioSleep).

ORIGINE
-------
Estratto MECCANICAMENTE da intervals_coach.py (03/10/2026, decisione di Simone, opzione B):
generatori deterministici delle descrizioni Intervals.icu per corsa, bici, nuoto, brick e
palestra, con tutte le regole e i BUG FIX affinati nel tempo (commenti originali inclusi).
Il codice e' copiato senza modifiche: l'estrazione segue le dipendenze reali (analisi AST)
e la suite test_sedute.py riesegue sul modulo i test di test_intervals_coach.py che lo
riguardano. Nessuna regola e' stata riscritta.

DIFFERENZE RISPETTO ALL'ORIGINALE (solo il bordo verso la rete)
---------------------------------------------------------------
- get_lthr / get_activities non chiamano Intervals.icu: leggono i dati passati a
  configura(atleta, attivita). Chi orchestra (coach) fa il fetch e li inietta.
- Nessuna scrittura: niente POST/PUT. Le funzioni producono testo e payload, la rete e'
  compito del chiamante.
"""
import base64, os, re
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

# ── TIMEZONE ────────────────────────────────────────────────
# ── TIMEZONE ────────────────────────────────────────────────
# BUG FIX: GitHub Actions esegue in UTC. Usare datetime.now() nudo
# (senza timezone) ritorna l'ora UTC, non l'ora locale di Bologna/Roma. Quando il workflow scatta vicino
# alla mezzanotte locale (es. 00:xx-01:xx CET/CEST), la data UTC e' ancora
# quella del giorno PRECEDENTE -> "oggi"/"ieri" calcolati nel resto dello script
# si disallineano di un giorno intero rispetto al calendario di Oura (che usa
# la giornata locale dell'utente). Questa e' la causa del bug "tag Oura
# associati al giorno sbagliato": la finestra di query [yest, today] finiva
# per puntare a notti diverse da quelle reali.
# now_local() ritorna un datetime NAIVE (senza tzinfo) in ora locale: naive
# per restare compatibile con tutte le sottrazioni/confronti datetime.strptime
# (naive) gia' presenti nel resto del file, che altrimenti darebbero
# TypeError "can't subtract offset-naive and offset-aware datetimes".
# 07/10/2026 (distribuzione): fuso del telefono, passato dal cervello (FUSO_ORARIO,
# nome IANA); Europe/Rome solo se manca. Prima era sempre Roma.
TZ_LOCALE = ZoneInfo(os.getenv("FUSO_ORARIO") or "Europe/Rome")

def now_local():
    return datetime.now(TZ_LOCALE).replace(tzinfo=None)


# Unita' vincente = Power (file .zwo, %FTP): sforzi dove la FC e' in ritardo
# strutturale (reps brevi, rampe, surge) o falsata dalla pendenza. HillWork incluso:
# in salita il pace e' privo di senso e su reps di 1-3min la FC sottostima il primo
# minuto; il running power resta stabile in pendenza. Il suo .zwo esisteva gia' in
# _build_run_zwo_base ma non veniva mai allegato.
#
# FONTE POTENZA = STRYD (decisione atleta, valida con e senza gare a calendario).
# Il pod Stryd e' lo standard de-facto del run power per consistenza e ripetibilita':
# stesso dato indoor/outdoor e tra dispositivi, compensazione vento, nessuna
# dipendenza da GPS/barometro del watch (Vance 'Run with Power'; van Dijk & van
# Megen 'The Secret of Running'; Palladino Power Project). Riferimento di tutti i
# target %Power: la CRITICAL POWER Stryd, che su Intervals.icu vive nel campo FTP
# dello sport Run — e' li' che il server risolve gli step %FTP dei file .zwo e i
# "%Power" delle description. Ricalibrazione periodica: test CP in TEST_ROTAZIONE /
# proponi_test_periodico + autodetect aggiorna_run_cp_automatico.
#
# QUALI SESSIONI IN POWER (scelta motivata, vedi anche il prompt TARGET METRICA):
# tutte le QUALITA' e i TEST — dove il target deve rispondere subito ed essere
# indipendente da pendenza/terreno/caldo. NON le sedute facili (Easy/Recovery/Long/
# BackToBack/ShakeOut/Strides): l'80% facile del volume si dosa sul carico INTERNO
# (FC), che si adatta a caldo/fatica/giornate no — i watt chiederebbero lo stesso
# output esterno anche quando il corpo dice di rallentare, l'errore esattamente
# opposto allo scopo di quelle sedute (Friel; Fitzgerald 80/20; lo stesso Palladino
# prescrive il facile a sensazione/FC). DownhillRepeats resta in Cadence (su
# pendenza variabile ne' watt ne' FC sono il punto: e' turnover).
# Alternations aggiunta ai POWER_TYPES: sugli on/off da 1-3min la FC e' in ritardo
# a OGNI cambio di ritmo (sale tardi sull'on, scende tardi sull'off), la potenza
# commuta istantanea — stesso razionale di Fartlek/Interval.
# ECCEZIONE MPRun/Alternations: se c'e' un obiettivo di tempo l'unita' vincente e'
# il PACE letterale (lo scopo della sessione E' il ritmo gara) -> niente .zwo,
# description tutta in Pace: vedi usa_zwo_power in create_run_workout.
POWER_TYPES = ["Threshold", "Interval", "Progressive", "Fartlek",
               "Repetition", "HillSprints", "HillWork", "MPRun", "TestRace",
               "Alternations"]

# BUG FIX (28/08/2026 — la sezione STATO spiegava come eseguire una seduta che il codice
# aveva gia' sostituito): "nota_esecuzione" e "motivazione" sono scritte dal modello sulla
# voce di piano GREZZA, ma dopo di lui intervengono cinque safety-net che possono cambiare
# tipo, sport o durata della stessa voce (copertura settimana, vincoli di giornata, scelta
# per-sport, conflitti palestra/qualita'). La nota resta pero' attaccata alla voce, e
# componi_consigli_oggi la stampa in coda allo STATO col nome NUOVO della seduta.
# _nota_coerente intercettava un solo verso — nota intensa su seduta declassata a facile —
# ma non il verso opposto, che e' quello che il safety-net di copertura produce ogni volta
# che promuove una Easy a Threshold: il messaggio annunciava "Threshold — qualita' della
# settimana" e subito dopo consigliava "Z2 vera: se fatichi a parlare sei fuori zona".
# Regola: chi riscrive la sostanza della seduta cancella la nota. Il consiglio torna
# deterministico (_consiglio_default, costruito dai params veri) — asciutto ma mai falso.
def _invalida_nota_esecuzione(sessione):
    """Toglie i testi che descrivevano la seduta PRIMA della riscrittura del codice."""
    sessione.pop("nota_esecuzione", None)
    return sessione

# Profili di BikeCross/Swim che sono QUALITA' a tutti gli effetti (soglia/VO2max in bici,
# soglia/velocita' in acqua). Prima contava solo QUALITY_TYPES (corsa) + BikeCross con
# "sogl" nel nome: in blocco multisport uno Swim soglia o una BikeCross vo2max non
# venivano contate, il safety-net "ne manca una" promuoveva un'Easy a Threshold e la
# settimana chiudeva con 3 qualita' invece di 2 (80/20 rotto per costruzione).
_PROFILI_QUALITA = {"soglia", "vo2max", "velocita"}

def tag_tipo_in_descrizione(desc, sessione_tipo):
    """Marca la description con il sessione_tipo REALMENTE creato su Intervals.icu,
    cosi' una conferma futura (azione='conferma') puo' leggere indietro cio' che
    e' davvero presente sull'evento, invece di fidarsi a occhi chiusi del tipo
    proposto dal nuovo piano di Claude. Marker invisibile a inizio testo."""
    return f"[[tipo:{sessione_tipo}]]\n{desc}"

def external_id_tipo(sessione_tipo, date_str, parte=None):
    # `parte` serve al brick, che occupa DUE eventi sulla stessa data (bici/corsa):
    # senza suffisso avrebbero external_id identico e sarebbero indistinguibili.
    # Il prefisso "coach:<tipo>:" resta invariato, quindi _EXTID_TIPO_RE continua a
    # riconoscere il tipo reale di entrambi.
    base = f"coach:{sessione_tipo}:{date_str}"
    return f"{base}:{parte}" if parte else base

def get_target_metrica(sessione_tipo, sport, params=None):
    """Etichetta informativa del target (per display/telegram). Non decide se
    allegare il file .zwo Power: per quello vedi `sessione_tipo in POWER_TYPES`
    in create_run_workout, perche' qui alcuni tipi hanno target misti/condizionali
    che non corrispondono mai a un singolo file power."""
    p = params or {}
    if p.get("pace_obiettivo_str"):
        return f"pace {p['pace_obiettivo_str']}"
    if sport != "Run":
        # Bici su strada con potenziometro e FTP nota: il target e' in watt
        # (vedi bike_usa_potenza). Tutto il resto — spinning indoor, nuoto — resta FC.
        if sessione_tipo == "BikeCross" and bike_usa_potenza(p):
            return "power"
        return "hr"
    if sessione_tipo == "RaceSimulation":
        # senza obiettivo di tempo (tipico trail/ultra): power Stryd — su D+ il pace
        # non ha senso e su 2-4h la FC va a deriva (vedi branch in description)
        return "pace" if p.get("ha_obiettivo_tempo", True) else "power"
    if sessione_tipo == "Strides":
        return "hr"  # unita' unica: pace solo con obiettivo (gia' intercettato sopra)
    if sessione_tipo == "DownhillRepeats":
        return "cadence"
    if sessione_tipo in POWER_TYPES:
        return "power"
    return "hr"

_ATHLETE_CACHE = None

def get_bike_ftp():
    """FTP bici configurata su Intervals.icu (sportSettings 'Ride'), None se assente.
    Nessuna chiamata HTTP aggiuntiva: legge la stessa _ATHLETE_CACHE di get_lthr()."""
    get_lthr()   # popola _ATHLETE_CACHE
    for s in (_ATHLETE_CACHE or {}).get("sportSettings", []):
        if "Ride" in s.get("types", []):
            return s.get("ftp") or None
    return None

_KEYWORD_INDOOR = ("indoor", "tapis", "treadmill", "rulli", "spinning",
                   "palestra", "gym", "turbo", "zwift", "al chiuso")

# ── AMBIENTE DI UNA SESSIONE BICI *PIANIFICATA* ─────────────
# Da non confondere con ambiente_attivita() qui sopra, che ricostruisce dove si e'
# allenato l'atleta in un'attivita' GIA' ESEGUITA. Qui si decide come va SCRITTA una
# sessione futura, e la conseguenza e' l'unita' di misura del target:
#   outdoor -> potenza (%FTP bici), indoor -> FC (%LTHR bici).
# Fonte di verita' in ordine: params.ambiente deciso dal piano; poi le parole nel nome;
# infine il DEFAULT "indoor", che e' il comportamento storico dello script (prima della
# bici Simone aveva solo lo spinning) e l'unico sicuro: una sessione indoor scritta in
# watt sarebbe ineseguibile sui macchinari di palestra, mentre una outdoor scritta in
# FC resta comunque allenabile.
_KEYWORD_OUTDOOR = ("outdoor", "strada", "aperto", "asfalto", "gran fondo", "granfondo")

def bike_ambiente(params=None, nome=""):
    a = str((params or {}).get("ambiente") or "").strip().lower()
    if a in ("outdoor", "indoor"):
        return a
    n = (nome or "").lower()
    if any(k in n for k in _KEYWORD_INDOOR):
        return "indoor"
    if any(k in n for k in _KEYWORD_OUTDOOR):
        return "outdoor"
    return "indoor"

def bike_usa_potenza(params=None, nome=""):
    """True se la sessione bici va scritta a WATT: serve sia l'ambiente outdoor sia una
    FTP bici configurata. Senza FTP (situazione iniziale, nessun dato pregresso) la
    sessione resta a FC finche' l'autodetect/il test non producono un valore: meglio un
    target in FC che una percentuale risolta su un'FTP inesistente."""
    return bike_ambiente(params, nome) == "outdoor" and bool(get_bike_ftp())

# BUG FIX (giugno 2026): Claude a volte restituisce wu_min/cd_min/rep_min
# fuori scala (es. wu_min=120 per una sessione di 150min totali) -> il corpo
# centrale (Z2/soglia/etc.) collassa a 0 e la sessione reale su Intervals.icu
# non rispecchia piu' l'intento del piano (vedi caso Long Run 2h30 Z2->Z3
# diventata 120m Z1 / 0m Z2 / 10m Z3 / 30m Z1). Nessuna validazione esisteva
# prima: il codice si fidava ciecamente dei numeri arrivati dall'LLM. Questa
# funzione scala wu/cd proporzionalmente se insieme eccedono la durata totale
# meno un margine minimo di corpo centrale, cosi' la sessione resta sempre
# strutturalmente sensata indipendentemente da cosa propone Claude.
def sane_wu_cd(wu, cd, durata_min, min_corpo=10):
    wu = max(0, wu); cd = max(0, cd)
    if wu + cd > durata_min - min_corpo:
        scale = max(0, durata_min - min_corpo) / max(1, wu + cd)
        wu, cd = max(3, round(wu * scale)), max(3, round(cd * scale))
    return wu, cd

def _strides_addon_desc(p, unita="HR"):
    if not p.get("strides_reps"):
        return ""
    s_reps = p.get("strides_reps"); s_sec = p.get("strides_sec", 20)
    s_rec  = p.get("strides_rec_sec", 60)
    # Target nominale nella STESSA unita' del workout ospite (Z3/Z1 HR o Pace),
    # per non rompere la regola unita' unica: su 15-20s nessuna metrica e'
    # rappresentativa, la guida vera e' l'RPE nel testo. unita=None (es. ospite
    # Cadence): step a sola durata, nessun token di zona.
    if unita is None:
        return (f"\n{s_reps}x\n- allungo {s_sec}s {STEP_INT_WORK} svelto e controllato "
                f"(RPE 8-9, ~ritmo 5K)\n"
                f"- recupero {s_rec}s {STEP_INT_REC} corsa blanda\n")
    if unita == "Power":
        return (f"\n{s_reps}x\n- allungo {s_sec}s {p.get('strides_pct', 115)}% Power {STEP_INT_WORK} "
                f"(svelto e controllato, RPE 8-9 — vai a sensazione ~ritmo 5K)\n"
                f"- recupero {s_rec}s 60% Power {STEP_INT_REC}\n")
    return (f"\n{s_reps}x\n- allungo {s_sec}s Z3 {unita} {STEP_INT_WORK} (svelto e controllato, "
            f"RPE 8-9 — vai a sensazione ~ritmo 5K)\n"
            f"- recupero {s_rec}s Z1 {unita} {STEP_INT_REC}\n")

# Safety-net della REGOLA UNITA' TARGET UNICA: rileva unita' miste in una
# description PRIMA di scriverla su Intervals.icu (solo log, non blocca).
_UNIT_PATTERNS = {
    "HR":      re.compile(r"Z\d(?:-Z?\d)?\s*HR|%\s*(?:LTHR|HR)", re.IGNORECASE),
    "Pace":    re.compile(r"Z\d(?:-Z?\d)?\s*Pace|%\s*Pace|\d:\d{2}\s*/\s*km", re.IGNORECASE),
    "Power":   re.compile(r"%\s*Power|(?<![\w:.])\d{2,3}%(?!\s*(?:HR|LTHR|Pace|Power))", re.IGNORECASE),
    "Cadence": re.compile(r"\d+\s*rpm\s+Cadence", re.IGNORECASE),
}

def verifica_unita_target(desc, nome=""):
    unita = {u for u, rx in _UNIT_PATTERNS.items() if rx.search(desc or "")}
    if len(unita) > 1:
        print(f"    ⚠️ TARGET MISTI in '{nome}': {sorted(unita)} — il watch "
              f"mostrera' solo una unita' (limite Intervals.icu, thread forum 36433)")
    return unita

def _unita_ospite(body):
    """Unita' target del workout ospite, SENZA stampare warning (verifica_unita_target
    resta il check rumoroso, chiamato una sola volta a valle). Serve agli iniettori di
    blocchi aggiuntivi per rispettare la REGOLA UNITA' TARGET UNICA."""
    u = {k for k, rx in _UNIT_PATTERNS.items() if rx.search(body or "")}
    return ("Pace" if u == {"Pace"} else None if u == {"Cadence"}
            else "Power" if u == {"Power"} else "HR")

def _endurance_addon_desc(p, unita="HR"):
    """Blocco di fondo aerobico che assorbe il tempo residuo di _fit_struttura_durata
    (vedi UNDERFLOW OLTRE I TETTI DI CONTORNO). Target nell'unita' dell'ospite."""
    n = int(p.get("endurance_min") or 0)
    if n < _ENDU_MIN_FIT:
        return ""
    if unita is None:      # ospite in Cadence: step a sola durata, nessun token di zona
        return f"\n- fondo aerobico {n}m {STEP_INT_WORK} (facile, conversazionale)\n"
    if unita == "Power":
        return f"\n- fondo aerobico {n}m 70-78% Power {STEP_INT_WORK}\n"
    if unita == "Pace":
        return f"\n- fondo aerobico {n}m Z2 Pace {STEP_INT_WORK}\n"
    return f"\n- fondo aerobico {n}m Z2 HR {STEP_INT_WORK}\n"

def _inject_endurance_desc(body, p):
    """Il blocco aerobico va DOPO le ripetute e PRIMA del defaticamento finale."""
    addon = _endurance_addon_desc(p, _unita_ospite(body))
    if not addon:
        return body
    idx  = body.rfind("\n\n- ")
    core = addon.strip("\n")
    if idx == -1:
        return body.rstrip("\n") + "\n\n" + core + "\n"
    return body[:idx].rstrip("\n") + "\n\n" + core + body[idx:]

def _inject_strides_desc(body, p):
    """Inserisce il blocco Strides prima dell'ultima riga (il defatigamento finale),
    cosi' la struttura resta sensata indipendentemente da come finisce ogni branch.
    BUG FIX: la prima versione lasciava un singolo \\n al punto di giunzione invece di
    una riga vuota (\\n\\n) — Intervals.icu interpreta il testo come sintassi strutturata
    e con un solo \\n fonde due righe/blocchi adiacenti in uno solo (es. "(ridiscesa a
    piedi) 6x" sulla stessa riga). Si forza esplicitamente \\n\\n su entrambi i lati."""
    # L'unita' degli allunghi segue quella del corpo ospite (HR default, Pace se
    # il body e' in zone Pace, nessun target se Cadence): vedi REGOLA UNITA' TARGET UNICA.
    addon = _strides_addon_desc(p, _unita_ospite(body))
    if not addon:
        return body
    idx = body.rfind("\n\n- ")
    addon_core = addon.strip("\n")
    if idx == -1:
        return body.rstrip("\n") + "\n\n" + addon_core + "\n"
    before = body[:idx].rstrip("\n")
    after  = body[idx:]  # inizia con "\n\n- ..." (riga del defatigamento)
    return before + "\n\n" + addon_core + after

# ── SESSION_DEFAULTS: UNICA FONTE DI VERITA' DEI PARAMETRI ──
# I default di wu/cd/reps/durate/intensita' erano TRIPLICATI in
# _build_run_description_base, _build_run_zwo_base e _calc_moving_time_base:
# tre copie degli stessi numeri da tenere allineate a mano ad ogni modifica,
# con drift reale gia' avvenuto (BackToBack: descrizione wu=8min, file .zwo
# wu=5min). Ora le tre funzioni fondono questa tabella nei params PRIMA di
# leggere qualsiasi valore: i fallback inline p.get(k, n) restano nel codice
# ma sono irraggiungibili per le chiavi coperte qui — a decidere e' la tabella,
# e descrizione, file .zwo e moving_time non possono piu' divergere tra loro.
# I params espliciti dell'LLM hanno sempre priorita' sulla tabella.
# BikeCross/Swim NON hanno wu/cd qui: i loro default dipendono da params.profilo
# e restano gestiti nei rispettivi branch. "Long" ignora deliberatamente wu/cd
# dell'LLM (vedi BUG FIX nel branch Long) e quindi non va coperto.
SESSION_DEFAULTS = {
    "Threshold":       {"wu_min": 15, "cd_min": 10, "reps": 3,  "rep_min": 10, "rec_min": 3, "intensity_pct": 95},
    "Interval":        {"wu_min": 15, "cd_min": 10, "reps": 6,  "rep_min": 1,  "rec_min": 1, "intensity_pct": 110},
    "HillWork":        {"wu_min": 15, "cd_min": 10, "reps": 8,  "rep_min": 2,  "rec_min": 2},
    "Progressive":     {"wu_min": 10, "cd_min": 5,  "pct1": 72, "pct2": 85,    "pct3": 95},
    "Fartlek":         {"wu_min": 12, "cd_min": 8,  "reps": 10, "rep_min": 1,  "rec_min": 1, "intensity_pct": 105},
    "RaceSimulation":  {"wu_min": 10, "cd_min": 10},
    "ShakeOut":        {"reps": 4},
    "Repetition":      {"wu_min": 15, "cd_min": 10, "reps": 10, "rep_sec": 35, "rec_min": 2, "intensity_pct": 120},
    "HillSprints":     {"wu_min": 12, "cd_min": 8,  "reps": 8,  "rep_sec": 10, "rec_min": 2},
    "Strides":         {"reps": 6,    "rep_sec": 20, "rec_sec": 60, "intensity_pct": 115},
    # intensity_pct di MPRun/Alternations: il main lo imposta da race_pace_pct_cp(gara) quando il
    # coach non lo specifica; questi 80/82 restano solo il fallback per la prep. continua 70.3.
    "MPRun":           {"wu_min": 10, "cd_min": 10, "intensity_pct": 80},
    "Alternations":    {"wu_min": 10, "cd_min": 10, "reps": 8,  "rep_min": 1,  "intensity_pct": 82},
    "DownhillRepeats": {"wu_min": 12, "cd_min": 8,  "reps": 6,  "rep_min": 2,  "rec_min": 2, "cadence_rpm": 180},
    "TestRace":        {"wu_min": 15, "cd_min": 10, "intensity_pct": 92},
    "Easy":            {"wu_min": 5,  "cd_min": 5},
    "BackToBack":      {"wu_min": 8,  "cd_min": 5},
}

# ── LA DURATA PIANIFICATA E' VINCOLANTE, NON DECORATIVA ────────────────────
# BUG FIX (28/08/2026 — la falla piu' grave rimasta, silenziosa per costruzione):
# per OTTO tipi di sessione (tutti quelli a ripetute) il parametro `durata_min` veniva
# semplicemente IGNORATO. description, .zwo e calc_moving_time leggevano reps/rep_min/
# rec_min/wu_min/cd_min dai SESSION_DEFAULTS e ricostruivano sempre la stessa struttura,
# qualunque durata avesse chiesto il coach. Misurato sul file com'era:
#   Threshold  -> 64min SEMPRE (richiesti 30, 45, 60 o 90: identico)
#   Interval   -> 37min | HillWork -> 57min | Fartlek -> 40min
#   Repetition -> 51min | HillSprints -> 37min | Alternations -> 36min
# Tre conseguenze, tutte nella direzione peggiore:
#  1. il TETTO DI TEMPO DICHIARATO DALL'ATLETA veniva violato in silenzio. Simone scrive
#     sul calendario "oggi ho solo 50 minuti", applica_vincoli_giornata porta durata_min a
#     50, e su Intervals.icu finisce comunque una Threshold da 64'. Nessun log, nessun
#     avviso: la nota e' stata letta, risolta, applicata al piano e poi persa dal
#     generatore. E' esattamente il tipo di fallimento silenzioso che questo file combatte
#     ovunque tranne qui;
#  2. il MONTE ORE SETTIMANALE era sistematicamente sottostimato. Il target di fase, lo
#     split nuoto/bici/corsa e il BUDGET SETTIMANA del prompt ragionano su durata_min,
#     mentre a calendario ci finiva fino a +19' per ogni sessione di qualita'. Con 2
#     qualita'/settimana sono ~40 minuti/sett di carico fuori bilancio, cioe' il freno
#     biometrico (fattore_progressione x volume sostenibile) che frena un numero diverso
#     da quello che l'atleta esegue davvero;
#  3. sane_wu_cd() era CODICE MORTO su questi tipi: calcolato in testa a
#     _build_run_description_base e poi scavalcato da p.get("wu_min") in ogni branch (e'
#     il motivo per cui `corpo_min` risultava una variabile mai usata). Il clamp che
#     doveva impedire un riscaldamento piu' lungo della seduta non ha mai girato.
# Il fix sta in UN SOLO punto — merge_session_defaults, che e' l'ingresso comune di
# descrizione, .zwo e moving_time — cosi' i tre non possono piu' divergere fra loro.
# Regola: la durata e' il vincolo, la struttura si adatta. In overflow si comprime prima
# il contorno (riscaldamento/defaticamento fino a 10'/5'), e solo dopo si tolgono
# ripetute: stessa gerarchia della FILOSOFIA del prompt ("si accorciano le facili di
# contorno, MAI le chiave"). In underflow il residuo va nel defaticamento (volume
# aerobico, fisiologicamente neutro) invece di sparire, cosi' moving_time == durata
# pianificata e il conto settimanale torna. Le ripetute non vengono MAI aumentate: la
# prescrizione del coach e' un tetto, non un bersaglio da riempire.
_WU_MAX_FIT, _CD_MAX_FIT = 25, 20

_WU_MIN_FIT, _CD_MIN_FIT = 10, 5

_ENDU_MIN_FIT = 5

# tipo -> (chiave della durata della ripetuta, esiste un recupero separato?)
# Alternations non ha rec_min: e' un on/off di pari durata, quindi l'unita' vale 2 rep.
_TIPI_A_RIPETUTE = {
    "Threshold":       ("rep_min", True),
    "Interval":        ("rep_min", True),
    "HillWork":        ("rep_min", True),
    "Fartlek":         ("rep_min", True),
    "Repetition":      ("rep_sec", True),
    "HillSprints":     ("rep_sec", True),
    "DownhillRepeats": ("rep_min", True),
    "Alternations":    ("rep_min", False),
}

def _fit_struttura_durata(sessione_tipo, durata_min, p):
    """Params adattati perche' la struttura reale stia dentro `durata_min`.
    Idempotente: rifittare una durata gia' coerente restituisce gli stessi numeri
    (serve a evento_gia_allineato, altrimenti ogni run riscriverebbe l'evento)."""
    conf = _TIPI_A_RIPETUTE.get(sessione_tipo)
    if not conf or not durata_min:
        return p
    chiave, ha_rec = conf
    rep_sec = int(p.get(chiave) or 0) * (1 if chiave == "rep_sec" else 60)
    if rep_sec <= 0:
        return p
    rec_sec  = (int(p.get("rec_min") or 0) * 60) if ha_rec else rep_sec
    unita    = rep_sec + rec_sec
    reps_req = max(1, int(p.get("reps") or 1))
    tot_sec  = int(durata_min) * 60

    def _prova(wu, cd):
        corpo = tot_sec - (wu + cd) * 60
        return (int(corpo // unita) if corpo > 0 else 0), corpo

    wu, cd = int(p.get("wu_min", 5)), int(p.get("cd_min", 5))
    reps_fit, corpo = _prova(wu, cd)
    if reps_fit < min(reps_req, 2):          # prima si comprime il contorno
        wu, cd = min(wu, _WU_MIN_FIT), min(cd, _CD_MIN_FIT)
        reps_fit, corpo = _prova(wu, cd)
    reps_fit = max(1, min(reps_req, reps_fit))
    residuo  = corpo - reps_fit * unita
    endu     = 0
    if residuo > 0:                          # underflow: il resto diventa aerobico
        extra   = int(residuo // 60)
        add_cd  = min(extra, max(0, _CD_MAX_FIT - cd)); cd += add_cd; extra -= add_cd
        add_wu  = min(extra, max(0, _WU_MAX_FIT - wu)); wu += add_wu; extra -= add_wu
        # ── UNDERFLOW OLTRE I TETTI DI CONTORNO: IL TEMPO NON PUO' SPARIRE ────────
        # BUG FIX (29/08/2026): il fit chiudeva qui. Se dopo aver portato wu/cd ai loro
        # tetti (25'/20') restava ancora tempo, quel tempo veniva semplicemente PERSO,
        # perche' le ripetute non si aumentano mai (la prescrizione del coach e' un
        # tetto). Misurato sul file com'era, con i default di tabella:
        #   Interval 120min -> 57min | Threshold 120min -> 84min | Fartlek 90min -> 65min
        #   HillSprints 90min -> 62min | Alternations 90min -> 61min | HillWork 120 -> 77
        # Cioe' l'esatto speculare del bug che questo fit e' nato per chiudere, nella
        # direzione opposta e altrettanto silenziosa: il budget settimanale, lo split
        # nuoto/bici/corsa e il freno biometrico ragionano su durata_min, mentre a
        # calendario finiva fino a UN'ORA IN MENO. Nessun log, nessuno scostamento
        # visibile: la settimana risultava "coperta" sulla carta e vuota nei fatti.
        # Il residuo diventa un blocco di FONDO AEROBICO esplicito, collocato DOPO le
        # ripetute e PRIMA del defaticamento: e' la struttura "qualita' + volume" di
        # Daniels/Friel (blocco intenso su gambe fresche, volume aerobico a seguire),
        # fisiologicamente neutro sul carico di intensita' e coerente con la filosofia
        # del prompt. Il blocco viene scritto in description, .zwo e moving_time dalla
        # stessa chiave, quindi i tre non possono divergere.
        endu = extra if extra >= _ENDU_MIN_FIT else 0
    out = dict(p)
    out["wu_min"], out["cd_min"], out["reps"] = wu, cd, reps_fit
    if endu:
        out["endurance_min"] = endu
    else:
        out.pop("endurance_min", None)
    # SFORAMENTO IRRIDUCIBILE. Con contorno gia' ai pavimenti e una sola ripetuta, certi
    # tipi non stanno in una finestra molto corta (una Threshold con rep da 10' non entra
    # in 20'). Il percorso dei vincoli lo evita gia' declassando sotto MIN_QUALITA_MIN,
    # ma se e' il COACH a pianificare una qualita' troppo breve nessuno se ne accorgeva.
    # Non si accorcia la ripetuta (cambierebbe lo stimolo): si dichiara, perche' il
    # principio della casa e' che un fallimento silenzioso e' peggio di un errore visibile.
    sforo = (wu + cd) * 60 + reps_fit * unita + endu * 60 - tot_sec
    if sforo > 120:
        print(f"    ⚠️ {sessione_tipo} {durata_min}min: la struttura minima "
              f"({reps_fit}x{int(rep_sec/60) or round(rep_sec)}{'m' if chiave=='rep_min' else 's'} "
              f"+ {wu}'/{cd}' di contorno) dura {round(sforo/60)}min piu' del tetto "
              f"dichiarato — durata o tipo di seduta incoerenti")
    return out

# ── IL NUOTO IGNORAVA LA DURATA PIANIFICATA ───────────────────────────────
# BUG FIX (29/08/2026 — misurato su tutta la griglia 25-120min): "Swim" non e' in
# SESSION_DEFAULTS ne' in _TIPI_A_RIPETUTE, quindi _fit_struttura_durata lo lasciava
# passare intatto e i profili di qualita' erano costruiti SOLO dai loro default:
#   profilo "soglia"   -> 41 min SEMPRE (pianificati 30, 40, 60 o 75: identico)
#   profilo "velocita" -> 32 min SEMPRE
# E' lo stesso difetto gia' corretto per gli otto tipi a ripetute della corsa, rimasto
# aperto sul nuoto. Le conseguenze sono le due opposte, entrambe silenziose:
#  1. SFORAMENTO DEL TETTO DICHIARATO: Simone scrive "oggi ho 30 minuti",
#     applica_vincoli_giornata porta durata_min a 30 — e il nuoto di soglia non e' fra i
#     _TIPI_QUALITA_TEMPO, quindi non viene nemmeno declassato — e su Intervals.icu
#     finisce una sessione da 41'. Il loop di allineamento durata, che gira DOPO i
#     vincoli, riscriveva pure durata_min a 41: la nota dell'atleta veniva letta,
#     risolta, applicata e poi disfatta dal generatore.
#  2. VOLUME PERSO: un nuoto di soglia da 70' pianificato in blocco 70.3 diventava 41'.
#     Mezz'ora a settimana che sparisce dal monte ore senza che nessun conteggio se ne
#     accorga (il budget della settimana successiva legge la durata gia' corretta).
# Qui il fit: le ripetute si adattano alla durata: mai piu' di quelle prescritte dal
# coach (se le ha scritte), mai meno di _SWIM_REPS_MIN — sotto quel numero una serie di
# soglia non e' piu' uno stimolo — e il residuo va in riscaldamento/defaticamento entro
# tetti da vasca. Identico in filosofia a _fit_struttura_durata, con i numeri del nuoto.
_SWIM_WU_MAX_FIT, _SWIM_CD_MAX_FIT = 15, 10

_SWIM_WU_MIN_FIT, _SWIM_CD_MIN_FIT = 5, 3

_SWIM_REPS_MIN = 3

_SWIM_PROFILI_FIT = {   # profilo: (wu, cd, reps_default, rep_min, rec_min, reps_max)
    "soglia":   (10, 5, 5, 4, 1, 10),
    "velocita": (10, 5, 8, 1, 1, 16),
}

def _swim_endu_da_residuo(residuo_sec):
    """Minuti di nuotata aerobica che assorbono il tempo residuo. Il blocco extra porta
    con se' il proprio riposo di transizione (due step nuotati non possono essere
    adiacenti), quindi il riposo si sconta PRIMA di decidere se il blocco esiste."""
    netto = residuo_sec - SWIM_REST_SEC
    n = int(netto // 60) if netto > 0 else 0
    return n if n >= _ENDU_MIN_FIT else 0

def _fit_swim_durata(durata_min, p, reps_esplicite=None):
    """Params di una nuotata adattati perche' la sessione realmente scritta stia dentro
    `durata_min`. Idempotente: al secondo giro `reps` e' ormai esplicita e il risultato
    non cambia (requisito di evento_gia_allineato).

    BUG FIX (29/08/2026) — il fit copriva solo 'soglia'/'velocita' e solo a meta':
      * SFORAMENTO su sessione corta: con capienza < 3 ripetute il pavimento
        _SWIM_REPS_MIN vinceva sul tetto di tempo e una soglia \"da 25 minuti\" usciva
        da 31. E' esattamente il tetto dichiarato dall'atleta violato in silenzio, la
        falla che il fit doveva chiudere. Ora si comprime PRIMA il contorno
        (riscaldamento/defaticamento fino a 5'/3'), come per la corsa.
      * VOLUME PERSO su sessione lunga: oltre reps_max il residuo spariva
        (velocita' 75' e 90' -> 58' entrambe, soglia 90' -> 76'), perche' wu/cd sono
        gia' ai tetti da vasca. Ora diventa una nuotata aerobica continua esplicita.
      * 'tecnica' ed 'endurance' non passavano affatto dal fit: la troncatura per
        difetto di drill/ripetute lasciava scoperti 5-7 minuti (40'->35', 90'->83') e
        il pavimento di 15' della nuotata continua faceva sforare le sessioni corte
        (endurance 25' -> 29'). Entrambi passano ora di qui."""
    if not durata_min:
        return p
    profilo = p.get("profilo", "tecnica")
    pace = p.get("pace_100m_sec") or get_swim_pace_sec_100m()
    pool = p.get("pool_len_m", SWIM_POOL_LEN_M)
    rest = p.get("rest_sec", SWIM_REST_SEC)
    tot_sec = durata_min * 60
    # MODIFICA (27/09/2026): seduta di libreria (vedi SEDUTE_NUOTO): metri fissi, il
    # residuo della durata diventa nuotata continua Z2 come negli altri profili.
    sed = seduta_nuoto_libreria(p, durata_min)
    if sed:
        resid = tot_sec - _swim_lib_sec(sed, pace, rest) - 2 * rest
        endu = _swim_endu_da_residuo(resid)
        # Residuo sotto il blocco minimo: va nel defaticamento (Press lap), non si perde.
        cd_extra = 0 if endu else int(max(0, resid) / pace * 100 // pool) * pool
        return dict(p, seduta=sed["id"], endurance_min=endu, cd_extra_m=cd_extra)
    p = {k: v for k, v in p.items() if k not in ("seduta", "cd_extra_m")}

    def sec(minuti):    # uno step nuotato e' scritto in metri: il tempo segue i metri
        return _swim_m(minuti, pace, pool) / 100 * pace

    out = dict(p)
    conf = _SWIM_PROFILI_FIT.get(profilo)

    if conf:
        d_wu, d_cd, d_reps, d_rep, d_rec, reps_max = conf
        wu   = int(p.get("wu_min", d_wu)); cd = int(p.get("cd_min", d_cd))
        rep  = p.get("rep_min", d_rep);    rec = p.get("rec_min", d_rec)
        unita = sec(rep) + rec * 60
        if unita <= 0:
            return p
        # I due riposi di transizione sono step reali della sessione (vedi il blocco
        # "DUE STEP NUOTATI NON POSSONO ESSERE ADIACENTI"): entrano nel budget.
        def _disp(w, c):
            return tot_sec - sec(w) - sec(c) - 2 * rest
        disp = _disp(wu, cd)
        if int(disp // unita) < _SWIM_REPS_MIN:   # prima si comprime il contorno
            wu, cd = min(wu, _SWIM_WU_MIN_FIT), min(cd, _SWIM_CD_MIN_FIT)
            disp = _disp(wu, cd)
        capienza = int(disp // unita) if disp > 0 else 0
        # Tetto: le ripetute che il coach ha scritto, se le ha scritte (non si va MAI
        # oltre la prescrizione); altrimenti il massimo sensato del profilo.
        tetto = int(reps_esplicite) if reps_esplicite else reps_max
        reps_fit = max(_SWIM_REPS_MIN, min(tetto, capienza))
        residuo  = disp - reps_fit * unita
        if residuo > 0:                   # underflow: prima si allunga il contorno...
            extra  = int(residuo // 60)
            add_cd = min(extra, max(0, _SWIM_CD_MAX_FIT - cd)); cd += add_cd; extra -= add_cd
            add_wu = min(extra, max(0, _SWIM_WU_MAX_FIT - wu)); wu += add_wu
            residuo = _disp(wu, cd) - reps_fit * unita
        out["wu_min"], out["cd_min"], out["reps"] = wu, cd, reps_fit
        out.setdefault("rep_min", rep); out.setdefault("rec_min", rec)
        out["endurance_min"] = _swim_endu_da_residuo(residuo)  # ...poi nuoto aerobico
        return out

    if profilo == "endurance":
        wu = int(p.get("wu_min", 8)); cd = int(p.get("cd_min", 5))
        # Il pavimento di _swim_mid_endurance_min non deve poter sfondare il tetto di
        # tempo: su sessione corta si comprime il contorno, non si allunga la sessione.
        if tot_sec - sec(wu) - sec(cd) - 2 * rest < _SWIM_ENDU_MIN_MIN * 60:
            wu, cd = min(wu, _SWIM_WU_MIN_FIT), min(cd, _SWIM_CD_MIN_FIT)
        out["wu_min"], out["cd_min"] = wu, cd
        out.pop("endurance_min", None)    # il corpo E' gia' la nuotata continua
        return out

    # "tecnica" (e qualunque profilo sconosciuto): la struttura tronca per difetto sia i
    # drill sia le ripetute, quindi avanza sempre qualche minuto. Si assorbe qui.
    wu, cd, drills, reps, rep_m, rep_sec = _swim_tecnica_struttura(
        durata_min, p, pace, pool, rest)
    usato = sec(wu) + len(drills) * reps * rep_sec + sec(cd) + 2 * rest
    out["endurance_min"] = _swim_endu_da_residuo(tot_sec - usato)
    return out

# ── I NUMERI DEL PIANO ARRIVANO DALL'LLM, NON DA UNO SCHEMA ────────────────
# BUG FIX (30/08/2026 — misurato): ogni generatore fa int() diretto sui numeri di
# `params`, e il piano non passa da nessuna validazione di tipo (piano_da_risposta
# controlla la forma del JSON, non il tipo dei singoli campi). Un valore non numerico —
# tipicamente il modello che scrive l'unita' dentro il valore, "rep_min": "10min"
# invece di 10 — solleva ValueError e uccide il run: misurati 21 punti di rottura
# distinti fra sane_wu_cd, _fit_struttura_durata, _build_run_description_base,
# _swim_tecnica_struttura, _endurance_addon_desc, _calc_moving_time_base,
# calc_moving_time e brick_split_min.
# Conseguenza: il run muore DOPO aver pagato la chiamata al modello e PRIMA di scrivere
# qualunque cosa — nessun evento su Intervals.icu, nessun messaggio su Telegram, nessun
# allenamento sull'orologio quel giorno.
# La pulizia sta in UN SOLO punto (merge_session_defaults, lo stesso ingresso comune di
# descrizione/.zwo/moving_time gia' usato dal fix sulla durata vincolante) piu' il brick,
# che ha la sua aritmetica separata: cosi' non c'e' un secondo posto da tenere allineato.
# Due regole, entrambe conservative:
#  - un valore GIA' numerico non viene toccato (nessun arrotondamento nuovo, nessun
#    cambio di comportamento sui piani validi, che sono la totalita' di quelli reali);
#  - da una stringa si prende il numero iniziale ("10min" -> 10, "45s" -> 45, "3,5" ->
#    3.5), perche' scartare il valore farebbe perdere in silenzio la prescrizione del
#    coach e la sostituirebbe col default del tipo. Se non c'e' nessun numero da leggere
#    la chiave si toglie e il default subentra, che e' l'unico ripiego sensato.
_PARAM_NUMERICI = ("reps", "rep_min", "rep_sec", "rep_m", "rec_min", "rec_sec",
                   "rest_sec", "wu_min", "cd_min", "intensity_pct", "pct1", "pct2",
                   "pct3", "cadence_rpm", "strides_reps", "strides_sec",
                   "strides_rec_sec", "strides_pct", "z3_pct", "endurance_min",
                   "pace_100m_sec", "pool_len_m")

_PARAM_NUM_RE = re.compile(r"-?\d+(?:[.,]\d+)?")

def _params_numerici_puliti(params):
    """Copia di `params` in cui le chiavi numeriche sono numeri o non ci sono."""
    p = dict(params or {})
    for k in _PARAM_NUMERICI:
        if k not in p:
            continue
        v = p[k]
        if isinstance(v, (int, float)) and not isinstance(v, bool):
            if v != v or v in (float("inf"), float("-inf")):   # NaN/infinito
                p.pop(k)
            continue                                            # gia' numerico: intatto
        m = _PARAM_NUM_RE.search(v) if isinstance(v, str) else None
        if not m:
            p.pop(k)
            continue
        n = float(m.group(0).replace(",", "."))
        p[k] = int(n) if n == int(n) else n
    return p

def merge_session_defaults(sessione_tipo, params, durata_min=None):
    """Params effettivi = default del tipo (tabella sopra) + override espliciti dell'LLM,
    poi adattati alla durata richiesta (vedi _fit_struttura_durata). `durata_min` e'
    opzionale solo per retrocompatibilita': i tre generatori la passano sempre."""
    params = _params_numerici_puliti(params)
    p = {**SESSION_DEFAULTS.get(sessione_tipo, {}), **(params or {})}
    if not durata_min:
        return p
    if sessione_tipo == "Swim":
        return _fit_swim_durata(durata_min, p, (params or {}).get("reps"))
    return _fit_struttura_durata(sessione_tipo, durata_min, p)

# ── NUOTO: STEP IN METRI, NON IN MINUTI ────────────────────
# BUG FIX avanzamento step su Garmin (sessione di nuoto ferma dopo il primo step, tutti
# i successivi mostrati come "Riposo"): in un workout di vasca uno step con condizione
# di fine a TEMPO viene esportato come step di RIPOSO e si chiude solo con la pressione
# del tasto lap -> l'allenamento non avanza mai da solo. Solo gli step con condizione di
# fine a DISTANZA diventano step di nuoto veri e propri, che il watch chiude da solo al
# raggiungimento dei metri. Quindi: corpo della sessione sempre in metri, tempo solo sui
# recuperi (dove lo step di riposo e' esattamente cio' che serve).
# ATTENZIONE ALLA SINTASSI Intervals.icu: "m" significa MINUTI, i metri si scrivono "mtr"
# (Workout Builder Syntax Quick Guide, forum thread 123701).
SWIM_PACE_SEC_100M = 120

SWIM_POOL_LEN_M    = 25

SWIM_REST_SEC      = 20

_SWIM_ENDU_MIN_MIN = 8

# ── TIPO DI STEP GARMIN DICHIARATO ESPLICITAMENTE (intensity=) ─────────
# Vale per QUALUNQUE workout description-based (nuoto e bici indoor/BikeCross: i tipi che
# non passano dal .zwo e i cui step nascono dal testo della description).
# BUG FIX (causa RESIDUA dell'"step di riposo", quella che gli step in metri da soli NON
# risolvono). Il tipo di uno step per Garmin e' il campo "intensity" del FIT
# (warmup/active/interval/recovery/rest/cooldown) e, se non lo si dichiara, Intervals.icu
# lo DEDUCE dal target con questa regola (David, forum thread 20104 post 3): se la FC
# target e' sotto l'80% del tetto di Z1 lo step diventa "recovery". Tutti i nostri step
# blandi sono scritti "0-80% LTHR" (o "0-89% LTHR"): il limite BASSO del range e' 0
# -> sempre sotto soglia -> RISCALDAMENTO e DEFATICAMENTO venivano esportati come step di
# RECUPERO. Conseguenze, diverse per sport ma stessa radice:
#   - NUOTO (vasca): uno step recovery/rest mette l'orologio in "rest mode", crede che tu
#     sia fermo al muro -> NON conta le vasche, NON registra distanza/bracciate/passo e
#     NON mostra il target (mostra la distanza dello step precedente). Da qui il "parte il
#     riposo" dopo il blocco centrale, con registrazione interrotta e fase finale sparita.
#   - BICI INDOOR: nessun blocco della sessione (non c'e' conteggio vasche da sospendere),
#     ma warmup e cooldown vengono etichettati e mostrati come "Riposo"/"Recupero" invece
#     che come riscaldamento e defaticamento, e il target dello step non viene visualizzato.
#   Riferimenti: forum.intervals.icu thread 20104 (regola di deduzione dell'intensity e
#   nota di Peter sul fatto che su uno step "recovery" il watch non mostra il target),
#   thread 19540 (step recovery non riconosciuti da Garmin), thread 86560 post 3
#   ("the intensity=rest triggers the rest mode": la conferma che il token funziona),
#   thread 121993 (sintassi completa: intensity=active/interval/rest/recovery/warmup/
#   cooldown, dopo il target e prima delle note tra parentesi).
# Da qui: OGNI step dichiara il proprio tipo. Cosi' l'intensity non viene piu' dedotta dal
# target e le zone basse restano zone basse ALLENATE, non pause.
# NOTA su cio' che NON e' un bug: in vasca la chiusura di uno step nuotato con il tasto
# lap e' comportamento Garmin, non dello script (l'orologio non puo' sapere che ti sei
# fermato al muro; "rest type: lap button press vs fixed rest time" e' ancora una
# feature request aperta su Intervals.icu, thread 41166).
#
# ── BUG FIX GARMIN (CAUSA FINALE): DUE STEP NUOTATI NON POSSONO ESSERE ADIACENTI ──────
# Sintomo: su Garmin la sessione di nuoto resta ferma sul primo step e NON avanza
# nemmeno premendo lap; su Suunto/Coros la stessa description funziona.
# Causa: in Pool Swim il tasto lap NON e' un "next step", e' il tasto RIPOSO. La
# macchina a stati del workout in vasca e' "step nuotato -> step di riposo -> step
# nuotato": il lap chiude la ripetuta e fa partire il RIPOSO, e alla fine del riposo il
# watch entra da solo nella ripetuta successiva (MySwimPro/Garmin: "once the lap button
# is tapped, the workout will advance to the rest period... the next rep will begin
# automatically"). Se lo step successivo NON e' un riposo, il lap non ha dove andare e
# l'allenamento si pianta li'. E' lo stesso difetto che 80/20 Endurance ha dovuto
# correggere su tutta la propria libreria nuoto aggiungendo "a 15-second pause between
# all segments" (forum 8020endurance, "Structured Swims"), ed e' il motivo per cui su
# Intervals.icu i riposi in vasca vanno dichiarati espliciti (thread 75705, 86560).
# Suunto e Coros non hanno questa macchina a stati (lo step avanza a distanza raggiunta
# o con lap libero) -> stessa description, nessun problema: da qui il bug "solo Garmin".
# Fix: fra due step NUOTATI adiacenti va sempre inserito uno step di riposo esplicito.
# Nelle sessioni generate qui i punti scoperti erano tre:
#   - riscaldamento -> prima ripetuta/primo drill (TUTTI i profili: e' esattamente il
#     punto in cui la sessione si bloccava "dopo il primo step");
#   - nuotata continua -> defaticamento (profilo "endurance");
#   - ultima ripetuta -> defaticamento: i Garmin piu' vecchi saltano l'ultimo riposo di
#     un blocco ripetuto (thread 86560), quindi senza riposo di transizione anche negli
#     altri profili il defaticamento resta irraggiungibile.
# Il riposo di transizione dura SWIM_REST_SEC (o params.rest_sec) ed e' anticipabile col
# lap come qualunque riposo: sul watch e' la pausa al muro che si fa comunque.
# Costo run invariato: e' solo testo in piu' nella description gia' inviata.
STEP_INT_WU   = "intensity=warmup"

STEP_INT_WORK = "intensity=active"

                                      # e i recuperi ATTIVI: float, Z2, %Power)
STEP_INT_REC  = "intensity=recovery"

STEP_INT_CD   = "intensity=cooldown"

STEP_INT_REST = "intensity=rest"

def _swim_mid_endurance_min(durata_min, wu, cd, rest):
    """Minuti della nuotata continua nel profilo 'endurance'. UNICA fonte condivisa da
    description e swim_durata_reale_min: erano due espressioni gemelle, ed entrambe
    dimenticavano i due riposi di transizione (sessione piu' lunga del pianificato).
    Il pavimento era 15': su una sessione da 25' faceva sforare il tetto dichiarato
    (25 -> 29). _fit_swim_durata comprime gia' il contorno per evitarlo; questo resta
    come ultima rete, abbassato a un minimo che sia comunque uno stimolo aerobico."""
    return max(_SWIM_ENDU_MIN_MIN, durata_min - wu - cd - 2 * rest / 60)

def _swim_m(minuti, pace_sec_100m=SWIM_PACE_SEC_100M, pool_len_m=SWIM_POOL_LEN_M):
    """Minuti previsti per uno step di nuoto -> metri corrispondenti, arrotondati alla
    lunghezza vasca (uno step di nuoto DEVE finire a distanza, vedi bug fix sopra)."""
    metri = minuti * 60 / max(1, pace_sec_100m) * 100
    return max(pool_len_m, int(round(metri / pool_len_m)) * pool_len_m)

# ── PASSO DI RIFERIMENTO NUOTO: MISURATO, NON ASSUNTO ──────
# La conversione minuti -> metri vale quanto il passo con cui viene fatta: con un passo
# di riferimento sbagliato le distanze scritte sugli step non corrispondono piu' alla
# durata pianificata della seduta, e l'unico modo di rimediare sarebbe rifare i conti a
# mano in vasca. Il passo viene quindi RICAVATO dai dati dell'atleta, non assunto:
#   1. CSS/soglia nuoto configurata su Intervals.icu (sportSettings "Swim",
#      threshold_pace in m/s — e' il valore che il test CSS 400+200 dello script
#      alimenta), rallentata di SWIM_CSS_TO_EASY per passare da soglia a Z1-Z2;
#   2. mediana del passo reale delle nuotate recenti (in stragrande maggioranza
#      aerobiche: e' gia' il passo facile vero, e segue da solo i miglioramenti);
#   3. SWIM_PACE_SEC_100M come ultima rete di sicurezza.
# Stessa gerarchia di get_swim_lthr(): il valore configurato/testato ha priorita' sulla
# stima. params.pace_100m_sec resta l'override esplicito per la singola sessione.
SWIM_PACE_LOOKBACK_DAYS = 90

SWIM_PACE_PLAUSIBILE    = (75, 210)

SWIM_CSS_TO_EASY        = 1.15

_SWIM_PACE_CACHE = None

def fmt_swim_pace(sec_100m):
    m, s = divmod(round(sec_100m), 60)
    return f"{m}:{s:02d}"

def _swim_threshold_pace_ms():
    """threshold_pace (m/s) configurato per lo sport Swim su Intervals.icu, se c'e'.
    Riusa la stessa cache del GET /athlete popolata da get_lthr()."""
    if _ATHLETE_CACHE is None:
        get_lthr(sport="Swim")
    for s in (_ATHLETE_CACHE or {}).get("sportSettings", []):
        if "Swim" in s.get("types", []):
            return s.get("threshold_pace")
    return None

def get_swim_pace_ref():
    """(passo sec/100m, fonte) con cui convertire i minuti di nuoto in metri.
    Non solleva mai: se la lettura dei dati fallisce si torna alla costante, perche'
    un problema di rete non deve impedire la creazione della sessione."""
    global _SWIM_PACE_CACHE
    if _SWIM_PACE_CACHE is not None:
        return _SWIM_PACE_CACHE
    lo, hi = SWIM_PACE_PLAUSIBILE
    pace, fonte = SWIM_PACE_SEC_100M, "default (nessun dato nuoto)"
    try:
        ms = _swim_threshold_pace_ms()
        if ms and ms > 0 and lo <= 100 / ms * SWIM_CSS_TO_EASY <= hi:
            pace  = 100 / ms * SWIM_CSS_TO_EASY
            fonte = (f"CSS configurata {fmt_swim_pace(100 / ms)}/100m "
                     f"+{round((SWIM_CSS_TO_EASY - 1) * 100)}%")
        else:
            passi = []
            for a in get_activities(SWIM_PACE_LOOKBACK_DAYS):
                if a.get("type") != "Swim":   # solo vasca: in acque libere il passo
                    continue                  # non e' confrontabile (niente virate, corrente)
                dist = a.get("distance") or 0
                tempo = a.get("moving_time") or a.get("elapsed_time") or 0
                if dist >= 400 and tempo > 0:
                    passi.append(tempo / dist * 100)
            passi = sorted(x for x in passi if lo <= x <= hi)
            if passi:
                pace  = passi[len(passi) // 2]
                fonte = f"mediana di {len(passi)} nuotate ultimi {SWIM_PACE_LOOKBACK_DAYS}gg"
    except Exception as e:
        pace, fonte = SWIM_PACE_SEC_100M, f"default (lettura dati nuoto fallita: {type(e).__name__})"
    _SWIM_PACE_CACHE = (round(pace), fonte)
    return _SWIM_PACE_CACHE

def get_swim_pace_sec_100m():
    return get_swim_pace_ref()[0]

# Catalogo drill di tecnica (Total Immersion / Swim Smooth): nome dello step + focus.
# Il nome diventa il TITOLO dello step sull'orologio, cosi' durante la sessione si sa
# sempre quale esercizio si sta eseguendo senza doverlo ricordare a memoria.
SWIM_DRILLS = [
    ("Catch up",            "una mano attende l'altra davanti, allunga e scivola"),
    ("Battute per bracciata", "sei battute di gambe sul fianco, poi cambio lato"),
    ("Finger trail",        "dita che sfiorano l'acqua nel recupero, gomito alto"),
    ("Braccio singolo",     "un braccio solo, l'altro esteso avanti: presa e trazione"),
    ("Battuta di gambe",    "gambe dall'anca, caviglia morbida, corpo allineato"),
    ("Sighting",            "tre bracciate poi occhi fuori: orientamento acque libere"),
]

# Il cue di uno step non deve contenere cifre ne' token di durata/distanza: il parser di
# Intervals.icu li leggerebbe come condizione di fine dello step e lo romperebbe (e' la
# causa storica del blocco con la riga drills "6kick-1stroke").
_SWIM_CUE_CIFRE = re.compile(r"\d+(?:[.,]\d+)?\s*")

_SWIM_CUE_UNITA = re.compile(r"(?<!\w)(?:mtr|km|mi|rpm|h|m|s)(?!\w)", re.IGNORECASE)

def _swim_cue(nome):
    """Nome del drill reso utilizzabile come titolo di uno step: prima via tutte le
    cifre ('6kick-1stroke' -> 'kick stroke'), poi le unita' rimaste orfane ('50m' ->
    'm' -> ''), infine i trattini (che il parser accoppia alle cifre nei range)."""
    testo = _SWIM_CUE_CIFRE.sub(" ", str(nome or "")).replace("-", " ")
    testo = _SWIM_CUE_UNITA.sub(" ", testo)
    testo = re.sub(r"\s{2,}", " ", testo).strip(" -—:,·()")
    return testo or "drill"

def _swim_tecnica_struttura(durata_min, p, pace, pool, rest):
    """Struttura effettiva della sessione nuoto 'tecnica' (drill selezionati, ripetute
    per drill, metri e secondi di ripetuta). UNICA FONTE DI VERITA' condivisa dalla
    descrizione e dal calcolo della durata reale: erano gli stessi numeri scritti due
    volte, ed e' esattamente il drift che SESSION_DEFAULTS evita per gli altri tipi."""
    wu = p.get("wu_min", 5); cd = p.get("cd_min", 5)
    drills = p.get("drills") or SWIM_DRILLS
    # Accetta sia ["nome", ...] (params dell'LLM) sia [("nome", "focus"), ...].
    drills = [d if isinstance(d, (list, tuple)) else (d, "") for d in drills]
    # I due riposi di transizione (dopo il riscaldamento, prima del defaticamento) sono
    # step reali e venivano SOMMATI a fine conto: la sessione usciva sistematicamente
    # piu' lunga della durata pianificata. Si scontano qui, alla fonte.
    corpo_min = max(6, durata_min - wu - cd - 2 * rest / 60)
    # Ripetuta di 2 vasche: abbastanza lunga per sentire il gesto, abbastanza corta
    # da non perdere la qualita' tecnica (che e' lo scopo della sessione).
    rep_m   = max(pool, int(round(p.get("rep_m", pool * 2) / pool)) * pool)
    rep_sec = rep_m / 100 * pace + rest
    # Su una sessione breve si riduce il NUMERO di drill, non le ripetute: due sole
    # ripetute per drill non bastano a fissare il gesto, e allungare la sessione
    # oltre la durata pianificata sballerebbe il carico della settimana.
    drills  = drills[:max(2, min(6, int(corpo_min * 60 // (2 * rep_sec))))]
    # Per DIFETTO, non per eccesso: con round() la sessione tecnica usciva fino a 4
    # minuti piu' lunga della durata pianificata (misurato su 40, 55, 95, 120min). Una
    # ripetuta in piu' non aggiunge nulla alla tecnica e sfora il tetto di tempo.
    reps    = max(2, int(corpo_min * 60 / len(drills) / rep_sec))
    return wu, cd, drills, reps, rep_m, rep_sec

# ── LIBRERIA SEDUTE NUOTO PER FASE (MODIFICA 27/09/2026 — richiesta di Simone) ──────
# Come le schede S&C (vedi SCHEDA_BASE): sedute FISSE e deterministiche, scelte dal codice
# e non scritte dal modello. Fonti: Triathlon Plus "Your Swim Guide" (piani Beginner e
# Improver: drill a 2x25-50, serie CSS a 100, piramidi, pull con palette) e ChiliTri
# "Swim Workouts for Triathletes" (K. Parnell: Pulling Along 4x200 pull, Start Strong and
# Hold On, side kick / 6-1-6 / 1-arm / pugni chiusi, sighting a occhi di coccodrillo).
# Prima: la struttura nasceva dai soli params del modello (profilo + numeri) e cambiava a
# ogni piano. Ora:
# - chiave (profilo, macro-fase): il profilo e' quello DEFINITIVO del piano (dopo tutti i
#   safety-net) e la macro-fase e' get_fase_macro, la stessa delle schede S&C;
# - due taglie per chiave (media ~1300-1900m entro 40', piena ~1600-2800m): si prende la piu' grande
#   che sta nella durata della voce; il residuo diventa nuotata continua Z2 come nel fit
#   (_swim_endu_da_residuo). Sotto la taglia media resta il generatore parametrico con il
#   suo fit (tetti dichiarati da nota, sedute corte): nulla cambia su quei casi;
# - biometrici: vedi assegna_libreria_nuoto (solo la seduta di OGGI, regola gia' scritta
#   nella banda: giallo = qualita' convertita in Z1-Z2, rosso = recupero attivo);
# - palette solo nelle sedute di qualita' (build): la qualita' non divide mai il giorno con
#   la palestra (correggi_conflitti_palestra_qualita), quindi nuoto Z1-Z2 dopo la forza
#   resta sempre senza palette come da regola del 27/09/2026.
# Conseguenza osservabile: sull'orologio e a calendario il nuoto e' una di queste sedute,
# con titolo fisso, metri fissi in % Pace sulla CSS e defaticamento "Press lap".
# Formato elementi del corpo (metri fissi, vasca da 25):
#   ("rip", ripetute, metri, zona, recupero_s, cue, nota)  blocco ripetuto
#   ("cont", metri, zona, cue, nota)                       step singolo
# Cue e note senza cifre: il parser di Intervals.icu le leggerebbe come fine dello step.
_SWIM_LIB_ZONE = {"Z1": "68-77% Pace", "Z2": "78-87% Pace", "Z3": "88-94% Pace",
                  "Z4": "95-100% Pace", "Z5": "100-104% Pace"}

def _swim_lib_pace(zona, pace_easy):
    """sec/100m con cui si nuota la zona: Z1-Z2 al passo facile misurato (come il resto del
    nuoto), Z3 al centro della fascia (CSS/0.91), Z4 a CSS, Z5 a CSS -5% (come 'velocita')."""
    css = pace_easy / SWIM_CSS_TO_EASY
    return {"Z3": css / 0.91, "Z4": css, "Z5": css * 0.95}.get(zona, pace_easy)

_CRESC = ("rip", 4, 50, "Z2", 15, "Crescendo", "da facile a svelto dentro la vasca")

_PULL  = "Pull con boa"

def _sed(sid, titolo, wu, corpo, cd=200, wu_z="Z1"):
    return {"id": sid, "titolo": titolo, "wu": wu, "wu_z": wu_z, "corpo": corpo, "cd": cd}

_TECNICA = [
    _sed("tec_m", "Tecnica drill", 200, [
        ("rip", 4, 50, "Z1", 20, "Battuta di gambe sul fianco", "orecchio sul braccio avanti"),
        ("rip", 4, 50, "Z1", 20, "Sei battute per bracciata", "rotazione dall'anca"),
        ("rip", 4, 50, "Z1", 20, "Braccio singolo", "gomito alto nella presa"),
        ("rip", 4, 50, "Z1", 20, "Pugni chiusi", "trazione con l'avambraccio"),
        ("rip", 3, 100, "Z2", 20, "Nuoto completo", "bracciata lunga, applica i drill")]),
    _sed("tec_l", "Tecnica drill lunga", 300, [
        ("rip", 4, 50, "Z1", 20, "Battuta di gambe sul fianco", "orecchio sul braccio avanti"),
        ("rip", 4, 50, "Z1", 20, "Sei battute per bracciata", "rotazione dall'anca"),
        ("rip", 4, 50, "Z1", 20, "Catch up", "una mano attende l'altra davanti"),
        ("rip", 4, 50, "Z1", 20, "Braccio singolo", "gomito alto nella presa"),
        ("rip", 4, 50, "Z1", 20, "Finger trail", "gomito alto nel recupero"),
        ("rip", 4, 50, "Z1", 20, "Pugni chiusi", "trazione con l'avambraccio"),
        ("rip", 6, 100, "Z2", 20, "Nuoto completo", "bracciata lunga, applica i drill")]),
]

SEDUTE_NUOTO = {
    ("tecnica", "*"): _TECNICA,
    ("endurance", "base"): [
        _sed("end_base_m", "Fondo aerobico", 300, [
            ("rip", 3, 300, "Z2", 30, "Nuotata regolare", "respirazione costante"),
            ("rip", 4, 100, "Z2", 20, _PULL, "solo braccia, niente palette")]),
        _sed("end_base_l", "Fondo aerobico pull", 400, [
            ("rip", 4, 200, "Z2", 30, _PULL, "solo braccia, niente palette"),
            ("rip", 4, 100, "Z2", 20, "Nuotata regolare", "respirazione costante"),
            ("rip", 4, 200, "Z2", 30, _PULL, "solo braccia, niente palette"),
            ("rip", 4, 50, "Z1", 20, "Catch up", "una mano attende l'altra davanti")])],
    ("endurance", "specifico"): [
        _sed("end_spec_m", "Fondo e ritmo gara", 300, [
            ("cont", 600, "Z2", "Nuotata continua", "sighting ogni tanto, come in gara"),
            ("rip", 4, 200, "Z3", 20, "Ritmo gara", "passo da mezzo ironman")]),
        _sed("end_spec_l", "Fondo e ritmo gara lungo", 400, [
            ("cont", 1000, "Z2", "Nuotata continua", "sighting ogni tanto, come in gara"),
            ("rip", 5, 200, "Z3", 20, "Ritmo gara", "passo da mezzo ironman"),
            ("rip", 4, 50, "Z2", 15, "Sighting", "occhi da coccodrillo, poi respira di lato")])],
    ("endurance", "taper"): [
        _sed("end_tap_m", "Fondo breve con richiamo gara", 300, [
            ("rip", 4, 200, "Z2", 20, "Nuotata regolare", "respirazione costante"),
            ("rip", 4, 50, "Z3", 20, "Ritmo gara", "passo da mezzo ironman")]),
        _sed("end_tap_l", "Fondo con richiamo gara", 300, [
            ("rip", 6, 200, "Z2", 20, "Nuotata regolare", "respirazione costante"),
            ("rip", 4, 50, "Z3", 20, "Ritmo gara", "passo da mezzo ironman")])],
    ("soglia", "base"): [
        _sed("css_base_m", "Soglia CSS a cento", 300, [
            _CRESC, ("rip", 8, 100, "Z4", 20, "CSS", "stesso passo su ogni ripetuta")],
            wu_z="Z2"),
        _sed("css_base_l", "Soglia CSS a cento lunga", 400, [
            _CRESC, ("rip", 12, 100, "Z4", 20, "CSS", "stesso passo su ogni ripetuta"),
            ("rip", 4, 100, "Z2", 20, _PULL, "solo braccia")], wu_z="Z2")],
    ("soglia", "build"): [
        _sed("css_build_m", "Soglia CSS a duecento", 300, [
            _CRESC, ("rip", 5, 200, "Z4", 20, "CSS", "passo costante, uscita pulita")],
            wu_z="Z2"),
        _sed("css_build_l", "Soglia CSS a duecento lunga", 400, [
            _CRESC, ("rip", 6, 200, "Z4", 20, "CSS", "passo costante, uscita pulita"),
            ("rip", 4, 100, "Z2", 20, "Pull con boa e palette",
             "entrata in linea con la spalla")], wu_z="Z2")],
    ("soglia", "specifico"): [
        _sed("css_spec_m", "Soglia lunga ritmo gara", 300, [
            _CRESC, ("rip", 3, 400, "Z4", 30, "Soglia lunga", "parti controllato, chiudi uguale")],
            wu_z="Z2"),
        _sed("css_spec_l", "Soglia lunga ritmo gara piena", 400, [
            _CRESC, ("rip", 4, 400, "Z4", 30, "Soglia lunga", "parti controllato, chiudi uguale"),
            ("rip", 4, 50, "Z2", 15, "Sighting", "occhi da coccodrillo, poi respira di lato")],
            wu_z="Z2")],
    ("soglia", "taper"): [
        _sed("css_tap_m", "Soglia di richiamo", 300, [
            _CRESC, ("rip", 4, 100, "Z4", 20, "CSS", "passo gara, zero fatica"),
            ("rip", 4, 50, "Z5", 30, "Svelto", "frequenza alta, bracciata pulita")], wu_z="Z2"),
        _sed("css_tap_l", "Soglia di richiamo piena", 400, [
            _CRESC, ("rip", 6, 100, "Z4", 20, "CSS", "passo gara, zero fatica"),
            ("rip", 4, 50, "Z5", 30, "Svelto", "frequenza alta, bracciata pulita")], wu_z="Z2")],
    ("velocita", "base"): [
        _sed("vel_base_m", "Velocita' a cinquanta", 300, [
            _CRESC, ("rip", 8, 50, "Z5", 30, "Veloce", "frequenza alta, gambe tranquille"),
            ("rip", 4, 100, "Z2", 20, _PULL, "solo braccia")], wu_z="Z2"),
        _sed("vel_base_l", "Velocita' a cinquanta lunga", 400, [
            _CRESC, ("rip", 12, 50, "Z5", 30, "Veloce", "frequenza alta, gambe tranquille"),
            ("rip", 6, 100, "Z2", 20, _PULL, "solo braccia")], wu_z="Z2")],
    ("velocita", "build"): [
        _sed("vel_build_m", "Velocita' parti forte e tieni", 300, [
            _CRESC, ("rip", 6, 100, "Z5", 30, "Veloce", "recupero al muro tutto"),
            ("rip", 4, 50, "Z5", 30, "Chiusura", "la piu' veloce della serata")], wu_z="Z2"),
        _sed("vel_build_l", "Velocita' parti forte e tieni lunga", 400, [
            _CRESC, ("rip", 4, 50, "Z4", 20, "Forte", "entra nel ritmo"),
            ("rip", 8, 100, "Z5", 30, "Veloce", "recupero al muro tutto"),
            ("rip", 4, 100, "Z2", 20, "Pull con boa e palette",
             "entrata in linea con la spalla")], wu_z="Z2")],
    ("velocita", "specifico"): [
        _sed("vel_spec_m", "Velocita' partenza gara", 300, [
            _CRESC, ("rip", 4, 50, "Z5", 30, "Partenza gara", "esci veloce dal gruppo"),
            ("rip", 3, 300, "Z4", 30, "Assesta il passo", "dopo la partenza torna a soglia")],
            wu_z="Z2"),
        _sed("vel_spec_l", "Velocita' partenza gara lunga", 400, [
            _CRESC, ("rip", 6, 50, "Z5", 30, "Partenza gara", "esci veloce dal gruppo"),
            ("rip", 4, 300, "Z4", 30, "Assesta il passo", "dopo la partenza torna a soglia"),
            ("rip", 4, 50, "Z2", 15, "Sighting", "occhi da coccodrillo, poi respira di lato")],
            wu_z="Z2")],
    ("velocita", "taper"): [
        _sed("vel_tap_m", "Velocita' di richiamo", 300, [
            _CRESC, ("rip", 6, 50, "Z5", 40, "Veloce", "frequenza alta, zero fatica"),
            ("rip", 4, 100, "Z2", 20, "Nuotata regolare", "respirazione costante")], wu_z="Z2"),
        _sed("vel_tap_l", "Velocita' di richiamo piena", 400, [
            _CRESC, ("rip", 8, 50, "Z5", 40, "Veloce", "frequenza alta, zero fatica"),
            ("rip", 4, 100, "Z2", 20, "Nuotata regolare", "respirazione costante")], wu_z="Z2")],
}

# Build = base per il fondo (nessuna differenza metodologica sul volume Z2).
SEDUTE_NUOTO[("endurance", "build")] = SEDUTE_NUOTO[("endurance", "base")]

SEDUTE_NUOTO_ID = {s["id"]: s for lista in SEDUTE_NUOTO.values() for s in lista}

# MODIFICA (27/09/2026 — decisione di Simone): le sedute sono scritte per un passo facile
# di riferimento (2:00/100m = CSS 1:44) e si TARANO sulla CSS configurata su Intervals.icu
# (get_swim_pace_ref: CSS +15%). Prima, con una CSS piu' lenta, la taglia media superava i
# 40' e la seduta da 40-45' ricadeva sul generatore parametrico; con una piu' veloce la
# stessa seduta durava meno del previsto. Ora ripetute (non la loro distanza, che da' il
# nome alla serie) e metri di riscaldamento/continua/defaticamento scalano di un fattore
# scelto perche' la seduta duri lo stesso tempo che al riferimento (i recuperi a tempo non
# scalano, quindi il fattore si cerca, non si calcola come rapporto dei passi).
# Conseguenza osservabile:
# i minuti delle taglie sono quelli della libreria, i metri sono quelli della tua CSS.
SWIM_LIB_PACE_RIF = 120

def _seduta_scalata(sed, f, pool):
    def mt(m):
        return max(pool, int(round(m * f / pool)) * pool)
    corpo = [(el[0], max(2, int(round(el[1] * f))), *el[2:]) if el[0] == "rip"
             else (el[0], mt(el[1]), *el[2:]) for el in sed["corpo"]]
    return dict(sed, wu=mt(sed["wu"]), cd=mt(sed["cd"]), corpo=corpo)

def _seduta_tarata(sed, pace, pool=SWIM_POOL_LEN_M, rest=SWIM_REST_SEC):
    """Copia della seduta tarata sul passo facile dell'atleta (vedi SWIM_LIB_PACE_RIF):
    per ogni fattore 0.50-2.00 il riscaldamento (min 100m) chiude lo scarto a vasche
    intere; la durata non supera mai quella di riferimento, cosi' una taglia che sta nei
    minuti al riferimento ci sta a qualunque CSS."""
    obiettivo = _swim_lib_sec(sed, SWIM_LIB_PACE_RIF, rest)
    vasca_wu = pool / 100 * _swim_lib_pace(sed["wu_z"], pace)
    cand = []
    for k in range(50, 201):
        x = _seduta_scalata(sed, k / 100, pool)
        scarto = obiettivo - _swim_lib_sec(x, pace, rest)
        x["wu"] = max(100, x["wu"] + int(scarto // vasca_wu) * pool)
        cand.append((_swim_lib_sec(x, pace, rest), k, x))
    sotto = [c for c in cand if c[0] <= obiettivo]
    if not sotto:
        return min(cand, key=lambda c: c[0])[2]
    # Entro un minuto dalla durata migliore, il fattore piu' vicino al rapporto dei passi:
    # il riscaldamento corregge lo scarto, non sostituisce le ripetute tolte o aggiunte.
    k_ideale = 100 * SWIM_LIB_PACE_RIF / max(1, pace)
    migliore = max(c[0] for c in sotto)
    return min((c for c in sotto if c[0] >= migliore - 60),
               key=lambda c: abs(c[1] - k_ideale))[2]

def _swim_lib_sec(sed, pace, rest, cd_extra=0):
    """Secondi della seduta di libreria SENZA i due riposi di transizione (dopo il
    riscaldamento e prima del defaticamento), che come per gli altri profili li conta
    swim_durata_reale_min. Dentro: i riposi fra un elemento del corpo e il successivo.
    cd_extra: metri aggiunti al defaticamento quando il residuo e' troppo corto per un
    blocco continuo a se' (vedi _fit_swim_durata)."""
    tot = (sed["wu"] / 100 * _swim_lib_pace(sed["wu_z"], pace)
           + (sed["cd"] + cd_extra) / 100 * pace + (len(sed["corpo"]) - 1) * rest)
    for el in sed["corpo"]:
        if el[0] == "rip":
            _, reps, m, z, rec = el[:5]
            tot += reps * (m / 100 * _swim_lib_pace(z, pace) + rec)
        else:
            tot += el[1] / 100 * _swim_lib_pace(el[2], pace)
    return tot

def seduta_nuoto_libreria(p, durata_min):
    """Seduta di libreria per questi params (serve params.macro_nuoto, messo da
    assegna_libreria_nuoto), o None -> generatore parametrico. Funzione pura di
    (profilo, macro, durata, passo): ricalcolata a ogni chiamata, cosi' segue la durata
    anche quando un safety-net successivo (tetto carico, vincoli) la accorcia."""
    macro = p.get("macro_nuoto")
    if not macro or not durata_min or p.get("acque_libere"):
        return None
    profilo = p.get("profilo", "tecnica")
    cand = SEDUTE_NUOTO.get((profilo, macro)) or SEDUTE_NUOTO.get((profilo, "*")) or []
    pace = p.get("pace_100m_sec") or get_swim_pace_sec_100m()
    rest = p.get("rest_sec", SWIM_REST_SEC)
    scelta = None
    for sed in cand:   # taglie in ordine crescente: vince la piu' grande che ci sta
        sed = _seduta_tarata(sed, pace, p.get("pool_len_m", SWIM_POOL_LEN_M), rest)
        if _swim_lib_sec(sed, pace, rest) + 2 * rest <= durata_min * 60:
            scelta = sed
    return scelta

def _swim_lib_desc(sed, rest, endu_blk, pausa, cd_extra=0):
    """Description Intervals.icu della seduta di libreria: stesse regole del generatore
    (step nuotati a distanza, % Pace, riposo fra due step nuotati, Press lap finale)."""
    out = (f"- riscaldamento {sed['wu']}mtr {_SWIM_LIB_ZONE[sed['wu_z']]} {STEP_INT_WU}\n")
    for el in sed["corpo"]:
        out += pausa
        if el[0] == "rip":
            _, reps, m, z, rec, cue, nota = el
            out += (f"\n{reps}x\n- {_swim_cue(cue)} {m}mtr {_SWIM_LIB_ZONE[z]} "
                    f"{STEP_INT_WORK} ({nota})\n- recupero {rec}s {STEP_INT_REST}\n\n")
        else:
            _, m, z, cue, nota = el
            out += f"\n- {_swim_cue(cue)} {m}mtr {_SWIM_LIB_ZONE[z]} {STEP_INT_WORK} ({nota})\n"
    return (out + endu_blk + pausa
            + f"- Press lap defaticamento {sed['cd'] + cd_extra}mtr {_SWIM_LIB_ZONE['Z1']} "
              f"{STEP_INT_CD}\n")

def assegna_libreria_nuoto(piano, fase, baseline_bio=None, today_str=None):
    """Aggancia le voci di nuoto in vasca alla libreria (params.macro_nuoto). Solo la seduta
    di OGGI legge i biometrici, con la regola gia' scritta nella banda (calc_baseline_
    biometrici): giallo -> qualita' convertita in Z1-Z2 (profilo 'endurance'), rosso ->
    recupero attivo (profilo 'tecnica'). Banda non calcolabile: nessuna conversione."""
    note = []
    macro = get_fase_macro(fase or "")
    b = baseline_bio or {}
    banda = b.get("banda") if b.get("ok") else None
    for s in piano or []:
        if (s.get("sessione_tipo") != "Swim" or s.get("sport") != "Swim"
                or s.get("azione") == "riposo"):
            continue
        p = dict(s.get("params") or {}, macro_nuoto=macro)
        prof = p.get("profilo") or "tecnica"
        if s.get("data") == today_str and banda == "rosso" and prof != "tecnica":
            p["profilo"] = "tecnica"
            note.append(f"{s.get('data')}: banda rossa, nuoto {prof} -> tecnica")
        elif s.get("data") == today_str and banda == "giallo" and prof in _PROFILI_QUALITA:
            p["profilo"] = "endurance"
            note.append(f"{s.get('data')}: banda gialla, nuoto {prof} -> endurance Z1-Z2")
        s["params"] = p
        sed = seduta_nuoto_libreria(p, int(s.get("durata_min") or 0))
        if sed:
            s["sessione_nome"] = f"Nuoto {sed['titolo']} {int(s.get('durata_min') or 0)}min"
            _invalida_nota_esecuzione(s)   # descriveva la struttura del modello, non questa
    return note

# ── DURATA REALE DI UNA SESSIONE DI NUOTO ──────────────────
# BUG FIX minuti dichiarati vs sessione reale: il nuoto e' l'unico tipo strutturato che
# NON aveva un ramo in _calc_moving_time_base -> si ricadeva su "durata_min * 60", cioe'
# sui minuti PROPOSTI dal piano, mentre la sessione realmente scritta dura tutt'altro:
# i profili "soglia"/"velocita" sono costruiti solo da reps/rep_min/rec_min (default:
# 40 e 31 min) e ignorano durata_min, e in "tecnica"/"endurance" gli step vengono
# arrotondati alla vasca. Risultato: una nuotata pianificata 60min finiva a calendario
# come 60min (e col nome che prometteva 60min) ma da eseguire ne bastavano 40.
# Qui la durata si ricava dagli step effettivamente generati: metri / passo + recuperi.
def swim_durata_reale_min(durata_min, params=None):
    """Minuti effettivi della sessione di nuoto che build_run_description genera con
    questi params (stessi profili, stessi default, stessa conversione minuti->metri)."""
    # `durata_min` va passata: senza, i profili di qualita' non passano dal fit e questa
    # funzione misurerebbe una sessione diversa da quella che build_run_description scrive.
    p = merge_session_defaults("Swim", params, durata_min)
    pace = p.get("pace_100m_sec") or get_swim_pace_sec_100m()
    pool = p.get("pool_len_m", SWIM_POOL_LEN_M)
    rest = p.get("rest_sec", SWIM_REST_SEC)

    def sec(minuti):   # tempo reale di uno step nuotato: e' scritto in metri, non in minuti
        return _swim_m(minuti, pace, pool) / 100 * pace

    profilo = p.get("profilo", "tecnica")
    if p.get("seduta") in SEDUTE_NUOTO_ID:   # MODIFICA (27/09/2026): libreria per fase
        tot = _swim_lib_sec(_seduta_tarata(SEDUTE_NUOTO_ID[p["seduta"]], pace, pool, rest),
                            pace, rest, int(p.get("cd_extra_m") or 0))
    elif profilo == "soglia":
        wu = p.get("wu_min", 10); cd = p.get("cd_min", 5)
        reps = p.get("reps", 5); rep = p.get("rep_min", 4); rec = p.get("rec_min", 1)
        tot = sec(wu) + reps * (sec(rep) + rec * 60) + sec(cd)
    elif profilo == "velocita":
        wu = p.get("wu_min", 10); cd = p.get("cd_min", 5)
        reps = p.get("reps", 8); rep = p.get("rep_min", 1); rec = p.get("rec_min", 1)
        tot = sec(wu) + reps * (sec(rep) + rec * 60) + sec(cd)
    elif profilo == "endurance":
        wu = p.get("wu_min", 8); cd = p.get("cd_min", 5)
        tot = sec(wu) + sec(_swim_mid_endurance_min(durata_min, wu, cd, rest)) + sec(cd)
    else:
        wu, cd, drills, reps, rep_m, rep_sec = _swim_tecnica_struttura(
            durata_min, p, pace, pool, rest)
        tot = sec(wu) + len(drills) * reps * rep_sec + sec(cd)
    # I due riposi di transizione (dopo il riscaldamento e prima del defaticamento) sono
    # step reali della sessione: vanno contati qui, altrimenti torna il drift fra minuti
    # dichiarati nel titolo/moving_time e sessione realmente scritta.
    tot += 2 * rest
    # Blocco aerobico che assorbe il residuo del fit (+ il suo riposo di transizione).
    endu = int(p.get("endurance_min") or 0)
    if endu >= _ENDU_MIN_FIT:
        tot += sec(endu) + rest
    return max(1, int(round(tot / 60)))

def _bike_desc_power(profilo, durata_min, p, cad):
    """Descrizione di una BikeCross OUTDOOR in %FTP bici — unita' unica Power (vedi
    REGOLA UNITA' TARGET UNICA: un workout esportato al watch ammette un solo tipo di
    target). Le percentuali sono lo schema Coggan (COGGAN_BIKE_POWER_ZONES) scritte
    esplicite: il server le risolve sull'FTP dello sport dell'evento, cioe' quella BICI.
    Le durate di default sono identiche a quelle del gemello indoor a FC, cosi' la
    stessa sessione non cambia moving_time solo perche' cambia l'unita' di misura.
    Cue specifici della strada (non servono al chiuso): su una salita i watt schizzano
    e su una discesa crollano — la prescrizione e' la MEDIA del tratto, e il tratto va
    scelto senza stop (Allen & Coggan; Friel, Cyclist's Training Bible)."""
    z1   = "45-55% Power"     # recupero attivo (Coggan Z1)
    z2   = "56-75% Power"     # endurance
    z4   = "91-100% Power"    # soglia: meta' bassa della Z4, sostenibile in ripetute
    z5   = "106-118% Power"   # VO2max
    z1z2 = "45-75% Power"     # riscaldamento: range unico valido, non due zone concatenate

    if profilo == "soglia":
        wu   = p.get("wu_min", 12); cd = p.get("cd_min", 8)
        reps = p.get("reps", 3); rep = p.get("rep_min", 10); rec = p.get("rec_min", 3)
        return (f"- {wu}m {z1z2} {STEP_INT_WU} (cadenza {cad}rpm)\n\n{reps}x\n"
                f"- {rep}m {z4} {STEP_INT_WORK} (cadenza {cad}rpm, tratto senza stop: "
                f"pianeggiante o falsopiano costante, watt medi del blocco)\n"
                f"- {rec}m {z2} {STEP_INT_WORK} (recupero attivo, continua a pedalare)\n\n"
                f"- {cd}m {z1} {STEP_INT_CD}\n")

    if profilo == "vo2max":
        wu   = p.get("wu_min", 12); cd = p.get("cd_min", 8)
        reps = p.get("reps", 6); rep = p.get("rep_min", 2); rec = p.get("rec_min", 2)
        return (f"- {wu}m {z1z2} {STEP_INT_WU} (cadenza {cad}rpm)\n\n{reps}x\n"
                f"- {rep}m {z5} {STEP_INT_WORK} (cadenza {cad}rpm, salita costante o "
                f"pianura con vento a favore: parti gia' lanciato)\n"
                f"- {rec}m {z2} {STEP_INT_WORK} (recupero attivo)\n\n"
                f"- {cd}m {z1} {STEP_INT_CD}\n")

    # profilo "recovery" (default) = fondo/lungo aerobico su strada, il pane del weekend.
    wu  = p.get("wu_min", 8); cd = p.get("cd_min", 5)
    mid = max(15, durata_min - wu - cd)
    return (f"- {wu}m {z1} {STEP_INT_WU} (cadenza {cad}rpm)\n\n"
            f"- {mid}m {z2} {STEP_INT_WORK} (cadenza {cad}rpm; in salita non superare "
            f"85% Power, in discesa lascia andare senza inseguire i watt)\n\n"
            f"- {cd}m {z1} {STEP_INT_CD}\n")

def build_run_description(sessione_tipo, durata_min, lthr=165, params=None, bike_lthr=None, swim_lthr=None):
    # BUG FIX: qui i params erano quelli GREZZI dell'LLM, non quelli fusi con la tabella
    # e adattati alla durata. Le chiavi calcolate da merge_session_defaults (endurance_min)
    # erano quindi invisibili agli iniettori: il blocco aerobico non sarebbe mai comparso.
    p = merge_session_defaults(sessione_tipo, params, durata_min)
    body = _build_run_description_base(sessione_tipo, durata_min, lthr, params, bike_lthr, swim_lthr)
    if sessione_tipo in ("Strides", "Rest"):
        return body
    # Il nuoto scrive gia' il proprio blocco aerobico dentro il branch (in metri, con il
    # riposo di transizione): l'iniettore generico lo duplicherebbe in minuti.
    if sessione_tipo != "Swim":
        body = _inject_endurance_desc(body, p)
    return _inject_strides_desc(body, p)

def _build_run_description_base(sessione_tipo, durata_min, lthr=165, params=None, bike_lthr=None, swim_lthr=None):
    p         = merge_session_defaults(sessione_tipo, params, durata_min)
    wu_min    = p.get("wu_min", 5)
    cd_min    = p.get("cd_min", 5)
    wu_min, cd_min = sane_wu_cd(wu_min, cd_min, durata_min)
    corpo_min = max(10, durata_min - wu_min - cd_min)

    if sessione_tipo == "Threshold":
        wu   = p.get("wu_min", 15); cd = p.get("cd_min", 10)
        reps = p.get("reps", 3); rep = p.get("rep_min", 10); rec = p.get("rec_min", 3)
        pct  = p.get("intensity_pct", 95)
        return (f"- {wu}m 75% {STEP_INT_WU}\n\n{reps}x\n"
                f"- {rep}m {pct}% {STEP_INT_WORK}\n- {rec}m 65% {STEP_INT_WORK}\n\n"
                f"- {cd}m 70% {STEP_INT_CD}\n")

    elif sessione_tipo == "Interval":
        wu   = p.get("wu_min", 15); cd = p.get("cd_min", 10)
        reps = p.get("reps", 6); rep = p.get("rep_min", 1); rec = p.get("rec_min", 1)
        pct  = p.get("intensity_pct", 110)
        return (f"- {wu}m 75% {STEP_INT_WU}\n\n{reps}x\n"
                f"- {rep}m {pct}% {STEP_INT_WORK}\n- {rec}m 60% {STEP_INT_WORK}\n\n"
                f"- {cd}m 70% {STEP_INT_CD}\n")

    elif sessione_tipo == "HillWork":
        wu   = p.get("wu_min", 15); cd = p.get("cd_min", 10)
        reps = p.get("reps", 8); rep = p.get("rep_min", 2); rec = p.get("rec_min", 2)
        return (f"- {wu}m Z1 HR {STEP_INT_WU}\n\n{reps}x\n"
                f"- {rep}m Z3 HR {STEP_INT_WORK} (salita)\n"
                f"- {rec}m Z1 HR {STEP_INT_REC} (discesa)\n\n"
                f"- {cd}m Z1 HR {STEP_INT_CD}\n")

    elif sessione_tipo == "Progressive":
        # Hudson/Fitzgerald: progressione a 3 blocchi, chiusura controllata vicino a soglia.
        # % Power (non HR): la FC ha latenza/drift e su una progressione vera serve un target
        # che risponda subito quando si cambia ritmo.
        wu  = p.get("wu_min", 10); cd = p.get("cd_min", 5)
        corpo = max(15, durata_min - wu - cd)
        t1 = corpo // 3; t2 = corpo // 3; t3 = corpo - t1 - t2
        pct1 = p.get("pct1", 72); pct2 = p.get("pct2", 85); pct3 = p.get("pct3", 95)
        return (f"- {wu}m 60-70% Power {STEP_INT_WU}\n\n"
                f"- {t1}m {pct1}% Power {STEP_INT_WORK}\n- {t2}m {pct2}% Power {STEP_INT_WORK}\n"
                f"- {t3}m {pct3}% Power {STEP_INT_WORK} (chiusura controllata)\n\n"
                f"- {cd}m 55-65% Power {STEP_INT_CD}\n")

    elif sessione_tipo == "Fartlek":
        # Daniels: surge liberi non strutturati, recupero a float (non completo).
        # % Power sui surge (non HR): nei surge brevi la FC non fa in tempo a salire ed e' inutile.
        wu   = p.get("wu_min", 12); cd = p.get("cd_min", 8)
        reps = p.get("reps", 10); rep = p.get("rep_min", 1); rec = p.get("rec_min", 1)
        pct  = p.get("intensity_pct", 105)
        return (f"- {wu}m 60-70% Power {STEP_INT_WU}\n\n{reps}x\n"
                f"- {rep}m {pct}% Power {STEP_INT_WORK} (surge libero)\n"
                f"- {rec}m 70-75% Power {STEP_INT_WORK} (float, non recupero pieno)\n\n"
                f"- {cd}m 55-65% Power {STEP_INT_CD}\n")

    elif sessione_tipo == "RaceSimulation":
        # Simulazione 100km: D+ specifico + blocco a ritmo gara, gestione idratazione/nutrizione.
        # Se la gara ha un obiettivo di tempo: passo/km letterale (la FC va a deriva con
        # calore/fatica/D+ e rischia di far correre la prova a un ritmo diverso da quello obiettivo).
        # Se la gara NON ha un obiettivo di tempo (tipico trail/ultra): %Power Stryd —
        # su D+ il pace e' privo di senso e in 2-4h la FC va a deriva cardiaca: la
        # potenza e' l'unica metrica di pacing stabile su salita/caldo/fatica (Vance
        # 'Run with Power'; van Dijk & van Megen). Prima era HR. Description-based
        # (struttura troppo specifica per il .zwo): unita' unica Power rispettata.
        wu  = p.get("wu_min", 10); cd = p.get("cd_min", 10)
        corpo = max(30, durata_min - wu - cd)
        race_min = p.get("rep_min", corpo // 3)
        base_min = corpo - race_min
        pace_str = p.get("pace_obiettivo_str")
        if pace_str:
            return (f"- {wu}m Z1 Pace {STEP_INT_WU}\n\n"
                    f"- {base_min}m Z2 Pace {STEP_INT_WORK} (D+ specifico)\n"
                    f"- ritmo gara {race_min}m {pace_str} {STEP_INT_WORK} "
                    f"(idratazione/nutrizione come in gara)\n\n"
                    f"- {cd}m Z1 Pace {STEP_INT_CD}\n")
        if p.get("ha_obiettivo_tempo", True):
            return (f"- {wu}m Z1 Pace {STEP_INT_WU}\n\n"
                    f"- {base_min}m Z2 Pace {STEP_INT_WORK} (D+ specifico)\n"
                    f"- ritmo gara {race_min}m Z3 Pace {STEP_INT_WORK} "
                    f"(idratazione/nutrizione come in gara)\n\n"
                    f"- {cd}m Z1 Pace {STEP_INT_CD}\n")
        return (f"- {wu}m 60-70% Power {STEP_INT_WU}\n\n"
                f"- {base_min}m 70-75% Power {STEP_INT_WORK} (D+ specifico)\n"
                f"- ritmo gara {race_min}m 78-85% Power {STEP_INT_WORK} "
                f"(idratazione/nutrizione come in gara)\n\n"
                f"- {cd}m 60% Power {STEP_INT_CD}\n")

    elif sessione_tipo == "ShakeOut":
        # Vigilia gara: fondo breve + allunghi reali, niente accumulo di fatica.
        reps  = p.get("reps", 4)
        corpo = max(10, durata_min - 5)
        return (f"- {corpo}m Z1 HR {STEP_INT_WORK}\n\n{reps}x\n"
                f"- allungo 20s Z3 HR {STEP_INT_WORK}\n"
                f"- recupero 45s Z1 HR {STEP_INT_REC}\n")

    elif sessione_tipo == "Repetition":
        # Daniels R-pace: reps brevi quasi massimali, recupero completo. Economia/potenza, non lattato.
        wu = p.get("wu_min", 15); cd = p.get("cd_min", 10)
        reps = p.get("reps", 10); rep_sec = p.get("rep_sec", 35); rec = p.get("rec_min", 2)
        pct  = p.get("intensity_pct", 120)
        return (f"- {wu}m 60-70% Power {STEP_INT_WU}\n\n{reps}x\n"
                f"- R-pace {rep_sec}s {pct}% Power {STEP_INT_WORK}\n"
                f"- recupero {rec}m 50-60% Power {STEP_INT_REC}\n\n"
                f"- {cd}m 55-65% Power {STEP_INT_CD}\n")

    elif sessione_tipo == "HillSprints":
        wu = p.get("wu_min", 12); cd = p.get("cd_min", 8)
        reps = p.get("reps", 8); rep_sec = p.get("rep_sec", 10); rec = p.get("rec_min", 2)
        corpo = (f"- {wu}m 60-70% {STEP_INT_WU}\n\n{reps}x\n"
                 f"- sprint salita {rep_sec}s 125% {STEP_INT_WORK} (6-8% pend.)\n"
                 f"- recupero {rec}m 50% {STEP_INT_WORK} (ridiscesa a piedi)\n")
        return corpo + f"\n- {cd}m 70-60% {STEP_INT_CD}\n"

    elif sessione_tipo == "Strides":
        # Corri Indipendente: 80-100m @5K-MP dopo fondo facile, tensione neuromuscolare, zero fatica.
        # Tutto in unita' HR (vedi REGOLA UNITA' TARGET UNICA): il vecchio "{pct}%" bare
        # diventava %Power e faceva sparire i target HR del fondo sul watch.
        reps  = p.get("reps", 6)
        corpo = max(15, durata_min - 5)
        # BUG FIX (27/09/2026): il fondo Z2 era "60-89% LTHR" = 103-152 bpm con LTHR 171:
        # nessun allarme in Z1, allarme FC alta da 153 ancora in Z2. Ora "Z2 HR" = 146-160.
        return (f"- {corpo}m Z2 HR {STEP_INT_WORK}\n\n{reps}x\n"
                f"- allungo {p.get('rep_sec',20)}s Z3 HR {STEP_INT_WORK} (svelto e controllato, "
                f"RPE 8-9 — ignora la FC, vai a sensazione ~ritmo 5K)\n"
                f"- recupero {p.get('rec_sec',60)}s Z1 HR {STEP_INT_REC}\n")

    elif sessione_tipo == "MPRun":
        # Ritmo gara continuo (marathon/ultra pace), senza interruzioni.
        # Passo/km letterale quando c'e' un obiettivo di tempo (Run Elite, Snow): l'obiettivo
        # e' tenere il ritmo reale di gara, non un'intensita' relativa. Altrimenti %Power
        # (la FC sale nel tempo per deriva cardiaca e farebbe rallentare inutilmente).
        wu = p.get("wu_min", 10); cd = p.get("cd_min", 10)
        corpo = max(20, durata_min - wu - cd)
        pace_str = p.get("pace_obiettivo_str")
        if pace_str:
            return (f"- {wu}m Z1 Pace {STEP_INT_WU}\n\n"
                    f"- ritmo gara {corpo}m {pace_str} {STEP_INT_WORK}\n\n"
                    f"- {cd}m Z1 Pace {STEP_INT_CD}\n")
        pct = p.get("intensity_pct", 80)
        # UNITA' UNICA: senza obiettivo tempo il corpo e' in %Power, quindi anche
        # riscaldamento/defaticamento (prima erano in Pace: target misti, e su TrailRun —
        # dove il .zwo non viene allegato — finivano cosi' sull'orologio).
        return (f"- {wu}m 60-70% Power {STEP_INT_WU}\n\n"
                f"- ritmo gara {corpo}m {pct}% Power {STEP_INT_WORK}\n\n"
                f"- {cd}m 55-65% Power {STEP_INT_CD}\n")

    elif sessione_tipo == "Alternations":
        # Alternanze MP / MP+rec — ponte tra fondo e ritmo gara (Corri Indipendente).
        # Fasi a ritmo gara in passo/km letterale se c'e' un obiettivo di tempo
        # (precisione che la FC non da', sempre in ritardo sul cambio ritmo).
        # SENZA obiettivo di tempo: %Power Stryd con file .zwo allegato (in POWER_TYPES):
        # sugli on/off da 1-3min la FC e' in ritardo strutturale a OGNI cambio di ritmo,
        # la potenza commuta istantanea (Vance, 'Run with Power'). Prima era tutta in HR.
        # REGOLA UNITA' TARGET UNICA: con obiettivo di tempo tutto in Pace
        # (ritmo gara letterale + zone Pace su riscaldamento/recuperi, niente .zwo:
        # vedi usa_zwo_power); senza obiettivo il target reale e' il file .zwo
        # (tutto %FTP/CP) che ha priorita' sulla description nell'export — la
        # description resta informativa, stesso schema di MPRun/TestRace.
        wu = p.get("wu_min", 10); cd = p.get("cd_min", 10)
        reps = p.get("reps", 8); rep = p.get("rep_min", 1)
        pace_str = p.get("pace_obiettivo_str")
        if pace_str:
            return (f"- {wu}m Z1 Pace {STEP_INT_WU}\n\n{reps}x\n"
                    f"- ritmo gara {rep}m {pace_str} {STEP_INT_WORK}\n"
                    f"- recupero attivo {rep}m Z2 Pace {STEP_INT_WORK}\n\n"
                    f"- {cd}m Z1 Pace {STEP_INT_CD}\n")
        pct = p.get("intensity_pct", 82)
        return (f"- {wu}m 60-70% Power {STEP_INT_WU}\n\n{reps}x\n"
                f"- ritmo gara {rep}m {pct}% Power {STEP_INT_WORK}\n"
                f"- recupero attivo {rep}m 70% Power {STEP_INT_WORK} (float)\n\n"
                f"- {cd}m 55-65% Power {STEP_INT_CD}\n")

    elif sessione_tipo == "DownhillRepeats":
        # Discesa tecnica controllata: cadenza alta, no overstriding, prevenzione infortuni (Daniels).
        # Cadenza come target primario sulle discese (non HR/Power: su pendenza variabile non sono
        # affidabili). Pace ampia solo come contesto generale, la cadenza e' la metrica che conta.
        wu = p.get("wu_min", 12); cd = p.get("cd_min", 8)
        reps = p.get("reps", 6); rep = p.get("rep_min", 2); rec = p.get("rec_min", 2)
        cad = p.get("cadence_rpm", 180)
        # UNITA' VINCENTE = CADENCE (suggerimento Simone): lo scopo della sessione
        # e' l'agilita' in discesa, cioe' velocita' delle gambe/turnover — non il
        # ritmo (variabile con la pendenza) ne' la FC. Il target rpm sta SOLO
        # sulle discese; riscaldamento/risalite/defaticamento restano step a durata
        # senza target ne' token di zona (Z_/%/km), per non reintrodurre unita' miste.
        return (f"- {wu}m {STEP_INT_WU} riscaldamento facile, corsa rilassata\n\n{reps}x\n"
                f"- discesa tecnica {rep}m {cad}rpm Cadence {STEP_INT_WORK} (appoggi rapidi "
                f"e corti, no overstriding, busto avanti)\n"
                f"- risalita {rec}m {STEP_INT_REC} corsetta molto facile o camminata "
                f"(recupero pieno)\n\n"
                f"- {cd}m {STEP_INT_CD} defaticamento sciolto\n")

    elif sessione_tipo == "TestRace":
        # Gara test 10K/HM a meta blocco: diagnostica reale, una volta nel ciclo (Corri Indipendente).
        # % Power come target: le gare su strada hanno un obiettivo di tempo/ritmo, la FC si guarda
        # ma resta secondaria in una sessione che serve a misurare la prestazione reale.
        wu = p.get("wu_min", 15); cd = p.get("cd_min", 10)
        corpo = max(20, durata_min - wu - cd)
        pct = p.get("intensity_pct", 92)
        return (f"- {wu}m 60-75% Power {STEP_INT_WU}\n\n"
                f"- TEST 10K/HM {corpo}m {pct}% Power {STEP_INT_WORK} (sforzo reale)\n\n"
                f"- {cd}m 55-65% Power {STEP_INT_CD}\n")

    elif sessione_tipo == "Recovery":
        return f"- {durata_min}m Z1 HR {STEP_INT_WORK}\n"

    elif sessione_tipo == "BikeCross":
        # Tre profili, stesso meccanismo di fondo (non-weight-bearing, scarica
        # l'impatto al suolo) ma scopo fisiologico diverso — vedi rationale
        # completo in FRIEL_BIKE_HR_ZONES e nel prompt ask_claude_plan (regola 12):
        # - "recovery" (default): scarico meccanico puro, sostituisce Recovery/Easy.
        # - "soglia":   replica la struttura di un Threshold corsa (blocchi medi a
        #               ridosso della soglia, recupero incompleto) — sostituisce
        #               UNA qualita corsa solo quando questa e' specificamente
        #               controindicata (segnale acuto, non solo carico cumulato).
        # - "vo2max":   replica la struttura di un Interval corsa (reps brevi alte,
        #               recupero attivo) — stesso uso eccezionale di "soglia".
        # Cadenza alta (85-95rpm) in tutti i profili per restare vicino al pattern
        # motorio della corsa anche nei profili piu intensi.
        # IMPORTANTE: Intervals.icu NON accetta bpm assoluti negli step (es. "<124bpm" o
        # "125-138bpm") -> errore di parsing nel Workout Builder. La sintassi valida per
        # i range di FC e' "a-b% HR" (= percentuale della LTHR, calcolata da Intervals.icu
        # in automatico sullo sport dell'attivita' — quindi sulla LTHR Bici configurata
        # per l'atleta, non quella Corsa: vedi get_bike_lthr()). Niente token "Z1 HR"/"Z2 HR"
        # nudi: userebbero lo schema zone "Bici" di Intervals.icu che, se la LTHR Bici non e'
        # mai stata testata/configurata, e' spesso solo una copia automatica della LTHR Corsa.
        # I cutoff sotto sono lo schema Friel (vedi FRIEL_BIKE_HR_ZONES), espressi qui come
        # percentuale esplicita cosi' il numero e' sempre coerente indipendentemente da come
        # e' configurata la LTHR Bici su Intervals.icu.
        cad     = p.get("cadence_rpm", 90)
        profilo = p.get("profilo", "recovery")
        # BIVIO OUTDOOR/INDOOR (vedi bike_ambiente): su strada il target e' la POTENZA
        # — la FC non e' utilizzabile con salite, discese e vento — mentre al chiuso
        # resta la FC, perche' lo spinning di palestra non fornisce watt.
        if bike_usa_potenza(p):
            return _bike_desc_power(profilo, durata_min, p, cad)
        # BUG FIX (27/09/2026 — "Bike Cross Z2 aerobica" del 30/09): gli step Z1/Z2 erano
        # le % Friel (0-80/81-89%), ma Intervals.icu e l'orologio usano le zone Ride
        # configurate (Z2 69-83%). Con LTHR 153 la "Z2" chiedeva 123-136 bpm, 78% in Z3.
        # Ora token di zona come nella corsa: Z2 = 105-127 bpm, la Z2 del grafico.
        # I blocchi soglia/VO2max restano range % espliciti, voluti.
        z1, z2  = "Z1 HR", "Z2 HR"
        z1z2    = "Z1-Z2 HR"  # riscaldamento: range unico valido, non concatenazione di z1+z2

        if profilo == "soglia":
            # Equivalente bici di Threshold: blocchi medi (8-12min) a ridosso
            # soglia (Z4, 94-99% LTHR bici) con recupero incompleto in Z2 — non
            # Z1 come nelle reps corsa, perche' su bici l'assenza di impatto
            # permette di tenere un recupero attivo piu sostenuto senza rischio.
            wu   = p.get("wu_min", 12); cd = p.get("cd_min", 8)
            reps = p.get("reps", 3); rep = p.get("rep_min", 10); rec = p.get("rec_min", 3)
            z4   = "94-99% LTHR"
            return (f"- {wu}m {z1z2} {STEP_INT_WU} (cadenza {cad}rpm)\n\n{reps}x\n"
                    f"- {rep}m {z4} {STEP_INT_WORK} (cadenza {cad}rpm)\n"
                    f"- {rec}m {z2} {STEP_INT_WORK} (recupero attivo)\n\n"
                    f"- {cd}m {z1} {STEP_INT_CD}\n")

        if profilo == "vo2max":
            # Equivalente bici di Interval: reps brevi (1-3min) in Z5a/Z5b
            # (100-106% LTHR bici), recupero attivo in Z2 (non Z1: su bici il
            # recupero completo non serve a scaricare impatto come in corsa,
            # e un recupero troppo blando fa perdere lo stimolo cardiovascolare).
            wu   = p.get("wu_min", 12); cd = p.get("cd_min", 8)
            reps = p.get("reps", 6); rep = p.get("rep_min", 2); rec = p.get("rec_min", 2)
            z5   = "100-106% LTHR"
            return (f"- {wu}m {z1z2} {STEP_INT_WU} (cadenza {cad}rpm)\n\n{reps}x\n"
                    f"- {rep}m {z5} {STEP_INT_WORK} (cadenza {cad}rpm)\n"
                    f"- {rec}m {z2} {STEP_INT_WORK} (recupero attivo)\n\n"
                    f"- {cd}m {z1} {STEP_INT_CD}\n")

        # profilo "recovery" (default): scarico meccanico puro, nessun blocco a
        # soglia — e' un giorno di scarico, non un workout di bici.
        wu  = p.get("wu_min", 8); cd = p.get("cd_min", 5)
        mid = max(15, durata_min - wu - cd)
        return (f"- {wu}m {z1} {STEP_INT_WU} (cadenza {cad}rpm)\n\n"
                f"- {mid}m {z2} {STEP_INT_WORK} (cadenza {cad}rpm, spinta leggera)\n\n"
                f"- {cd}m {z1} {STEP_INT_CD}\n")

    elif sessione_tipo == "Swim":
        # Stesso framework %LTHR di BikeCross (FRIEL_BIKE_HR_ZONES, valido per
        # qualunque sport: cambia solo la LTHR di riferimento, qui get_swim_lthr).
        # Quattro profili via params.profilo, building block standard 80/20
        # Triathlon (Fitzgerald) + Total Immersion:
        # "tecnica" (default): drills tecnici, intensita' bassa — priorita'
        #   costante in tutte le fasi (Total Immersion: per la maggior parte dei
        #   triatleti la tecnica e' il fattore limitante, non il condizionamento).
        # "endurance": nuotata continua Z2, costruisce volume.
        # "soglia": ripetute medie vicine a soglia (qui in minuti, non metri, per
        #   restare nel formato testuale del resto dello script) — equivalente
        #   nuoto di Threshold/BikeCross "soglia".
        # "velocita": ripetute brevi alte, recupero completo — equivalente nuoto
        #   di Interval/BikeCross "vo2max".
        profilo = p.get("profilo", "tecnica")
        # MODIFICA (25/09/2026 — richiesta di Simone): target in % Pace sulla CSS (threshold
        # pace del nuoto su Intervals.icu = 100%), come nel suo workout "Nuoto_Intervalli":
        # il passo si legge in vasca, la FC in acqua no. Fasce = zone pace Intervals.icu
        # (Z1 <77.5, Z2 77.5-87.7, Z4 94.3-100, Z5a 100-103.4). Conseguenza osservabile:
        # sull'orologio ogni step nuotato ha un passo/100m, non un range di FC.
        z1, z2  = "68-77% Pace", "78-87% Pace"
        z1z2    = "68-85% Pace"  # riscaldamento: range unico valido, non concatenazione di z1+z2
        # BUG FIX Garmin "Riposo"/nessun avanzamento: TUTTI gli step nuotati sono a
        # DISTANZA (metri, token "mtr"), il tempo resta solo sui recuperi. Vedi il
        # blocco NUOTO: STEP IN METRI sopra per il perche'.
        # Passo di riferimento MISURATO sull'atleta (CSS configurata o nuotate reali):
        # e' cio' che rende le distanze degli step coerenti con la durata pianificata
        # senza doverle ricalcolare a mano in vasca. Vedi get_swim_pace_ref().
        pace = p.get("pace_100m_sec") or get_swim_pace_sec_100m()
        pool = p.get("pool_len_m", SWIM_POOL_LEN_M)
        rest = p.get("rest_sec", SWIM_REST_SEC)

        def mtr(minuti):
            return _swim_m(minuti, pace, pool)

        # BUG FIX (28/08/2026): le ripetute di soglia/velocita' erano dimensionate col passo
        # EASY (CSS +15%): 4' a CSS coprono ~13% di metri in piu' di 4' a passo facile, quindi
        # ogni ripetuta scritta era piu' corta di quanto la durata pianificata volesse. Le
        # ripetute usano il passo della loro intensita': CSS per la soglia, ~CSS -5% per le
        # ripetute brevi veloci (Swim Smooth: 100-200 a ritmo "CSS-2/3s per 100").
        pace_css = pace / SWIM_CSS_TO_EASY

        def mtr_rep(minuti, fattore=1.0):
            return _swim_m(minuti, pace_css * fattore, pool)

        # Riposo di transizione fra due step NUOTATI adiacenti: senza di lui il tasto lap
        # non ha uno step di riposo dove andare e su Garmin la sessione si pianta (vedi
        # "DUE STEP NUOTATI NON POSSONO ESSERE ADIACENTI").
        pausa = f"- pausa al muro {rest}s {STEP_INT_REST}\n"
        # MODIFICA (26/09/2026 — richiesta di Simone): il defaticamento e' "Press lap": si
        # chiude solo col tasto lap, cosi' a fine allenamento si puo' continuare a nuotare
        # senza che il workout finisca da solo a distanza raggiunta. Vale su Intervals.icu e,
        # tradotto in step "lap.button", sul nuoto caricato su Garmin.

        # Blocco aerobico che assorbe il tempo residuo del fit (vedi _fit_swim_durata):
        # va DOPO le ripetute e PRIMA del defaticamento, con il proprio riposo di
        # transizione perche' due step nuotati non possono essere adiacenti.
        _endu = int(p.get("endurance_min") or 0)
        # MODIFICA (25/09/2026): in acque libere anche questa nuotata continua e' a tempo.
        _endu_d = f"{_endu}m" if p.get("acque_libere") else f"{mtr(_endu)}mtr"
        endu_blk = (f"{pausa}\n- nuotata continua {_endu_d} {z2} {STEP_INT_WORK}\n"
                    if _endu >= _ENDU_MIN_FIT else "")

        # MODIFICA (27/09/2026): seduta fissa di libreria per fase (vedi SEDUTE_NUOTO).
        if p.get("seduta") in SEDUTE_NUOTO_ID:
            return _swim_lib_desc(_seduta_tarata(SEDUTE_NUOTO_ID[p["seduta"]], pace, pool, rest),
                                  rest, endu_blk, pausa, int(p.get("cd_extra_m") or 0))

        if profilo == "soglia":
            wu = p.get("wu_min", 10); cd = p.get("cd_min", 5)
            reps = p.get("reps", 5); rep = p.get("rep_min", 4); rec = p.get("rec_min", 1)
            z4 = "95-100% Pace"   # MODIFICA (25/09/2026): CSS, vedi z1/z2 sopra
            return (f"- riscaldamento e drills {mtr(wu)}mtr {z1z2} {STEP_INT_WU}\n"
                    f"{pausa}\n{reps}x\n"
                    f"- ripetuta {mtr_rep(rep)}mtr {z4} {STEP_INT_WORK}\n"
                    f"- recupero {round(rec*60)}s {STEP_INT_REST}\n\n"
                    f"{endu_blk}"
                    f"{pausa}"
                    f"- Press lap defaticamento {mtr(cd)}mtr {z1} {STEP_INT_CD}\n")

        if profilo == "velocita":
            wu = p.get("wu_min", 10); cd = p.get("cd_min", 5)
            reps = p.get("reps", 8); rep = p.get("rep_min", 1); rec = p.get("rec_min", 1)
            z5 = "100-104% Pace"  # MODIFICA (25/09/2026): Z5a pace, vedi z1/z2 sopra
            return (f"- riscaldamento e drills {mtr(wu)}mtr {z1z2} {STEP_INT_WU}\n"
                    f"{pausa}\n{reps}x\n"
                    f"- ripetuta veloce {mtr_rep(rep, 0.95)}mtr {z5} {STEP_INT_WORK}\n"
                    f"- recupero completo {round(rec*60)}s {STEP_INT_REST}\n\n"
                    f"{endu_blk}"
                    f"{pausa}"
                    f"- Press lap defaticamento {mtr(cd)}mtr {z1} {STEP_INT_CD}\n")

        if profilo == "endurance":
            wu = p.get("wu_min", 8); cd = p.get("cd_min", 5)
            mid = _swim_mid_endurance_min(durata_min, wu, cd, rest)
            # MODIFICA (25/09/2026 — decisione di Simone): in ACQUE LIBERE la nuotata
            # continua e' a tempo (niente vasche da contare, il GPS in acqua misura male la
            # distanza). In vasca resta a distanza: vedi NUOTO: STEP IN METRI.
            continua = f"{round(mid)}m" if p.get("acque_libere") else f"{mtr(mid)}mtr"
            return (f"- riscaldamento e drills {mtr(wu)}mtr {z1} {STEP_INT_WU}\n"
                    f"{pausa}\n"
                    f"- nuotata continua {continua} {z2} {STEP_INT_WORK}\n"
                    f"{pausa}\n"
                    f"- Press lap defaticamento {mtr(cd)}mtr {z1} {STEP_INT_CD}\n")

        # "tecnica" (default): drills prioritari, intensita' bassa tutta la sessione.
        # UPDATE: UNO STEP PER OGNI DRILL invece di un unico blocco "drills tecnica" con
        # i nomi relegati in una nota finale. In Intervals.icu il testo che precede la
        # distanza diventa il cue dello step ed e' quello che il watch mostra durante
        # l'esecuzione: mettendoci il nome del drill la sessione si segue in ordine,
        # sapendo sempre quale esercizio si sta eseguendo. Ogni drill e' un blocco
        # ripetuto (ripetuta a distanza + recupero a tempo), cosi' ogni step ha una
        # condizione di fine valida e il watch avanza da solo.
        wu, cd, drills, reps, rep_m, rep_sec = _swim_tecnica_struttura(
            durata_min, p, pace, pool, rest)
        blocchi = ""
        for nome, focus in drills:
            nota = f" ({focus})" if focus else ""
            blocchi += (f"{reps}x\n- {_swim_cue(nome)} {rep_m}mtr {z1} {STEP_INT_WORK}{nota}\n"
                        f"- recupero {rest}s {STEP_INT_REST}\n\n")
        return (f"- riscaldamento {mtr(wu)}mtr {z1} {STEP_INT_WU}\n"
                f"{pausa}\n"
                f"{blocchi}"
                f"{endu_blk}"
                f"{pausa}"
                f"- Press lap defaticamento {mtr(cd)}mtr {z1} {STEP_INT_CD}\n")

    elif sessione_tipo == "Brick":
        # Transizione bici->corsa: il blocco piu specifico per l'adattamento
        # neuromuscolare alle "gambe pesanti" post-bici (Be Iron Fit, transition
        # workouts; 80/20 Triathlon cap.4: ricorrente nelle settimane
        # Specifico/Picco di Half e Full).
        # NON e' piu' questa la descrizione che finisce su Intervals.icu: il brick
        # viene creato come DUE eventi separati (vedi create_brick_workout), perche'
        # in un evento unico type="Ride" anche gli step di corsa prendevano zone e
        # LTHR della bici. Qui resta solo la vista testuale unificata (log/debug),
        # generata dalla stessa unica fonte dei due eventi reali.
        bike_desc, run_desc = build_brick_descrizioni(durata_min, params)
        return f"{bike_desc}\n- transizione rapida (<3m)\n\n{run_desc}"

    elif sessione_tipo == "Easy":
        wu  = p.get("wu_min", 5); cd = p.get("cd_min", 5)
        mid = max(10, durata_min - wu - cd)
        return (f"- {wu}m Z1 HR {STEP_INT_WU}\n\n- {mid}m Z2 HR {STEP_INT_WORK}\n\n"
                f"- {cd}m Z1 HR {STEP_INT_CD}\n")

    elif sessione_tipo == "Long":
        # BUG FIX: per "Long" wu_min/cd_min/rep_min dall'LLM vengono IGNORATI
        # come minuti assoluti -- un fondo lungo ha warmup/cooldown fissi e
        # brevi per definizione (non e' una sessione a intervalli dove questi
        # parametri hanno senso come input liberi). Caso reale che ha rotto
        # la sessione: wu_min=120, cd_min=30, rep_min=10 su durata_min=150 ->
        # corpo Z2 azzerato (120m Z1 / 0m Z2 / 10m Z3 / 30m Z1 su Intervals.icu).
        wu  = 5 if durata_min < 180 else 10
        cd  = 5
        mid = max(10, durata_min - wu - cd)
        # chiusura Z3 come PERCENTUALE del corpo (15-30%, default 20%), mai
        # come minuti assoluti dell'LLM: cosi' non puo' mai eccedere/azzerare
        # la base Z2, qualunque sia il valore proposto nel piano.
        # z3_pct=0 e' ammesso (Base/Scarico/Recupero: il lungo e' TUTTO Z2 — Fitzgerald 80/20,
        # Seiler): una chiusura Z3 obbligatoria su ogni lungo trasformava la seduta piu' lunga
        # della settimana in una seduta moderata e rompeva l'80/20 per costruzione. Il default
        # per fase lo mette il main (long_z3_pct_default); qui si rispetta cio' che arriva.
        pct_z3   = min(0.30, max(0.0, (p.get("z3_pct", 20) or 0) / 100))
        mp_min   = 0 if pct_z3 < 0.05 else max(10, round(mid * pct_z3))
        base_min = mid - mp_min
        pace_str = p.get("pace_obiettivo_str")
        # REGOLA UNITA' TARGET UNICA: unita' HR per tutto il long, il passo obiettivo
        # come nota NON parsabile ("4:45 al km", mai "4:45/km": il parser di
        # Intervals.icu lo leggerebbe come secondo target Pace, ricreando il mix).
        mp_target = (f"Z3 HR (ritmo gara ~{pace_str.replace('/km', ' al km')})"
                     if pace_str else "Z3 HR")
        if not mp_min:
            return (f"- {wu}m Z1 HR {STEP_INT_WU}\n\n- {base_min}m Z2 HR {STEP_INT_WORK} "
                    f"(tutto aerobico, nessuna chiusura veloce)\n\n- {cd}m Z1 HR {STEP_INT_CD}\n")
        return (f"- {wu}m Z1 HR {STEP_INT_WU}\n\n- {base_min}m Z2 HR {STEP_INT_WORK}\n\n"
                f"- {mp_min}m {mp_target} {STEP_INT_WORK}\n\n- {cd}m Z1 HR {STEP_INT_CD}\n")

    elif sessione_tipo == "BackToBack":
        # Secondo allenamento del weekend doppio: tenuta a ritmo su gambe stanche.
        wu  = p.get("wu_min", 8); cd = p.get("cd_min", 5)
        corpo = max(20, durata_min - wu - cd)
        tempo_min = p.get("rep_min", max(10, corpo // 4))
        base_min  = corpo - tempo_min
        return (f"- {wu}m Z1 HR {STEP_INT_WU} (gambe pesanti, normale)\n\n"
                f"- {base_min}m Z2 HR {STEP_INT_WORK}\n"
                f"- {tempo_min}m Z3 HR {STEP_INT_WORK} (tenuta su gambe stanche)\n\n"
                f"- {cd}m Z1 HR {STEP_INT_CD}\n")

    else:
        wu  = p.get("wu_min", 5); cd = p.get("cd_min", 5)
        mid = max(10, durata_min - wu - cd)
        return (f"- {wu}m Z1 HR {STEP_INT_WU}\n\n- {mid}m Z2 HR {STEP_INT_WORK}\n\n"
                f"- {cd}m Z1 HR {STEP_INT_CD}\n")

ATTIVAZIONE_PRE = {
    "Threshold": "mobilita+skip", "Interval": "mobilita+skip", "HillWork": "mobilita+skip",
    "Progressive": "mobilita anche/caviglie", "Fartlek": "mobilita+skip",
    "RaceSimulation": "mobilita+balzi", "Repetition": "mobilita+skip",
    "HillSprints": "mobilita+skip", "MPRun": "mobilita+skip", "Alternations": "mobilita+skip",
    "TestRace": "mobilita+balzi", "DownhillRepeats": "mobilita caviglie/ginocchia",
    "BikeCross": "mobilita anca/ginocchio (no skip, no balzi)",
    "Swim": "mobilita spalle/caviglie",
    "Brick": "mobilita+skip (T1/T2 rapida)",
}

def append_warmup_cooldown(desc, sessione_tipo):
    """Aggiunge le note pre/post attivazione SULLE righe '- ...' esistenti (stessa sintassi
    delle sessioni originali: niente righe nuove senza trattino, niente unita' apostrofo/virgolette)."""
    lines = desc.rstrip("\n").split("\n")
    dash_idx = [i for i, l in enumerate(lines) if l.startswith("-")]
    if not dash_idx:
        return desc
    pre = ATTIVAZIONE_PRE.get(sessione_tipo)
    if pre:
        lines[dash_idx[0]] += f" (pre: {pre})"
    lines[dash_idx[-1]] += " (post: stretching)"
    return "\n".join(lines) + "\n"

def _strides_addon_zwo(p):
    if not p.get("strides_reps"):
        return ""
    s_reps = p.get("strides_reps"); s_sec = p.get("strides_sec", 20)
    s_pct  = p.get("strides_pct", 115) / 100; s_rec = p.get("strides_rec_sec", 60)
    return (f'    <IntervalsT Repeat="{s_reps}" OnDuration="{s_sec}" '
            f'OffDuration="{s_rec}" OnPower="{s_pct:.2f}" OffPower="0.60" />\n')

def _inject_strides_zwo(segs, p):
    addon = _strides_addon_zwo(p)
    if not addon:
        return segs
    idx = segs.rfind("<Cooldown")
    return segs + addon if idx == -1 else segs[:idx] + addon + segs[idx:]

def _endurance_addon_zwo(p):
    n = int(p.get("endurance_min") or 0)
    return "" if n < _ENDU_MIN_FIT else f'    <SteadyState Duration="{n*60}" Power="0.73" />\n'

def _inject_endurance_zwo(segs, p):
    addon = _endurance_addon_zwo(p)
    if not addon:
        return segs
    idx = segs.rfind("<Cooldown")
    return segs + addon if idx == -1 else segs[:idx] + addon + segs[idx:]

def build_run_zwo(sessione_tipo, durata_min, target_metrica, params=None):
    # Stesso BUG FIX di build_run_description: servono i params FUSI, non quelli grezzi.
    p = merge_session_defaults(sessione_tipo, params, durata_min)
    segs = _build_run_zwo_base(sessione_tipo, durata_min, target_metrica, params)
    segs = _inject_endurance_zwo(segs, p)
    if sessione_tipo != "Strides":
        segs = _inject_strides_zwo(segs, p)
    return f"""<?xml version="1.0" encoding="utf-8"?>
<workout_file>
    <author>Coach AI</author>
    <name>{sessione_tipo}</name>
    <description>{sessione_tipo}</description>
    <sportType>run</sportType>
    <workout>
    {segs}
    </workout>
</workout_file>"""

def _build_run_zwo_base(sessione_tipo, durata_min, target_metrica, params=None):
    p         = merge_session_defaults(sessione_tipo, params, durata_min)
    wu_sec    = p.get("wu_min", 5) * 60
    cd_sec    = p.get("cd_min", 5) * 60
    corpo_sec = max(600, durata_min*60 - wu_sec - cd_sec)

    if sessione_tipo == "Threshold":
        wu_sec  = p.get("wu_min", 15) * 60; cd_sec = p.get("cd_min", 10) * 60
        reps    = p.get("reps", 3); rep_sec = p.get("rep_min", 10) * 60
        rec_sec = p.get("rec_min", 3) * 60; pct = p.get("intensity_pct", 95) / 100
        segs = (f'<Warmup Duration="{wu_sec}" PowerLow="0.60" PowerHigh="0.75" />\n'
                f'    <IntervalsT Repeat="{reps}" OnDuration="{rep_sec}" '
                f'OffDuration="{rec_sec}" OnPower="{pct:.2f}" OffPower="0.60" />\n'
                f'    <Cooldown Duration="{cd_sec}" PowerLow="0.75" PowerHigh="0.60" />')

    elif sessione_tipo == "Interval":
        wu_sec  = p.get("wu_min", 15) * 60; cd_sec = p.get("cd_min", 10) * 60
        reps    = p.get("reps", 6); rep_sec = p.get("rep_min", 1) * 60
        rec_sec = p.get("rec_min", 1) * 60; pct = p.get("intensity_pct", 110) / 100
        segs = (f'<Warmup Duration="{wu_sec}" PowerLow="0.60" PowerHigh="0.75" />\n'
                f'    <IntervalsT Repeat="{reps}" OnDuration="{rep_sec}" '
                f'OffDuration="{rec_sec}" OnPower="{pct:.2f}" OffPower="0.55" />\n'
                f'    <Cooldown Duration="{cd_sec}" PowerLow="0.75" PowerHigh="0.60" />')

    elif sessione_tipo == "HillWork":
        wu_sec  = p.get("wu_min", 15) * 60; cd_sec = p.get("cd_min", 10) * 60
        reps    = p.get("reps", 8); rep_sec = p.get("rep_min", 2) * 60
        rec_sec = p.get("rec_min", 2) * 60
        segs = (f'<Warmup Duration="{wu_sec}" PowerLow="0.60" PowerHigh="0.75" />\n'
                f'    <IntervalsT Repeat="{reps}" OnDuration="{rep_sec}" '
                f'OffDuration="{rec_sec}" OnPower="0.90" OffPower="0.60" />\n'
                f'    <Cooldown Duration="{cd_sec}" PowerLow="0.75" PowerHigh="0.60" />')

    elif sessione_tipo == "Progressive":
        wu_sec    = p.get("wu_min", 10) * 60; cd_sec = p.get("cd_min", 5) * 60
        corpo_sec = max(900, durata_min*60 - wu_sec - cd_sec)
        t1 = corpo_sec // 3; t2 = corpo_sec // 3; t3 = corpo_sec - t1 - t2
        pct1 = p.get("pct1", 72)/100; pct2 = p.get("pct2", 85)/100; pct3 = p.get("pct3", 95)/100
        segs = (f'<Warmup Duration="{wu_sec}" PowerLow="0.60" PowerHigh="0.70" />\n'
                f'    <SteadyState Duration="{t1}" Power="{pct1:.2f}" />\n'
                f'    <SteadyState Duration="{t2}" Power="{pct2:.2f}" />\n'
                f'    <SteadyState Duration="{t3}" Power="{pct3:.2f}" />\n'
                f'    <Cooldown Duration="{cd_sec}" PowerLow="0.70" PowerHigh="0.60" />')

    elif sessione_tipo == "Fartlek":
        wu_sec  = p.get("wu_min", 12) * 60; cd_sec = p.get("cd_min", 8) * 60
        reps    = p.get("reps", 10); rep_sec = p.get("rep_min", 1) * 60
        rec_sec = p.get("rec_min", 1) * 60; pct = p.get("intensity_pct", 105) / 100
        segs = (f'<Warmup Duration="{wu_sec}" PowerLow="0.60" PowerHigh="0.75" />\n'
                f'    <IntervalsT Repeat="{reps}" OnDuration="{rep_sec}" '
                f'OffDuration="{rec_sec}" OnPower="{pct:.2f}" OffPower="0.70" />\n'
                f'    <Cooldown Duration="{cd_sec}" PowerLow="0.75" PowerHigh="0.60" />')

    elif sessione_tipo == "Repetition":
        wu_sec  = p.get("wu_min", 15) * 60; cd_sec = p.get("cd_min", 10) * 60
        reps    = p.get("reps", 10); rep_sec = p.get("rep_sec", 35)
        rec_sec = p.get("rec_min", 2) * 60; pct = p.get("intensity_pct", 120) / 100
        segs = (f'<Warmup Duration="{wu_sec}" PowerLow="0.60" PowerHigh="0.75" />\n'
                f'    <IntervalsT Repeat="{reps}" OnDuration="{rep_sec}" '
                f'OffDuration="{rec_sec}" OnPower="{pct:.2f}" OffPower="0.50" />\n'
                f'    <Cooldown Duration="{cd_sec}" PowerLow="0.75" PowerHigh="0.60" />')

    elif sessione_tipo == "HillSprints":
        wu_sec = p.get("wu_min", 12) * 60; cd_sec = p.get("cd_min", 8) * 60
        reps = p.get("reps", 8); rep_sec = p.get("rep_sec", 10); rec_sec = p.get("rec_min", 2) * 60
        segs = (f'<Warmup Duration="{wu_sec}" PowerLow="0.60" PowerHigh="0.70" />\n'
                f'    <IntervalsT Repeat="{reps}" OnDuration="{rep_sec}" '
                f'OffDuration="{rec_sec}" OnPower="1.25" OffPower="0.50" />\n'
                f'    <Cooldown Duration="{cd_sec}" PowerLow="0.70" PowerHigh="0.60" />')

    elif sessione_tipo == "MPRun":
        wu_sec    = p.get("wu_min", 10) * 60; cd_sec = p.get("cd_min", 10) * 60
        corpo_sec = max(900, durata_min*60 - wu_sec - cd_sec)
        pct = p.get("intensity_pct", 80)/100
        segs = (f'<Warmup Duration="{wu_sec}" PowerLow="0.60" PowerHigh="0.70" />\n'
                f'    <SteadyState Duration="{corpo_sec}" Power="{pct:.2f}" />\n'
                f'    <Cooldown Duration="{cd_sec}" PowerLow="0.70" PowerHigh="0.60" />')

    elif sessione_tipo == "TestRace":
        wu_sec    = p.get("wu_min", 15) * 60; cd_sec = p.get("cd_min", 10) * 60
        corpo_sec = max(900, durata_min*60 - wu_sec - cd_sec)
        pct = p.get("intensity_pct", 92)/100
        segs = (f'<Warmup Duration="{wu_sec}" PowerLow="0.60" PowerHigh="0.75" />\n'
                f'    <SteadyState Duration="{corpo_sec}" Power="{pct:.2f}" />\n'
                f'    <Cooldown Duration="{cd_sec}" PowerLow="0.70" PowerHigh="0.60" />')

    elif sessione_tipo == "Alternations":
        # Solo senza obiettivo di tempo (con pace_obiettivo_str il .zwo non viene
        # allegato: vedi usa_zwo_power). OffPower 0.70 = recupero attivo/float Z2,
        # coerente con la description ("recupero attivo", non recupero pieno).
        wu_sec  = p.get("wu_min", 10) * 60; cd_sec = p.get("cd_min", 10) * 60
        reps    = p.get("reps", 8); rep_sec = p.get("rep_min", 1) * 60
        pct     = p.get("intensity_pct", 82) / 100
        segs = (f'<Warmup Duration="{wu_sec}" PowerLow="0.60" PowerHigh="0.70" />\n'
                f'    <IntervalsT Repeat="{reps}" OnDuration="{rep_sec}" '
                f'OffDuration="{rep_sec}" OnPower="{pct:.2f}" OffPower="0.70" />\n'
                f'    <Cooldown Duration="{cd_sec}" PowerLow="0.70" PowerHigh="0.60" />')

    elif sessione_tipo == "Recovery":
        segs = f'<SteadyState Duration="{durata_min*60}" Power="0.65" />'

    elif sessione_tipo in ["Easy", "BackToBack"]:
        segs = (f'<Warmup Duration="{wu_sec}" PowerLow="0.60" PowerHigh="0.70" />\n'
                f'    <SteadyState Duration="{corpo_sec}" Power="0.75" />\n'
                f'    <Cooldown Duration="{cd_sec}" PowerLow="0.70" PowerHigh="0.60" />')

    elif sessione_tipo == "Long":
        mp_sec   = p.get("rep_min", 0) * 60 or max(600, corpo_sec // 5)
        base_sec = corpo_sec - mp_sec
        segs = (f'<Warmup Duration="{wu_sec}" PowerLow="0.60" PowerHigh="0.70" />\n'
                f'    <SteadyState Duration="{base_sec}" Power="0.73" />\n'
                f'    <SteadyState Duration="{mp_sec}" Power="0.82" />\n'
                f'    <Cooldown Duration="{cd_sec}" PowerLow="0.70" PowerHigh="0.60" />')

    else:
        segs = (f'<Warmup Duration="{wu_sec}" PowerLow="0.60" PowerHigh="0.70" />\n'
                f'    <SteadyState Duration="{corpo_sec}" Power="0.75" />\n'
                f'    <Cooldown Duration="{cd_sec}" PowerLow="0.70" PowerHigh="0.60" />')

    return segs

_GYM_CARICO = ("REGOLA DI CARICO: RIR 2-3 (RPE 7-8) su ogni serie, mai a cedimento; "
               "massima velocita' d'intenzione nella fase concentrica.")

SCHEDA_BASE = {
    "label": "Forza massimale 4x4 @80-85% 1RM, RIR 2",
    "durata": 45,
    "righe": [
        "WARM-UP (8 min)",
        "Foam Roller ITB e flessori d'anca: 60\" per lato",
        "Indian Sitting con rotazioni toraciche: 2 x 6 per lato",
        "Scorpions prono: 2 x 8 alternati",
        "BLOCCO A — forza massimale, 4 serie",
        "A1. Trap Bar Deadlift (o Squat profondo): 4 rip @80-85% 1RM, RIR 2, colonna neutra",
        "Recupero: 2'30\"",
        "BLOCCO B — unilaterale, 3 serie",
        "B1. Bulgarian Split Squat manubri: 6 rip x gamba RPE 7.5, discesa in 3\"",
        "Recupero: 90\"",
        "BLOCCO C — parte alta in superserie, 4 serie",
        "C1. Trazioni alla sbarra prone o neutre: 5 rip, depressione scapolare prima del pull",
        "C2. Chest Press: 5 rip RIR 2",
        "C3. Seated Row: 8 rip",
        "Recupero: 60\"",
        "BLOCCO D — stabilita' tronco e adduttori, 3 serie",
        "D1. Copenhagen Plank: 20\" per lato",
        "D2. Double Leg Lower: 12 rip, stop se la lombare si stacca",
        "D3. Tibialis Raise su gradino: 15 rip",
        "D4. Face Pull: 12 rip",
        "Recupero: 60\"",
    ],
}

SCHEDA_BUILD = {
    "label": "Potenza/RFD 3x4-5 esplosivi @25-30% 1RM",
    "durata": 45,
    "righe": [
        "WARM-UP (6 min)",
        "A-March con rimbalzo elastico: 2 x 20 metri",
        "Hurdle Walk-through: 2 x 6 per gamba",
        "Band Walk laterale con mini-band sotto le ginocchia: 2 x 10 metri per lato",
        "BLOCCO A — potenza e tripla estensione, 3 serie",
        "A1. Trap Bar Jump Shrug: 4 salti massimali @25-30% 1RM, atterraggio morbido",
        "Recupero: 2'",
        "BLOCCO B — forza esplosiva unilaterale, 3 serie",
        "B1. Box Step-Up esplosivo 45 cm: 5 rip x gamba, manubri leggeri RPE 6",
        "Recupero: 90\"",
        "BLOCCO C — catena posteriore, 3 serie",
        "C1. Single-Leg RDL kettlebell controlaterale: 6 rip x gamba RPE 7, bacino parallelo",
        "Recupero: 75\"",
        "BLOCCO D — reattivita' tendinea, 3 serie",
        "D1. Drop Jump da box 20-30 cm: 5 balzi, contatto sotto 250 ms, caviglia rigida",
        "Recupero: 90\"",
        "BLOCCO E — parte alta in superserie, 3 serie",
        "E1. Trazioni alla sbarra: 5 rip",
        "E2. Chest Press: 6 rip",
        "E3. Seated Row: 8 rip",
        "E4. Face Pull: 12 rip",
        "Recupero: 60\"",
        "BLOCCO F — core rotazionale, 3 serie",
        "F1. Swiss Ball Stir-the-Pot: 8 rotazioni per senso",
        "Recupero: 60\"",
    ],
}

SCHEDA_PRIMING = {
    "label": "Priming neuromuscolare 2x3 @75% 1RM, zero fatica",
    "durata": 25,
    "righe": [
        "ATTIVAZIONE (4 min)",
        "Pogo Jump a caviglie rigide: 2 x 15 rimbalzi",
        "Arm Swimmers prono: 2 x 10",
        "BLOCCO A — priming bilaterale, 2 serie",
        "A1. Trap Bar Deadlift veloce: 3 rip @75% 1RM, concentrica alla massima velocita'",
        "Recupero: 2'",
        "BLOCCO B — potenza parte alta, 2 serie",
        "B1. Medicine Ball Slam 5-6 kg: 5 lanci massimali",
        "Recupero: 90\"",
        "BLOCCO C — tirata e cuffia, 2 serie",
        "C1. Inverse Pull: 6 rip",
        "C2. Face Pull: 12 rip leggero",
        "Recupero: 45\"",
        "BLOCCO D — controllo pelvico, 2 serie",
        "D1. Deadbug isometrico con fitball: 6 contrazioni da 4\" per lato",
        "Recupero: 45\"",
    ],
}

def _scheda_condensata(carico, label, ordine):
    """Forza condensata di 35' per la giornata abbinata al cardio (knowledge base, par. 6)."""
    return {
        "label": label,
        "durata": 35,
        "ordine": ordine,
        "righe": [
            "WARM-UP (5 min)",
            "Apertura anche + dorsiflessione caviglia",
            "Glute Bridge: 10 rip",
            "BLOCCO A — forza, 3 serie",
            f"A1. Trap Bar Deadlift: 4 rip {carico}",
            "Recupero: 2'",
            "BLOCCO B — tirata e spinta 1 a 1, 3 serie",
            "B1. Inverse Pull (TRX o bilanciere): 8 rip",
            "B2. Press-up: 8 rip",
            "Recupero: 60\"",
            "BLOCCO C — tendine d'Achille e tronco, 3 serie",
            "C1. Single-Leg Calf Raise su gradino, discesa in 3\": 12 rip x gamba",
            "C2. Side Plank: 30\" per lato",
            "C3. Face Pull: 12 rip",
            "Recupero: 45\"",
        ],
    }

SCHEDA_B2B = _scheda_condensata(
    "@80% 1RM, RIR 2-3", "Forza condensata 3x4 @80% 1RM, poi cardio Z2",
    "ORDINE: forza PRIMA (sistema nervoso fresco), poi bici indoor (rulli/spinning) Z2 agile "
    "90-95 rpm o nuoto Z1-Z2 puro senza palette. Tra le due: 25-30g di carboidrati + 6-8g di EAA.")

SCHEDA_B2B_CORSA = _scheda_condensata(
    "@68% 1RM, RIR 3", "Forza condensata 3x4 @68% 1RM, dopo la corsa",
    "ORDINE: PRIMA la corsa aerobica Z1-Z2, poi la forza con carichi -15% (fatica neurale). "
    "Tra le due: 25-30g di carboidrati + 6-8g di EAA.")

# NUOVA (04/10/2026 — richiesta di Simone, ciclo continuo senza gare): forza "companion"
# pre-hab del mercoledi', subito dopo la corsa easy. Carichi leggeri, nessuna fatica da
# portare alla qualita' di giovedi'/venerdi'. Stesso formato delle altre schede (blocchi,
# serie, recuperi) cosi' gym_in_step la converte negli step Intervals.icu.
SCHEDA_COMPANION = {
    "label": "Pre-hab companion: catena posteriore, caviglia/anche, cuffia, core",
    "durata": 40,
    "carico": ("REGOLA DI CARICO: RPE 6-7 su ogni serie, mai a cedimento; esecuzione lenta "
               "e controllata, l'obiettivo e' il movimento pulito, non il carico."),
    "ordine": ("ORDINE: PRIMA la corsa easy Z2 con gli allunghi, SUBITO DOPO questa scheda. "
               "Carichi leggeri, RPE 6-7, mai a cedimento: e' pre-hab, non allenamento di forza."),
    "righe": [
        "WARM-UP (4 min)",
        "Cat-Camel: 2 x 8",
        "90/90 Hip Switch: 2 x 6 per lato",
        "BLOCCO A — catena posteriore leggera, 3 serie",
        "A1. Romanian Deadlift con manubri: 10 rip RPE 6, discesa in 3\"",
        "A2. Single-Leg Hip Thrust: 10 rip x gamba",
        "A3. Nordic Hamstring eccentrico assistito con elastico: 5 rip",
        "Recupero: 60\"",
        "BLOCCO B — caviglia e anche, 2 serie",
        "B1. Knee-to-Wall (dorsiflessione caviglia): 10 rip x lato",
        "B2. Cossack Squat a corpo libero: 6 rip x lato",
        "B3. Soleus Raise seduto, ginocchio flesso: 15 rip",
        "Recupero: 45\"",
        "BLOCCO C — cuffia dei rotatori e scapole (nuoto), 2 serie",
        "C1. Extra-rotazione con elastico, gomito al fianco: 15 rip x braccio",
        "C2. Y-T-W prono su panca inclinata, senza carico: 8 rip per lettera",
        "C3. Serratus Wall Slide con foam roller: 10 rip",
        "Recupero: 45\"",
        "BLOCCO D — core anti-rotazionale, 3 serie",
        "D1. Pallof Press al cavo o con elastico: 10 rip x lato, tenuta 2\"",
        "D2. Side Plank con abduzione: 30\" per lato",
        "D3. Dead Bug con elastico: 8 rip x lato",
        "Recupero: 45\"",
    ],
}

SCHEDE_SOLA_FORZA = {"base": SCHEDA_BASE, "build": SCHEDA_BUILD, "specifico": SCHEDA_BUILD}

def get_fase_macro(fase):
    # MODIFICA (27/09/2026 — decisione di Simone): quattro macro-fasi, base / build /
    # specifico-picco / taper. Costruzione e Sviluppo (anche in scarico) erano "base":
    # ora prendono la scheda di potenza/RFD invece di quella di forza massimale.
    if "Avvicinamento" in fase or fase.startswith("Taper"):
        return "taper"
    if fase.startswith(("Specifico", "Picco")):
        return "specifico"
    if "Costruzione" in fase or "Sviluppo" in fase:
        return "build"
    return "base"

def get_main_block(fase, gym_day_type="strength"):
    """Scheda S&C della seduta (MODIFICA 27/09/2026, vedi SCHEDA_BASE): in taper sempre il
    priming; altrimenti la scheda completa della fase nella serata di sola forza, quella
    condensata di 35' quando la forza divide lo slot con il cardio."""
    macro = get_fase_macro(fase)
    gym_day_type = (gym_day_type or "").replace("_rir3", "").replace("_sep", "")
    if macro == "taper":
        return SCHEDA_PRIMING
    if gym_day_type == "companion":     # 04/10/2026: pre-hab dopo la corsa easy
        return SCHEDA_COMPANION
    if gym_day_type == "run":
        return SCHEDA_B2B_CORSA
    if gym_day_type == "b2b":
        return SCHEDA_B2B
    return SCHEDE_SOLA_FORZA[macro]

def _gym_rir3(fase, gym_day_type):
    """MODIFICA (27/09/2026 — decisione di Simone): qualita' di corsa il giorno prima ->
    RIR 3 fisso solo sulle schede di forza pesante (base, condensata all'80%). Potenza/RFD,
    priming e forza dopo la corsa (gia' RIR 3) restano come sono."""
    return (str(gym_day_type).endswith("_rir3")
            and get_main_block(fase, gym_day_type) in (SCHEDA_BASE, SCHEDA_B2B))

def gym_durata_target(fase, gym_day_type="strength"):
    """Durata della seduta palestra: e' quella della scheda scritta in descrizione, unica
    fonte per il moving_time dell'evento e per la riga 'Durata totale'."""
    return get_main_block(fase, gym_day_type)["durata"]

def build_gym_description(descrizione_claude, fase, gara_princ=None, gym_day_type="strength"):
    scheda = get_main_block(fase, gym_day_type)
    righe = scheda["righe"]
    rir3 = _gym_rir3(fase, gym_day_type)
    if rir3:
        righe = [r.replace("RIR 2-3", "RIR 3").replace("RIR 2,", "RIR 3,") for r in righe]
    blocchi = [
        f"Schema palestra — {scheda['label']}",
        scheda.get("carico", _GYM_CARICO),   # 04/10/2026: la companion ha la sua regola
        *(["IERI QUALITA' DI CORSA: RIR 3 fisso sui carichi pesanti (indolenzimento da "
           "impatto, la forza target non si esprime pieno)."] if rir3 else []),
        *(["OGGI ANCHE NUOTO: nuoto al mattino, forza al pomeriggio, almeno 6-8 ore fra le "
           "due. Nello stesso slot solo con nuoto Z1-Z2 facile."]
          if "_sep" in str(gym_day_type) else []),
        *([scheda["ordine"]] if scheda.get("ordine") else []),
        *righe,
        f"Durata totale: ~{scheda['durata']} min, cambi attrezzo inclusi — "
        "se sfori, taglia dall'ultimo blocco, mai dal blocco A",
        f"* {scheda['durata']}m",
    ]
    desc = "\n\n".join(blocchi)

    # La descrizione di Claude deve contenere SOLO adattamenti del giorno
    # (biometrici/TAG/focus) — il prompt lo impone, ma se ricopia comunque lo
    # schema (marker A1./Warm-up/Blocco/Finisher) va scartata: lo schema sopra
    # e' gia' completo e duplicarlo in coda creava incoerenze (bug titolo 80%
    # vs corpo 70-75% + NOTE COACH ridondante).
    if descrizione_claude and len(descrizione_claude) > 20:
        e_schema = re.search(r"\b(A1\.|B1\.|C1\.|Warm[- ]?up|Blocco principale|Finisher)",
                             descrizione_claude, re.I)
        if not e_schema:
            desc += f"\n\nADATTAMENTI OGGI\n\n{descrizione_claude[:400]}"
    return desc

def calc_moving_time(sessione_tipo, durata_min, params=None):
    p = merge_session_defaults(sessione_tipo, params, durata_min)
    tot = _calc_moving_time_base(sessione_tipo, durata_min, p)
    # Il blocco aerobico che assorbe l'underflow e' uno step reale della sessione.
    # Nel nuoto e' gia' contato dentro swim_durata_reale_min (in metri, non in minuti):
    # sommarlo di nuovo qui raddoppierebbe la durata dell'evento a calendario.
    if sessione_tipo != "Swim" and int(p.get("endurance_min") or 0) >= _ENDU_MIN_FIT:
        tot += int(p["endurance_min"]) * 60
    if sessione_tipo != "Strides" and p.get("strides_reps"):
        tot += p.get("strides_reps") * (p.get("strides_sec", 20) + p.get("strides_rec_sec", 60))
    return tot

def _calc_moving_time_base(sessione_tipo, durata_min, p):
    if sessione_tipo == "Threshold":
        wu = p.get("wu_min",15); cd = p.get("cd_min",10)
        reps = p.get("reps",3); rep = p.get("rep_min",10); rec = p.get("rec_min",3)
        return (wu + reps*rep + reps*rec + cd) * 60
    elif sessione_tipo == "Interval":
        wu = p.get("wu_min",15); cd = p.get("cd_min",10)
        reps = p.get("reps",6); rep = p.get("rep_min",1); rec = p.get("rec_min",1)
        return (wu + reps*rep + reps*rec + cd) * 60
    elif sessione_tipo == "HillWork":
        wu = p.get("wu_min",15); cd = p.get("cd_min",10)
        reps = p.get("reps",8); rep = p.get("rep_min",2); rec = p.get("rec_min",2)
        return (wu + reps*rep + reps*rec + cd) * 60
    elif sessione_tipo == "Fartlek":
        wu = p.get("wu_min",12); cd = p.get("cd_min",8)
        reps = p.get("reps",10); rep = p.get("rep_min",1); rec = p.get("rec_min",1)
        return (wu + reps*rep + reps*rec + cd) * 60
    elif sessione_tipo == "Repetition":
        wu = p.get("wu_min",15); cd = p.get("cd_min",10); reps = p.get("reps",10)
        rep_sec = p.get("rep_sec",35); rec = p.get("rec_min",2)
        return wu*60 + reps*rep_sec + reps*rec*60 + cd*60
    elif sessione_tipo == "HillSprints":
        wu = p.get("wu_min",12); cd = p.get("cd_min",8); reps = p.get("reps",8)
        rep_sec = p.get("rep_sec",10); rec = p.get("rec_min",2)
        return wu*60 + reps*rep_sec + reps*rec*60 + cd*60
    elif sessione_tipo == "Alternations":
        wu = p.get("wu_min",10); cd = p.get("cd_min",10)
        reps = p.get("reps",8); rep = p.get("rep_min",1)
        return (wu + 2*reps*rep + cd) * 60
    elif sessione_tipo == "DownhillRepeats":
        # BUG FIX: mancava del tutto e cadeva sul ramo generico `durata_min*60`, mentre la
        # description costruisce wu + reps*(rep+rec) + cd. moving_time e struttura reale
        # divergevano quindi in modo opposto agli altri tipi a ripetute (evento piu' lungo
        # di quanto scritto in descrizione), e evento_gia_allineato non poteva mai
        # confermare la seduta: riscrittura a ogni run.
        wu = p.get("wu_min",12); cd = p.get("cd_min",8)
        reps = p.get("reps",6); rep = p.get("rep_min",2); rec = p.get("rec_min",2)
        return (wu + reps*rep + reps*rec + cd) * 60
    elif sessione_tipo == "Swim":
        # Vedi swim_durata_reale_min(): la durata di una nuotata non e' durata_min ma
        # quella degli step realmente generati (metri/passo + recuperi).
        return swim_durata_reale_min(durata_min, p) * 60
    return durata_min * 60

# Etichette per i tre profili BikeCross sul calendario. Il nome del profilo interno
# ("recovery") descrive il RUOLO nella regola 12a (sostituisce Recovery/Easy per
# scaricare l'impatto), NON l'intensita': la sessione generata e' un Z2 pieno con lo
# stesso beneficio aerobico della corsa easy sostituita (durata gia' maggiorata dal
# fattore di conversione). Chiamarla "Bike Cross Recovery" sul calendario e' quindi
# fuorviante: sembra uno scarico. Qui il nome dell'evento viene reso esplicito
# sull'intensita' reale, senza toccare il profilo interno usato dal resto del codice.
BIKECROSS_LABEL = {
    "recovery": "Z2 aerobica",
    "soglia":   "Soglia",
    "vo2max":   "VO2max",
}

_BIKECROSS_NOME_RE = re.compile(
    r"\b(bike\s*cross|bikecross|recovery|recupero|scarico|z2\s*aerobica|soglia|vo2\s*max|vo2max)\b",
    re.IGNORECASE)

def normalizza_nome_bikecross(name, params=None):
    """Nome autoritativo di una sessione BikeCross sul calendario: il codice sa quale
    profilo ha davvero generato (params.profilo) e quindi quale intensita' contiene la
    description, mentre il nome proposto dall'LLM puo' descriverla male (il caso tipico:
    'Bike Cross Recovery' per una sessione che e' interamente Z2). Si ripulisce il nome
    dai termini di profilo/intensita' proposti e si riaggancia l'etichetta corretta,
    stessa logica gia' usata da normalizza_nome_gym() per i %massimale."""
    profilo = (params or {}).get("profilo", "recovery")
    label   = BIKECROSS_LABEL.get(profilo, BIKECROSS_LABEL["recovery"])
    # L'ambiente si legge PRIMA di ripulire il nome (le parole indoor/outdoor sono uno
    # dei suoi indizi) e viene poi riagganciato dal codice: sul calendario deve essere
    # sempre leggibile a colpo d'occhio se quella seduta e' su strada (target watt) o
    # al chiuso (target FC), perche' e' l'unica differenza che cambia come si esegue.
    amb     = bike_ambiente(params, name)
    base    = _BIKECROSS_NOME_RE.sub("", name or "")
    base    = re.sub(r"\b(indoor|outdoor)\b", "", base, flags=re.IGNORECASE)
    base    = re.sub(r"\s{2,}", " ", base).strip(" -—:,·()")
    nome    = f"Bike Cross {label}"
    if base:
        nome += f" — {base}"
    suffisso = f" ({amb}{', watt' if amb == 'outdoor' and get_bike_ftp() else ''})"
    if len(nome) + len(suffisso) > 60:
        nome = nome[:60 - len(suffisso)]
    # BUG FIX (04/09/2026 — nome spaiato sul calendario e sull'orologio): il run del
    # 04/09 ha scritto "Bike Cross Z2 aerobica — bici (spinning (indoor)" partendo da
    # "Recovery bici (spinning)". Due sorgenti indipendenti dello stesso difetto: (a) lo
    # .strip(" -—:,·()") sopra toglie una parentesi solo se resta a un ESTREMO, quindi
    # con "bici (spinning)" sopravvive la sola aperta; (b) il taglio a 60 caratteri qui
    # sopra puo' cadere dentro una coppia ancora integra. Il controllo sta DOPO
    # entrambe e prima del suffisso (che e' l'unica coppia che il codice garantisce):
    # se il nome ha parentesi spaiate o vuote le si toglie, il testo resta leggibile.
    if nome.count("(") != nome.count(")") or re.search(r"\(\s*\)", nome):
        nome = re.sub(r"\s{2,}", " ",
                      nome.replace("(", " ").replace(")", " ")).rstrip(" -—:,·")
    return nome + suffisso

# Durata TOTALE dichiarata nel nome ("Nuoto tecnica 45min", "Nuoto Z2 60'"). Il gruppo
# opzionale davanti cattura un eventuale "NxM" (es. "5x4min"): quelle sono le ripetute,
# gia' rese coerenti con la sessione da enforce_reps_da_nome, e NON vanno riscritte.
# "m"/"mtr" non sono ammessi come unita' di tempo: in un nome di nuoto sono metri.
_SWIM_NOME_DURATA_RE = re.compile(r"(\d{1,3}\s*[x×]\s*)?(?<!\d)(\d{1,3})\s*(?:min(?:uti)?\b|')",
                                  re.IGNORECASE)

# BUG FIX (30/08/2026): i METRI dichiarati nel titolo non erano mai riconciliati con la
# struttura realmente generata — solo i minuti lo erano. Il numero nel titolo era quello
# scritto dall'LLM e basta. Misurato sulla griglia 30-90min x 4 profili: scarti dal 12%
# al 64% (es. "Tecnica Total Immersion — 1800m" a 60min genera 2300mtr di step).
# Conseguenza osservabile: sul calendario e su Telegram la seduta annunciava una
# distanza, in vasca l'orologio ne faceva nuotare un'altra. Stesso invariante gia'
# applicato ai minuti ("il titolo segue il contenuto reale"), esteso ai metri.
# La distanza NON viene ricalcolata con una formula gemella (sarebbe l'ennesima coppia
# di espressioni destinate a divergere): si somma quella scritta negli step dalla
# description reale, che e' l'unica fonte. Il gruppo opzionale "NxM" davanti protegge
# le distanze di ripetuta ("6x100m"), che non sono il totale della seduta.
_SWIM_NOME_METRI_RE = re.compile(r"(\d{1,3}\s*[x×]\s*)?(?<!\d)(\d{3,5})\s*(?:mtr|mt|metri|m)\b",
                                 re.IGNORECASE)

# Metri di uno step: una riga vuota chiude il blocco ripetuto "Nx" (sintassi Intervals.icu).
_SWIM_STEP_MTR_RE = re.compile(r"(\d+)\s*mtr\b", re.IGNORECASE)

_SWIM_BLOCCO_RE   = re.compile(r"^(\d+)x$")

def normalizza_nome_swim(name, durata_min, params=None):
    """Nome autoritativo di una sessione di nuoto sul calendario: i minuti dichiarati nel
    titolo devono essere quelli della sessione che il codice genera davvero, non quelli
    proposti dal piano (vedi swim_durata_reale_min: con profilo "soglia"/"velocita" la
    struttura viene da reps/rep_min e ignora durata_min, e gli step sono arrotondati alla
    vasca). Stessa logica gia' usata da normalizza_nome_bikecross/normalizza_nome_gym: il
    titolo segue il contenuto reale. Se il nome non dichiara minuti resta invariato."""
    reale = swim_durata_reale_min(durata_min, params)
    # MODIFICA (27/09/2026): con la libreria il titolo e' quello della seduta scelta ora
    # (la durata puo' essere cambiata dopo assegna_libreria_nuoto, e con lei la taglia).
    _sed_n = seduta_nuoto_libreria(merge_session_defaults("Swim", params), durata_min)
    if _sed_n:
        name = f"Nuoto {_sed_n['titolo']} {reale}min"

    def _sub(m):
        if m.group(1):          # "5x4min": ripetute, non durata totale
            return m.group(0)
        return f"{reale}min"

    nome = _SWIM_NOME_DURATA_RE.sub(_sub, name or "")

    # Metri: si riscrivono solo se il titolo ne dichiara (mai aggiunti se assenti).
    if _SWIM_NOME_METRI_RE.search(nome):
        tot, mult = 0, 1
        for riga in build_run_description("Swim", durata_min, 165, params).splitlines():
            s = riga.strip()
            if not s:
                mult = 1
                continue
            blocco = _SWIM_BLOCCO_RE.match(s)
            if blocco:
                mult = int(blocco.group(1))
                continue
            if s.startswith("- "):
                mm = _SWIM_STEP_MTR_RE.search(s)
                if mm:
                    tot += int(mm.group(1)) * mult
        if tot:
            def _sub_m(m):
                if m.group(1):      # "6x100m": distanza di ripetuta, non il totale
                    return m.group(0)
                return f"{tot}m"
            nome = _SWIM_NOME_METRI_RE.sub(_sub_m, nome)

    nome = re.sub(r"\s{2,}", " ", nome).strip(" -—:,·")
    return nome[:60]

# Rimuove dal nome proposto dall'LLM un eventuale suffisso di parte gia' presente
# (es. "Brick 1/2 bici 40min"), per non impilarli ad ogni riscrittura.
_BRICK_NOME_RE  = re.compile(r"\s*[—–-]?\s*(?:[12]\s*/\s*2|T[12])\s*(?:bici|corsa[^,]*|transizione[^,]*)?(?:\s*\d+\s*(?:min|s)\b)?\s*$", re.I)

# ── EVENTO TRANSIZIONE (T2) ────────────────────────────────
# "Transition" e' un tipo di attivita' REALE di Intervals.icu: e' nato nel 2022 per
# dare un'identita' ai pezzi di transizione che arrivano dai file multisport Garmin
# (prima venivano importati come corse chiamate "Transition"). Usarlo anche in
# PIANIFICAZIONE ha due effetti utili:
#  1. il piano ha la stessa forma di cio' che l'orologio restituisce se il brick e'
#     registrato in modalita' multisport (Ride + Transition + Run): senza l'evento,
#     la transizione importata resta un'attivita' non pianificata e finisce nel
#     debrief come "sessione extra" (rumore puro);
#  2. la T2 diventa un oggetto con una durata obiettivo, quindi allenabile e
#     verificabile, invece di una nota dentro un altro step.
# NON e' un workout strutturato: nessun target, nessuno step: e' un segnaposto
# temporale. Se sul tuo Garmin Connect l'evento Transition dovesse comparire come
# workout spurio, basta mettere questa costante a False: il brick torna a due eventi
# e il codice ripulisce da solo la T2 rimasta a calendario.
BRICK_EVENTO_TRANSIZIONE = True

BRICK_T2_SEC = 180

# ── LA T2 DEVE ESSERE UNO STEP, NON SOLO UN TESTO ──────────
# BUG FIX sincronizzazione Suunto: l'evento Transition nasceva con una description di
# sola prosa ("Transizione T2 obiettivo <180s: bici al posto, casco giu'...") e NESSUNA
# riga di step. Intervals.icu costruisce la SuuntoPlus Guide dagli step della
# description: senza nemmeno uno step la guida arriva a Suunto con "steps" vuoto e
# l'upload viene rifiutato -> l'intera sincronizzazione dei workout pianificati si
# pianta su quel giorno (stessa famiglia di problemi del thread forum 9560, dove David
# elenca fra le cause di rottura della guida proprio la description senza contenuto
# strutturato). Da qui la regola: OGNI evento creato dallo script — transizione
# compresa — deve avere almeno uno step con una durata dichiarata.
# Lo step della T2 e' volutamente SENZA target (nessun %LTHR, nessuna zona): la
# transizione e' tempo cronometrato, non intensita'. La durata dello step e' l'obiettivo
# BRICK_T2_SEC, e senza flag "press lap" lo step si chiude da solo allo scadere del
# tempo oppure prima se premi il lap: e' esattamente il comportamento voluto in T2
# (vedi thread forum 11376: senza il flag il lap button ANTICIPA la fine dello step).
_STEP_CON_DURATA_RE = re.compile(r"^\s*-\s.*?\b\d+\s*(?:m\d+s|min|mtr|m|s)\b", re.M | re.I)

def fmt_durata_step(secondi):
    """Durata di uno step nella sintassi Intervals.icu: "3m", "2m30s", "45s".
    ATTENZIONE: "m" e' MINUTI (i metri sono "mtr"), vedi il commento sul nuoto."""
    s = max(1, int(secondi or 0))
    m, r = divmod(s, 60)
    return f"{m}m{r}s" if m and r else f"{m}m" if m else f"{r}s"

def descrizione_ha_step(desc):
    """True se la description contiene almeno una riga di step con una durata: e' la
    condizione minima perche' Intervals.icu possa generarne una guida per l'orologio."""
    return bool(_STEP_CON_DURATA_RE.search(desc or ""))

def build_brick_t2_descrizione(params=None):
    """Description dell'evento T2: un paragrafo di contesto + UNO step di durata.
    Fonte unica, usata sia in creazione sia dal controllo di allineamento (cosi' le T2
    scritte dalla versione precedente — solo prosa, guida vuota — risultano disallineate
    e vengono riscritte al primo run invece di restare a calendario a rompere la sync).

    BUG FIX (30/08/2026): il gesto era descritto SOLO nella versione outdoor ("bici al
    posto, casco giu'"), unico ambiente in cui Simone non puo' eseguire un brick — non
    ha un posto sicuro dove lasciare la bici in transizione, e rientrarla in casa
    annulla la transizione stessa. Su un brick indoor (spinning + tapis) quell'istruzione
    arrivava sull'orologio ineseguibile, stesso difetto gia' corretto sul blocco debrief.
    L'ambiente si legge con bike_ambiente (default "indoor", come per BikeCross)."""
    gesto = ("giu' dallo spinning, scarpe da corsa e via sul tapis"
             if bike_ambiente(params) == "indoor"
             else "bici al posto, casco giu', scarpe su, via di corsa")
    return (f"Transizione T2 del brick: {gesto}. "
            f"Obiettivo sotto i {BRICK_T2_SEC}s. Nessun target di intensita': si cronometra "
            f"e basta.\n\n"
            f"- T2 transizione {fmt_durata_step(BRICK_T2_SEC)} {STEP_INT_WORK} "
            f"({gesto})\n")

def brick_split_min(durata_min, params=None):
    """(minuti bici, minuti corsa) del brick. UNICA fonte: la usano descrizioni,
    nomi e moving_time dei due eventi, che non possono quindi divergere.
    params.rep_min = minuti di BICI proposti dall'LLM.

    BUG FIX (30/08/2026 — misurato): l'evento T2 veniva scritto IN AGGIUNTA ai minuti
    pianificati. Un brick da 90min finiva a calendario come 63+3+27 = 93min, cioe' 3
    minuti che il piano non aveva prescritto e che quindi mancavano al BUDGET SETTIMANA,
    allo split nuoto/bici/corsa e al freno biometrico: il tetto orario frenava un numero
    diverso da quello davvero eseguito. Ora la transizione si scala dal budget, come
    ogni altro pezzo della seduta (invariante: bici + T2 + corsa == durata_min)."""
    tot = max(30, int(durata_min or 0))
    t2_min = -(-BRICK_T2_SEC // 60) if BRICK_EVENTO_TRANSIZIONE else 0
    tot = max(30, tot - t2_min)
    # Il brick non passa da merge_session_defaults (ha la sua aritmetica): la pulizia dei
    # numeri del piano va richiamata qui, vedi _params_numerici_puliti.
    bike_min = int(_params_numerici_puliti(params).get("rep_min") or round(tot * 0.7))
    bike_min = max(20, min(bike_min, tot - 10))
    return bike_min, tot - bike_min

def build_brick_descrizioni(durata_min, params=None):
    """(descrizione bici, descrizione corsa) del brick, transizione inclusa.
    La bici e' scritta in %LTHR (stessa convenzione di BikeCross/Swim: su un evento
    type="Ride" le percentuali si risolvono sulla LTHR bici) e la corsa in zone HR
    corsa: e' proprio questa separazione a eliminare i target sbagliati."""
    p = params or {}
    bike_min, run_min = brick_split_min(durata_min, params)
    wu = 8 if bike_min >= 35 else 5
    cd = 5
    corpo = max(5, bike_min - wu - cd)
    # BUG FIX (30/08/2026), due difetti nella stessa riga di testo:
    #  1. la transizione bici->corsa e' la T2 (la T1 e' nuoto->bici), ma le due gambe la
    #     chiamavano "T1" mentre l'evento a calendario si chiama "T2 transizione": sullo
    #     stesso brick l'orologio mostrava due nomi per lo stesso gesto;
    #  2. il contorno era scritto solo per l'outdoor, l'unico ambiente in cui Simone NON
    #     puo' fare un brick (nessun posto sicuro dove lasciare la bici in transizione).
    #     Indoor la bici e' lo spinning e la corsa e' il tapis: la seduta si esegue in
    #     palestra senza transizione fittizia. Il target resta in %LTHR/HR in entrambi i
    #     casi (nessun dato di potenza sui macchinari, vedi bike_ambiente).
    indoor = bike_ambiente(params) == "indoor"
    mezzo_bici = "spinning" if indoor else "bici"
    t2_hint = ("T2: tapis gia' libero e scarpe pronte, si corre entro 3m"
               if indoor else "T2: scarpe pronte, corsa entro 3m")
    # BUG FIX (27/09/2026): riscaldamento e defaticamento bici erano "0-80% LTHR" = 0-122 bpm
    # con LTHR 153, fino a 18 bpm dentro la Z2 Ride senza allarme. Ora "Z1 HR" = 0-104.
    bike_desc = (f"- {wu}m Z1 HR {STEP_INT_WU} (cadenza 85-95rpm su {mezzo_bici}, "
                 f"pre: mobilita+skip)\n\n"
                 f"- {corpo}m 81-93% LTHR {STEP_INT_WORK} (cadenza 85-95rpm)\n\n"
                 f"- {cd}m Z1 HR {STEP_INT_CD} (cadenza alta gambe sciolte, {t2_hint})\n")
    # REGOLA UNITA' TARGET UNICA: la corsa resta tutta in HR, il passo gara solo come
    # nota non parsabile ("al km", mai "/km" — vedi il branch Long).
    pace_str = p.get("pace_obiettivo_str")
    run_target = (f"Z2-Z3 HR (ritmo gara ~{pace_str.replace('/km', ' al km')})"
                  if pace_str else "Z2-Z3 HR")
    dove_corsa = "corsa sul tapis" if indoor else "corsa"
    run_desc = (f"- {run_min}m {dove_corsa} {run_target} {STEP_INT_WORK} (gambe da "
                f"{mezzo_bici}, subito dopo la T2: cadenza alta e passo controllato nei "
                f"primi minuti, post: stretching)\n")
    return bike_desc, run_desc

def normalizza_nome_brick(name, parte, durata_min, params=None):
    """Nome autoritativo delle parti del brick sul calendario: dichiara sport,
    ordine e minuti reali di quella leg, cosi' e' impossibile confondere gli eventi
    fra loro o scambiarli per sessioni distinte (stessa logica di
    normalizza_nome_bikecross/normalizza_nome_gym)."""
    base = _BRICK_NOME_RE.sub("", name or "").strip(" -—–:,·")
    base = re.sub(r"\s{2,}", " ", base) or "Brick"
    if "brick" not in base.lower():
        base = f"Brick — {base}"
    bike_min, run_min = brick_split_min(durata_min, params)
    suff = {"bike": f"1/2 bici {bike_min}min",
            "t2":   f"T2 transizione {BRICK_T2_SEC}s",
            "run":  f"2/2 corsa off-bike {run_min}min"}[parte]
    return f"{base} {suff}"[:60]

def brick_parti_attese():
    """Parti che il brick deve avere a calendario, nell'ordine di esecuzione."""
    return ["bike", "t2", "run"] if BRICK_EVENTO_TRANSIZIONE else ["bike", "run"]

def normalizza_nome_gym(name, fase, gym_day_type="strength"):
    """Il titolo non deve dichiarare %massimale/serie propri (fonte del bug
    'Forza Generale 80% max' vs corpo 70-75%): rimuove i claim numerici dal nome
    proposto da Claude e aggancia l'intensita' autoritativa del blocco realmente
    generato (scheda della fase e della giornata, vedi get_main_block)."""
    base = re.sub(r"[@\s]*\d{1,3}(?:-\d{1,3})?\s*%\s*(?:mass?imale|max)?", "", name or "", flags=re.I)
    base = re.sub(r"\d+\s*serie", "", base, flags=re.I)
    base = re.sub(r"\s{2,}", " ", base).strip(" -—:,·") or "Forza"
    label = get_main_block(fase, gym_day_type)["label"]
    m = re.search(r"\d{2,3}(?:-\d{2,3})?%", label)
    nome = f"{base} @{m.group(0)}" if m else base
    # MODIFICA (27/09/2026): il suffisso dice l'ordine della giornata abbinata al cardio
    # (prima era "mantenimento post-qualita" sulla companion). In taper la scheda e' il
    # priming qualunque sia la giornata: nessun suffisso.
    if get_fase_macro(fase) == "taper":
        return nome
    nome += {"b2b": " — prima del cardio Z2", "run": " — dopo la corsa",
             "strength_sep": " — 6-8h dal nuoto"}.get(str(gym_day_type).replace("_rir3", ""), "")
    return nome + (" — RIR 3" if _gym_rir3(fase, gym_day_type) else "")

# MODIFICA (25/09/2026 — richiesta di Simone): la palestra si scrive su Intervals.icu a
# STEP, come il suo workout "Scheda A": ogni esercizio e' uno step "Press lap" (si
# avanza premendo lap a fine serie), le serie sono blocchi "Nx", il recupero e' uno
# step Rest. Contenuto invariato (build_gym_description, testato); la struttura segue
# Jarvis, "Strength and Conditioning for Triathlon" cap. 8 (fig. 8.2: preparazione ->
# forza/potenza a superserie -> condizionamento in coda, recuperi brevi 20-45" nel
# condizionamento). Nel testo degli step niente %, apici o "Nm": il parser di
# Intervals.icu li leggerebbe come target o durate. Le stime degli step sommano la
# durata reale della seduta, cosi' la durata a calendario resta quella di prima.
# Conseguenza osservabile: sull'orologio la seduta scorre esercizio per esercizio.
_GYM_SEZIONI = ("WARM-UP", "ATTIVAZIONE", "BLOCCO", "ISOMETRIE", "FINISHER")

def _gym_testo_step(t):
    t = re.sub(r"(\d+(?:-\d+)?)\s*%", r"\1 per cento", t)
    t = re.sub(r"(\d+)'(\d+)\"", r"\1 minuti \2 secondi", t)
    t = re.sub(r"(\d+)\"", r"\1 secondi", t)
    t = re.sub(r"(\d+)'", r"\1 minuti", t)
    t = re.sub(r"(\d+)\s*m\b", r"\1 metri", t)
    t = t.replace("@", "al ").replace(":", " —")
    t = re.sub(r"— (\d+(?:-\d+)?)$", r"— \1 rip", t.strip())
    return re.sub(r"\s{2,}", " ", t).strip()

def _gym_rec_sec(riga):
    m = re.search(r"(\d+)'(?:(\d+)\")?|(\d+)\"", riga)
    if not m:
        return 30
    return int(m.group(1)) * 60 + int(m.group(2) or 0) if m.group(1) else int(m.group(3))

def _gym_secondi_a_tempo(e):
    """Durata in secondi di un esercizio A TEMPO (isometria, plank, foam roller), None se
    e' a ripetizioni. 06/10/2026 — regola di Simone: gli esercizi a tempo sono step a
    durata, senza "Press lap". Durata = contrazioni x secondi x lati."""
    if re.search(r"\brip\b|\bripetizion", e.lower()):
        return None
    m = re.search(r"(\d+)\s*(?:\"|secondi\b|sec\b)", e) or re.search(r"(\d+)'", e)
    if not m:
        return None
    sec = int(m.group(1)) * (60 if m.group(0).endswith("'") else 1)
    n = re.search(r"(\d+)\s*(?:contrazioni|tenute)", e)
    lati = 2 if re.search(r"(per|x)\s+lato", e) else 1
    return sec * (int(n.group(1)) if n else 1) * lati


def gym_in_step(desc, durata_min):
    """Testo di build_gym_description -> workout a step Intervals.icu (Press lap / Rest)."""
    testo, blocchi, sez, gruppo = [], [], None, []

    def chiudi(rec=None):
        nonlocal gruppo
        if gruppo:
            if sez["warmup"]:
                n = 1
            else:
                inline = [int(m.group(1)) for e in gruppo
                          for m in [re.search(r":\s*(\d+)\s*x\s", e)] if m]
                n = sez["n"] or (max(inline) if inline else 1)
            righe = [re.sub(r":\s*(\d+)\s*x\s", r": \1 serie da " if sez["warmup"] else ": ", e)
                     for e in gruppo]
            blocchi.append((n, righe, rec if rec is not None else (30 if n > 1 else 0)))
            gruppo = []

    for riga in (l.strip() for l in (desc or "").splitlines()):
        if not riga or re.fullmatch(r"\* ?\d+m", riga):
            continue
        if riga.startswith(_GYM_SEZIONI):
            if sez:
                chiudi()
            m = re.search(r"(\d+)\s*serie", riga)
            sez = {"n": int(m.group(1)) if m else None,
                   "warmup": riga.startswith(("WARM-UP", "ATTIVAZIONE"))}
        elif riga.lower().startswith("recupero") and sez:
            chiudi(_gym_rec_sec(riga))
        # BUG FIX (28/09/2026 — "Press lap ADATTAMENTI OGGI" sull'orologio): la nota del
        # coach sta in coda alla descrizione, dopo l'ultimo blocco, e diventava 2 esercizi
        # x3 serie. Da li' in poi e' testo della seduta, non step.
        elif riga.startswith("ADATTAMENTI OGGI"):
            if sez:
                chiudi()
            sez = None
            testo.append(riga)
        elif sez and not riga.startswith("Durata totale"):
            gruppo.append(riga)
        else:
            testo.append(riga)
    if sez:
        chiudi()
    if not blocchi:
        return desc
    # 06/10/2026: gli esercizi a tempo hanno la loro durata e non entrano nella stima
    # della durata media degli esercizi a ripetizioni.
    a_tempo = sum(n * (_gym_secondi_a_tempo(e) or 0) for n, g, _ in blocchi for e in g)
    rec_tot = sum(n * r for n, _, r in blocchi)
    esec    = sum(n * sum(1 for e in g if _gym_secondi_a_tempo(e) is None) for n, g, _ in blocchi)
    stima   = max(30, min(120, round((durata_min * 60 - rec_tot - a_tempo) / max(esec, 1))))
    out = ["\n".join(testo)]
    for n, g, r in blocchi:
        righe = [(f"- {_gym_testo_step(e)} {_gym_secondi_a_tempo(e)}s intensity=active"
                  if _gym_secondi_a_tempo(e) else
                  f"- Press lap {_gym_testo_step(e)} {stima}s intensity=active") for e in g]
        if r:
            righe.append(f"- Rest {r}s intensity=rest")
        out.append((f"{n}x\n" if n > 1 else "") + "\n".join(righe))
    return "\n\n".join(x for x in out if x) + "\n"


# ── CORSA SENZA SENSORE DI POTENZA (03/10/2026 — decisione di Simone) ─────────────
# In intervals_coach.py le sedute di qualita' di corsa (POWER_TYPES) sono scritte in %
# della potenza critica, con file .zwo: presuppongono uno Stryd. Simone non lo usa, e
# un utente qualunque dell'app di solito non ce l'ha: quei target sull'orologio non
# significano nulla. Senza potenza la stessa struttura (durate, ripetute, recuperi)
# viene riscritta in:
#  - FREQUENZA CARDIACA per le sedute a blocchi lunghi (Threshold, Progressive, MPRun,
#    TestRace): token di zona "Zn HR" quando il target sta dentro una zona, "a-b% LTHR"
#    solo quando il range sta a cavallo di due zone (regola di Simone, coerente con il
#    BUG FIX del 27/09 sulla bici);
#  - PASSO quando c'e' anche un solo step attivo sotto i 3 minuti (Interval, Fartlek,
#    Repetition, Alternations, HillSprints): in ripetute brevi la FC non fa in tempo a
#    salire e come target e' inaffidabile. "% Pace" = percentuale del passo soglia
#    configurato su Intervals.icu.
# Unita' unica per evento in entrambi i casi (REGOLA UNITA' TARGET UNICA).
# Corrispondenza % potenza critica -> % LTHR di corsa (relazione FC/potenza sotto
# soglia, Friel; sopra soglia la FC satura): punti interpolati linearmente.
_POTENZA_CORSA = [False]
_LTHR_DA_POTENZA = [(50, 70), (60, 78), (70, 84), (80, 88), (90, 93), (100, 99),
                    (110, 104), (120, 106), (130, 108)]
_RE_TARGET_POTENZA = re.compile(r"(?<=\s)(\d{2,3})(?:-(\d{2,3}))?%(?:\s+Power)?(?=\s+intensity=)")
_RE_DURATA_STEP = re.compile(r"\s(\d+)(m|s)\s+\d{2,3}(?:-\d{2,3})?%")


def _lthr_da_potenza(pct):
    pts = _LTHR_DA_POTENZA
    if pct <= pts[0][0]:
        return pts[0][1]
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        if pct <= x1:
            return y0 + (y1 - y0) * (pct - x0) / (x1 - x0)
    return pts[-1][1]


def _zona_lthr(pct_lthr):
    """Zona FC di corsa (schema LTHR a 5 zone di Intervals.icu) per una % di LTHR."""
    for z, lim in ((1, 85), (2, 90), (3, 95), (4, 100)):
        if pct_lthr < lim:
            return z
    return 5


def _usa_passo(desc):
    for riga in desc.splitlines():
        if "intensity=active" not in riga:
            continue
        m = _RE_DURATA_STEP.search(riga)
        if m and (int(m.group(1)) * (60 if m.group(2) == "m" else 1)) < 180:
            return True
    return False


def converti_potenza_corsa(desc):
    """Riscrive in FC o in passo i target in % di potenza di una seduta di corsa."""
    passo = _usa_passo(desc)

    def sost(m):
        a = int(m.group(1)); b = int(m.group(2)) if m.group(2) else a
        lo, hi = min(a, b), max(a, b)
        if passo:
            if lo == hi:
                lo, hi = lo - 2, hi + 2
            return f"{lo}-{hi}% Pace"
        l_lo, l_hi = _lthr_da_potenza(lo), _lthr_da_potenza(hi)
        z_lo, z_hi = _zona_lthr(l_lo), _zona_lthr(l_hi)
        if z_lo == z_hi:
            return f"Z{z_lo} HR"
        return f"{round(l_lo)}-{round(l_hi)}% LTHR"

    return "\n".join(_RE_TARGET_POTENZA.sub(sost, r) if r.startswith("- ") else r
                     for r in desc.split("\n"))

# ── BORDO VERSO LA RETE (sostituisce le versioni HTTP di intervals_coach.py) ──────────
_ATTIVITA = []

def configura(atleta=None, attivita=None, potenza_corsa=False):
    """Inietta i dati dell'atleta: `atleta` = JSON di GET /athlete (sportSettings con
    lthr, ftp, threshold_pace), `attivita` = lista di GET /activities. Azzera le cache
    derivate (passo nuoto), cosi' due run nello stesso processo non si contaminano.
    `potenza_corsa`: True solo con un sensore di potenza in corsa (Stryd) in uso."""
    global _ATHLETE_CACHE, _ATTIVITA, _SWIM_PACE_CACHE
    _ATHLETE_CACHE = atleta if isinstance(atleta, dict) else {}
    _ATTIVITA = list(attivita or [])
    _SWIM_PACE_CACHE = None
    _POTENZA_CORSA[0] = bool(potenza_corsa)

def imposta_passo_nuoto(sec_100m, fonte="passo misurato dal coach"):
    """Passo di riferimento in vasca (sec/100m) per convertire minuti in metri."""
    global _SWIM_PACE_CACHE
    if isinstance(sec_100m, (int, float)) and sec_100m > 0:
        _SWIM_PACE_CACHE = (round(sec_100m), fonte)

def get_lthr(sport="Run"):
    """Come intervals_coach.get_lthr, sui dati iniettati da configura()."""
    for s in (_ATHLETE_CACHE or {}).get("sportSettings", []):
        if sport in s.get("types", []):
            return s.get("lthr", 165 if sport == "Run" else None)
    return 165 if sport == "Run" else None

def get_activities(days=14):
    """Come intervals_coach.get_activities, sui dati iniettati da configura()."""
    oldest = (now_local() - timedelta(days=days)).strftime("%Y-%m-%d")
    return [a for a in _ATTIVITA if (a.get("start_date_local") or "")[:10] >= oldest]


# ══════════════════════════════════════════════════════════════════════════════
# INTERFACCIA PER IL COACH (03/10/2026 — nuova, il resto del file e' estratto)
# ══════════════════════════════════════════════════════════════════════════════
# Le stesse trasformazioni che create_run_workout / create_brick_workout /
# create_gym_workout applicano in intervals_coach.py PRIMA della POST, in forma pura:
# ritornano gli eventi pronti, la scrittura resta al chiamante. Ordine e funzioni
# identici all'originale; tolto solo il ramo Garmin (fuori dalla versione distribuita).

FASE_IC = {"base": "Base", "senza_gara": "Base", "recupero": "Base",
           "build": "Costruzione", "peak": "Specifico", "taper": "Taper", "gara": "Taper"}

def eventi_seduta(data_str, nome, sessione_tipo, durata_min, sport, lthr=None, params=None,
                  bike_lthr=None, swim_lthr=None, fase="Base", gym_day_type="strength"):
    """Lista degli eventi Intervals.icu della seduta (1, o 2-3 per il brick).
    `fase`: nome di fase alla intervals_coach (vedi FASE_IC) per palestra e nuoto."""
    lthr = lthr or get_lthr("Run")
    p = dict(params or {})
    if sessione_tipo == "Rest":
        return []
    if sessione_tipo == "Gym":
        nome_g = normalizza_nome_gym(nome, fase, gym_day_type)
        desc = tag_tipo_in_descrizione(gym_in_step(
            build_gym_description("", fase, None, gym_day_type),
            gym_durata_target(fase, gym_day_type)), "Gym")
        return [{"category": "WORKOUT", "start_date_local": f"{data_str}T00:00:00",
                 "type": "WeightTraining", "name": nome_g, "description": desc,
                 "external_id": external_id_tipo("Gym", data_str),
                 "moving_time": int(durata_min) * 60}]
    if sessione_tipo == "Brick":
        bike_desc, run_desc = build_brick_descrizioni(durata_min, p)
        bike_min, run_min = brick_split_min(durata_min, p)
        tutte = {"bike": ("Ride", bike_desc, bike_min * 60),
                 "t2": ("Transition", build_brick_t2_descrizione(p), BRICK_T2_SEC),
                 "run": ("Run", run_desc, run_min * 60)}
        out = []
        for parte in brick_parti_attese():
            sp, desc, secondi = tutte[parte]
            out.append({"category": "WORKOUT", "start_date_local": f"{data_str}T00:00:00",
                        "type": sp, "name": normalizza_nome_brick(nome, parte, durata_min, p),
                        "description": tag_tipo_in_descrizione(desc, "Brick"),
                        "external_id": external_id_tipo("Brick", data_str, parte),
                        "moving_time": secondi})
        return out
    if sessione_tipo == "BikeCross":
        nome = normalizza_nome_bikecross(nome, p)
    elif sessione_tipo == "Swim":
        if sport == "Swim":
            p.setdefault("macro_nuoto", get_fase_macro(fase or ""))
        nome = normalizza_nome_swim(nome, durata_min, p)
    if sessione_tipo == "Swim" and sport == "OpenWaterSwim":
        p["acque_libere"] = True
    target = get_target_metrica(sessione_tipo, sport, p)
    desc = build_run_description(sessione_tipo, durata_min, lthr, p, bike_lthr, swim_lthr)
    desc = append_warmup_cooldown(desc, sessione_tipo)
    desc = tag_tipo_in_descrizione(desc, sessione_tipo)
    ev = {"category": "WORKOUT", "start_date_local": f"{data_str}T00:00:00", "type": sport,
          "name": nome, "description": desc,
          "external_id": external_id_tipo(sessione_tipo, data_str),
          "moving_time": calc_moving_time(sessione_tipo, durata_min, p)}
    potenza = (sessione_tipo in POWER_TYPES and sport == "Run"
               and not (sessione_tipo in ("MPRun", "Alternations") and p.get("pace_obiettivo_str")))
    if potenza and not _POTENZA_CORSA[0]:
        ev["description"] = converti_potenza_corsa(desc)
    elif potenza:
        ev["filename"] = f"{sessione_tipo.lower()}_{data_str}.zwo"
        ev["file_contents_base64"] = base64.b64encode(
            build_run_zwo(sessione_tipo, durata_min, target, p).encode()).decode()
    return [ev]

def problemi_evento(ev):
    """Regole gia' pagate in intervals_coach.py, come controllo finale prima della
    scrittura. Lista vuota = evento scrivibile."""
    out, desc, tipo = [], ev.get("description") or "", ev.get("type")
    if tipo != "WeightTraining" and not descrizione_ha_step(desc):
        out.append("description senza step di durata: non sincronizzabile sull'orologio")
    if "file_contents_base64" not in ev:
        unita = {u for u, rx in _UNIT_PATTERNS.items() if rx.search(desc)}
        if len(unita) > 1:
            out.append(f"target misti {sorted(unita)}: il watch ne mostra uno solo")
    if tipo == "Swim":
        if "% Pace" not in desc:
            out.append("nuoto senza target % Pace")
        if "Press lap" not in desc:
            out.append("nuoto senza defaticamento Press lap")
    if not ev.get("moving_time"):
        out.append("moving_time assente")
    return out
