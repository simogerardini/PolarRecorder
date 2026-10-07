# -*- coding: utf-8 -*-
"""
SOGLIE — LTHR e FTP aggiornate dalle attivita' reali, e rotazione dei test periodici
(cervello BioSleep, roadmap punto 4).

Estratto MECCANICAMENTE da intervals_coach.py (06/10/2026): stima_lthr_sport,
aggiorna_lthr_automatico, stima_bike_ftp, aggiorna_bike_ftp_automatico e la rotazione dei
test (TEST_ROTAZIONE). Codice copiato senza modifiche, commenti e BUG FIX originali inclusi.

Differenze (solo il bordo):
- configura(intestazioni, atleta_id, atleta, attivita): le chiamate usano l'autenticazione
  passata dal cervello (token OAuth o chiave API); get_lthr / get_activities leggono i dati
  iniettati invece di rifare GET.
- TEST_ROTAZIONE: protocolli senza Stryd (decisione di Simone del 04/10/2026); vedi sotto.
Il principio resta quello di intervals_coach: soglie SOLO al rialzo dagli sforzi reali; un
ribasso va confermato da un test.
"""
import requests
from datetime import datetime, timedelta

from biometria import now_local

ICU = {}
ATHLETE_ID = "0"


def _http():
    """Sessione propria: coach_settimanale all'import rimappa requests.get/post sulla sua
    Session; le chiamate di questo modulo non dipendono da quale modulo e' stato caricato
    prima."""
    return requests.Session()
_ATHLETE_CACHE = None
_ATTIVITA = []


def configura(intestazioni, atleta_id, atleta=None, attivita=None):
    global ICU, ATHLETE_ID, _ATHLETE_CACHE, _ATTIVITA
    ICU, ATHLETE_ID = dict(intestazioni or {}), str(atleta_id or "0")
    _ATHLETE_CACHE = atleta if isinstance(atleta, dict) else {}
    _ATTIVITA = list(attivita or [])


def get_lthr(sport="Run"):
    """Come intervals_coach.get_lthr: legge l'atleta iniettato; se la cache e' stata
    azzerata (dopo una scrittura di soglia) lo rilegge da Intervals.icu, cosi' la sport
    successiva confronta con il valore vero e non con None."""
    global _ATHLETE_CACHE
    if _ATHLETE_CACHE is None:
        r = _http().get(f"https://intervals.icu/api/v1/athlete/{ATHLETE_ID}",
                                   headers=ICU, timeout=30)
        _ATHLETE_CACHE = r.json() if r.status_code == 200 else {}
    for s in (_ATHLETE_CACHE or {}).get("sportSettings", []):
        if sport in s.get("types", []):
            return s.get("lthr")
    return None


def get_activities(days=14):
    oldest = (now_local() - timedelta(days=days)).strftime("%Y-%m-%d")
    return [a for a in _ATTIVITA if (a.get("start_date_local") or "")[:10] >= oldest]

# ── TEST PERIODICI (SOGLIE E CRITICAL POWER) ────────────────────────────────
# Problema: senza una gara a calendario il coach entra in preparazione continua 70.3
# (get_multisport_703_phase) e puo' restare mesi senza MAI raccogliere un dato oggettivo. Le conseguenze
# sono note: (a) le zone restano tarate su una soglia vecchia — aggiorna_lthr_automatico
# alza la LTHR solo se trova sforzi massimali recenti, e in base non ce ne sono, quindi
# tutte le sedute diventano progressivamente troppo facili; (b) manca un feedback di
# progresso, che e' il primo fattore di abbandono nei blocchi lunghi senza obiettivo.
# CON L'ADOZIONE DELLA POTENZA STRYD il problema raddoppia e vale ANCHE CON GARA a
# calendario: tutti i target %Power dei workout corsa si risolvono sulla CP (campo FTP
# dello sport Run su Intervals.icu) e una CP stantia sposta OGNI qualita' sulla zona
# sbagliata. Stryd/Palladino raccomandano di ricalibrare la CP ogni 4-6 settimane —
# vedi il ramo "con gara" di proponi_test_periodico.
#
# Bibliografia: Friel ('Training Bible') prescrive field test ogni 4-6 settimane nella
# fase Base, proprio per ritarare zone e verificare l'adattamento; Fitzgerald ('80/20')
# raccomanda una gara test breve periodica come stimolo neuromuscolare e motivazionale
# dentro blocchi prevalentemente aerobici; Maffetone (MAF test) e Friel (aerobic
# decoupling Pa:HR < 5%) misurano l'efficienza aerobica a FC costante, che e'
# ESATTAMENTE l'adattamento che una fase di base dovrebbe produrre.
#
# Un test ogni 4 settimane costa 1 sessione di qualita' (che in base c'e' comunque) e
# non intacca il volume aerobico. Oltre le 6 settimane si e' in ritardo: i dati sono
# stantii e le zone quasi certamente sbagliate.
TEST_TARGET_GG  = 28

TEST_DEFICIT_GG = 42

# Rotazione: si cambia disciplina/qualita' misurata ad ogni ciclo, cosi' in ~4 mesi
# tutte le soglie delle 3 discipline sono fresche e si e' misurata anche l'efficienza
# aerobica (che nessun test di soglia cattura).
TEST_ROTAZIONE = [
    {"chiave": "run_tt30", "sport": "Run", "sessione_tipo": "TestRace",
     # MODIFICA (06/10/2026): niente Stryd (Simone non lo usa, e molti utenti non l'hanno):
     # il test misura LTHR e passo soglia di corsa.
     "nome": "Test 30' Time Trial corsa (LTHR + passo soglia)",
     "protocollo": "15' riscaldamento progressivo, poi 30' a ritmo massimo sostenibile "
                   "su percorso pianeggiante e regolare (no traffico, no salite), 10' defaticamento",
     "misura": "LTHR corsa = FC media degli ULTIMI 20' dei 30' (Friel); passo soglia = passo "
               "medio dei 30'. La LTHR viene raccolta in automatico (solo al rialzo)",
     "fonte": "Friel, Training Bible (LTHR e passo soglia da time trial di 30')"},
    # MODIFICA (06/10/2026): per chi ha un misuratore il test misura FTP e LTHR, per chi
    # non ce l'ha solo la LTHR bici (rullo o strada senza stop vanno entrambi bene).
    {"chiave": "bike_ftp20", "sport": "Bike", "sessione_tipo": "BikeCross",
     "nome": "Test 20' bici (FTP e LTHR)",
     "protocollo": "Su strada senza stop o sul rullo. 20' riscaldamento con 3x1' allunghi, "
                   "5' blowout all-out, 5' facile, poi 20' al massimo sostenibile, costante, "
                   "10' defaticamento. Con misuratore di potenza si misura anche la FTP",
     "misura": "FTP bici = 95% della potenza media dei 20' (Coggan): e' il valore su cui si "
               "risolvono TUTTI i target %Power delle uscite outdoor. LTHR bici = FC media dei "
               "20'. Entrambi vengono raccolti in automatico dagli algoritmi (solo al rialzo); "
               "il test toglie inoltre alla FTP lo stato di stima PROVVISORIA",
     "fonte": "Allen & Coggan, Training and Racing with a Power Meter"},
    {"chiave": "swim_css", "sport": "Swim", "sessione_tipo": "Swim",
     "nome": "Test CSS nuoto 400+200",
     "protocollo": "400m riscaldamento tecnico, 400m a tutta, 5' recupero completo, "
                   "200m a tutta, 200m sciolto",
     "misura": "CSS (passo soglia nuoto) = 200 / (t400 - t200) in metri/secondo. "
               "Da qui si tarano tutte le ripetute nuoto",
     "fonte": "Swim Smooth / Wakayoshi — critical swim speed"},
    {"chiave": "run_maf", "sport": "Run", "sessione_tipo": "TestRace",
     "nome": "Test aerobico MAF 8km",
     "protocollo": "15' riscaldamento, poi 8km su percorso FISSO (stesso ogni volta) a FC "
                   "COSTANTE in alto Z2, senza mai spingere, 10' defaticamento",
     "misura": "Passo medio a pari FC e decoupling Pa:HR (deve restare sotto il 5%). "
               "Se il passo migliora a FC identica, la base aerobica sta funzionando",
     "fonte": "Maffetone (MAF test) + Friel (aerobic decoupling)"},
]

