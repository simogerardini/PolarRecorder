# -*- coding: utf-8 -*-
"""
TAG AUTOMATICI (19/10/2026, proposta 4 approvata da Simone): notti confondenti riconosciute
dai dati, cosi' la baseline resta pulita anche quando l'atleta dimentica il tag.
Valgono SOLO per la baseline (esclusione delle notti confondenti), mai per l'allenamento
(niente limiti di "viaggio" ecc.): l'app li mostra come proposte da confermare.
- caldo: notte tropicale (minima esterna >= 20 °C tra le 22 e le 6) dal meteo della posizione;
- viaggio: cambio del fuso orario del telefono -> le due notti dal cambio;
- sonno_disturbato: inizio del sonno >= 90' dopo la mediana personale (orari passati dall'app).
Tutti persistiti in stato["tag_auto"] (ultimi 120 giorni).
"""
from datetime import datetime, timedelta

NOTTE_TROPICALE_C = 20.0
RITARDO_SONNO_MIN = 90
CONSERVA_GG = 120


def notti_tropicali(lat, lon, notti, sessione=None):
    """{data notte (giorno del risveglio): minima 22-06} con minima >= 20 °C. Una chiamata."""
    notti = sorted(d for d in notti or [] if d)
    if lat is None or lon is None or not notti:
        return {}
    try:
        import requests
        dal = (datetime.strptime(notti[0], "%Y-%m-%d") - timedelta(days=1)).strftime("%Y-%m-%d")
        r = (sessione or requests.Session()).get("https://api.open-meteo.com/v1/forecast", timeout=15, params={
            "latitude": round(float(lat), 2), "longitude": round(float(lon), 2), "timezone": "auto",
            "hourly": "temperature_2m", "start_date": dal, "end_date": notti[-1]})
        if r.status_code != 200:
            return {}
        h = (r.json() or {}).get("hourly") or {}
        temp = dict(zip(h.get("time") or [], h.get("temperature_2m") or []))
    except Exception:
        return {}
    out = {}
    for d in notti:
        g = datetime.strptime(d, "%Y-%m-%d")
        ore = [(g - timedelta(days=1)).strftime("%Y-%m-%dT") + f"{o:02d}:00" for o in (22, 23)] + \
              [g.strftime("%Y-%m-%dT") + f"{o:02d}:00" for o in range(0, 7)]
        valori = [temp[o] for o in ore if isinstance(temp.get(o), (int, float))]
        if len(valori) >= 6 and min(valori) >= NOTTE_TROPICALE_C:
            out[d] = round(min(valori), 1)
    return out


def _minuti_sera(hhmm):
    """Minuti dalle 18:00 (l'una di notte = 420), per confrontare orari a cavallo della mezzanotte."""
    try:
        h, m = (int(x) for x in str(hhmm).split(":")[:2])
    except (TypeError, ValueError):
        return None
    return ((h - 18) % 24) * 60 + m


def sonni_tardivi(notti_cfg):
    """{data: ritardo in minuti} delle notti iniziate >= 90' dopo la mediana personale."""
    inizi = {d: _minuti_sera((v or {}).get("inizio")) for d, v in (notti_cfg or {}).items()}
    inizi = {d: m for d, m in inizi.items() if m is not None}
    if len(inizi) < 7:
        return {}
    ordinati = sorted(inizi.values())
    mediana = ordinati[len(ordinati) // 2]
    return {d: m - mediana for d, m in inizi.items() if m - mediana >= RITARDO_SONNO_MIN}


def aggiorna(stato, oggi, fuso=None, posizione=None, notti_cfg=None, sessione=None):
    """Aggiorna stato["tag_auto"] e restituisce le proposte nuove o confermate degli ultimi
    7 giorni: [{"data", "tag", "motivo", "valori"}]."""
    tag = {d: dict(v) for d, v in (stato.get("tag_auto") or {}).items()}
    proposte = []

    def metti(d, chiave, motivo, valori):
        tag.setdefault(d, {})[chiave] = {"motivo": motivo, "valori": valori}

    # viaggio: cambio di fuso
    prima = stato.get("ultimo_fuso")
    if fuso and prima and fuso != prima:
        for i in (0, 1):
            d = (datetime.strptime(oggi, "%Y-%m-%d") + timedelta(days=i)).strftime("%Y-%m-%d")
            metti(d, "viaggio", "tag_auto_viaggio", {"da": prima, "a": fuso})
    if fuso:
        stato["ultimo_fuso"] = fuso
    # notti tropicali (ultime 7)
    if posizione:
        notti = [(datetime.strptime(oggi, "%Y-%m-%d") - timedelta(days=i)).strftime("%Y-%m-%d") for i in range(7)]
        for d, t in notti_tropicali(posizione.get("lat"), posizione.get("lon"), notti, sessione).items():
            metti(d, "caldo", "tag_auto_notte_tropicale", {"minima": str(t)})
    # sonno tardivo
    for d, ritardo in sonni_tardivi(notti_cfg).items():
        metti(d, "sonno_disturbato", "tag_auto_sonno_tardivo", {"ritardo": str(ritardo)})
    limite = (datetime.strptime(oggi, "%Y-%m-%d") - timedelta(days=CONSERVA_GG)).strftime("%Y-%m-%d")
    stato["tag_auto"] = {d: v for d, v in tag.items() if d >= limite}
    dal = (datetime.strptime(oggi, "%Y-%m-%d") - timedelta(days=7)).strftime("%Y-%m-%d")
    for d in sorted(stato["tag_auto"]):
        if d >= dal:
            for chiave, v in stato["tag_auto"][d].items():
                proposte.append({"data": d, "tag": chiave, "motivo": v["motivo"], "valori": v["valori"]})
    return proposte


def tag_per_baseline(tag_giorni, stato):
    """Tag di giorno dell'atleta + tag automatici (solo per l'esclusione dalla baseline)."""
    out = {d: list(v) for d, v in (tag_giorni or {}).items()}
    for d, v in (stato.get("tag_auto") or {}).items():
        out[d] = sorted(set(out.get(d, [])) | set(v))
    return out
