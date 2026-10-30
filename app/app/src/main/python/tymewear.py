# -*- coding: utf-8 -*-
"""
TYMEWEAR VITALPRO (30/10/2026, Parte 13) — soglie ventilatorie dal profilo dell'app, come
Stryd e CORE 2. Nessun BLE nel cervello.
profilo.tymewear = {"attivo", "bici"|"corsa": {"data", "fonte", "vt1": {"fc", "w"|"passo", "ve"},
                    "vt2": {...}}}   (passo in s/km; "w" in corsa solo con Stryd)
1. Sedute aerobiche: tetto al VT1. Solo gli step aerobici (target che parte dalla Z1/Z2, non
   "ritmo gara") e solo con la soglia della disciplina presente; il tetto e' espresso nella stessa
   unita' dello step (FC in % LTHR, potenza in % FTP/CP, passo in % del passo soglia: mai
   target misti) e vince sempre il valore piu' basso: il VT1 non alza mai un target.
2. Coerenza: VT2 contro FTP/LTHR oltre il 5% -> avviso "Tymewear:"; mai modifiche
   automatiche di FTP o LTHR. Test piu' vecchio di 12 settimane -> da ripetere.
3. Fase successiva (non ora): aggiornamento automatico dagli stream del data field Connect IQ
   (GET /activity/{id}/streams): nessun nome di stream nel codice finche' non e' verificato
   su un'attivita' reale (certo solo TymeBreathRate).
"""
import math, re
from datetime import date

SCARTO_MAX = 0.05
SETTIMANE_VALIDITA = 12
DISCIPLINE = {"bici": ("Ride", "VirtualRide"), "corsa": ("Run", "VirtualRun")}
# zone predefinite (% della soglia) se l'atleta non le ha su Intervals.icu
HR_DEF = {"bici": [81, 89, 93, 99, 102, 106, 200], "corsa": [85, 89, 94, 99, 102, 106, 200]}
POWER_DEF = {"bici": [55, 75, 90, 105, 120, 150, 999],
             "corsa": [80, 90, 100, 115, 130, 999]}      # corsa: zone di potenza tipo Stryd (% CP)
PACE_DEF = [77.5, 87.7, 94.3, 100, 103.4, 111.5, 999]

_T_PCT = re.compile(r"(?P<lo>\d+(?:\.\d+)?)-(?P<hi>\d+(?:\.\d+)?)% (?P<u>LTHR|FTP|CP|Pace)\b")
_T_ZONA = re.compile(r"\bZ(?P<a>\d)(?:-Z(?P<b>\d))? (?P<u>HR|Power|Pace)\b")


def disciplina_di(sport):
    return next((d for d, t in DISCIPLINE.items() if sport in t), None)


def _impostazioni(atleta, sport):
    return next((x for x in (atleta or {}).get("sportSettings") or [] if sport in (x.get("types") or [])), None)


def _num(v):
    return v if isinstance(v, (int, float)) and v > 0 else None


def soglie_disc(tw, disc):
    """{"vt1": {...}, "vt2": {...}, "data"} della disciplina se Tymewear e' attivo, o None."""
    if not (tw or {}).get("attivo"):
        return None
    s = (tw or {}).get(disc) or {}
    return s if (s.get("vt1") or {}) else None


def _zone_pct(st, unita, disc):
    """Limiti superiori delle zone in % della soglia, per unita' HR/Power/Pace."""
    if unita == "HR":
        lthr, z = _num((st or {}).get("lthr")), (st or {}).get("hr_zones")
        if lthr and isinstance(z, list) and z:
            return [v / lthr * 100 for v in z]
        return HR_DEF[disc]
    if unita == "Power":
        return (st or {}).get("power_zones") or POWER_DEF[disc]
    return (st or {}).get("pace_zones") or PACE_DEF


def _range_zona(zone, a, b):
    lo = zone[a - 2] if a >= 2 else max(zone[0] - 15, 40)
    return lo, zone[(b or a) - 1]


def tetti(tw, atleta, sport):
    """{"HR": % LTHR, "Power": % FTP, "Pace": % passo soglia} del VT1, solo dove calcolabili."""
    disc = disciplina_di(sport)
    s = soglie_disc(tw, disc) if disc else None
    st = _impostazioni(atleta, sport)
    if not s or not st:
        return {}
    v1 = s.get("vt1") or {}
    out = {}
    if _num(v1.get("fc")) and _num(st.get("lthr")):
        out["HR"] = v1["fc"] / st["lthr"] * 100
    if _num(v1.get("w")) and _num(st.get("ftp")):
        out["Power"] = v1["w"] / st["ftp"] * 100
    if disc == "corsa" and _num(v1.get("passo")) and _num(st.get("threshold_pace")):
        out["Pace"] = (1000 / v1["passo"]) / st["threshold_pace"] * 100
    return out


