# -*- coding: utf-8 -*-
"""
BIOMETRIA — banda biometrica HRV/FC a riposo e stato di forma (cervello BioSleep).

Estratto MECCANICAMENTE da intervals_coach.py (03/10/2026): calc_baseline_biometrici (la
formula affinata nel tempo, scelta da Simone come unica banda), stato_forma e l'adattatore
della serie BioSleep (campi wellness BioSleep* su Intervals.icu). Stessa formula della
prontezza mostrata nell'app. Codice copiato senza modifiche; test in test_biometria.py.
"""
import math, os
from collections import defaultdict
from datetime import datetime, timedelta

from sedute import now_local



# MODIFICA (02/10/2026): BioSleepQuality = % della registrazione coperta da battiti validi.
# Sotto soglia la notte resta fuori dalla serie: un rMSSD da artefatti/disconnessioni
# sarebbe letto come affaticamento. Notti senza il campo (registrate prima che esistesse)
# restano nella serie, altrimenti si perde lo storico.
BIOSLEEP_QUALITA_MIN   = 80

def biosleep_history_da_wellness(wellness, oura_history=None):
    """Serie BioSleep nello stesso formato di get_oura_history, per calc_baseline_biometrici.
    Giorno senza BioSleepRMSSD valido = saltato (mai zero). Tag confondenti presi da Oura."""
    tags = {g.get("data"): g.get("tags") for g in (oura_history or [])
            if isinstance(g, dict) and g.get("data") and g.get("tags")}
    num = lambda v: v if isinstance(v, (int, float)) and v > 0 else None
    out = []
    for w in wellness or []:
        if not isinstance(w, dict):
            continue
        d = str(w.get("id") or w.get("date") or "")[:10]
        hrv = num(w.get("BioSleepRMSSD"))
        if not d or hrv is None:
            continue
        q = w.get("BioSleepQuality")
        if isinstance(q, (int, float)) and q < BIOSLEEP_QUALITA_MIN:
            continue
        sonno = num(w.get("BioSleepSleepHours"))
        g = {"data": d, "hrv_ms": hrv, "resting_hr": num(w.get("BioSleepAvgHR")),
             "sleep_h": round(sonno, 1) if sonno else None}
        if tags.get(d):
            g["tags"] = list(tags[d])
        out.append(g)
    return sorted(out, key=lambda x: x["data"])

# ── ZONE DI FORMA (grafico Fitness/Fatica/Forma di Intervals.icu) ──────────
# Forma (TSB) = CTL - ATL. Intervals.icu colora il grafico sulla FORMA RELATIVA
# (Form% = TSB/CTL x 100), non sul TSB assoluto: e' il motivo per cui browser e app
# a volte mostrano zone diverse per lo stesso giorno (un caso segnalato sul forum:
# CTL 44 / ATL 60 -> TSB -16, "Optimal" in assoluto, ma -36% -> "High Risk" in
# relativo). Qui si usa la forma RELATIVA come classificazione primaria, perche' e'
# l'unica scala che regge il confronto nel tempo: -20 di TSB su CTL 40 e' uno scavo
# doppio rispetto allo stesso -20 su CTL 80, e Simone e' un amatore con CTL modesta,
# esattamente il caso in cui la soglia assoluta -30 (tarata da Friel su atleti con
# CTL alta) sottostima il rischio. Il TSB assoluto resta nel prompt perche' e' il
# numero che Simone vede sul browser.
# Confini (stessi numeri della legenda Intervals.icu/Friel, letti come percentuali):
#   > +20   Transizione (giallo)  stacco/fine stagione: fitness in caduta
#   +5..+20 Fresco (blu)          forma da gara: e' qui che si arriva in taper
#   -10..+5 Grigia (grigio)       plateau: non si costruisce quasi nulla
#   -30..-10 Ottimale (verde)     dove avvengono gli adattamenti
#   < -30   Alto rischio (rosso)  non produttiva, rischio infortunio/sovrallenamento
# BUG FIX (28/08/2026): i confini grigia/ottimale erano -5/+10, ma Friel (e la legenda
# Intervals.icu, che usa gli stessi numeri letti come %) mettono la grigia fra -10 e +5 e
# l'ottimale fra -30 e -10. Con -5 lo script dichiarava "grigia" giorni che sul grafico
# di Simone erano verdi -> contraddizione nello STATO e freno di progressione sbagliato.
_FORM_ZONE_BANDS = ((20, "Transizione", "giallo"), (5, "Fresco", "blu"),
                    (-10, "Grigia", "grigio"), (-30, "Ottimale", "verde"))

