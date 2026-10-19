# -*- coding: utf-8 -*-
"""
PRECISIONE — proposte 5, 6 e 8 (19/10/2026, approvate da Simone). Solo avvisi, nessun effetto
sul piano.
6. Efficienza aerobica dalle attivita' di Intervals.icu: fattore di efficienza
   (icu_efficiency_factor: potenza o velocita' per battito) e decoupling Pa:HR delle sedute
   aerobiche lunghe (>= 45', intensita' < 85%). A parita' di FC, un'efficienza che cala del
   5% o piu' in 2 settimane rispetto alle 4 precedenti e' un segnale di affaticamento.
5. Fatica percepita dal check-in: carico per unita' di fatica = TSS / (RPE x minuti). Se le
   ultime 3 sedute valgono meno dell'80% della mediana precedente, lo stesso lavoro costa di
   piu': affaticamento che spesso precede il calo dell'HRV.
8. Ciclo mestruale (facoltativo, dati che restano sul telefono): i 7 giorni prima delle
   mestruazioni (fase luteale tardiva, HRV fisiologicamente piu' basso) diventano notti
   confondenti per la baseline e un avviso informativo.
"""
from datetime import date, timedelta

SPORT = {"bici": ("Ride", "VirtualRide"), "corsa": ("Run", "VirtualRun")}
EF_SOGLIA_PCT = -5.0
FATICA_SOGLIA = 0.80
LUTEALE_GIORNI = 7
CICLO_DEFAULT = 28


def _mediana(v):
    v = sorted(v)
    return (v[len(v) // 2] if len(v) % 2 else (v[len(v) // 2 - 1] + v[len(v) // 2]) / 2) if v else None


def _aerobica(a):
    inten = a.get("icu_intensity")
    return (a.get("moving_time") or 0) >= 45 * 60 and (inten is None or inten < 85)


def analisi(attivita, oggi_str):
    """{"bici"|"corsa": {"ef_variazione_pct", "decoupling_mediana", "sedute"}}."""
    oggi = date.fromisoformat(oggi_str)
    out = {}
    for nome, tipi in SPORT.items():
        sel = [a for a in attivita or [] if a.get("type") in tipi and _aerobica(a)
               and isinstance(a.get("icu_efficiency_factor"), (int, float))]
        gg = lambda a: (oggi - date.fromisoformat((a.get("start_date_local") or "")[:10])).days
        recenti = [a["icu_efficiency_factor"] for a in sel if 0 < gg(a) <= 14]
        prima = [a["icu_efficiency_factor"] for a in sel if 14 < gg(a) <= 42]
        var = None
        if len(recenti) >= 3 and len(prima) >= 3 and _mediana(prima):
            var = round((_mediana(recenti) / _mediana(prima) - 1) * 100, 1)
        dec = [a["decoupling"] for a in sel if isinstance(a.get("decoupling"), (int, float)) and 0 < gg(a) <= 28]
        out[nome] = {"ef_variazione_pct": var, "decoupling_mediana": _mediana(dec) if len(dec) >= 3 else None,
                     "sedute": len(sel)}
    return out


def avvisi(analisi_):
    return [f"efficienza aerobica in calo nella {nome}: {v['ef_variazione_pct']}% nelle ultime 2 settimane"
            for nome, v in (analisi_ or {}).items()
            if v.get("ef_variazione_pct") is not None and v["ef_variazione_pct"] <= EF_SOGLIA_PCT]


def fatica_percepita(checkin, attivita, oggi_str):
    """{"calo_pct", "sedute"} confrontando le ultime 3 sedute con la mediana delle precedenti
    (ultimi 28 giorni), o None se i dati non bastano."""
    import checkin as ck
    per_giorno = {}
    for a in attivita or []:
        if isinstance(a.get("icu_training_load"), (int, float)) and a.get("type"):
            per_giorno.setdefault(((a.get("start_date_local") or "")[:10], a["type"]), []).append(a)
    valori = []
    dal = (date.fromisoformat(oggi_str) - timedelta(days=28)).isoformat()
    for eid, v in sorted((checkin or {}).items(), key=lambda x: x[1]["data"]):
        if v["stato"] == "saltata" or not v.get("rpe") or not (dal <= v["data"] < oggi_str):
            continue
        tipo = ck.tipo_di(v["chiave"])
        att = (per_giorno.get((v["data"], tipo)) or [None])[0]
        if not att:
            continue
        minuti = v.get("minuti") or round((att.get("moving_time") or 0) / 60)
        if minuti > 0:
            valori.append(att["icu_training_load"] / (v["rpe"] * minuti))
    if len(valori) < 9:
        return None
    base, ult = _mediana(valori[:-3]), sum(valori[-3:]) / 3
    return {"calo_pct": round((ult / base - 1) * 100, 1) if base else None, "sedute": len(valori)}


def avviso_fatica(r):
    if not r or r.get("calo_pct") is None or r["calo_pct"] > (FATICA_SOGLIA - 1) * 100:
        return None
    return f"fatica percepita in aumento: lo stesso carico costa il {abs(r['calo_pct'])}% in piu' di fatica"


def giorni_luteali(inizi, oggi_str):
    """Date (YYYY-MM-DD) dei 7 giorni prima di ogni mestruazione, stimando il prossimo inizio
    del ciclo in corso con la durata media dei cicli registrati (28 giorni se non ce ne sono)."""
    d = sorted(date.fromisoformat(x) for x in inizi or [] if x)
    if not d:
        return set()
    durate = [(b - a).days for a, b in zip(d, d[1:]) if 21 <= (b - a).days <= 40]
    ciclo = round(sum(durate) / len(durate)) if durate else CICLO_DEFAULT
    prossimi = d[1:] + [d[-1] + timedelta(days=ciclo)]
    out = set()
    for p in prossimi:
        for i in range(1, LUTEALE_GIORNI + 1):
            out.add((p - timedelta(days=i)).isoformat())
    return {x for x in out if x <= (date.fromisoformat(oggi_str) + timedelta(days=7)).isoformat()}


def avviso_ciclo(luteali, oggi_str):
    if oggi_str in (luteali or set()):
        return "fase luteale: un HRV piu' basso e' atteso e non pesa sulla baseline"
    return None
