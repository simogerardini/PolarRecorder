# -*- coding: utf-8 -*-
"""
DEGRADATO — attivita' non leggibili da Intervals.icu (10/10/2026, richiesta della Parte 4).

Intervals.icu potrebbe bloccare via API le attivita' dei dispositivi Garmin (come gia' fa con
quelle sincronizzate da Strava), flussi compresi, lasciando CTL e ATL nella wellness. Qui:
- riconoscere un'attivita' bloccata (presente ma senza carico ne' durata, o con una nota) e
  distinguere "attivita' bloccate" da "nessuna attivita'" e da "lista non leggibile";
- ricostruire il carico di ogni giorno invertendo la ATL della wellness. Formula verificata
  sull'account di Simone il 10/10/2026 (49 giorni, errore medio 2.55 TSS su 48 di media):
  ATL_t = k*ATL_{t-1} + (1-k)*carico_t, k = exp(-1/7), valore del giorno comprensivo del
  carico di quel giorno; quindi carico_t = (ATL_t - k*ATL_{t-1}) / (1-k).
Quando le attivita' sono leggibili nulla cambia.
"""
import math

K_ATL = math.exp(-1 / 7)
CARICO_MINIMO = 0.5      # sotto questa soglia il giorno ricostruito e' riposo (rumore dell'arrotondamento)


def attivita_bloccata(a):
    """Presente ma illeggibile: nessun carico e nessuna durata, oppure una nota di blocco."""
    if not isinstance(a, dict):
        return False
    if any(k.startswith("_note") for k in a):
        return True
    return a.get("icu_training_load") is None and a.get("moving_time") is None


def stato_attivita(attivita):
    """"non_leggibili" (la richiesta e' fallita), "nessuna", "bloccate" (almeno una), "leggibili"."""
    if attivita is None:
        return "non_leggibili"
    if not attivita:
        return "nessuna"
    return "bloccate" if any(attivita_bloccata(a) for a in attivita) else "leggibili"


def carico_da_wellness(wellness):
    """{data: carico ricostruito} dai giorni consecutivi con ATL (il primo giorno manca)."""
    w = {x.get("id"): x.get("atl") for x in wellness or [] if x.get("id")}
    giorni = sorted(w)
    out = {}
    for a, b in zip(giorni, giorni[1:]):
        if w[a] is None or w[b] is None:
            continue
        from datetime import date
        if (date.fromisoformat(b) - date.fromisoformat(a)).days != 1:
            continue
        out[b] = max(0.0, round((w[b] - K_ATL * w[a]) / (1 - K_ATL), 1))
    return out


def ripara(attivita, wellness):
    """Attivita' con i giorni illeggibili sostituiti da una pseudo-attivita' col carico
    ricostruito ("ricostruita": True, senza tipo ne' durata). Giorni leggibili invariati.
    Con la lista non leggibile (None) si ricostruiscono tutti i giorni con carico."""
    carichi = carico_da_wellness(wellness)
    if attivita is None:
        return [_pseudo(d, c) for d, c in sorted(carichi.items()) if c > CARICO_MINIMO]
    per_giorno = {}
    for a in attivita:
        per_giorno.setdefault((a.get("start_date_local") or "")[:10], []).append(a)
    out = []
    for d, lista in sorted(per_giorno.items()):
        leggibili = [a for a in lista if not attivita_bloccata(a)]
        if len(leggibili) == len(lista):
            out += lista
            continue
        out += leggibili
        resto = (carichi.get(d) or 0.0) - sum(a.get("icu_training_load") or 0 for a in leggibili)
        if resto > CARICO_MINIMO:
            out.append(_pseudo(d, round(resto, 1)))
    return out


def _pseudo(d, carico):
    return {"id": f"ricostruita:{d}", "start_date_local": f"{d}T00:00:00", "icu_training_load": carico,
            "moving_time": None, "type": None, "ricostruita": True}