# BUG FIX (25/09/2026 — misurato, stessa correzione di periodization_coach.py del
# 16/09): la classificazione sopra sul RELATIVO contraddice il grafico di Simone. Su
# CTL 42.4 / ATL 38.1 il TSB e' +4.3 (grigia, come sul grafico) ma in relativo fa
# +10.1% -> "Fresco": in una settimana di carico il piano riceveva "sei troppo fresco,
# copri il volume senza tagli" con il grafico grigio, e su CTL 44 / ATL 60 (TSB -16,
# verde sul grafico) dichiarava zona rossa. Le due scale coincidono solo a CTL ~100.
# Ora la ZONA si legge sul TSB assoluto (i confini sopra sono gli stessi numeri); la
# forma relativa resta stampata e sotto RISCHIO_RELATIVO_PCT accende rischio_relativo,
# che frena la progressione (fattore_progressione) e la copertura
# (_freno_biometrico_copertura) come la zona rossa: il cambio di scala non allenta freni.
RISCHIO_RELATIVO_PCT = float(os.getenv("RISCHIO_RELATIVO_PCT", -30.0))

FORM_ZONA_ROSSA  = "Alto rischio"

def _zona_da_form_pct(form_pct):
    for soglia, nome, colore in _FORM_ZONE_BANDS:
        if form_pct > soglia:
            return nome, colore
    return FORM_ZONA_ROSSA, "rosso"

# ── CTL/ATL DALLE SOLE SEDUTE ESEGUITE ─────────────────────────────────────
# BUG FIX (13/09/2026 — misurato contro l'API): le righe wellness di Intervals.icu
# contengono il carico degli allenamenti PIANIFICATI, non solo di quelli eseguiti. La
# riga di oggi restituita dall'endpoint si riproduce fino alla sesta cifra decimale
# applicando l'EWMA ai valori di ieri con il carico della seduta ancora da fare.
# Due conseguenze, entrambe di segno opposto alla realta':
#   - la seduta di OGGI gonfia la fatica di oggi: il coach misura la freschezza su un
#     numero che contiene gia' il carico della sessione che sta per prescrivere. Piu'
#     pesante pianifica, piu' stanco si crede. Ogni giorno.
#   - una seduta SALTATA continua a pesare finche' l'evento resta a calendario. Il
#     12/09/2026 il long bike da 2h30 non e' stato fatto: alle 08:39 del giorno dopo il
#     suo carico era ancora dentro. Misurato: CTL 48.4 / ATL 63.4 / TSB -15.0 -> zona
#     Alto rischio -> "tieni la seduta di oggi in Z1-Z2" -> long run declassato. Sulle
#     sole sedute eseguite gli stessi giorni danno CTL 42.9 / ATL 34.5 / TSB +8.3:
#     riposare faceva risultare piu' stanchi.
# Il ricalcolo puo' solo TOGLIERE carico mai eseguito, mai aggiungerne: se i carichi
# reali superano quelli contabilizzati i dati non sono coerenti e non si tocca nulla.
# Vale la stessa regola per ogni altra ambiguita' (attivita' senza carico, lista
# attivita' vuota): togliere fatica che esiste davvero farebbe spingere il coach
# proprio quando deve frenare, ed e' il verso pericoloso dell'errore.
_EWMA_ATL = math.exp(-1 / 7)

_EWMA_CTL = math.exp(-1 / 42)

_CARICO_TOLL = 5.0

