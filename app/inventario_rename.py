#!/usr/bin/env python3
"""Inventario per il passaggio BioSleep/NightForm -> NoctaliX. NON modifica nulla.

Uso, dalla cartella principale del repository:
    git switch -c rename-noctalix        # (solo la prima volta)
    python3 inventario_rename.py > inventario.tsv
    python3 inventario_rename.py --riepilogo

Classifica ogni riga di `git grep -n -i -E "bio[ _-]?sleep|night[ _-]?form"`:
  A = testo visibile all'utente
  B = identificatore interno non salvato (package, classi, variabili, tag di log, commenti)
  C = identificatore SALVATO o ESTERNO (DB, SharedPreferences, WorkManager, canali, campi
      Intervals.icu, file salvati, URL, assetlinks, applicationId, Keystore)
La classificazione e' automatica: va riletta, i casi dubbi sono marcati con '?'.
"""
import re
import subprocess
import sys

PATTERN = r"bio[ _-]?sleep|night[ _-]?form"

C_REGOLE = [
    (r"\.db\b|SQLiteOpenHelper|databaseBuilder", "database"),
    (r"getSharedPreferences|PREFS|_PREFS|KEY_\w+\s*=", "SharedPreferences"),
    (r"enqueueUnique|uniqueWork|\"biosleep-|'biosleep-|WorkManager", "WorkManager"),
    (r"NotificationChannel|CHANNEL", "canale notifica"),
    (r"\"BioSleep(RMSSD|SDNN|RHR|MinHR|AvgHR|Hours|SleepHours|DeepMin|REMMin|LightMin|AwakeMin|Quality)\"|F_[A-Z_]+\s*=", "campo wellness Intervals.icu"),
    (r"https?://|workers\.dev|assetlinks|OAUTH_|redirect", "URL / OAuth"),
    (r"applicationId|package_name|it\.biosleep\.recorder", "applicationId / package"),
    (r"KeyStore|ALIAS|keystore", "chiave Keystore"),
    (r"\.(log|json|txt|csv|fit|zip)\b|filesDir|files/", "file salvato"),
    (r"external_id|externalId", "external_id"),
]


def classifica(file: str, testo: str) -> tuple[str, str]:
    t = testo.strip()
    for regola, motivo in C_REGOLE:
        if re.search(regola, t, re.IGNORECASE):
            return "C", motivo
    if file.endswith(".xml") and "/res/values" in file and "<string" in t:
        return "A", "stringa risorsa"
    if re.match(r"^(//|\*|/\*|#|<!--)", t):
        return "B", "commento"
    if re.match(r"^(package|import)\b", t):
        return "B", "package / import"
    if re.search(r"Log\.[diwe]\(\s*\"|TAG\s*=", t):
        return "B?", "tag di log (lo usano gli script adb: aggiornarli insieme)"
    # stringa con BioSleep e spazi/lettere attorno: probabilmente testo per l'utente
    for s in re.findall(r"\"([^\"]*)\"", t):
        if re.search(PATTERN, s, re.IGNORECASE) and (" " in s or s[:1].isupper()):
            return "A?", "stringa nel codice (verificare se e' mostrata)"
    if file.endswith((".md", ".txt", ".html")):
        return "A?", "documentazione"
    return "B", "identificatore"


def main() -> None:
    out = subprocess.run(
        ["git", "grep", "-n", "-I", "-i", "-E", PATTERN], capture_output=True, text=True, check=False
    ).stdout.splitlines()
    righe = []
    for r in out:
        file, linea, testo = r.split(":", 2)
        cat, motivo = classifica(file, testo)
        righe.append((cat, motivo, file, linea, testo.strip()[:160]))
    if "--riepilogo" in sys.argv:
        from collections import Counter
        for (cat, motivo), n in sorted(Counter((c, m) for c, m, *_ in righe).items()):
            print(f"{cat:3} {n:5}  {motivo}")
        print(f"\nTotale righe: {len(righe)}")
        return
    print("cat\tmotivo\tfile\triga\ttesto")
    for r in sorted(righe):
        print("\t".join(r))


if __name__ == "__main__":
    main()
