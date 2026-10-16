# -*- coding: utf-8 -*-
"""
SEDUTE AVANZATE (13/10/2026, decisione di Simone) — libreria tratta da sedute di atleti
avanzati ed elite (Blummenfelt, Alpecin, EF, Ingebrigtsen, Kipyegon, Haug, SISU...),
RIMODULATA per gli utenti:
1. mai valori assoluti: watt, passi e tempi dell'atleta diventano percentuali delle soglie
   dell'utente (bici: % LTHR come il resto del coach; corsa: % del passo soglia);
   lattato 2.5 mmol ~ 90-94% del passo soglia, 3.5 mmol ~ 95-99%; 75% FTP ~ 85-89% LTHR;
2. volume secondo il LIVELLO (impostazioni dell'app): principiante ~50%, intermedio ~70%,
   avanzato 100% delle ripetute, mai le durate da professionista; la durata totale la
   decide lo slot del coach: se il main set non ci sta si tolgono ripetute, e se ancora non
   ci sta si torna alla seduta standard della libreria;
3. alimentazione realistica (60-90 g/h), mai doppie sedute nuove (la doppia soglia
   norvegese e' diventata due sedute singole), intensita' massima solo nei blocchi di
   sviluppo/preparazione specifica (la banda gialla o rossa la declassa come ogni qualita');
4. esclusi: Rudisha 400/200 e sprint massimo da 60 m; lavori glicolitici solo per lo sprint.
"""
import os

LIVELLI = {"principiante": 0, "beginner": 0, "intermedio": 1, "intermediate": 1, "avanzato": 2, "advanced": 2}


def livello():
    return LIVELLI.get(str(os.getenv("LIVELLO") or "intermedio").lower(), 1)


def S(dur, target, intensita="active", nota=None):
    return {"dur": dur, "target": target, "int": intensita, "nota": nota}


REC_BICI, REC_CORSA = "Z2 HR", "Z1 HR"


def _r(lv, *valori):
    """Ripetute per livello (principiante, intermedio, avanzato)."""
    return valori[lv]