def giorni_da_ultimo_test(state, today_str):
    """Giorni dall'ultimo test periodico realmente pianificato, None se mai fatto."""
    ultimo = (state.get("ultimo_test") or {}).get("data")
    if not ultimo:
        return None
    try:
        return (datetime.strptime(today_str, "%Y-%m-%d")
                - datetime.strptime(ultimo, "%Y-%m-%d")).days
    except ValueError:
        return None

def prossimo_test_in_rotazione(state):
    """Voce successiva della rotazione rispetto all'ultimo test eseguito.
    Alla prima esecuzione parte dal test di soglia corsa (il piu' informativo:
    e' l'unico che ritara le zone della disciplina primaria)."""
    ultima = (state.get("ultimo_test") or {}).get("chiave")
    chiavi = [t["chiave"] for t in TEST_ROTAZIONE]
    # PRECEDENZA AL TEST FTP BICI: e' l'unica soglia dell'atleta senza NESSUN dato
    # storico alle spalle (bici e potenziometro nuovi) e su di essa si risolvono tutti
    # i target %Power delle uscite outdoor. Finche' e' assente o solo stimata — e solo
    # se Simone sta gia' uscendo col misuratore, vedi 'priorita_test' in
    # aggiorna_bike_ftp_automatico — scavalca la rotazione. Mai due volte di fila.
    if (state.get("bike_ftp_auto") or {}).get("priorita_test") and ultima != "bike_ftp20":
        return next(t for t in TEST_ROTAZIONE if t["chiave"] == "bike_ftp20")
    if ultima not in chiavi:
        return TEST_ROTAZIONE[0]
    return TEST_ROTAZIONE[(chiavi.index(ultima) + 1) % len(TEST_ROTAZIONE)]

def set_sport_lthr_on_intervals(sport, target_lthr):
    """Scrive la LTHR stimata su Intervals.icu (sport-settings 'Ride'/'Swim') e fa ricalcolare
    le zone HR di quello sport (recalcHrZones=true). Necessario perche' gli step '%HR' nei workout
    vengono risolti dal SERVER sulla LTHR configurata per lo sport dell'attivita', non da un
    valore che calcoliamo solo lato nostro: se quella LTHR su Intervals.icu e' rimasta una
    copia automatica della Run LTHR, un range '%HR' su un workout Bici/Nuoto esce sulle zone Corsa
    (troppo alte per un recupero bici/nuoto)."""
    r = _http().put(
        f"https://intervals.icu/api/v1/athlete/{ATHLETE_ID}/sport-settings/{sport}",
        headers=ICU, params={"recalcHrZones": "true"},
        json={"lthr": target_lthr})
    if r.status_code not in (200, 204):
        print(f"  ATTENZIONE: scrittura LTHR {sport} su Intervals.icu fallita ({r.status_code}) "
              f"— verifica che l'API key abbia lo scope SETTINGS:WRITE")
        return False
    return True

# ── AUTODETECT LTHR DA ATTIVITA' (periodico, per sport) ──────
# La LTHR deriva (fitness che sale in Base, cali post-infortunio/off-season): se il
# valore configurato su Intervals.icu invecchia, TUTTE le zone HR — e quindi ogni
# step '%HR' dei workout, risolti server-side su quelle zone — puntano al bersaglio
# sbagliato. Qui, a cadenza congrua (un mesociclo), la soglia di ogni sport viene
# ri-stimata dagli sforzi sostenuti reali e, se serve, scritta DIRETTAMENTE su
# Intervals.icu con ricalcolo zone (set_sport_lthr_on_intervals, recalcHrZones=true).
#
# PRINCIPIO CHIAVE (correzione: la prima versione era troppo conservativa): la
# formula da campo di Friel (LTHR ~ 95% della migliore media FC su 20' — Triathlete's
# Training Bible) vale solo se quei 20' erano uno sforzo MASSIMALE tipo test o gara.
# Negli allenamenti normali — soprattutto in fase Base, dove di proposito non si va
# mai vicino a soglia — la best-20' misura solo "il punto piu' alto a cui si e'
# SCELTO di andare": la formula produce quindi un LIMITE INFERIORE della soglia,
# non la soglia. Trattarla come stima puntuale aveva abbassato la LTHR corsa a 159
# quando una maratona corsa a 164bpm medi dimostrava una soglia ben piu' alta. Da qui:
#   - la stima e' un LOWER BOUND: max(0.95 x best-20', best-60'). La best-60' e' un
#     limite inferiore diretto per definizione (la soglia e' ~ lo sforzo massimo
#     sostenibile un'ora: nessuno tiene 60' una FC sopra soglia), ed e' proprio il
#     canale con cui le gare lunghe (maratona, GF) alzano la stima in modo affidabile
#     anche senza test dedicati;
#   - si aggiorna SOLO AL RIALZO: se il lower bound supera il configurato, la soglia
#     e' certamente almeno li'. L'ASSENZA di sforzi duri NON e' evidenza che la
#     soglia sia scesa: al ribasso non si tocca MAI in automatico — un eventuale
#     ridimensionamento richiede un test (30' tipo time trial) o una gara, che il
#     log suggerisce quando i dati non validano il configurato da troppo tempo.
LTHR_CHECK_DAYS     = 28

                            # reali di soglia si vedono su settimane, non su giorni