def wellness_reale(wellness, activities):
    """Righe wellness ricalcolate sulle sole sedute realmente eseguite.

    Per ogni giorno si ricava dalla wellness il carico che Intervals.icu ha
    contabilizzato e lo si confronta con quello delle ATTIVITA'. Dal primo giorno in cui
    il contabilizzato eccede l'eseguito (cioe' dove e' entrato del pianificato) ctl/atl
    si rifanno in avanti con i soli carichi reali; i giorni a monte, e i giorni puliti
    quando non c'e' nulla da correggere, restano intatti — il ricalcolo non deve
    introdurre deriva per conto suo. Gli altri campi della riga (rampRate, biometrici)
    passano com'erano. Ritorna `wellness` tale e quale in ogni caso in cui il ricalcolo
    non sia difendibile."""
    righe = sorted([x for x in (wellness or [])
                    if isinstance(x, dict)
                    and isinstance(x.get("ctl"), (int, float)) and not isinstance(x.get("ctl"), bool)
                    and isinstance(x.get("atl"), (int, float)) and not isinstance(x.get("atl"), bool)],
                   key=lambda x: x.get("id") or x.get("date") or "")
    if len(righe) < 2 or not activities:
        return wellness
    carichi = defaultdict(float)
    for a in activities or []:
        if not isinstance(a, dict):
            continue
        giorno = (a.get("start_date_local") or "")[:10]
        if not giorno:
            continue
        carico = a.get("icu_training_load")
        if not isinstance(carico, (int, float)) or isinstance(carico, bool):
            return wellness      # carico ignoto: la differenza non e' attribuibile al pianificato
        carichi[giorno] += carico
    sporco = None
    incoerente = None
    for i in range(1, len(righe)):
        giorno = righe[i].get("id") or righe[i].get("date") or ""
        contabilizzato = (righe[i]["atl"] - righe[i - 1]["atl"] * _EWMA_ATL) / (1 - _EWMA_ATL)
        eseguito = carichi.get(giorno, 0.0)
        # BUG FIX (26/09/2026 — misurato con diag_forma.py): un giorno con piu' carico
        # reale che contabilizzato (14/09: 31 contro 25) faceva uscire SUBITO, anche se
        # il ricalcolo parte dalla riga prima del giorno sporco e quel giorno non lo
        # tocca. Conseguenza osservabile: TSB -10.0 (zona Ottimale) con i 68 TSS solo
        # pianificati di oggi dentro, invece di -2.6 (zona Grigia). L'incoerenza blocca
        # ancora se cade sulla riga di innesco o dopo.
        if eseguito > contabilizzato + _CARICO_TOLL:
            incoerente = i       # piu' carico reale di quanto contabilizzato: dati incoerenti
            continue
        if sporco is None and contabilizzato - eseguito > _CARICO_TOLL:
            sporco = i
    if sporco is None:
        return wellness
    if incoerente is not None and incoerente >= sporco - 1:
        return wellness
    ctl, atl = righe[sporco - 1]["ctl"], righe[sporco - 1]["atl"]
    corretti = {}
    for r in righe[sporco:]:
        giorno = r.get("id") or r.get("date") or ""
        carico = carichi.get(giorno, 0.0)
        ctl = ctl * _EWMA_CTL + carico * (1 - _EWMA_CTL)
        atl = atl * _EWMA_ATL + carico * (1 - _EWMA_ATL)
        corretti[giorno] = (ctl, atl)
    # BUG FIX (13/09/2026 — residuo del fix sopra, misurato): ctl/atl venivano ripuliti
    # ma rampRate restava quello di Intervals.icu, cioe' ancora calcolato sul
    # pianificato. rampRate del giorno e' CTL di quel giorno meno CTL di sette giorni
    # prima (verificato contro l'API sull'11, 12 e 13/09: coincide alla sesta cifra),
    # quindi si rifa' dai CTL gia' corretti senza chiamate nuove. Prima: la seduta
    # pianificata per oggi alzava il CTL di oggi di carico x 0.0235 e quei punti
    # finivano nella rampa; sopra MS703_RAMP_CTL_MAX scattava il freno e il tetto
    # settimanale scendeva da 1.10x a 1.00x del sostenibile — ore tolte alla settimana
    # per una rampa mai prodotta. Se il giorno -7 non e' in finestra si tiene il valore
    # di Intervals.icu: sovrastima la rampa, quindi semmai frena, ed e' il verso prudente.
    ctl_per_giorno = {(x.get("id") or x.get("date") or ""): x["ctl"] for x in righe}
    ctl_per_giorno.update({g: v[0] for g, v in corretti.items()})
    rampe = {}
    for giorno in corretti:
        try:
            meno7 = (datetime.strptime(giorno, "%Y-%m-%d")
                     - timedelta(days=7)).strftime("%Y-%m-%d")
        except ValueError:
            continue
        if meno7 in ctl_per_giorno:
            rampe[giorno] = ctl_per_giorno[giorno] - ctl_per_giorno[meno7]
    ultima = righe[-1]
    scarto = round((ctl - atl) - (ultima["ctl"] - ultima["atl"]), 1)
    if abs(scarto) >= 1:
        print(f"  Forma dalle sole sedute eseguite: TSB {round(ctl - atl, 1)} "
              f"invece di {round(ultima['ctl'] - ultima['atl'], 1)} "
              f"({scarto:+} togliendo il carico solo pianificato)")
    fuori = []
    for x in (wellness or []):
        giorno = (x.get("id") or x.get("date") or "") if isinstance(x, dict) else ""
        if giorno not in corretti:
            fuori.append(x)
            continue
        nuovo = dict(x, ctl=corretti[giorno][0], atl=corretti[giorno][1])
        if giorno in rampe:
            nuovo["rampRate"] = rampe[giorno]
        fuori.append(nuovo)
    return fuori

def stato_forma(wellness):
    """Fitness/Fatica/Forma di oggi + zona del grafico Intervals.icu + da quanti giorni
    si sta in quella zona. Ritorna {} se la wellness non ha ancora CTL.

    La persistenza e' il dato che mancava: una giornata in zona grigia non significa
    niente, sette di fila sono un mesociclo buttato; un giorno in rossa capita dopo un
    lungo, tre di fila sono un buco che si paga. Senza contarli il modello poteva solo
    guardare il valore di oggi, che e' rumore."""
    w = [x for x in (wellness or []) if isinstance(x, dict) and x.get("ctl")]
    if not w:
        return {}
    w.sort(key=lambda x: x.get("id") or x.get("date") or "")
    def _zona(x):
        ctl, atl = x.get("ctl") or 0, x.get("atl") or 0
        if not ctl:
            return None, None, None
        tsb = ctl - atl
        return round(tsb, 1), round(tsb / ctl * 100, 1), _zona_da_form_pct(tsb)
    tsb, pct, zc = _zona(w[-1])
    if zc is None:
        return {}
    nome, colore = zc
    gg = 1
    for x in reversed(w[:-1]):                       # quanti giorni consecutivi in questa zona
        _, _, z = _zona(x)
        if not z or z[0] != nome:
            break
        gg += 1
    return {"ctl": round(w[-1]["ctl"], 1), "atl": round(w[-1].get("atl") or 0, 1),
            "tsb": tsb, "form_pct": pct, "zona": nome, "colore": colore,
            "giorni_in_zona": gg, "storico_gg": len(w),
            # BUG FIX (25/09/2026): vedi RISCHIO_RELATIVO_PCT.
            "rischio_relativo": pct < RISCHIO_RELATIVO_PCT}