# ── catalogo: id -> definizione ──────────────────────────────────────────────────────
# chiavi: dove puo' stare; fasi: blocchi del ciclo continuo ("A", "B", "W1") e fasi di gara;
# distanze: None = tutte; livello_min: 0/1/2; tipo: tag della libreria (nomi e traduzioni);
# main(lv): [(ripetute, [step]), ...]; rec_blocchi: minuti tra i blocchi.
CATALOGO = {
    # BICI — soglia e sweet spot
    "bici_soglia_7x10": dict(sport="Ride", tipo="BikeCross", chiavi={"bici_chiave"}, fasi={"B", "build", "peak"},
        main=lambda lv: [(_r(lv, 3, 5, 7), [S("10m", "94-99% LTHR"), S("2m" if lv < 2 else "1m", REC_BICI, nota="recupero attivo")])]),
    "bici_soglia_3x12": dict(sport="Ride", tipo="BikeCross", chiavi={"bici_chiave"}, fasi={"B", "build"},
        main=lambda lv: [(_r(lv, 2, 3, 3), [S("12m", "94-99% LTHR"), S("4m", REC_BICI, nota="recupero attivo")])]),
    "bici_sweetspot_5x10": dict(sport="Ride", tipo="BikeCross", chiavi={"bici_chiave"}, fasi={"W1", "B", "base", "build"},
        main=lambda lv: [(_r(lv, 3, 4, 5), [S("10m", "90-94% LTHR"), S("2m", REC_BICI, nota="recupero attivo")])]),
    "bici_over_under": dict(sport="Ride", tipo="BikeCross", chiavi={"bici_chiave"}, fasi={"B", "build"}, rec_blocchi=8,
        main=lambda lv: [(_r(lv, 5, 8, 10), [S("1m", "100-106% LTHR", nota="over"), S("1m", "90-94% LTHR", nota="under")])]
        * _r(lv, 1, 2, 2)),
    # BICI — VO2max
    "bici_vo2_3x3_alternato": dict(sport="Ride", tipo="BikeCross", chiavi={"bici_chiave"}, fasi={"A", "build"}, rec_blocchi=10,
        main=lambda lv: [(_r(lv, 2, 3, 3), [S("3m", "100-106% LTHR"), S("3m", REC_BICI, nota="recupero attivo")])] * 2),
    "bici_vo2_30_15": dict(sport="Ride", tipo="BikeCross", chiavi={"bici_chiave"}, fasi={"A", "build", "peak"}, rec_blocchi=8,
        main=lambda lv: [(_r(lv, 8, 10, 12), [S("30s", "100-106% LTHR", nota="RPE 9"), S("15s", "Z1 HR")])] * _r(lv, 1, 2, 2)),
    "bici_vo2_4x3": dict(sport="Ride", tipo="BikeCross", chiavi={"bici_chiave"}, fasi={"A", "build"},
        main=lambda lv: [(_r(lv, 3, 4, 4), [S("3m", "100-106% LTHR"), S("3m", REC_BICI, nota="recupero attivo")])]),
    # BICI — forza specifica (bassa cadenza) e lungo metabolico
    "bici_torque": dict(sport="Ride", tipo="BikeCross", chiavi={"bici_chiave"}, fasi={"W1", "base", "build"}, rec_blocchi=5,
        main=lambda lv: [(_r(lv, 3, 4, 4), [S("6m", "88-92% LTHR", nota="cadenza 60rpm"), S("3m", REC_BICI, nota="recupero attivo")]),
                         (4, [S("30s", "Z4 HR", nota="cadenza 110rpm"), S("1m", REC_BICI)])]),
    "bici_lungo_metabolico": dict(sport="Ride", tipo="BikeCross", chiavi={"lungo_bici"}, fasi={"build", "peak"},
        distanze={"70.3", "full"},
        main=lambda lv: [(_r(lv, 2, 2, 3), [S(_r(lv, "20m", "25m", "30m"), "85-89% LTHR"), S("10m", REC_BICI, nota="recupero attivo")])]),
    # CORSA — soglia (anche la doppia soglia norvegese, in due sedute separate)
    "corsa_doppia_soglia_6min": dict(sport="Run", tipo="Threshold", chiavi={"corsa_chiave"}, fasi={"B", "build"},
        main=lambda lv: [(_r(lv, 3, 4, 5), [S("6m", "90-94% Pace", nota="sotto soglia, controllato"), S("1m", REC_CORSA)])]),
    "corsa_doppia_soglia_1000": dict(sport="Run", tipo="Threshold", chiavi={"corsa_chiave"}, fasi={"B", "build"},
        main=lambda lv: [(_r(lv, 5, 7, 10), [S("1km", "95-99% Pace", nota="sotto soglia, controllato"), S("1m", REC_CORSA)])]),
    "corsa_sotto_soglia_5x8": dict(sport="Run", tipo="Threshold", chiavi={"corsa_chiave"}, fasi={"B", "build"},
        main=lambda lv: [(_r(lv, 3, 4, 5), [S("8m", "92-96% Pace"), S("90s", REC_CORSA)])]),
    "corsa_soglia_4x10": dict(sport="Run", tipo="Threshold", chiavi={"corsa_chiave"}, fasi={"B", "build", "peak"},
        main=lambda lv: [(_r(lv, 2, 3, 4), [S("10m", "97-101% Pace"), S("2m", REC_CORSA)])]),
    "corsa_tempo_3x12": dict(sport="Run", tipo="Threshold", chiavi={"corsa_chiave"}, fasi={"W1", "base", "build"},
        main=lambda lv: [(_r(lv, 2, 3, 3), [S("12m", "90-94% Pace"), S("3m", REC_CORSA)])]),
    "corsa_sopra_soglia_1km": dict(sport="Run", tipo="Threshold", chiavi={"corsa_chiave"}, fasi={"B", "build", "peak"},
        main=lambda lv: [(_r(lv, 5, 6, 8), [S("1km", "101-104% Pace"), S("90s", REC_CORSA)])]),
    "corsa_progressione_3km": dict(sport="Run", tipo="Progressive", chiavi={"corsa_chiave"}, fasi={"B", "build", "peak"},
        main=lambda lv: [(_r(lv, 2, 3, 5), [S("1km", "96-99% Pace"), S("1km", "99-102% Pace"),
                                             S("1km", "102-105% Pace", nota="progressione")])]),
    # CORSA — VO2max e potenza aerobica
    "corsa_vo2_5x4": dict(sport="Run", tipo="Interval", chiavi={"corsa_chiave"}, fasi={"A", "build", "peak"},
        main=lambda lv: [(_r(lv, 3, 4, 5), [S("4m", "106-110% Pace"), S("3m", REC_CORSA)])]),
    "corsa_potenza_8x3": dict(sport="Run", tipo="Interval", chiavi={"corsa_chiave"}, fasi={"A", "build"},
        main=lambda lv: [(_r(lv, 5, 6, 8), [S("3m", "104-108% Pace"), S("2m", REC_CORSA)])]),
    "corsa_velocita_critica_1km": dict(sport="Run", tipo="Interval", chiavi={"corsa_chiave"}, fasi={"A", "build", "peak"},
        main=lambda lv: [(_r(lv, 4, 5, 6), [S("1km", "103-106% Pace"), S("90s", REC_CORSA)])]),
    "corsa_vo2_10x2": dict(sport="Run", tipo="Interval", chiavi={"corsa_chiave"}, fasi={"A", "build"},
        main=lambda lv: [(_r(lv, 6, 8, 10), [S("2m", "106-110% Pace"), S("1m", REC_CORSA)])]),
    "corsa_piramide": dict(sport="Run", tipo="Interval", chiavi={"corsa_chiave"}, fasi={"peak"}, rec_blocchi=2,
        distanze={"sprint", "olimpico"},
        main=lambda lv: [(_r(lv, 1, 1, 2), [S("1km", "103-106% Pace"), S("90s", REC_CORSA)]),
                         (_r(lv, 1, 2, 3), [S("0.8km", "105-108% Pace"), S("90s", REC_CORSA)]),
                         (_r(lv, 2, 2, 4), [S("0.6km", "107-110% Pace"), S("90s", REC_CORSA)]),
                         (_r(lv, 2, 3, 5), [S("0.4km", "110-114% Pace"), S("60s", REC_CORSA)]),
                         (_r(lv, 3, 4, 6), [S("0.15km", "Z5 Pace", nota="progressione"), S("60s", REC_CORSA)])]),
    "corsa_progressione_kipyegon": dict(sport="Run", tipo="Progressive", chiavi={"corsa_chiave"}, fasi={"peak"},
        rec_blocchi=3,
        main=lambda lv: [(1, [S("2km", "98-101% Pace")]),
                         (_r(lv, 1, 2, 3), [S("1km", "103-106% Pace"), S("90s", REC_CORSA)]),
                         (_r(lv, 3, 5, 7), [S("0.4km", "108-112% Pace", nota="progressione"), S("90s", REC_CORSA)])]),
    # CORSA — neuromuscolare e glicolitici (solo sprint)
    "corsa_sprint_salita": dict(sport="Run", tipo="HillSprints", chiavi={"corsa_chiave"}, fasi={"A", "build", "peak"},
        main=lambda lv: [(1, [S("20m", "Z2 HR")]),
                         (_r(lv, 6, 8, 8), [S("8s", "Z5 HR", nota="sprint in salita"), S("2m", REC_CORSA)])]),
    "corsa_glicolitica_45s": dict(sport="Run", tipo="Repetition", chiavi={"corsa_chiave"}, fasi={"peak"},
        distanze={"sprint"}, livello_min=1,
        main=lambda lv: [(_r(lv, 4, 6, 8), [S("45s", "Z5 HR", nota="RPE 9"), S("3m", REC_CORSA)])]),
    "corsa_300m": dict(sport="Run", tipo="Repetition", chiavi={"corsa_chiave"}, fasi={"peak"}, distanze={"sprint"},
        livello_min=1,
        main=lambda lv: [(_r(lv, 4, 5, 6), [S("0.3km", "112-116% Pace"), S("4m", REC_CORSA)])]),
    # CORSA — lungo con ritmo gara (70.3 e full)
    "corsa_lungo_ritmo_gara": dict(sport="Run", tipo="Long", chiavi={"lungo_corsa"}, fasi={"build", "peak"},
        distanze={"70.3", "full"},
        main=lambda lv: [(1, [S(_r(lv, "40m", "50m", "60m"), "Z2 HR")]),
                         (_r(lv, 2, 2, 3), [S("15m" if lv < 2 else "20m", "Z3 HR", nota="ritmo gara"), S("3m", "Z2 HR")])]),
}

