# -*- coding: utf-8 -*-
"""
CERVELLO BIOSLEEP — ingresso unico per l'app Android (Chaquopy).  04/10/2026

L'app chiama  esegui_app(config_json) -> risultato_json  e non vede altro.

config (JSON):
  intervals_token                            token OAuth (preferito), oppure
  intervals_api_key, intervals_athlete_id    chiave API personale + id atleta
  cartella        memoria privata dell'app per stato, flag, riepiloghi e log
  modo            "auto" (default) | "settimanale" | "giornaliero"
  dry_run         true = calcola senza scrivere su Intervals.icu
  senza_attesa    true = procede anche senza i biometrici della notte (avvio di ripiego)
  tag             facoltativo: {"giorni": {"YYYY-MM-DD": [chiavi]}, "sedute": {"<id attivita'>": [chiavi]}}
                  vocabolario in coach_settimanale.TAG_GIORNO / TAG_SEDUTA
  profilo         facoltativo: {"fc_max", "fc_riposo", "tetto_ore",
                  "disponibilita": {"lun".."dom": minuti massimi, 0 = non disponibile}}
risultato (JSON):
  esito           "pianificata" | "fatto" | "niente" | "attesa" | "gia_fatto" | "errore"
                  ("niente": giornaliero senza sedute da rimodulare oggi; riepilogo prodotto)
  notifiche       testi da mostrare come notifiche Android (prima andavano su Telegram)
  riepilogo_file  percorso di riepilogo_<data>.json (null se non prodotto)
  log_file        percorso del log del run
  errore          messaggio, solo con esito "errore"

Nessun os.chdir: Python gira nel processo Android, cambiare cartella cambierebbe quella
dell'intera app. Stato, flag, riepiloghi e log usano percorsi assoluti dentro "cartella".
Con dry_run (pulsante di misura) il riepilogo NON viene scritto su disco.

Nessuna chiamata a GitHub o Telegram: solo Intervals.icu (Oura rimossa il 04/10/2026). I moduli vengono
ricaricati a ogni avvio perche' l'interprete resta vivo nel processo dell'app e le
variabili globali di un run non devono passare al successivo.
"""
import contextlib, importlib, io, json, os, sys, traceback

_VARIABILI_ESTERNE = ("GH_TOKEN", "GITHUB_REPOSITORY", "TELEGRAM_TOKEN", "TELEGRAM_CHAT_ID")
cs = None


# ── PREPARAZIONE DELL'ACCOUNT INTERVALS.ICU (06/10/2026 — roadmap punto 1) ──────────
# I campi wellness personalizzati in cui l'app scrive la notte. Simone li aveva creati a
# mano; per chi installa l'app li crea prepara_account(), solo quelli mancanti (si puo'
# rilanciare senza effetti). Definizione presa dal suo account (custom-item reali):
# INPUT_FIELD numerico privato; qui in piu' unita' e descrizione, utili a chi li legge.
CAMPI_BIOSLEEP = [
    {"code": "BioSleepRMSSD",      "units": "ms",  "desc": "rMSSD media delle finestre di 5' valide nel sonno"},
    {"code": "BioSleepSDNN",       "units": "ms",  "desc": "SDNN della notte"},
    {"code": "BioSleepRHR",        "units": "bpm", "desc": "FC a riposo: media dei 5' piu' bassi"},
    {"code": "BioSleepMinHR",      "units": "bpm", "desc": "FC minima (1o percentile)"},
    {"code": "BioSleepAvgHR",      "units": "bpm", "desc": "FC media notturna"},
    {"code": "BioSleepHours",      "units": "h",   "desc": "durata della registrazione"},
    {"code": "BioSleepSleepHours", "units": "h",   "desc": "sonno totale"},
    {"code": "BioSleepDeepMin",    "units": "min", "desc": "sonno profondo"},
    {"code": "BioSleepREMMin",     "units": "min", "desc": "sonno REM"},
    {"code": "BioSleepLightMin",   "units": "min", "desc": "sonno leggero"},
    {"code": "BioSleepAwakeMin",   "units": "min", "desc": "veglia durante la notte"},
    {"code": "BioSleepQuality",    "units": "%",   "desc": "copertura della registrazione con battiti validi"},
]


def _intestazioni(cfg):
    if cfg.get("intervals_token"):
        return {"Authorization": f"Bearer {cfg['intervals_token']}", "Content-Type": "application/json"}, "0"
    import base64
    chiave = base64.b64encode(f"API_KEY:{cfg['intervals_api_key']}".encode()).decode()
    return ({"Authorization": f"Basic {chiave}", "Content-Type": "application/json"},
            cfg.get("intervals_athlete_id") or "0")


