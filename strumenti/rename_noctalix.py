#!/usr/bin/env python3
"""Passaggio BioSleep -> NoctaliX, automatico e verificabile.

Dalla cartella principale del repository, sul branch rename-noctalix:
    python3 rename_noctalix.py A              # anteprima dei testi visibili (non scrive nulla)
    python3 rename_noctalix.py A --applica    # applica
    python3 rename_noctalix.py C              # anteprima del nuovo applicationId
    python3 rename_noctalix.py C --applica
    python3 rename_noctalix.py verifica       # cosa resta (deve restare solo B e C "mantenere")

Regole (approvate):
  A  testo visibile: il nome viene da UNA fonte, app/build.gradle.kts (val nomeApp), che genera
     @string/app_name e BuildConfig.APP_NAME; in Python dalla costante NOME_APP di marchio.py.
     I messaggi "BioSleep: ..." perdono il prefisso.
  C  si cambia solo l'applicationId; DB, SharedPreferences, campi Intervals.icu, WorkManager,
     canali, Keystore, URL del Worker e formato dei backup restano.
  B  (package, classi, tag di log...) non si tocca qui: si fa insieme al namespace.
Lo script lavora sulle righe che trova ADESSO nel repository (git grep), non su numeri di riga
salvati: funziona anche se nel frattempo le altre chat hanno cambiato i file.
"""
from __future__ import annotations  # annotazioni "str | None" anche con il Python 3.9 di macOS

import difflib
import re
import subprocess
import sys
from pathlib import Path

NOME = "NoctaliX"
NUOVO_ID = "com.noctalix.app"
VECCHIO_ID = "it.biosleep.recorder"
PKG_BUILDCONFIG = "com.wboelens.polarrecorder"
GRADLE = Path("app/app/build.gradle.kts")
STRINGS = Path("app/app/src/main/res/values/strings.xml")
PY_DIR = Path("app/app/src/main/python")
MARCHIO = PY_DIR / "marchio.py"

# Nome come parola a se': non tocca i codici dei campi (BioSleepRMSSD...) ne' le classi.
NOME_VECCHIO = re.compile(r"BioSleep(?![A-Za-z_])")
PREFISSO_LOG = re.compile(r'logState\.addLog\w*\(\s*"BioSleep: ')
CAMPI = re.compile(r"BioSleep(RMSSD|SDNN|RHR|MinHR|AvgHR|Hours|SleepHours|DeepMin|REMMin|LightMin|AwakeMin|Quality)")
DA_NON_TOCCARE = [  # righe con identificatori salvati o esterni (C) o diagnostica (B)
    re.compile(p)
    for p in [
        r"TAG\s*=", r"Log\.[diwe]\(", r"getSharedPreferences", r"PREFS", r"\.db\b", r"workers\.dev",
        r'"biosleep-', r"ALIAS", r"CHANNEL", r"newWakeLock", r"getResource\(", r"error\(\"Manca",
        r"^\s*(//|\*|/\*|#)", r'"""', r"^\s*(package|import)\b", r"FORMATO", r"\.biosleep",
    ]
]


def git_grep() -> list[tuple[str, int, str]]:
    out = subprocess.run(
        ["git", "grep", "-n", "-I", "-E", r"BioSleep|biosleep_notti_|" + re.escape(VECCHIO_ID)],
        capture_output=True, text=True, check=False).stdout
    righe = []
    for r in out.splitlines():
        f, n, t = r.split(":", 2)
        righe.append((f, int(n), t))
    return righe


def sostituisci_nelle_stringhe(riga: str, nuovo: str) -> str:
    """Sostituisce il nome solo dentro le stringhe tra virgolette doppie (rispetta gli escape)."""
    out, i, dentro = [], 0, False
    while i < len(riga):
        c = riga[i]
        if c == "\\" and dentro:
            out.append(riga[i:i + 2]); i += 2; continue
        if c == '"':
            dentro = not dentro; out.append(c); i += 1; continue
        if dentro:
            m = NOME_VECCHIO.match(riga, i)
            if m and not CAMPI.match(riga, i):
                out.append(nuovo); i = m.end(); continue
        out.append(c); i += 1
    return "".join(out)


def riga_kotlin(t: str) -> str | None:
    if any(p.search(t) for p in DA_NON_TOCCARE):
        return None
    if PREFISSO_LOG.search(t):
        return t.replace('"BioSleep: ', '"', 1)
    if "biosleep_notti_" in t:
        return t.replace("biosleep_notti_", "noctalix_notti_")
    nuova = sostituisci_nelle_stringhe(t, "${BuildConfig.APP_NAME}")
    # una stringa che contiene solo il nome diventa la costante stessa
    nuova = nuova.replace('"${BuildConfig.APP_NAME}"', "BuildConfig.APP_NAME")
    return nuova if nuova != t else None


def riga_python(t: str) -> str | None:
    if any(p.search(t) for p in DA_NON_TOCCARE) or CAMPI.search(t):
        return None
    # solo stringhe semplici su una riga: "..." o f"..."
    def sost(m: re.Match) -> str:
        pref, corpo = m.group(1), m.group(2)
        if not NOME_VECCHIO.search(corpo):
            return m.group(0)
        corpo = NOME_VECCHIO.sub("{NOME_APP}", corpo)
        return (pref if "f" in pref.lower() else "f" + pref) + '"' + corpo + '"'
    nuova = re.sub(r'((?<![A-Za-z0-9_])[fFrR]{0,2})"((?:[^"\\]|\\.)*)"', sost, t)
    return nuova if nuova != t else None