def zona_forma_attesa(fase):
    """Zona di forma ATTESA per la fase: e' l'obiettivo contro cui si legge quella reale.
    Carico -> Ottimale (verde): e' li' che avvengono gli adattamenti. Scarico -> Grigia o
    Fresco, ed e' corretto cosi': lo scarico serve proprio a risalire. Taper/Picco ->
    Fresco (blu), la forma da gara. Transizione (giallo) non e' mai un obiettivo: e' fine
    stagione o stop forzato."""
    f = (fase or "")
    if "Taper" in f or "Picco" in f:
        return "Fresco", "e' la forma da gara: si arriva qui riducendo il volume, non l'intensita'"
    if "Scarico" in f:
        return "Grigia", "risalita fisiologica dello scarico, attesa e voluta: non e' un problema"
    return "Ottimale", "e' la zona in cui il carico produce adattamento"

# tag che confondono il dato biometrico (non contano come segnale di carico).
# MODIFICA (05/10/2026 — contratto con l'app BioSleep): i tag arrivano solo dall'app, con un
# vocabolario fisso; il confronto e' ESATTO sulla chiave (prima: sottostringa sui nomi dei
# tag Oura, per cui "malattia", "altitudine", "sonno_disturbato" e "caffeina_tardi" non
# venivano riconosciuti). La copia Kotlin nell'app usa lo stesso elenco.
TAG_CONFONDENTI = ("alcol", "cena_tardiva", "caffeina_tardi", "stress", "viaggio",
                   "malattia", "sonno_disturbato", "caldo", "altitudine")
_BASELINE_TAG_CONFONDENTI = TAG_CONFONDENTI

def _bio_media_sd(vals):
    vals = [v for v in vals if isinstance(v, (int, float))]
    if not vals:
        return None, None
    m = sum(vals) / len(vals)
    sd = (sum((v - m) ** 2 for v in vals) / len(vals)) ** 0.5 if len(vals) > 1 else 0.0
    return m, sd