def prepara_account(config_json):
    """Crea su Intervals.icu i campi wellness BioSleep mancanti. Da chiamare dopo il
    collegamento (e a ogni aggiornamento dell'app: non tocca i campi gia' presenti).
    Risultato JSON: {"esito": "ok"|"permesso_mancante"|"errore", "creati": [codici],
    "esistenti": [codici], "errore": "..."}. "permesso_mancante" = il token non ha lo
    scope SETTINGS:WRITE: va rifatto il collegamento chiedendolo."""
    import requests
    cfg = json.loads(config_json)
    out = {"esito": "errore", "creati": [], "esistenti": []}
    # Sessione propria: coach_settimanale all'import rimappa requests.get/post sulla sua
    # Session; qui non dipendiamo da quale modulo e' stato caricato prima.
    http = requests.Session()
    try:
        h, atleta = _intestazioni(cfg)
        url = f"https://intervals.icu/api/v1/athlete/{atleta}/custom-item"
        r = http.get(url, headers=h, timeout=30)
        if r.status_code in (401, 403):
            out["esito"] = "permesso_mancante"
            return json.dumps(out)
        if r.status_code != 200:
            out["errore"] = f"lettura campi: HTTP {r.status_code}"
            return json.dumps(out)
        presenti = {((x.get("content") or {}).get("code") or x.get("name"))
                    for x in r.json() or [] if x.get("type") == "INPUT_FIELD"}
        for campo in CAMPI_BIOSLEEP:
            if campo["code"] in presenti:
                out["esistenti"].append(campo["code"])
                continue
            corpo = {"type": "INPUT_FIELD", "visibility": "PRIVATE", "name": campo["code"],
                     "description": campo["desc"],
                     "content": {"code": campo["code"], "name": campo["code"], "type": "numeric",
                                 "units": campo["units"], "number_format": ".1f", "gauge": True,
                                 "color": "#333333", "text_align": "center", "text_wrap": "no",
                                 "options": [], "min": None, "max": None}}
            rp = http.post(url, headers=h, json=corpo, timeout=30)
            if rp.status_code in (401, 403):
                out["esito"] = "permesso_mancante"
                return json.dumps(out)
            if rp.status_code not in (200, 201):
                out["errore"] = f"creazione {campo['code']}: HTTP {rp.status_code} {rp.text[:120]}"
                return json.dumps(out)
            out["creati"].append(campo["code"])
        out["esito"] = "ok"
    except Exception as e:
        out["errore"] = f"{type(e).__name__}: {e}"
    return json.dumps(out)


def valida_tag(tag):
    """({"giorni", "sedute"} con le sole chiavi del vocabolario, [chiavi scartate])."""
    import coach_settimanale as voc
    tag = tag or {}
    validi, scartati = {"giorni": {}, "sedute": {}}, []
    for gruppo, ammesse in (("giorni", voc.TAG_GIORNO), ("sedute", voc.TAG_SEDUTA)):
        for k, chiavi in (tag.get(gruppo) or {}).items():
            buone = [t for t in chiavi or [] if t in ammesse]
            scartati += [t for t in chiavi or [] if t not in ammesse]
            if buone:
                validi[gruppo][str(k)] = buone
    return validi, scartati


def _carica_moduli():
    global cs
    import sedute, biometria, carico, coach_settimanale
    for m in (sedute, biometria, carico, coach_settimanale):
        importlib.reload(m)
    cs = coach_settimanale
    return sedute


def _blocchi(oggi, ore_target):
    """Blocchi del vecchio riepilogo del mattino. Rilegge da Intervals.icu gli stessi
    dati del run (4 GET), cosi' i valori sono quelli del calendario appena scritto."""
    lun = cs.lunedi_di(oggi)
    dom = (cs._dt(lun) + cs.timedelta(days=6)).strftime("%Y-%m-%d")
    wellness60 = cs.get_wellness(60)
    serie, _ = cs.storia_biometrica(wellness60, cs.TAG_GIORNI)
    baseline = cs.calc_baseline_hrv(serie, oggi)
    pos = cs.posizione_ciclo(lun, cs.get_races(), pause=cs.pause_recenti(lun))
    mod = cs.modulazione_biometrica(baseline, cs.stato_forma(wellness60), pos["fase"])
    cs._CACHE.pop(("events", lun, dom), None)
    return cs.blocchi_riepilogo(oggi, pos, baseline, mod, wellness60, cs.get_activities(42),
                                cs.get_events(lun, dom), ore_target, list(cs._NON_SCRITTE))