# brick specifici 70.3 e full (SISU, Vincent Luis), nella preparazione build/peak: non in
# rotazione, sostituiscono le descrizioni del brick (bici a ritmo gara, corsa a ritmo gara)
CATALOGO["brick_bici_gara"] = dict(sport="Ride", tipo="BikeCross", chiavi=set(), fasi=set(),
    distanze={"70.3", "full"},
    main=lambda lv: [(_r(lv, 2, 2, 3), [S(_r(lv, "20m", "25m", "30m"), "88-92% LTHR", nota="ritmo gara"),
                                         S("10m", REC_BICI, nota="recupero attivo")])])


def brick_gara(seduta):
    return seduta.get("distanza") in ("70.3", "full") and seduta.get("fase") in ("build", "peak")


def descrizione_corsa_brick(minuti):
    """Corsa del brick 70.3/full: subito a ritmo gara (niente riscaldamento: si scende dalla
    bici), 5' di defaticamento."""
    lavoro = max(5, minuti - 5)
    return (f"[[tipo:Brick]]\n- {lavoro}m Z3 HR intensity=active (ritmo gara)\n\n"
            f"- {minuti - lavoro}m Z1 HR intensity=cooldown (post: stretching)\n") if minuti > 5 else None


# rotazione per chiave e blocco/fase: id avanzati che si alternano alle sedute standard
ROTAZIONE = {}
for _id, _d in CATALOGO.items():
    for _k in _d["chiavi"]:
        for _f in _d["fasi"]:
            ROTAZIONE.setdefault((_k, _f), []).append(_id)