def aggiungi_import(testo: str, file: str) -> str:
    if file.endswith(".kt") and "BuildConfig.APP_NAME" in testo:
        imp = f"import {PKG_BUILDCONFIG}.BuildConfig"
        if imp not in testo and not re.search(rf"^package {re.escape(PKG_BUILDCONFIG)}$", testo, re.M):
            if re.search(r"^import ", testo, re.M):
                testo = re.sub(r"^(import .*)$", imp + r"\n\1", testo, count=1, flags=re.M)
            else:  # file senza import: subito dopo la riga package
                testo = re.sub(r"^(package .*)$", r"\1\n\n" + imp, testo, count=1, flags=re.M)
    if file.endswith(".py") and "{NOME_APP}" in testo and "from marchio import NOME_APP" not in testo:
        righe = testo.split("\n")
        # PRIMA del primo import (mai dentro un import su piu' righe), ma dopo "from __future__"
        primo = next(
            (i for i, r in enumerate(righe) if re.match(r"^(import|from) \w", r) and not r.startswith("from __future__")),
            len(righe))
        righe.insert(primo, "from marchio import NOME_APP")
        testo = "\n".join(righe)
    return testo


def piano_A() -> dict[str, str]:
    """file -> nuovo contenuto."""
    nuovi: dict[str, list[str]] = {}
    for f, n, t in git_grep():
        if "/src/test/" in f or f.endswith(("rename_noctalix.py", "inventario_rename.py")):
            continue  # i test non mostrano nulla all'utente
        if f.endswith(".kt"):
            nuova = riga_kotlin(t)
        elif f.endswith(".py"):
            nuova = riga_python(t)
        elif f.endswith(".md"):
            nuova = NOME_VECCHIO.sub(NOME, t) if not re.search(r"workers\.dev|github\.com", t) else None
        else:
            nuova = None
        if nuova is None:
            continue
        righe = nuovi.setdefault(f, Path(f).read_text(encoding="utf-8").split("\n"))
        assert righe[n - 1] == t, f"{f}:{n} cambiato durante la lettura"
        righe[n - 1] = nuova
    piano = {f: aggiungi_import("\n".join(r), f) for f, r in nuovi.items()}

    # Fonte unica del nome
    g = GRADLE.read_text(encoding="utf-8")
    if "val nomeApp" not in g:
        g = re.sub(
            r'(\n(\s*)versionName = .*\n)',
            r"\1\n\2// Nome dell'app: UNICA fonte. Genera @string/app_name e BuildConfig.APP_NAME.\n"
            rf'\2val nomeApp = "{NOME}"\n'
            r'\2resValue("string", "app_name", nomeApp)\n'
            r'\2buildConfigField("String", "APP_NAME", "\\"$nomeApp\\"")\n',
            g, count=1)
        piano[str(GRADLE)] = g
    s = STRINGS.read_text(encoding="utf-8")
    if 'name="app_name"' in s:
        piano[str(STRINGS)] = re.sub(r'\s*<string name="app_name">[^<]*</string>', "", s, count=1)
    if any(f.endswith(".py") for f in piano) and not MARCHIO.exists():
        piano[str(MARCHIO)] = (
            '"""Nome dell\'app per il codice Python: unica costante (stessa di build.gradle.kts)."""\n'
            f'NOME_APP = "{NOME}"\n')
    return piano


def piano_C() -> dict[str, str]:
    g = GRADLE.read_text(encoding="utf-8")
    nuovo = g.replace(f'applicationId = "{VECCHIO_ID}"', f'applicationId = "{NUOVO_ID}"')
    return {str(GRADLE): nuovo} if nuovo != g else {}


def mostra_e_applica(piano: dict[str, str], applica: bool) -> None:
    for f, nuovo in sorted(piano.items()):
        p = Path(f)
        vecchio = p.read_text(encoding="utf-8") if p.exists() else ""
        sys.stdout.writelines(difflib.unified_diff(
            vecchio.splitlines(True), nuovo.splitlines(True), f"a/{f}", f"b/{f}", n=0))
        if applica:
            p.write_text(nuovo, encoding="utf-8")
    print(f"\n{len(piano)} file {'modificati' if applica else 'da modificare (anteprima: aggiungi --applica)'}")


def verifica() -> None:
    # i test e gli strumenti della rinomina contengono il vecchio nome di proposito (dati, regole)
    restano = [(f, n, t) for f, n, t in git_grep()
               if "/src/test/" not in f and not f.endswith(("rename_noctalix.py", "inventario_rename.py"))]
    visibili = [(f, n, t) for f, n, t in restano
                if (f.endswith(".kt") and riga_kotlin(t)) or (f.endswith(".py") and riga_python(t))]
    print(f"Righe con il vecchio nome: {len(restano)} (B e C 'mantenere' sono attese)")
    print(f"Testi visibili ancora da rinominare: {len(visibili)}")
    for f, n, t in visibili:
        print(f"  {f}:{n}  {t.strip()[:120]}")
    if any(VECCHIO_ID in t for f, _, t in restano if f.endswith("build.gradle.kts")):
        print("applicationId: ancora it.biosleep.recorder")


if __name__ == "__main__":
    fase = sys.argv[1] if len(sys.argv) > 1 else ""
    if fase == "A":
        mostra_e_applica(piano_A(), "--applica" in sys.argv)
    elif fase == "C":
        mostra_e_applica(piano_C(), "--applica" in sys.argv)
    elif fase == "verifica":
        verifica()
    else:
        print(__doc__)