LTHR_RETRY_DAYS     = 7

                            # lo sforzo sostenuto che mancava puo' arrivare in ogni momento
LTHR_LOOKBACK_DAYS  = 42

LTHR_MIN_DELTA_BPM  = 2

LTHR_MAX_STEP_BPM   = 6

                            # solo e' quasi certamente un outlier (drift termico, dato
                            # sporco); se la crescita e' reale, converge nei periodi dopo
LTHR_STALE_PCT      = 0.95

                            # c'e' nulla che validi il valore in uso -> suggerisci test soglia
LTHR_PLAUSIBLE_BPM  = (120, 205)

LTHR_SPORTS = {   # sport-settings Intervals.icu -> tipi attivita' che vi afferiscono
    "Run":  {"Run", "TrailRun", "VirtualRun"},
    "Ride": {"Ride", "VirtualRide", "GravelRide", "MountainBikeRide"},
    "Swim": {"Swim", "OpenWaterSwim"},
}

def get_activity_hr_series(activity_id):
    """Serie [(secondi, bpm), ...] dagli streams dell'attivita'. I campioni senza FC
    (dropout sensore) vengono scartati: la media mobile in _best_rolling_hr e'
    time-aware e tollera i buchi."""
    r = _http().get(f"https://intervals.icu/api/v1/activity/{activity_id}/streams",
                     headers=ICU, params={"types": "time,heartrate"})
    if r.status_code != 200:
        return None
    streams = {s.get("type"): s.get("data") for s in r.json() if isinstance(s, dict)}
    t, hr = streams.get("time"), streams.get("heartrate")
    if not t or not hr or len(t) != len(hr):
        return None
    return [(ti, h) for ti, h in zip(t, hr) if h] or None

def _best_rolling_hr(serie, window_sec=1200):
    """Migliore media FC su una finestra di window_sec, con campionamento irregolare
    (pause, auto-pause, dropout): integrale FC*dt a due puntatori. I gap >30\" non
    contribuiscono all'integrale ma allargano la finestra: uno sforzo interrotto da
    pause NON puo' produrre una best-20' gonfiata."""
    if not serie or serie[-1][0] - serie[0][0] < window_sec:
        return None
    best, j, area = None, 0, 0.0   # area = integrale hr*dt sui campioni (j, i]
    for i in range(1, len(serie)):
        dt = serie[i][0] - serie[i-1][0]
        if 0 < dt <= 30:
            area += serie[i][1] * dt
        while serie[i][0] - serie[j][0] > window_sec:
            dt_j = serie[j+1][0] - serie[j][0]
            if 0 < dt_j <= 30:
                area -= serie[j+1][1] * dt_j
            j += 1
        span = serie[i][0] - serie[j][0]
        if span >= window_sec * 0.98:
            media = area / span
            if best is None or media > best:
                best = media
    return best

def stima_lthr_sport(activities, sport, lthr_corrente):
    """LOWER BOUND della LTHR di uno sport dagli sforzi sostenuti del periodo:
    max(0.95 x best-20', best-60') — vedi principio chiave nel commento di sezione.
    Gli streams si scaricano SOLO per le 4 sedute a FC media piu' alta (la best
    vive li', il resto e' traffico inutile). Ritorna (lower_bound|None, nota)."""
    tipi = LTHR_SPORTS[sport]
    # decoupling > 5% = deriva cardiaca (caldo/disidratazione/fatica: Friel Pa:HR): la FC alta
    # di quelle sedute NON e' evidenza di soglia alta, e in estate avrebbe gonfiato la LTHR
    # di +6bpm/mesociclo (il clamp non impedisce l'accumulo). Si scartano quando il campo c'e'.
    cand = [a for a in activities or []
            if (a.get("type") or "") in tipi
            and (a.get("moving_time") or 0) >= 25 * 60
            and (a.get("average_heartrate") or a.get("average_heart_rate"))
            and not ((a.get("decoupling") or 0) > 5)]
    if not cand:
        return None, f"nessuna attivita' {sport} con FC di almeno 25' nel periodo"
    cand.sort(key=lambda a: a.get("average_heartrate") or a.get("average_heart_rate") or 0,
              reverse=True)
    best20, best60 = None, None
    for a in cand[:4]:
        serie = get_activity_hr_series(a.get("id"))
        if not serie:
            continue
        b20 = _best_rolling_hr(serie, 1200)
        b60 = _best_rolling_hr(serie, 3600)
        if b20 and (best20 is None or b20 > best20):
            best20 = b20
        if b60 and (best60 is None or b60 > best60):
            best60 = b60
    if not best20:
        return None, "streams FC non disponibili/insufficienti sulle sedute candidate"
    lb = max(round(best20 * 0.95), round(best60) if best60 else 0)
    if not (LTHR_PLAUSIBLE_BPM[0] <= lb <= LTHR_PLAUSIBLE_BPM[1]):
        return None, f"lower bound {lb}bpm fuori dal range plausibile {LTHR_PLAUSIBLE_BPM}"
    fonte = (f"best-60' {round(best60)}bpm" if best60 and round(best60) >= round(best20 * 0.95)
             else f"0.95 x best-20' {round(best20)}bpm")
    return lb, (f"lower bound da {fonte} "
                f"(best-20' {round(best20)}bpm"
                + (f", best-60' {round(best60)}bpm" if best60 else "")
                + f"; analizzate {min(len(cand), 4)} sedute piu' intense)")