def ammessa(id_, seduta, lv=None):
    d = CATALOGO.get(id_)
    if not d:
        return False
    lv = livello() if lv is None else lv
    dist = seduta.get("distanza")
    return lv >= d.get("livello_min", 0) and (not d.get("distanze") or dist in d["distanze"])


def candidati(chiave, fase, seduta, lv=None):
    return [("Avanzata", {"id": i}) for i in ROTAZIONE.get((chiave, fase), []) if ammessa(i, seduta, lv)]


# ── durata e descrizione ──────────────────────────────────────────────────────────────
MIN_PER_KM = 4.5    # stima prudente per convertire le distanze in tempo (corsa)


def _minuti(dur):
    if dur.endswith("km"):
        return float(dur[:-2]) * MIN_PER_KM
    if dur.endswith("s"):
        return float(dur[:-1]) / 60
    return float(dur[:-1])


def _main_min(blocchi, rec_blocchi):
    tot = sum(r * sum(_minuti(s["dur"]) for s in passi) for r, passi in blocchi)
    return tot + rec_blocchi * max(0, len(blocchi) - 1)


def adatta(blocchi, rec_blocchi, disponibili):
    """Toglie ripetute (dal blocco piu' lungo, mai sotto 2 se ne aveva di piu') finche' il
    main set sta nei minuti disponibili. None se non ci sta neanche cosi'."""
    b = [[r, p] for r, p in blocchi]
    minimi = [min(r, 2) for r, _ in blocchi]
    while _main_min(b, rec_blocchi) > disponibili:
        riducibili = [i for i, (r, _) in enumerate(b) if r > minimi[i]]
        if not riducibili:
            return None
        i = max(riducibili, key=lambda j: b[j][0] * sum(_minuti(s["dur"]) for s in b[j][1]))
        b[i][0] -= 1
    return [(r, p) for r, p in b]