def applica(descrizione, sport, atleta, tw):
    """Descrizione con il tetto VT1 sugli step aerobici (vedi testa del modulo)."""
    cap = tetti(tw, atleta, sport)
    if not cap or not descrizione:
        return descrizione
    disc, st = disciplina_di(sport), _impostazioni(atleta, sport)
    righe = []
    for riga in str(descrizione).split("\n"):
        righe.append(_applica_riga(riga, cap, st, disc) if riga.startswith("- ") and "(ritmo gara)" not in riga else riga)
    return "\n".join(righe)


def _applica_riga(riga, cap, st, disc):
    m = _T_PCT.search(riga)
    if m:
        unita = {"LTHR": "HR", "FTP": "Power", "CP": "Power", "Pace": "Pace"}[m.group("u")]
        lo, hi, testo_u = float(m.group("lo")), float(m.group("hi")), m.group("u")
    else:
        m = _T_ZONA.search(riga)
        if not m:
            return riga
        unita = m.group("u")
        lo, hi = _range_zona(_zone_pct(st, unita, disc), int(m.group("a")), int(m.group("b") or m.group("a")))
        testo_u = {"HR": "LTHR", "Power": "FTP", "Pace": "Pace"}[unita]
    z2_top = _zone_pct(st, unita, disc)[1]
    # step aerobico = parte dalla Z1/Z2 (anche se a cavallo con la Z3, come la bici del brick
    # "81-93% LTHR"); da Z3 in su (tempo, sweet spot, soglia) nessun tetto
    if unita not in cap or lo > z2_top + 0.5 or hi <= cap[unita]:
        return riga                       # step non aerobico, nessun tetto per l'unita', o gia' sotto
    nuovo_hi = math.floor(cap[unita])
    nuovo_lo = min(round(lo), nuovo_hi - 3)
    return riga[:m.start()] + f"{nuovo_lo}-{nuovo_hi}% {testo_u}" + riga[m.end():]


def _vecchio(data, oggi_str):
    try:
        return (date.fromisoformat(oggi_str) - date.fromisoformat(str(data)[:10])).days > SETTIMANE_VALIDITA * 7
    except (TypeError, ValueError):
        return False


def avvisi(tw, atleta, oggi_str):
    """Avvisi di coerenza (prefisso "Tymewear:"): mai modifiche a FTP o LTHR."""
    out = []
    for disc, tipi in DISCIPLINE.items():
        s = soglie_disc(tw, disc)
        st = _impostazioni(atleta, tipi[0])
        if not s:
            continue
        v2 = s.get("vt2") or {}
        if disc == "bici" and st and _num(v2.get("w")) and _num(st.get("ftp")) \
                and abs(v2["w"] - st["ftp"]) / st["ftp"] > SCARTO_MAX:
            out.append(f"Tymewear: VT2 bici {round(v2['w'])} W, FTP {round(st['ftp'])} W: ripeti il test o aggiorna l'FTP")
        if st and _num(v2.get("fc")) and _num(st.get("lthr")) and abs(v2["fc"] - st["lthr"]) / st["lthr"] > SCARTO_MAX:
            out.append(f"Tymewear: VT2 {disc} {round(v2['fc'])} bpm, LTHR {round(st['lthr'])} bpm: "
                       f"ripeti il test o aggiorna la LTHR")
        if s.get("data") and _vecchio(s["data"], oggi_str):
            out.append(f"Tymewear: test {disc} del {str(s['data'])[:10]} da ripetere (piu' di 12 settimane)")
    return out


def _passo_txt(sec):
    return f"{int(sec // 60)}:{int(round(sec % 60)):02d}/km"


def valori_txt(s, disc):
    """"VT1 149 bpm 149 W · VT2 165 bpm 190 W" (passo in corsa se non ci sono i watt)."""
    parti = []
    for nome in ("vt1", "vt2"):
        v = (s or {}).get(nome) or {}
        pezzi = [f"{round(v['fc'])} bpm"] if _num(v.get("fc")) else []
        if _num(v.get("w")):
            pezzi.append(f"{round(v['w'])} W")
        elif disc == "corsa" and _num(v.get("passo")):
            pezzi.append(_passo_txt(v["passo"]))
        if pezzi:
            parti.append(f"{nome.upper()} " + " ".join(pezzi))
    return " · ".join(parti)