def aggiorna_lthr_automatico(state, today_str):
    """Per ogni sport in LTHR_SPORTS, se il periodo e' scaduto (state['lthr_auto']):
    calcola il lower bound della LTHR dagli sforzi reali e, SOLO se supera il
    configurato oltre il rumore, alza il valore su Intervals.icu con ricalcolo zone
    HR (mai al ribasso: vedi principio chiave sopra). Le attivita' (finestra
    LTHR_LOOKBACK_DAYS) si scaricano solo se almeno uno sport e' da controllare.
    Ritorna (lista_aggiornamenti, state_modificato)."""
    global _ATHLETE_CACHE
    auto     = state.setdefault("lthr_auto", {})
    today_dt = datetime.strptime(today_str, "%Y-%m-%d")

    def _scaduto(rec):
        last = (rec or {}).get("last_check")
        if not last:
            return True
        try:
            passati = (today_dt - datetime.strptime(last, "%Y-%m-%d")).days
        except ValueError:
            return True
        return passati >= (LTHR_CHECK_DAYS if (rec or {}).get("esito") == "ok" else LTHR_RETRY_DAYS)

    da_controllare = [s for s in LTHR_SPORTS if _scaduto(auto.get(s))]
    if not da_controllare:
        return [], False

    acts = get_activities(LTHR_LOOKBACK_DAYS)
    aggiornamenti = []
    for sport in da_controllare:
        corrente = get_lthr(sport=sport)
        est, nota = stima_lthr_sport(acts, sport, corrente)
        if est is None:
            auto[sport] = {"last_check": today_str, "esito": "skip", "nota": nota}
            print(f"  LTHR {sport}: stima saltata — {nota}")
            continue
        # SOLO AL RIALZO (vedi principio chiave): il lower bound sotto il configurato
        # non e' evidenza di soglia scesa, e' solo assenza di sforzi duri nel periodo.
        if corrente and est < corrente + LTHR_MIN_DELTA_BPM:
            stantio = est < corrente * LTHR_STALE_PCT
            auto[sport] = {"last_check": today_str, "esito": "ok", "lthr": corrente,
                           "nota": f"lower bound {est}bpm non supera il configurato {corrente}bpm"}
            print(f"  LTHR {sport}: {corrente}bpm confermata (lower bound {est}bpm — {nota})"
                  + ("\n    💡 Da un mesociclo nessuno sforzo valida questa LTHR: se vuoi un dato "
                     "fresco, inserisci un test soglia (30' tipo time trial, LTHR = FC media "
                     "degli ultimi 20') o una gara: l'algoritmo lo raccogliera' da solo."
                     if stantio else ""))
            continue
        nuovo = est
        if corrente and est - corrente > LTHR_MAX_STEP_BPM:
            nuovo = corrente + LTHR_MAX_STEP_BPM
        if set_sport_lthr_on_intervals(sport, nuovo):
            _ATHLETE_CACHE = None   # i prossimi get_lthr() rileggono i valori appena scritti
            auto[sport] = {"last_check": today_str, "esito": "ok", "lthr": nuovo,
                           "precedente": corrente, "nota": nota}
            aggiornamenti.append({"sport": sport, "da": corrente, "a": nuovo})
            print(f"  LTHR {sport}: {corrente or 'ND'} -> {nuovo}bpm, zone HR ricalcolate — {nota}"
                  + (f" [rialzo clampato a +{LTHR_MAX_STEP_BPM}bpm su lower bound {est}]"
                     if nuovo != est else ""))
        else:
            auto[sport] = {"last_check": today_str, "esito": "errore_scrittura", "nota": nota}
    return aggiornamenti, True

def get_activity_power_series(activity_id):
    """Serie [(secondi, watt), ...] dagli streams dell'attivita'. _best_rolling_hr
    e' agnostica sul valore (integrale valore*dt a due puntatori): si riusa
    identica sui watt, gap e pause gestiti allo stesso modo."""
    r = _http().get(f"https://intervals.icu/api/v1/activity/{activity_id}/streams",
                     headers=ICU, params={"types": "time,watts"})
    if r.status_code != 200:
        return None
    streams = {s.get("type"): s.get("data") for s in r.json() if isinstance(s, dict)}
    t, w = streams.get("time"), streams.get("watts")
    if not t or not w or len(t) != len(w):
        return None
    # BUG FIX (28/08/2026): `if wi` scartava i campioni a 0 W (ruota libera, semafori,
    # discese). In _best_rolling_hr un buco < 30" viene attribuito al campione successivo,
    # quindi la best-20' diventava "best-20' PEDALATI": FTP/CP sovrastimate, scritte su
    # Intervals.icu e usate come riferimento di tutti gli step %FTP. Per la FC il filtro
    # `if h` resta giusto (0 bpm = dropout del sensore); per la potenza lo zero e' un dato.
    return [(ti, wi) for ti, wi in zip(t, w) if wi is not None] or None

# BOOTSTRAP (nessun dato di potenza pregresso, nessuna FTP nota — situazione di
# partenza di Simone). Due strade possibili: (a) aspettare il test FTP 20' della
# rotazione test, lasciando le uscite outdoor senza target di potenza per settimane;
# (b) stimare un valore PROVVISORIO dalla prima uscita utile e raffinarlo. Qui si fa
# (b) + (a) insieme, che e' la pratica corrente sia in letteratura sia sulle piattaforme
# (Coggan: l'FTP si stima anche da sforzi sostenuti registrati, non solo da test
# dedicati; Intervals.icu stessa propone eFTP dalla curva di potenza):
#   1. alla prima uscita >= BIKE_FTP_MIN_EFFORT_MIN con potenza si scrive una FTP
#      PROVVISORIA, cosi' le uscite outdoor hanno subito un bersaglio sensato;
#   2. finche' e' provvisoria la stima si ricontrolla ogni BIKE_FTP_BOOT_DAYS (non ogni
#      mesociclo): nelle prime settimane la vera FTP emerge in fretta e solo al rialzo;
#   3. il test FTP 20' OUTDOOR viene messo in cima alla rotazione test (vedi
#      prossimo_test_in_rotazione) e, una volta eseguito, il valore smette di essere
#      provvisorio e si torna alla cadenza normale di ricontrollo.
# Stessa filosofia dell'autodetect LTHR/CP corsa: la stima e' un LOWER BOUND e si
# aggiorna SOLO AL RIALZO — l'assenza di sforzi duri non e' evidenza di FTP scesa.
BIKE_FTP_MIN_DELTA_W    = 5

BIKE_FTP_MAX_STEP_W     = 15

BIKE_FTP_STALE_PCT      = 0.95

BIKE_FTP_PLAUSIBLE_W    = (80, 450)

BIKE_FTP_MIN_EFFORT_MIN = 25

BIKE_FTP_BOOT_DAYS      = 7

def get_bike_ftp():
    """FTP bici configurata su Intervals.icu (sportSettings 'Ride'), None se assente.
    Nessuna chiamata HTTP aggiuntiva: legge la stessa _ATHLETE_CACHE di get_lthr()."""
    get_lthr()   # popola _ATHLETE_CACHE
    for s in (_ATHLETE_CACHE or {}).get("sportSettings", []):
        if "Ride" in s.get("types", []):
            return s.get("ftp") or None
    return None

def set_bike_ftp_on_intervals(watts):
    """Scrive la FTP bici sullo sport 'Ride' e chiede il ricalcolo delle zone potenza.
    NOTA: anche se il server ignorasse recalcPowerZones, i target restano corretti —
    gli step sono scritti in PERCENTUALE ("91-100% Power"), quindi si risolvono
    direttamente sull'FTP appena scritta e non sui bordi zona memorizzati."""
    r = _http().put(
        f"https://intervals.icu/api/v1/athlete/{ATHLETE_ID}/sport-settings/Ride",
        headers=ICU, params={"recalcPowerZones": "true"}, json={"ftp": watts})
    if r.status_code not in (200, 204):
        print(f"  ATTENZIONE: scrittura FTP bici su Intervals.icu fallita ({r.status_code}) "
              f"— verifica che l'API key abbia lo scope SETTINGS:WRITE")
        return False
    return True

