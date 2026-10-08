# -*- coding: utf-8 -*-
"""
CERVELLO NOCTALIX — ingresso unico per l'app Android (Chaquopy).  04/10/2026

L'app chiama  esegui_app(config_json) -> risultato_json  e non vede altro.

config (JSON):
  intervals_token                            token OAuth (preferito), oppure
  intervals_api_key, intervals_athlete_id    chiave API personale + id atleta
  cartella        memoria privata dell'app per stato, flag, riepiloghi e log
  modo            "auto" (default) | "settimanale" | "giornaliero"
  dry_run         true = calcola senza scrivere su Intervals.icu
  senza_attesa    true = procede anche senza i biometrici della notte (avvio di ripiego)
  forza           true = rifa' il lavoro anche se gia' fatto oggi/questa settimana
                  ("Ripianifica questa settimana"); le sedute passate non si toccano
  tag             facoltativo: {"giorni": {"YYYY-MM-DD": [chiavi]}, "sedute": {"<id attivita'>": [chiavi]}}
                  vocabolario in coach_settimanale.TAG_GIORNO / TAG_SEDUTA
  posizione       facoltativo: {"lat", "lon"} dal telefono (meteo per i giorni caldi;
                  arrotondata a 2 decimali prima di chiedere le previsioni)
  profilo         facoltativo: {"fc_max", "fc_riposo", "tetto_ore",
                  "caldo": {"converti_corsa": true, "ora_feriale": 18, "ora_weekend": 10},
                  "detp": {"core2": true, "detp": true},
                  "stryd": true|false (qualita' di corsa in potenza se la CP e' configurata),
                  "palestra": {"attrezzatura": [barbell_gym, kettlebell, trx_suspension,
                               resistance_bands, bodyweight_only, hotel_minimal],
                               "livello": "beginner"|"intermediate"|"advanced"},
                  "disponibilita": {"lun".."dom": minuti massimi, 0 = non disponibile},
                  "settimana": {"lungo_bici": "sab", "lungo_corsa": "dom", "riposo": null,
                                "sedute": {"nuoto": 2, "bici": 2, "corsa": 3, "forza": 2}}}
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
from marchio import NOME_APP
import messaggi   # 07/10/2026 (punto 13a): codici dei messaggi per l'app

VERSIONE = "2026.10.07-13a"   # anche nel LEGGIMI del pacchetto
import contextlib, importlib, io, json, os, re, sys, traceback

_VARIABILI_ESTERNE = ("GH_TOKEN", "GITHUB_REPOSITORY", "TELEGRAM_TOKEN", "TELEGRAM_CHAT_ID")
cs = None


# ── PREPARAZIONE DELL'ACCOUNT INTERVALS.ICU (06/10/2026 — roadmap punto 1) ──────────
# I campi wellness personalizzati in cui l'app scrive la notte. Simone li aveva creati a
# mano; per chi installa l'app li crea prepara_account(), solo quelli mancanti (si puo'
# rilanciare senza effetti). Definizione presa dal suo account (custom-item reali):
# INPUT_FIELD numerico privato; qui in piu' unita' e descrizione, utili a chi li legge.
CAMPI_WELLNESS = [
    {"code": "BioSleepRMSSD",      "nome": "rMSSD",      "units": "ms",  "desc": "rMSSD media delle finestre di 5' valide nel sonno"},
    {"code": "BioSleepSDNN",       "nome": "SDNN",       "units": "ms",  "desc": "SDNN della notte"},
    {"code": "BioSleepRHR",        "nome": "RHR",        "units": "bpm", "desc": "FC a riposo: media dei 5' piu' bassi"},
    {"code": "BioSleepMinHR",      "nome": "Min HR",      "units": "bpm", "desc": "FC minima (1o percentile)"},
    {"code": "BioSleepAvgHR",      "nome": "Avg HR",      "units": "bpm", "desc": "FC media notturna"},
    {"code": "BioSleepHours",      "nome": "Recording h",      "units": "h",   "desc": "durata della registrazione"},
    {"code": "BioSleepSleepHours", "nome": "Sleep h", "units": "h",   "desc": "sonno totale"},
    {"code": "BioSleepDeepMin",    "nome": "Deep min",    "units": "min", "desc": "sonno profondo"},
    {"code": "BioSleepREMMin",     "nome": "REM min",     "units": "min", "desc": "sonno REM"},
    {"code": "BioSleepLightMin",   "nome": "Light min",   "units": "min", "desc": "sonno leggero"},
    {"code": "BioSleepAwakeMin",   "nome": "Awake min",   "units": "min", "desc": "veglia durante la notte"},
    {"code": "BioSleepQuality",    "nome": "Quality",    "units": "%",   "desc": "copertura della registrazione con battiti validi"},
]


def _intestazioni(cfg):
    if cfg.get("intervals_token"):
        return {"Authorization": f"Bearer {cfg['intervals_token']}", "Content-Type": "application/json"}, "0"
    import base64
    chiave = base64.b64encode(f"API_KEY:{cfg['intervals_api_key']}".encode()).decode()
    return ({"Authorization": f"Basic {chiave}", "Content-Type": "application/json"},
            cfg.get("intervals_athlete_id") or "0")


def prepara_account(config_json):
    """Crea su Intervals.icu i campi wellness dell'app mancanti. Da chiamare dopo il
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
        for campo in CAMPI_WELLNESS:
            if campo["code"] in presenti:
                out["esistenti"].append(campo["code"])
                continue
            # 08/10/2026 (rinomina NoctaliX): nome visibile "NoctaliX <etichetta>" dalla costante
            # unica del marchio; il codice resta l'identificatore tecnico del dato.
            visibile = f"{NOME_APP} {campo['nome']}"
            corpo = {"type": "INPUT_FIELD", "visibility": "PRIVATE", "name": visibile,
                     "description": f"{NOME_APP}: {campo['desc']}",
                     "content": {"code": campo["code"], "name": visibile, "type": "numeric",
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


# ── CONTROLLO DELLE SOGLIE (06/10/2026 — roadmap punto 3) ───────────────────────────
# Le sedute scritte dal coach si appoggiano alle soglie configurate su Intervals.icu:
# "Zn HR" e "% LTHR" -> LTHR della disciplina; "% Pace" -> passo soglia (corsa) e CSS
# (nuoto, threshold_pace di Swim). Se mancano, l'orologio riceve step senza target.
# "bloccante" = target mancanti sull'orologio; "consigliato" = funziona, ma peggio.
_SOGLIE = (
    # disciplina, tipi Intervals, campo app, campo Intervals, gravita', effetto
    ("corsa", ("Run",), "lthr", "lthr", "bloccante",
     "le sedute di corsa in FC (Zn HR, % LTHR) arrivano sull'orologio senza target"),
    ("corsa", ("Run",), "passo_soglia", "threshold_pace", "bloccante",
     "le ripetute brevi di corsa in % Pace arrivano sull'orologio senza target"),
    ("bici", ("Ride", "VirtualRide"), "lthr", "lthr", "bloccante",
     "le sedute di bici in FC (Zn HR, % LTHR) arrivano sull'orologio senza target"),
    ("bici", ("Ride", "VirtualRide"), "ftp", "ftp", "consigliato",
     "senza FTP Intervals.icu non stima bene il carico (TSS) delle uscite senza FC"),
    ("nuoto", ("Swim",), "css", "threshold_pace", "bloccante",
     "le sedute di nuoto in % Pace arrivano sull'orologio senza target"),
    ("nuoto", ("Swim",), "lthr", "lthr", "consigliato",
     "senza LTHR del nuoto il carico delle nuotate e' stimato peggio"),
)


def _passo(m_s, per_metri, unita):
    """m/s -> "m:ss/unita'" (es. 3.70 m/s -> 4:30/km)."""
    if not isinstance(m_s, (int, float)) or m_s <= 0:
        return None
    sec = round(per_metri / m_s)
    return f"{sec // 60}:{sec % 60:02d}/{unita}"


def potenza_corsa_attiva(profilo, atleta):
    """07/10/2026 (punto 12): qualita' di corsa in potenza solo con Stryd nel profilo E la CP
    (FTP dello sport Run) configurata su Intervals.icu. Altrimenti FC/passo come prima."""
    if not (profilo or {}).get("stryd"):
        return False
    for s in (atleta or {}).get("sportSettings", []):
        if "Run" in (s.get("types") or []):
            return isinstance(s.get("ftp"), (int, float)) and s["ftp"] > 0
    return False


def verifica_soglie(atleta, stryd=False):
    """([mancanti], {valori}) dal JSON di GET /athlete. Pura: usata da controlla_soglie e
    dagli avvisi di ogni riepilogo. stryd=True: la CP di corsa diventa necessaria."""
    out = {"mancanti": [], "valori": {}}
    sport = (atleta or {}).get("sportSettings") or []

    def impostazioni(tipi):
        return next((x for x in sport if set(tipi) & set(x.get("types") or [])), None)

    sport_mancante = set()
    for disc, tipi, campo, campo_icu, gravita, effetto in _SOGLIE:
        st = impostazioni(tipi)
        if st is None:
            if disc not in sport_mancante:
                sport_mancante.add(disc)
                out["mancanti"].append({
                    "disciplina": disc, "campo": "sport", "gravita": "bloccante",
                    "effetto": f"nessuna impostazione per {disc} su Intervals.icu: "
                               f"le sedute di {disc} arrivano sull'orologio senza target"})
            continue
        v = st.get(campo_icu)
        if not isinstance(v, (int, float)) or v <= 0:
            out["mancanti"].append({"disciplina": disc, "campo": campo,
                                    "gravita": gravita, "effetto": effetto})
            continue
        if campo == "passo_soglia":
            v = _passo(v, 1000, "km")
        elif campo == "css":
            v = _passo(v, 100, "100m")
        out["valori"].setdefault(disc, {})[campo] = v
    if stryd:     # 07/10/2026 (punto 12): con Stryd la CP (FTP Run) serve ai target in potenza
        run = next((x for x in (atleta or {}).get("sportSettings") or [] if "Run" in (x.get("types") or [])), None)
        cp = (run or {}).get("ftp")
        if isinstance(cp, (int, float)) and cp > 0:
            out["valori"].setdefault("corsa", {})["cp"] = cp
        else:
            out["mancanti"].append({"disciplina": "corsa", "campo": "cp", "gravita": "bloccante",
                                    "effetto": "senza CP (FTP corsa) le sedute restano in FC/passo: "
                                               "Stryd non viene usato"})
    return out["mancanti"], out["valori"]


def controlla_soglie(config_json):
    """Soglie di corsa, bici e nuoto su Intervals.icu e cosa succede se mancano.
    Risultato JSON: {"esito": "ok"|"da_completare"|"permesso_mancante"|"errore",
    "mancanti": [{"disciplina", "campo", "gravita", "effetto"}], "valori": {...},
    "link": pagina delle impostazioni sport di Intervals.icu}."""
    import requests
    cfg = json.loads(config_json)
    out = {"esito": "errore", "mancanti": [], "valori": {},
           "link": "https://intervals.icu/settings"}
    try:
        h, atleta = _intestazioni(cfg)
        r = requests.Session().get(f"https://intervals.icu/api/v1/athlete/{atleta}",
                                   headers=h, timeout=30)
        if r.status_code in (401, 403):
            out["esito"] = "permesso_mancante"
            return json.dumps(out)
        if r.status_code != 200:
            out["errore"] = f"lettura atleta: HTTP {r.status_code}"
            return json.dumps(out)
        mancanti, valori = verifica_soglie(r.json() or {}, (cfg.get("profilo") or {}).get("stryd"))
        out["mancanti"], out["valori"] = mancanti, valori
        bloccanti = any(m["gravita"] == "bloccante" for m in out["mancanti"])
        out["esito"] = "da_completare" if bloccanti else "ok"
    except Exception as e:
        out["errore"] = f"{type(e).__name__}: {e}"
    return json.dumps(out, ensure_ascii=False)


def registra_css(config_json):
    """Tempi del test CSS inseriti nell'app: {"t400": secondi, "t200": secondi}.
    Calcola il CSS, lo scrive come passo soglia del nuoto su Intervals.icu e chiude il
    test nello stato. Risultato: {"esito": "ok"|"valori_non_validi"|"permesso_mancante"|
    "errore", "css": "m:ss/100m"}."""
    import soglie as sg
    cfg = json.loads(config_json)
    out = {"esito": "errore"}
    css = sg.css_da_tempi(cfg.get("t400"), cfg.get("t200"))
    if css is None:
        out["esito"] = "valori_non_validi"
        out["errore"] = "tempi incoerenti: il 400 deve durare circa il doppio del 200"
        return json.dumps(out)
    try:
        h, atleta = _intestazioni(cfg)
        sg.configura(h, atleta)
        r = sg._http().put(f"https://intervals.icu/api/v1/athlete/{atleta}/sport-settings/Swim",
                           headers=h, json={"threshold_pace": round(css, 4)}, timeout=30)
        if r.status_code in (401, 403):
            out["esito"] = "permesso_mancante"
            return json.dumps(out)
        if r.status_code not in (200, 204):
            out["errore"] = f"scrittura CSS: HTTP {r.status_code}"
            return json.dumps(out)
        out.update(esito="ok", css=sg._passo_txt(css, 100, "100m"))
        if cfg.get("cartella"):        # chiude il test nello stato del coach
            import coach_settimanale as cs_
            cs_.STATE_FILE = os.path.join(os.path.abspath(cfg["cartella"]), "stato_coach.json")
            st = cs_.carica_stato()
            t = st.get("ultimo_test") or {}
            if t.get("chiave") == "swim_css":
                t.update(elaborato=True, attesa_tempi=False, css=round(css, 4))
                cs_.salva_stato(st)
    except Exception as e:
        out["errore"] = f"{type(e).__name__}: {e}"
    return json.dumps(out)


# ── GARE CREATE DALL'APP (06/10/2026 — roadmap punto 5) ─────────────────────────────
# Una gara e' un evento RACE_A/B/C su Intervals.icu: la periodizzazione del coach la legge
# gia' (get_races / gara_obiettivo / classifica_distanza). La distanza va nel nome e nella
# descrizione con le parole che classifica_distanza riconosce. Lo sprint si prepara come
# l'olimpico (stesse tabelle).
DISTANZE_GARA = {
    "sprint":   ("Triathlon sprint", "distanza: sprint (0,75 / 20 / 5 km) — preparazione olimpica"),
    "olimpico": ("Triathlon olimpico", "distanza: olimpico (1,5 / 40 / 10 km)"),
    "70.3":     ("Triathlon 70.3", "distanza: 70.3 half (1,9 / 90 / 21,1 km)"),
    "full":     ("Triathlon full distance", "distanza: full distance (3,8 / 180 / 42,2 km)"),
}
GARA_MESI_MAX = 18
_RE_SUFFISSO_DISTANZA = re.compile(
    r"(?:\s+—\s+(?:" + "|".join(re.escape(t) for t, _ in DISTANZE_GARA.values()) + r"))+\s*$")
_RE_DISTANZA_SCELTA = re.compile(r"^distanza:\s*(sprint|olimpico|70\.3|full)\b", re.M | re.I)


def _http_icu(cfg):
    import requests
    h, atleta = _intestazioni(cfg)
    return requests.Session(), h, f"https://intervals.icu/api/v1/athlete/{atleta}"


def salva_gara(config_json):
    """Crea (o modifica, con "id") una gara: {"nome", "data": "YYYY-MM-DD",
    "priorita": "A"|"B"|"C", "distanza": "sprint"|"olimpico"|"70.3"|"full"}.
    Risultato: {"esito": "ok"|"valori_non_validi"|"permesso_mancante"|"errore", "id",
    "ripianifica": true -> l'app lancia la ripianificazione forzata della settimana}."""
    from datetime import datetime, timedelta
    cfg = json.loads(config_json)
    out = {"esito": "errore"}
    nome = (cfg.get("nome") or "").strip()[:60]
    try:
        data = datetime.strptime(cfg.get("data") or "", "%Y-%m-%d")
    except ValueError:
        data = None
    oggi = _adesso(cfg).replace(hour=0, minute=0, second=0, microsecond=0)
    errori = []
    if not nome:
        errori.append("nome mancante")
    if not data or data < oggi or data > oggi + timedelta(days=GARA_MESI_MAX * 31):
        errori.append(f"data non valida: da oggi a {GARA_MESI_MAX} mesi")
    if cfg.get("priorita") not in ("A", "B", "C"):
        errori.append("priorita' A, B o C")
    if cfg.get("distanza") not in DISTANZE_GARA:
        errori.append("distanza: sprint, olimpico, 70.3 o full")
    if errori:
        out.update(esito="valori_non_validi", errore="; ".join(errori))
        return json.dumps(out)
    titolo, descr = DISTANZE_GARA[cfg["distanza"]]
    giorno = data.strftime("%Y-%m-%d")
    # BUG FIX (06/10/2026): in modifica il nome arriva con il suffisso della distanza
    # precedente; senza toglierlo si accumulava ("Lago — Triathlon sprint — Triathlon
    # olimpico"). Si tolgono tutti i suffissi " — <titolo di distanza>" in coda.
    nome = _RE_SUFFISSO_DISTANZA.sub("", nome).strip() or nome
    if cfg.get("calda"):      # 07/10/2026 (punto 11): gara calda -> acclimatazione DETP
        descr += "\ncaldo: si"
    ev = {"category": f"RACE_{cfg['priorita']}", "start_date_local": f"{giorno}T00:00:00",
          "name": f"{nome} — {titolo}" if titolo.lower() not in nome.lower() else nome,
          "description": f"{descr}\nGara {cfg['priorita']} creata da {NOME_APP}.",
          "external_id": f"app:gara:{cfg.get('id') or giorno}"}
    try:
        http, h, base = _http_icu(cfg)
        if cfg.get("id"):
            r = http.put(f"{base}/events/{cfg['id']}", headers=h, json=ev, timeout=30)
        else:
            r = http.post(f"{base}/events", headers=h, json=ev, timeout=30)
        if r.status_code in (401, 403):
            out["esito"] = "permesso_mancante"
        elif r.status_code in (200, 201):
            out.update(esito="ok", id=(r.json() or {}).get("id") or cfg.get("id"), ripianifica=True)
        else:
            out["errore"] = f"HTTP {r.status_code} {r.text[:120]}"
    except Exception as e:
        out["errore"] = f"{type(e).__name__}: {e}"
    return json.dumps(out)


def elimina_gara(config_json):
    """Elimina la gara {"id"} solo se l'evento e' davvero una gara (RACE_*)."""
    from datetime import timedelta
    cfg = json.loads(config_json)
    out = {"esito": "errore"}
    try:
        http, h, base = _http_icu(cfg)
        oggi = _adesso(cfg)
        r = http.get(f"{base}/events", headers=h, timeout=30,
                     params={"oldest": (oggi - timedelta(days=30)).strftime("%Y-%m-%d"),
                             "newest": (oggi + timedelta(days=GARA_MESI_MAX * 31)).strftime("%Y-%m-%d")})
        if r.status_code in (401, 403):
            out["esito"] = "permesso_mancante"
            return json.dumps(out)
        ev = next((e for e in (r.json() or []) if str(e.get("id")) == str(cfg.get("id"))), None)
        if not ev or not (ev.get("category") or "").startswith("RACE"):
            out["esito"] = "non_trovata"
            return json.dumps(out)
        rd = http.delete(f"{base}/events/{cfg['id']}", headers=h, timeout=30)
        if rd.status_code in (200, 204):
            out.update(esito="ok", ripianifica=True)
        else:
            out["errore"] = f"HTTP {rd.status_code}"
    except Exception as e:
        out["errore"] = f"{type(e).__name__}: {e}"
    return json.dumps(out)


def gare(config_json):
    """Elenco delle gare future per l'app: id, nome, data, priorita', distanza come la
    riconosce il coach, settimane alla gara, e quale detta la periodizzazione."""
    from datetime import timedelta
    import coach_settimanale as voc
    cfg = json.loads(config_json)
    out = {"esito": "errore", "gare": []}
    try:
        http, h, base = _http_icu(cfg)
        oggi = _adesso(cfg)
        oggi_s = oggi.strftime("%Y-%m-%d")
        r = http.get(f"{base}/events", headers=h, timeout=30,
                     params={"oldest": oggi_s,
                             "newest": (oggi + timedelta(days=GARA_MESI_MAX * 31)).strftime("%Y-%m-%d")})
        if r.status_code in (401, 403):
            out["esito"] = "permesso_mancante"
            return json.dumps(out)
        lista = []
        for e in r.json() or []:
            cat = e.get("category") or ""
            if not cat.startswith("RACE"):
                continue
            g = {"id": e.get("id"), "name": e.get("name") or "", "date": (e.get("start_date_local") or "")[:10],
                 "category": cat, "priorita": {"RACE_A": 1, "RACE_B": 2, "RACE_C": 3}.get(cat, 9),
                 "dist_km": round((e.get("distance") or 0) / 1000, 1),
                 "desc": (e.get("description") or "")[:300]}
            lista.append(g)
        obiettivo = voc.gara_obiettivo(lista, oggi_s)
        lun = voc.lunedi_di(oggi_s)
        for g in sorted(lista, key=lambda x: x["date"]):
            out["gare"].append({
                "id": g["id"], "nome": g["name"], "data": g["date"], "priorita": g["category"][-1],
                "distanza": voc.classifica_distanza(g),
                # 06/10/2026: la distanza scelta dall'utente (riga "distanza:" scritta da
                # salva_gara); "distanza" resta quella della preparazione (sprint -> olimpico)
                "distanza_scelta": (lambda m: m.group(1).lower() if m else None)(
                    _RE_DISTANZA_SCELTA.search(g.get("desc") or "")),
                "settimane": voc.settimane_alla_gara(lun, g["date"]),
                "calda": voc.detp.gara_calda(g),
                "obiettivo": bool(obiettivo and obiettivo["id"] == g["id"])})
        out["esito"] = "ok"
    except Exception as e:
        out["errore"] = f"{type(e).__name__}: {e}"
    return json.dumps(out, ensure_ascii=False)


# ── BACKUP DELLO STATO DEL CERVELLO (06/10/2026 — roadmap punto 8) ──────────────────
# Un solo file JSON con lo stato del coach (test, soglie, flag, ultimo piano...) e i
# riepiloghi. La cifratura e la destinazione (file, Drive) le gestisce l'app: qui solo il
# contenuto, con formato e versione per poter rifiutare un file sconosciuto.
BACKUP_FORMATO, BACKUP_VERSIONE = "biosleep-cervello", 1
_BACKUP_NOMI = re.compile(r"^(stato_coach|riepilogo_\d{4}-\d{2}-\d{2})\.json$")


def esporta_stato(config_json):
    """{"cartella", "file": percorso di destinazione} -> {"esito", "file", "voci"}."""
    cfg = json.loads(config_json)
    cart = os.path.abspath(cfg["cartella"])
    out = {"esito": "errore"}
    try:
        voci = {}
        for nome in sorted(os.listdir(cart)):
            if _BACKUP_NOMI.match(nome):
                with open(os.path.join(cart, nome), encoding="utf-8") as f:
                    voci[nome] = json.load(f)
        pacco = {"formato": BACKUP_FORMATO, "versione": BACKUP_VERSIONE,
                 "creato": _adesso(cfg).isoformat(timespec="seconds"), "file": voci}
        tmp = cfg["file"] + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(json.dumps(pacco, ensure_ascii=False))
        os.replace(tmp, cfg["file"])
        out.update(esito="ok", file=cfg["file"], voci=len(voci))
    except Exception as e:
        out["errore"] = f"{type(e).__name__}: {e}"
    return json.dumps(out)


def importa_stato(config_json):
    """{"cartella", "file": backup} -> {"esito": "ok"|"non_valido"|"errore", "voci"}.
    Prima controlla tutto il file; poi salva lo stato attuale come
    stato_coach.prima_del_ripristino.json e scrive ogni voce in modo atomico. Accetta
    solo stato_coach.json e riepilogo_<data>.json: nessun nome di file arbitrario."""
    cfg = json.loads(config_json)
    cart = os.path.abspath(cfg["cartella"])
    out = {"esito": "non_valido"}
    try:
        with open(cfg["file"], encoding="utf-8") as f:
            pacco = json.load(f)
    except (OSError, ValueError) as e:
        out["errore"] = f"file non leggibile: {type(e).__name__}"
        return json.dumps(out)
    if not isinstance(pacco, dict) or pacco.get("formato") != BACKUP_FORMATO:
        out["errore"] = f"non e' un backup del cervello {NOME_APP}"
        return json.dumps(out)
    if not isinstance(pacco.get("versione"), int) or pacco["versione"] > BACKUP_VERSIONE:
        out["errore"] = "backup di una versione piu' recente dell'app: aggiorna l'app"
        return json.dumps(out)
    voci = pacco.get("file")
    if not isinstance(voci, dict) or not all(isinstance(k, str) and _BACKUP_NOMI.match(k)
                                            and isinstance(v, dict) for k, v in voci.items()):
        out["errore"] = "contenuto non valido"
        return json.dumps(out)
    if cfg.get("verifica"):          # 06/10/2026: solo controllo, nessuna scrittura
        out.update(esito="ok", voci=len(voci))
        return json.dumps(out)
    # 06/10/2026 — TUTTO O NIENTE: (1) tutte le voci si scrivono in una cartella
    # temporanea (dove avvengono quasi tutti gli errori possibili: disco pieno, permessi);
    # (2) i file che verranno sostituiti si spostano in una seconda cartella temporanea;
    # (3) le voci nuove si spostano al loro posto. Se (2) o (3) falliscono si rimettono i
    # file di prima e si tolgono quelli nuovi. Le cartelle temporanee spariscono sempre.
    import shutil, tempfile
    nuove = vecchie = None
    spostati, messi = [], []
    try:
        os.makedirs(cart, exist_ok=True)
        nuove = tempfile.mkdtemp(prefix=".ripristino_nuovo_", dir=cart)
        vecchie = tempfile.mkdtemp(prefix=".ripristino_vecchio_", dir=cart)
        for nome, contenuto in voci.items():
            with open(os.path.join(nuove, nome), "w", encoding="utf-8") as f:
                f.write(json.dumps(contenuto, ensure_ascii=False))
        for nome in voci:
            if os.path.exists(os.path.join(cart, nome)):
                os.replace(os.path.join(cart, nome), os.path.join(vecchie, nome))
                spostati.append(nome)
        for nome in voci:
            os.replace(os.path.join(nuove, nome), os.path.join(cart, nome))
            messi.append(nome)
        if "stato_coach.json" in spostati:
            shutil.copyfile(os.path.join(vecchie, "stato_coach.json"),
                            os.path.join(cart, "stato_coach.prima_del_ripristino.json"))
        out.update(esito="ok", voci=len(voci))
    except Exception as e:
        for nome in messi:
            try:
                os.remove(os.path.join(cart, nome))
            except OSError:
                pass
        for nome in spostati:
            try:
                shutil.move(os.path.join(vecchie, nome), os.path.join(cart, nome))
            except OSError:
                pass
        out.update(esito="errore", errore=f"{type(e).__name__}: {e} (ripristino annullato, "
                                          f"nessun file cambiato)")
    finally:
        for d in (nuove, vecchie):
            if d:
                shutil.rmtree(d, ignore_errors=True)
    return json.dumps(out)


def registra_sweat(config_json):
    """Sweat test (07/10/2026, punto 11): {"cartella", "p1", "p2" (kg nudo prima/dopo),
    "b1", "b2" (kg borraccia), "urine" (kg, di solito 0), "minuti", "sodio_mg_l"
    (facoltativo, dal patch)}. Salva {"litri_h", "sodio_mg_l"} nello stato del coach."""
    import detp as dt
    cfg = json.loads(config_json)
    sr = dt.sweat_rate(cfg.get("p1"), cfg.get("p2"), cfg.get("b1"), cfg.get("b2"),
                       cfg.get("urine"), cfg.get("minuti"))
    if sr is None:
        return json.dumps({"esito": "valori_non_validi",
                           "errore": "valori non plausibili (sudorazione tra 0,2 e 4 L/h, durata >= 30')"})
    import coach_settimanale as cs_
    cs_.STATE_FILE = os.path.join(os.path.abspath(cfg["cartella"]), "stato_coach.json")
    st = cs_.carica_stato()
    st["sweat"] = {"litri_h": sr}
    if isinstance(cfg.get("sodio_mg_l"), (int, float)) and 100 <= cfg["sodio_mg_l"] <= 3000:
        st["sweat"]["sodio_mg_l"] = cfg["sodio_mg_l"]
    cs_.salva_stato(st)
    return json.dumps({"esito": "ok", "litri_h": sr, "sodio_mg_l": st["sweat"].get("sodio_mg_l")})


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


def _imposta_fuso(cfg):
    """FUSO_ORARIO per i moduli (letto all'import, prima del reload). None se tutto ok,
    altrimenti il testo dell'avviso (fuso non valido -> Europe/Rome)."""
    from zoneinfo import ZoneInfo
    fuso = cfg.get("fuso")
    os.environ.pop("FUSO_ORARIO", None)
    if not fuso:
        return None
    try:
        ZoneInfo(fuso)
        os.environ["FUSO_ORARIO"] = fuso
        return None
    except Exception:
        return f"fuso orario non valido ({fuso}): uso Europe/Rome"


def _adesso(cfg):
    """Ora locale del telefono per le funzioni chiamate senza un run (gare, backup...)."""
    from datetime import datetime
    from zoneinfo import ZoneInfo
    try:
        return datetime.now(ZoneInfo(cfg.get("fuso") or "Europe/Rome")).replace(tzinfo=None)
    except Exception:
        return datetime.now(ZoneInfo("Europe/Rome")).replace(tzinfo=None)


def _carica_moduli():
    global cs
    import sedute, biometria, carico, soglie, palestra, caldo, detp, coach_settimanale
    for m in (sedute, biometria, carico, soglie, palestra, caldo, detp, coach_settimanale):
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


_NOMI_SPORT = {"Run": "corsa", "Ride": "bici", "Swim": "nuoto"}


def _aggiorna_soglie(piano, atleta, oggi, cfg_profilo=None):
    """Punto 4: registra il test messo nel piano e alza LTHR/FTP dagli sforzi reali
    (algoritmi di intervals_coach: solo al rialzo). Righe per gli avvisi del riepilogo.
    Un errore qui non ferma il run: finisce nel log."""
    righe = []
    try:
        stato = cs.carica_stato()
        for s in (piano or {}).get("sedute", []):
            if s.get("detp") == "sweat":                    # 07/10/2026 (punto 11)
                stato["ultimo_sweat_test"] = s["data"]
                righe.append(f"sweat test CORE in programma il {s['data']}")
            if s.get("test"):
                stato["ultimo_test"] = {"data": s["data"], "chiave": s["test"]["chiave"],
                                        "nome": s["test"]["nome"]}
                righe.append(f"test in programma il {s['data']}: {s['test']['nome']}")
        cs.soglie.configura(cs.ICU, cs.ATHLETE_ID, atleta,
                            cs.get_activities(cs.soglie.LTHR_LOOKBACK_DAYS))
        righe += cs.soglie.elabora_test(stato, oggi)      # passo soglia dal test, CSS
        agg, _ = cs.soglie.aggiorna_lthr_automatico(stato, oggi)
        for a in agg:
            righe.append(f"LTHR {_NOMI_SPORT.get(a['sport'], a['sport'])} {a['da']} -> {a['a']} "
                         f"aggiornata su Intervals.icu dagli sforzi reali")
        if (cfg_profilo or {}).get("stryd"):        # 07/10/2026 (punto 12): CP corsa da Stryd
            agg_cp, _ = cs.soglie.aggiorna_run_cp_automatico(stato, oggi)
            for a in agg_cp:
                righe.append(f"CP corsa {a.get('da') or 'nessuna'} -> {a['a']} W aggiornata su Intervals.icu")
        agg_ftp, _ = cs.soglie.aggiorna_bike_ftp_automatico(stato, oggi)
        for a in agg_ftp or []:
            righe.append(f"FTP bici {a.get('da') or 'nessuna'} -> {a['a']} W aggiornata su Intervals.icu")
        cs.salva_stato(stato)
    except Exception:
        print("  ⚠️ Aggiornamento soglie non riuscito:\n" + traceback.format_exc())
    return righe


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
    for k in ("FCMAX", "FCREST", "FC_DA_APP", "MAX_ORE_CARDIO_SETT", "DISPONIBILITA", "SETTIMANA_TIPO",
              "PALESTRA", "CALDO", "DETP"):
        os.environ.pop(k, None)
    prof = cfg.get("profilo") or {}
    if prof.get("fc_max") and prof.get("fc_riposo"):
        os.environ.update({"FCMAX": str(int(prof["fc_max"])), "FCREST": str(int(prof["fc_riposo"])),
                           "FC_DA_APP": "1"})
    if prof.get("tetto_ore"):
        os.environ["MAX_ORE_CARDIO_SETT"] = str(float(prof["tetto_ore"]))
    if prof.get("disponibilita"):
        os.environ["DISPONIBILITA"] = json.dumps(prof["disponibilita"])
    if prof.get("detp"):          # 07/10/2026: CORE 2 e protocollo DETP (punto 11)
        os.environ["DETP"] = json.dumps(prof["detp"])
    if prof.get("caldo"):         # 06/10/2026: preferenze per i giorni caldi (punto 7)
        os.environ["CALDO"] = json.dumps(prof["caldo"])
    if prof.get("palestra"):      # 06/10/2026: attrezzatura e livello (roadmap punto 6)
        os.environ["PALESTRA"] = json.dumps(prof["palestra"])
    if prof.get("settimana"):     # 06/10/2026: settimana tipo configurabile (roadmap punto 2)
        os.environ["SETTIMANA_TIPO"] = json.dumps(prof["settimana"])
    # 07/10/2026 (distribuzione): "oggi" segue il fuso del telefono (nome IANA)
    fuso_avviso = _imposta_fuso(cfg)
    out = {"esito": "errore", "notifiche": [], "riepilogo_file": None, "log_file": None}
    if fuso_avviso:
        out["notifiche"].append(fuso_avviso)
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
            sedute.configura(atleta, cs.get_activities(42),
                             potenza_corsa=potenza_corsa_attiva(prof, atleta))   # punto 12
            oggi = cs.now_local().strftime("%Y-%m-%d")
            # 06/10/2026 (punto 7): previsioni nel luogo del telefono, oggi + 7 giorni
            posiz = cfg.get("posizione") or {}
            if posiz.get("lat") is not None and posiz.get("lon") is not None:
                d0 = cs._dt(oggi)
                cs.METEO = cs.caldo.previsioni(
                    posiz["lat"], posiz["lon"],
                    [(d0 + cs.timedelta(days=i)).strftime("%Y-%m-%d") for i in range(8)],
                    cs.PROFILO_CALDO)
            stato0 = cs.carica_stato()
            # 07/10/2026 (punto 11): sudorazione dallo sweat test salvato; sweat test da
            # proporre se mai fatto o piu' vecchio di un anno
            if cs.detp.attivo(cs.PROFILO_DETP):
                if not cs.PROFILO_DETP.get("sweat") and stato0.get("sweat"):
                    cs.PROFILO_DETP["sweat"] = stato0["sweat"]
                ult = stato0.get("ultimo_sweat_test")
                cs.SWEAT_TEST_DA_FARE = not cs.PROFILO_DETP.get("sweat") and (
                    not ult or (cs._dt(oggi) - cs._dt(ult)).days > 365)
            # 06/10/2026 (punto 4): test periodico da proporre se l'ultimo e' vecchio
            # 06/10/2026 (punto 6): settimane dal primo uso della forza (onboarding)
            if not stato0.get("forza_inizio") and not cfg.get("dry_run"):
                stato0["forza_inizio"] = oggi
                cs.salva_stato(stato0)
            if stato0.get("forza_inizio"):
                cs.FORZA_SETTIMANE = (cs._dt(oggi) - cs._dt(stato0["forza_inizio"])).days // 7
            gg_test = cs.soglie.giorni_da_ultimo_test(stato0, oggi)
            if gg_test is None or gg_test >= cs.soglie.TEST_TARGET_GG:
                cs.TEST_PROSSIMO = cs.soglie.prossimo_test_in_rotazione(stato0)
            # 06/10/2026: "forza" = "Ripianifica questa settimana" dall'app (ignora il flag;
            # il passato non si riscrive comunque).
            esito, piano = cs.esegui_auto(cfg.get("modo", "auto"), bool(cfg.get("dry_run")),
                                          bool(cfg.get("forza")), None, bool(cfg.get("senza_attesa")))
            out["esito"] = esito
            cs.TEST_PROSSIMO = None     # vale solo per questo run
            cs.METEO = None
            cs.SWEAT_TEST_DA_FARE = False
            soglie_agg = []
            if not cfg.get("dry_run") and esito in ("pianificata", "fatto", "niente"):
                soglie_agg = _aggiorna_soglie(piano, atleta, oggi, prof)
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
                errori_sett = cs.settimana_tipo_valida(prof.get("settimana"))[1] if prof.get("settimana") else []
                if errori_sett:
                    rie["avvisi"] = "\n".join(x for x in [rie.get("avvisi") or "",
                                                         "settimana tipo non valida, uso quella predefinita: "
                                                         + "; ".join(errori_sett)] if x)
                if soglie_agg:
                    rie["avvisi"] = "\n".join(x for x in [rie.get("avvisi") or ""] + soglie_agg if x)
                da_compl = cs.soglie.test_da_completare(cs.carica_stato())
                if da_compl:
                    rie["test_da_completare"] = da_compl
                # 06/10/2026: soglie bloccanti mancanti su Intervals.icu (punto 3)
                bloccanti = [f"{m['disciplina']} {m['campo']}" for m in verifica_soglie(atleta, prof.get("stryd"))[0]
                             if m["gravita"] == "bloccante"]
                if bloccanti:
                    rie["avvisi"] = "\n".join(x for x in [rie.get("avvisi") or "",
                                                         "soglie da completare su Intervals.icu: "
                                                         + ", ".join(bloccanti)] if x)
                # 05/10/2026: decisioni prese per un tag, visibili nell'app
                if cs._MOTIVI_TAG or tag_scartati:
                    rie["motivi"] = list(dict.fromkeys((rie.get("motivi") or []) + cs._MOTIVI_TAG))
                    righe = [f"tag: {m}" for m in cs._MOTIVI_TAG]
                    if tag_scartati:
                        righe.append("tag sconosciuti ignorati: " + ", ".join(sorted(set(tag_scartati))))
                    rie["avvisi"] = "\n".join(x for x in [rie.get("avvisi") or ""] + righe if x)
                # 07/10/2026 (punto 13a): messaggi con codice e valori, per la traduzione
                rie["messaggi"] = messaggi.messaggi_riepilogo(rie, out["notifiche"])
                with open(percorso + ".tmp", "w", encoding="utf-8") as f:
                    f.write(json.dumps(rie, ensure_ascii=False))
                os.replace(percorso + ".tmp", percorso)
                out["riepilogo_file"] = percorso
    except Exception as e:
        out["errore"] = f"{type(e).__name__}: {e}"
        buf.write(traceback.format_exc())
    finally:
        try:
            oggi_log = _adesso(cfg).strftime("%Y-%m-%d_%H%M%S_%f")
            log = os.path.join(cartella, "log", f"coach_{oggi_log}.txt")
            with open(log, "w", encoding="utf-8") as f:
                f.write(buf.getvalue())
            out["log_file"] = log
            vecchi = sorted(n for n in os.listdir(os.path.join(cartella, "log")) if n.startswith("coach_"))
            for n in vecchi[:-30]:
                os.remove(os.path.join(cartella, "log", n))
        except OSError:
            pass
    out["messaggi"] = [messaggi.codifica(n, "notifica") for n in out["notifiche"]]   # punto 13a
    out["versione"] = VERSIONE
    return json.dumps(out, ensure_ascii=False)


if __name__ == "__main__":
    print(esegui_app(sys.argv[1] if len(sys.argv) > 1 else sys.stdin.read()))
