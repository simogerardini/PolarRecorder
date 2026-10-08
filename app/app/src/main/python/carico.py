# -*- coding: utf-8 -*-
"""
CARICO — forma attesa, TSB a domenica, carico sostenibile e tetto TSS (cervello NoctaliX).

Estratto MECCANICAMENTE da intervals_coach.py (04/10/2026, decisione di Simone):
target_forma_settimana, tsb_domenica_da_calendario, calc_carico_sostenibile,
tetto_carico_settimana, carico_fatto_settimana, tassi_carico, budget_settimana e la rampa
massima della CTL. Codice copiato senza modifiche, commenti e BUG FIX originali inclusi;
le funzioni comuni (EWMA, ora locale, zona attesa) vengono da biometria.py. Test in
test_carico.py, estratti dalla suite di intervals_coach.
"""
import os
from collections import defaultdict
from datetime import datetime, timedelta

from biometria import (RISCHIO_RELATIVO_PCT, _EWMA_ATL, _EWMA_CTL,   # noqa: F401
                       now_local, zona_forma_attesa)

_ZONE_ALTE_MIN_SEC_DEF  = 5*60

def _secondi_zone_alte(a):
    """Secondi da Z4 in su. Robusto al numero di zone configurate: nello schema a 5 zone
    come in quello Friel a 7 la soglia e' la QUARTA, quindi in entrambi i casi z[3:]."""
    z = (a or {}).get("icu_hr_zone_times") or (a or {}).get("icu_zone_times") or []
    z = [x for x in z if isinstance(x, (int, float))]
    return sum(z[3:]) if len(z) >= 4 else None

# Una sessione brick o triathlon arriva su Intervals.icu SPEZZATA in un'attivita' per
# frazione (bici, transizione, corsa...): e' il modo in cui la piattaforma importa i file
# multisport, non una scelta dello script. Il debrief pero' non puo' seguire quella
# frammentazione: scegli_attivita_debrief avrebbe agganciato il commento alla sola leg
# piu' lunga (di norma la bici), quindi il coach avrebbe giudicato UN pezzo di sessione
# ignorando proprio cio' che rende un brick un brick — le transizioni e la tenuta nella
# frazione finale sulle gambe stanche. E la nota che Simone scrive nella chat appunti
# della corsa off-bike sarebbe rimasta orfana, su un'attivita' mai commentata.
# Da qui: nelle giornate multisport il debrief e' UNO solo, riguarda l'intera sessione,
# e viene scritto come NOTE sul giorno a cui appartiene — l'unico oggetto del calendario
# Intervals.icu che sta sopra le singole attivita'. Gli appunti che Simone ha scritto
# sulle SINGOLE frazioni entrano tutti nel prompt (vedi parti_multisport ->
# ask_claude_analysis) e vanno considerati nel giudizio complessivo.
_SPORT_FAMIGLIA_MULTISPORT = {
    "Ride": "bici", "VirtualRide": "bici", "GravelRide": "bici",
    "MountainBikeRide": "bici", "EBikeRide": "bici", "Handcycle": "bici",
    "Run": "corsa", "TrailRun": "corsa", "VirtualRun": "corsa",
    "Swim": "nuoto", "OpenWaterSwim": "nuoto",
}

def _famiglia_multisport(a):
    """Disciplina 'da triathlon' di un'attivita' (bici/corsa/nuoto), None per tutto il
    resto (palestra, camminate, ecc.: non concorrono a definire una giornata multisport)."""
    return _SPORT_FAMIGLIA_MULTISPORT.get((a or {}).get("type") or "")

