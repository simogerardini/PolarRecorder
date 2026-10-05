# -*- coding: utf-8 -*-
"""
CERVELLO BIOSLEEP — ingresso unico per l'app Android (Chaquopy).  04/10/2026

L'app chiama  esegui_app(config_json) -> risultato_json  e non vede altro.

config (JSON):
  intervals_api_key, intervals_athlete_id   obbligatori
  cartella        memoria privata dell'app per stato, flag, riepiloghi e log
  modo            "auto" (default) | "settimanale" | "giornaliero"
  dry_run         true = calcola senza scrivere su Intervals.icu
  senza_attesa    true = procede anche senza i biometrici della notte (avvio di ripiego)
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
    serie, _ = cs.storia_biometrica(wellness60)
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
    os.environ.update({"INTERVALS_API_KEY": cfg["intervals_api_key"],
                       "INTERVALS_ATHLETE_ID": cfg["intervals_athlete_id"],
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
