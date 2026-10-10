# -*- coding: utf-8 -*-
"""
NOMI DELLE SEDUTE sul calendario (10/10/2026, regola di Simone):
    [DISCIPLINA] · [STIMOLO FISIOLOGICO] · [FORMATO DEL MAIN SET]
- DISCIPLINA: SWIM, BIKE, RUN, BRICK, STR;
- STIMOLO: l'adattamento principale (Recovery, Endurance Z2, Aerobico, Tempo, Sweet Spot,
  Soglia, VO2max, Velocita', Tecnica...);
- FORMATO: la struttura del main set (4x10min, 6x20s, 8x400m, Over-Under, Progressivo, Drills...);
  niente apostrofi ne' virgolette nel nome;
- MAI la durata complessiva nel titolo: minuti e chilometri restano nella descrizione.
Il nome si ricava dalla seduta e dalla descrizione gia' scritta (blocchi "Nx", durate, target):
cosi' vale per ogni seduta della libreria senza elencarle a mano.
"""
import re

SEP = " · "
DISCIPLINE = ("SWIM", "BIKE", "RUN", "BRICK", "STR")

# stimolo dal tipo della libreria per la corsa (il tipo dice gia' l'adattamento)
STIMOLO_CORSA = {"Easy": "Aerobico", "Recovery": "Recovery", "Long": "Endurance Z2", "Threshold": "Soglia",
                 "Interval": "VO2max", "Repetition": "Velocità", "Fartlek": "Soglia", "Alternations": "Soglia",
                 "HillWork": "Forza in salita", "HillSprints": "Neuromuscolare", "Strides": "Neuromuscolare",
                 "RaceSimulation": "Ritmo gara", "MPRun": "Ritmo gara", "Progressive": "Aerobico",
                 "ShakeOut": "Attivazione", "TestRace": "Test"}
# parola del formato per i blocchi ripetuti della corsa
FORMATO_CORSA = {"ShakeOut": "Allunghi", "Interval": "Intervalli", "Repetition": "Ripetute", "HillWork": "Salite", "HillSprints": "Sprint in salita",
                 "Fartlek": "Fartlek", "Strides": "Allunghi", "Alternations": "Over-Under", "Threshold": "Ripetute",
                 "RaceSimulation": "Ripetute"}
# il profilo scelto dal coach, quando c'e', dice lo stimolo meglio dei target
PROFILO_BICI = {"soglia": "Soglia", "vo2max": "VO2max", "sweetspot": "Sweet Spot", "tempo": "Tempo",
                "recovery": "Recovery", "endurance": "Endurance Z2"}
PROFILO_NUOTO = {"soglia": "Soglia CSS", "velocita": "Velocità", "endurance": "Endurance Z2", "tecnica": "Tecnica"}


def _minuscola(t):
    return t[:1].lower() + t[1:]


_DRILL = re.compile(r"\b(drill|Catch up|Pugni chiusi|Finger trail|Braccio singolo|Battuta di gambe|Sei battute)\b", re.I)


def _passi(desc):
    """[(ripetizioni, [step])] dei blocchi; step = {"min", "metri", "int", "zona", "pct"}."""
    blocchi, cur = [], None
    for riga in (desc or "").splitlines():
        r = riga.strip()
        m = re.fullmatch(r"(\d+)x", r)
        if m:
            cur = [int(m.group(1)), []]
            blocchi.append(cur)
            continue
        if not r.startswith("- "):
            if not r and cur and cur[1]:
                cur = None
            continue
        if cur is None:
            cur = [1, []]
            blocchi.append(cur)
        r2 = re.sub(r"\([^()]*(?:\([^()]*\)[^()]*)*\)", "", r)
        st = {"int": (re.search(r"intensity=(\w+)", r2) or [None, None])[1], "min": None, "sec": None,
              "metri": None, "zona": None, "pct": None}
        d = re.search(r"\s(\d+(?:\.\d+)?)(m|s|mtr|km)\b", r2)
        if d:
            v, u = float(d.group(1)), d.group(2)
            if u == "m":
                st["min"] = v
            elif u == "s":
                st["sec"] = v
            elif u == "mtr":
                st["metri"] = v
            else:
                st["metri"] = v * 1000
        z = re.search(r"\bZ(\d)(?:-Z(\d))?\s*(?:HR|Pace|Power)?", r2)
        if z:
            st["zona"] = int(z.group(2) or z.group(1))
        p = re.search(r"(\d+)-(\d+)%\s*(?:LTHR|Pace|FTP|Power)?", r2)
        if p:
            st["pct"] = (int(p.group(1)) + int(p.group(2))) / 2
            st["lo"], st["hi"] = int(p.group(1)), int(p.group(2))
        cur[1].append(st)
    return blocchi


def _duro(st):
    return st["int"] == "active" and ((st["pct"] or 0) >= 85 or (st["zona"] or 0) >= 3)


def _intensita(st):
    """Stimolo da un passo duro, per bici e nuoto."""
    pct, z = st["pct"], st["zona"]
    if pct is not None:
        lo, hi = st.get("lo", pct), st.get("hi", pct)
        breve = (st["min"] is not None and st["min"] <= 5) or st["sec"] is not None
        if lo >= 105 or hi >= 110 or (breve and lo >= 100):
            return "VO2max"
        return "Soglia" if lo >= 94 else "Sweet Spot"
    return {3: "Tempo", 4: "Soglia", 5: "VO2max"}.get(z, "Endurance Z2")