def stima_bike_ftp(activities, ftp_corrente=None):
    """LOWER BOUND della FTP bici dagli sforzi con potenza del periodo:
    max(0.95 x best-20', best-60'). 0.95 x best-20' e' il protocollo FTP classico
    (Allen & Coggan); la best-60' e' un limite inferiore per DEFINIZIONE (l'FTP e' la
    potenza massima sostenibile ~un'ora) ed e' il canale con cui una lunga impegnativa
    alza la stima anche senza test. Gli streams si scaricano solo per le 3 uscite a
    potenza media piu' alta (la best vive li'). Ritorna (lb|None, nota, ha_potenza)."""
    # device_watts=False = potenza STIMATA (Strava/Intervals senza misuratore): non e' un dato
    # del potenziometro e non deve mai entrare nella FTP.
    rides = [a for a in activities or []
             if (a.get("type") or "") in LTHR_SPORTS["Ride"]
             and (a.get("average_watts") or 0) > 0
             and a.get("device_watts") is not False]
    ha_potenza = bool(rides)   # Simone sta gia' uscendo col potenziometro
    cand = [a for a in rides
            if (a.get("moving_time") or 0) >= BIKE_FTP_MIN_EFFORT_MIN * 60]
    if not cand:
        return None, (f"nessuna uscita in bici con potenza di almeno "
                      f"{BIKE_FTP_MIN_EFFORT_MIN}' nel periodo"), ha_potenza
    cand.sort(key=lambda a: a.get("average_watts") or 0, reverse=True)
    best20, best60 = None, None
    for a in cand[:3]:
        serie = get_activity_power_series(a.get("id"))
        if not serie:
            continue
        b20 = _best_rolling_hr(serie, 1200)
        b60 = _best_rolling_hr(serie, 3600)
        if b20 and (best20 is None or b20 > best20):
            best20 = b20
        if b60 and (best60 is None or b60 > best60):
            best60 = b60
    if not best20:
        return None, "streams potenza non disponibili/insufficienti sulle uscite candidate", ha_potenza
    lb = max(round(best20 * 0.95), round(best60) if best60 else 0)
    if not (BIKE_FTP_PLAUSIBLE_W[0] <= lb <= BIKE_FTP_PLAUSIBLE_W[1]):
        return None, f"lower bound {lb}W fuori dal range plausibile {BIKE_FTP_PLAUSIBLE_W}", ha_potenza
    fonte = (f"best-60' {round(best60)}W" if best60 and round(best60) >= round(best20 * 0.95)
             else f"0.95 x best-20' {round(best20)}W")
    return lb, (f"lower bound da {fonte} (best-20' {round(best20)}W"
                + (f", best-60' {round(best60)}W" if best60 else "")
                + f"; analizzate {min(len(cand), 3)} uscite a potenza piu' alta)"), ha_potenza

def bike_ftp_stato(state):
    """'assente' | 'provvisoria' | 'testata' — governa sia il prompt (le uscite outdoor
    possono usare i watt solo se una FTP esiste) sia la priorita' del test FTP 20'."""
    rec = state.get("bike_ftp_auto") or {}
    if not get_bike_ftp():
        return "assente"
    return "provvisoria" if rec.get("provvisoria") else "testata"

