#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CHIUSURA RINOMINA NOCTALIX — verifica dello storico e pulizia dei 12 campi BioSleep*.
08/10/2026.

1. VERIFICA (sempre, sola lettura): per ogni giorno con un valore in un campo BioSleep*, il
   campo Noctalix* corrispondente deve avere lo STESSO valore. Elenca le differenze.
2. PULIZIA (solo con --elimina e solo se la verifica e' OK): cancella le 12 DEFINIZIONI dei
   campi personalizzati BioSleep* (DELETE /athlete/{id}/custom-item/{id}). Non tocca i campi
   Noctalix*, gli altri campi personalizzati, i grafici, ne' le altre voci della wellness.
   Senza --elimina e' una prova: mostra cosa cancellerebbe.

Uso (da python/, con l'ambiente che ha requests):
  PYTHONPATH=.:../strumenti python ../strumenti/pulisci_campi_vecchi.py --chiave LA_TUA_CHIAVE
  PYTHONPATH=.:../strumenti python ../strumenti/pulisci_campi_vecchi.py --chiave LA_TUA_CHIAVE --elimina
"""
import argparse, sys
from datetime import date, timedelta

import campi
from migra_campi_wellness import Intervals as _Base

VECCHI = set(campi.VECCHI.values())


class Intervals(_Base):
    def custom_items(self):
        r = self.http.get(f"{self.base}/custom-item", headers=self.h, timeout=60)
        r.raise_for_status()
        return r.json() or []

    def elimina_item(self, item_id):
        r = self.http.delete(f"{self.base}/custom-item/{item_id}", headers=self.h, timeout=60)
        r.raise_for_status()


def verifica(api, dal, al):
    """{"ok", "valori_controllati", "differenze": [...]} confrontando vecchi e nuovi."""
    controllati, diff = 0, []
    for riga in api.get(dal, al):
        d = riga.get("id")
        for nuovo, vecchio in campi.VECCHI.items():
            v = riga.get(vecchio)
            if v is None:
                continue
            controllati += 1
            n = riga.get(nuovo)
            if n is None:
                diff.append(f"{d}: {nuovo} vuoto ({vecchio}={v})")
            elif abs(float(n) - float(v)) > 1e-6:
                diff.append(f"{d}: {nuovo}={n} diverso da {vecchio}={v}")
    return {"ok": not diff, "valori_controllati": controllati, "differenze": diff}


def pulisci(api, dal, al, elimina=False, stampa=print):
    r = verifica(api, dal, al)
    da_eliminare = [{"id": x["id"], "codice": (x.get("content") or {}).get("code"), "nome": x.get("name")}
                    for x in api.custom_items()
                    if x.get("type") == "INPUT_FIELD" and (x.get("content") or {}).get("code") in VECCHI]
    out = dict(r, da_eliminare=da_eliminare, eliminati=0, errore=None)
    stampa(f"Verifica: {r['valori_controllati']} valori BioSleep* controllati, "
           f"{len(r['differenze'])} differenze -> {'OK' if r['ok'] else 'NON OK'}")
    for x in r["differenze"]:
        stampa("  " + x)
    stampa(f"Campi BioSleep* da eliminare: {len(da_eliminare)}")
    for x in da_eliminare:
        stampa(f"  id {x['id']}: {x['codice']}")
    if not elimina:
        stampa("PROVA: nessuna cancellazione (aggiungi --elimina per cancellare)")
        return out
    if not r["ok"]:
        out["errore"] = "storico Noctalix* incompleto: rilancia strumenti/migra_campi_wellness.py --esegui"
        stampa("NIENTE CANCELLATO: " + out["errore"])
        return out
    for x in da_eliminare:
        api.elimina_item(x["id"])
        out["eliminati"] += 1
        stampa(f"  eliminato {x['codice']}")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--chiave")
    ap.add_argument("--token")
    ap.add_argument("--dal", default=(date.today() - timedelta(days=400)).isoformat())
    ap.add_argument("--al", default=date.today().isoformat())
    ap.add_argument("--elimina", action="store_true", help="cancella davvero (solo con verifica OK)")
    a = ap.parse_args()
    if not (a.chiave or a.token):
        ap.error("serve --chiave o --token")
    r = pulisci(Intervals(a.chiave, a.token), a.dal, a.al, a.elimina)
    sys.exit(0 if r["ok"] and not r["errore"] else 1)


if __name__ == "__main__":
    main()