# ── DURATA DI SEDUTA vs TEMPO IN MOVIMENTO ────────────────────────────────
# DECISIONE (28/08/2026). Tutte le somme di volume settimanale usavano `moving_time`.
# Su corsa e bici e' la scelta giusta. In VASCA no, e non per una questione filosofica:
# e' un problema di unita' di misura fra numeratore e denominatore.
#   - Il TARGET (`volume_target_h`, `vol_disciplina`) viene dalle tabelle multisport —
#     Friel ATP, Fink "Be Iron Fit", 80/20 Triathlon, i piani 70.3 di riferimento — dove
#     le ore di nuoto sono ORE DI SEDUTA (tempo in acqua), recuperi fra le serie inclusi:
#     nessuna di quelle fonti prescrive "1.5h di sole bracciate".
#   - Il MISURATO era invece il solo tempo in movimento, che in vasca esclude i recuperi
#     e vale tipicamente il 65-75% del tempo reale.
# Confrontare le due cose significa avere un numeratore e un denominatore in unita'
# diverse, e l'errore non si annulla: `budget_settimana` dichiarava ore mancanti che erano
# gia' state fatte (e il coach aggiungeva volume), mentre `calc_volume_sostenibile`
# abbassava l'ancora e quindi il tetto. Due spinte opposte sullo stesso giorno.
# I recuperi fra le serie, del resto, sono parte dell'allenamento: la struttura di un
# 10x100 vive del recupero, e il pacchetto aerobico e' la serie completa. Il carico
# interno non e' toccato da questa scelta — TSS/load restano quelli di Intervals.icu.
# CAP DI SICUREZZA: elapsed_time e' anche il campo che esplode se l'orologio resta acceso
# nello spogliatoio. Si prende quindi il minore fra elapsed e moving x SESSIONE_MAX_RATIO:
# una vasca con recuperi pieni non supera ~1.6x il tempo in movimento.
SESSIONE_MAX_RATIO = 1.6

_SPORT_DURATA_SEDUTA = ("Swim", "OpenWaterSwim")

def durata_sessione_sec(a):
    """Secondi di SEDUTA dell'attivita': tempo in movimento per corsa/bici/palestra,
    tempo effettivo in acqua (recuperi fra le serie inclusi, con cap) per il nuoto.
    Unica definizione di 'ore allenate' del file: la usano ancora, budget e riepiloghi."""
    mov = (a or {}).get("moving_time") or 0
    if (a or {}).get("type") not in _SPORT_DURATA_SEDUTA:
        return mov
    tot = (a or {}).get("elapsed_time") or 0
    if not mov:
        return tot
    return max(mov, min(tot, round(mov * SESSIONE_MAX_RATIO)))

# ── FRENO BIOMETRICO ALLA PROGRESSIONE DEL VOLUME ──────────────────────────
# Il tetto sul volume era una costante: 110% del sostenibile, sempre, qualunque cosa
# dicessero i biometrici. Combinato con un target di fase piu' BASSO del sostenibile
# reale (7.0h di tabella contro ~9h realmente fatte) non si attivava MAI, e il sistema
# restava senza alcun organo di progressione: le ore settimanali oscillavano col rumore
# delle decisioni giornaliere invece di salire. Ora il fattore E' l'organo di
# progressione, ed e' guidato dai dati (banda della media HRV 7gg vs baseline):
#   verde  -> 1.10  cresci ~10% sopra cio' che gia' sostieni
#   giallo -> 1.00  consolida: il tetto e' esattamente cio' che gia' fai
#   rosso  -> 0.90  arretra
#   grigio -> 1.05  baseline non calcolabile: crescita prudente, non blocco
# Freni indipendenti dalla banda (ne basta uno a declassare di un gradino):
#   - FC a riposo +5bpm sostenuti sopra baseline (sovraccarico o malattia in arrivo)
#   - HRV sotto baseline-10% da 3+ giorni consecutivi
#   - rampRate CTL oltre MS703_RAMP_CTL_MAX (il carico sta gia' salendo troppo in fretta:
#     ~5-8 CTL/settimana e' il limite prudenziale citato per gli amatori)
MS703_RAMP_CTL_MAX = float(os.getenv("MS703_RAMP_CTL_MAX", 7.0))

def budget_settimana(activities, target_h, today_dt):
    """Ore gia' fatte nella settimana ISO in corso e ore che restano sul target.
    Ritorna (fatte_h, restano_h, giorni_restanti_oggi_incluso).

    Senza questo dato il modello non aveva NESSUN modo di sapere se la settimana fosse
    in ritardo o in anticipo sul target: pianificava un giorno per volta e il totale
    settimanale usciva dal caso. E' l'altra meta' della causa delle due settimane di
    carico chiuse a 9h09 e poi 8h41, in calo invece che in crescita. Il conto lo fa il
    codice, non il prompt: le attivita' sono gia' in ATTIVITA RECENTI 14gg, ma chiedere
    al modello di sommarle e' esattamente il tipo di aritmetica che sbaglia."""
    lun = (today_dt - timedelta(days=today_dt.weekday())).strftime("%Y-%m-%d")
    # MODIFICA (10/09/2026 — palestra fuori dal monte ore): una forza da 50' consumava
    # 0.8h del budget cardio della settimana (fatte 1.8h invece di 1.0h con 1h di corsa).
    fatte = sum(durata_sessione_sec(a) / 3600 for a in activities or []
                if lun <= (a.get("start_date_local") or "")[:10] <= today_dt.strftime("%Y-%m-%d")
                and not _e_palestra(a))
    return (round(fatte, 1), round(max(0.0, (target_h or 0) - fatte), 1),
            7 - today_dt.weekday())