def aggiorna_bike_ftp_automatico(state, today_str):
    """Come aggiorna_run_cp_automatico ma per la FTP bici, con in piu' il BOOTSTRAP
    (vedi commento di sezione): se non esiste ancora nessuna FTP, la prima stima utile
    viene scritta come PROVVISORIA e ricontrollata ogni BIKE_FTP_BOOT_DAYS finche' il
    test FTP 20' outdoor non la conferma. Ritorna (lista_aggiornamenti, state_dirty)."""
    global _ATHLETE_CACHE
    rec      = state.setdefault("bike_ftp_auto", {})
    today_dt = datetime.strptime(today_str, "%Y-%m-%d")
    corrente = get_bike_ftp()
    # Periodo di ricontrollo: mesociclo quando il dato e' consolidato, settimanale
    # finche' e' assente/provvisorio (fase di calibrazione iniziale).
    consolidata = bool(corrente) and not rec.get("provvisoria") and rec.get("esito") == "ok"
    periodo = LTHR_CHECK_DAYS if consolidata else BIKE_FTP_BOOT_DAYS
    last = rec.get("last_check")
    if last:
        try:
            passati = (today_dt - datetime.strptime(last, "%Y-%m-%d")).days
        except ValueError:
            passati = 10**6
        if passati < periodo:
            return [], False

    est, nota, ha_potenza = stima_bike_ftp(get_activities(LTHR_LOOKBACK_DAYS), corrente)
    # Il test FTP 20' ha senso proporlo SOLO se Simone sta davvero uscendo col
    # potenziometro: senza uscite con watt nel periodo la rotazione test resta normale.
    rec["ha_potenza"]   = ha_potenza
    rec["priorita_test"] = bool(ha_potenza) and (not corrente or rec.get("provvisoria", not corrente))
    if est is None:
        rec.update({"last_check": today_str, "esito": "skip", "nota": nota})
        print(f"  FTP bici: stima saltata — {nota}")
        return [], True

    # Un test FTP 20' realmente pianificato nella finestra di analisi promuove il dato
    # da provvisorio a testato: lo sforzo massimale che mancava adesso c'e'.
    ult = state.get("ultimo_test") or {}
    test_recente = False
    if ult.get("chiave") == "bike_ftp20" and ult.get("data"):
        gg_dal_test = (today_dt - datetime.strptime(ult["data"], "%Y-%m-%d")).days
        test_recente = gg_dal_test <= LTHR_LOOKBACK_DAYS

    if not corrente:
        # BOOTSTRAP: prima FTP in assoluto. Si scrive comunque, anche se nasce da
        # un'uscita non massimale: un bersaglio approssimato per difetto e' molto
        # meglio di nessun bersaglio, e da qui in poi puo' solo salire.
        if set_bike_ftp_on_intervals(est):
            _ATHLETE_CACHE = None
            rec.update({"last_check": today_str, "esito": "ok", "ftp": est,
                        "provvisoria": not test_recente, "nota": nota})
            rec["priorita_test"] = not test_recente
            print(f"  FTP bici: PRIMA STIMA {est}W scritta su Intervals.icu "
                  f"({'da test' if test_recente else 'PROVVISORIA — da confermare col test FTP 20 outdoor'}) — {nota}")
            return [{"da": None, "a": est}], True
        rec.update({"last_check": today_str, "esito": "errore_scrittura", "nota": nota})
        return [], True

    # FTP PROVVISORIA: segue la stima in ENTRAMBE le direzioni (28/08/2026). La regola
    # "solo al rialzo" protegge un valore validato da un test/gara; una FTP provvisoria
    # non e' mai stata validata da nulla, quindi se la stima corretta scende (caso reale:
    # il bug dei campioni a 0 W l'aveva gonfiata) va riscritta al ribasso, altrimenti
    # ogni step %FTP outdoor resta tarato su un numero che nessuno sforzo giustifica.
    # Per forzare questo ricontrollo subito basta retrodatare "last_check" nello stato.
    if (rec.get("provvisoria") and not test_recente
            and est < corrente - BIKE_FTP_MIN_DELTA_W):
        if set_bike_ftp_on_intervals(est):
            _ATHLETE_CACHE = None
            rec.update({"last_check": today_str, "esito": "ok", "ftp": est,
                        "precedente": corrente, "provvisoria": True, "nota": nota})
            rec["priorita_test"] = True
            print(f"  FTP bici: PROVVISORIA ribassata {corrente} -> {est}W (stima corretta "
                  f"con i campioni a 0 W; da confermare col test FTP 20' outdoor) — {nota}")
            return [{"da": corrente, "a": est}], True
        rec.update({"last_check": today_str, "esito": "errore_scrittura", "nota": nota})
        return [], True

    if est < corrente + BIKE_FTP_MIN_DELTA_W:
        stantia = est < corrente * BIKE_FTP_STALE_PCT
        rec.update({"last_check": today_str, "esito": "ok", "ftp": corrente,
                    "provvisoria": rec.get("provvisoria", False) and not test_recente,
                    "nota": f"lower bound {est}W non supera il configurato {corrente}W"})
        print(f"  FTP bici: {corrente}W confermata (lower bound {est}W — {nota})"
              + ("\n    💡 Nel periodo nessuno sforzo valida questa FTP: pianifica il "
                 "test FTP 20' outdoor; al ribasso non si aggiorna mai in automatico."
                 if stantia else ""))
        return [], True

    nuovo = est
    if est - corrente > BIKE_FTP_MAX_STEP_W:
        nuovo = corrente + BIKE_FTP_MAX_STEP_W
    if set_bike_ftp_on_intervals(nuovo):
        _ATHLETE_CACHE = None
        rec.update({"last_check": today_str, "esito": "ok", "ftp": nuovo,
                    "precedente": corrente,
                    "provvisoria": rec.get("provvisoria", False) and not test_recente,
                    "nota": nota})
        rec["priorita_test"] = bool(rec.get("provvisoria"))
        print(f"  FTP bici: {corrente} -> {nuovo}W (tutti i target %Power delle uscite "
              f"outdoor si risolvono sul nuovo valore) — {nota}"
              + (f" [rialzo clampato a +{BIKE_FTP_MAX_STEP_W}W su lower bound {est}]"
                 if nuovo != est else ""))
        return [{"da": corrente, "a": nuovo}], True
    rec.update({"last_check": today_str, "esito": "errore_scrittura", "nota": nota})
    return [], True



# ── CP CORSA CON STRYD (07/10/2026, punto 12: estratto da intervals_coach.py, invariato) ──
# ── AUTODETECT CP CORSA DA POTENZA STRYD (periodico) ─────────
# Stessa architettura e stesso principio dell'autodetect LTHR qui sopra, applicati
# alla critical power di corsa: TUTTI i target %Power dei workout (file .zwo %FTP e
# "%Power" nelle description) vengono risolti dal server Intervals.icu sul campo FTP
# dello sport Run — che con Stryd E' la CP. Se quel valore invecchia, ogni qualita'
# a potenza punta al bersaglio sbagliato, esattamente come le zone HR con una LTHR
# stantia. La stima e' un LOWER BOUND dagli sforzi sostenuti reali:
#   max(best-30' watt, 0.95 x best-20' watt)
# — la CP e' ~ la potenza massima sostenibile 30-40' (Jones et al.; Vance 'Run with
# Power'), quindi la best-30' e' un limite inferiore diretto; 0.95 x best-20' e'
# l'approssimazione FTP-style (Coggan, portata alla corsa da Palladino/Stryd). E'
# il canale con cui i test run_tt30/TestRace e le gare vengono raccolti da soli.
# SOLO AL RIALZO, mai al ribasso in automatico: l'assenza di sforzi duri non e'
# evidenza di CP scesa. Un ribasso reale (detraining, o il passaggio dalla potenza
# nativa Suunto a Stryd, che legge tipicamente piu' basso) richiede il test CP
# dedicato e la conferma manuale del valore su Intervals.icu — il log lo suggerisce
# quando i dati non validano il configurato da troppo tempo.
CP_MIN_DELTA_W  = 3

CP_MAX_STEP_W   = 10

CP_STALE_PCT    = 0.95

CP_PLAUSIBLE_W  = (100, 500)

def get_run_cp():
    """FTP corsa (= CP Stryd) configurata su Intervals.icu, None se assente."""
    get_lthr()   # popola _ATHLETE_CACHE
    for s in (_ATHLETE_CACHE or {}).get("sportSettings", []):
        if "Run" in s.get("types", []):
            return s.get("ftp")
    return None

def set_run_cp_on_intervals(watts):
    """Scrive la CP corsa come FTP dello sport Run su Intervals.icu: e' il valore su
    cui il server risolve gli step %FTP dei .zwo e i '%Power' delle description
    (le zone potenza espresse in percentuale seguono automaticamente)."""
    r = _http().put(
        f"https://intervals.icu/api/v1/athlete/{ATHLETE_ID}/sport-settings/Run",
        headers=ICU, json={"ftp": watts})
    if r.status_code not in (200, 204):
        print(f"  ATTENZIONE: scrittura CP corsa su Intervals.icu fallita ({r.status_code}) "
              f"— verifica che l'API key abbia lo scope SETTINGS:WRITE")
        return False
    return True

