# -*- coding: utf-8 -*-
"""
CHECK-IN DOPO LA SEDUTA (10/10/2026, contratto della Parte 3).
configJson "checkin": {"<external_id sw:...>": {"stato": "fatta"|"modificata"|"saltata",
  "rpe": 1-10 (non per saltata), "minuti": n (solo modificata), "data": "YYYY-MM-DD",
  "tag": [chiavi dei tag di seduta]}} per gli ultimi 28 giorni.
Usi nel cervello:
1. i tag del check-in valgono come i tag di seduta (stesso vocabolario, stessi effetti);
2. fatica fuori scala: RPE >= 8 su una seduta facile o >= 9 su qualunque seduta vale come
   "fatica_alta" (la qualita' dei giorni seguenti diventa aerobica, come col tag);
3. modalita' degradata: il carico ricostruito di un giorno si divide tra le sedute fatte o
   modificate in proporzione a RPE x minuti (session-RPE di Foster), con tipo e durata;
   le saltate non contano;
4. riepilogo: fatte, modificate, saltate e RPE medio degli ultimi 7 giorni.
"""
import re
from datetime import date, timedelta

STATI = ("fatta", "modificata", "saltata")
# sedute facili: qui una RPE >= 8 e' gia' fuori scala (sulle altre serve >= 9)
FACILI = {"corsa_supporto", "lungo_corsa", "nuoto_supporto", "nuoto_rigenerante", "bici_supporto",
          "lungo_bici", "brick_corsa", "gara_bici_facile", "gara_attivazione", "mobilita"}
TIPO = (("nuoto", "Swim"), ("gara_nuoto", "Swim"), ("bici", "Ride"), ("brick_bici", "Ride"),
        ("lungo_bici", "Ride"), ("corsa", "Run"), ("lungo_corsa", "Run"), ("brick_corsa", "Run"),
        ("gara_richiami", "Run"), ("gara_attivazione", "Run"), ("forza", "WeightTraining"),
        ("mobilita", "WeightTraining"))


def _tag_ammessi():
    import coach_settimanale as voc
    return voc.TAG_SEDUTA


def tipo_di(chiave):
    for parola, tipo in TIPO:
        if chiave == parola or chiave.startswith(parola + "_") or chiave.endswith("_" + parola) or parola in chiave:
            return tipo
    return None


def leggi(grezzo):
    """Voci valide del check-in, con "chiave" (della seduta del coach) e tag filtrati."""
    out = {}
    ammessi = _tag_ammessi()
    for eid, v in (grezzo or {}).items():
        m = re.fullmatch(r"sw:([a-z_]+):(\d{4}-\d{2}-\d{2})", str(eid))
        if not m or not isinstance(v, dict) or v.get("stato") not in STATI:
            continue
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", str(v.get("data") or "")):
            continue
        rpe = v.get("rpe")
        if v["stato"] != "saltata" and not (isinstance(rpe, (int, float)) and 1 <= rpe <= 10):
            continue
        minuti = v.get("minuti")
        voce = {"stato": v["stato"], "data": v["data"], "chiave": m.group(1),
                "rpe": int(rpe) if v["stato"] != "saltata" else None,
                "minuti": int(minuti) if v["stato"] == "modificata" and isinstance(minuti, (int, float))
                and minuti > 0 else None,
                "tag": [t for t in (v.get("tag") or []) if t in ammessi]}
        out[str(eid)] = voce
    return out


def tag_sedute(checkin):
    """({"checkin:<eid>": [tag]}, pseudo-attivita' per datarli) per coach_settimanale."""
    tag, pseudo = {}, []
    for eid, v in checkin.items():
        lista = list(v["tag"])
        r = v.get("rpe")
        if r is not None and (r >= 9 or (r >= 8 and v["chiave"] in FACILI)) and "fatica_alta" not in lista:
            lista.append("fatica_alta")
        if lista:
            tag[f"checkin:{eid}"] = lista
            pseudo.append({"id": f"checkin:{eid}", "start_date_local": f"{v['data']}T00:00:00"})
    return tag, pseudo


def ripartisci(attivita, checkin, previste):
    """Divide ogni pseudo-attivita' ricostruita tra le sedute fatte/modificate di quel giorno
    (peso RPE x minuti). previste = {eid: moving_time pianificato in secondi}. Le attivita'
    vere restano come sono; i giorni senza check-in restano ricostruiti senza tipo."""
    per_giorno = {}
    for eid, v in checkin.items():
        if v["stato"] == "saltata":
            continue
        minuti = v["minuti"] or round((previste.get(eid) or 0) / 60)
        if minuti > 0:
            per_giorno.setdefault(v["data"], []).append((eid, v, minuti))
    out = []
    for a in attivita:
        d = (a.get("start_date_local") or "")[:10]
        sedute = per_giorno.get(d) if a.get("ricostruita") else None
        if not sedute:
            out.append(a)
            continue
        pesi = [v["rpe"] * m for _, v, m in sedute]
        tot = sum(pesi) or 1
        for (eid, v, m), p in zip(sedute, pesi):
            out.append({"id": f"ricostruita:{eid}", "start_date_local": a["start_date_local"],
                        "icu_training_load": round(a["icu_training_load"] * p / tot, 1),
                        "moving_time": m * 60, "type": tipo_di(v["chiave"]), "ricostruita": True,
                        "session_rpe": v["rpe"]})
    return out


def riepilogo(checkin, oggi_str, giorni=7):
    dal = (date.fromisoformat(oggi_str) - timedelta(days=giorni)).isoformat()
    voci = [v for v in checkin.values() if dal <= v["data"] < oggi_str]
    rpe = [v["rpe"] for v in voci if v["rpe"] is not None]
    return {"fatte": sum(v["stato"] == "fatta" for v in voci),
            "modificate": sum(v["stato"] == "modificata" for v in voci),
            "saltate": sum(v["stato"] == "saltata" for v in voci),
            "rpe_medio": round(sum(rpe) / len(rpe), 1) if rpe else None}