def _unisci_al_settimanale(percorso, rie):
    """Un run giornaliero nello stesso giorno della pianificazione (lunedi', o dopo un
    piano rifatto) NON cancella il riepilogo settimanale del giorno: aggiorna i blocchi
    e accoda le note, ma tiene tipo, sedute, fase e testo della settimana."""
    if rie.get("tipo") != "giornaliero" or not os.path.exists(percorso):
        return rie
    try:
        with open(percorso, encoding="utf-8") as f:
            vecchio = json.load(f)
    except (OSError, ValueError):
        return rie
    if vecchio.get("tipo") != "settimanale":
        return rie
    unito = dict(vecchio)
    for k, v in rie.items():
        if k not in ("tipo", "sedute", "testo", "fase", "lunedi", "ore_target", "intensita",
                     "banda", "motivi", "non_scritte"):
            unito[k] = v
    unito["non_scritte"] = list(vecchio.get("non_scritte") or []) + list(rie.get("non_scritte") or [])
    if rie.get("testo"):
        unito["testo"] = (vecchio.get("testo") or "") + "\n\n" + rie["testo"]
    return unito


def _riepilogo(piano, esito, notifiche, oggi):
    if piano:
        mod = piano.get("mod") or {}
        sedute_out = []
        for s in sorted(piano["sedute"], key=lambda x: (x["giorno"], x["slot"])):
            for e in cs.payload_eventi(s, s["data"]):
                sedute_out.append({"data": s["data"], "nome": e["name"], "tipo": e["type"],
                                   "durata_min": round((e.get("moving_time") or 0) / 60),
                                   "qualita": bool(s.get("qualita")),
                                   "declassata": bool(s.get("declassata")),
                                   "descrizione": e["description"]})
        return {"v": 1, "data": oggi, "tipo": "settimanale", "esito": esito,
                "lunedi": piano["giorni"][0], "fase": piano["pos"].get("fase"),
                "banda": mod.get("banda"), "motivi": mod.get("motivi") or [],
                "ore_target": piano.get("ore_target"), "sedute": sedute_out,
                "intensita": cs.intensita_settimana(piano["sedute"], piano["ctx"], piano["pos"]),
                "testo": cs.riepilogo(piano["pos"], piano["ore_target"], mod, piano["sedute"],
                                      piano["baseline"], piano["forma"], piano["giorni"], piano["ctx"])}
    return {"v": 1, "data": oggi, "tipo": "giornaliero", "esito": esito, "sedute": [],
            "testo": "\n\n".join(notifiche)}