def stima_run_cp(activities, cp_corrente):
    """LOWER BOUND della CP corsa dagli sforzi con potenza del periodo. Solo
    attivita' corsa >=25' con potenza media registrata (Stryd); gli streams si
    scaricano solo per le 4 sedute a potenza media piu' alta. Ritorna (lb|None, nota)."""
    cand = [a for a in activities or []
            if (a.get("type") or "") in LTHR_SPORTS["Run"]
            and (a.get("moving_time") or 0) >= 25 * 60
            and (a.get("average_watts") or 0) > 0]
    if not cand:
        return None, "nessuna attivita' corsa con potenza (Stryd) di almeno 25' nel periodo"
    cand.sort(key=lambda a: a.get("average_watts") or 0, reverse=True)
    best20, best30 = None, None
    for a in cand[:4]:
        serie = get_activity_power_series(a.get("id"))
        if not serie:
            continue
        b20 = _best_rolling_hr(serie, 1200)
        b30 = _best_rolling_hr(serie, 1800)
        if b20 and (best20 is None or b20 > best20):
            best20 = b20
        if b30 and (best30 is None or b30 > best30):
            best30 = b30
    if not best20:
        return None, "streams potenza non disponibili/insufficienti sulle sedute candidate"
    lb = max(round(best30) if best30 else 0, round(best20 * 0.95))
    if not (CP_PLAUSIBLE_W[0] <= lb <= CP_PLAUSIBLE_W[1]):
        return None, f"lower bound {lb}W fuori dal range plausibile {CP_PLAUSIBLE_W}"
    fonte = (f"best-30' {round(best30)}W" if best30 and round(best30) >= round(best20 * 0.95)
             else f"0.95 x best-20' {round(best20)}W")
    return lb, (f"lower bound da {fonte} (best-20' {round(best20)}W"
                + (f", best-30' {round(best30)}W" if best30 else "")
                + f"; analizzate {min(len(cand), 4)} sedute a potenza piu' alta)")

def aggiorna_run_cp_automatico(state, today_str):
    """Come aggiorna_lthr_automatico ma per la CP corsa: stessa periodicita'
    (LTHR_CHECK_DAYS/LTHR_RETRY_DAYS, finestra LTHR_LOOKBACK_DAYS), stesso principio
    solo-al-rialzo. Ritorna (lista_aggiornamenti, state_modificato)."""
    global _ATHLETE_CACHE
    rec      = state.setdefault("cp_auto", {})
    today_dt = datetime.strptime(today_str, "%Y-%m-%d")
    last = rec.get("last_check")
    if last:
        try:
            passati = (today_dt - datetime.strptime(last, "%Y-%m-%d")).days
        except ValueError:
            passati = 10**6
        if passati < (LTHR_CHECK_DAYS if rec.get("esito") == "ok" else LTHR_RETRY_DAYS):
            return [], False

    corrente  = get_run_cp()
    est, nota = stima_run_cp(get_activities(LTHR_LOOKBACK_DAYS), corrente)
    if est is None:
        rec.update({"last_check": today_str, "esito": "skip", "nota": nota})
        print(f"  CP corsa: stima saltata — {nota}")
        return [], True
    if corrente and est < corrente + CP_MIN_DELTA_W:
        stantio = est < corrente * CP_STALE_PCT
        rec.update({"last_check": today_str, "esito": "ok", "cp": corrente,
                    "nota": f"lower bound {est}W non supera il configurato {corrente}W"})
        print(f"  CP corsa: {corrente}W confermata (lower bound {est}W — {nota})"
              + ("\n    💡 Da un mesociclo nessuno sforzo valida questa CP (o la fonte "
                 "potenza e' cambiata, es. passaggio a Stryd): pianifica il test CP "
                 "(30' TT — run_tt30); al ribasso non si aggiorna mai in automatico."
                 if stantio else ""))
        return [], True
    nuovo = est
    if corrente and est - corrente > CP_MAX_STEP_W:
        nuovo = corrente + CP_MAX_STEP_W
    if set_run_cp_on_intervals(nuovo):
        _ATHLETE_CACHE = None   # eventuali riletture vedono il valore appena scritto
        rec.update({"last_check": today_str, "esito": "ok", "cp": nuovo,
                    "precedente": corrente, "nota": nota})
        print(f"  CP corsa: {corrente or 'ND'} -> {nuovo}W (FTP Run aggiornata: tutti i "
              f"target %Power si risolvono sul nuovo valore) — {nota}"
              + (f" [rialzo clampato a +{CP_MAX_STEP_W}W su lower bound {est}]"
                 if nuovo != est else ""))
        return [{"da": corrente, "a": nuovo}], True
    rec.update({"last_check": today_str, "esito": "errore_scrittura", "nota": nota})
    return [], True

# ── EVENTI DEI TEST (06/10/2026 — nuovo, roadmap punto 4) ──────────────────────────
# Sintassi delle altre sedute (sedute.py): step con durata o distanza, una sola unita' di
# target per evento, nuoto con "% Pace" e "Press lap". Gli step di test non hanno target:
# si va a sensazione, al massimo sostenibile; il dato lo raccolgono gli algoritmi.
_DESCRIZIONI_TEST = {
    "run_tt30": ("Run", 55, "[[tipo:TestRace]]\n"
                 "- 15m Z2 HR intensity=warmup (progressivo, + 3 allunghi)\n\n"
                 "- 30m intensity=active (TEST: 30' al massimo sostenibile, regolare, percorso piano)\n\n"
                 "- 10m Z1 HR intensity=cooldown (post: stretching)\n"),
    "run_maf": ("Run", 70, "[[tipo:TestRace]]\n"
                "- 15m Z1 HR intensity=warmup\n\n"
                "- 8km Z2 HR intensity=active (TEST MAF: FC costante in alto Z2, percorso fisso)\n\n"
                "- 10m Z1 HR intensity=cooldown (post: stretching)\n"),
    "bike_ftp20": ("Ride", 70, "[[tipo:BikeCross]]\n"
                   "- 17m Z2 HR intensity=warmup\n\n"
                   "3x\n- 1m intensity=active (allungo)\n- 1m Z1 HR intensity=recovery\n\n"
                   "- 5m intensity=active (blowout: tutto)\n"
                   "- 5m Z1 HR intensity=recovery\n\n"
                   "- 20m intensity=active (TEST: 20' al massimo sostenibile, costante)\n\n"
                   "- 10m Z1 HR intensity=cooldown\n"),
    "swim_css": ("Swim", 45, "[[tipo:Swim]]\n"
                 "- riscaldamento tecnico 400mtr 68-77% Pace intensity=warmup\n"
                 "- pausa al muro 60s intensity=rest\n\n"
                 "- 400mtr intensity=active (TEST CSS: a tutta, cronometra)\n"
                 "- recupero completo 5m intensity=rest\n\n"
                 "- 200mtr intensity=active (TEST CSS: a tutta, cronometra)\n"
                 "- pausa al muro 60s intensity=rest\n\n"
                 "- Press lap sciolto 200mtr 68-77% Pace intensity=cooldown\n"),
}
# test -> chiave della seduta del coach che sostituisce
SEDUTA_DEL_TEST = {"run_tt30": "corsa_chiave", "bike_ftp20": "bici_chiave",
                   "swim_css": "nuoto_chiave", "run_maf": "corsa_supporto"}


