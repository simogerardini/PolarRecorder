# -*- coding: utf-8 -*-
"""
MODELLO PERSONALE DELLA RISPOSTA nel cervello (19/10/2026, decisione di Simone).
Stimato una volta a settimana sullo storico (365 giorni di wellness): scarto di ln(rMSSD) della
notte dalla media dei 28 giorni precedenti in funzione del carico dei 3 giorni prima (TSS
ricostruito dalla ATL) e dei tag confondenti. Sull'account di Simone (19/10/2026, 256 notti):
-12.9% per 100 TSS il giorno dopo, nessun effetto distinguibile a 2-3 giorni, ferie -17%.
Uso: SOLO spiegazione ("stanotte -9%: atteso -7% ±6% dal carico di ieri") e avviso quando la
notte e' piu' bassa di quanto carico e tag spiegano. La banda non cambia: il calo dovuto al
carico e' proprio cio' che la banda deve vedere per frenare.
Si spegne da solo se l'effetto del carico di ieri non e' distinguibile da zero o se cambia
troppo tra le due meta' dello storico.
"""
import math
from datetime import date, timedelta

import campi
import degradato

FINESTRA = 28
NOTTI_MIN = 60
NOCTALIX_MIN = 60            # con almeno 60 notti NoctaliX il modello si stima su NoctaliX
RICALCOLO_GG = 7


def _risolvi(A, b):
    n = len(A)
    M = [list(A[i]) + [b[i]] for i in range(n)]
    for c in range(n):
        p = max(range(c, n), key=lambda r: abs(M[r][c]))
        if abs(M[p][c]) < 1e-12:
            return None
        M[c], M[p] = M[p], M[c]
        for r in range(n):
            if r != c:
                f = M[r][c] / M[c][c]
                M[r] = [x - f * y for x, y in zip(M[r], M[c])]
    return [M[i][n] / M[i][i] for i in range(n)]


def _ols(X, y):
    k, n = len(X[0]), len(y)
    if n <= k + 5:
        return None
    XtX = [[sum(X[r][i] * X[r][j] for r in range(n)) for j in range(k)] for i in range(k)]
    beta = _risolvi(XtX, [sum(X[r][i] * y[r] for r in range(n)) for i in range(k)])
    if beta is None:
        return None
    res = [y[r] - sum(beta[i] * X[r][i] for i in range(k)) for r in range(n)]
    s2 = sum(e * e for e in res) / (n - k)
    inv = [_risolvi(XtX, [1.0 if i == j else 0.0 for i in range(k)]) for j in range(k)]
    if any(c is None for c in inv):
        return None
    se = [math.sqrt(max(inv[i][i] * s2, 0)) for i in range(k)]
    return beta, se, math.sqrt(s2)


def _serie(wellness, fonte):
    out = {}
    for w in wellness or []:
        d = str(w.get("id") or "")[:10]
        if fonte == "noctalix":
            v, q = w.get(campi.CODICE["rmssd"]), w.get(campi.CODICE["quality"])
            if isinstance(q, (int, float)) and q < 80:
                v = None
        else:
            v = w.get("hrv")
        if d and isinstance(v, (int, float)) and v > 0:
            out[d] = math.log(v)
    return out


def _scarto(serie, d):
    g = date.fromisoformat(d)
    prima = [v for x, v in serie.items() if g - timedelta(days=FINESTRA) <= date.fromisoformat(x) < g]
    return (serie[d] - sum(prima) / len(prima)) if d in serie and len(prima) >= 14 else None


def _x(carico, d, confondenti):
    g = date.fromisoformat(d)
    cc = [carico.get((g - timedelta(days=k)).isoformat()) for k in (1, 2, 3)]
    if any(c is None for c in cc):
        return None
    return [1.0] + [c / 100 for c in cc] + [1.0 if d in (confondenti or ()) else 0.0]


