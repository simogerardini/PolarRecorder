#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MIGRAZIONE UNA TANTUM dei campi wellness BioSleep* -> Noctalix* sul tuo account Intervals.icu.
08/10/2026 (rinomina NoctaliX).

ORDINE (importante):
  1. crea i campi nuovi: cervello.prepara_account con l'app nuova o lo script (crea solo i mancanti);
  2. lancia questo script prima in PROVA, poi con --esegui;
  3. solo dopo installa la versione dell'app che scrive e legge i codici Noctalix*.

Uso (da python/):
  PYTHONPATH=.:../strumenti python3 ../strumenti/migra_campi_wellness.py --chiave LA_TUA_CHIAVE
  PYTHONPATH=.:../strumenti python3 ../strumenti/migra_campi_wellness.py --chiave LA_TUA_CHIAVE --esegui
Opzioni: --dal 2026-09-01 (default: 400 giorni fa)  --al YYYY-MM-DD (default: oggi)

Regole: di default e' una PROVA (non scrive). Copia solo i valori presenti nei campi
vecchi; NON sovrascrive un valore nuovo gia' presente; non tocca i campi vecchi (si
nascondono/cancellano a mano su Intervals.icu dopo qualche settimana). Alla fine rilegge
e confronta. Se si interrompe, rilanciarla: riprende da dove mancano i valori.
"""
import argparse, base64, sys
from datetime import date, timedelta

import campi


class Intervals:
    def __init__(self, chiave=None, token=None, atleta="0"):
        import requests
        self.http = requests.Session()
        self.h = ({"Authorization": f"Bearer {token}"} if token else
                  {"Authorization": "Basic " + base64.b64encode(f"API_KEY:{chiave}".encode()).decode()})
        self.base = f"https://intervals.icu/api/v1/athlete/{atleta}"

    def get(self, oldest, newest):
        r = self.http.get(f"{self.base}/wellness", headers=self.h, params={"oldest": oldest, "newest": newest},
                          timeout=60)
        r.raise_for_status()
        return r.json() or []

    def put_giorno(self, data, valori):
        r = self.http.put(f"{self.base}/wellness/{data}", headers=self.h, json=dict(valori, id=data), timeout=60)
        r.raise_for_status()


def _da_copiare(riga):
    """{codice nuovo: valore} da copiare per un giorno (solo vecchi presenti e nuovi vuoti)."""
    out = {}
    for nuovo, vecchio in campi.VECCHI.items():
        v = riga.get(vecchio)
        if v is not None and riga.get(nuovo) is None:
            out[nuovo] = v
    return out


def migra(api, dal, al, esegui=False, stampa=print):
    righe = api.get(dal, al)
    piano = {r["id"]: _da_copiare(r) for r in righe if r.get("id")}
    piano = {d: v for d, v in sorted(piano.items()) if v}
    rapporto = {"giorni_letti": len(righe), "giorni_da_copiare": len(piano),
                "valori_da_copiare": sum(len(v) for v in piano.values()),
                "giorni_copiati": 0, "errore": None, "verifica_ok": False, "differenze": []}
    stampa(f"{len(righe)} giorni letti, {rapporto['giorni_da_copiare']} da copiare "
           f"({rapporto['valori_da_copiare']} valori){'' if esegui else ' — PROVA, nessuna scrittura'}")
    if not esegui:
        for d, v in piano.items():
            stampa(f"  {d}: {v}")
        return rapporto
    for d, v in piano.items():
        try:
            api.put_giorno(d, v)
            rapporto["giorni_copiati"] += 1
        except Exception as e:
            rapporto["errore"] = f"{d}: {type(e).__name__}: {e} — rilancia lo script per riprendere"
            stampa("  ERRORE " + rapporto["errore"])
            return rapporto
    dopo = {r["id"]: r for r in api.get(dal, al) if r.get("id")}
    for d in dopo:
        for nuovo, vecchio in campi.VECCHI.items():
            vv, vn = dopo[d].get(vecchio), dopo[d].get(nuovo)
            if vv is not None and vn is None:
                rapporto["differenze"].append(f"{d}: {nuovo} vuoto ({vecchio}={vv})")
    rapporto["verifica_ok"] = not rapporto["differenze"]
    stampa(f"{rapporto['giorni_copiati']} giorni copiati; verifica {'OK' if rapporto['verifica_ok'] else 'FALLITA'}")
    for x in rapporto["differenze"]:
        stampa("  " + x)
    return rapporto


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--chiave", help="chiave API personale di Intervals.icu")
    ap.add_argument("--token", help="in alternativa: token OAuth")
    ap.add_argument("--dal", default=(date.today() - timedelta(days=400)).isoformat())
    ap.add_argument("--al", default=date.today().isoformat())
    ap.add_argument("--esegui", action="store_true", help="scrive davvero (senza: prova)")
    a = ap.parse_args()
    if not (a.chiave or a.token):
        ap.error("serve --chiave o --token")
    r = migra(Intervals(a.chiave, a.token), a.dal, a.al, a.esegui)
    sys.exit(1 if r["errore"] or (a.esegui and not r["verifica_ok"]) else 0)


if __name__ == "__main__":
    main()