def _durata(st):
    if st["metri"]:
        m = st["metri"]
        return f"{m / 1000:g} km" if m >= 1000 else f"{m:g}m"
    # 10/10/2026: niente apostrofi ne' virgolette nei nomi (rompevano i JSON in passato,
    # decisione di Simone): minuti come "min", secondi come "s" (4x10min, 6x20s).
    if st["sec"]:
        return f"{st['sec']:g}s" if st["sec"] < 60 else f"{st['sec'] / 60:g}min"
    if st["min"]:
        return f"{st['min']:g}min"
    return ""


def _main_set(blocchi, brevi_ammessi=True):
    """(ripetizioni, passo duro principale, tutti i passi duri del blocco) o None.
    brevi_ammessi=False: gli allunghi (<= 30") non sono un main set (corsa facile o lunga)."""
    cand = []
    for rip, passi in blocchi:
        duri = [s for s in passi if _duro(s) and (brevi_ammessi or not (s["sec"] and s["sec"] <= 30))]
        if rip > 1 and duri:
            lavoro = rip * sum((s["min"] or 0) * 60 + (s["sec"] or 0) + (s["metri"] or 0) / 4 for s in duri)
            cand.append((lavoro, rip, duri))
    if not cand:
        return None
    _, rip, duri = max(cand, key=lambda x: x[0])
    return rip, duri[0], duri


def _continuo(blocchi):
    """Formato per una seduta senza blocchi duri ripetuti."""
    attivi = [s for _, p in blocchi for s in p if s["int"] == "active"]
    if not attivi:
        return "Continuo"
    brevi = [s for r, p in blocchi for s in p if s["int"] == "active" and (s["sec"] or 999) <= 30]
    attivi = [s for s in attivi if s not in brevi] or attivi        # gli allunghi non fanno "progressivo"
    zone = [s["zona"] for s in attivi if s["zona"]]
    if len(zone) >= 2 and zone == sorted(zone) and zone[-1] > zone[0]:
        return "Progressivo"
    lungo = max(attivi, key=lambda s: (s["min"] or 0) * 60 + (s["sec"] or 0) + (s["metri"] or 0) / 4)
    base = f"Ritmo costante Z{lungo['zona']}" if lungo.get("zona") else "Ritmo costante"
    return base + (" + Allunghi" if brevi else "")


def titolo(ev, seduta=None):
    """Nome dell'evento secondo la regola. Le schede di forza e i test hanno gia' il loro."""
    nome = ev.get("name") or ""
    if nome.split(SEP)[0] in DISCIPLINE and nome.count(SEP) == 2:
        return nome
    s = seduta or {}
    tipo = ev.get("type")
    desc = ev.get("description") or ""
    t_lib = (re.search(r"\[\[tipo:(\w+)\]\]", desc) or [None, None])[1]
    key = s.get("key") or ""
    blocchi = _passi(desc)
    facile = t_lib in ("Easy", "Long", "Recovery", "Progressive", "ShakeOut") or key in ("corsa_supporto", "lungo_corsa")
    main = _main_set(blocchi, brevi_ammessi=not facile)
    profilo = ((s.get("scelta") or (None, {}))[1] or {}).get("profilo")
    if key.startswith("brick") or t_lib == "Brick" or tipo == "Transition":
        if tipo == "Transition":
            return SEP.join(["BRICK", "Transizione", "T2"])
        if tipo == "Run":
            return SEP.join(["BRICK", "Off-bike", "Corsa " + _minuscola(_continuo(blocchi))])
        stim = _intensita(main[1]) if main else "Endurance Z2"
        return SEP.join(["BRICK", stim, "Bici " + (f"{main[0]}x{_durata(main[1])}" if main else _minuscola(_continuo(blocchi)))])
    if tipo == "Run":
        stim = STIMOLO_CORSA.get(t_lib, "Aerobico")
        if main and t_lib in FORMATO_CORSA:
            formato = f"{FORMATO_CORSA[t_lib]} {main[0]}x{_durata(main[1])}"
        elif main:
            formato = f"{main[0]}x{_durata(main[1])}"
        else:
            formato = _continuo(blocchi)
        return SEP.join(["RUN", stim, formato])
    if tipo == "Swim":
        if _DRILL.search(desc) and not main:
            formato = "Drills" + (" & Pull Buoy" if re.search(r"\bPull\b", desc) else "")
            return SEP.join(["SWIM", "Recovery" if key == "nuoto_rigenerante" else "Tecnica", formato])
        if main:
            corta = main[1]["metri"] is not None and main[1]["metri"] <= 50
            stim = (PROFILO_NUOTO.get(profilo) or ("Velocità" if corta else None) or
                    {"Sweet Spot": "Tempo", "Soglia": "Soglia CSS", "VO2max": "Velocità"}.get(_intensita(main[1]), "Endurance"))
            return SEP.join(["SWIM", stim, f"{main[0]}x{_durata(main[1])}"])
        stim = "Recovery" if key == "nuoto_rigenerante" else "Endurance Z2"
        return SEP.join(["SWIM", stim, "Nuoto continuo"])
    if tipo in ("Ride", "VirtualRide"):
        if main:
            duri = {round(x["pct"] or 0) for x in main[2]}
            over = len(duri) >= 2 and min(duri) < 100 < max(duri)
            stim = PROFILO_BICI.get(profilo) or _intensita(main[1])
            return SEP.join(["BIKE", stim, f"{main[0]}x{_durata(main[1])}" + (" Over-Under" if over else "")])
        cont = _continuo(blocchi)
        stim = "Recovery" if "Z1" in cont else "Endurance Z2"
        return SEP.join(["BIKE", stim, cont])
    return nome