def stima(wellness, oggi_str, confondenti=None):
    """{"attivo", "fonte", "coeff": {costante, carico_1..3, confondente}, "se", "sd_residuo",
    "notti", "motivo"}. Fonte: NoctaliX con >= 60 notti valide, altrimenti i campi standard."""
    nx = _serie(wellness, "noctalix")
    fonte = "noctalix" if len(nx) >= NOCTALIX_MIN else "standard"
    serie = nx if fonte == "noctalix" else _serie(wellness, "standard")
    carico = degradato.carico_da_wellness(sorted(wellness or [], key=lambda w: str(w.get("id"))))
    righe = []
    for d in sorted(serie):
        if d >= oggi_str:
            continue
        y, x = _scarto(serie, d), _x(carico, d, confondenti)
        if y is not None and x is not None:
            righe.append((x, y))
    base = {"attivo": False, "fonte": fonte, "notti": len(righe), "data": oggi_str}
    if len(righe) < NOTTI_MIN:
        return dict(base, motivo="poche_notti")
    usa_conf = any(x[4] for x, _ in righe)
    col = [0, 1, 2, 3] + ([4] if usa_conf else [])
    fit = _ols([[x[i] for i in col] for x, _ in righe], [y for _, y in righe])
    if not fit:
        return dict(base, motivo="dati_collineari")
    beta, se, sd = fit
    nomi = ["costante", "carico_1", "carico_2", "carico_3", "confondente"]
    coeff = {nomi[i]: b for i, b in zip(col, beta)}
    errori = {nomi[i]: s for i, s in zip(col, se)}
    b1, s1 = coeff["carico_1"], errori["carico_1"]
    if not (b1 + 2 * s1 < 0):
        return dict(base, coeff=coeff, se=errori, sd_residuo=sd, motivo="effetto_non_distinguibile")
    meta = len(righe) // 2
    parti = []
    for pezzo in (righe[:meta], righe[meta:]):
        c_p = [0, 1, 2, 3] + ([4] if any(x[4] for x, _ in pezzo) else [])
        f = _ols([[x[i] for i in c_p] for x, _ in pezzo], [y for _, y in pezzo])
        parti.append(f[0][1] if f else None)
    if None in parti or parti[0] * parti[1] <= 0 or abs(parti[0] - parti[1]) > max(0.02, 0.5 * max(map(abs, parti))):
        return dict(base, coeff=coeff, se=errori, sd_residuo=sd, motivo="instabile")
    return dict(base, attivo=True, coeff=coeff, se=errori, sd_residuo=sd, motivo=None)


def modello_aggiornato(stato, leggi_wellness, oggi_str, confondenti=None):
    """Modello in cache nello stato, ristimato ogni 7 giorni (leggi_wellness solo allora)."""
    m = stato.get("modello")
    if m and m.get("data") and (date.fromisoformat(oggi_str) - date.fromisoformat(m["data"])).days < RICALCOLO_GG:
        return m
    m = stima(leggi_wellness(), oggi_str, confondenti)
    stato["modello"] = m
    return m


def notte_attesa(wellness, oggi_str, m, confondenti=None):
    """{"reale_pct", "atteso_pct", "intervallo_pct", "carico_ieri", "confondente", "fonte"} per la
    notte di oggi (scarto dalla media dei 28 giorni precedenti, serie della fonte del modello)."""
    if not m or not m.get("attivo"):
        return None
    serie = _serie(wellness, m["fonte"])
    reale = _scarto(serie, oggi_str)
    carico = degradato.carico_da_wellness(sorted(wellness or [], key=lambda w: str(w.get("id"))))
    x = _x(carico, oggi_str, confondenti)
    if reale is None or x is None:
        return None
    c = m["coeff"]
    atteso = c["costante"] + c["carico_1"] * x[1] + c["carico_2"] * x[2] + c["carico_3"] * x[3] + \
        c.get("confondente", 0.0) * x[4]
    margine = 2 * m["sd_residuo"]
    return {"reale_pct": round(math.expm1(reale) * 100, 1), "atteso_pct": round(math.expm1(atteso) * 100, 1),
            "intervallo_pct": round(math.expm1(margine) * 100, 1), "carico_ieri": round(x[1] * 100),
            "confondente": bool(x[4]), "fonte": m["fonte"],
            # con un tag confondente sulla notte il calo e' gia' spiegato dal tag (anche se lo
            # storico non ha abbastanza notti con tag per stimarne l'effetto): nessun avviso
            "sotto": reale < atteso - margine and not x[4]}


def avviso_calo(n):
    if not n or not n.get("sotto"):
        return None
    return (f"calo non spiegato: HRV di stanotte {n['reale_pct']}% contro {n['atteso_pct']}% atteso "
            f"da carico e tag (±{n['intervallo_pct']}%)")