# ── IL CARICO DELLA SETTIMANA PUNTA ALLA ZONA DI FORMA ATTESA ──────────────
# MODIFICA (25/09/2026 — richiesta di Simone): il volume della settimana non e' piu'
# solo la tabella di fase passata dal tetto: e' il volume che, a carico giornaliero
# costante, porta la Forma di domenica nella zona attesa dalla fase (carico -> Ottimale,
# scarico -> Grigia, taper/picco -> Fresco). Stessa EWMA di Intervals.icu applicata in
# avanti (proietta_tsb di periodization_coach.py), carico convertito in ore con il tasso
# TSS/h cardio MISURATO sui 28gg, carico medio della palestra sottratto (pesa sull'ATL
# ma e' fuori dal monte ore). Obiettivo: centro della fascia; in carico il fondo della
# fascia non scende sotto RISCHIO_RELATIVO_PCT della CTL. Limiti che non si toccano:
# mai sopra il tetto biometrico, si ALZA solo in fase di carico con banda verde (negli
# altri casi si puo' solo abbassare), mai sotto le ore gia' fatte ne' sotto il 50% del
# target di fase (taglio massimo del volume nel taper, Bosquet et al. 2007: 41-60%),
# nessun intervento in recupero post-gara o senza almeno 2h cardio per il tasso.
# Conseguenza osservabile: il "Volume target" del piano, la riga VOLUME e la FORMA su
# Telegram dicono lo stesso numero, e il calendario della settimana e' dosato su quello.
_TSB_FASCIA_ATTESA = {"Ottimale": (-30.0, -10.0), "Grigia": (-10.0, 5.0),
                      "Fresco": (5.0, 20.0)}

def target_forma_settimana(forma, phase, activities, today_dt, tetto=None, banda=None,
                           tetto_tss=None):
    """(volume_target_h, info) oppure (None, None) se non si puo' calcolare."""
    fo, fase = forma or {}, (phase or {}).get("fase") or ""
    ctl, atl, target = fo.get("ctl"), fo.get("atl"), (phase or {}).get("volume_target_h")
    if not ctl or atl is None or target is None or fase.startswith("Recupero"):
        return None, None
    attesa = zona_forma_attesa(fase)[0]
    lo, hi = _TSB_FASCIA_ATTESA[attesa]
    if attesa == "Ottimale":
        lo = min(hi, max(lo, RISCHIO_RELATIVO_PCT / 100 * ctl))
    obiettivo = (lo + hi) / 2
    lim = (today_dt - timedelta(days=27)).strftime("%Y-%m-%d")
    ore_c = load_c = load_gym = 0.0
    for a in activities or []:
        d, load = (a.get("start_date_local") or "")[:10], a.get("icu_training_load")
        if not d or d < lim or not isinstance(load, (int, float)) or isinstance(load, bool):
            continue
        if _e_palestra(a):
            load_gym += load
        elif durata_sessione_sec(a):
            ore_c += durata_sessione_sec(a) / 3600
            load_c += load
    if ore_c < 2 or not load_c:
        return None, None
    tasso = load_c / ore_c
    n = 7 - today_dt.weekday()

    def tsb_domenica(carico):
        # La riga di oggi e' gia' decaduta con carico zero (wellness_reale): oggi si
        # aggiunge solo il carico, dal giorno dopo EWMA piena.
        c, a = ctl + carico * (1 - _EWMA_CTL), atl + carico * (1 - _EWMA_ATL)
        for _ in range(max(n, 3) - 1):
            c = c * _EWMA_CTL + carico * (1 - _EWMA_CTL)
            a = a * _EWMA_ATL + carico * (1 - _EWMA_ATL)
        return c - a

    t0, t100 = tsb_domenica(0.0), tsb_domenica(100.0)
    carico = max(0.0, (t0 - obiettivo) / (t0 - t100) * 100.0)
    # MODIFICA (25/09/2026): il carico giornaliero non supera quello ammesso dal tetto TSS.
    lim_tss = False
    if tetto_tss:
        ammesso = max(0.0, (tetto_tss - carico_fatto_settimana(activities, today_dt)) / n)
        lim_tss = carico > ammesso
        carico = min(carico, ammesso)
    ore = max(0.0, carico - load_gym / 28) * n / tasso
    fatte = budget_settimana(activities, target, today_dt)[0]
    tetto_su = max(target, tetto) if (attesa == "Ottimale" and banda == "verde" and tetto) \
        else target
    nuovo = round(max(fatte, target * 0.5, min(fatte + ore, tetto_su)), 1)
    # BUG FIX (25/09/2026 — run delle 10:50): Telegram dichiarava il carico che porta la
    # Forma in zona (~70 TSS/g) anche quando il tetto biometrico fermava il volume a
    # 7.6h: la settimana programmata dava Forma -8.8 (grigia) a domenica, non -11.7.
    # Ora si calcola anche la Forma che il volume PROGRAMMATO produce, e il motivo per
    # cui non coincide col target (tetto biometrico o taglio massimo del 50%).
    carico_prog = max(0.0, nuovo - fatte) * tasso / n + load_gym / 28
    limite = ("tetto" if nuovo < round(fatte + ore, 1) - 0.05
              else "taglio massimo" if nuovo > round(fatte + ore, 1) + 0.05
              else "tetto carico" if lim_tss else None)
    return nuovo, {"attesa": attesa, "fascia": (round(lo, 1), hi), "tsb_obiettivo": round(obiettivo, 1),
                   "carico_gg": round(carico), "tasso": round(tasso, 1), "fatte": fatte,
                   "prima": target, "tsb_oggi": fo.get("tsb"),
                   "limitato": limite is not None, "limite": limite,
                   "carico_gg_prog": round(carico_prog),
                   "tsb_programmato": round(tsb_domenica(carico_prog), 1)}