def calc_baseline_biometrici(oura_history_long, phase=None, today_str=None):
    """Baseline personale + media mobile 7gg + coefficiente di variazione per HRV
    e FC a riposo, con la BANDA DECISIONALE (verde/giallo/rosso). E' questo l'output
    che guida la pianificazione, NON il valore del singolo giorno. Vedi il commento
    metodologico sopra. Richiede una finestra Oura lunga (~60gg) per una baseline
    stabile; degrada con grazia se ci sono pochi giorni."""
    def has_conf(tags):
        return any((t or "") in _BASELINE_TAG_CONFONDENTI for t in (tags or []))

    hist = sorted([d for d in (oura_history_long or []) if d.get("data")],
                  key=lambda x: x["data"])
    hrv = [(d["data"], d.get("hrv_ms"), has_conf(d.get("tags"))) for d in hist if d.get("hrv_ms")]
    rhr = [(d["data"], d.get("resting_hr"), has_conf(d.get("tags"))) for d in hist if d.get("resting_hr")]

    out = {"ok": False, "n_giorni_hrv": len(hrv)}
    if len(hrv) < 7:
        out["nota"] = (f"Solo {len(hrv)} giorni di HRV disponibili: servono ~14+ giorni "
                       f"per una baseline affidabile. Non prendere decisioni drastiche sul solo dato di oggi.")
        return out

    # METODO (Plews & Laursen 2013, Flatt & Esco 2016, Buchheit 2014): l'HRV rMSSD e'
    # log-normale, quindi media, SD e "normal range" si calcolano su Ln(rMSSD); le bande sono
    # INDIVIDUALI = baseline +/- 0.5 x SD della baseline (smallest worthwhile change), non una
    # percentuale fissa uguale per tutti. Le percentuali (-10/-20%) restano solo come numero
    # descrittivo nel prompt: la decisione la prende il normal range.
    import math
    ln = lambda v: math.log(max(v, 1e-6))

    # ── LE FINESTRE SONO GIORNI DI CALENDARIO, NON RIGHE DELLA LISTA ─────────────────
    # BUG FIX (29/08/2026): roll7, prev7 e la finestra della FC a riposo erano fette di
    # LISTA (hrv[-7:], hrv[-14:-7]), cioe' gli ultimi 7 RECORD, non gli ultimi 7 GIORNI.
    # Con l'anello scarico o non sincronizzato due notti, la "media 7gg" diventava in
    # silenzio una media su 9-10 giorni, e direzione_7v7 confrontava due finestre di
    # ampiezza reale diversa: un delta che il codice presenta come "andamento settimanale"
    # e che pulisci_trend_inventato usa come UNICA verita' per censurare le frasi del
    # coach. E' lo stesso buco gia' chiuso su persistenza_gg_sotto il 28/08 (li' la catena
    # si interrompe correttamente sul giorno mancante), rimasto aperto qui.
    # In piu' NESSUNO guardava la VETUSTA': se l'ultimo dato Oura e' di tre giorni fa, la
    # banda veniva comunque presentata nello STATO come lo stato di stamattina.
    def _dt(d_):
        try:
            return datetime.strptime(str(d_)[:10], "%Y-%m-%d")
        except (TypeError, ValueError):
            return None

    def _finestra(serie, fine_dt, giorni, solo_puliti=False):
        """Valori della serie [(data, valore, confondente)] che cadono negli ultimi
        `giorni` giorni di calendario terminanti in fine_dt (estremi inclusi)."""
        inizio = fine_dt - timedelta(days=giorni - 1)
        return [v for d_, v, c in serie
                if (not solo_puliti or not c)
                and (_dt(d_) is not None and inizio <= _dt(d_) <= fine_dt)]

    ultimo_dt = next((_dt(d_) for d_, _v, _c in reversed(hrv) if _dt(d_)), None)
    if ultimo_dt is None:
        out["nota"] = "Date dei dati Oura illeggibili: baseline non calcolabile."
        return out
    inizio_roll = ultimo_dt - timedelta(days=6)

    # Quanti giorni fa e' l'ultimo dato disponibile: sopra 2 il quadro non e' piu' "di
    # stamattina" e va dichiarato, non spacciato per attuale.
    try:
        oggi_dt = datetime.strptime(str(today_str)[:10], "%Y-%m-%d") if today_str else now_local()
    except (TypeError, ValueError):
        oggi_dt = now_local()
    gg_ritardo = max(0, (oggi_dt.replace(hour=0, minute=0, second=0, microsecond=0)
                         - ultimo_dt).days)

    # media mobile operativa = ultimi 7 giorni di CALENDARIO (tutti, non solo puliti)
    roll7_vals = _finestra(hrv, ultimo_dt, 7)
    if len(roll7_vals) < 3:      # rete: finestra troppo vuota, si torna ai record
        roll7_vals = [v for _, v, _ in hrv[-7:]]
    roll7 = sum(roll7_vals) / len(roll7_vals)
    roll7_ln = sum(ln(v) for v in roll7_vals) / len(roll7_vals)

    # baseline = media dei giorni PULITI PRECEDENTI alla finestra dei 7 giorni
    # (riferimento consolidato). Fallback progressivi se i dati puliti scarseggiano.
    base_pool = [v for d_, v, conf in hrv
                 if not conf and _dt(d_) is not None and _dt(d_) < inizio_roll]
    if len(base_pool) < 7:
        base_pool = [v for _, v, conf in hrv if not conf]
    if len(base_pool) < 7:
        base_pool = [v for _, v, _ in hrv]
    # FINESTRA UNICA (28/08/2026): baseline lineare (pct nel prompt) e base_ln/SD (banda)
    # usavano pool diversi (tutto il pool vs ultimi 42 gg): il prompt poteva dire "-12%" e
    # "banda VERDE" nella stessa riga. Ora tutte le statistiche di baseline vengono dagli
    # stessi giorni.
    BASELINE_SD_GG = 42
    base_pool = base_pool[-BASELINE_SD_GG:]
    baseline, _sd_lin = _bio_media_sd(base_pool)
    cv = round(_sd_lin / baseline * 100, 1) if baseline else None

    # ── SD DEL NORMAL RANGE: SCELTA METODOLOGICA, MOTIVATA (28/08/2026) ──────────────
    # La SD resta quella dei VALORI GIORNALIERI di Ln rMSSD, non quella della media
    # mobile a 7 giorni. E' una decisione presa dopo aver provato l'alternativa, non
    # per inerzia, e i due mondi vanno distinti:
    #   - Hopkins/Flatt (TrainingPeaks) definiscono la SWC come 0.5 x SD della media
    #     settimanale. Statisticamente e' ineccepibile — la variabile giudicata E' la
    #     media 7gg — ma la SD di una media di 7 misure vale ~1/2.5 di quella dei singoli
    #     giorni: applicata qui produceva una SWC dell'1.5-2%, cioe' banda GIALLA a ogni
    #     calo del 2% della media settimanale. Un calo del 5-10% e' pero' la risposta
    #     ATTESA a una settimana di carico (e' scritto in _contesto_fase_baseline, sulle
    #     stesse fonti): con quelle soglie ogni mesociclo verrebbe frenato a meta'.
    #     Lo stesso articolo, non a caso, affianca alla formula un trigger pratico molto
    #     piu' largo — un calo di ~7.5% del rMSSD grezzo.
    #   - Altini/HRV4Training (e' il metodo del "normal range" che Simone vede nelle app)
    #     confrontano la media mobile 7gg contro una banda costruita sui giorni singoli.
    #     E' meno elegante ma e' tarato sui numeri che i praticanti usano davvero, ed e'
    #     quello che con un CV del 10% mette il giallo a -5% e il rosso a -10%: le stesse
    #     soglie della letteratura applicata.
    # Si tiene quindi il secondo, con due correzioni al modo in cui la SD viene stimata.
    #
    # (1) FINESTRA LIMITATA. La SD veniva calcolata su tutto il pool di baseline, fino a
    # ~53 giorni. Su due mesi dentro la dispersione finisce anche la DERIVA di forma
    # (l'HRV sale con l'adattamento), che non e' rumore giornaliero: la SD si gonfia e la
    # banda si allarga proprio quando l'atleta sta migliorando, cioe' quando servirebbe
    # piu' sensibile. Ora la SD si stima sui giorni piu' recenti del pool.
    base_ln, sd_ln = _bio_media_sd([ln(v) for v in base_pool])
    sd_ln = sd_ln or 0.0
    #
    # (2) GUARD RAIL SULLA SWC. La SD e' una stima, e su 6 settimane di dati puo' uscire
    # implausibile in entrambi i versi — ed entrambi i versi fanno danno:
    #   - troppo GRANDE (misure rumorose, sonno irregolare, CV 15-20%): 1 SD vale il 18%
    #     e la banda ROSSA non scatta mai. E' il caso pericoloso, ed e' esattamente il
    #     motivo per cui una banda "individuale" non tarata puo' essere peggio di una
    #     soglia fissa.
    #   - troppo PICCOLA: la banda diventa un allarme continuo.
    # La SWC operativa (0.5 SD, letta in % sul valore in ms) viene quindi tenuta nella
    # forbice 3-8%, che copre l'intervallo di CV tipico di un atleta allenato (5-10%,
    # Plews) e il trigger pratico del 7.5% citato sopra.
    SWC_PCT_MIN, SWC_PCT_MAX = 3.0, 8.0
    _sd_min, _sd_max = SWC_PCT_MIN / 50.0, SWC_PCT_MAX / 50.0      # 0.5*sd_ln ~ frazione
    sd_grezza = sd_ln
    # CV IN COLLASSO — RELATIVO, non assoluto (28/08/2026). Prima scattava se la SD di
    # baseline era sotto 0.06 (CV ~6%): in un atleta con CV naturale del 5-6% la nota
    # "non aumentare il carico" compariva ogni settimana. Plews 2013 descrive un CALO del
    # CV rispetto al proprio storico (nel caso studio da ~9% a ~5%), non un livello
    # assoluto: qui il CV degli ULTIMI 7 giorni si confronta col CV della baseline e
    # l'allarme scatta solo se e' sceso sotto il 60% di quello.
    _, sd_roll7_ln = _bio_media_sd([ln(v) for v in roll7_vals])
    cv_collassato = bool(sd_grezza and len(base_pool) >= 21 and len(roll7_vals) >= 6
                         and (sd_roll7_ln or 0.0) < 0.6 * sd_grezza)
    sd_clampata   = bool(sd_grezza and not (_sd_min <= sd_grezza <= _sd_max))
    if sd_grezza:
        sd_ln = min(max(sd_grezza, _sd_min), _sd_max)
    pct = round((roll7 - baseline) / baseline * 100, 1) if baseline else None
    # z = di quante SD (su ln) la media 7gg sta sotto/sopra la baseline
    z = round((roll7_ln - base_ln) / sd_ln, 2) if sd_ln > 0 else None
    # normal range riportato in ms, cosi' Simone lo puo' confrontare con l'app
    nr_lo = round(math.exp(base_ln - 0.5 * sd_ln), 1)
    nr_hi = round(math.exp(base_ln + 0.5 * sd_ln), 1)

    # BUG FIX (22/08/2026 — allucinazione "la media settimanale e' risalita sopra la
    # baseline"): il prompt riceveva solo la FOTOGRAFIA (media 7gg vs baseline) e nessun
    # dato sul MOVIMENTO, cosi' la direzione del trend il modello se la inventava. Qui il
    # confronto deterministico fra la finestra dei 7 giorni e quella dei 7 precedenti: e'
    # l'unica direzione che il coach puo' dichiarare (vedi format_baseline_biometrici e
    # pulisci_trend_inventato). Costo run invariato: stessi dati Oura gia' in memoria.
    prev7 = _finestra(hrv, ultimo_dt - timedelta(days=7), 7)
    media_prev7 = sum(prev7) / len(prev7) if len(prev7) >= 4 else None
    d77 = round((roll7 - media_prev7) / media_prev7 * 100, 1) if media_prev7 else None
    if d77 is None:
        direzione = "non determinabile"
    elif d77 >= 3:
        direzione = "in salita"
    elif d77 <= -3:
        direzione = "in calo"
    else:
        direzione = "stabile"

    # persistenza: giorni consecutivi (dal piu' recente) sotto il normal range (baseline-0.5SD)
    # BUG FIX (28/08/2026): il contatore scorreva la lista dei giorni CON DATO, non i
    # giorni di calendario. Con l'anello scarico o non sincronizzato per due notti, tre
    # giorni bassi distribuiti su otto risultavano "5 consecutivi" — e a 5 fattore_
    # progressione congela il tetto del volume anche in banda verde. Un buco di
    # sincronizzazione non e' un giorno sotto soglia: la catena si interrompe.
    soglia_g = nr_lo if sd_ln > 0 else (baseline * 0.90 if baseline else None)
    persist = 0
    if soglia_g is not None:
        atteso = None
        for d_, v, _c in reversed(hrv):
            try:
                giorno = datetime.strptime(str(d_)[:10], "%Y-%m-%d")
            except (TypeError, ValueError):
                break
            if atteso is not None and giorno != atteso:
                break                      # giorno mancante: la serie non e' consecutiva
            if v >= soglia_g:
                break
            persist += 1
            atteso = giorno - timedelta(days=1)

    # BUG FIX (30/09/2026): la banda decide sulla media dei ln, il messaggio mostrava la media
    # aritmetica (sempre un po' piu' alta). Run del 30/09: "media 7gg 54.1ms, limite 54.1ms"
    # in giallo; con serie piu' variabili la media mostrata finiva SOPRA il limite violato.
    # Con z calcolabile, media, baseline e % si mostrano sulla scala dei ln (in ms); due
    # decimali se media e limite coincidono al decimo. Soglie, persistenza e trend invariati.
    r7_out, base_out, pct_out, lo_out = round(roll7, 1), round(baseline, 1), pct, nr_lo
    if z is not None:
        _r7g, _log, _bg = math.exp(roll7_ln), math.exp(base_ln - 0.5 * sd_ln), math.exp(base_ln)
        _dec = 2 if round(_r7g, 1) == round(_log, 1) else 1
        r7_out, lo_out = round(_r7g, _dec), round(_log, _dec)
        base_out, pct_out = round(_bg, 1), round((_r7g - _bg) / _bg * 100, 1)

    # Bande sul normal range (Plews & Laursen: SWC = 0.5 SD; sotto 1 SD la deviazione e' "quasi
    # certamente" reale). Fallback sulle vecchie percentuali solo se la SD non e' calcolabile
    # (baseline troppo corta o piatta).
    if z is None and pct is None:
        banda, azione = "grigio", "Dati insufficienti per una banda affidabile."
    elif z is not None:
        if z >= -0.5:
            banda = "verde"
            azione = (f"Procedi con la seduta pianificata (media 7gg dentro il normal range "
                      f"{lo_out}-{nr_hi}ms).")
        elif z > -1.0:
            banda = "giallo"
            azione = (f"Riduci intensita'/volume o converti la qualita' in Z1-Z2 (media 7gg sotto il "
                      f"normal range: {z:+.1f} SD, limite inferiore {lo_out}ms).")
        else:
            banda = "rosso"
            azione = (f"Recupero attivo o riposo, specie se persistente su piu' giorni "
                      f"(media 7gg {z:+.1f} SD sotto baseline, oltre una deviazione standard).")
        # Saturazione parasimpatica (Plews 2013): HRV ben SOPRA il range con FC riposo in calo
        # nelle settimane di carico pesante puo' essere overreaching, non freschezza. Non
        # cambia banda (falsi positivi frequenti), ma il coach lo deve sapere.
        if z >= 1.0:
            azione += (" NOTA: media 7gg oltre +1 SD sopra la baseline: se coincide con FC riposo "
                       "bassa e gambe pesanti, leggila come possibile saturazione parasimpatica "
                       "(overreaching), non come freschezza garantita.")
        if cv_collassato:
            azione += (" NOTA: la variabilita' giorno-per-giorno dell'HRV si e' quasi azzerata. In "
                       "un atleta allenato un CV in collasso non e' stabilita' ma uno dei marcatori "
                       "di overreaching non funzionale (Plews 2013): non aumentare il carico "
                       "questa settimana anche se la banda e' verde.")
    elif pct >= -10:
        banda, azione = "verde", "Procedi con la seduta pianificata (media 7gg entro -10% dalla baseline)."
    elif pct > -20:
        banda, azione = "giallo", "Riduci intensita'/volume o converti la qualita' in Z1-Z2 (media 7gg fra 10 e 20% sotto baseline)."
    else:
        banda, azione = "rosso", "Recupero attivo o riposo, specie se persistente (media 7gg oltre 20% sotto baseline)."

    # FC a riposo come conferma (rolling 7gg vs baseline)
    rhr_block = None
    if len(rhr) >= 7:
        rhr_vals = _finestra(rhr, ultimo_dt, 7) or [v for _, v, _ in rhr[-7:]]
        rhr_r7 = sum(rhr_vals) / len(rhr_vals)
        rhr_pool = [v for d_, v, conf in rhr
                    if not conf and _dt(d_) is not None and _dt(d_) < inizio_roll] \
                   or [v for _, v, _ in rhr]
        rhr_base, _ = _bio_media_sd(rhr_pool)
        rhr_delta = round(rhr_r7 - rhr_base, 1) if rhr_base else None
        rhr_block = {"baseline": round(rhr_base, 1) if rhr_base else None,
                     "rolling7": round(rhr_r7, 1),
                     "delta": rhr_delta,
                     "allarme": bool(rhr_delta is not None and rhr_delta >= 5)}

    out.update({"ok": True, "baseline_hrv": base_out, "rolling7_hrv": r7_out,
                "cv_pct": cv, "pct_vs_baseline": pct_out, "persistenza_gg_sotto": persist,
                "z_ln": z, "sd_ln": round(sd_ln, 3), "normal_range_ms": (lo_out, nr_hi),
                "cv_collassato": cv_collassato, "sd_clampata": sd_clampata,
                "sd_ln_grezza": round(sd_grezza, 3) if sd_grezza else None,
                "banda": banda, "azione": azione, "fc_riposo": rhr_block,
                "direzione_7v7": direzione, "delta_7v7_pct": d77,
                "gg_ritardo_oura": gg_ritardo, "n_giorni_finestra7": len(roll7_vals),
                "contesto_fase": _contesto_fase_baseline(phase)})
    return out

