# -*- coding: utf-8 -*-
"""
CALDO — previsioni meteo nel luogo del telefono e regole per i giorni caldi
(cervello BioSleep, roadmap punto 7, 06/10/2026).

Regole di Simone, gia' in intervals_coach.py (regole 12a/12c e soglia del gate meteo):
- caldo = temp >= 28 °C, oppure >= 26 °C con umidita' >= 70%, all'ora di allenamento
  (feriali 18:00, weekend 10:00). L'umidita' da sola non basta (a Milano sarebbe sempre);
- corsa facile -> bici indoor, profilo recovery, durata x fattore corsa->bici misurato
  sui dati reali (calc_bike_run_conversion, estratta da intervals_coach; 1.4 di ripiego);
- qualita' e lunghi di corsa NON si convertono: si accorciano (-10%, lungo -15%) con il
  consiglio di uscire nelle ore piu' fresche e di curare idratazione ed elettroliti;
- senza previsioni non cambia nulla (fail-closed: mai "caldo" senza un dato).
Previsioni: Open-Meteo (gratuito, senza chiave), posizione arrotondata a 2 decimali.
"""
import requests
from datetime import datetime, timedelta

from biometria import now_local

TEMP_CALDO = 28
TEMP_AFA, UMIDITA_AFA = 26, 70


def e_caldo(temp_c, umidita_pct):
    if not isinstance(temp_c, (int, float)):
        return False
    return temp_c >= TEMP_CALDO or (temp_c >= TEMP_AFA and (umidita_pct or 0) >= UMIDITA_AFA)


def ora_allenamento(data_str, profilo=None):
    """Feriali dopo il lavoro, weekend in mattinata (default_hour di intervals_coach)."""
    p = profilo or {}
    feriale = datetime.strptime(data_str, "%Y-%m-%d").weekday() < 5
    return int(p.get("ora_feriale", 18) if feriale else p.get("ora_weekend", 10))


def previsioni(lat, lon, date, profilo=None):
    """{data: {"temp_c", "umidita_pct", "ora"}} all'ora di allenamento, UNA chiamata.
    {} se la posizione manca o la chiamata fallisce."""
    date = sorted(d for d in (date or []) if d)
    if lat is None or lon is None or not date:
        return {}
    try:
        r = requests.Session().get("https://api.open-meteo.com/v1/forecast", timeout=15, params={
            "latitude": round(float(lat), 2), "longitude": round(float(lon), 2), "timezone": "auto",
            "hourly": "temperature_2m,relative_humidity_2m", "start_date": date[0], "end_date": date[-1]})
        if r.status_code != 200:
            return {}
        h = (r.json() or {}).get("hourly") or {}
        idx = {t: i for i, t in enumerate(h.get("time") or [])}
        out = {}
        for d in date:
            ora = ora_allenamento(d, profilo)
            i = idx.get(f"{d}T{ora:02d}:00")
            if i is None:
                continue
            serie_t, serie_u = h.get("temperature_2m") or [], h.get("relative_humidity_2m") or []
            out[d] = {"temp_c": serie_t[i] if i < len(serie_t) else None,
                      "umidita_pct": serie_u[i] if i < len(serie_u) else None, "ora": ora}
        return out
    except Exception:
        return {}


# ── CONVERSIONE CORSA -> BICI (estratta da intervals_coach.py, invariata) ──────────
# ── CONVERSIONE DINAMICA DURATA CORSA -> BICI ────────────────
# Fattore fisso di ripiego quando i dati Intervals.icu non bastano a stimarlo.
# 1.4x = punto medio del range letterario 1.3-1.5x (regola 12a): a parita' di
# stimolo aerobico serve piu' durata in bici perche' il costo metabolico/min in
# Z2 e' piu basso (non-weight-bearing, minor massa muscolare eccentrica).
BIKE_CONV_FALLBACK   = 1.4

BIKE_CONV_MIN_CLAMP  = 1.15

BIKE_CONV_MAX_CLAMP  = 1.75

def calc_bike_run_conversion(activities, days=42):
    """Fattore di conversione DINAMICO minuti-corsa -> minuti-bici, stimato dai dati
    reali dell'atleta su Intervals.icu invece di un 1.3-1.5x fisso.

    Idea: a parita' di durata, la corsa produce piu' carico aerobico al minuto della
    bici (icu_training_load = TRIMP-like, gia' normalizzato per FC/zone dal server).
    Rapporto load/min corsa vs load/min bici = quanti minuti di bici servono per
    eguagliare un minuto di corsa allo stesso stimolo. Si usano SOLO attivita' in
    fascia prevalentemente aerobica (Z1-Z2), coerenti con le sessioni easy/recovery
    che verranno sostituite; le sedute di qualita' (load/min alto) sono escluse
    perche' droghererebbero il rapporto e non sono comunque sostituibili in bici.

    Ritorna (fattore_clampato, dettaglio_dict). In assenza di dati sufficienti torna
    BIKE_CONV_FALLBACK con nota, cosi' il chiamante ha sempre un valore usabile.
    """
    soglia = (now_local() - timedelta(days=days)).strftime("%Y-%m-%d")

    def _loadmin_aerobici(tipi):
        vals = []
        for a in activities or []:
            if (a.get("type") or "") not in tipi:
                continue
            if (a.get("start_date_local") or "")[:10] < soglia:
                continue
            mt   = a.get("moving_time") or 0
            load = a.get("icu_training_load")
            if not mt or mt < 20 * 60 or not load:   # scarta sessioni troppo brevi/senza load
                continue
            lpm = load / (mt / 60.0)
            # Filtro aerobico: escludi le sedute intense (load/min alto). ~1.1 load/min
            # separa bene Z1-Z2 continuo dalle ripetute soglia/VO2max nei dati di Simone.
            if lpm <= 1.1:
                vals.append(lpm)
        return vals

    run_lpm  = _loadmin_aerobici({"Run", "TrailRun"})
    bike_lpm = _loadmin_aerobici({"Ride", "VirtualRide", "GravelRide", "MountainBikeRide"})

    # Servono almeno 2 campioni per lato per non stimare su un singolo outlier.
    if len(run_lpm) < 2 or len(bike_lpm) < 2:
        return BIKE_CONV_FALLBACK, {
            "fonte": "fallback",
            "nota": f"dati insufficienti (corse Z2:{len(run_lpm)} bici Z2:{len(bike_lpm)}, min 2 per lato)",
            "n_run": len(run_lpm), "n_bike": len(bike_lpm)}

    def _mediana(v):
        s = sorted(v); n = len(s); m = n // 2
        return s[m] if n % 2 else (s[m-1] + s[m]) / 2

    run_med  = _mediana(run_lpm)
    bike_med = _mediana(bike_lpm)
    if bike_med <= 0:
        return BIKE_CONV_FALLBACK, {"fonte": "fallback", "nota": "load/min bici nullo"}

    raw       = run_med / bike_med
    clamped   = max(BIKE_CONV_MIN_CLAMP, min(BIKE_CONV_MAX_CLAMP, raw))
    fu_clamp  = not (BIKE_CONV_MIN_CLAMP <= raw <= BIKE_CONV_MAX_CLAMP)
    return round(clamped, 2), {
        "fonte": "dati_clampato" if fu_clamp else "dati",
        "run_loadmin": round(run_med, 3), "bike_loadmin": round(bike_med, 3),
        "raw": round(raw, 2), "n_run": len(run_lpm), "n_bike": len(bike_lpm)}