# ── TETTO DI CARICO (TSS) ACCANTO AL TETTO DI ORE ──────────────────────────
# MODIFICA (25/09/2026 — decisione di Simone): il tetto biometrico limitava solo le ORE,
# e a parita' di ore una settimana con piu' qualita' porta piu' stress (72 TSS/g scritti
# contro i 66 stimati il 25/09). Il TSS e' la misura dello stress: ora ha un tetto suo,
# costruito come quello di ore per restare congruente:
#  - sostenibile = mediana del TSS delle ultime 4 settimane ISO complete (palestra
#    INCLUSA: fuori dal monte ore per scelta, ma e' stress e pesa sull'ATL);
#  - tetto = sostenibile x lo STESSO fattore biometrico (fattore_progressione);
#  - mai oltre il carico che alzerebbe la CTL di piu' di MS703_RAMP_CTL_MAX in una
#    settimana (soglia di rampa gia' in uso nel file).
# Si applica in tre punti: target di forma (il volume non supera il carico ammesso),
# piano (limita_carico_settimana accorcia le sedute facili prima della scrittura),
# Telegram (fatti/tetto TSS accanto alle ore). Conseguenza osservabile: la settimana
# a calendario non supera il tetto di carico anche quando rispetta quello di ore.
def calc_carico_sostenibile(activities, weeks=4, giorni_finestra=35):
    oggi = now_local().date()
    inizio_fin = oggi - timedelta(days=giorni_finestra)
    lun_corrente = oggi - timedelta(days=oggi.weekday())
    by_week = defaultdict(float)
    for a in activities or []:
        d, load = (a.get("start_date_local") or "")[:10], a.get("icu_training_load")
        if not d or not isinstance(load, (int, float)) or isinstance(load, bool):
            continue
        try:
            dt = datetime.strptime(d, "%Y-%m-%d").date()
        except ValueError:
            continue
        by_week[dt - timedelta(days=dt.weekday())] += load
    complete = sorted(l for l in by_week if inizio_fin <= l < lun_corrente)
    vals = sorted(by_week[l] for l in complete[-weeks:])
    if len(vals) < 2:
        return None
    m = len(vals) // 2
    return round(vals[m] if len(vals) % 2 else (vals[m - 1] + vals[m]) / 2)

def tetto_carico_settimana(carico_sost, fattore, ctl=None):
    if not carico_sost:
        return None
    t = carico_sost * (fattore or 1.0)
    if ctl:
        # carico settimanale costante che porta la CTL a +MS703_RAMP_CTL_MAX in 7 giorni
        t = min(t, 7 * (ctl + MS703_RAMP_CTL_MAX / (1 - _EWMA_CTL ** 7)))
    return round(t)

def carico_fatto_settimana(activities, today_dt):
    lun = (today_dt - timedelta(days=today_dt.weekday())).strftime("%Y-%m-%d")
    return round(sum(a.get("icu_training_load") or 0 for a in activities or []
                     if (a.get("start_date_local") or "")[:10] >= lun
                     and isinstance(a.get("icu_training_load"), (int, float))))

