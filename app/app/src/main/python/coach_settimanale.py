#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
COACH SETTIMANALE MULTISPORT — generatore deterministico di sedute su Intervals.icu.

COSA FA
-------
Una volta a settimana (cron del lunedi') costruisce la settimana di allenamento e la
scrive sul calendario Intervals.icu. Nessun modello linguistico, nessun prompt: la
programmazione esce dalle tabelle di "Settimana tipo e periodizzazione multisport"
(file Word) e i numeri arrivano da Intervals.icu (gare, wellness, CTL/ATL, attivita')
e da Oura (HRV, FC a riposo, sonno, readiness).

LE TRE FONTI DI VERITA'
-----------------------
1. WORD  -> struttura della settimana, main set per distanza e per fase, regole di
            scaling (durata = taglia blocchi interi; fatica = converti in aerobico),
            lunghezza del ciclo, tabelle di volume, scarico ogni 4 settimane, taper.
2. INTERVALS.ICU -> gare a calendario (RACE_A/B/C) = periodizzazione; wellness
            (CTL/ATL/rampa); attivita' delle ultime settimane = volume realmente
            sostenuto; eventi HOLIDAY/SICK/INJURED = giorni non disponibili.
3. OURA  -> HRV, FC a riposo, sonno, readiness = banda decisionale verde/giallo/rosso,
            che decide se ridurre l'intensita' di una seduta e se serve un riposo.

PRINCIPIO DI PROGETTO (ereditato da intervals_coach.py)
-------------------------------------------------------
Tutta la logica sta in funzioni PURE e testabili; la rete vive solo nel livello di
fetch e in quello di scrittura. Ogni regola del Word e ogni vincolo personale ha un
test corrispondente in test_coach_settimanale.py: se una regola non ha un test, non
e' dimostrato che sia implementata.

IDEMPOTENZA
-----------
Ogni evento nasce con un external_id deterministico (`sw:<tipo>:<data>`): rilanciare
lo script sulla stessa settimana non crea duplicati, aggiorna solo cio' che e'
cambiato. Le giornate gia' passate non si toccano mai: una seduta saltata e' persa e
non si insegue (principio dell'atleta).

USO
---
    python3 coach_settimanale.py                  # scrive la settimana corrente
    python3 coach_settimanale.py --dry-run        # stampa e basta, nessuna scrittura
    python3 coach_settimanale.py --lunedi 2026-09-21
    python3 coach_settimanale.py --giornaliero    # rimodula SOLO la seduta di oggi
    python3 coach_settimanale.py --force          # ignora il lock settimanale
"""
from marchio import NOME_APP
import warnings
warnings.filterwarnings("ignore")

import os, re, sys, json, math, base64, argparse
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import requests

import sedute   # libreria e sintassi delle sedute (estratta da intervals_coach.py)
import biometria   # banda biometrica di intervals_coach (formula unica)
import carico      # forma attesa, TSB a domenica, tetto TSS (da intervals_coach)
import soglie      # LTHR/FTP automatiche e test periodici (da intervals_coach)
import palestra    # libreria delle schede di forza (documento di Simone, 06/10/2026)
import caldo       # previsioni nel luogo del telefono e regole dei giorni caldi
import detp        # protocollo DETP con sensore CORE 2 (punto 11)
import traduzioni  # 13b: nomi e note delle sedute nella lingua dell'atleta
import messaggi    # 13d: codici per i testi del riepilogo
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    pass

# ── HTTP: timeout sempre, retry solo sui metodi idempotenti ───────────────────
# Stessa scelta di intervals_coach.py: un POST andato in timeout puo' essere stato
# eseguito comunque dal server, quindi NON si ritenta alla cieca (si rilegge il
# calendario e si guarda l'external_id, vedi scrivi_evento).
_HTTP = requests.Session()
_HTTP.mount("https://", HTTPAdapter(max_retries=Retry(
    total=3, backoff_factor=2, status_forcelist=[429, 500, 502, 503, 504],
    allowed_methods=frozenset(["GET", "PUT", "DELETE"]))))
_session_request = _HTTP.request


def _request_sicura(method, url, **kw):
    kw.setdefault("timeout", 30)
    try:
        return _session_request(method, url, **kw)
    except requests.RequestException as e:
        print(f"  ⚠️ HTTP {method} {url.split('?')[0]}: {e}")
        r = requests.Response()
        r.status_code = 599
        r._content = str(e).encode()
        return r


_HTTP.request = _request_sicura
requests.get, requests.post = _HTTP.get, _HTTP.post
requests.put, requests.delete = _HTTP.put, _HTTP.delete

# ── TIMEZONE ─────────────────────────────────────────────────────────────────
# GitHub Actions / cron girano in UTC: vicino alla mezzanotte locale la data UTC e'
# ancora quella di ieri e la settimana verrebbe scritta sfasata di un giorno.
# 07/10/2026 (distribuzione): fuso del telefono, passato dal cervello (FUSO_ORARIO,
# nome IANA); Europe/Rome solo se manca. Prima era sempre Roma.
TZ_LOCALE = ZoneInfo(os.getenv("FUSO_ORARIO") or "Europe/Rome")


def now_local():
    return datetime.now(TZ_LOCALE).replace(tzinfo=None)


# ── CREDENZIALI ──────────────────────────────────────────────────────────────
API_KEY            = os.getenv("INTERVALS_API_KEY")
ATHLETE_ID         = os.getenv("INTERVALS_ATHLETE_ID")
STATE_FILE         = os.getenv("COACH_SETT_STATE", "coach_settimanale_state.json")

# 06/10/2026: accesso OAuth dall'app (token Bearer, atleta "0" = proprietario del token);
# la chiave API personale resta valida (Basic auth).
INTERVALS_TOKEN    = os.getenv("INTERVALS_TOKEN")
if INTERVALS_TOKEN:
    ATHLETE_ID = ATHLETE_ID or "0"
AUTH = (f"Bearer {INTERVALS_TOKEN}" if INTERVALS_TOKEN else
        f"Basic {base64.b64encode(f'API_KEY:{API_KEY}'.encode()).decode()}")
ICU  = {"Authorization": AUTH, "Content-Type": "application/json"}
ICU_BASE = "https://intervals.icu/api/v1"

# ── PROFILO ATLETA E VINCOLI PERSONALI ───────────────────────────────────────
# Il Word e' scritto per un atleta generico. Questi sono i vincoli reali che
# trasformano la settimana tipo in una settimana ESEGUIBILE. Sono tutti env var:
# cambiare vincolo non richiede di toccare la logica.
FCMAX          = int(os.getenv("FCMAX", 189))     # Karvonen: il metodo che l'atleta usa
FCREST         = int(os.getenv("FCREST", 42))
# 04/10/2026: con il profilo dell'app (FC aggiornate dall'app man mano che raccoglie dati)
# queste valgono piu' di quelle configurate su Intervals.icu.
FC_DA_APP      = os.getenv("FC_DA_APP", "0") == "1"
# Disponibilita' per giorno dal profilo dell'app: minuti massimi, 0 = non disponibile.
_GIORNI_ABBR   = ("lun", "mar", "mer", "gio", "ven", "sab", "dom")
# 08/10/2026 ("+" sul calendario): minuti disponibili per una DATA precisa
# {"YYYY-MM-DD": minuti}, 0 = non disponibile. Ha la precedenza sulla settimana tipo.
# 08/10/2026 (13b): lingua del calendario (it/en/es; zh -> en sull'orologio)
LINGUA = traduzioni.lingua_calendario(os.getenv("LINGUA"))
DISPONIBILITA_DATE = {str(k): int(v) for k, v in
                      json.loads(os.getenv("DISPONIBILITA_DATE", "{}") or "{}").items()
                      if isinstance(v, (int, float)) and v >= 0}
DISPONIBILITA  = {_GIORNI_ABBR.index(k): int(v) for k, v in
                  json.loads(os.getenv("DISPONIBILITA", "{}") or "{}").items()
                  if k in _GIORNI_ABBR and isinstance(v, (int, float))}
DISTANZA_DEF   = os.getenv("DISTANZA_OBIETTIVO", "70.3")   # usata quando non c'e' gara A
MAX_ORE_CARDIO = float(os.getenv("MAX_ORE_CARDIO_SETT", 12.0))  # palestra esclusa
# MODIFICA (04/10/2026 — decisione di Simone): nessun tetto giornaliero nel cervello;
# se una giornata e' lunga e' l'atleta a fermarsi prima. 0 = disattivato.
TETTO_GIORNO   = int(os.getenv("TETTO_GIORNALIERO_MIN", 0))   # palestra INCLUSA
MIN_SEDUTA     = int(os.getenv("MIN_SEDUTA_MIN", 40))      # sotto: non vale lo spostamento
LUNGO_BICI_MAX_NO_GARA = int(os.getenv("LUNGO_BICI_MAX_NO_GARA", 120))
PALESTRA_X_SETT = int(os.getenv("PALESTRA_X_SETT", 2))
BICI_SOLO_INDOOR = os.getenv("BICI_SOLO_INDOOR", "1") not in ("0", "false", "False", "")
NUOTO_DA_SOLO_FERIALE = os.getenv("NUOTO_DA_SOLO_FERIALE", "1") not in ("0", "false", "False", "")
# Il Word mette il lunedi' a riposo completo. L'atleta si allena 7/7 e vuole il riposo
# solo quando lo chiedono i biometrici: SETTIMANA_WORD=1 ripristina il template letterale.
SETTIMANA_WORD = os.getenv("SETTIMANA_WORD", "0") not in ("0", "false", "False", "")

SWIM_POOL_M       = int(os.getenv("SWIM_POOL_LEN_M", 25))
SWIM_PACE_FALLBACK = int(os.getenv("SWIM_PACE_SEC_100M", 120))
SWIM_PACE_PLAUSIBILE = (75, 210)

GIORNI_IT = ["Lunedi", "Martedi", "Mercoledi", "Giovedi", "Venerdi", "Sabato", "Domenica"]


# ── ZONE DI FREQUENZA CARDIACA: KARVONEN (%HRR) ──────────────────────────────
# Il Word parla in Z1-Z5 e RPE. Le zone Z1-Z5 qui sono quelle di Karvonen
# (riserva cardiaca), non le %LTHR di Intervals.icu: sono quelle che l'atleta usa e
# riconosce, ed e' l'unica scala in cui "Z2" significa davvero la stessa cosa per lui
# e per il Word. Le percentuali di HRR sono quelle classiche 50/60/70/80/90.
# Le soglie NON sono il 50/60/70/80/90 da manuale: sono quelle che l'atleta usa
# davvero e che con FCmax 189 / FCrest 42 restituiscono esattamente le sue zone
# (Z1 130-145, Z2 146-160, Z3 161-170, Z4 171-179, Z5 180-189). Il limite inferiore di
# ogni zona e' il superiore della precedente +1 bpm, cosi' le fasce non si sovrappongono.
KARVONEN_PCT = {"Z1": (0.60, 0.70), "Z2": (0.70, 0.80), "Z3": (0.80, 0.87),
                "Z4": (0.87, 0.93), "Z5": (0.93, 1.00)}
# Corrispondenza Word: RPE <-> zona (tabella "Legenda e scala di intensita'")
RPE_ZONA = {3: "Z1", 4: "Z1", 5: "Z2", 6: "Z2", 7: "Z3", 8: "Z4", 9: "Z4", 10: "Z5"}
# Ritmo gara per distanza (stessa tabella): Olimpico ~ Z4 basso, 70.3 ~ Z3,
# Full ~ Z2 alto / Z3 basso.
RITMO_GARA_ZONA = {"olimpico": "Z4", "70.3": "Z3", "full": "Z3"}

# 2026-09-14 — Schema Friel in %LTHR: e' quello configurato sul profilo Intervals.icu
# dell'atleta (7 zone, LTHR corsa 171bpm, le altre discipline regolate con i criteri di
# Friel rispetto alla corsa). E' la scala in cui sono scritti i target degli step, vedi
# bpm(). Le zone Karvonen sopra restano la scala di RAGIONAMENTO del piano (il Word
# parla in Z1-Z5 e RPE); questa e' la loro traduzione nella sola sintassi che il
# Workout Builder sappia leggere. La Z5 del piano copre Z5a+Z5b di Friel: e' usata per
# gli sprint corti, non per la capacita' anaerobica pura.
FRIEL_LTHR_PCT = {"Z1": (0, 80), "Z2": (81, 89), "Z3": (90, 93),
                  "Z4": (94, 99), "Z5": (100, 106)}


def zone_karvonen(fcmax=None, fcrest=None):
    """Z1-Z5 in bpm con il metodo della riserva cardiaca: bpm = FCrest + pct*(FCmax-FCrest)."""
    fcmax = fcmax or FCMAX
    fcrest = fcrest or FCREST
    hrr = max(1, fcmax - fcrest)
    out, prec_hi = {}, None
    for z in ("Z1", "Z2", "Z3", "Z4", "Z5"):
        lo_pct, hi_pct = KARVONEN_PCT[z]
        lo = int(round(fcrest + lo_pct * hrr))
        hi = int(round(fcrest + hi_pct * hrr))
        if prec_hi is not None:
            lo = max(lo, prec_hi + 1)
        out[z] = (lo, max(lo, hi))
        prec_hi = out[z][1]
    return out


def bpm(zona, zone=None):
    """'Z2' -> '81-89% LTHR'. E' l'UNICA unita' di target usata nelle descrizioni di
    corsa e bici: mescolare unita' nella stessa sessione manda in confusione sia
    Intervals.icu sia l'orologio (vedi REGOLA UNITA' TARGET UNICA).

    2026-09-14 — BUG FIX, misurato: 50 step su 50 di corsa e bici uscivano con un target
    in bpm assoluti ('- 10m 130-145bpm intensity=warmup'). Il Workout Builder di
    Intervals.icu NON interpreta i bpm assoluti: ammette zone ('Z2 HR') o percentuali
    ('81-89% LTHR', '70% HR'). Conseguenza osservata a calendario e sull'orologio: la
    sessione arrivava con durate e testo ma SENZA target, cioe' come una sessione
    testuale. Stesso difetto gia' pagato e documentato in intervals_coach.py.
    Le percentuali sono lo schema Friel (FRIEL_LTHR_PCT), lo stesso a 7 zone configurato
    sul profilo dell'atleta. Scriverle esplicite invece di usare il token nudo 'Zn HR'
    e' voluto: il token userebbe lo schema zone che Intervals.icu ha per QUEL sport, e
    una LTHR bici o nuoto mai testata e' spesso solo una copia automatica di quella
    corsa. La percentuale, invece, si risolve sulla soglia dello sport dell'evento —
    che l'atleta ha gia' regolato con i criteri di Friel rispetto alla corsa — quindi
    lo stesso testo produce il bpm corretto su Run, Ride e Swim senza sapere lo sport.
    Il parametro `zone` resta nella firma per non toccare i punti di chiamata.
    """
    lo, hi = FRIEL_LTHR_PCT[zona]
    return f"{lo}-{hi}% LTHR"


def bpm_range(z_lo, z_hi, zone=None):
    """Fascia a cavallo di due zone, es. Z1-Z2 -> '0-89% LTHR'. Un range unico, non due
    zone concatenate: la concatenazione non e' sintassi valida nel Workout Builder."""
    return f"{FRIEL_LTHR_PCT[z_lo][0]}-{FRIEL_LTHR_PCT[z_hi][1]}% LTHR"


# ── LETTURA DATI: INTERVALS.ICU ──────────────────────────────────────────────
_CACHE = {}


def _get_icu(path, **params):
    r = requests.get(f"{ICU_BASE}/athlete/{ATHLETE_ID}/{path}", headers=ICU, params=params)
    if r.status_code != 200:
        print(f"  ⚠️ ICU {path}: HTTP {r.status_code} — {r.text[:120]}")
        return None
    try:
        return r.json()
    except ValueError:
        return None


def get_events(oldest, newest):
    """Tutti gli eventi a calendario nella finestra (workout pianificati, gare, note,
    HOLIDAY/SICK/INJURED)."""
    key = ("events", oldest, newest)
    if key in _CACHE:
        return _CACHE[key]
    data = _get_icu("events", oldest=oldest, newest=newest) or []
    _CACHE[key] = [e for e in data if isinstance(e, dict)]
    return _CACHE[key]


def get_races(mesi_avanti=18, look_back_days=10):
    """Gare dal calendario: e' questa la sorgente della periodizzazione.
    look_back_days: una gara corsa ieri deve restare visibile, altrimenti la fase
    'recupero post-gara' non scatterebbe mai."""
    oldest = (now_local() - timedelta(days=look_back_days)).strftime("%Y-%m-%d")
    newest = (now_local() + timedelta(days=mesi_avanti * 30)).strftime("%Y-%m-%d")
    races = []
    for e in get_events(oldest, newest):
        cat = e.get("category", "") or ""
        if not cat.startswith("RACE"):
            continue
        races.append({
            "id": e.get("id"),
            "name": e.get("name", "") or "",
            "date": (e.get("start_date_local", "") or "")[:10],
            "category": cat,
            "priorita": {"RACE_A": 1, "RACE_B": 2, "RACE_C": 3}.get(cat, 9),
            "dist_km": round((e.get("distance") or 0) / 1000, 1),
            "type": e.get("type") or "",
            "desc": (e.get("description") or "")[:300],
        })
    races.sort(key=lambda x: x["date"])
    return races


def get_wellness(days=42):
    oldest = (now_local() - timedelta(days=days)).strftime("%Y-%m-%d")
    newest = now_local().strftime("%Y-%m-%d")
    data = _get_icu("wellness", oldest=oldest, newest=newest) or []
    # ordinamento esplicito: piu' consumatori leggono "l'ultimo giorno" come [-1] e
    # l'ordine di risposta dell'API non e' garantito da contratto.
    return sorted([w for w in data if isinstance(w, dict)],
                  key=lambda x: x.get("id") or x.get("date") or "")


def get_activities(days=42):
    key = ("acts", days)
    if key in _CACHE:
        return _CACHE[key]
    oldest = (now_local() - timedelta(days=days)).strftime("%Y-%m-%d")
    newest = now_local().strftime("%Y-%m-%d")
    _CACHE[key] = _get_icu("activities", oldest=oldest, newest=newest) or []
    return _CACHE[key]


def get_athlete_hr():
    """FCmax / FCrest come configurate su Intervals.icu, con fallback sulle env.
    L'atleta vuole che i suoi riferimenti vivano su Intervals.icu ed e' da li' che
    devono arrivare: le env sono solo la rete di sicurezza."""
    if FC_DA_APP:
        return FCMAX, FCREST
    r = requests.get(f"{ICU_BASE}/athlete/{ATHLETE_ID}", headers=ICU)
    fcmax, fcrest = FCMAX, FCREST
    if r.status_code == 200:
        try:
            a = r.json() or {}
        except ValueError:
            a = {}
        for chiave in ("max_hr", "maxHr", "hr_max"):
            if a.get(chiave):
                fcmax = int(a[chiave])
                break
        for chiave in ("resting_hr", "restingHr", "hr_rest"):
            if a.get(chiave):
                fcrest = int(a[chiave])
                break
    if not (120 <= fcmax <= 220) or not (30 <= fcrest <= 90) or fcrest >= fcmax:
        return FCMAX, FCREST      # valori implausibili: non si costruiscono zone sopra
    return fcmax, fcrest






# ═════════════════════════════════════════════════════════════════════════════
# 1. BIOMETRICI — la banda decisionale
# ═════════════════════════════════════════════════════════════════════════════
# METODO (Plews & Laursen 2013, Flatt & Esco 2016, Altini/HRV4Training): l'HRV rMSSD
# e' log-normale, quindi baseline, SD e "normal range" si calcolano su Ln(rMSSD); le
# bande sono INDIVIDUALI (baseline +/- 0.5 SD = smallest worthwhile change), non una
# percentuale fissa uguale per tutti. La decisione si prende sulla MEDIA MOBILE a 7
# giorni contro la baseline, mai sul dato di stamattina.

SWC_PCT_MIN, SWC_PCT_MAX = 3.0, 8.0   # forbice di plausibilita' della SWC operativa
BASELINE_SD_GG = 42                   # finestra su cui si stimano baseline e SD
# 5 giorni, non 3: la persistenza conta i VALORI GIORNALIERI sotto un normal range
# costruito per la media 7gg (baseline -0.5 SD dei singoli giorni). Una notte singola ci
# finisce sotto circa una volta su tre anche in piena salute, e tre di fila capitano per
# puro rumore piu' volte al mese. Cinque di fila non sono piu' rumore (p < 1%): e' li'
# che ha senso frenare anche a banda verde. Soglia ereditata da intervals_coach.py.
PERSISTENZA_RIPOSO_GG = 5
RHR_ALLARME_BPM = 5                   # FC riposo 7gg sopra baseline di 5 bpm = conferma
SONNO_MIN_H = 6.0                     # media 7gg sotto: freno sul volume
# Giorni di ritardo oltre i quali il quadro Oura NON e' piu' "di stamattina". La
# rimodulazione giornaliera decide se ridurre la seduta di OGGI: farlo sulla notte di
# ieri sera significa declassare una seduta per uno stato che non esiste piu'. Se
# l'anello non ha ancora sincronizzato non si decide niente, si riprova piu' tardi.
OURA_MAX_RITARDO_GG = int(os.getenv("OURA_MAX_RITARDO_GG", 1))


def oura_utilizzabile(baseline, oggi_str=None, max_ritardo=None):
    """(utilizzabile, motivo). Separata e pura perche' e' la condizione che decide se
    il run del mattino ha senso: va testata, non dedotta dal log."""
    max_ritardo = OURA_MAX_RITARDO_GG if max_ritardo is None else max_ritardo
    b = baseline or {}
    if not b.get("ok"):
        return False, (b.get("nota") or "nessuna baseline HRV disponibile")
    ritardo = b.get("gg_ritardo")
    if ritardo is None:
        return False, "data dell'ultimo dato Oura illeggibile"
    if ritardo >= max_ritardo:
        return False, (f"l'ultimo dato biometrico e' di {ritardo} "
                       f"giorn{'o' if ritardo == 1 else 'i'} fa: la notte non e' ancora "
                       f"arrivata su Intervals.icu")
    return True, "dato biometrico aggiornato a oggi"


def _media_sd(vals):
    if not vals:
        return None, None
    m = sum(vals) / len(vals)
    if len(vals) < 2:
        return m, 0.0
    var = sum((v - m) ** 2 for v in vals) / (len(vals) - 1)
    return m, math.sqrt(var)


def _dt(d):
    try:
        return datetime.strptime(str(d)[:10], "%Y-%m-%d")
    except (TypeError, ValueError):
        return None


def _finestra(serie, fine_dt, giorni):
    """Valori che cadono negli ultimi `giorni` giorni di CALENDARIO terminanti in
    fine_dt. Fondamentale che sia calendario e non 'ultimi N record': con l'anello
    scarico due notti, una media '7 giorni' diventerebbe in silenzio una media su 9."""
    inizio = fine_dt - timedelta(days=giorni - 1)
    return [v for d, v in serie if _dt(d) and inizio <= _dt(d) <= fine_dt]


def calc_baseline_hrv(oura_history, today_str=None):
    """MODIFICA (04/10/2026 — decisione di Simone): una sola formula della banda, quella
    affinata in intervals_coach (biometria.calc_baseline_biometrici), la stessa della
    prontezza nell'app. Qui solo l'adattamento ai nomi che usa questo file. La formula
    storica di questo file e' stata rimossa: su 400 storie casuali dava una banda diversa
    in 1 caso (10 notti: verde invece di giallo)."""
    b = dict(biometria.calc_baseline_biometrici(oura_history or [], today_str=today_str))
    if not b.get("ok"):
        b["banda"] = "grigio"     # convenzione di questo file: nessuna banda = grigio
    b["persistenza_gg"] = b.get("persistenza_gg_sotto")
    b["gg_ritardo"] = b.get("gg_ritardo_oura")
    hist = sorted([d for d in oura_history or [] if d.get("data")], key=lambda d: d["data"])
    sonno = [d.get("sleep_h") for d in hist[-7:] if d.get("sleep_h")]
    readiness = [d.get("readiness") for d in hist[-3:] if d.get("readiness")]
    temp = [d.get("temp_dev") for d in hist[-2:] if d.get("temp_dev") is not None]
    b.update({"sonno_medio_h": round(sum(sonno) / len(sonno), 1) if sonno else None,
              "readiness_media": round(sum(readiness) / len(readiness)) if readiness else None,
              "temp_dev_max": max(temp) if temp else None})
    return b


NOCTALIX_NOTTI_MIN = 14   # sotto queste notti la baseline NoctaliX non e' affidabile


def storia_biometrica(wellness, tag_giorni=None):
    """(serie, fonte) per la banda, dalla wellness di Intervals.icu. NoctaliX (campi
    Noctalix*) decide solo con >= NOCTALIX_NOTTI_MIN notti valide; prima valgono i campi
    standard (hrv/restingHR, sincronizzati da Oura o Garmin). Mai le due serie insieme:
    fascia e anello/orologio hanno livelli assoluti diversi."""
    # 05/10/2026: tag di giorno dell'app sulla serie (esclusione dei confondenti)
    tag_l = [{"data": d, "tags": list(v)} for d, v in (tag_giorni or {}).items() if v]
    bs = biometria.noctalix_history_da_wellness(wellness, tag_l)
    if len(bs) >= NOCTALIX_NOTTI_MIN:
        return bs, f"{NOME_APP}, {len(bs)} notti"
    out = []
    for w in wellness or []:
        d = str(w.get("id") or w.get("date") or "")[:10]
        hrv = w.get("hrv")
        if not d or not isinstance(hrv, (int, float)) or hrv <= 0:
            continue
        sonno = w.get("sleepSecs")
        if (tag_giorni or {}).get(d):
            out.append({"data": d, "hrv_ms": hrv, "resting_hr": w.get("restingHR"),
                        "tags": list(tag_giorni[d]),
                        "sleep_h": round(w["sleepSecs"] / 3600, 1)
                        if isinstance(w.get("sleepSecs"), (int, float)) and w["sleepSecs"] else None,
                        "readiness": w.get("readiness")})
            continue
        out.append({"data": d, "hrv_ms": hrv, "resting_hr": w.get("restingHR"),
                    "sleep_h": round(sonno / 3600, 1) if isinstance(sonno, (int, float)) and sonno else None,
                    "readiness": w.get("readiness")})
    return sorted(out, key=lambda x: x["data"]), f"wellness Intervals.icu, {len(out)} notti"


def stato_forma(wellness):
    """CTL/ATL/TSB e rampa settimanale del CTL dai dati Intervals.icu."""
    w = [x for x in (wellness or []) if x.get("ctl") is not None]
    if not w:
        return {"ok": False, "ctl": None, "atl": None, "tsb": None, "rampa_7gg": None}
    ultimo = w[-1]
    ctl = ultimo.get("ctl") or 0
    atl = ultimo.get("atl") or 0
    prec = next((x for x in reversed(w[:-1])
                 if _dt(x.get("id") or x.get("date")) and _dt(ultimo.get("id") or ultimo.get("date"))
                 and (_dt(ultimo.get("id") or ultimo.get("date")) - _dt(x.get("id") or x.get("date"))).days >= 7),
                None)
    rampa = round(ctl - (prec.get("ctl") or 0), 1) if prec else None
    return {"ok": True, "ctl": round(ctl, 1), "atl": round(atl, 1),
            "tsb": round(ctl - atl, 1), "rampa_7gg": rampa}


# ── CURVA DI PEGGIORAMENTO ───────────────────────────────────────────────────
# E' la stessa curva di intervals_coach.py (fattore_progressione +
# _freno_biometrico_copertura), riportata qui senza cambiarne le soglie: due coach che
# leggono gli stessi biometrici non possono dare due risposte diverse.
#   banda   fattore sul volume     qualita'      palestra
#   verde        1.10                 si'           si'
#   grigio       1.05                 si'           si'
#   giallo       1.00            niente qualita'    si'
#   rosso        0.90            niente qualita'    no (recupero)
# La', quei numeri moltiplicano il VOLUME SOSTENIBILE misurato; qui moltiplicano il
# target di fase, che e' gia' il volume "giusto" della settimana. Si usa quindi la
# stessa curva normalizzata su verde = 1.00, cosi' i rapporti fra le bande restano
# identici (verde:giallo:rosso = 1.10:1.00:0.90).
FATTORE_BANDA_SOSTENIBILE = {"verde": 1.10, "grigio": 1.05, "giallo": 1.00, "rosso": 0.90}
MOD_VOLUME = {b: round(v / 1.10, 2) for b, v in FATTORE_BANDA_SOSTENIBILE.items()}


def curva_peggioramento(baseline, forma=None):
    """(blocca_qualita, blocca_palestra, freni) secondo la curva sopra.
    E' l'unico punto in cui si decide quanto peggiorare: la pianificazione settimanale
    e la rimodulazione giornaliera leggono questa funzione, non due regole diverse."""
    b = baseline or {}
    banda = b.get("banda") or "grigio"
    f = forma or {}
    freni = []

    if (b.get("fc_riposo") or {}).get("allarme"):
        freni.append(f"FC riposo +{(b['fc_riposo'] or {}).get('delta')}bpm sulla baseline")
    if (b.get("persistenza_gg") or 0) >= PERSISTENZA_RIPOSO_GG:
        freni.append(f"HRV sotto il normal range da {b['persistenza_gg']}gg")
    if f.get("rampa_7gg") is not None and f["rampa_7gg"] > 8:
        freni.append(f"rampa CTL +{f['rampa_7gg']}/7gg")
    if f.get("tsb") is not None and f["tsb"] <= -30:
        freni.append(f"TSB {f['tsb']}")

    blocca_qualita = banda in ("giallo", "rosso")
    blocca_palestra = banda == "rosso"
    return blocca_qualita, blocca_palestra, freni


def modulazione_biometrica(baseline, forma, fase=""):
    """Traduce i biometrici in tre decisioni operative, applicando curva_peggioramento:
      - fattore_volume: quanto scalare il monte ore della settimana;
      - max_qualita: quante sedute di qualita' restano (le altre diventano aerobiche);
      - riposo: se la settimana deve contenere un giorno di riposo completo.
    Corrisponde allo "scaling fatica" del Word: la seduta non si elimina, si converte."""
    b = baseline or {}
    banda = b.get("banda", "grigio")
    fattore = MOD_VOLUME.get(banda, 1.0)
    blocca_qualita, blocca_palestra, freni = curva_peggioramento(b, forma)
    mot = list(freni)
    riposo = False

    if banda == "rosso":
        mot.insert(0, f"HRV: media 7gg oltre 1 SD sotto il normal range ({b.get('z_ln')})")
    elif banda == "giallo":
        mot.insert(0, f"HRV: media 7gg sotto il normal range ({b.get('z_ln')} SD)")
    elif banda == "grigio" and b.get("nota"):
        mot.insert(0, b["nota"])

    # Un freno attivo vale come un gradino di banda: e' la regola
    # "fattore = 1.00 se era sopra, altrimenti 0.90" dello script originale.
    if freni:
        fattore = 0.91 if fattore > 0.91 else 0.82

    # In verde non c'e' un tetto al NUMERO di qualita': il limite e' il 10% di alta
    # intensita' sul volume bici+corsa (limita_alta_intensita). Un tetto sul conteggio
    # declasserebbe sedute che il carico regge benissimo.
    max_qualita = 0 if banda == "rosso" else (1 if blocca_qualita else 99)
    if freni and max_qualita > 1:
        max_qualita = 1

    if banda == "rosso" and (b.get("persistenza_gg") or 0) >= PERSISTENZA_RIPOSO_GG:
        riposo = True
        mot.append("banda rossa persistente: la curva biometrica prescrive recupero")

    sonno = b.get("sonno_medio_h")
    if sonno is not None and sonno < SONNO_MIN_H:
        fattore = min(fattore, 0.90)
        mot.append(f"sonno medio 7gg {sonno}h")

    temp = b.get("temp_dev_max")
    ready = b.get("readiness_media")
    if temp is not None and temp >= 0.8:
        riposo = True
        mot.append(f"deviazione temperatura +{temp}°C (possibile infiammazione)")
    elif ready is not None and ready < 60:
        fattore = min(fattore, 0.85)
        max_qualita = min(max_qualita, 1)
        mot.append(f"readiness media {ready}")

    # Il taper non si modula al ribasso due volte: il suo taglio di volume e' gia' nella
    # periodizzazione, e in quella fase un HRV basso e' atteso, non allarmante.
    if fase == "taper":
        fattore = max(fattore, 0.90)

    return {"banda": banda, "fattore_volume": round(fattore, 2),
            "max_qualita": max_qualita, "riposo": riposo,
            "blocca_palestra": blocca_palestra, "motivi": mot}


# ═════════════════════════════════════════════════════════════════════════════
# 2. PERIODIZZAZIONE — dal calendario gare alla fase
# ═════════════════════════════════════════════════════════════════════════════
# Tutte le costanti vengono dalla Parte 2 del Word.

CICLO = {                              # settimane per fase (Word: "Il ciclo per distanza")
    "olimpico": {"base": 6, "build": 6, "peak": 2, "taper": 2},    # 16 settimane
    "70.3":     {"base": 8, "build": 8, "peak": 6, "taper": 2},    # 24 settimane
    "full":     {"base": 10, "build": 10, "peak": 7, "taper": 3},  # 30 settimane
}

VOLUME_H = {                           # (da, a) ore/settimana per fase
    # 2026-09-14 — Peak olimpico da (8, 10) a (9, 10): stesso difetto della Base 70.3,
    # trovato dal test di monotonia. Il Build olimpico finisce a 9h e il Peak partiva da
    # 8h: chi passa in Peak si vedeva arrivare a calendario una settimana piu' leggera
    # della precedente proprio all'ingresso nel blocco piu' duro. Preesistente, latente
    # finche' non metti una gara olimpica a calendario.
    "olimpico": {"base": (5, 7), "build": (7, 9), "peak": (9, 10), "taper": (4, 5)},
    # 2026-09-14 — Base 70.3 alzata da (7, 9) a (8, 10) su richiesta dell'atleta.
    # Prima: a 7.0h di target (tetto della settimana 1 di Base e di tutto il periodo
    # senza gara) la corsa di supporto e il nuoto di supporto atterrano a 35', sotto i
    # 40' di MIN_SEDUTA, e vengono dissolti nelle sedute chiave: sul calendario arriva
    # un lunedi' vuoto e un giovedi' di sola palestra, con 2 bici invece di 3.
    # Da 7.5h in su tutte e 12 le sedute sopravvivono, quindi il fondo del range — non
    # il tetto — e' il numero che decide la struttura. Adesso: settimana 1 a 8.0h,
    # settimana 8 a 10.0h, lunedi' e giovedi' pieni. La settimana di scarico (10 x 0.72
    # = 7.2h) resta sotto la soglia e perde il nuoto di supporto: e' un deload, va bene.
    # 2026-09-14 — Fondo di Build alzato da 9 a 10 nello stesso passaggio. Con Base a
    # (8, 10) l'ultima settimana di Base valeva 10h e la prima di Build 9h: a calendario
    # arrivava un gradino all'ingiu' di un'ora nel punto in cui il carico deve salire, e
    # la rampa non lo intercetta perche' frena le salite, non le discese. Ora la
    # progressione e' monotona: Base 8 -> 10, Build 10 -> 12, Peak 12 -> 14.
    "70.3":     {"base": (8, 10), "build": (10, 12), "peak": (12, 14), "taper": (5, 7)},
    "full":     {"base": (6, 9), "build": (11, 12.5), "peak": (13, 15), "taper": (6, 8)},
}

# 2026-09-14 — Riferimento per la settimana di recupero post-gara, sganciato da
# VOLUME_H: sono i fondi di Base storici. Se domani la Base si alza ancora, il
# recupero non deve seguirla.
VOLUME_RECUPERO_H = {"olimpico": 5, "70.3": 7, "full": 6}

# Taper: fattori sull'ultima settimana piena (Word, tabella "Taper e settimana di gara").
TAPER_FATTORI = {
    "olimpico": {2: 0.65, 1: 0.45},
    "70.3":     {2: 0.65, 1: 0.45},
    "full":     {3: 0.75, 2: 0.60, 1: 0.40},
}

# Ripartizione per disciplina: quote ricavate dalla tabella "Volume settimanale della
# settimana tipo (fase Build)" del Word, al netto di forza e mobilita'.
QUOTE_DISCIPLINA = {
    "olimpico": {"nuoto": 0.22, "bici": 0.39, "corsa": 0.39},
    "70.3":     {"nuoto": 0.21, "bici": 0.42, "corsa": 0.37},
    "full":     {"nuoto": 0.19, "bici": 0.45, "corsa": 0.36},
}

RAMPA_MAX = 1.10          # +10% a settimana sul volume complessivo (Be IronFit)
SCARICO_FATTORE = 0.72    # -28%: la forbice del Word e' -25/-30%
ALTA_INTENSITA_MAX = 0.10  # max 10% del volume combinato bici + corsa
ALTA_INTENSITA_PERPETUO = 0.20   # ciclo continuo senza gare: polarizzazione 80/20
RECUPERO_POST_GARA = {"RACE_A": 7, "RACE_B": 4, "RACE_C": 2}

_PATTERN_DIST = [
    ("full",     [r"\bironman\b(?!\s*70)", r"\bim\b", r"\bfull\b", r"140\.6", r"\bxl\b"]),
    ("70.3",     [r"70\.?3", r"half\s*iron", r"\bhalf\b", r"70,3", r"113", r"middle"]),
    ("olimpico", [r"olimpic", r"olympic", r"\bstandard\b", r"51\.5", r"\bsprint\b"]),
]


def classifica_distanza(gara):
    """Distanza obiettivo dal nome/descrizione della gara, con la distanza di bici
    come ultimo indizio. Non indovina: se non riconosce, torna None e il chiamante
    ricade su DISTANZA_OBIETTIVO."""
    if not gara:
        return None
    testo = f"{gara.get('name','')} {gara.get('desc','')}".lower()
    for dist, patterns in _PATTERN_DIST:
        if any(re.search(p, testo) for p in patterns):
            return dist
    km = gara.get("dist_km") or 0
    if km >= 180:
        return "full"
    if km >= 80:
        return "70.3"
    if km >= 35:
        return "olimpico"
    return None


def gara_obiettivo(races, today_str):
    """La gara che detta la periodizzazione: la prima RACE_A futura; in mancanza, la
    prima gara futura in ordine di data."""
    fut = [g for g in (races or []) if g.get("date", "") >= today_str]
    if not fut:
        return None
    a = [g for g in fut if g["priorita"] == 1]
    return a[0] if a else fut[0]


def gara_appena_corsa(races, today_str):
    """Gara nei giorni immediatamente precedenti: impone recupero proporzionato alla
    categoria (una C non si paga come una A)."""
    oggi = _dt(today_str)
    if not oggi:
        return None
    for g in sorted(races or [], key=lambda x: x["date"], reverse=True):
        d = _dt(g.get("date"))
        if not d or d > oggi:
            continue
        gg = (oggi - d).days
        if gg <= RECUPERO_POST_GARA.get(g["category"], 2):
            return dict(g, giorni_fa=gg)
    return None


def settimane_alla_gara(lunedi_str, gara_date):
    """Settimane PIENE fra il lunedi' della settimana pianificata e la gara.
    0 = la gara cade in questa settimana."""
    lun = _dt(lunedi_str)
    gd = _dt(gara_date)
    if not lun or not gd:
        return None
    return max(0, (gd - lun).days // 7)


def posizione_ciclo(lunedi_str, races, distanza_default=None, pause=None):
    """Restituisce la fase e tutto cio' che ne dipende. E' l'unico punto in cui si
    decide 'dove siamo nel ciclo': tutto il resto legge questo dizionario."""
    dist_def = distanza_default or DISTANZA_DEF
    gara = gara_obiettivo(races, lunedi_str)
    post = gara_appena_corsa(races, lunedi_str)

    if post:
        return {"fase": "recupero", "distanza": classifica_distanza(post) or dist_def,
                "gara": None, "post_gara": post, "settimane_alla_gara": None,
                "idx_fase": 0, "len_fase": 1, "scarico": False,
                "etichetta": f"recupero post-{post['category']} ({post['name']})"}

    if not gara:
        # Il Word: senza gara non cambia la struttura, cambia il contenuto — si resta
        # in modalita' Base con ciclo 3 di carico + 1 di scarico.
        idx, blocco = _settimana_ciclo_libero(lunedi_str, ancora_dopo_pausa(lunedi_str, pause))
        return {"fase": "senza_gara", "distanza": "olimpico", "gara": None,
                "post_gara": None, "settimane_alla_gara": None,
                "idx_fase": idx, "len_fase": 5, "scarico": idx == 4, "blocco": blocco,
                "etichetta": (f"senza gara — ciclo continuo, blocco {blocco}, "
                              + ("scarico" if idx == 4 else f"carico {idx + 1}/4"))}

    dist = classifica_distanza(gara) or dist_def
    wk = settimane_alla_gara(lunedi_str, gara["date"])
    c = CICLO[dist]

    if wk == 0:
        return {"fase": "gara", "distanza": dist, "gara": gara, "post_gara": None,
                "settimane_alla_gara": 0, "idx_fase": 0, "len_fase": 1,
                "scarico": False, "etichetta": f"settimana di gara — {gara['name']}"}

    if wk <= c["taper"]:
        return {"fase": "taper", "distanza": dist, "gara": gara, "post_gara": None,
                "settimane_alla_gara": wk, "idx_fase": c["taper"] - wk,
                "len_fase": c["taper"], "scarico": False,
                "etichetta": f"taper, {wk} settimane alla gara"}

    soglia_peak = c["taper"] + c["peak"]
    soglia_build = soglia_peak + c["build"]
    if wk <= soglia_peak:
        fase, idx, ln_f = "peak", soglia_peak - wk, c["peak"]
    elif wk <= soglia_build:
        fase, idx, ln_f = "build", soglia_build - wk, c["build"]
    else:
        fase, idx, ln_f = "base", max(0, c["base"] - (wk - soglia_build)), c["base"]

    # Scarico ogni 4 settimane: mai in taper, mai in settimana di gara. Si conta
    # sull'indice DENTRO la fase, cosi' ogni blocco chiude con la sua settimana leggera.
    scarico = (idx % 4 == 3)
    return {"fase": fase, "distanza": dist, "gara": gara, "post_gara": None,
            "settimane_alla_gara": wk, "idx_fase": idx, "len_fase": ln_f,
            "scarico": scarico,
            "etichetta": (f"{fase} {idx+1}/{ln_f}" + (" (scarico)" if scarico else "")
                          + f" — {wk} settimane a {gara['name']}")}


def _settimana_ciclo_libero(lunedi_str, ancora=None):
    """(indice 0-4, blocco "A"|"B") nel ciclo continuo senza gare. Ancorato a un lunedi'
    fisso cosi' due run consecutivi non cambiano idea sulla settimana di scarico.
    MODIFICA (04/10/2026 — decisione di Simone): blocchi 4:1 (Load 1-4 + scarico) invece
    di 3+1, e blocchi alterni A (base aerobica + VO2max) / B (soglia + muscular endurance)."""
    lun = _dt(lunedi_str)
    anc = _dt(ancora or os.getenv("ANCORA_CICLO", "2026-01-05"))
    if not lun or not anc:
        return 0, "A"
    sett = (lun - anc).days // 7
    return sett % 5, ("A" if (sett // 5) % 2 == 0 else "B")


def volume_target_h(pos, volume_precedente_h=None):
    """Ore di CARDIO della settimana (palestra e mobilita' escluse: sono fuori dal
    monte ore per decisione dell'atleta). Ordine di applicazione:
      1. tabella di fase del Word, interpolata sulla posizione dentro la fase;
      2. taper: fattore sull'ultima settimana piena;
      3. scarico: -28%;
      4. regola del 10%: mai piu' del +10% sulla settimana precedente REALE;
      5. tetto personale (MAX_ORE_CARDIO)."""
    dist = pos["distanza"]
    fase = pos["fase"]

    if fase == "recupero":
        gg = (pos.get("post_gara") or {}).get("giorni_fa", 0)
        cat = (pos.get("post_gara") or {}).get("category", "RACE_C")
        # 2026-09-14 — Il recupero post-gara NON segue il fondo di Base. Prima leggeva
        # VOLUME_H[dist]["base"][0], quindi alzando la Base 70.3 da 7 a 10 la settimana
        # dopo una gara A saliva da 3.7h a 4.2h e quella dopo una C da 5.8h a 6.6h: piu'
        # carico proprio nei giorni in cui serve meno, per un cambio che riguardava tutt'altro.
        # I valori qui sotto sono i vecchi fondi di Base, congelati: il recupero resta
        # identico a prima per tutte e tre le distanze.
        base_h = VOLUME_RECUPERO_H[dist]
        # Piu' la gara e' recente e importante, piu' la settimana e' leggera.
        quota = {"RACE_A": 0.45, "RACE_B": 0.60, "RACE_C": 0.75}.get(cat, 0.75)
        return round(base_h * min(1.0, quota + 0.08 * gg), 1)

    if fase == "gara":
        # Settimana di gara: il volume non e' piu' la variabile da governare.
        return round(VOLUME_H[dist]["taper"][0] * 0.7, 1)

    if fase == "senza_gara":
        lo, hi = VOLUME_H[dist]["base"]
        target = lo + (hi - lo) * (pos["idx_fase"] / 3)
    elif fase == "taper":
        piena = VOLUME_H[dist]["peak"][1]
        target = piena * TAPER_FATTORI[dist].get(pos["settimane_alla_gara"], 0.5)
    else:
        lo, hi = VOLUME_H[dist][fase]
        prog = pos["idx_fase"] / max(1, pos["len_fase"] - 1) if pos["len_fase"] > 1 else 1.0
        target = lo + (hi - lo) * prog

    if pos.get("scarico"):
        target *= SCARICO_FATTORE

    if volume_precedente_h and volume_precedente_h > 0 and fase not in ("taper", "gara"):
        # La regola del 10% e' un TETTO, non un obiettivo: se la settimana scorsa e'
        # stata molto scarsa non si torna al volume pieno di colpo (Word: se si perde
        # piu' di una settimana si rientra da un livello piu' basso).
        target = min(target, volume_precedente_h * RAMPA_MAX)

    return round(min(target, MAX_ORE_CARDIO), 1)


def volume_reale_settimana(activities, lunedi_str):
    """Ore di cardio realmente fatte nei 7 giorni precedenti al lunedi' pianificato.
    Per il nuoto si usa il tempo di SEDUTA (elapsed) e non il moving time: le tabelle
    di volume delle fonti contano le ore in acqua, recuperi inclusi."""
    lun = _dt(lunedi_str)
    if not lun:
        return None
    inizio, fine = lun - timedelta(days=7), lun - timedelta(days=1)
    tot = 0
    for a in activities or []:
        d = _dt((a.get("start_date_local") or "")[:10])
        if not d or not (inizio <= d <= fine):
            continue
        tipo = (a.get("type") or "")
        if tipo in ("WeightTraining", "Workout", "Yoga"):
            continue                      # la palestra e' fuori dal monte ore cardio
        mov = a.get("moving_time") or 0
        ela = a.get("elapsed_time") or mov
        dur = min(ela, mov * 1.6) if tipo in ("Swim", "OpenWaterSwim") else mov
        tot += dur
    return round(tot / 3600, 1)


# ═════════════════════════════════════════════════════════════════════════════
# 3. LA SETTIMANA TIPO — dal Word alla lista di sedute
# ═════════════════════════════════════════════════════════════════════════════
# Ogni voce del catalogo ha: sport, famiglia (per le quote di disciplina), se e' una
# seduta di QUALITA' (conta nel 10% di alta intensita' e si declassa per prima quando
# i biometrici lo chiedono), durata NOMINALE per distanza presa dalle tabelle del Word,
# durata minima sotto la quale la seduta perde senso, e priorita' (1 = si difende per
# ultima, 5 = e' la prima a essere sacrificata).

CATALOGO = {
    "corsa_supporto":   {"sport": "Run",  "famiglia": "corsa", "qualita": False,
                         "nom": {"olimpico": 40, "70.3": 50, "full": 60}, "min": 30, "prio": 5},
    "nuoto_chiave":     {"sport": "Swim", "famiglia": "nuoto", "qualita": True,
                         "nom": {"olimpico": 45, "70.3": 55, "full": 68}, "min": 35, "prio": 3},
    "bici_chiave":      {"sport": "Ride", "famiglia": "bici",  "qualita": True,
                         "nom": {"olimpico": 60, "70.3": 78, "full": 85}, "min": 45, "prio": 2},
    "nuoto_supporto":   {"sport": "Swim", "famiglia": "nuoto", "qualita": False,
                         "nom": {"olimpico": 45, "70.3": 55, "full": 62}, "min": 30, "prio": 4},
    "corsa_chiave":     {"sport": "Run",  "famiglia": "corsa", "qualita": True,
                         "nom": {"olimpico": 52, "70.3": 65, "full": 72}, "min": 45, "prio": 1},
    "bici_supporto":    {"sport": "Ride", "famiglia": "bici",  "qualita": False,
                         "nom": {"olimpico": 45, "70.3": 62, "full": 80}, "min": 40, "prio": 5},
    "brick_bici":       {"sport": "Ride", "famiglia": "bici",  "qualita": True,
                         "nom": {"olimpico": 68, "70.3": 150, "full": 240}, "min": 60, "prio": 1},
    "brick_corsa":      {"sport": "Run",  "famiglia": "corsa", "qualita": True,
                         "nom": {"olimpico": 18, "70.3": 35, "full": 40}, "min": 10, "prio": 1},
    "lungo_bici":       {"sport": "Ride", "famiglia": "bici",  "qualita": False,
                         "nom": {"olimpico": 75, "70.3": 120, "full": 150}, "min": 60, "prio": 1},
    # Il lungo NON e' una seduta aerobica pura da Build in poi: il Word gli aggiunge
    # 2x10-15' a ritmo gara nella seconda meta'. E' quindi una "qualita'" ai fini del
    # declassamento biometrico, anche se il suo contributo di Z4 e' quasi nullo.
    "lungo_corsa":      {"sport": "Run",  "famiglia": "corsa", "qualita": True,
                         "nom": {"olimpico": 60, "70.3": 95, "full": 135}, "min": 45, "prio": 1},
    "nuoto_rigenerante": {"sport": "Swim", "famiglia": "nuoto", "qualita": False,
                          "nom": {"olimpico": 25, "70.3": 28, "full": 30}, "min": 20, "prio": 6},
    "forza":            {"sport": "WeightTraining", "famiglia": "forza", "qualita": False,
                         "nom": {"olimpico": 30, "70.3": 30, "full": 30}, "min": 25, "prio": 4},
    "mobilita":         {"sport": "Workout", "famiglia": "forza", "qualita": False,
                         "nom": {"olimpico": 25, "70.3": 25, "full": 25}, "min": 15, "prio": 6},
    # Sedute della settimana di gara (durate fissate dal Word, non scalate).
    "gara_richiami":    {"sport": "Run",  "famiglia": "corsa", "qualita": True,
                         "nom": {"olimpico": 45, "70.3": 45, "full": 45}, "min": 45, "prio": 1},
    "gara_nuoto":       {"sport": "Swim", "famiglia": "nuoto", "qualita": True,
                         "nom": {"olimpico": 40, "70.3": 40, "full": 40}, "min": 40, "prio": 1},
    "gara_bici_facile": {"sport": "Ride", "famiglia": "bici",  "qualita": False,
                         "nom": {"olimpico": 60, "70.3": 60, "full": 60}, "min": 30, "prio": 3},
    "gara_attivazione": {"sport": "Run",  "famiglia": "corsa", "qualita": False,
                         "nom": {"olimpico": 30, "70.3": 30, "full": 30}, "min": 20, "prio": 2},
}

# Settimana tipo LETTERALE del Word (Parte 1, tabella iniziale).
SETTIMANA_WORD_TEMPLATE = [
    [],                                          # lunedi': riposo completo
    ["nuoto_chiave", "corsa_supporto"],          # martedi'
    ["bici_chiave", "forza"],                    # mercoledi'
    ["nuoto_supporto", "corsa_chiave"],          # giovedi'
    ["bici_supporto"],                           # venerdi'
    ["brick", "mobilita"],                       # sabato
    ["lungo_corsa", "nuoto_rigenerante"],        # domenica
]

# Settimana ADATTATA ai vincoli reali dell'atleta. Le tre leve del Word restano le
# stesse, cambia solo la collocazione:
#   - si allena 7/7 e non vuole un giorno di riposo programmato -> il lunedi' libero
#     del Word accoglie la corsa di supporto, che era la seconda seduta del martedi';
#   - nei feriali nuoto + altra disciplina e' impraticabile (tranne nuoto + palestra,
#     stesso posto) -> martedi' e giovedi' restano giornate di solo nuoto (+ forza);
#   - la bici resta a tre uscite come nel Word, tutte indoor: chiave il mercoledi',
#     supporto + opener il venerdi' (e' la seduta che apre le gambe per il brick, quindi
#     il giorno giusto e' quello) e brick il sabato. Il venerdi' porta due sedute perche'
#     e' l'unico giorno libero da nuoto in cui l'opener puo' stare prima del sabato: e'
#     la corsa di qualita' a venire prima, la bici e' l'apertura corta che la segue e
#     il tetto giornaliero la accorcia per prima (ha la priorita' piu' bassa);
#   - niente palestra nel giorno del brick.
SETTIMANA_ADATTATA = [
    ["corsa_supporto"],                          # lunedi'
    ["nuoto_chiave"],                            # martedi'
    ["bici_chiave", "forza"],                    # mercoledi'
    ["nuoto_supporto", "forza"],                 # giovedi'
    ["corsa_chiave", "bici_supporto"],           # venerdi'
    ["brick"],                                   # sabato
    ["lungo_corsa", "nuoto_rigenerante"],        # domenica
]

# Settimana di gara (Word: "Struttura della settimana di gara"). La gara e' domenica.
SETTIMANA_GARA = [
    ["gara_richiami"],      # lunedi': corsa 45' con 3x7' a ritmo gara
    [],                     # martedi': riposo
    ["gara_nuoto"],         # mercoledi': 1.700m con 7x200 RPE 8
    [],                     # giovedi': riposo (o bici leggera)
    ["gara_bici_facile"],   # venerdi': bici leggera max 1h
    ["gara_attivazione"],   # sabato: attivazione corta
    [],                     # domenica: GARA (l'evento esiste gia' a calendario)
]


def _brick_previsto(pos):
    """Il brick esiste solo da Build in poi (Word: 'Base -> niente brick: solo lungo
    bici aerobico, con eventualmente 15' di corsa facile a seguire'). Senza gara a
    calendario vale la stessa regola, per esplicita indicazione della Parte 2."""
    return pos["fase"] in ("build", "peak") and not pos.get("scarico")


def _forza_prevista(pos):
    """La forza parte dalla settimana 1 e prosegue per gran parte del ciclo; nelle
    ultime 3 settimane resta solo mobilita' (Word / RG Active)."""
    if pos["fase"] in ("taper", "gara"):
        return 0
    if pos["fase"] == "recupero":
        return 1
    return PALESTRA_X_SETT


def espandi_template(template, pos):
    """Template -> lista di sedute concrete, con durata nominale e attributi."""
    dist = pos["distanza"]
    sedute, forza_rimaste = [], _forza_prevista(pos)
    for giorno, chiavi in enumerate(template):
        slot = 0
        for k in chiavi:
            if k == "forza":
                if forza_rimaste <= 0:
                    continue
                forza_rimaste -= 1
            if k == "brick":
                # Il brick e' sempre DUE eventi separati (Ride + Run): un unico evento
                # bici con la corsa dentro manderebbe l'orologio in zone sbagliate.
                if _brick_previsto(pos):
                    slot += 1
                    sedute.append(_seduta("brick_bici", giorno, slot, dist, pos))
                    slot += 1
                    sedute.append(_seduta("brick_corsa", giorno, slot, dist, pos))
                else:
                    slot += 1
                    sedute.append(_seduta("lungo_bici", giorno, slot, dist, pos))
                    slot += 1
                    s = _seduta("brick_corsa", giorno, slot, dist, pos)
                    # Word: "solo lungo bici aerobico, con eventualmente 15' di corsa
                    # facile a seguire". Sono 15 minuti fissi, non una seduta da scalare:
                    # servono a non perdere la sensazione, non a fare volume.
                    s["durata"] = s["nominale"] = 15
                    s["fisso"] = True
                    s["qualita"] = False
                    s["nome"] = "Corsa facile a seguire 15'"
                    sedute.append(s)
                continue
            if k == "nuoto_rigenerante" and pos["fase"] not in ("peak", "taper", "senza_gara", "base"):
                continue
            slot += 1
            sedute.append(_seduta(k, giorno, slot, dist, pos))
    return sedute


def _seduta(key, giorno, slot, dist, pos):
    c = CATALOGO[key]
    nom = c["nom"][dist] if isinstance(c["nom"], dict) else c["nom"]
    # Base, periodo senza gara e recupero post-gara condividono la stessa regola: le
    # sedute chiave restano al loro posto ma diventano sedute a ritmo costante, senza
    # intervalli a ritmo gara (Word, "Periodi senza gara in calendario").
    aerobiche = ("base", "senza_gara", "recupero")
    companion = key in ("brick_corsa", "nuoto_rigenerante", "mobilita")
    if key == "forza":
        # MODIFICA (03/10/2026): la forza dura quanto la scheda di intervals_coach della
        # fase (45', 25' in taper), non 30': lo slot non conteneva la scheda (misurato:
        # scritta 44.8'). Fissata qui, prima di volume e tetti, cosi' il tetto giornaliero
        # la conta per quello che e'.
        nom = sedute.gym_durata_target(sedute.FASE_IC.get(pos["fase"], "Base"))
    s = {"key": key, "giorno": giorno, "slot": slot, "sport": c["sport"],
         "companion": companion,
         "famiglia": c["famiglia"], "qualita": c["qualita"] and pos["fase"] not in aerobiche,
         "nominale": nom, "durata": nom, "min": c["min"], "prio": c["prio"],
         "fase": pos["fase"], "distanza": dist, "nome": "", "note": []}
    if key == "forza":
        s["fisso"] = True   # la scheda non si accorcia: si toglie intera, non si taglia
    return s


def settimana_tipo(pos):
    """La settimana di questa fase, prima di volume e biometrici."""
    if pos["fase"] == "gara":
        return espandi_template(SETTIMANA_GARA, pos)
    template = SETTIMANA_WORD_TEMPLATE if SETTIMANA_WORD else SETTIMANA_ADATTATA
    sedute = espandi_template(template, pos)
    if pos["fase"] == "recupero":
        # Recupero post-gara: si tiene la struttura ma tutto diventa aerobico e corto.
        # Nessuna seduta di qualita', nessun brick (gia' escluso da _brick_previsto).
        sedute = [s for s in sedute if s["famiglia"] != "forza" or s["key"] == "mobilita"]
        for s in sedute:
            s["qualita"] = False
    return sedute


# ── VINCOLI STRUTTURALI (indipendenti dal volume) ────────────────────────────
def applica_vincoli(sedute, pos, giorni_indisponibili=None):
    """Vincoli che non dipendono dal volume: giorni dichiarati non disponibili a
    calendario, nuoto da solo nei feriali, niente palestra nel giorno del brick."""
    indisp = set(giorni_indisponibili or [])
    out = [s for s in sedute if s["giorno"] not in indisp]

    if NUOTO_DA_SOLO_FERIALE:
        # Feriale con nuoto: l'unica compagnia ammessa e' la palestra (stesso posto).
        for g in range(0, 5):
            del_giorno = [s for s in out if s["giorno"] == g]
            if any(s["famiglia"] == "nuoto" for s in del_giorno):
                for s in list(del_giorno):
                    if s["famiglia"] not in ("nuoto", "forza"):
                        out.remove(s)

    # Niente palestra nel giorno del brick / del lungo bici.
    giorni_brick = {s["giorno"] for s in out if s["key"] in ("brick_bici", "lungo_bici")}
    out = [s for s in out if not (s["key"] == "forza" and s["giorno"] in giorni_brick)]

    if BICI_SOLO_INDOOR:
        for s in out:
            if s["famiglia"] == "bici":
                s["indoor"] = True
                s["note"].append("indoor (palestra)")
    return out


def applica_modulazione(sedute, mod, pos):
    """Scaling FATICA del Word: la seduta non si elimina, si converte in aerobica.
    Le qualita' si difendono per priorita': la corsa chiave e il brick sono gli ultimi
    a cadere, la bici chiave e il nuoto chiave i primi."""
    qualita = sorted([s for s in sedute if s["qualita"]], key=lambda s: s["prio"])
    tenute = qualita[:max(0, mod["max_qualita"])]
    for s in qualita:
        if s not in tenute:
            s["qualita"] = False
            s["declassata"] = True
            s["note"].append("convertita in aerobica (biometrici)")

    if mod.get("blocca_palestra"):
        # Banda rossa: la curva prescrive recupero, e il recupero non comprende un
        # circuito di forza. La palestra torna appena la banda risale.
        sedute = [s for s in sedute if s["famiglia"] != "forza"]

    if mod["riposo"]:
        # Il riposo si mette nel giorno con il carico piu' basso, e a parita' il piu'
        # vicino: si perde la seduta meno importante della settimana. Una seduta
        # saltata e' persa e non si insegue (nessun recupero altrove).
        carichi = {}
        for s in sedute:
            carichi.setdefault(s["giorno"], 0)
            carichi[s["giorno"]] += s["durata"] * (0.5 if s["famiglia"] == "forza" else 1)
        if carichi:
            giorno_riposo = min(carichi, key=lambda g: (carichi[g], g))
            sedute = [s for s in sedute if s["giorno"] != giorno_riposo]
            for s in sedute:
                s.setdefault("ctx", {})
            mod["giorno_riposo"] = giorno_riposo
    return sedute


def dimensiona(sedute, ore_target, mod, pos):
    """Porta il monte ore delle sedute CARDIO a `ore_target` (palestra e mobilita'
    restano fuori dal conteggio), poi applica il fattore biometrico.
    Lo scaling e' proporzionale ma con pavimento per seduta: sotto il minimo la seduta
    non vale piu' lo spostamento e viene tolta, non ridotta a un moncone."""
    cardio = [s for s in sedute if s["famiglia"] != "forza" and not s.get("fisso")]
    nominale = sum(s["nominale"] for s in cardio)
    if not nominale:
        return sedute

    target_min = ore_target * 60 * mod["fattore_volume"]
    quote = QUOTE_DISCIPLINA.get(pos["distanza"], QUOTE_DISCIPLINA["70.3"])

    # Scaling PER DISCIPLINA verso le quote della tabella di volume del Word: uno
    # scaling uniforme conserverebbe le proporzioni nominali del catalogo, che non
    # sono quelle che il piano dichiara di voler rispettare.
    for fam in ("nuoto", "bici", "corsa"):
        voci = [x for x in cardio if x["famiglia"] == fam]
        nom_fam = sum(x["nominale"] for x in voci)
        if not nom_fam:
            continue
        k = (target_min * quote.get(fam, 0)) / nom_fam
        for x in voci:
            x["durata"] = int(round(x["nominale"] * k / 5.0) * 5)

    # Le sedute della settimana di gara non si scalano: sono richiami, la loro durata
    # e' gia' la durata giusta.
    if pos["fase"] == "gara":
        for s in sedute:
            s["durata"] = s["nominale"]
        return sedute

    # DURATA MINIMA UTILE. Una seduta creata da zero sotto i 40 minuti non vale lo
    # spostamento: o sta sopra il minimo, o non si crea e i suoi minuti vanno alla
    # seduta piu' importante della stessa disciplina (allungare una seduta gia' in
    # programma va benissimo). Le sedute COMPANION sono esenti: non sono uscite a se'
    # stanti ma code di un'altra sessione, nello stesso posto e nella stessa ora.
    for s in cardio:
        if s.get("companion"):
            continue
        soglia = max(s["min"], MIN_SEDUTA)
        if s["durata"] >= soglia:
            continue
        if s["prio"] <= 3:
            s["durata"] = soglia          # una seduta chiave non scende sotto il minimo
            continue
        recuperati = s["durata"]
        s["durata"] = 0
        s["note"].append(f"tolta: sotto i {MIN_SEDUTA}' minimi per una seduta a se'")
        stessa_fam = [x for x in cardio
                      if x["famiglia"] == s["famiglia"] and x is not s and x["durata"] > 0]
        if stessa_fam:
            dest = min(stessa_fam, key=lambda x: (x["prio"], -x["durata"]))
            dest["durata"] += recuperati
            dest["note"].append("allungata coi minuti di una seduta troppo corta")
    sedute = [s for s in sedute if s["durata"] > 0]

    # La corsa del brick non ha un minimo "da seduta": e' la coda di una sessione
    # piu' lunga, e 10-15 minuti hanno senso pieno.
    return sedute


def applica_tetti(sedute, pos):
    """Tetto giornaliero (palestra inclusa) e tetto sul lungo bici.
    Il lungo/brick e' esente dal tetto giornaliero SOLO quando c'e' una gara a
    calendario: e' l'unica seduta che non si comprime senza cambiare la gara
    obiettivo. Senza gara il lungo bici sta in 120' e quel giorno resta da solo."""
    con_gara = bool(pos.get("gara"))

    if not con_gara:
        for s in sedute:
            if s["key"] in ("lungo_bici", "brick_bici"):
                if s["durata"] > LUNGO_BICI_MAX_NO_GARA:
                    s["durata"] = LUNGO_BICI_MAX_NO_GARA
                    s["note"].append(f"tetto {LUNGO_BICI_MAX_NO_GARA}' (nessuna gara a calendario)")
                giorno = s["giorno"]
                sedute = [x for x in sedute
                          if x["giorno"] != giorno or x["key"] in ("lungo_bici", "brick_bici", "brick_corsa")]

    for g in range(7 if TETTO_GIORNO > 0 else 0):
        del_giorno = [s for s in sedute if s["giorno"] == g]
        esenti = [s for s in del_giorno
                  if con_gara and s["key"] in ("lungo_bici", "brick_bici", "brick_corsa", "lungo_corsa")]
        if esenti:
            continue
        tot = sum(s["durata"] for s in del_giorno)
        if tot <= TETTO_GIORNO:
            continue
        # Si taglia dalla seduta meno prioritaria, fino al suo minimo; se non basta,
        # la si toglie del tutto.
        # BUG FIX (04/10/2026): una seduta "fissa" (scheda di forza, 15' a seguire il lungo
        # bici) non si accorcia: prima si tagliano le altre, poi semmai la si toglie intera.
        # Misurato: scheda di forza da 45' tagliata a 40', con gli step ancora da 45'.
        for s in sorted(del_giorno, key=lambda x: (bool(x.get("fisso")), -x["prio"])):
            ecc = tot - TETTO_GIORNO
            if ecc <= 0:
                break
            riducibile = 0 if s.get("fisso") else s["durata"] - s["min"]
            if riducibile >= ecc:
                s["durata"] -= ecc
                s["note"].append(f"ridotta per il tetto di {TETTO_GIORNO}'/giorno")
                tot -= ecc
            else:
                tot -= s["durata"]
                s["durata"] = 0
                s["note"].append(f"tolta per il tetto di {TETTO_GIORNO}'/giorno")
        sedute = [s for s in sedute if s["durata"] > 0]
    return sedute


# Minuti di lavoro ad alta intensita' dentro una seduta di qualita': sono i minuti del
# main set, non la durata della seduta. Servono per la regola del 10%.
def minuti_alta_intensita(s, ctx=None):
    """Minuti realmente spesi in Z4-Z5 dentro la seduta. NON e' la durata del main set:
    i blocchi a ritmo gara 70.3 e Full stanno in Z3 e non sono alta intensita' (la
    regola del 10% di Be IronFit parla del lavoro in Z4). Il conteggio riusa la stessa
    struttura che genera la descrizione: un secondo calcolo andrebbe a deriva."""
    if not s.get("qualita"):
        return 0
    ctx = ctx or {"zone": zone_karvonen(), "settimana_pari": False}
    key = s["key"]
    if key == "corsa_chiave":
        st = struttura_corsa_chiave(s, ctx)
        return st["n"] * st["rep"] if st["zona"] in ("Z4", "Z5") else 0
    if key == "bici_chiave":
        st = struttura_bici_chiave(s, ctx)
        return st["n"] * st["rep"] if st["zona"] in ("Z4", "Z5") else 0
    if key in ("brick_bici", "brick_corsa", "lungo_corsa", "gara_richiami"):
        # Tutti a ritmo gara: contano solo se per quella distanza il ritmo gara e' Z4
        # (Olimpico). Per 70.3 e Full il ritmo gara e' Z3 o meno.
        if RITMO_GARA_ZONA.get(s["distanza"]) not in ("Z4", "Z5"):
            return 0
        return int(round(max(0, s["durata"] - 20) * 0.4))
    return 0


def limita_alta_intensita(sedute, pos, ctx=None):
    """Word / Be IronFit: il lavoro ad alta intensita' resta al massimo il 10% del
    volume combinato bici + corsa, e ci si arriva gradualmente. Se si sfora, si
    declassa la qualita' meno prioritaria (non si accorciano le ripetute: perderebbero
    lo scopo)."""
    if pos["fase"] in ("gara", "taper"):
        return sedute          # il taper mantiene l'intensita' per scelta esplicita
    volume_bc = sum(s["durata"] for s in sedute if s["famiglia"] in ("bici", "corsa"))
    # 04/10/2026: nel ciclo continuo vale la polarizzazione 80/20 (decisione di Simone).
    tetto = volume_bc * (ALTA_INTENSITA_PERPETUO if pos["fase"] == "senza_gara" else ALTA_INTENSITA_MAX)
    for _ in range(6):
        hi = sum(minuti_alta_intensita(s, ctx) for s in sedute)
        if hi <= tetto or tetto <= 0:
            break
        cand = sorted([s for s in sedute if s["qualita"]], key=lambda s: -s["prio"])
        if not cand:
            break
        cand[0]["qualita"] = False
        cand[0]["note"].append("convertita in aerobica (tetto 10% alta intensita')")
    return sedute


def quote_effettive(sedute):
    """Ripartizione reale per disciplina, per il riepilogo e per i test."""
    tot = sum(s["durata"] for s in sedute if s["famiglia"] != "forza") or 1
    out = {}
    for fam in ("nuoto", "bici", "corsa"):
        out[fam] = round(sum(s["durata"] for s in sedute if s["famiglia"] == fam) / tot, 3)
    return out


# ═════════════════════════════════════════════════════════════════════════════
# 4. CONTENUTO DELLE SEDUTE — i main set del Word, per distanza e per fase
# ═════════════════════════════════════════════════════════════════════════════
# REGOLA UNITA' TARGET UNICA: dentro una descrizione c'e' una sola unita' di target.
# Corsa e bici -> bpm (zone Karvonen). Nuoto -> metri + RPE fra parentesi, nessun
# target macchina: in vasca la FC da polso e' inaffidabile e un target in bpm
# manderebbe l'orologio a inseguire un numero che non esiste.
WU, WORK, REC, CD, REST = ("intensity=warmup", "intensity=active", "intensity=recovery",
                           "intensity=cooldown", "intensity=rest")

SWIM_REST_SEC = 20


def swim_pace_sec_100m(activities=None):
    """Passo di riferimento in vasca: MISURATO sulle nuotate recenti, non assunto.
    La conversione minuti -> metri vale quanto il passo con cui viene fatta."""
    paces = []
    for a in activities or []:
        if (a.get("type") or "") not in ("Swim", "OpenWaterSwim"):
            continue
        d, t = a.get("distance") or 0, a.get("moving_time") or 0
        if d > 300 and t > 300:
            p = t / (d / 100.0)
            if SWIM_PACE_PLAUSIBILE[0] <= p <= SWIM_PACE_PLAUSIBILE[1]:
                paces.append(p)
    if not paces:
        return SWIM_PACE_FALLBACK
    paces.sort()
    return int(round(paces[len(paces) // 2]))


def metri(minuti, pace_100, pool=None):
    """Minuti -> metri, arrotondati alla vasca: uno step di nuoto deve finire a
    distanza, altrimenti l'orologio non ha una condizione di fine valida."""
    pool = pool or SWIM_POOL_M
    m = minuti * 60 / max(1, pace_100) * 100
    return max(pool, int(round(m / pool)) * pool)


def _pausa(sec=SWIM_REST_SEC):
    """Riposo di transizione: due step NUOTATI non possono essere adiacenti, o il
    tasto lap non ha dove andare e su Garmin la sessione si pianta."""
    return f"- pausa al muro {sec}s {REST}\n"


def _blocchi_che_entrano(disponibile_sec, costo_sec, nominali, minimo=1):
    """SCALING DURATA del Word: si tagliano BLOCCHI INTERI, mai la lunghezza del
    singolo intervallo (accorciarlo gli farebbe perdere lo scopo)."""
    if costo_sec <= 0:
        return nominali
    return max(minimo, min(nominali, int(disponibile_sec // costo_sec)))


# ── NUOTO ────────────────────────────────────────────────────────────────────
DRILL = ["catch-up", "side kick", "zippers"]


def _sec_nuoto(m, pace, fattore=1.0):
    """Secondi di uno step nuotato di `m` metri. `fattore` < 1 = passo piu' veloce
    (le ripetute si nuotano piu' forte del riscaldamento: dimensionarle col passo
    facile le farebbe uscire sistematicamente piu' corte del previsto)."""
    return m / 100.0 * pace * fattore


def _coda_nuoto(residuo_sec, pace):
    """Residuo del fit -> nuotata continua ESPLICITA. I blocchi si tagliano interi,
    quindi un residuo c'e' sempre: senza questo blocco la sessione scritta dura molto
    meno dei minuti dichiarati nel titolo, e i conti tocca rifarli in vasca."""
    if residuo_sec < 180:
        return ""
    return (_pausa() + f"- nuotata continua {metri(residuo_sec / 60.0, pace)}mtr {WORK} "
            f"(ritmo aerobico costante)\n")


def desc_nuoto_chiave(s, ctx):
    """Nuoto a sforzo progressivo: si impara a distinguere l'85% dal 95%.
    La struttura si costruisce tenendo il conto dei secondi mano a mano, cosi' la
    durata scritta e quella dichiarata sono lo stesso numero per costruzione."""
    pace, dist, fase, dur = ctx["pace100"], s["distanza"], s["fase"], s["durata"]
    wu_m = metri(min(8, max(5, dur * 0.15)), pace)
    cd_m = metri(4, pace)
    usato = _sec_nuoto(wu_m, pace) + _sec_nuoto(cd_m, pace) + 2 * SWIM_REST_SEC
    righe = [f"- riscaldamento misti e drill ({', '.join(DRILL)}) {wu_m}mtr {WU} (RPE 4)\n",
             _pausa()]

    if fase == "base" or not s["qualita"]:
        # Base / SCALING FATICA: si costruisce solo fino all'85%, niente massimale.
        rep_m = {"olimpico": 100, "70.3": 150, "full": 200}[dist]
        costo = _sec_nuoto(rep_m, pace, 0.95) + SWIM_REST_SEC
        n = _blocchi_che_entrano(dur * 60 - usato, costo, 12, 4)
        usato += n * costo
        righe += [f"{n}x\n",
                  f"- ripetuta {rep_m}mtr {WORK} (progressione 65-75-85%, mai sopra)\n",
                  f"- recupero {SWIM_REST_SEC}s {REST}\n\n"]
        if fase == "base":
            righe.append("Nota: in Base il tempo si spende su tecnica e drill, "
                         "non sull'intensita'.\n")
    else:
        # Pre-main del Word: 3 round di (50 al 75% / 50 all'85% / 50 al 95%).
        costo_pre = _sec_nuoto(150, pace, 0.95) + 10
        if dur * 60 - usato > costo_pre * 3 + 8 * 60:
            righe += ["3x\n",
                      f"- 50 al 75% / 50 all'85% / 50 al 95% — 150mtr {WORK} "
                      f"(palette e pull buoy)\n",
                      f"- recupero 10s {REST}\n\n"]
            usato += costo_pre * 3
        rep_m = {"olimpico": 100, "70.3": 200, "full": 300}[dist]
        rec = {"olimpico": 20, "70.3": 30, "full": 30}[dist]
        nom = {"olimpico": 12, "70.3": 9, "full": 6}[dist]
        costo = _sec_nuoto(rep_m, pace, 0.92) + rec
        n = _blocchi_che_entrano(dur * 60 - usato, costo, nom, 4)
        usato += n * costo
        livelli = "65 / 75 / 85 / 95%" if n >= 6 else "65 / 75 / 85%"
        chiusura = ("a ritmo gara, con respirazione ipossica" if fase == "peak"
                    else "l'ultima al massimo")
        righe += [f"{n}x\n",
                  f"- ripetuta {rep_m}mtr {WORK} (progressione {livelli}, {chiusura})\n",
                  f"- recupero {rec}s {REST}\n\n"]
        if fase == "peak":
            righe.append("Nota: nel riscaldamento aggiungi polo-sighting.\n")

    coda = _coda_nuoto(dur * 60 - usato, pace)
    if coda:
        righe.append(coda)
    righe += [_pausa(), f"- defaticamento misti sciolti {cd_m}mtr {CD} (RPE 3)\n"]
    return "".join(righe)


def desc_nuoto_supporto(s, ctx):
    """Endurance e ritmo: tenere la forma mentre la fatica arriva."""
    pace, dist, fase, dur = ctx["pace100"], s["distanza"], s["fase"], s["durata"]
    wu_m = metri(max(5, dur * 0.14), pace)
    cd_m = metri(3, pace)
    usato = _sec_nuoto(wu_m, pace) + _sec_nuoto(cd_m, pace) + 2 * SWIM_REST_SEC

    rep_m = {"olimpico": 400, "70.3": 200, "full": 600}[dist]
    nom = {"olimpico": 3, "70.3": 8, "full": 3}[dist]
    rec = {"olimpico": 30, "70.3": 20, "full": 30}[dist]
    costo = _sec_nuoto(rep_m, pace, 0.95) + rec
    n = _blocchi_che_entrano(dur * 60 - usato, costo, nom, 2)
    usato += n * costo

    intensita = "a ritmo gara" if s["qualita"] else "a ritmo costante, RPE 6"
    righe = [f"- riscaldamento {wu_m}mtr {WU} (ogni 4a vasca a stile non libero)\n",
             _pausa(), f"\n{n}x\n",
             f"- ripetuta {rep_m}mtr {WORK} ({intensita})\n",
             f"- recupero {rec}s {REST}\n\n"]

    # Coda di 25 a forma perfetta: e' il tratto che il Word scala per primo.
    costo25 = _sec_nuoto(25, pace, 0.95) + 5
    nom25 = {"olimpico": 20, "70.3": 30, "full": 40}[dist]
    n25 = _blocchi_che_entrano(dur * 60 - usato, costo25, nom25, 0)
    if n25 >= 4:
        usato += n25 * costo25
        cue = "a ritmo forte sostenuto" if s["qualita"] else "a forma perfetta"
        righe += [f"{n25}x\n", f"- 25mtr {WORK} ({cue})\n", f"- recupero 5s {REST}\n\n"]
    if fase == "peak":
        righe.append("Nota: in Peak una volta a settimana questa seduta si fa in acque "
                     "libere (partenza, sighting, contatto).\n")

    coda = _coda_nuoto(dur * 60 - usato, pace)
    if coda:
        righe.append(coda)
    righe += [_pausa(), f"- sciolti finali {cd_m}mtr {CD} (recupero abbondante)\n"]
    return "".join(righe)


def desc_nuoto_rigenerante(s, ctx):
    pace = ctx["pace100"]
    m = metri(max(18, s["durata"] - 4), pace)
    coda = ("In Peak diventa la seduta in acque libere: 2.000-2.200mtr a ritmo costante, "
            "provando respirazione e sighting.\n" if s["fase"] == "peak" else "")
    return (f"- nuotata sciolta {m}mtr {WORK} (RPE 3-4, bassa intensita')\n"
            f"{_pausa()}"
            f"- defaticamento {metri(3, pace)}mtr {CD}\n{coda}")


# ── BICI ─────────────────────────────────────────────────────────────────────
def _premain_bici(z):
    """Pre-main del Word: 2x4', 2x3', 2x2', 2x1' — dispari Z2 a cadenza crescente,
    pari in build da Z1 a Z3/Z4. Costa 20': e' il primo blocco a saltare."""
    r = ""
    for m in (4, 3, 2, 1):
        r += (f"- {m}m {bpm('Z2', z)} {WORK} (cadenza crescente fino a veloce)\n"
              f"- {m}m {bpm_range('Z1', 'Z3', z)} {WORK} (build progressivo)\n")
    return r


def struttura_bici_chiave(s, ctx):
    """Main set della bici chiave. Per il 70.3 il Word prevede un'alternanza
    settimanale fra 8x3' a soglia e 4x10' di lavoro di cadenza: qui la decide il
    numero della settimana ISO, cosi' due run dello stesso giorno non cambiano idea."""
    dist, fase, dur = s["distanza"], s["fase"], s["durata"]
    wu, cd = 10, 5
    corpo = max(15, dur - wu - cd)
    if fase == "peak":
        rep, rec, nom, zona = 6, 2, 8, "Z4"
        cue = "a ritmo gara, posizione aero mantenuta per tutto il blocco"
    elif dist == "olimpico":
        rep, rec, nom, zona, cue = 4, 6, 5, "Z4", "RPE 8"
    elif dist == "70.3":
        if ctx.get("settimana_pari"):
            rep, rec, nom, zona = 10, 0, 4, "Z3"
            cue = "come 2x(4' sotto 50rpm + 1' oltre 95rpm)"
        else:
            rep, rec, nom, zona = 3, 5, 8, "Z4"
            cue = "come salita, alternando seduto e in piedi"
    else:
        rep, rec, nom, zona = 5, 5, 6, "Z4"
        cue = "2' seduto in salita / 2' in piedi / 1' sprint aero"
    # Il pre-main costa 20': e' il primo blocco a saltare quando il tempo non basta.
    pre = corpo >= nom * (rep + rec) + 20
    disponibile = corpo - (20 if pre else 0)
    n = _blocchi_che_entrano(disponibile * 60, (rep + rec) * 60, nom, 3)
    resto = max(0, disponibile - n * (rep + rec))
    return {"n": n, "rep": rep, "rec": rec, "zona": zona, "cue": cue,
            "wu": wu, "cd": cd, "pre": pre, "resto": resto}


def desc_bici_chiave(s, ctx):
    """Forza specifica e range di cadenza, ai due estremi della scala RPM."""
    z, fase, dur = ctx["zone"], s["fase"], s["durata"]
    wu, cd = 10, 5
    corpo = max(15, dur - wu - cd)
    testa = f"- {wu}m {bpm_range('Z1','Z2',z)} {WU} (spin sciolto, cadenza 90-95rpm)\n\n"
    coda = f"- {cd}m {bpm('Z1',z)} {CD} (+ allunghi di scioglimento)\n"

    if fase == "base":
        # Base: intervalli sostituiti da continuo a ritmo costante: tenuta e posizione.
        return (testa + f"- {corpo}m {bpm('Z2',z)} {WORK} (ritmo costante, posizione "
                        f"stabile, cadenza 85-90rpm)\n\n" + coda)

    if not s["qualita"]:
        # SCALING FATICA: si tiene il pre-main e il main diventa 4x10' Z2 a cadenza
        # crescente. La seduta non sparisce, cambia sistema energetico.
        n = _blocchi_che_entrano(corpo * 60, 10 * 60, 4, 2)
        return (testa + f"{n}x\n- 10m {bpm('Z2',z)} {WORK} (cadenza crescente lungo il "
                        f"blocco)\n\n" + _riempi(corpo - n * 10, z, "spin sciolto") + coda)

    st = struttura_bici_chiave(s, ctx)
    pre = _premain_bici(z) + "\n" if st["pre"] else ""
    rec_riga = (f"- {st['rec']}m {bpm('Z2',z)} {REC} (spin, continua a pedalare)\n"
                if st["rec"] else "")
    return (testa + pre + _riempi(st["resto"], z, "rullata di raccordo")
            + f"{st['n']}x\n- {st['rep']}m {bpm(st['zona'],z)} {WORK} ({st['cue']})\n"
            + rec_riga + "\n" + coda)


def desc_bici_supporto(s, ctx):
    """Uscita a basso stress che prepara il brick, con aperture per svegliare le gambe."""
    z, dist, fase, dur = ctx["zone"], s["distanza"], s["fase"], s["durata"]
    n_open = {"olimpico": 4, "70.3": 5, "full": 6}[dist]
    if fase == "base" or not s["qualita"]:
        return (f"- {dur}m {bpm_range('Z1','Z2',z)} {WORK} "
                f"(rapporto agile, cadenza sopra 95rpm; nessuna apertura)\n")
    corpo = max(20, dur - n_open * 4)
    return (f"- {corpo}m {bpm_range('Z1','Z2',z)} {WU} (cadenza sopra 95rpm)\n\n"
            f"{n_open}x\n"
            f"- 30s {bpm('Z5',z)} {WORK} (in crescita fino al massimo negli ultimi 10s)\n"
            f"- 3m {bpm('Z1',z)} {REC}\n\n"
            f"- 5m {bpm('Z1',z)} {CD} (non deve lasciare fatica per domani)\n")


# ── CORSA ────────────────────────────────────────────────────────────────────
def desc_corsa_supporto(s, ctx):
    z, dur = ctx["zone"], s["durata"]
    wu = 10
    corpo = max(15, dur - wu)
    return (f"- {wu}m {bpm('Z1',z)} {WU} (meglio se preceduta da riscaldamento dinamico)\n\n"
            f"- {corpo}m {bpm('Z2',z)} {WORK} (ritmo conversazionale; ogni 4o minuto "
            f"controllo di postura e appoggio)\n")


def struttura_corsa_chiave(s, ctx):
    """Main set della corsa di qualita': (ripetute, minuti, recupero, zona, cue).
    UNICA FONTE DI VERITA' condivisa da descrizione e conteggio del 10%."""
    dist, fase, dur = s["distanza"], s["fase"], s["durata"]
    wu, cd = 12, 8
    corpo = max(15, dur - wu - cd)
    if fase == "peak":
        rep, rec, nom, zona, cue = 8, 2, 6, "Z4", "a ritmo leggermente sopra quello di gara"
    elif dist == "olimpico":
        rep, rec, nom, zona, cue = 3, 2, 6, "Z4", "RPE 8, controllato"
    elif dist == "70.3":
        if ctx.get("settimana_pari"):
            rep, rec, nom, zona, cue = 2, 3, 10, "Z4", "in salita, RPE 8"
        else:
            rep, rec, nom, zona, cue = 10, 3, 3, "Z4", "a soglia, sforzo controllato e in crescita"
    else:
        rep, rec, nom, zona, cue = 20, 5, 2, "Z3", "a ritmo IM (Z3 basso)"
    n = _blocchi_che_entrano(corpo * 60, (rep + rec) * 60, nom, 2)
    resto = max(0, corpo - n * (rep + rec))
    return {"n": n, "rep": rep, "rec": rec, "zona": zona, "cue": cue,
            "wu": wu, "cd": cd, "resto": resto}


def _riempi(resto, z, cue="blocco aerobico di raccordo"):
    """SCALING DURATA: i blocchi si tagliano interi, quindi resta sempre un residuo.
    Il residuo diventa un blocco aerobico esplicito, non minuti fantasma: la durata
    dichiarata dell'evento deve coincidere con la somma degli step."""
    if resto < 5:
        return ""
    return f"- {resto}m {bpm('Z2', z)} {WORK} ({cue})\n\n"


def desc_corsa_chiave(s, ctx):
    """La seduta di qualita' della corsa: l'ultimo intervallo deve essere il migliore."""
    z, fase, dur = ctx["zone"], s["fase"], s["durata"]
    wu, cd = 12, 8
    testa = f"- {wu}m {bpm_range('Z1','Z2',z)} {WU} (+ 4 allunghi di 20s)\n\n"
    coda = f"- {cd}m {bpm('Z1',z)} {CD} (+ allunghi di mobilita')\n"

    if fase == "base" or not s["qualita"]:
        # Base: nessun intervallo, corsa continua con allunghi.
        # SCALING FATICA: stessa forma, corsa fluida Z2 + 6x20" di allunghi.
        allunghi = 8                      # 6 x (20s + 1m) arrotondati
        corpo = max(10, dur - wu - cd - allunghi)
        cue = "corsa continua" if fase == "base" else "corsa fluida"
        return (testa + f"- {corpo}m {bpm('Z2',z)} {WORK} ({cue})\n\n"
                f"6x\n- 20s {bpm('Z4',z)} {WORK} (allungo)\n"
                f"- 1m {bpm('Z1',z)} {REC}\n\n" + coda)

    st = struttura_corsa_chiave(s, ctx)
    rep_txt = f"{st['rep']}m"
    return (testa + _riempi(st["resto"], z, "corsa fluida prima del main set")
            + f"{st['n']}x\n- {rep_txt} {bpm(st['zona'],z)} {WORK} ({st['cue']})\n"
              f"- {st['rec']}m {bpm('Z1',z)} {REC}\n\n" + coda)


def desc_lungo_corsa(s, ctx):
    """Resilienza e forma sotto fatica: il tratto piu' veloce sono gli ultimi minuti."""
    z, dist, fase, dur = ctx["zone"], s["distanza"], s["fase"], s["durata"]
    wu = 12
    corpo = max(20, dur - wu)
    testa = f"- {wu}m {bpm('Z1',z)} {WU} (i primi minuti sono il riscaldamento)\n\n"

    if fase in ("base", "senza_gara", "recupero") or not s["qualita"]:
        return (testa + f"- {corpo}m {bpm('Z2',z)} {WORK} (tutto aerobico: cresce la "
                        f"durata, non l'intensita')\n")

    if dist == "olimpico":
        blocco = min(10, max(5, corpo // 5))
        base = corpo - 2 * blocco
        return (testa + f"- {base}m {bpm('Z2',z)} {WORK}\n\n2x\n"
                        f"- {blocco}m {bpm('Z4',z)} {WORK} (a ritmo gara, nella seconda meta')\n"
                        f"- 3m {bpm('Z2',z)} {REC}\n")
    # Progressione a 4 gradini (Word: 40/25/15/5 per il 70.3, 50/30/20/10 per il Full),
    # riscalata sulla durata effettiva mantenendo le proporzioni.
    pesi = [0.42, 0.28, 0.20, 0.10]
    t = [max(5, int(round(corpo * p / 5) * 5)) for p in pesi]
    t[0] = max(5, corpo - sum(t[1:]))
    zone_seq = [("Z2", "aerobico"), ("Z2", "Z2 alto, ritmo sostenuto"),
                ("Z3", "ritmo gara"), ("Z3", "Z3+, chiusura")]
    righe = "".join(f"- {t[i]}m {bpm(zone_seq[i][0],z)} {WORK} ({zone_seq[i][1]})\n"
                    for i in range(4))
    nota = ("Nota: pause camminate di 20-40s se servono a tenere la forma.\n"
            if dist == "full" else "")
    return testa + righe + nota


# ── BRICK / LUNGO BICI ───────────────────────────────────────────────────────
def desc_brick_bici(s, ctx):
    """La seduta piu' importante della settimana: sforzo specifico di gara e prova
    generale di idratazione, alimentazione, abbigliamento e posizione."""
    z, dist, dur = ctx["zone"], s["distanza"], s["durata"]
    wu = min(30, max(15, int(dur * 0.18)))
    cd = 5
    corpo = max(20, dur - wu - cd)
    testa = (f"- {wu}m {bpm_range('Z1','Z2',z)} {WU} (fluido)\n"
             f"- 6m {bpm_range('Z2','Z4',z)} {WORK} (pre-main: in crescita ogni 1.5m fino a Z4)\n\n")
    if not s["qualita"]:
        return (testa + f"- {corpo}m {bpm('Z2',z)} {WORK} (uscita aerobica continua a "
                        f"sensazione)\n\n- {cd}m {bpm('Z1',z)} {CD}\n")
    rep, rec, nom = ({"olimpico": (6, 3, 3), "70.3": (20, 10, 3), "full": (20, 5, 3)})[dist]
    n = _blocchi_che_entrano((corpo - 6) * 60, (rep + rec) * 60, nom, 2)
    ritmo = RITMO_GARA_ZONA[dist]
    resto = max(0, corpo - 6 - n * (rep + rec))
    if resto >= 10:
        filler = f"- {resto}m {bpm('Z2',z)} {WORK} (rullata, alimentazione e idratazione)\n"
    else:
        # Un residuo di pochi minuti non merita un blocco a se': finisce nel
        # defaticamento, che resta un numero vero e non un arrotondamento perduto.
        filler, cd = "", cd + resto
    cadenza = (f"- 5m {bpm('Z2',z)} {WORK} (cadenza 50rpm, forza specifica)\n"
               f"- 5m {bpm('Z2',z)} {WORK} (cadenza 70rpm)\n"
               if dist == "full" else "")
    return (testa + filler + f"{n}x\n- {rep}m {bpm(ritmo,z)} {WORK} (a ritmo gara {dist})\n"
            f"- {rec}m {bpm('Z1',z)} {REC} (rullante)\n\n" + cadenza
            + f"- {cd}m {bpm('Z1',z)} {CD}\n")


def desc_brick_corsa(s, ctx):
    """Corsa immediatamente a seguire: 'come mi sento' non e' una variabile, e' il
    punto della seduta."""
    z, dist, dur = ctx["zone"], s["distanza"], s["durata"]
    if not s["qualita"] or dur <= 16:
        return (f"- {dur}m {bpm('Z2',z)} {WORK} (corsa facile subito dopo la bici, "
                f"senza guardare il passo)\n")
    rampa = min(10, max(4, dur // 3))
    resto = dur - rampa
    ritmo = RITMO_GARA_ZONA[dist]
    sopra = "sopra ritmo gara" if dist == "70.3" else "fino a poco sopra il ritmo IM"
    return (f"- {rampa}m {bpm_range('Z2','Z3',z)} {WU} (rampa {sopra})\n"
            f"- {resto}m {bpm(ritmo,z)} {WORK} (a ritmo gara, transizione dalla bici)\n")


def desc_lungo_bici(s, ctx):
    z, dur = ctx["zone"], s["durata"]
    wu = min(20, max(10, int(dur * 0.15)))
    return (f"- {wu}m {bpm_range('Z1','Z2',z)} {WU}\n\n"
            f"- {dur - wu - 5}m {bpm('Z2',z)} {WORK} (lungo aerobico continuo, posizione "
            f"stabile, cadenza 85-90rpm)\n\n- 5m {bpm('Z1',z)} {CD}\n")


# ── FORZA E MOBILITA' ────────────────────────────────────────────────────────
# Programmi #1/#2/#3 dalla tabella "Forza e condizionamento" del Word. Mai a
# cedimento, tempo 2s giu' / 2s su, sempre 5-10' di mobilita'.
FORZA_PROGRAMMI = {
    "1": ("Forza #1 — forza generale",
          ["Squat 3x15", "Dip su panca 3x12-15", "Hip raise 3x15", "Plank 2x45-60s",
           "Centipede 3x10", "Dorsal raises 3x10", "Side plank 2x30s per lato", "Crunch 3x20"]),
    "2": ("Forza #2 — forza massima",
          ["Affondo split con piede posteriore rialzato 2x12-15 per lato",
           "Pulldown con elastico 3x15-20", "Hip raise monopodalico da rialzo 3x15",
           "Dip con piedi rialzati 3x12", "Plank a braccia tese 3x60s",
           "Calf raises 2x20 per lato", "Crunch 5x20"]),
    "3": ("Forza #3 — mantenimento",
          ["Squat 2x12", "Plank a braccia tese 2x45s", "Pulldown con elastico 2x15",
           "Hip raise 2x12", "Crunch 2x20"]),
}
FASE_FORZA = {"base": "1", "senza_gara": "1", "build": "2", "peak": "3",
              "taper": "3", "recupero": "3", "gara": "3"}


def desc_forza(s, ctx):
    prog = FASE_FORZA.get(s["fase"], "1")
    titolo, esercizi = FORZA_PROGRAMMI[prog]
    corpo = "\n".join(f"  • {e}" for e in esercizi)
    return (f"{titolo}\n\n- 8m mobilita' {WU} (non si taglia mai: si tagliano gli esercizi)\n"
            f"- {max(15, s['durata'] - 13)}m circuito {WORK}\n{corpo}\n"
            f"- 5m defaticamento e stretching {CD}\n\n"
            f"Tempo di esecuzione 2s in discesa / 2s in salita. Carichi moderati, "
            f"mai a cedimento.\n")


def desc_mobilita(s, ctx):
    return (f"- {s['durata']}m mobilita' e core {WORK} "
            f"(seduta flottante: se il brick e' stato duro, spostala o saltala)\n")


# ── SETTIMANA DI GARA ────────────────────────────────────────────────────────
def desc_gara_richiami(s, ctx):
    """Corsa 45' con 3x7' a ritmo gara: durata fissata dal Word, quindi warm-up e
    defaticamento si dimensionano su quello che avanza, non viceversa."""
    z = ctx["zone"]
    resto = max(6, s["durata"] - 3 * 10)
    wu, cd = resto - resto // 2, resto // 2
    return (f"- {wu}m {bpm('Z1',z)} {WU}\n\n3x\n"
            f"- 7m {bpm(RITMO_GARA_ZONA[s['distanza']],z)} {WORK} (a ritmo gara)\n"
            f"- 3m {bpm('Z1',z)} {REC}\n\n- {cd}m {bpm('Z1',z)} {CD}\n")


def desc_gara_nuoto(s, ctx):
    return (f"- riscaldamento 200mtr {WU} (RPE 4)\n{_pausa(15)}\n7x\n"
            f"- ripetuta 200mtr {WORK} (RPE 8)\n- recupero 15s {REST}\n\n"
            f"{_pausa(15)}- defaticamento 100mtr {CD}\n")


def desc_gara_bici_facile(s, ctx):
    z = ctx["zone"]
    return (f"- {s['durata']}m {bpm('Z1',z)} {WORK} (solo per sciogliere le gambe; "
            f"se possibile su un tratto del percorso di gara)\n")


def desc_gara_attivazione(s, ctx):
    z = ctx["zone"]
    return (f"- 12m {bpm('Z1',z)} {WU}\n\n3x\n- 1m {bpm('Z3',z)} {WORK} (in crescita fino a "
            f"ritmo gara)\n- 2m {bpm('Z1',z)} {REC}\n\n5x\n- 15s {bpm('Z4',z)} {WORK} (allungo)\n"
            f"- 45s {bpm('Z1',z)} {REC}\n\n"
            f"- 4m {bpm('Z1',z)} {CD}\n\n"
            f"Nuoto sul percorso max 15 minuti e bici 30-45 minuti se la logistica "
            f"lo permette.\n")


BUILDER = {
    "nuoto_chiave": desc_nuoto_chiave, "nuoto_supporto": desc_nuoto_supporto,
    "nuoto_rigenerante": desc_nuoto_rigenerante, "bici_chiave": desc_bici_chiave,
    "bici_supporto": desc_bici_supporto, "corsa_supporto": desc_corsa_supporto,
    "corsa_chiave": desc_corsa_chiave, "lungo_corsa": desc_lungo_corsa,
    "brick_bici": desc_brick_bici, "brick_corsa": desc_brick_corsa,
    "lungo_bici": desc_lungo_bici, "forza": desc_forza, "mobilita": desc_mobilita,
    "gara_richiami": desc_gara_richiami, "gara_nuoto": desc_gara_nuoto,
    "gara_bici_facile": desc_gara_bici_facile, "gara_attivazione": desc_gara_attivazione,
}

# Quando una seduta chiave e' aerobica (per fase o per declassamento biometrico) il
# titolo non deve continuare a promettere qualita': e' il primo numero che l'atleta
# legge sull'orologio e deve descrivere la sessione che trovera' dentro.
NOMI_AEROBICI = {
    "corsa_chiave": "Corsa fluida", "bici_chiave": "Bici continua",
    "nuoto_chiave": "Nuoto tecnico progressivo", "brick_bici": "Lungo bici aerobico",
    "brick_corsa": "Corsa facile a seguire", "lungo_corsa": "Lungo corsa aerobico",
    "nuoto_supporto": "Nuoto endurance",
}

NOMI = {
    "nuoto_chiave": "Nuoto chiave progressivo", "nuoto_supporto": "Nuoto endurance e ritmo",
    "nuoto_rigenerante": "Nuoto rigenerante", "bici_chiave": "Bici chiave",
    "bici_supporto": "Bici supporto + opener", "corsa_supporto": "Corsa di supporto",
    "corsa_chiave": "Corsa qualita'", "lungo_corsa": "Lungo corsa",
    "brick_bici": "Brick — bici", "brick_corsa": "Brick — corsa a seguire",
    "lungo_bici": "Lungo bici", "forza": "Forza", "mobilita": "Mobilita' e core",
    "gara_richiami": "Richiami a ritmo gara", "gara_nuoto": "Nuoto pre-gara",
    "gara_bici_facile": "Bici sciolta", "gara_attivazione": "Attivazione pre-gara",
}



# ═════════════════════════════════════════════════════════════════════════════
# 4b. SEDUTE DALLA LIBRERIA DI INTERVALS_COACH (03/10/2026 — decisione di Simone)
# ═════════════════════════════════════════════════════════════════════════════
# Il coach settimanale era stato abbandonato per la sintassi delle sedute, scritte a mano
# (desc_*) e mai passate dalle correzioni pagate in intervals_coach.py: nuoto senza
# "% Pace" ne' "Press lap", nuoto progressivo letto come potenza, bici aerobica in % Friel
# (bug del 27/09: "Z2" chiesta 123-136 bpm, 78% in Z3). Ora questo file decide COSA fare
# (giorno, disciplina, durata, qualita', fase) e sedute.py — estratto da intervals_coach
# con le sue regole e la sua suite — decide COME scriverlo. desc_mobilita resta qui: la
# libreria di intervals_coach non ha una seduta di mobilita'.
#
# LIBRERIA AMPLIATA: lo stesso slot ruota fra piu' sedute della stessa famiglia, scelte
# per fase. La rotazione e' deterministica (settimana ISO + giorno): due run della stessa
# settimana producono lo stesso piano, settimane diverse sedute diverse.
sedute_mod = sedute

LIBRERIA = {
    # chiave: {fase o "*": [(sessione_tipo, params), ...]}   qualita' = True
    "corsa_chiave": {
        "build": [("Threshold", {}), ("Interval", {}), ("HillWork", {}), ("Fartlek", {})],
        "peak":  [("RaceSimulation", {}), ("Threshold", {}), ("Alternations", {}), ("Progressive", {})],
        "taper": [("Fartlek", {}), ("Threshold", {"reps": 2})],
        "*":     [("Threshold", {}), ("Interval", {})],
    },
    "bici_chiave": {
        "build": [("BikeCross", {"profilo": "soglia"}), ("BikeCross", {"profilo": "vo2max"})],
        "peak":  [("BikeCross", {"profilo": "soglia"})],
        "taper": [("BikeCross", {"profilo": "vo2max", "reps": 4})],
        "*":     [("BikeCross", {"profilo": "soglia"})],
    },
    "nuoto_chiave": {
        "base":  [("Swim", {"profilo": "soglia"}), ("Swim", {"profilo": "tecnica"})],
        "build": [("Swim", {"profilo": "soglia"}), ("Swim", {"profilo": "velocita"})],
        "peak":  [("Swim", {"profilo": "soglia"}), ("Swim", {"profilo": "velocita"})],
        "*":     [("Swim", {"profilo": "soglia"})],
    },
    "lungo_corsa":   {"*": [("Long", {})]},
    "gara_richiami": {"*": [("Threshold", {"reps": 3, "rep_min": 3, "rec_min": 2})]},
    "gara_nuoto":    {"*": [("Swim", {"profilo": "velocita"})]},
}
# Versione aerobica (fasi base/senza gara/recupero, o seduta declassata dai biometrici).
LIBRERIA_AEROBICA = {
    "corsa_chiave":      [("Progressive", {}), ("Easy", {"strides_reps": 4})],
    "corsa_supporto":    [("Easy", {}), ("Easy", {"strides_reps": 4})],
    "lungo_corsa":       [("Long", {})],
    "gara_richiami":     [("Easy", {"strides_reps": 4})],
    "gara_attivazione":  [("ShakeOut", {})],
    "bici_chiave":       [("BikeCross", {"profilo": "recovery"})],
    "bici_supporto":     [("BikeCross", {"profilo": "recovery"})],
    "lungo_bici":        [("BikeCross", {"profilo": "recovery"})],
    "gara_bici_facile":  [("BikeCross", {"profilo": "recovery"})],
    "nuoto_chiave":      [("Swim", {"profilo": "endurance"})],
    "nuoto_supporto":    [("Swim", {"profilo": "endurance"}), ("Swim", {"profilo": "tecnica"})],
    "nuoto_rigenerante": [("Swim", {"profilo": "tecnica"})],
    "gara_nuoto":        [("Swim", {"profilo": "endurance"})],
}



# ═════════════════════════════════════════════════════════════════════════════
# 4c. CICLO CONTINUO SENZA GARE — "PERPETUAL BASE & BUILD" (04/10/2026, Simone)
# ═════════════════════════════════════════════════════════════════════════════
# Triathlon olimpico, atleta amatore evoluto. Feriali solo dopo le 18 (1 cardio, o 1
# cardio + forza companion), weekend liberi per lunghi e brick. Blocchi 4:1:
#   W1 ~9.5h (Z2 + richiamo soglia) · W2 ~10h · W3 ~10.5h (picco di densita') ·
#   W4 ~10-10.5h (ritmo gara olimpico) · W5 scarico ~6-6.5h (-40%, frequenza
#   mantenuta, brevi stimoli, palestra dimezzata a carichi invariati).
# Blocco A: base aerobica + VO2max. Blocco B: soglia + muscular endurance.
# Interferenza: forza full body il lunedi' seguita da nuoto Z2; il giorno dopo bici, mai
# corsa di qualita'. Chiavi della stessa disciplina ad almeno 48h. Brick nel weekend.
# Le sedute sono quelle della libreria (sedute.py); qui si scelgono tipo e durata.
#
# ── SETTIMANA TIPO CONFIGURABILE (06/10/2026 — roadmap punto 2) ───────────────────
# Il profilo dell'app sceglie da elenchi: giorno del lungo bici + brick, giorno del lungo
# corsa, giorno di riposo, sedute per disciplina. struttura_settimana() colloca le sedute
# con regole fisse; con il profilo predefinito la settimana e' quella decisa da Simone.
_GG = ("lun", "mar", "mer", "gio", "ven", "sab", "dom")
SETTIMANA_PREDEFINITA = {"lungo_bici": "sab", "lungo_corsa": "dom", "riposo": None,
                         "sedute": {"nuoto": 2, "bici": 2, "corsa": 3, "forza": 2}}
LIMITI_SEDUTE = {"nuoto": (1, 4), "bici": (1, 4), "corsa": (1, 4), "forza": (0, 2)}
# Durate W1..W5 per chiave (minuti). La forza dura quanto la sua scheda.
DURATE_PERPETUO = {
    "nuoto_supporto": (45, 45, 50, 45, 40),
    "bici_chiave":    (75, 75, 80, 75, 60),
    "bici_supporto":  (60, 60, 60, 60, 45),
    "corsa_supporto": (50, 50, 55, 50, 40),
    "nuoto_chiave":   (60, 65, 75, 70, 45),
    "corsa_chiave":   (60, 60, 60, 60, 45),
    "brick_bici":     (180, 180, 195, 195, 105),
    "brick_corsa":    (15, 20, 20, 20, 10),
    "lungo_corsa":    (80, 80, 90, 90, 45),
}
# W2: domenica bi-giornaliera (nuoto + corsa Z2) al posto del lungo corsa
BIGIORNALIERO_W2 = (("nuoto_supporto", 50), ("corsa_supporto", 60))
# Preferenze di collocazione (prima scelta valida), pensate per i lunghi nel weekend
PREF_GIORNI = {"bici_chiave": (1, 2, 3, 0, 4, 5, 6), "nuoto_chiave": (3, 2, 1, 4, 0, 5, 6),
               "corsa_chiave": (4, 3, 2, 1, 0, 5, 6), "forza_full": (0, 1, 2, 3, 4, 5, 6),
               "libero": (0, 1, 2, 3, 4, 5, 6)}
_MOTIVI_SETTIMANA = []


def settimana_tipo_valida(cfg):
    """(profilo completo e valido, [errori]). Con un errore qualsiasi vale il predefinito."""
    cfg = cfg or {}
    out = {**SETTIMANA_PREDEFINITA, "sedute": dict(SETTIMANA_PREDEFINITA["sedute"])}
    errori = []
    for k in ("lungo_bici", "lungo_corsa", "riposo"):
        if k in cfg:
            v = cfg[k]
            if v is not None and v not in _GG:
                errori.append(f"{k}: giorno non valido ({v})")
            else:
                out[k] = v
    for f, n in (cfg.get("sedute") or {}).items():
        lo, hi = LIMITI_SEDUTE.get(f, (None, None))
        if lo is None or not isinstance(n, int) or not lo <= n <= hi:
            errori.append(f"sedute {f}: {n} fuori da {lo}-{hi}")
        else:
            out["sedute"][f] = n
    if out["lungo_bici"] == out["lungo_corsa"]:
        errori.append("lungo bici e lungo corsa nello stesso giorno")
    if out["riposo"] in (out["lungo_bici"], out["lungo_corsa"]):
        errori.append("giorno di riposo coincide con un lungo")
    if errori:
        return settimana_tipo_valida({})[0] if cfg else out, errori
    return out, []


SETTIMANA_TIPO = settimana_tipo_valida(json.loads(os.getenv("SETTIMANA_TIPO", "{}") or "{}"))[0]


def _dist_gg(a, b):
    d = abs(a - b) % 7
    return min(d, 7 - d)


def struttura_settimana(cfg, disponibili, w):
    """[(giorno, slot, chiave, gym_day_type|None)] della settimana W(w+1) del ciclo."""
    lb, lc = _GG.index(cfg["lungo_bici"]), _GG.index(cfg["lungo_corsa"])
    n = dict(cfg["sedute"])
    liberi = [g for g in range(7) if g in disponibili and g not in (lb, lc)]
    piano = {g: [] for g in range(7)}          # giorno -> [chiavi]
    _MOTIVI_SETTIMANA.clear()

    def cardio(g):
        return [k for k in piano[g] if CATALOGO[k]["famiglia"] != "forza"]

    def manca(chiave):
        _MOTIVI_SETTIMANA.append(f"{NOMI.get(chiave, chiave)}: non c'e' spazio nella settimana tipo")

    # lunghi
    if lb in disponibili:
        piano[lb] += ["brick_bici", "brick_corsa"]
        n["bici"] -= 1
    if lc in disponibili:
        if w == 1 and n["nuoto"] >= 2:
            piano[lc] += ["nuoto_supporto", "corsa_supporto"]
        else:
            piano[lc].append("lungo_corsa")
        n["corsa"] -= 1
    lunghi = {"bici": [lb] if lb in disponibili else [], "corsa": [lc] if lc in disponibili else []}

    # qualita': una per disciplina se ce ne sono almeno 2 a settimana
    chiave_di = {"bici": "bici_chiave", "nuoto": "nuoto_chiave", "corsa": "corsa_chiave"}
    giorni_q = {}
    for fam in ("bici", "nuoto", "corsa"):
        if cfg["sedute"][fam] < 2 or n[fam] < 1:
            continue
        key = chiave_di[fam]
        for g in PREF_GIORNI[key]:
            if g not in liberi or cardio(g):
                continue
            vicini = lunghi.get(fam, []) + [x for f2, x in giorni_q.items() if f2 == fam]
            if any(_dist_gg(g, x) < 2 for x in vicini):
                continue
            piano[g].append(key)
            giorni_q[fam] = g
            n[fam] -= 1
            break
        else:
            pass   # nessun giorno valido: la seduta resta di supporto

    # forza full body: niente qualita' quel giorno, niente corsa di qualita' il giorno dopo
    full = None
    if n["forza"] >= 1:
        for g in PREF_GIORNI["forza_full"]:
            if g in liberi and not any(CATALOGO[k]["qualita"] for k in piano[g]) \
                    and "corsa_chiave" not in piano[(g + 1) % 7]:
                piano[g].insert(0, "forza")
                full = g
                n["forza"] -= 1
                break
        else:
            manca("forza")
            n["forza"] -= 1

    # supporto: il nuoto Z2 dopo la forza full body, poi i giorni liberi (1 cardio al giorno)
    # corsa e bici prima: il nuoto in piu' puo' andare nel weekend, accanto a un lungo di
    # un'altra disciplina (il weekend non ha il limite di 1 cardio dei feriali)
    supporto = (("corsa", "corsa_supporto"), ("bici", "bici_supporto"), ("nuoto", "nuoto_supporto"))
    weekend = [g for g in (lc, lb) if g in disponibili]
    if full is not None and n["nuoto"] >= 1 and not cardio(full):
        piano[full].append("nuoto_supporto")
        n["nuoto"] -= 1
    for fam, key in supporto:
        while n[fam] > 0:
            g = next((g for g in PREF_GIORNI["libero"] if g in liberi and not cardio(g)), None)
            if g is None:
                g = next((g for g in weekend if fam not in
                          {CATALOGO[k]["famiglia"] for k in piano[g]}), None)
            if g is None:
                manca(key)
                n[fam] -= 1
                continue
            piano[g].append(key)
            n[fam] -= 1

    # forza companion: dopo una corsa facile, se c'e'; altrimenti in un giorno senza qualita'
    while n["forza"] > 0:
        g = next((g for g in liberi if "corsa_supporto" in piano[g] and "forza" not in piano[g]), None)
        if g is None:
            g = next((g for g in liberi if "forza" not in piano[g]
                      and not any(CATALOGO[k]["qualita"] for k in piano[g])), None)
        if g is None:
            manca("forza")
        else:
            piano[g].append("forza")
        n["forza"] -= 1

    out = []
    for g in range(7):
        for slot, k in enumerate(piano[g]):
            tipo = None
            if k == "forza":
                tipo = "strength" if g == full else "companion"
            out.append((g, slot, k, tipo))
    return out


# Qualita' per settimana e blocco: (tipo libreria, params). W1 richiamo soglia, W2-W3
# focus del blocco, W4 specificita' ritmo gara, W5 brevi richiami neuromuscolari.
QUALITA_PERPETUO = {
    "bici_chiave": {0: ("BikeCross", {"profilo": "soglia"}),
                    "A": ("BikeCross", {"profilo": "vo2max"}), "B": ("BikeCross", {"profilo": "soglia"}),
                    3: ("BikeCross", {"profilo": "soglia"}),
                    4: ("BikeCross", {"profilo": "vo2max", "reps": 3})},
    "nuoto_chiave": {0: ("Swim", {"profilo": "soglia"}),
                     "A": ("Swim", {"profilo": "velocita"}), "B": ("Swim", {"profilo": "soglia"}),
                     3: ("Swim", {"profilo": "soglia"}), 4: ("Swim", {"profilo": "velocita"})},
    "corsa_chiave": {0: ("Threshold", {}),
                     "A": ("Interval", {}), "B": ("Threshold", {}),
                     3: ("RaceSimulation", {}), 4: ("Strides", {})},
}
AEROBICHE_PERPETUO = {
    "nuoto_supporto": ("Swim", {"profilo": "endurance"}),
    "bici_supporto":  ("BikeCross", {"profilo": "recovery"}),
    "corsa_supporto": ("Easy", {"strides_reps": 5}),
    "lungo_corsa":    ("Long", {}),
}


def settimana_perpetua(pos, cfg=None, disponibili=None, escludi=None, disp=None):
    w = pos.get("idx_fase", 0)
    cfg = cfg or SETTIMANA_TIPO
    if disponibili is None:
        riposo = _GG.index(cfg["riposo"]) if cfg.get("riposo") else None
        d = DISPONIBILITA if disp is None else disp     # 08/10/2026: con le date del "+"
        disponibili = {g for g in range(7) if g != riposo and d.get(g, 1) != 0}
    disponibili = set(disponibili) - set(escludi or ())
    lc = _GG.index(cfg["lungo_corsa"])
    out = []
    test_da_fare = TEST_PROSSIMO if w == 0 else None   # 06/10/2026: test solo in W1
    for giorno, slot, key, gym in struttura_settimana(cfg, disponibili, w):
        s = _seduta(key, giorno, slot, "olimpico", pos)
        if key == "forza":
            # Full body: scheda di forza massimale (scarico: condensata, serie ridotte e
            # carico invariato); companion pre-hab dopo la corsa; niente companion in scarico.
            if gym == "companion" and w == 4:
                continue
            s["fisso"] = True
            s["gym_day_type"] = ("b2b" if w == 4 else "strength") if gym == "strength" else "companion"
            s["durata"] = s["nominale"] = sedute.gym_durata_target(
                sedute.FASE_IC["senza_gara"], s["gym_day_type"])
        else:
            d = DURATE_PERPETUO[key][w]
            if w == 1 and giorno == lc and key in dict(BIGIORNALIERO_W2):
                d = dict(BIGIORNALIERO_W2)[key]
            s["durata"] = s["nominale"] = d
            s["min"] = min(s["min"], d)
        if key in QUALITA_PERPETUO:
            tab = QUALITA_PERPETUO[key]
            s["scelta"] = tab[w] if w in (0, 3, 4) else tab[pos.get("blocco", "A")]
            s["qualita"] = True
        elif key in AEROBICHE_PERPETUO:
            s["scelta"] = AEROBICHE_PERPETUO[key]
        if key in ("brick_bici", "brick_corsa"):
            s["qualita"] = False          # lungo Z2 + transizione: volume, non intensita'
            s["fisso"] = True
        if test_da_fare and soglie.SEDUTA_DEL_TEST[test_da_fare["chiave"]] == key:
            s["test"] = test_da_fare      # la prima seduta di quella chiave diventa il test
            s["fisso"] = True
            s["qualita"] = test_da_fare["chiave"] != "run_maf"
            s["durata"] = s["nominale"] = soglie._DESCRIZIONI_TEST[test_da_fare["chiave"]][1]
            test_da_fare = None
        out.append(s)
    return out


def scala_biometrica(sedute_sett, mod):
    """Freni biometrici sul volume del ciclo continuo: stessa curva della pianificazione
    con gara (MOD_VOLUME), applicata alle durate della settimana tipo."""
    f = mod.get("fattore_volume", 1.0)
    if f >= 1.0:
        return sedute_sett
    for s in sedute_sett:
        if s["famiglia"] != "forza":
            s["durata"] = max(s["min"], int(round(s["durata"] * f / 5.0) * 5))
    return sedute_sett


def carico_ieri_eccessivo(eventi_ieri, attivita, ieri_str, soglia=1.25):
    """True se la corsa di qualita' di ieri ha prodotto piu' del 125% del TSS pianificato:
    il brick di oggi si alleggerisce (regola 3 di Simone, ciclo continuo)."""
    pianificato = sum(e.get("icu_training_load") or 0 for e in eventi_ieri or []
                      if (e.get("external_id") or "") == f"sw:corsa_chiave:{ieri_str}")
    if not pianificato:
        return False
    fatto = sum(a.get("icu_training_load") or 0 for a in attivita or []
                if (a.get("start_date_local") or "")[:10] == ieri_str
                and FAMIGLIA_TIPO.get(a.get("type")) == "corsa")
    return fatto > pianificato * soglia


def brick_alleggerito(bici_min, corsa_min):
    """Bici -20% (a 5'), corsa off-bike al massimo 15'."""
    return int(round(bici_min * 0.8 / 5.0) * 5), min(corsa_min, 15)


def eventi_brick_alleggeriti(coach_oggi, oggi, pos, ctx):
    """[(payload, evento esistente)] per riscrivere il brick di oggi alleggerito."""
    def ev(key, tipo):
        return next((e for e in coach_oggi if (e.get("external_id") or "").startswith(f"sw:{key}:")
                     and e.get("type") == tipo), None)
    bici, corsa = ev("brick_bici", "Ride"), ev("brick_corsa", "Run")
    if not bici or not corsa:
        return []
    nb, nr = brick_alleggerito(round((bici.get("moving_time") or 0) / 60),
                               round((corsa.get("moving_time") or 0) / 60))
    t2 = -(-sedute.BRICK_T2_SEC // 60) if sedute.BRICK_EVENTO_TRANSIZIONE else 0
    esistenti = {e.get("external_id"): e for e in coach_oggi}
    out = []
    for key, durata in (("brick_bici", nb), ("brick_corsa", nr)):
        s = {"key": key, "sport": CATALOGO[key]["sport"], "famiglia": CATALOGO[key]["famiglia"],
             "qualita": False, "declassata": True, "durata": durata, "nominale": durata,
             "min": CATALOGO[key]["min"], "prio": CATALOGO[key]["prio"], "fase": pos["fase"],
             "distanza": pos["distanza"], "giorno": _dt(oggi).weekday(), "slot": 0, "note": [],
             "companion": False, "data": oggi, "brick": (nb + nr + t2, nb)}
        componi(s, ctx)
        for p in payload_eventi(s, oggi):
            out.append((p, esistenti.get(p["external_id"])))
    return out



# ═════════════════════════════════════════════════════════════════════════════
# 4d. PROFILO DELL'APP E TSS COME LIMITE (04/10/2026 — decisioni di Simone)
# ═════════════════════════════════════════════════════════════════════════════
def applica_disponibilita(sedute_sett, disp):
    """Minuti massimi per giorno dal profilo: prima si accorciano le sedute non fisse
    del giorno (fino al loro minimo), poi si tolgono quelle a priorita' piu' bassa."""
    for g, maxm in (disp or {}).items():
        if not maxm:
            continue
        del_g = [s for s in sedute_sett if s["giorno"] == g and s["durata"] > 0]
        ecc = sum(s["durata"] for s in del_g) - maxm
        for s in sorted(del_g, key=lambda x: (bool(x.get("fisso")), -x["prio"])):
            if ecc <= 0:
                break
            if not s.get("fisso") and s["famiglia"] != "forza":
                taglio = min(ecc, s["durata"] - s["min"])
                if taglio > 0:
                    s["durata"] -= taglio
                    ecc -= taglio
                    s["note"].append(f"accorciata alla disponibilita' del giorno ({maxm}')")
        for s in sorted(del_g, key=lambda x: x["prio"], reverse=True):
            if ecc <= 0:
                break
            # 08/10/2026 ("+" sul calendario): l'ultima seduta cardio del giorno non si toglie
            # se nei minuti rimasti ci sta una versione aerobica di almeno 20': si declassa.
            altre_cardio = [x for x in del_g if x is not s and x["durata"] > 0 and x["famiglia"] != "forza"]
            spazio = s["durata"] - ecc
            if s["famiglia"] != "forza" and not altre_cardio and spazio >= 20:
                s.update(durata=spazio, qualita=False, declassata=True)
                s["min"] = min(s["min"], spazio)
                s["note"].append(f"aerobica di {spazio}': non c'era tempo per la seduta piena ({maxm}')")
                ecc = 0
                break
            ecc -= s["durata"]
            s["durata"] = 0
            s["note"].append(f"tolta: oltre la disponibilita' del giorno ({maxm}')")
    return [s for s in sedute_sett if s["durata"] > 0]


def applica_tetto_ore(sedute_sett, tetto_h):
    """Tetto ore cardio settimanale del profilo: si scalano le sedute non fisse, poi se
    serve anche le fisse (lunghi del weekend), mai sotto il loro minimo."""
    tetto = (tetto_h or 0) * 60
    cardio = [s for s in sedute_sett if s["famiglia"] != "forza"]
    tot = sum(s["durata"] for s in cardio)
    if not tetto or tot <= tetto:
        return sedute_sett
    for gruppo in ([s for s in cardio if not s.get("fisso")], [s for s in cardio if s.get("fisso")]):
        ecc = sum(s["durata"] for s in cardio) - tetto
        base = sum(s["durata"] - s["min"] for s in gruppo)
        if ecc <= 0 or base <= 0:
            continue
        quota = min(1.0, ecc / base)
        for s in gruppo:
            taglio = int(-(-(s["durata"] - s["min"]) * quota // 5) * 5)
            if taglio > 0:
                s["durata"] = max(s["min"], s["durata"] - taglio)
                s["note"].append(f"ridotta al tetto di {tetto_h:g}h cardio settimanali")
    return sedute_sett


def tetto_tss_settimana(activities, ctl, scarico):
    """(carico sostenibile, tetto TSS): mediana delle ultime 4 settimane complete, +10%
    nelle settimane di carico, limitato dalla rampa massima della CTL (intervals_coach)."""
    sost = carico.calc_carico_sostenibile(activities)
    return sost, carico.tetto_carico_settimana(sost, 1.0 if scarico else 1.10, ctl)


def stima_tss(s, tassi):
    if not tassi or not tassi.get("medio"):
        return None
    if s["famiglia"] == "forza":
        return tassi.get("gym") or 0.0
    t = tassi["fam"].get(s["famiglia"], tassi["medio"])
    return s["durata"] / 60 * t * (tassi["kq"] if s.get("qualita") else tassi["kf"])


def limita_tss(sedute_sett, activities, lunedi, forma, pos):
    """Regola 1 di Simone: il TSS e' un limite. Stima della settimana con i TSS/h misurati
    (carico.tassi_carico); oltre il tetto si accorciano le sedute FACILI, le piu' lunghe
    per prime, max -25% e mai sotto 30' (stessa regola di intervals_coach). Qualita',
    forza e sedute fisse non si toccano."""
    tassi = carico.tassi_carico(activities, _dt(lunedi))
    sost, tetto = tetto_tss_settimana(activities, (forma or {}).get("ctl"), pos.get("scarico"))
    stime = [stima_tss(s, tassi) for s in sedute_sett]
    if any(x is None for x in stime):
        return {"sostenibile": sost, "tetto": tetto, "stimato": None, "tasso": None}
    stimato = sum(stime)
    if tetto and stimato > tetto:
        ecc = stimato - tetto
        facili = sorted((s for s in sedute_sett if not s.get("qualita") and not s.get("fisso")
                         and s["famiglia"] != "forza"), key=lambda x: -x["durata"])
        for s in facili:
            if ecc <= 0:
                break
            per_min = stima_tss(s, tassi) / s["durata"] if s["durata"] else 0
            if per_min <= 0:
                continue
            taglio = min(int(s["durata"] * 0.25), s["durata"] - 30, int(-(-ecc // per_min)))
            taglio = (taglio // 5) * 5
            if taglio <= 0:
                continue
            s["durata"] -= taglio
            ecc -= taglio * per_min
            s["note"].append(f"ridotta per il tetto TSS settimanale ({tetto})")
        stimato = sum(stima_tss(s, tassi) for s in sedute_sett)
    return {"sostenibile": sost, "tetto": tetto, "stimato": round(stimato),
            "tasso": round(tassi["medio"], 1)}



# ═════════════════════════════════════════════════════════════════════════════
# 4e. TAG DALL'APP (05/10/2026 — contratto con la Parte 3)
# ═════════════════════════════════════════════════════════════════════════════
# Vocabolario fisso, senza testo libero: il cervello e' deterministico. Tag di giorno con
# la data della mattina (come la wellness); tag di seduta con l'id dell'attivita'.
ZONE_INFORTUNIO = {   # zona -> famiglie che la caricano (si tolgono finche' il tag resta)
    "ginocchio": {"corsa", "bici", "forza"}, "caviglia": {"corsa"}, "piede": {"corsa"},
    "polpaccio": {"corsa"}, "anca": {"corsa", "forza"}, "schiena": {"corsa", "forza"},
    "spalla": {"nuoto", "forza"},
}
TAG_GIORNO = {
    "alcol": "Alcol", "cena_tardiva": "Cena tardiva", "caffeina_tardi": "Caffeina tardi",
    "stress": "Stress", "viaggio": "Viaggio", "malattia": "Malattia",
    "sonno_disturbato": "Sonno disturbato", "caldo": "Caldo", "altitudine": "Altitudine",
    "dolore_muscolare": "Dolore muscolare",
    **{f"infortunio:{z}": f"Infortunio — {z}" for z in ZONE_INFORTUNIO},
}
TAG_SEDUTA = {
    "fatica_alta": "Fatica alta", "gambe_pesanti": "Gambe pesanti", "malessere": "Malessere",
    **{f"dolore:{z}": f"Dolore — {z}" for z in ZONE_INFORTUNIO},
}
TAG_GIORNI = {}     # "YYYY-MM-DD" -> [chiavi]          (impostati da cervello.esegui_app)
TAG_SEDUTE = {}     # "<id attivita'>" -> [chiavi]
_MOTIVI_TAG = []    # decisioni prese per un tag in questo run (per il riepilogo)
TAG_FATICA = ("fatica_alta", "gambe_pesanti", "malessere")


def _piu(d, n):
    return (_dt(d) + timedelta(days=n)).strftime("%Y-%m-%d")


def effetti_tag(d, ref, tag_giorni, tag_sedute, activities):
    """Effetti dei tag sul giorno d (ref = giorno della decisione). Regole:
    - malattia sul giorno: riposo; il giorno dopo (rientro) solo attivita' leggera;
    - malattia o infortunio taggati negli ultimi 2 giorni prima di ref valgono anche per
      i giorni seguenti ("finche' il tag resta": l'app li rimanda finche' ci sono);
      la malattia che prosegue cosi' vale come solo leggero;
    - infortunio:<zona>: niente discipline che caricano la zona;
    - viaggio: niente qualita' ne' forza, al massimo 45';
    - dolore_muscolare: niente qualita';
    - fatica_alta / gambe_pesanti / malessere su una seduta: la prima qualita' entro 3
      giorni diventa aerobica; dolore:<zona> su una seduta: zona scarica il giorno dopo."""
    tg = tag_giorni or {}
    eff = {"riposo": False, "leggero": False, "no_qualita": False, "no_forza": False,
           "max_min": None, "vietate": set(), "fatica_da": [], "motivi": [], "prevenzione": None}
    oggi_t = set(tg.get(d) or [])
    ieri_t = set(tg.get(_piu(d, -1)) or [])
    recenti = set()
    for k in range(0, 3):
        x = _piu(ref, -k)
        if x <= d:
            recenti |= {t for t in (tg.get(x) or []) if t == "malattia" or t.startswith("infortunio:")}
    if "malattia" in oggi_t:
        eff["riposo"] = True
        eff["motivi"].append(f"tag malattia del {d}: riposo")
    elif "malattia" in ieri_t or "malattia" in recenti:
        eff["leggero"] = True
        eff["motivi"].append(f"tag malattia: {d} solo attivita' leggera")
    for t in sorted(oggi_t | recenti):
        if t.startswith("infortunio:") and t.split(":", 1)[1] in ZONE_INFORTUNIO:
            z = t.split(":", 1)[1]
            eff["vietate"] |= ZONE_INFORTUNIO[z]
            eff["prevenzione"] = eff["prevenzione"] or z
            eff["motivi"].append(f"tag infortunio {z}: {d} senza {', '.join(sorted(ZONE_INFORTUNIO[z]))}")
    if "viaggio" in oggi_t:
        eff.update(no_qualita=True, no_forza=True)
        eff["max_min"] = 45
        eff["motivi"].append(f"tag viaggio del {d}: niente qualita' ne' forza, max 45'")
    if "dolore_muscolare" in oggi_t:
        eff["no_qualita"] = True
        eff["motivi"].append(f"tag dolore muscolare del {d}: niente qualita'")
    for a in activities or []:
        tags = (tag_sedute or {}).get(str(a.get("id"))) or []
        ad = (a.get("start_date_local") or "")[:10]
        if not tags or not ad:
            continue
        if ad < d <= _piu(ad, 3) and any(t in TAG_FATICA for t in tags):
            eff["fatica_da"].append(ad)
        if d == _piu(ad, 1):
            for t in tags:
                if t.startswith("dolore:") and t.split(":", 1)[1] in ZONE_INFORTUNIO:
                    z = t.split(":", 1)[1]
                    # 06/10/2026 (punto 6): col dolore la forza resta; la companion diventa
                    # la scheda di prevenzione della zona
                    eff["vietate"] |= ZONE_INFORTUNIO[z] - {"forza"}
                    eff["prevenzione"] = eff["prevenzione"] or z
                    vietate_txt = ", ".join(sorted(ZONE_INFORTUNIO[z] - {"forza"}))
                    eff["motivi"].append(f"tag dolore {z} nella seduta del {ad}: {d} "
                                         + (f"senza {vietate_txt}" if vietate_txt else "")
                                         + "; companion di prevenzione")
    if eff["leggero"]:
        eff.update(no_qualita=True, no_forza=True)
        eff["max_min"] = 45
    return eff


def applica_tag(sedute_sett, giorni, ref, tag_giorni, tag_sedute, activities):
    """Effetti dei tag sul piano della settimana; motivi in _MOTIVI_TAG."""
    if not tag_giorni and not tag_sedute:
        return sedute_sett
    consumate = set()
    for s in sorted(sedute_sett, key=lambda x: (x["giorno"], x["slot"])):
        d = giorni[s["giorno"]]
        eff = effetti_tag(d, ref, tag_giorni, tag_sedute, activities)
        motivo = None
        if (s["key"] == "forza" and s.get("gym_day_type") == "companion" and eff["prevenzione"]
                and not eff["riposo"]):
            # 06/10/2026 (punto 6): con un infortunio o un dolore la companion non si toglie,
            # diventa la scheda di prevenzione della zona (assegna_schede)
            s["prevenzione"] = eff["prevenzione"]
            for m in eff["motivi"]:
                if m not in _MOTIVI_TAG:
                    _MOTIVI_TAG.append(m)
            continue
        if eff["riposo"] or s["famiglia"] in eff["vietate"] or (eff["no_forza"] and s["famiglia"] == "forza"):
            s["durata"] = 0
            motivo = "tolta"
        else:
            nuove = [a for a in eff["fatica_da"] if a not in consumate]
            if s.get("qualita") and (eff["no_qualita"] or nuove):
                s["qualita"], s["declassata"] = False, True
                consumate.update(nuove)
                motivo = "resa aerobica"
                if nuove and not eff["no_qualita"]:
                    eff["motivi"].append(f"tag di fatica sulla seduta del {nuove[0]}: "
                                         f"{NOMI.get(s['key'], s['key'])} del {d} resa aerobica")
            if eff["max_min"] and s["durata"] > eff["max_min"]:
                s["durata"] = eff["max_min"]
                s["min"] = min(s["min"], eff["max_min"])
                motivo = motivo or f"ridotta a {eff['max_min']}'"
        if motivo:
            s["note"].append(f"{motivo} per i tag: " + "; ".join(eff["motivi"]))
            for m in eff["motivi"]:
                if m not in _MOTIVI_TAG:
                    _MOTIVI_TAG.append(m)
    return [s for s in sedute_sett if s["durata"] > 0]


def rimodula_per_tag(ev, eff):
    """(azione, motivo, durata_max) per la seduta di oggi, o None se i tag non c'entrano."""
    key = (ev.get("external_id") or ":").split(":")[1]
    if key not in CATALOGO:
        return None
    fam, motivo = CATALOGO[key]["famiglia"], "; ".join(eff["motivi"])
    if eff["riposo"] or fam in eff["vietate"] or (eff["no_forza"] and fam == "forza"):
        return "togli", motivo, None
    durata = round((ev.get("moving_time") or 0) / 60)
    if CATALOGO[key]["qualita"] and (eff["no_qualita"] or eff["fatica_da"]):
        if eff["fatica_da"] and not eff["no_qualita"]:
            motivo = f"tag di fatica sulla seduta del {eff['fatica_da'][0]}"
        return "declassa", motivo, eff["max_min"]
    if eff["max_min"] and durata > eff["max_min"]:
        return "declassa", motivo, eff["max_min"]
    return None



def assegna_schede(sedute_sett, pos, giorni, races):
    """06/10/2026 (punto 6): per ogni seduta di forza sceglie la scheda della libreria
    (palestra.scegli_scheda). Lunedi' full body, mercoledi' companion; gara A entro 14
    giorni, tempo del giorno, profilo e prevenzione decidono quali schede sono ammesse.
    Se nessuna va bene la seduta si toglie, con il motivo."""
    rot = _dt(giorni[0]).isocalendar()[1]
    gare_a = sorted(g["date"] for g in races or [] if g.get("category") == "RACE_A")
    out = []
    for s in sedute_sett:
        if s["key"] != "forza":
            out.append(s)
            continue
        d = giorni[s["giorno"]]
        prossima_a = next((g for g in gare_a if g >= d), None)
        gg_a = (_dt(prossima_a) - _dt(d)).days if prossima_a else None
        slot = "companion" if s.get("gym_day_type") == "companion" else "full"
        sch, motivo = palestra.scegli_scheda(
            slot, pos, PROFILO_PALESTRA, rot + (1 if slot == "companion" else 0), gg_a,
            DISPONIBILITA.get(s["giorno"]), s.get("prevenzione"), FORZA_SETTIMANE)
        if not sch:
            m = f"forza del {d} tolta: {motivo}"
            if m not in _MOTIVI_SETTIMANA:
                _MOTIVI_SETTIMANA.append(m)
            continue
        s["scheda"] = sch
        s["durata"] = s["nominale"] = sch["durata"]
        s["min"] = min(s["min"], sch["durata"])
        if motivo:
            s["note"].append(f"scheda {sch['titolo']} ({motivo})")
        out.append(s)
    return out



# ═════════════════════════════════════════════════════════════════════════════
# 4f. CALDO (06/10/2026 — roadmap punto 7, regole di Simone da intervals_coach)
# ═════════════════════════════════════════════════════════════════════════════
_MOTIVI_CALDO = []
_CORSE_FACILI = ("corsa_supporto",)
_CORSE_DA_ACCORCIARE = {"corsa_chiave": 0.90, "lungo_corsa": 0.85, "brick_corsa": 0.90,
                        "gara_richiami": 0.90}


def giorno_caldo(d):
    """Meteo del giorno se caldo (previsione) o taggato "caldo" dall'app, altrimenti None."""
    m = (METEO or {}).get(d)
    if m and caldo.e_caldo(m.get("temp_c"), m.get("umidita_pct")):
        return m
    if "caldo" in (TAG_GIORNI.get(d) or []):
        return {"temp_c": None, "umidita_pct": None, "ora": None, "tag": True}
    return None


def _descr_meteo(d, m):
    if m.get("tag"):
        return f"caldo del {d} (tag)"
    ora = f" alle {m['ora']}" if m.get("ora") is not None else ""
    return f"caldo del {d}: {round(m['temp_c'])} °C, umidita' {round(m.get('umidita_pct') or 0)}%{ora}"


def _a5(x):
    return int(round(x / 5.0) * 5)


def applica_caldo(sedute_sett, giorni, activities):
    """Regole del caldo sul piano: corsa facile -> bici indoor (se l'atleta non lo
    esclude), qualita' e lunghi di corsa accorciati. Nuoto, forza e bici invariati."""
    if METEO is None and not any("caldo" in (v or []) for v in TAG_GIORNI.values()):
        return sedute_sett
    fattore = caldo.calc_bike_run_conversion(activities)[0]
    converti = PROFILO_CALDO.get("converti_corsa", True)
    for s in sedute_sett:
        d = giorni[s["giorno"]]
        m = giorno_caldo(d)
        if not m or s.get("test"):
            continue
        if s["key"] in _CORSE_FACILI and converti:
            minuti = _a5(s["durata"] * fattore)
            motivo = (f"{_descr_meteo(d, m)}: corsa facile convertita in bici indoor "
                      f"{minuti}' (x{fattore:g})")
            s.update(key="bici_supporto", sport="Ride", famiglia="bici", indoor=True,
                     durata=minuti, nominale=minuti, scelta=("BikeCross", {"profilo": "recovery"}))
            s["min"] = min(s["min"], minuti)
        elif s["key"] in _CORSE_DA_ACCORCIARE or (s["key"] in _CORSE_FACILI and not converti):
            k = _CORSE_DA_ACCORCIARE.get(s["key"], 0.90)
            minuti = max(s["min"] if s["min"] < s["durata"] else 0, _a5(s["durata"] * k))
            motivo = (f"{_descr_meteo(d, m)}: {NOMI.get(s['key'], s['key'])} accorciata a {minuti}' "
                      f"— esci nelle ore piu' fresche, idratazione ed elettroliti")
            s["durata"] = s["nominale"] = minuti
        else:
            continue
        s["note"].append(motivo)
        if motivo not in _MOTIVI_CALDO:
            _MOTIVI_CALDO.append(motivo)
    return sedute_sett


def rimodula_per_caldo(ev, m, oggi, activities, profilo):
    """(azione, motivo, minuti) per la seduta di oggi con il caldo, o None."""
    if not m or not caldo.e_caldo(m.get("temp_c"), m.get("umidita_pct")) and not m.get("tag"):
        return None
    key = (ev.get("external_id") or ":").split(":")[1]
    durata = round((ev.get("moving_time") or 0) / 60)
    if key in _CORSE_FACILI and (profilo or {}).get("converti_corsa", True):
        f = caldo.calc_bike_run_conversion(activities)[0]
        minuti = _a5(durata * f)
        return "converti", (f"{_descr_meteo(oggi, m)}: corsa facile convertita in bici indoor "
                            f"{minuti}' (x{f:g})"), minuti
    if key in _CORSE_DA_ACCORCIARE or key in _CORSE_FACILI:
        minuti = _a5(durata * _CORSE_DA_ACCORCIARE.get(key, 0.90))
        return "accorcia", (f"{_descr_meteo(oggi, m)}: accorciata a {minuti}' — esci nelle ore "
                            f"piu' fresche, idratazione ed elettroliti"), minuti
    return None



# ═════════════════════════════════════════════════════════════════════════════
# 4g. DETP — HEAT BLOCK CON SENSORE CORE 2 (07/10/2026 — punto 11, approvato da Simone)
# ═════════════════════════════════════════════════════════════════════════════
_MOTIVI_DETP = []
_FACILI_DETP = ("corsa_supporto", "bici_supporto")
HEAT_MINUTI, HEAT_AGGIUNTO_MINUTI = 65, 45
ACCL_DA, ACCL_A, MANT_GG = 20, 7, 4        # acclimatazione G-20..G-7, mantenimento G-4
ACCL_MAX_SETTIMANA = 5


def _duro(s):
    """Qualita' di corsa/bici o lungo: giorni in cui l'heat block non va."""
    return (s["famiglia"] in ("bici", "corsa") and s.get("qualita")) or \
        s["key"] in ("brick_bici", "brick_corsa", "lungo_bici", "lungo_corsa")


def _heat(base_s, giorno, d, pos, minuti, sostituisce=None):
    s = dict(base_s) if base_s else _seduta("bici_supporto", giorno, 9, pos["distanza"], pos)
    s.update(key="bici_supporto", sport="Ride", famiglia="bici", qualita=False, indoor=True,
             detp="heat", durata=minuti, nominale=minuti, fisso=True, data=d)
    s["min"] = min(s.get("min", minuti), minuti)
    s["note"] = list(s.get("note") or []) + [f"heat block CORE{' al posto di ' + sostituisce if sostituisce else ''}"]
    return s


def applica_detp(sedute_sett, giorni, pos, mod, races, indisp):
    """Heat block secondo il DETP:
    - gara A segnata calda: acclimatazione nei giorni G-20..G-7 (max 5 a settimana), poi
      un mantenimento a G-4; mai negli ultimi 3 giorni;
    - altrimenti 1 a settimana: ciclo continuo W2-W4, con gara in costruzione/specifico;
      mai in scarico, taper, settimana di gara, W1 (test).
    Sostituisce una corsa/bici facile; senza un giorno facile utile, non si fa.
    Freni: banda non verde (gialla, rossa o grigia), tag malattia/sonno_disturbato/alcol/
    viaggio quel giorno;
    nella frequenza settimanale anche mai il giorno prima di una qualita' di corsa/bici o di
    un lungo. Nell'acclimatazione quel vincolo cade (8-10 sedute in 14 giorni non stanno
    altrimenti in una settimana con 4 giorni duri): la bici di qualita' diventa heat block e
    si aggiungono heat block brevi ai giorni facili."""
    if not detp.attivo(PROFILO_DETP):
        return sedute_sett
    # 07/10/2026 (decisione di Simone): heat block solo con banda VERDE. Anche la grigia
    # (baseline in calibrazione) sospende: senza baseline non ci si accorge dello stress.
    banda = (mod or {}).get("banda")
    if banda != "verde":
        _MOTIVI_DETP.append(f"DETP: heat block sospeso, banda biometrica {banda or 'assente'}")
        return sedute_sett
    calda = next((g for g in sorted(races or [], key=lambda x: x["date"])
                  if g.get("category") == "RACE_A" and detp.gara_calda(g) and g["date"] >= giorni[0]), None)
    per_g = {g: [x for x in sedute_sett if x["giorno"] == g] for g in range(7)}

    def libero_da_tag(d):
        return not any(t in (TAG_GIORNI.get(d) or []) for t in detp.TAG_STOP)

    def giorno_ok(g, prima_dura=True):
        d = giorni[g]
        if g in indisp or not libero_da_tag(d) or any(_duro(x) and not x.get("detp") for x in per_g[g]):
            return False
        if prima_dura and g < 6 and any(_duro(x) for x in per_g[g + 1]):
            return False
        return True

    def metti(g, minuti_aggiunto, ammessi):
        d = giorni[g]
        facile = next((x for x in per_g[g] if x["key"] in ammessi and not x.get("detp")), None)
        if facile:
            nuovo = _heat(facile, g, d, pos, HEAT_MINUTI, NOMI.get(facile["key"], facile["key"]))
            sedute_sett[sedute_sett.index(facile)] = nuovo
            per_g[g][per_g[g].index(facile)] = nuovo
            return True
        if minuti_aggiunto:
            nuovo = _heat(None, g, d, pos, minuti_aggiunto)
            nuovo["slot"] = len(per_g[g])
            sedute_sett.append(nuovo)
            per_g[g].append(nuovo)
            return True
        return False

    if calda:
        fin = _dt(calda["date"])
        messi = 0
        for g in range(7):
            gg = (fin - _dt(giorni[g])).days
            if messi >= ACCL_MAX_SETTIMANA or gg < 3:
                continue
            if ACCL_A <= gg <= ACCL_DA and giorno_ok(g, prima_dura=False):
                if metti(g, HEAT_AGGIUNTO_MINUTI, _FACILI_DETP):
                    messi += 1
            elif ACCL_A <= gg <= ACCL_DA:
                # giorno con la bici di qualita': diventa heat block (sezione 4.1, potenza bassa)
                q = next((x for x in per_g[g] if x["key"] == "bici_chiave"), None)
                if q and g not in indisp and libero_da_tag(giorni[g]) and metti(g, None, ("bici_chiave",)):
                    messi += 1
            elif gg == MANT_GG and giorno_ok(g, prima_dura=False):
                messi += metti(g, HEAT_AGGIUNTO_MINUTI, _FACILI_DETP)
        if messi:
            _MOTIVI_DETP.append(f"DETP: {messi} heat block per la gara calda del {calda['date']}")
        return sedute_sett
    settimana_ok = (pos["fase"] == "senza_gara" and pos.get("idx_fase") in (1, 2, 3)) or \
        (pos["fase"] in ("build", "peak") and not pos.get("scarico"))
    if not settimana_ok:
        return sedute_sett
    for g in range(7):
        if giorno_ok(g) and metti(g, None, _FACILI_DETP):
            if SWEAT_TEST_DA_FARE:
                h = next(x for x in per_g[g] if x.get("detp") == "heat")
                h.update(detp="sweat", durata=60, nominale=60)
                _MOTIVI_DETP.append(f"DETP: sweat test il {giorni[g]}")
            else:
                _MOTIVI_DETP.append(f"DETP: heat block il {giorni[g]}")
            break
    return sedute_sett



# ═════════════════════════════════════════════════════════════════════════════
# 4h. DISPONIBILITA' PER DATA (08/10/2026 — "+" sul calendario dell'app)
# ═════════════════════════════════════════════════════════════════════════════
def disponibilita_settimana(giorni):
    """{indice del giorno: minuti} della settimana: settimana tipo del profilo, con le date
    del "+" che la sostituiscono (anche per riaprire un giorno che il profilo chiude)."""
    out = dict(DISPONIBILITA)
    for g, d in enumerate(giorni):
        if d in DISPONIBILITA_DATE:
            out[g] = DISPONIBILITA_DATE[d]
    return out


def rimodula_per_disponibilita(eventi_oggi, minuti):
    """Corsa del mattino con minuti propri per oggi: [(azione, evento, minuti|None)].
    0 -> togli tutto. Altrimenti le sedute si tengono in ordine finche' stanno nei minuti;
    quella che sfora si accorcia ai minuti rimasti (se ne restano almeno 15), le altre si
    tolgono. Nessuna azione se i minuti bastano."""
    out, resto = [], minuti
    for ev in eventi_oggi:
        dur = round((ev.get("moving_time") or 0) / 60)
        if minuti == 0 or resto <= 0:
            out.append(("togli", ev, None))
        elif dur <= resto:
            resto -= dur
        elif resto >= 15:
            out.append(("accorcia", ev, resto))
            resto = 0
        else:
            out.append(("togli", ev, None))
            resto = 0
    return out


def scelta_seduta(s):
    """(sessione_tipo, params) della libreria per questa seduta. Rotazione deterministica."""
    rot = (_dt(s["data"]).isocalendar()[1] + s.get("giorno", 0)) if s.get("data") else 0
    if s.get("qualita") and s["key"] in LIBRERIA:
        per_fase = LIBRERIA[s["key"]]
        cand = per_fase.get(s.get("fase")) or per_fase["*"]
    else:
        cand = LIBRERIA_AEROBICA.get(s["key"]) or LIBRERIA.get(s["key"], {}).get("*")
    tipo, params = cand[rot % len(cand)]
    return tipo, dict(params)


TEST_PROSSIMO = None   # test periodico da mettere nella W1 (impostato dal cervello)
# 06/10/2026 (punto 6): profilo palestra dall'app {"attrezzatura": [...], "livello": ...};
# FORZA_SETTIMANE = settimane dal primo uso (onboarding dei principianti), dal cervello.
PROFILO_PALESTRA = json.loads(os.getenv("PALESTRA", "{}") or "{}")
FORZA_SETTIMANE = None
# 06/10/2026 (punto 7): previsioni {data: {temp_c, umidita_pct, ora}} dal cervello (None =
# nessuna previsione: nessuna regola del caldo) e preferenze {"converti_corsa": bool}.
METEO = None
# 07/10/2026 (punto 11): {"core2": bool, "detp": bool, "sweat": {"litri_h", "sodio_mg_l"}}
PROFILO_DETP = json.loads(os.getenv("DETP", "{}") or "{}")
SWEAT_TEST_DA_FARE = False      # impostato dal cervello (mai fatto o piu' vecchio di un anno)
PROFILO_CALDO = json.loads(os.getenv("CALDO", "{}") or "{}")


def eventi_da_libreria(s):
    """Eventi Intervals.icu della seduta. 07/10/2026: con il DETP attivo note HSI brevi
    sugli step di qualita' e brick, riga di idratazione su lunghi e brick."""
    evs = _eventi_da_libreria(s)
    if not evs or not detp.attivo(PROFILO_DETP) or s.get("detp"):
        return evs
    evs = [detp.annota(e, s["key"], s.get("qualita"), s.get("distanza")) for e in evs]
    if s["key"] in ("brick_bici", "lungo_bici", "lungo_corsa"):
        riga = detp.nota_idratazione(PROFILO_DETP.get("sweat"))
        evs[0] = dict(evs[0], description=evs[0]["description"] + riga + "\n")
    return evs


def _eventi_da_libreria(s):
    """Eventi Intervals.icu della seduta, scritti da sedute.py. None per la mobilita'."""
    if s.get("detp") in ("heat", "sweat"):
        desc = detp.descrizione_heat(s["durata"]) if s["detp"] == "heat" else detp.descrizione_sweat_test()
        nome = (f"Heat block CORE {s['durata']}min" if s["detp"] == "heat" else "Sweat test CORE 60min")
        return [{"category": "WORKOUT", "start_date_local": f"{s['data']}T00:00:00", "type": "Ride",
                 "name": nome, "description": desc, "moving_time": s["durata"] * 60,
                 "external_id": f"coach:Detp:{s['data']}"}]
    if s.get("test"):
        return soglie.eventi_test(s["test"], s["data"], sedute._POTENZA_CORSA[0])
    fase_ic = sedute.FASE_IC.get(s.get("fase"), "Base")
    lthr = sedute.get_lthr("Run")
    bike_lthr, swim_lthr = sedute.get_lthr("Ride"), sedute.get_lthr("Swim")
    params_bici = {"ambiente": "indoor" if s.get("indoor") else "outdoor"}
    if s["key"] == "mobilita":
        return None
    if s["key"] == "forza" and s.get("scheda"):     # 06/10/2026: libreria del documento
        return [palestra.evento(s["scheda"], s["data"])]
    if s["key"] == "forza":
        return sedute.eventi_seduta(s["data"], s["nome"], "Gym", s["durata"], "WeightTraining",
                                    fase=fase_ic, gym_day_type=s.get("gym_day_type", "strength"))
    if s["key"] in ("brick_bici", "brick_corsa") and s.get("brick"):
        tot, rep_min = s["brick"]
        evs = sedute.eventi_seduta(s["data"], "Brick", "Brick", tot, "Ride", lthr,
                                   dict(params_bici, rep_min=rep_min), bike_lthr, swim_lthr, fase_ic)
        return [e for e in evs if (e["type"] == "Run") == (s["key"] == "brick_corsa")]
    if s["key"] == "brick_bici" and not s.get("brick"):
        # 05/10/2026: corsa del brick tolta (es. tag infortunio): resta il lungo bici Z2
        tipo, params = "BikeCross", {"profilo": "recovery"}
    elif s.get("scelta") and (s.get("qualita") or s["key"] not in LIBRERIA):
        # 04/10/2026: seduta scelta dalla settimana tipo del ciclo continuo
        tipo, params = s["scelta"][0], dict(s["scelta"][1])
    elif s["key"] == "brick_corsa":     # 15' a seguire il lungo bici: corsa facile
        # Recovery e non Easy: Easy ha riscaldamento e defaticamento propri e su 15' ne
        # scrive 20 (misurato il 03/10); Recovery e' un blocco unico della durata data.
        tipo, params = "Recovery", {}
    else:
        tipo, params = scelta_seduta(s)
    if tipo == "BikeCross":
        params.update(params_bici)
        params = dimensiona_bici(params, s["durata"])
    nome = s["nome"]
    if tipo == "BikeCross":
        # Il nome della bici lo compone normalizza_nome_bikecross ("Bike Cross <profilo> —
        # ...") e lo tronca a 60 caratteri: senza la parte descrittiva la durata resta intera.
        nome = re.sub(r"\s*\+ opener", "", nome)
    return sedute.eventi_seduta(s["data"], nome, tipo, s["durata"], s["sport"], lthr, params,
                                bike_lthr, swim_lthr, fase_ic)


def dimensiona_bici(params, durata):
    """BikeCross soglia/VO2max hanno in intervals_coach una struttura fissa (12' + 3x13' +
    8' = 59'), perche' li' le ripetute le dimensionava l'LLM sulla durata del piano. Qui
    nessun modello: ripetute dalla durata, il resto in riscaldamento e defaticamento.
    Misurato il 03/10: bici chiave da 70-80' scritta in 36-59'."""
    p = dict(params)
    prof = p.get("profilo")
    if prof not in ("soglia", "vo2max"):
        return p
    rep, rec, r_min, r_max = (10, 3, 2, 5) if prof == "soglia" else (2, 2, 4, 10)
    rep = p.get("rep_min", rep); rec = p.get("rec_min", rec)
    wu, cd = 12, 8
    reps = max(r_min, min(r_max, (durata - wu - cd) // (rep + rec)))
    resto = max(0, durata - wu - cd - reps * (rep + rec))
    p.update(reps=reps, rep_min=rep, rec_min=rec, wu_min=wu + resto - resto // 2,
             cd_min=cd + resto // 2)
    return p


def accoppia_brick(sedute_sett):
    """Il brick e' UNA seduta in due eventi: bici + T2 + corsa == bici + corsa del piano."""
    t2_min = -(-sedute.BRICK_T2_SEC // 60) if sedute.BRICK_EVENTO_TRANSIZIONE else 0
    for b in [x for x in sedute_sett if x["key"] == "brick_bici"]:
        c = next((x for x in sedute_sett if x["key"] == "brick_corsa"
                  and x["giorno"] == b["giorno"]), None)
        if c:
            spec = (b["durata"] + c["durata"] + t2_min, b["durata"])
            b["brick"] = c["brick"] = spec


def componi(s, ctx):
    """Riempie nome e descrizione della seduta. La descrizione e' l'unica fonte degli
    step che finiscono sull'orologio."""
    if s["key"] == "forza":
        base = FORZA_PROGRAMMI[FASE_FORZA.get(s["fase"], "1")][0].split("—")[0].strip()
    elif s["qualita"]:
        base = NOMI[s["key"]]
    else:
        base = NOMI_AEROBICI.get(s["key"], NOMI[s["key"]])
    suffisso = " (biometrici)" if s.get("declassata") else ""
    # MODIFICA (03/10/2026): durata nel nome come "NNmin", non NN': l'apostrofo rompeva i
    # JSON (stessa convenzione di intervals_coach).
    s["nome"] = f"{base} {s['durata']}min{suffisso}"
    s["moving_time"] = s["durata"] * 60
    # MODIFICA (03/10/2026): descrizione e step da sedute.py (sintassi di intervals_coach);
    # solo la mobilita' resta sul builder storico di questo file.
    s["eventi"] = eventi_da_libreria(s)
    if s["eventi"] is None:
        s["descrizione"] = BUILDER[s["key"]](s, ctx)
    elif s["eventi"]:
        s["descrizione"] = s["eventi"][0]["description"]
        s["nome"] = s["eventi"][0]["name"] + suffisso if suffisso not in s["eventi"][0]["name"] else s["eventi"][0]["name"]
    return s


# ═════════════════════════════════════════════════════════════════════════════
# 5. SCRITTURA SU INTERVALS.ICU — idempotente per costruzione
# ═════════════════════════════════════════════════════════════════════════════
CATEGORIE_INDISPONIBILE = ("HOLIDAY", "SICK", "INJURED")
MARKER_NOTA = "🗓️ Piano settimanale"


# Contatore delle scritture IRREVERSIBILI verso Intervals.icu (eventi creati,
# aggiornati o cancellati). Serve a decidere se, davanti a un run morto a meta', il
# lock del giorno va rimosso per consentire un nuovo tentativo o va lasciato: se
# qualcosa e' gia' finito a calendario, un secondo run rifarebbe il lavoro.
_SCRITTURE = [0]
_NON_SCRITTE = []   # 04/10/2026: sedute rifiutate dal calendario, per il riepilogo dell'app


def _conta_scrittura():
    _SCRITTURE[0] += 1


def external_id(key, data_str):
    """Deterministico: rilanciare lo script sulla stessa settimana non crea duplicati.
    E' anche la chiave con cui si riconosce un evento come 'scritto dal coach' e quindi
    sovrascrivibile: tutto cio' che l'atleta crea a mano non ha questo id e non si tocca."""
    return f"sw:{key}:{data_str}"


_ACCENTI = {"a": "à", "e": "è", "i": "ì", "o": "ò", "u": "ù"}


def _senza_apostrofi(nome):
    nome = re.sub(r"([aeiouAEIOU])'(?=\W|$)",
                  lambda m: _ACCENTI.get(m.group(1).lower(), m.group(1)), nome or "")
    return nome.replace("'", " ").replace("  ", " ").strip()


def payload_eventi(s, data_str):
    """Eventi da scrivere per la seduta (2 per la meta' bici del brick: bici + T2).
    external_id resta lo schema di questo coach (sw:<chiave>:<data>[:parte]): e' cosi'
    che riconosce come propri gli eventi da aggiornare o pulire."""
    if not s.get("eventi"):
        return [{"category": "WORKOUT", "start_date_local": f"{data_str}T00:00:00",
                 "type": s["sport"], "name": s["nome"], "description": s["descrizione"],
                 "moving_time": s["moving_time"], "external_id": external_id(s["key"], data_str)}]
    out = []
    for i, e in enumerate(s["eventi"]):
        # MODIFICA (04/10/2026 — regola di Simone): niente apostrofi nei nomi ("NN'" e
        # "qualita'" rompevano il JSON): vocale + apostrofo diventa la vocale accentata.
        e = dict(e, name=_senza_apostrofi(e["name"]))
        eid = external_id(s["key"], data_str) + ("" if i == 0 else f":{e['type'].lower()}")
        out.append(dict(e, start_date_local=f"{data_str}T00:00:00", external_id=eid))
    return out


def payload_evento(s, data_str):
    return payload_eventi(s, data_str)[0]


def evento_allineato(esistente, payload):
    """Un evento gia' identico non si riscrive: meno chiamate, e soprattutto nessuna
    modifica gratuita di un evento che l'orologio ha gia' scaricato."""
    if not esistente:
        return False
    for campo in ("name", "type", "description"):
        if (esistente.get(campo) or "").strip() != (payload.get(campo) or "").strip():
            return False
    return int(esistente.get("moving_time") or 0) == int(payload.get("moving_time") or 0)


def giorni_evento(e):
    """Tutti i giorni coperti da un evento. Un evento di piu' giorni (ferie 14-24) ha
    end_date_local alla mezzanotte del giorno DOPO l'ultimo: fine esclusa."""
    ini = _dt(e.get("start_date_local"))
    if not ini:
        return []
    fine_raw = e.get("end_date_local") or ""
    fine = _dt(fine_raw) or ini
    if fine > ini and fine_raw[11:19] in ("", "00:00:00"):
        fine -= timedelta(days=1)
    return [(ini + timedelta(days=i)).strftime("%Y-%m-%d") for i in range((fine - ini).days + 1)]


def giorni_non_disponibili(eventi, giorni):
    """Giorni dichiarati indisponibili a calendario (ferie, malattia, infortunio) piu'
    i giorni di gara: quel giorno il piano non ci scrive sopra nulla.
    BUG FIX (04/10/2026): si guardava solo il primo giorno dell'evento; ferie dal 14 al
    24 inserite come un unico evento bloccavano solo il 14 (misurato: settimana del 19
    senza alcun giorno indisponibile)."""
    out = set()
    for e in eventi:
        cat = e.get("category") or ""
        if not (cat in CATEGORIE_INDISPONIBILE or cat.startswith("RACE")):
            continue
        giorni_e = giorni_evento(e) if cat in CATEGORIE_INDISPONIBILE else \
            [(e.get("start_date_local") or "")[:10]]
        out.update(giorni.index(d) for d in giorni_e if d in giorni)
    return out


PAUSA_MIN_GG = 5    # pausa che fa ripartire il ciclo continuo dal blocco A, carico 1/4


def pause_da_eventi(eventi):
    """Giorni di ferie/malattia/infortunio a calendario (ordinati)."""
    return sorted({d for e in eventi or [] if (e.get("category") or "") in CATEGORIE_INDISPONIBILE
                   for d in giorni_evento(e)})


def ancora_dopo_pausa(lunedi_str, giorni_pausa):
    """Lunedi' di ripartenza dopo l'ultima pausa di >= PAUSA_MIN_GG giorni consecutivi
    finita prima di lunedi_str (None se non ce n'e'). 04/10/2026: dopo 10 giorni di ferie
    si riparte da carico 1/4 del blocco A, non dalla settimana in cui ci si era fermati."""
    lun = _dt(lunedi_str)
    giorni = sorted({_dt(d) for d in giorni_pausa or [] if _dt(d)})
    corse, inizio, prec = [], None, None
    for d in giorni:
        if prec and (d - prec).days == 1:
            prec = d
            continue
        if inizio:
            corse.append((inizio, prec))
        inizio = prec = d
    if inizio:
        corse.append((inizio, prec))
    ancora = None
    for a, b in corse:
        if (b - a).days + 1 >= PAUSA_MIN_GG:
            dopo = b + timedelta(days=1)
            ripartenza = dopo + timedelta(days=(7 - dopo.weekday()) % 7)
            if ripartenza <= lun:
                ancora = ripartenza
    return ancora.strftime("%Y-%m-%d") if ancora else None


def eventi_vecchio_coach(eventi, oggi_str):
    """Sedute e note di intervals_coach.py (external_id "coach:...") da oggi in avanti."""
    return [e for e in eventi or [] if (e.get("external_id") or "").startswith("coach:")
            and e.get("category") in ("WORKOUT", "NOTE")
            and (e.get("start_date_local") or "")[:10] >= oggi_str]


def eventi_del_coach(eventi):
    return {e.get("external_id"): e for e in eventi if (e.get("external_id") or "").startswith("sw:")}


def scrivi_evento(payload, esistente, dry=False):
    payload = traduzioni.traduci_evento(payload, LINGUA)   # 13b: unico punto di scrittura
    if evento_allineato(esistente, payload):
        print(f"    = {payload['start_date_local'][:10]} — {payload['name']} (invariato)")
        return "invariato"
    if dry:
        print(f"    ~ {payload['start_date_local'][:10]} — {payload['name']} (dry-run)")
        return "dry"
    if esistente:
        r = requests.put(f"{ICU_BASE}/athlete/{ATHLETE_ID}/events/{esistente['id']}",
                         headers=ICU, json=payload)
        if r.status_code in (200, 201):
            _conta_scrittura()
            print(f"    ↻ {payload['start_date_local'][:10]} — {payload['name']}")
            return "aggiornato"
        print(f"    ❌ update {esistente['id']}: {r.text[:120]}")
        _NON_SCRITTE.append(f"{payload['start_date_local'][:10]} — {payload['name']}: {r.text[:120]}")
        return "errore"
    r = requests.post(f"{ICU_BASE}/athlete/{ATHLETE_ID}/events", headers=ICU, json=payload)
    if r.status_code not in (200, 201):
        # Un POST andato in timeout puo' essere stato eseguito lo stesso: si rilegge il
        # calendario e si cerca l'external_id invece di ritentare alla cieca.
        giorno = payload["start_date_local"][:10]
        _CACHE.pop(("events", giorno, giorno), None)
        cand = [e for e in get_events(giorno, giorno)
                if e.get("external_id") == payload["external_id"]]
        if cand:
            _conta_scrittura()
            print(f"    ✅ {giorno} — {payload['name']} (POST riuscito nonostante l'errore)")
            return "creato"
        r = requests.post(f"{ICU_BASE}/athlete/{ATHLETE_ID}/events", headers=ICU, json=payload)
    if r.status_code in (200, 201):
        _conta_scrittura()
        print(f"    ✅ {payload['start_date_local'][:10]} — {payload['name']}")
        return "creato"
    print(f"    ❌ {payload['start_date_local'][:10]} — {r.text[:150]}")
    _NON_SCRITTE.append(f"{payload['start_date_local'][:10]} — {payload['name']}: {r.text[:120]}")
    return "errore"


def cancella_evento(ev, dry=False):
    if dry:
        print(f"    ~ rimuoverei {ev.get('name')} ({ev.get('start_date_local','')[:10]})")
        return
    r = requests.delete(f"{ICU_BASE}/athlete/{ATHLETE_ID}/events/{ev['id']}", headers=ICU)
    _conta_scrittura()
    if r.status_code not in (200, 204):
        print(f"    ⚠️ evento {ev['id']} non rimosso: HTTP {r.status_code}")


# ── REPORT ───────────────────────────────────────────────────────────────────
def intensita_settimana(sedute, ctx=None, pos=None):
    """Minuti di alta intensita' e base bici+corsa, col tetto DELLA FASE. Una sola fonte
    per la riga del testo e per il campo "intensita" del riepilogo dell'app (04/10/2026).
    BUG FIX (05/10/2026): il tetto mostrato era sempre il 10% della preparazione gara;
    nel ciclo continuo vale l'80/20 (20%), che e' quello applicato da limita_alta_intensita.
    Il riepilogo mostrava "10,5% · tetto 10%" in rosso, un superamento che non c'era."""
    continuo = (pos or {}).get("fase") == "senza_gara"
    tetto = ALTA_INTENSITA_PERPETUO if continuo else ALTA_INTENSITA_MAX
    return {"alta_min": sum(minuti_alta_intensita(s, ctx) for s in sedute),
            "base_min": sum(s["durata"] for s in sedute if s["famiglia"] in ("bici", "corsa")),
            "tetto_pct": round(tetto * 100),
            "regola": ("polarizzazione 80/20: al massimo il 20% del tempo di bici e corsa "
                       "sopra la soglia" if continuo else
                       "preparazione gara: al massimo il 10% del tempo di bici e corsa "
                       "sopra la soglia")}


# 13d ter (09/10/2026, richiesta della Parte 3): ogni riga delle notifiche del coach ha un
# codice e i suoi valori, con le parole di elenco chiuso come codici (fase, blocco, tipo di
# settimana, banda, direzione, giorno, sedute). RIGHE_NOTIFICHE[testo] = righe in ordine;
# il cervello le restituisce accanto a ogni notifica (notifiche_righe).
RIGHE_NOTIFICHE = {}
GIORNI_CODICE = ["lun", "mar", "mer", "gio", "ven", "sab", "dom"]
_DIREZIONE_CODICE = {"in salita": "in_salita", "in calo": "in_calo", "stabile": "stabile",
                     "non determinabile": "non_determinabile"}


def _riga(testo, codice, **valori):
    return {"tipo": "notifica_riga", "codice": codice, "valori": valori, "testo": testo}


def _intestazione(pos):
    testo = f"{MARKER_NOTA} — {pos['etichetta']}"
    f = pos.get("fase")
    if f == "senza_gara":
        return _riga(testo, "nr_intestazione_continuo", blocco=pos.get("blocco"),
                     tipo_settimana="scarico" if pos.get("scarico") else "carico",
                     carico=None if pos.get("scarico") else str((pos.get("idx_fase") or 0) + 1))
    if f == "recupero":
        post = pos.get("post_gara") or {}
        return _riga(testo, "nr_intestazione_recupero", categoria=post.get("category"), gara=post.get("name"))
    if f == "gara":
        return _riga(testo, "nr_intestazione_gara", gara=(pos.get("gara") or {}).get("name"))
    if f == "taper":
        return _riga(testo, "nr_intestazione_taper", settimane=str(pos.get("settimane_alla_gara")))
    return _riga(testo, "nr_intestazione_fase", fase=f, n=str((pos.get("idx_fase") or 0) + 1),
                 tot=str(pos.get("len_fase")), scarico="si" if pos.get("scarico") else "no",
                 settimane=str(pos.get("settimane_alla_gara")), gara=(pos.get("gara") or {}).get("name"))


def riepilogo_righe(pos, ore_target, mod, sedute, baseline, forma, giorni, ctx=None):
    q = quote_effettive(sedute)
    cardio = sum(s["durata"] for s in sedute if s["famiglia"] != "forza")
    it = intensita_settimana(sedute, ctx, pos)
    hi, vol_bc = it["alta_min"], it["base_min"] or 1
    pct = f"{hi/vol_bc*100:.1f}"
    r = [_intestazione(pos),
         _riga(f"Distanza obiettivo: {pos['distanza']} · fase {pos['fase']}" + (" · SCARICO" if pos.get("scarico") else ""),
               "nr_distanza", distanza=pos["distanza"], fase=pos["fase"],
               tipo_settimana="scarico" if pos.get("scarico") else "normale"),
         _riga(f"Volume cardio: {cardio//60}h{cardio%60:02d} (target {ore_target}h, "
               f"fattore biometrico {mod['fattore_volume']})",
               "nr_volume", ore=str(cardio // 60), minuti=f"{cardio%60:02d}", target=str(ore_target),
               fattore=str(mod["fattore_volume"])),
         _riga(f"Ripartizione: nuoto {int(q['nuoto']*100)}% · bici {int(q['bici']*100)}% · "
               f"corsa {int(q['corsa']*100)}%", "nr_ripartizione", nuoto=str(int(q["nuoto"] * 100)),
               bici=str(int(q["bici"] * 100)), corsa=str(int(q["corsa"] * 100))),
         _riga(f"Alta intensita': {hi}' su {vol_bc}' bici+corsa ({pct}%, tetto {it['tetto_pct']}%)",
               "nr_alta_intensita", min=str(hi), tot=str(vol_bc), pct=pct, tetto=str(it["tetto_pct"])),
         _riga(f"Banda biometrica: {mod['banda'].upper()}", "nr_banda", banda=mod["banda"])]
    if baseline.get("ok"):
        nr = baseline["normal_range_ms"]
        r.append(_riga(f"HRV media 7gg {baseline['rolling7_hrv']}ms su baseline "
                       f"{baseline['baseline_hrv']}ms (range {nr[0]}-{nr[1]}ms), {baseline['direzione_7v7']}",
                       "nr_hrv", media=str(baseline["rolling7_hrv"]), baseline=str(baseline["baseline_hrv"]),
                       min=str(nr[0]), max=str(nr[1]),
                       direzione=_DIREZIONE_CODICE.get(baseline["direzione_7v7"], baseline["direzione_7v7"])))
    if forma.get("ok"):
        rampa = forma.get("rampa_7gg")
        r.append(_riga(f"CTL {forma['ctl']} · ATL {forma['atl']} · TSB {forma['tsb']}"
                       + (f" · rampa 7gg {rampa:+}" if rampa is not None else ""),
                       "nr_forma", ctl=str(forma["ctl"]), atl=str(forma["atl"]), tsb=str(forma["tsb"]),
                       rampa=(f"{rampa:+}" if rampa is not None else None)))
    if mod["motivi"]:
        r.append(_riga("Motivi della modulazione: " + "; ".join(mod["motivi"]), "nr_motivi",
                       messaggi=[messaggi.codifica(m, "motivo") for m in mod["motivi"]]))
    if mod.get("giorno_riposo") is not None:
        r.append(_riga(f"RIPOSO consigliato: {GIORNI_IT[mod['giorno_riposo']]} — "
                       f"lo chiedono i biometrici, non il calendario.",
                       "nr_riposo_consigliato", giorno=GIORNI_CODICE[mod["giorno_riposo"]]))
    r.append(_riga("", "nr_vuota"))
    for g in range(7):
        voci = sorted((s for s in sedute if s["giorno"] == g), key=lambda x: x["slot"])
        data = f"{giorni[g][8:10]}/{giorni[g][5:7]}"
        if not voci:
            r.append(_riga(f"{GIORNI_IT[g]} {data}: riposo", "nr_giorno_riposo",
                           giorno=GIORNI_CODICE[g], data=data))
            continue
        dettagli = " + ".join(f"{NOMI[s['key']]} {s['durata']}'" + (" [aerobica]" if s.get("declassata") else "")
                              for s in voci)
        r.append(_riga(f"{GIORNI_IT[g]} {data}: {dettagli}", "nr_giorno_sedute", giorno=GIORNI_CODICE[g],
                       data=data, sedute=[{"chiave": s["key"], "minuti": str(s["durata"]),
                                           "aerobica": "si" if s.get("declassata") else "no"} for s in voci]))
    for x in r:
        x["valori"] = {k: v for k, v in x["valori"].items() if v is not None}
    return r


def riepilogo(pos, ore_target, mod, sedute, baseline, forma, giorni, ctx=None):
    righe = riepilogo_righe(pos, ore_target, mod, sedute, baseline, forma, giorni, ctx)
    testo = "\n".join(x["testo"] for x in righe)
    RIGHE_NOTIFICHE[testo] = righe
    return testo


def nota_rimodulazione(oggi, mod, azioni):
    """Notifica della rimodulazione del mattino. azioni = [(testo, riga codificata)]."""
    prima = (f"{MARKER_NOTA} — rimodulazione del {oggi}, banda {mod['banda'].upper()}: "
             + "; ".join(t for t, _ in azioni))
    righe = [_riga(prima, "nr_rimodulazione", data=oggi, banda=mod["banda"], azioni=[c for _, c in azioni])]
    testo = prima
    if mod["motivi"]:
        riga = f"Motivi: {'; '.join(mod['motivi'])}"
        testo += "\n" + riga
        righe.append(_riga(riga, "nr_motivi", messaggi=[messaggi.codifica(m, "motivo") for m in mod["motivi"]]))
    RIGHE_NOTIFICHE[testo] = righe
    return testo


def _az(testo, codice, **valori):
    """Azione della rimodulazione: (testo, codice e valori) per nota_rimodulazione."""
    return testo, {"codice": codice, "valori": {k: v for k, v in valori.items() if v is not None}, "testo": testo}


def scrivi_nota(testo, data_str, esistenti, dry=False):
    payload = {"category": "NOTE", "start_date_local": f"{data_str}T00:00:00",
               "name": MARKER_NOTA, "description": testo,
               "external_id": external_id("nota", data_str)}
    return scrivi_evento(payload, esistenti.get(payload["external_id"]), dry)


def telegram(testo):
    """Consegna di una notifica all'app. 09/10/2026 (privacy): nessun invio di rete; il
    vecchio invio a Telegram di intervals_coach e' stato tolto. cervello.esegui_app la
    sostituisce con l'aggiunta a "notifiche"; da sola non fa nulla."""
    return None


# ── LOCK A FLAG SU GITHUB ────────────────────────────────────────────────────
# Stesso meccanismo di intervals_coach.py: un file .flag nella root del repo, creato
# APPENA si sa che il run fara' il lavoro (lock di avvio, non marcatore di fine — era
# quello il bug che faceva partire due run in parallelo su polling lunghi).
# Qui i flag sono due, perche' i lavori sono due:
#   sett_piano_<lunedi>.flag   -> la settimana e' gia' stata pianificata
#   sett_giorno_<data>.flag    -> la rimodulazione di oggi e' gia' stata fatta
# Fuori da GitHub Actions (run locale) si ricade sul file di stato: stessa semantica,
# stessa API, nessun ramo speciale nei chiamanti.
FLAG_PREFISSI = ("sett_piano_", "sett_giorno_")


def lock_presente(nome):
    # 09/10/2026 (privacy): i lock vivono solo nello stato sul telefono (niente GitHub)
    return nome in (carica_stato().get("flag") or [])


def lock_crea(nome):
    st = carica_stato()
    st["flag"] = sorted(set(st.get("flag") or []) | {nome})
    salva_stato(st)
    return True


def lock_rimuovi(nome, motivo="retry"):
    """Si usa SOLO se il run e' morto prima di scrivere qualcosa sul calendario: cosi'
    lo scatto successivo della finestra puo' riprovare invece di lasciare la giornata
    senza piano. Se invece qualcosa e' gia' stato scritto il lock RESTA: un secondo run
    rifarebbe il lavoro, e un piano monco e' meglio di un piano doppio."""
    st = carica_stato()
    st["flag"] = [f for f in (st.get("flag") or []) if f != nome]
    salva_stato(st)
    return True


def flag_da_ripulire(nomi, oggi_str, lunedi_str):
    """Quali flag presenti nel repo sono ormai vecchi. Le date ISO si ordinano come
    stringhe, quindi il confronto e' lessicografico; i flag correnti sono esclusi per
    costruzione. Funzione pura: e' la parte che si puo' sbagliare, ed e' testata."""
    correnti = {f"sett_piano_{lunedi_str}.flag", f"sett_giorno_{oggi_str}.flag"}
    fuori = []
    for nome in nomi:
        if nome in correnti or not nome.endswith(".flag"):
            continue
        for pre in FLAG_PREFISSI:
            if nome.startswith(pre) and nome[len(pre):-5] < (
                    lunedi_str if pre == "sett_piano_" else oggi_str):
                fuori.append(nome)
                break
    return fuori


def pulisci_flag_vecchi(oggi_str, lunedi_str):
    """I flag vecchi si tolgono dallo stato (uno al giorno si accumulerebbe)."""
    st = carica_stato()
    tenere = set(st.get("flag") or []) - set(flag_da_ripulire(st.get("flag") or [], oggi_str, lunedi_str))
    st["flag"] = sorted(tenere)
    salva_stato(st)


def carica_stato():
    try:
        with open(STATE_FILE) as f:
            return json.load(f)
    except Exception:
        return {}


def salva_stato(st):
    # BUG FIX (04/10/2026 — cervello nell'app): file temporaneo + os.replace. Android puo'
    # uccidere il processo a meta' scrittura: il file troncato si leggeva come {} e
    # flag e stato sparivano (settimana ripianificata da capo).
    tmp = f"{STATE_FILE}.tmp"
    try:
        with open(tmp, "w") as f:
            f.write(json.dumps(st, indent=2))
        os.replace(tmp, STATE_FILE)
    except OSError as e:
        print(f"  ⚠️ stato non salvato: {e}")
        try:
            os.remove(tmp)
        except OSError:
            pass


# ═════════════════════════════════════════════════════════════════════════════
# 6. ORCHESTRAZIONE
# ═════════════════════════════════════════════════════════════════════════════
def pause_recenti(lunedi_str, settimane=10):
    """Ferie/malattia a calendario nelle ultime `settimane` e nella settimana corrente."""
    ini = (_dt(lunedi_str) - timedelta(weeks=settimane)).strftime("%Y-%m-%d")
    fine = (_dt(lunedi_str) + timedelta(days=6)).strftime("%Y-%m-%d")
    return pause_da_eventi(get_events(ini, fine))


def lunedi_di(data_str):
    d = _dt(data_str) or now_local()
    return (d - timedelta(days=d.weekday())).strftime("%Y-%m-%d")


def costruisci_settimana(lunedi, races, oura_hist, wellness, activities,
                         eventi_settimana=None, zone=None, pace100=None, pause=None):
    """PIPELINE PURA: dagli input grezzi alla lista di sedute pronte da scrivere.
    Nessuna chiamata di rete qui dentro — e' questa la funzione che la suite testa."""
    giorni = [(_dt(lunedi) + timedelta(days=i)).strftime("%Y-%m-%d") for i in range(7)]

    pos = posizione_ciclo(lunedi, races,
                          pause=pause if pause is not None else pause_da_eventi(eventi_settimana))
    baseline = calc_baseline_hrv(oura_hist, lunedi)
    forma = stato_forma(wellness)
    mod = modulazione_biometrica(baseline, forma, pos["fase"])

    vol_prec = volume_reale_settimana(activities, lunedi)
    ore = volume_target_h(pos, vol_prec)

    indisp = giorni_non_disponibili(eventi_settimana or [], giorni)
    # 04/10/2026: giorni a 0 minuti nella disponibilita' del profilo = non disponibili
    # 08/10/2026 ("+" sul calendario): le date con minuti propri vincono sulla settimana tipo
    disp_sett = disponibilita_settimana(giorni)
    indisp |= {g for g, m in disp_sett.items() if m == 0}
    motivi_date = []
    for g, d in enumerate(giorni):
        if d in DISPONIBILITA_DATE:
            m = DISPONIBILITA_DATE[d]
            motivi_date.append(f"{d}: non disponibile" if m == 0 else f"{d}: solo {m}' disponibili")

    ctx = {"zone": zone or zone_karvonen(),
           "pace100": pace100 or swim_pace_sec_100m(activities),
           "settimana_pari": (_dt(lunedi).isocalendar()[1] % 2 == 0)}

    if pos["fase"] == "senza_gara":
        # 04/10/2026: ciclo continuo con settimana tipo e durate proprie (PERPETUO).
        # Niente Word ne' dimensionamento per quote: solo i freni biometrici sul volume.
        # 08/10/2026 (Parte 5): i giorni di assenza a calendario escono gia' dalla settimana
        # tipo, cosi' le sedute chiave si ricollocano nei giorni rimasti (prima si toglievano).
        sedute = settimana_perpetua(pos, escludi=indisp, disp=disp_sett)
        for m in _MOTIVI_SETTIMANA:
            if m not in mod["motivi"]:
                mod["motivi"].append(m)
        sedute = applica_vincoli(sedute, pos, indisp)
        sedute = applica_modulazione(sedute, mod, pos)
        sedute = scala_biometrica(sedute, mod)
        ore = round(sum(s["durata"] for s in sedute if s["famiglia"] != "forza") / 60, 2)
        sedute = limita_alta_intensita(sedute, pos, ctx)
    else:
        sedute = settimana_tipo(pos)
        sedute = applica_vincoli(sedute, pos, indisp)
        sedute = applica_modulazione(sedute, mod, pos)
        sedute = dimensiona(sedute, ore, mod, pos)
        sedute = applica_tetti(sedute, pos)
        sedute = limita_alta_intensita(sedute, pos, ctx)

    # 04/10/2026: profilo dall'app (minuti per giorno, tetto ore cardio) e TSS come
    # limite reale del piano (regola 1 di Simone), sulle durate definitive.
    sedute = applica_tag(sedute, giorni, lunedi, TAG_GIORNI, TAG_SEDUTE, activities)
    for m in _MOTIVI_TAG:
        if m not in mod["motivi"]:
            mod["motivi"].append(m)
    _MOTIVI_SETTIMANA[:] = [m for m in _MOTIVI_SETTIMANA if not m.startswith("forza del ")] \
        if pos["fase"] != "senza_gara" else _MOTIVI_SETTIMANA
    sedute = applica_caldo(sedute, giorni, activities)
    sedute = applica_detp(sedute, giorni, pos, mod, races, indisp)
    for m in _MOTIVI_DETP:
        if m not in mod["motivi"]:
            mod["motivi"].append(m)
    for m in _MOTIVI_CALDO:
        if m not in mod["motivi"]:
            mod["motivi"].append(m)
    sedute = assegna_schede(sedute, pos, giorni, races)
    for m in _MOTIVI_SETTIMANA:
        if m.startswith("forza del ") and m not in mod["motivi"]:
            mod["motivi"].append(m)
    sedute = applica_disponibilita(sedute, disp_sett)
    for m in motivi_date:
        if m not in mod["motivi"]:
            mod["motivi"].append(m)
    sedute = applica_tetto_ore(sedute, MAX_ORE_CARDIO)
    info_carico = limita_tss(sedute, activities, lunedi, forma, pos)
    if pos["fase"] == "senza_gara":
        ore = round(sum(s["durata"] for s in sedute if s["famiglia"] != "forza") / 60, 2)

    sedute_mod.imposta_passo_nuoto(ctx["pace100"])
    for s in sedute:
        s["data"] = giorni[s["giorno"]]
    accoppia_brick(sedute)
    for s in sedute:
        componi(s, ctx)

    return {"pos": pos, "baseline": baseline, "forma": forma, "mod": mod,
            "ore_target": ore, "volume_precedente": vol_prec, "sedute": sedute,
            "giorni": giorni, "indisponibili": sorted(indisp), "ctx": ctx,
            "carico": info_carico}


def esegui(lunedi, dry=False, solo_futuro=True, pre_lock=None, senza_attesa=False):
    """pre_lock: callback chiamata SUBITO DOPO aver verificato che i dati Oura ci sono
    e PRIMA di scrivere qualunque cosa. E' li' che nasce il lock della settimana: se
    nascesse a fine run, due scatti ravvicinati della finestra del mattino
    pianificherebbero la stessa settimana due volte."""
    print(f"\n═══ COACH SETTIMANALE — settimana del {lunedi} ═══")
    races = get_races()
    print(f"  Gare a calendario: {len(races)}")
    oura_hist, fonte_bio = storia_biometrica(get_wellness(60), TAG_GIORNI)
    print(f"  Biometria: {len(oura_hist)} notti ({fonte_bio})")
    wellness = get_wellness(42)
    activities = get_activities(42)
    fcmax, fcrest = get_athlete_hr()
    zone = zone_karvonen(fcmax, fcrest)
    print(f"  Zone Karvonen (FCmax {fcmax} / FCrest {fcrest}): "
          + " · ".join(f"{k} {v[0]}-{v[1]}" for k, v in zone.items()))

    # Anche la pianificazione settimanale vuole biometrici di stamattina: la banda
    # decide il fattore di volume dell'intera settimana. Se l'anello non ha ancora
    # sincronizzato si aspetta lo scatto dopo; all'ultimo scatto della finestra il
    # workflow passa senza_attesa e si pianifica comunque (meglio una settimana su
    # banda grigia che nessuna settimana).
    baseline_probe = calc_baseline_hrv(oura_hist, now_local().strftime("%Y-%m-%d"))
    ok_oura, motivo_oura = oura_utilizzabile(baseline_probe)
    if not ok_oura and not senza_attesa:
        print(f"  ⏳ {motivo_oura}. Pianificazione rimandata al prossimo scatto.")
        return "attesa"
    if not ok_oura:
        print(f"  ⚠️ {motivo_oura} — si pianifica lo stesso (ultimo scatto della finestra).")
    if pre_lock:
        pre_lock()

    giorni = [(_dt(lunedi) + timedelta(days=i)).strftime("%Y-%m-%d") for i in range(7)]
    eventi = get_events(giorni[0], giorni[-1])

    piano = costruisci_settimana(lunedi, races, oura_hist, wellness, activities,
                                 eventi, zone=zone, pause=pause_recenti(lunedi))
    testo = riepilogo(piano["pos"], piano["ore_target"], piano["mod"], piano["sedute"],
                      piano["baseline"], piano["forma"], giorni, piano["ctx"])
    print("\n" + testo + "\n")

    esistenti = eventi_del_coach(eventi)
    oggi = now_local().strftime("%Y-%m-%d")
    attesi = set()
    print("  Scrittura su Intervals.icu:")
    for s in sorted(piano["sedute"], key=lambda x: (x["giorno"], x["slot"])):
        if solo_futuro and s["data"] < oggi:
            # Il passato non si riscrive: una seduta saltata e' persa e non si insegue.
            continue
        for p in payload_eventi(s, s["data"]):
            attesi.add(p["external_id"])
            scrivi_evento(p, esistenti.get(p["external_id"]), dry)

    # Pulizia: sedute scritte da un run precedente che questa settimana non esistono
    # piu' (cambio di fase, riposo biometrico, giorno diventato indisponibile).
    for eid, ev in esistenti.items():
        if eid in attesi or eid.startswith("sw:nota:"):
            continue
        if (ev.get("start_date_local") or "")[:10] < oggi:
            continue
        print(f"    − rimuovo {ev.get('name')} ({ev.get('start_date_local','')[:10]}): "
              f"non e' piu' nel piano")
        cancella_evento(ev, dry)

    # BUG FIX (05/10/2026): il primo piano del cervello si e' SOMMATO alle sedute gia' a
    # calendario. La pulizia qui sopra vede solo gli eventi "sw:"; quelli scritti da
    # intervals_coach.py prima dello spegnimento ("coach:<tipo>:<data>", note comprese)
    # restavano. Da oggi in avanti si tolgono; storico e sedute a mano non si toccano.
    for ev in eventi_vecchio_coach(eventi, oggi):
        print(f"    − rimuovo {ev.get('name')} ({ev.get('start_date_local','')[:10]}): "
              f"scritta da intervals_coach")
        cancella_evento(ev, dry)

    scrivi_nota(testo, lunedi, esistenti, dry)
    telegram(testo)
    return piano


def rimodula_seduta(ev, mod, pos, ctx):
    """Cosa fare OGGI con una seduta gia' a calendario, secondo la curva di
    peggioramento. Ritorna ("tieni"|"declassa"|"togli", motivo).
    E' la stessa curva della pianificazione settimanale, applicata alla singola
    giornata: verde procede, giallo toglie la qualita' e lascia la palestra, rosso
    toglie anche la palestra, rosso persistente (o febbre) manda a riposo."""
    key = (ev.get("external_id") or "").split(":")[1] if ev.get("external_id") else ""
    if key not in CATALOGO:
        return "tieni", "non e' una seduta del coach"
    famiglia = CATALOGO[key]["famiglia"]

    if mod["riposo"]:
        return "togli", "la curva biometrica prescrive riposo"
    if mod.get("blocca_palestra") and famiglia == "forza":
        return "togli", "banda rossa: recupero, niente circuito di forza"
    if mod["banda"] in ("verde", "grigio") and not mod["motivi"]:
        # Nota: una banda che risale in giornata NON ri-promuove una seduta gia'
        # convertita in aerobica. La sessione e' gia' stata annunciata e magari gia'
        # scaricata sull'orologio: rimetterci dentro le ripetute a meta' mattina
        # sarebbe un cambio di programma, non una correzione.
        return "tieni", "banda verde"
    if famiglia == "forza":
        return "tieni", "la palestra resta"
    if not CATALOGO[key]["qualita"]:
        # Una seduta gia' aerobica non si declassa due volte: il volume della settimana
        # e' gia' stato tagliato dal fattore di banda al momento della pianificazione.
        return "tieni", "seduta gia' aerobica"
    if mod["max_qualita"] <= 0 or mod["banda"] in ("giallo", "rosso"):
        return "declassa", f"banda {mod['banda']}"
    return "tieni", "nessun peggioramento rispetto alla pianificazione"


def esegui_giornaliero(dry=False, force=False, pre_lock=None):
    """Modalita' leggera del mattino: NON ripianifica la settimana, rilegge solo i
    biometrici e applica la curva di peggioramento alla seduta di oggi. Serve perche'
    "oggi riduco" e' una decisione giornaliera, mentre il piano e' settimanale."""
    oggi = now_local().strftime("%Y-%m-%d")
    lunedi = lunedi_di(oggi)
    races = get_races()
    oura_hist, _fonte_bio = storia_biometrica(get_wellness(60), TAG_GIORNI)
    wellness = get_wellness(42)
    activities = get_activities(42)
    pos = posizione_ciclo(lunedi, races, pause=pause_recenti(lunedi))
    baseline = calc_baseline_hrv(oura_hist, oggi)
    forma = stato_forma(wellness)
    # PRIMA di qualunque decisione: il dato c'e' ed e' di stamattina?
    ok_oura, motivo_oura = oura_utilizzabile(baseline, oggi)
    if not ok_oura and not force:
        print(f"  ⏳ {oggi}: {motivo_oura}. Nessuna rimodulazione, si riprova al "
              f"prossimo giro della finestra del mattino.")
        return "attesa"

    if pre_lock:
        pre_lock()

    mod = modulazione_biometrica(baseline, forma, pos["fase"])
    print(f"  {oggi}: banda {mod['banda'].upper()}"
          + (f" — {'; '.join(mod['motivi'])}" if mod["motivi"] else ""))

    eventi = get_events(oggi, oggi)
    # 09/10/2026 (bug visto da Simone): protezione contro intervals_coach ancora attivo su
    # GitHub. Anche al mattino, e non solo nella pianificazione settimanale, si tolgono da oggi
    # a domenica le sedute e le note "coach:" (nota Riepilogo, brick/nuoto specchio Garmin).
    domenica = (_dt(lunedi_di(oggi)) + timedelta(days=6)).strftime("%Y-%m-%d")
    for ev in eventi_vecchio_coach(get_events(oggi, domenica), oggi):
        print(f"    − rimuovo {ev.get('name')} ({ev.get('start_date_local', '')[:10]}): scritta da intervals_coach")
        cancella_evento(ev, dry)
    eventi = [e for e in eventi if not (e.get("external_id") or "").startswith("coach:")]
    coach = [e for e in eventi if (e.get("external_id") or "").startswith("sw:")
             and not (e.get("external_id") or "").startswith("sw:nota:")]
    if not coach:
        print("  Nessuna seduta del coach oggi: niente da rimodulare.")
        return "niente"

    zone = zone_karvonen(*get_athlete_hr())
    ctx = {"zone": zone, "pace100": swim_pace_sec_100m(activities),
           "settimana_pari": (_dt(lunedi).isocalendar()[1] % 2 == 0)}
    azioni = []
    # 04/10/2026 (ciclo continuo, regola 3 di Simone): sabato, dopo un venerdi' oltre il
    # 125% del TSS pianificato, il brick si alleggerisce (bici -20%, corsa max 15').
    if pos["fase"] == "senza_gara" and _dt(oggi).weekday() == 5:
        ieri = (_dt(oggi) - timedelta(days=1)).strftime("%Y-%m-%d")
        if carico_ieri_eccessivo(get_events(ieri, ieri), activities, ieri):
            for p, esistente in eventi_brick_alleggeriti(coach, oggi, pos, ctx):
                scrivi_evento(p, esistente, dry)
            coach = [e for e in coach if not (e.get("external_id") or "").startswith(
                ("sw:brick_bici:", "sw:brick_corsa:"))]
            azioni.append(_az("brick alleggerito: corsa di qualita' di ieri oltre il 125% del TSS pianificato",
                              "nr_az_brick_alleggerito"))
    eff_tag = effetti_tag(oggi, oggi, TAG_GIORNI, TAG_SEDUTE, activities)
    meteo_oggi = giorno_caldo(oggi)
    # 08/10/2026 ("+" sul calendario): minuti propri per oggi -> si applicano per primi
    azioni_disp = {}
    if oggi in DISPONIBILITA_DATE:
        minuti_oggi = DISPONIBILITA_DATE[oggi]
        for azione, ev_d, minuti in rimodula_per_disponibilita(coach, minuti_oggi):
            azioni_disp[id(ev_d)] = (azione, minuti)
        m = f"{oggi}: non disponibile" if minuti_oggi == 0 else f"{oggi}: solo {minuti_oggi}' disponibili"
        if azioni_disp and m not in _MOTIVI_TAG:
            _MOTIVI_TAG.append(m)
    for ev in coach:
        if id(ev) in azioni_disp:
            azione, minuti = azioni_disp[id(ev)]
            nome = ev.get("name") or "seduta"
            if azione == "togli":
                cancella_evento(ev, dry)
                azioni.append(_az(f"{nome} rimossa — disponibilita' del giorno", "nr_az_rimossa_disponibilita",
                                  chiave=(ev.get("external_id") or "::").split(":")[1] or None, nome=nome))
            else:
                nuovo = {k: ev[k] for k in ("category", "start_date_local", "type", "name",
                                            "description", "external_id") if k in ev}
                nuovo["moving_time"] = minuti * 60
                scrivi_evento(nuovo, ev, dry)
                azioni.append(_az(f"{nome} accorciata a {minuti}' — disponibilita' del giorno",
                                  "nr_az_accorciata_disponibilita", minuti=str(minuti),
                                  chiave=(ev.get("external_id") or "::").split(":")[1] or None, nome=nome))
            continue
        sch = palestra.scheda_da_nome(ev.get("name"))
        if (sch and eff_tag["prevenzione"] and not eff_tag["riposo"]
                and palestra.e_companion(sch)):
            # 06/10/2026 (punto 6): la companion di oggi diventa la scheda di prevenzione
            prev, _ = palestra.scegli_scheda("companion", pos, PROFILO_PALESTRA,
                                             _dt(oggi).isocalendar()[1], prevenzione=eff_tag["prevenzione"])
            if prev and prev["id"] != sch["id"]:
                p = palestra.evento(prev, oggi)
                p["external_id"] = ev.get("external_id")
                scrivi_evento(p, ev, dry)
                m = f"companion sostituita con {prev['titolo']} (prevenzione {eff_tag['prevenzione']})"
                azioni.append(_az(m, "nr_az_companion_prevenzione", scheda=prev["titolo"],
                                  zona=eff_tag["prevenzione"]))
                if m not in _MOTIVI_TAG:
                    _MOTIVI_TAG.append(m)
            continue
        per_caldo = rimodula_per_caldo(ev, meteo_oggi, oggi, activities, PROFILO_CALDO) if meteo_oggi else None
        if per_caldo and per_caldo[0] == "converti":
            # 06/10/2026 (punto 7): corsa facile -> bici indoor, evento nuovo
            _, motivo, minuti = per_caldo
            s = {"key": "bici_supporto", "sport": "Ride", "famiglia": "bici", "qualita": False,
                 "declassata": False, "durata": minuti, "nominale": minuti, "indoor": True,
                 "min": CATALOGO["bici_supporto"]["min"], "prio": CATALOGO["bici_supporto"]["prio"],
                 "fase": pos["fase"], "distanza": pos["distanza"], "giorno": _dt(oggi).weekday(),
                 "slot": 0, "note": [motivo], "companion": False, "data": oggi,
                 "scelta": ("BikeCross", {"profilo": "recovery"})}
            componi(s, ctx)
            cancella_evento(ev, dry)
            for p in payload_eventi(s, oggi):
                scrivi_evento(p, None, dry)
            azioni.append((motivo, {k: v for k, v in messaggi.codifica(motivo).items() if k != 'tipo'}))
            if motivo not in _MOTIVI_CALDO:
                _MOTIVI_CALDO.append(motivo)
            continue
        if per_caldo and per_caldo[0] == "accorcia":
            _, motivo, minuti = per_caldo
            nuovo = dict(ev, moving_time=minuti * 60,
                         description=(ev.get("description") or "") + f"\n{motivo}")
            scrivi_evento({k: nuovo[k] for k in ("category", "start_date_local", "type", "name",
                                                 "description", "moving_time", "external_id")
                           if k in nuovo}, ev, dry)
            azioni.append((motivo, {k: v for k, v in messaggi.codifica(motivo).items() if k != 'tipo'}))
            if motivo not in _MOTIVI_CALDO:
                _MOTIVI_CALDO.append(motivo)
            continue
        per_tag = rimodula_per_tag(ev, eff_tag)    # 05/10/2026: i tag dell'app vengono prima
        if per_tag:
            azione, motivo, durata_max = per_tag
            # BUG FIX (05/10/2026): _MOTIVI_TAG senza prefisso, come nel run settimanale;
            # "tag: " lo aggiunge solo chi scrive gli avvisi (prima: "tag: tag: ...").
            if motivo not in _MOTIVI_TAG:
                _MOTIVI_TAG.append(motivo)
        else:
            azione, motivo = rimodula_seduta(ev, mod, pos, ctx)
            durata_max = None
        key = (ev.get("external_id") or "").split(":")[1]
        if azione == "tieni":
            print(f"    = {ev.get('name')}: invariata ({motivo})")
            continue
        if azione == "togli":
            print(f"    − {ev.get('name')}: rimossa ({motivo})")
            cancella_evento(ev, dry)
            azioni.append(_az(f"{NOMI.get(key, key)} rimossa — {motivo}", "nr_az_rimossa", chiave=key,
                              motivo=messaggi.codifica(motivo, "motivo")))
            continue
        durata = int((ev.get("moving_time") or 0) / 60) or CATALOGO[key]["min"]
        if durata_max:
            durata = min(durata, durata_max)
        s = {"key": key, "sport": ev.get("type"), "famiglia": CATALOGO[key]["famiglia"],
             "qualita": False, "declassata": True, "durata": durata,
             "min": CATALOGO[key]["min"], "prio": CATALOGO[key]["prio"],
             "fase": pos["fase"], "distanza": pos["distanza"], "nominale": durata,
             "giorno": _dt(oggi).weekday(), "slot": 1, "note": [], "companion": False}
        componi(s, ctx)
        scrivi_evento(payload_evento(s, oggi), ev, dry)
        azioni.append(_az(f"{NOMI.get(key, key)} convertita in aerobica — {motivo}", "nr_az_resa_aerobica",
                          chiave=key, motivo=messaggi.codifica(motivo, "motivo")))

    if azioni:
        telegram(nota_rimodulazione(oggi, mod, azioni))   # 13d ter: righe codificate

    return "fatto"



# ═════════════════════════════════════════════════════════════════════════════
# 7. BLOCCHI DEL RIEPILOGO PER L'APP (04/10/2026 — richiesta della Parte 3)
# ═════════════════════════════════════════════════════════════════════════════
# Stessi nomi e stesso formato del vecchio riepilogo del mattino di intervals_coach.
# Funzione pura: tutti gli ingressi arrivano dal chiamante. Un valore che questo coach
# non calcola (TSB obiettivo, volume sostenibile, tetti di carico) e' null, mai inventato.
DECISIONI = {"RECUPERO": "🔴 RECUPERO", "RIDUCI": "🟠 RIDUCI",
             "RISPETTA": "🟢 RISPETTA IL PIANO", "SPINGI": "🔵 PUOI SPINGERE"}
FAMIGLIA_TIPO = {"Swim": "nuoto", "OpenWaterSwim": "nuoto",
                 "Ride": "bici", "VirtualRide": "bici", "GravelRide": "bici",
                 "MountainBikeRide": "bici", "EBikeRide": "bici",
                 "Run": "corsa", "VirtualRun": "corsa", "TrailRun": "corsa",
                 "WeightTraining": "palestra"}


def _discipline(activities, oggi_dt, distanza):
    quote = QUOTE_DISCIPLINA.get(distanza) or QUOTE_DISCIPLINA["70.3"]
    out = {}
    for nome, giorni in (("7gg", 7), ("28gg", 28)):
        inizio = (oggi_dt - timedelta(days=giorni - 1)).strftime("%Y-%m-%d")
        ore = {f: 0.0 for f in ("nuoto", "bici", "corsa", "palestra")}
        for a in activities or []:
            f = FAMIGLIA_TIPO.get(a.get("type"))
            if f and inizio <= (a.get("start_date_local") or "")[:10] <= oggi_dt.strftime("%Y-%m-%d"):
                ore[f] += (a.get("moving_time") or 0) / 3600
        cardio = sum(ore[f] for f in ("nuoto", "bici", "corsa"))
        out[nome] = {f: {"h": round(h, 2),
                         "pct_h": (round(h / cardio * 100, 1) if cardio and f != "palestra" else None),
                         "target_pct": (round(quote[f] * 100) if f in quote else None)}
                     for f, h in ore.items()}
    return out


# 13d (09/10/2026, richiesta della Parte 3): codici da elenchi chiusi per i campi di testo
# del riepilogo fuori da "messaggi", cosi' l'app li traduce. I testi restano (italiano).
CODICI_ZONA = {"Fresco": "fresco", "Ottimale": "ottimale", "Grigia": "grigia",
               "Transizione": "transizione", "Alto rischio": "alto_rischio"}
CODICI_DIREZIONE = {"in salita": "in_salita", "in calo": "in_calo", "stabile": "stabile",
                    "non determinabile": "non_determinabile"}


def codice_azione(azione):
    """(codice, [note]) dell'azione della banda scritta da biometria.calc_baseline_biometrici."""
    a = azione or ""
    if not a:
        return None, []
    codice = ("procedi" if a.startswith("Procedi") else "riduci" if a.startswith("Riduci")
              else "recupero" if a.startswith("Recupero") else
              "dati_insufficienti" if a.startswith("Dati insufficienti") else None)
    note = []
    if "saturazione parasimpatica" in a or "oltre +1 SD sopra la baseline" in a:
        note.append("saturazione_parasimpatica")
    if "quasi azzerata" in a:
        note.append("cv_collassato")
    return codice, note


def blocchi_riepilogo(oggi_str, pos, baseline, mod, wellness, activities, eventi_settimana,
                      ore_target=None, non_scritte=None):
    oggi_dt = _dt(oggi_str)
    lun = (oggi_dt - timedelta(days=oggi_dt.weekday())).strftime("%Y-%m-%d")
    b, fc = baseline or {}, (baseline or {}).get("fc_riposo") or {}
    mod = mod or {}
    banda = b.get("banda") if b.get("ok") else None
    if mod.get("riposo"):
        codice = "RECUPERO"
    elif mod.get("banda") in ("giallo", "rosso") or mod.get("motivi"):
        codice = "RIDUCI"
    else:
        codice = "RISPETTA"
    motivo = "; ".join(mod.get("motivi") or []) or (
        f"banda {banda}: si segue il piano" if banda else "baseline in calibrazione: si segue il piano")

    reale = biometria.wellness_reale(wellness or [], activities or [])
    fo = biometria.stato_forma(reale) or {}
    fase_ic = sedute.FASE_IC.get(pos.get("fase"), "Base") + (" Scarico" if pos.get("scarico") else "")

    settimana = [a for a in activities or [] if lun <= (a.get("start_date_local") or "")[:10] <= oggi_str]
    fatte_h = sum((a.get("moving_time") or 0) for a in settimana
                  if FAMIGLIA_TIPO.get(a.get("type")) in ("nuoto", "bici", "corsa")) / 3600
    fatti = sum(a.get("icu_training_load") or 0 for a in settimana)
    oggi_fatto = any((a.get("start_date_local") or "")[:10] == oggi_str for a in activities or [])
    da_fare = [e for e in eventi_settimana or [] if e.get("category") == "WORKOUT"
               and ((e.get("start_date_local") or "")[:10] > oggi_str
                    or ((e.get("start_date_local") or "")[:10] == oggi_str and not oggi_fatto))]
    carichi = [e.get("icu_training_load") for e in da_fare]
    di_oggi = [e for e in eventi_settimana or [] if e.get("category") == "WORKOUT"
               and (e.get("start_date_local") or "")[:10] == oggi_str]

    # 04/10/2026: obiettivo di forma, TSB a domenica, carico sostenibile e tetto TSS con le
    # funzioni di intervals_coach (carico.py), che prima arrivavano sempre null.
    sost, tetto_tss = tetto_tss_settimana(activities, fo.get("ctl"), pos.get("scarico"))
    tassi = carico.tassi_carico(activities, oggi_dt)
    _, info = carico.target_forma_settimana(
        fo, {"fase": fase_ic, "volume_target_h": ore_target}, activities, oggi_dt,
        tetto=MAX_ORE_CARDIO, banda=banda, tetto_tss=tetto_tss)
    info = info or {}
    tasso = info.get("tasso") or tassi.get("medio")
    domenica = carico.tsb_domenica_da_calendario(fo, eventi_settimana, activities, oggi_dt, tasso) or {}

    avvisi = []
    if not b.get("ok"):
        avvisi.append(b.get("nota") or "baseline biometrica non disponibile")
    elif (b.get("gg_ritardo_oura") or 0) >= 1:
        avvisi.append(f"ultimo dato biometrico di {b['gg_ritardo_oura']} giorni fa")
    if fc.get("allarme"):
        avvisi.append(f"FC a riposo +{fc.get('delta')} bpm sulla baseline")
    if non_scritte:
        avvisi.append(f"{len(non_scritte)} sedute non scritte a calendario")
    if info.get("fascia") and domenica.get("tsb") is not None and not (
            info["fascia"][0] <= domenica["tsb"] <= info["fascia"][1]):
        avvisi.append(f"TSB previsto a domenica {domenica['tsb']} fuori dalla fascia attesa "
                      f"{info['fascia'][0]}/{info['fascia'][1]}")
    az_codice, az_note = codice_azione(b.get("azione"))
    nr = b.get("normal_range_ms") or (None, None)
    nota_cod = messaggi.codifica(b["nota"]) if b.get("nota") else None
    return {
        "fase_codice": pos.get("fase"),
        "decisione": {"codice": codice, "etichetta": DECISIONI[codice], "motivo": motivo,
                      "motivo_codice": ("motivi" if mod.get("motivi") else
                                        "banda_segue_piano" if banda else "calibrazione_segue_piano"),
                      "motivo_valori": {"banda": banda} if banda and not mod.get("motivi") else {}},
        "biometria": {"ok": bool(b.get("ok")), "banda": banda, "azione": b.get("azione"),
                      "nota": b.get("nota"), "hrv_7gg": b.get("rolling7_hrv"),
                      "hrv_baseline": b.get("baseline_hrv"), "pct_vs_baseline": b.get("pct_vs_baseline"),
                      "z_ln": b.get("z_ln"),
                      "range_ms": list(b["normal_range_ms"]) if b.get("normal_range_ms") else None,
                      "direzione_7v7": b.get("direzione_7v7"),
                      "gg_sotto_range": b.get("persistenza_gg_sotto"),
                      "fc_7gg": fc.get("rolling7"), "fc_baseline": fc.get("baseline"),
                      "fc_delta": fc.get("delta"), "fc_allarme": bool(fc.get("allarme")),
                      "gg_ritardo": b.get("gg_ritardo_oura"),
                      "azione_codice": az_codice, "azione_note": az_note,
                      "azione_valori": {"z": b.get("z_ln"), "pct": b.get("pct_vs_baseline"),
                                        "range_min": nr[0], "range_max": nr[1]},
                      "nota_codice": nota_cod["codice"] if nota_cod else None,
                      "nota_valori": nota_cod["valori"] if nota_cod else {},
                      "direzione_7v7_codice": CODICI_DIREZIONE.get(b.get("direzione_7v7"))},
        "forma": {"ctl": fo.get("ctl"), "atl": fo.get("atl"), "tsb": fo.get("tsb"),
                  "form_pct": fo.get("form_pct"), "zona": fo.get("zona"),
                  "zona_attesa": biometria.zona_forma_attesa(fase_ic)[0],
                  "zona_codice": CODICI_ZONA.get(fo.get("zona")),
                  "zona_attesa_codice": CODICI_ZONA.get(biometria.zona_forma_attesa(fase_ic)[0]),
                  "tsb_obiettivo": info.get("tsb_obiettivo"),
                  "fascia": list(info["fascia"]) if info.get("fascia") else None,
                  "tsb_domenica": domenica.get("tsb"),
                  "ramp": stato_forma(wellness).get("rampa_7gg"), "ramp_max": carico.MS703_RAMP_CTL_MAX},
        "discipline": _discipline(activities, oggi_dt, pos.get("distanza")),
        "volume": {"target_h": ore_target,
                   "sostenibile_h": (round(sost / tassi["medio"], 1) if sost and tassi.get("medio") else None),
                   "tetto_h": MAX_ORE_CARDIO,
                   "fatte_h": round(fatte_h, 1),
                   "restano_h": round(max(0.0, ore_target - fatte_h), 1) if ore_target else None,
                   "giorni": 7 - oggi_dt.weekday()},
        "carico": {"fatti": round(fatti), "tetto": tetto_tss, "sostenibile": sost,
                   "calendario": (round(fatti + sum(carichi)) if all(isinstance(c, (int, float))
                                                                     for c in carichi) else None)},
        "oggi": "; ".join(f"{e.get('name')} ({round((e.get('moving_time') or 0) / 60)}min)"
                          for e in di_oggi) or "Riposo",
        "avvisi": "\n".join(avvisi),
        "non_scritte": list(non_scritte or []),
    }



def assenze_nuove(lunedi, oggi):
    """08/10/2026 (Parte 5). Primo giorno (YYYY-MM-DD) da oggi in poi coperto da
    un'assenza a calendario (HOLIDAY/SICK/INJURED) in cui c'e' ancora una seduta del
    coach (sw: o coach:), oppure None. Una GET degli eventi della settimana."""
    giorni = [(_dt(lunedi) + timedelta(days=i)).strftime("%Y-%m-%d") for i in range(7)]
    eventi = get_events(giorni[0], giorni[-1])
    assenti = [g for g in giorni_non_disponibili(
        [e for e in eventi if (e.get("category") or "") in CATEGORIE_INDISPONIBILE], giorni)
        if giorni[g] >= oggi]
    if not assenti:
        return None
    nostri = {(e.get("start_date_local") or "")[:10] for e in eventi
              if (e.get("external_id") or "").startswith(("sw:", "coach:"))
              and e.get("category") == "WORKOUT"}
    toccati = sorted(giorni[g] for g in assenti if giorni[g] in nostri)
    return toccati[0] if toccati else None


def esegui_auto(modo="auto", dry=False, force=False, lunedi=None, senza_attesa=False):
    """Un avvio del coach. Ritorna (esito, piano): esito in "pianificata" | "fatto" |
    "attesa" | "gia_fatto"; piano = la settimana costruita (solo per "pianificata").
    MODIFICA (04/10/2026): estratto da main() perche' l'app lo chiama senza riga di comando."""
    now = now_local()
    oggi = now.strftime("%Y-%m-%d")
    lunedi = lunedi_di(lunedi or oggi)
    flag_sett = f"sett_piano_{lunedi}.flag"
    flag_giorno = f"sett_giorno_{oggi}.flag"
    if modo == "auto":
        # La settimana ha la precedenza: se il piano di questa settimana non esiste
        # ancora si pianifica, in qualunque giorno si sia. Solo dopo la giornata.
        modo = "giornaliero" if (lock_presente(flag_sett) and not force) else "settimanale"
        print(f"  Modalita' automatica -> {modo}")
    nome_flag = flag_sett if modo == "settimanale" else flag_giorno
    if not force and lock_presente(nome_flag):
        print(f"  {nome_flag} gia' presente: niente da fare.")
        return "gia_fatto", None

    def prendi_lock():
        if dry:
            print(f"  (dry-run: non creo {nome_flag})")
            return
        lock_crea(nome_flag)
        pulisci_flag_vecchi(oggi, lunedi)

    _SCRITTURE[0] = 0
    _NON_SCRITTE.clear()
    _MOTIVI_TAG.clear()
    _MOTIVI_CALDO.clear()
    _MOTIVI_DETP.clear()
    try:
        assenza = assenze_nuove(lunedi, oggi) if modo == "giornaliero" else None
        if modo == "giornaliero" and not assenza:
            esito = esegui_giornaliero(dry, force, pre_lock=prendi_lock)
            return esito, None
        if assenza:
            # 08/10/2026 (Parte 5): un'assenza nuova copre sedute gia' scritte da oggi in
            # poi -> la corsa del mattino ripianifica la settimana da oggi (il passato non si
            # riscrive) invece di rimodulare solo la seduta del giorno.
            print(f"  Assenza a calendario dal {assenza}: ripianifico la settimana da oggi")
        piano = esegui(lunedi, dry, pre_lock=prendi_lock, senza_attesa=senza_attesa)
        if assenza and isinstance(piano, dict):
            m = f"settimana ripianificata da oggi: assenza a calendario dal {assenza}"
            if m not in piano["mod"]["motivi"]:
                piano["mod"]["motivi"].append(m)
    except Exception:
        # Se non e' finito niente a calendario, il lock si toglie e il prossimo avvio
        # riprova. Se invece qualcosa e' gia' stato scritto il lock resta.
        if not dry and _SCRITTURE[0] == 0:
            lock_rimuovi(nome_flag)
        else:
            print(f"  ⚠️ {_SCRITTURE[0]} scritture gia' fatte: il lock resta, "
                  f"nessun nuovo tentativo oggi.")
        raise
    if piano == "attesa":
        print("  In attesa dei dati biometrici della notte.")
        return "attesa", None
    return "pianificata", piano


def main():
    ap = argparse.ArgumentParser(description="Coach settimanale multisport (deterministico)")
    ap.add_argument("--modo", choices=["auto", "settimanale", "giornaliero"], default="auto")
    ap.add_argument("--dry-run", action="store_true", help="non scrive nulla")
    ap.add_argument("--lunedi", default=None, help="YYYY-MM-DD della settimana da pianificare")
    ap.add_argument("--force", action="store_true", help="ignora i lock")
    ap.add_argument("--senza-attesa", action="store_true",
                    help="pianifica anche senza i biometrici della notte")
    args = ap.parse_args()
    if not (API_KEY or INTERVALS_TOKEN) or not ATHLETE_ID:
        print("❌ INTERVALS_API_KEY / INTERVALS_ATHLETE_ID mancanti")
        return 1
    esegui_auto(args.modo, args.dry_run, args.force, args.lunedi, args.senza_attesa)
    return 0


if __name__ == "__main__":
    sys.exit(main())
