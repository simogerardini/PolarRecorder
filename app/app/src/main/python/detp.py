# -*- coding: utf-8 -*-
"""
DETP — protocollo di allenamento con sensore CORE 2 (Heat Strain Index, HSI).
Cervello BioSleep, roadmap punto 11 (07/10/2026), impostazione approvata da Simone.

Dal documento "metodo_detp_per_core_2": si usa la PIANIFICAZIONE (heat block, tetto HSI
nelle qualita', target del brick e del cambio T2, idratazione, sweat test); il pacing in
tempo reale (correzioni di potenza dal vivo) resta fuori: richiede dati durante la seduta.
Regola di Simone sulla sintassi: negli step solo il target HSI, breve ("HSI<6.5",
"HSI 7.2-7.8"), all'inizio del testo, perche' l'orologio mostra poche parole.
I valori HSI sono quelli del documento: indicazioni, non soglie di letteratura verificate.
"""
import re

# Brick per distanza (tabella 3): bici, cambio T2 (massimo), corsa
BRICK_HSI = {"sprint": ("5.5-7", "7.2", "7-8.2"), "olimpico": ("5-6.2", "6.5", "6.2-7.5"),
             "70.3": ("4.2-5.4", "5.5", "5.5-6.6"), "full": ("3.2-4.4", "4.5", "4.5-5.5")}
QUALITA_TETTO, QUALITA_RIPARTENZA = "6.5", "5"     # sezione 4.2
HEAT_STOP = "8.2"
TAG_STOP = ("malattia", "sonno_disturbato", "alcol", "viaggio")


def attivo(profilo):
    p = profilo or {}
    return bool(p.get("core2")) and bool(p.get("detp"))


def _prefissa(riga, nota):
    """"- 8m Z2 HR intensity=..." -> "- HSI<5 8m Z2 HR intensity=..." (una sola nota HSI)."""
    if not riga.startswith("- ") or "HSI" in riga:
        return riga
    if riga.startswith("- Press lap "):
        return riga.replace("- Press lap ", f"- Press lap {nota} ", 1)
    return f"- {nota} {riga[2:]}"


def annota(evento, key, qualita, distanza):
    """Note HSI sugli step dell'evento (copia). Qualita' bici/corsa: tetto sugli step attivi
    e soglia di ripartenza sui recuperi; brick: target per frazione e massimo al cambio T2."""
    tipo = evento.get("type")
    righe = (evento.get("description") or "").splitlines()
    out = []
    bici, t2, corsa = BRICK_HSI.get(distanza) or BRICK_HSI["olimpico"]
    for r in righe:
        nota = None
        if key in ("bici_chiave", "corsa_chiave") and qualita:
            # i recuperi della libreria sono a volte "intensity=active (recupero attivo)"
            if "intensity=recovery" in r or "intensity=rest" in r or "recupero" in r.lower():
                nota = f"HSI<{QUALITA_RIPARTENZA}"
            elif "intensity=active" in r:
                nota = f"HSI<{QUALITA_TETTO}"
        elif key in ("brick_bici", "brick_corsa"):
            if tipo == "Transition":
                nota = f"HSI<{t2}"
            elif "intensity=active" in r:
                nota = f"HSI {bici}" if tipo == "Ride" else f"HSI {corsa}"
        out.append(_prefissa(r, nota) if nota else r)
    return dict(evento, description="\n".join(out) + ("\n" if righe else ""))


def nota_idratazione(sweat=None):
    """Una riga di descrizione (non uno step) per lunghi e brick."""
    if sweat and sweat.get("litri_h"):
        ml = round(sweat["litri_h"] * 750 / 50) * 50
        sod = f", sodio ~{round(sweat['litri_h'] * sweat['sodio_mg_l'] / 50) * 50} mg/h" \
            if sweat.get("sodio_mg_l") else ""
        return f"Liquidi ~{ml} ml/h{sod} (sweat test). Oltre HSI 5.5: solo liquidi."
    return "Liquidi 600-800 ml/h, sodio 600-900 mg/h (HSI 3.6-5.5). Oltre HSI 5.5: solo liquidi."


def descrizione_heat(minuti=65):
    """Heat block (sezione 4.1): bici indoor vestito, plateau HSI 7.2-7.8 a potenza bassa."""
    plateau = max(20, minuti - 25)
    return ("[[tipo:BikeCross]]\n"
            f"Heat block CORE: stop se HSI {HEAT_STOP} o capogiri, nausea, brividi.\n"
            "- HSI 7 15m Z2 HR intensity=warmup\n"
            f"- HSI 7.2-7.8 {plateau}m Z1 HR intensity=active\n"
            "- 10m Z1 HR intensity=cooldown\n")


def descrizione_sweat_test():
    """Sweat test (sezione 6): 60' bici indoor, fase normotermica e fase ipertermica."""
    return ("[[tipo:BikeCross]]\n"
            "Sweat test: pesati nudo prima e dopo, pesa la borraccia, non urinare.\n"
            "- HSI<4.5 30m Z2 HR intensity=active\n"
            "- HSI 6.5-7.5 30m Z2 HR intensity=active\n")


def sweat_rate(p1, p2, b1, b2, urine, minuti):
    """Litri/ora: ((P1-P2) + (B1-B2) - U) / T. None se i valori non sono plausibili."""
    try:
        t = float(minuti) / 60
        sr = ((float(p1) - float(p2)) + (float(b1) - float(b2)) - float(urine or 0)) / t
    except (TypeError, ValueError, ZeroDivisionError):
        return None
    # BUG FIX (07/10/2026, trovato dalla suite test_detp): si arrotonda PRIMA del confronto:
    # 70.0-69.9 + 0.5-0.4 = 0.19999... in virgola mobile e il limite 0.2 veniva rifiutato.
    sr = round(sr, 2)
    return sr if 0.2 <= sr <= 4.0 and t >= 0.5 else None


def gara_calda(gara):
    return bool(re.search(r"^caldo:\s*si\b", (gara or {}).get("desc") or "", re.M | re.I))