def tassi_carico(activities, today_dt, giorni=28):
    """TSS/h misurati sui 28gg: per disciplina, rapporto qualita'/facili (dalle zone FC
    alte), e TSS medio di una palestra. Servono a stimare il carico di una voce di piano."""
    lim = (today_dt - timedelta(days=giorni - 1)).strftime("%Y-%m-%d")
    fam = defaultdict(lambda: [0.0, 0.0])
    cl = {"q": [0.0, 0.0], "f": [0.0, 0.0]}
    gym = []
    for a in activities or []:
        d, load = (a.get("start_date_local") or "")[:10], a.get("icu_training_load")
        if not d or d < lim or not isinstance(load, (int, float)) or isinstance(load, bool):
            continue
        if _e_palestra(a):
            gym.append(load)
            continue
        h = durata_sessione_sec(a) / 3600
        if h <= 0:
            continue
        f = _famiglia_multisport(a) or "altro"
        fam[f][0] += load; fam[f][1] += h
        za = _secondi_zone_alte(a)
        k = "q" if (za or 0) >= _ZONE_ALTE_MIN_SEC_DEF else "f"
        cl[k][0] += load; cl[k][1] += h
    tot_l = sum(v[0] for v in fam.values()); tot_h = sum(v[1] for v in fam.values())
    medio = tot_l / tot_h if tot_h else None
    per_fam = {f: v[0] / v[1] for f, v in fam.items() if v[1] >= 1}
    kq = (cl["q"][0] / cl["q"][1]) / medio if medio and cl["q"][1] >= 1 else 1.0
    kf = (cl["f"][0] / cl["f"][1]) / medio if medio and cl["f"][1] >= 1 else 1.0
    return {"medio": medio, "fam": per_fam, "kq": max(1.0, kq), "kf": min(1.0, kf),
            "gym": sum(gym) / len(gym) if gym else 0.0}

def tsb_domenica_da_calendario(forma, eventi, activities, today_dt, tasso):
    """BUG FIX (25/09/2026): Forma di domenica col carico degli eventi GIA' SCRITTI a
    calendario da oggi a domenica (icu_training_load dell'evento; se manca, durata x
    tasso TSS/h misurato). Oggi non si conta se c'e' gia' un'attivita' (e' nella
    riga wellness). E' la verifica che la programmazione reale raggiunga il target."""
    fo = forma or {}
    if fo.get("ctl") is None or fo.get("atl") is None:
        return None
    n = 7 - today_dt.weekday()
    giorni = [(today_dt + timedelta(days=i)).strftime("%Y-%m-%d") for i in range(n)]
    fatto_oggi = any((a.get("start_date_local") or "")[:10] == giorni[0] for a in activities or [])
    # MODIFICA (25/09/2026): restituisce anche ore e carico/giorno, cosi' il messaggio
    # mostra UNA proiezione verificabile invece di stima teorica + calendario.
    carichi, ore = [0.0] * n, 0.0
    for e in eventi or []:
        d = (e.get("start_date_local") or "")[:10]
        if e.get("category") != "WORKOUT" or d not in giorni or (d == giorni[0] and fatto_oggi):
            continue
        load = e.get("icu_training_load")
        if not isinstance(load, (int, float)) or isinstance(load, bool):
            load = 0.0 if _e_palestra(e) else (e.get("moving_time") or 0) / 3600 * (tasso or 0)
        carichi[giorni.index(d)] += load
        if not _e_palestra(e):
            ore += (e.get("moving_time") or 0) / 3600
    c = fo["ctl"] + carichi[0] * (1 - _EWMA_CTL)
    a = fo["atl"] + carichi[0] * (1 - _EWMA_ATL)
    for L in carichi[1:]:
        c = c * _EWMA_CTL + L * (1 - _EWMA_CTL)
        a = a * _EWMA_ATL + L * (1 - _EWMA_ATL)
    return {"tsb": round(c - a, 1), "ore": round(ore, 1), "carico_gg": round(sum(carichi) / n),
            "carico_tot": round(sum(carichi))}

def _e_palestra(x):
    """Evento o attivita' di forza. Le due famiglie (cardio / palestra) vanno confrontate
    separatamente: un'uscita in bici non dimostra che la palestra sia stata fatta."""
    s = f"{x.get('name','')} {x.get('type','')}".lower()
    return ((x.get("type") or "") in ("WeightTraining", "Gym", "Hiit", "Crossfit")
            or any(k in s for k in ("forza", "palestra", "gym", "strength", "pesi")))