def eventi_test(test, data_str, stryd=False):
    sport, minuti, desc = _DESCRIZIONI_TEST[test["chiave"]]
    if stryd and test["chiave"] == "run_tt30":   # 07/10/2026 (punto 12)
        test = dict(test, misura=test["misura"] + ". Con Stryd: CP = potenza media dei 30', raccolta "
                                                  "in automatico (solo al rialzo)")
    # Il "%" nelle note verrebbe letto da Intervals.icu come target in potenza: si scrive
    # "per cento" (test bici: "95% della potenza media" -> target misti HR/Power).
    note = (f"\n{test['protocollo']}\nMisura: {test['misura']}\n").replace("%", " per cento")
    return [{"category": "WORKOUT", "start_date_local": f"{data_str}T00:00:00", "type": sport,
             "name": test["nome"], "description": desc + note, "moving_time": minuti * 60,
             "external_id": f"coach:Test:{data_str}"}]


# ── ELABORAZIONE DEI TEST (06/10/2026 — nuovo, roadmap punto 4) ───────────────────
# LTHR e FTP si aggiornano dagli algoritmi qui sopra. Il passo soglia di corsa e il CSS
# vengono dai test: il test E' la misura, quindi vale anche al ribasso (a differenza
# degli algoritmi automatici, che salgono e basta). Una variazione oltre il 15% non si
# scrive: e' piu' probabile un GPS sbagliato o un test interrotto che un cambiamento vero.
PASSO_VARIAZIONE_MAX = 0.15
PASSO_CORSA_PLAUSIBILE = (2.0, 6.5)     # m/s (8:20/km - 2:34/km)
CSS_PLAUSIBILE = (0.6, 2.0)             # m/s (2:47/100m - 0:50/100m)
TEST_GIORNI_ATTESA = 3                  # dopo, un test non trovato si considera saltato


def _passo_txt(m_s, metri, unita):
    sec = round(metri / m_s)
    return f"{sec // 60}:{sec % 60:02d}/{unita}"


def get_activity_speed_series(activity_id):
    """[(secondi, m/s)] dallo stream velocity_smooth dell'attivita'."""
    r = _http().get(f"https://intervals.icu/api/v1/activity/{activity_id}/streams",
                    headers=ICU, params={"types": "time,velocity_smooth"}, timeout=30)
    if r.status_code != 200:
        return None
    streams = {x.get("type"): x.get("data") for x in r.json() if isinstance(x, dict)}
    t, v = streams.get("time"), streams.get("velocity_smooth")
    if not t or not v or len(t) != len(v):
        return None
    return [(ti, vi) for ti, vi in zip(t, v) if vi is not None] or None


def _soglia_corrente(sport, campo):
    get_lthr(sport)     # popola la cache dell'atleta
    for x in (_ATHLETE_CACHE or {}).get("sportSettings", []):
        if sport in x.get("types", []):
            return x.get(campo)
    return None


def scrivi_soglia(sport, campo, valore):
    r = _http().put(f"https://intervals.icu/api/v1/athlete/{ATHLETE_ID}/sport-settings/{sport}",
                    headers=ICU, json={campo: valore}, timeout=30)
    return r.status_code in (200, 204)


def test_da_completare(stato):
    """Il test CSS gia' svolto di cui l'app deve chiedere i tempi (o None)."""
    t = (stato or {}).get("ultimo_test") or {}
    if t.get("chiave") == "swim_css" and t.get("attesa_tempi"):
        return {"chiave": "swim_css", "data": t.get("data")}
    return None


def elabora_test(stato, oggi_str):
    """Dopo la data del test: passo soglia di corsa dal 30' (passo medio dei 30' migliori),
    richiesta dei tempi per il CSS. Una sola volta per test. Righe per gli avvisi."""
    t = (stato or {}).get("ultimo_test") or {}
    if not t.get("data") or t.get("elaborato") or t["data"] > oggi_str:
        return []
    righe = []
    if t.get("chiave") == "swim_css":
        if not t.get("attesa_tempi"):
            t["attesa_tempi"] = True
            righe.append("test CSS svolto: inserisci nell'app i tempi dei 400 e dei 200 metri")
        return righe
    if t.get("chiave") != "run_tt30":
        t["elaborato"] = True
        return righe
    giorno = t["data"]
    cand = [a for a in _ATTIVITA if (a.get("type") or "") in LTHR_SPORTS["Run"]
            and (a.get("start_date_local") or "")[:10] == giorno
            and (a.get("moving_time") or 0) >= 30 * 60]
    if not cand:
        if (datetime.strptime(oggi_str, "%Y-%m-%d") - datetime.strptime(giorno, "%Y-%m-%d")).days \
                > TEST_GIORNI_ATTESA:
            t["elaborato"] = True
            righe.append(f"test di corsa del {giorno} non trovato tra le attivita': passo soglia invariato")
        return righe
    serie = get_activity_speed_series(max(cand, key=lambda a: a.get("moving_time") or 0).get("id"))
    v = _best_rolling_hr(serie, 1800) if serie else None
    t["elaborato"] = True
    if not v or not (PASSO_CORSA_PLAUSIBILE[0] <= v <= PASSO_CORSA_PLAUSIBILE[1]):
        righe.append(f"test di corsa del {giorno}: passo non misurabile, passo soglia invariato")
        return righe
    v = round(v, 3)
    corrente = _soglia_corrente("Run", "threshold_pace")
    if corrente and abs(v - corrente) / corrente > PASSO_VARIAZIONE_MAX:
        righe.append(f"test di corsa del {giorno}: passo {_passo_txt(v, 1000, 'km')} troppo diverso "
                     f"dall'attuale {_passo_txt(corrente, 1000, 'km')} (oltre il 15%): non scritto, "
                     f"conferma tu il valore su Intervals.icu")
        return righe
    if scrivi_soglia("Run", "threshold_pace", v):
        t["passo_soglia"] = v
        righe.append(f"passo soglia corsa {_passo_txt(v, 1000, 'km')} dal test del {giorno}, "
                     f"aggiornato su Intervals.icu")
    else:
        righe.append(f"test di corsa del {giorno}: scrittura del passo soglia non riuscita")
    return righe


def css_da_tempi(t400, t200):
    """CSS in m/s dai tempi in secondi (Swim Smooth / Wakayoshi), None se incoerenti."""
    if not all(isinstance(x, (int, float)) and x > 0 for x in (t400, t200)):
        return None
    if not 1.8 * t200 < t400 < 2.6 * t200:       # 400 a tutta vs 200 a tutta: rapporto tipico
        return None
    css = 200 / (t400 - t200)
    return css if CSS_PLAUSIBILE[0] <= css <= CSS_PLAUSIBILE[1] else None