def _riga(s):
    nota = f" ({s['nota']})" if s.get("nota") else ""
    return f"- {s['dur']} {s['target']} intensity={s['int']}{nota}"


def descrizione(id_, durata, lv=None):
    """(descrizione Intervals.icu, minuti) della seduta per questa durata, o None."""
    d = CATALOGO[id_]
    lv = livello() if lv is None else lv
    corsa = d["sport"] == "Run"
    wu_min, cd_min = (15, 10) if corsa else (15, 10)
    rec_b = d.get("rec_blocchi", 0)
    blocchi = adatta(d["main"](lv), rec_b, durata - wu_min - 5)
    if not blocchi:
        return None
    main = _main_min(blocchi, rec_b)
    resto = max(5.0, durata - wu_min - main)
    wu = wu_min + max(0.0, resto - 20)          # oltre 20' di defaticamento si allunga il riscaldamento
    cd = min(resto, 20.0)
    pre = "pre: mobilita+skip" if corsa else "pre: mobilita anca/ginocchio (no skip, no balzi)"
    # un solo tipo di target per evento (l'orologio ne mostra uno): con il passo nel main
    # set, anche riscaldamento, recuperi e defaticamento vanno a passo (zone di passo)
    passo = any("Pace" in st["target"] for _, p in blocchi for st in p)
    # zone di passo in percentuale del passo soglia, come le sedute standard della libreria
    A_PASSO = {"Z2 HR": "73-77% Pace", "Z1-Z2 HR": "73-77% Pace", "Z1 HR": "62-68% Pace"}
    unita = lambda t: A_PASSO.get(t, t) if passo else t
    righe = [f"[[tipo:{d['tipo']}]]",
             f"- {round(wu)}m {unita('Z1-Z2 HR' if not corsa else 'Z2 HR')} intensity=warmup ({pre})", ""]
    for i, (r, passi) in enumerate(blocchi):
        if r > 1:
            righe.append(f"{r}x")
        righe += [_riga(dict(st, target=unita(st["target"]))) for st in passi]
        righe.append("")
        if rec_b and i < len(blocchi) - 1:
            righe += [f"- {rec_b}m {unita(REC_BICI if not corsa else REC_CORSA)} intensity=active (recupero attivo)", ""]
    righe.append(f"- {max(1, round(cd))}m {'68-72% Pace' if passo else 'Z1 HR'} intensity=cooldown (post: stretching)")
    return "\n".join(righe) + "\n", round(wu) + main + round(cd)


def eventi(id_, seduta, lv=None):
    """Evento Intervals.icu della seduta avanzata, o None (non ci sta: seduta standard)."""
    d = CATALOGO.get(id_)
    if not d:
        return None
    r = descrizione(id_, seduta["durata"], lv)
    if not r:
        return None
    desc, _ = r
    return [{"category": "WORKOUT", "start_date_local": f"{seduta['data']}T00:00:00", "type": d["sport"],
             "name": seduta.get("nome") or id_, "description": desc, "moving_time": seduta["durata"] * 60}]