def _contesto_fase_baseline(phase):
    """Aspettativa sull'HRV data la fase. ESTRATTO da calc_baseline_biometrici: e' l'unica
    parte che dipendeva dalla fase, e la fase ora si calcola DOPO la baseline (il volume
    target passa dal freno biometrico, vedi fattore_progressione). Il main chiama prima
    calc_baseline_biometrici() senza fase, poi reinietta qui il contesto: nessuna chiamata
    duplicata, nessun costo in piu'."""
    fase = (phase or {}).get("fase", "") if isinstance(phase, dict) else ""
    # BUG FIX: i nomi fase delle settimane di scarico contengono il nome del mesociclo
    # ("Scarico Base 70.3 Multisport...", "Scarico Costruzione..."), quindi il match a
    # substring del ramo sotto le classificava come FASE DI CARICO, con l'aspettativa
    # HRV rovesciata: in scarico non si attende un calo fisiologico ma un RIMBALZO.
    # Va testato per primo, prima di "Base"/"Costruzione".
    # MODIFICA (29/09/2026): "dal 4o giorno" qui e nella fase di carico sotto, allineato a
    # decisione_giornaliera: il prompt del piano non dice piu' "rimbalzo mancato" al giorno 2.
    if "Scarico" in fase:
        contesto_fase = ("Settimana di scarico: e' ATTESO un rimbalzo della media HRV verso/oltre la "
                         "baseline. Se dal 4o giorno non arriva, il carico delle settimane precedenti non "
                         "e' stato smaltito: prolunga l'alleggerimento invece di ripartire con il carico.")
    elif any(k in fase for k in ("Taper", "Picco")):
        contesto_fase = ("Fase taper/picco: e' ATTESO un rimbalzo della media HRV verso/oltre la baseline. "
                         "Se il rimbalzo non arriva, il carico pregresso non e' ancora smaltito: allunga il "
                         "recupero prima della gara, non aggiungere intensita'.")
    elif any(k in fase for k in ("Costruzione", "Sviluppo", "Specifico", "Carico", "Base")):
        # COERENZA BANDA <-> FASE (28/08/2026): con il normal range individuale un calo del
        # 5% della media 7gg finisce in banda GIALLA, la cui azione generica e' "riduci
        # intensita'/converti in Z1-Z2". Ma in una settimana di CARICO quel calo e' la
        # risposta attesa (sovraccarico funzionale), non un segnale di allarme: applicare
        # li' l'azione generica significherebbe smontare il mesociclo proprio mentre
        # funziona. La letteratura mette il punto di decisione dopo lo scarico — conta se
        # l'HRV RIMBALZA, non se scende durante il carico (Plews & Laursen; Bosquet et al.
        # sul taper). Questa nota arriva DOPO la banda nel prompt e la qualifica.
        contesto_fase = ("Fase di carico: una banda GIALLA (calo del 5-10% della media HRV settimanale) e' la "
                         "risposta ATTESA al carico, non un allarme — tieni la seduta pianificata, semmai "
                         "accorcia il volume, e NON declassare una qualita' solo per questo. Si interviene "
                         "davvero in tre casi: banda ROSSA, FC a riposo in allarme, oppure banda gialla che "
                         "resta tale anche nella settimana di scarico (dal 4o giorno: li' la media 7gg e' "
                         "di notti di scarico). Non reagire mai a un singolo giorno.")
    else:
        contesto_fase = ""
    return contesto_fase