def esegui_app(config_json):
    cfg = json.loads(config_json)
    cartella = cfg["cartella"]
    cartella = os.path.abspath(cartella)
    os.makedirs(os.path.join(cartella, "log"), exist_ok=True)
    for k in _VARIABILI_ESTERNE:
        os.environ.pop(k, None)
    # 06/10/2026: OAuth (intervals_token) oppure chiave API personale + id atleta
    for k in ("INTERVALS_TOKEN", "INTERVALS_API_KEY", "INTERVALS_ATHLETE_ID"):
        os.environ.pop(k, None)
    if cfg.get("intervals_token"):
        os.environ.update({"INTERVALS_TOKEN": cfg["intervals_token"], "INTERVALS_ATHLETE_ID": "0"})
    else:
        os.environ.update({"INTERVALS_API_KEY": cfg["intervals_api_key"],
                           "INTERVALS_ATHLETE_ID": cfg["intervals_athlete_id"]})
    os.environ.update({
                       "COACH_SETT_STATE": os.path.join(cartella, "stato_coach.json")})
    # 04/10/2026: profilo atleta dall'app. FC massima e a riposo (aggiornate dall'app man
    # mano che raccoglie dati) valgono piu' di quelle su Intervals.icu; disponibilita' per
    # giorno in minuti (0 = non disponibile); tetto ore cardio settimanale.
    for k in ("FCMAX", "FCREST", "FC_DA_APP", "MAX_ORE_CARDIO_SETT", "DISPONIBILITA"):
        os.environ.pop(k, None)
    prof = cfg.get("profilo") or {}
    if prof.get("fc_max") and prof.get("fc_riposo"):
        os.environ.update({"FCMAX": str(int(prof["fc_max"])), "FCREST": str(int(prof["fc_riposo"])),
                           "FC_DA_APP": "1"})
    if prof.get("tetto_ore"):
        os.environ["MAX_ORE_CARDIO_SETT"] = str(float(prof["tetto_ore"]))
    if prof.get("disponibilita"):
        os.environ["DISPONIBILITA"] = json.dumps(prof["disponibilita"])
    out = {"esito": "errore", "notifiche": [], "riepilogo_file": None, "log_file": None}
    buf = io.StringIO()
    try:
        with contextlib.redirect_stdout(buf):
            sedute = _carica_moduli()
            cs.telegram = out["notifiche"].append      # gli avvisi tornano all'app
            # 05/10/2026: tag dell'app (vocabolario fisso; chiavi sconosciute ignorate)
            tag_validi, tag_scartati = valida_tag(cfg.get("tag"))
            cs.TAG_GIORNI, cs.TAG_SEDUTE = tag_validi["giorni"], tag_validi["sedute"]
            # Il riepilogo vive nell'app (riepilogo_<data>.json): nessuna NOTE di piano sul
            # calendario di Intervals.icu (coach silenzioso, decisione di Simone).
            cs.scrivi_nota = lambda *a, **k: None
            r = cs.requests.get(f"{cs.ICU_BASE}/athlete/{cs.ATHLETE_ID}", headers=cs.ICU)
            atleta = r.json() if r.status_code == 200 else {}
            sedute.configura(atleta, cs.get_activities(42))
            oggi = cs.now_local().strftime("%Y-%m-%d")
            esito, piano = cs.esegui_auto(cfg.get("modo", "auto"), bool(cfg.get("dry_run")),
                                          False, None, bool(cfg.get("senza_attesa")))
            out["esito"] = esito
            if esito in ("pianificata", "fatto", "niente") and not cfg.get("dry_run"):
                rie = _riepilogo(piano, esito, out["notifiche"], oggi)
                stato = cs.carica_stato()
                if piano:      # il target ore della settimana serve anche ai run giornalieri
                    stato["ore_target"] = {"lunedi": piano["giorni"][0], "ore": piano["ore_target"]}
                    cs.salva_stato(stato)
                ot = stato.get("ore_target") or {}
                # BUG FIX (04/10/2026): un errore nelle letture dei blocchi (4 GET dopo la
                # scrittura del piano) faceva perdere il riepilogo di un piano gia' a
                # calendario. Ora il riepilogo esce senza blocchi, l'eccezione va nel log.
                try:
                    rie.update(_blocchi(oggi, ot.get("ore") if ot.get("lunedi") == cs.lunedi_di(oggi)
                                        else None))
                except Exception:
                    print("  ⚠️ Blocchi del riepilogo non calcolati: riepilogo scritto senza blocchi")
                    print(traceback.format_exc())
                percorso = os.path.join(cartella, f"riepilogo_{oggi}.json")
                rie = _unisci_al_settimanale(percorso, rie)
                # 05/10/2026: decisioni prese per un tag, visibili nell'app
                if cs._MOTIVI_TAG or tag_scartati:
                    rie["motivi"] = list(dict.fromkeys((rie.get("motivi") or []) + cs._MOTIVI_TAG))
                    righe = [f"tag: {m}" for m in cs._MOTIVI_TAG]
                    if tag_scartati:
                        righe.append("tag sconosciuti ignorati: " + ", ".join(sorted(set(tag_scartati))))
                    rie["avvisi"] = "\n".join(x for x in [rie.get("avvisi") or ""] + righe if x)
                with open(percorso + ".tmp", "w", encoding="utf-8") as f:
                    f.write(json.dumps(rie, ensure_ascii=False))
                os.replace(percorso + ".tmp", percorso)
                out["riepilogo_file"] = percorso
    except Exception as e:
        out["errore"] = f"{type(e).__name__}: {e}"
        buf.write(traceback.format_exc())
    finally:
        try:
            oggi_log = __import__("datetime").datetime.now().strftime("%Y-%m-%d_%H%M%S_%f")
            log = os.path.join(cartella, "log", f"coach_{oggi_log}.txt")
            with open(log, "w", encoding="utf-8") as f:
                f.write(buf.getvalue())
            out["log_file"] = log
            vecchi = sorted(n for n in os.listdir(os.path.join(cartella, "log")) if n.startswith("coach_"))
            for n in vecchi[:-30]:
                os.remove(os.path.join(cartella, "log", n))
        except OSError:
            pass
    return json.dumps(out, ensure_ascii=False)


if __name__ == "__main__":
    print(esegui_app(sys.argv[1] if len(sys.argv) > 1 else sys.stdin.read()))
