# -*- coding: utf-8 -*-
"""
CAMPI WELLNESS — l'UNICO elenco dei campi personalizzati che l'app scrive su Intervals.icu.
08/10/2026 (rinomina NoctaliX, decisione di Simone: migrare i codici prima del lancio).

- code: identificatore del dato su Intervals.icu. CamelCase (regola di Intervals.icu: due
  campi con lo stesso codice sono lo stesso campo; non cambiarlo una volta in uso).
  Prefisso tecnico "Noctalix" (minuscolo dopo la N: e' un identificatore, non il marchio).
- costante: nome della costante Kotlin generata (strumenti/genera_campi_kotlin.py).
- nome: etichetta visibile, preceduta da marchio.NOME_APP ("NoctaliX rMSSD").
- vecchio: codice BioSleep* da cui lo copia strumenti/migra_campi_wellness.py (una tantum).
Nessun altro file del cervello scrive questi codici a mano: c'e' un test.
"""
CAMPI = [
    {"chiave": "rmssd",       "code": "NoctalixRMSSD",      "costante": "F_RMSSD",       "vecchio": "BioSleepRMSSD",      "nome": "rMSSD",       "units": "ms",  "desc": "rMSSD media delle finestre di 5' valide nel sonno"},
    {"chiave": "sdnn",        "code": "NoctalixSDNN",       "costante": "F_SDNN",        "vecchio": "BioSleepSDNN",       "nome": "SDNN",        "units": "ms",  "desc": "SDNN della notte"},
    {"chiave": "rhr",         "code": "NoctalixRHR",        "costante": "F_RHR",         "vecchio": "BioSleepRHR",        "nome": "RHR",         "units": "bpm", "desc": "FC a riposo: media dei 5' piu' bassi"},
    {"chiave": "min_hr",      "code": "NoctalixMinHR",      "costante": "F_MIN_HR",      "vecchio": "BioSleepMinHR",      "nome": "Min HR",      "units": "bpm", "desc": "FC minima (1o percentile)"},
    {"chiave": "avg_hr",      "code": "NoctalixAvgHR",      "costante": "F_AVG_HR",      "vecchio": "BioSleepAvgHR",      "nome": "Avg HR",      "units": "bpm", "desc": "FC media notturna"},
    {"chiave": "hours",       "code": "NoctalixHours",      "costante": "F_HOURS",       "vecchio": "BioSleepHours",      "nome": "Recording h", "units": "h",   "desc": "durata della registrazione"},
    {"chiave": "sleep_hours", "code": "NoctalixSleepHours", "costante": "F_SLEEP_HOURS", "vecchio": "BioSleepSleepHours", "nome": "Sleep h",     "units": "h",   "desc": "sonno totale"},
    {"chiave": "deep_min",    "code": "NoctalixDeepMin",    "costante": "F_DEEP_MIN",    "vecchio": "BioSleepDeepMin",    "nome": "Deep min",    "units": "min", "desc": "sonno profondo"},
    {"chiave": "rem_min",     "code": "NoctalixREMMin",     "costante": "F_REM_MIN",     "vecchio": "BioSleepREMMin",     "nome": "REM min",     "units": "min", "desc": "sonno REM"},
    {"chiave": "light_min",   "code": "NoctalixLightMin",   "costante": "F_LIGHT_MIN",   "vecchio": "BioSleepLightMin",   "nome": "Light min",   "units": "min", "desc": "sonno leggero"},
    {"chiave": "awake_min",   "code": "NoctalixAwakeMin",   "costante": "F_AWAKE_MIN",   "vecchio": "BioSleepAwakeMin",   "nome": "Awake min",   "units": "min", "desc": "veglia durante la notte"},
    {"chiave": "quality",     "code": "NoctalixQuality",    "costante": "F_QUALITY",     "vecchio": "BioSleepQuality",    "nome": "Quality",     "units": "%",   "desc": "copertura della registrazione con battiti validi"},
]
CODICE = {c["chiave"]: c["code"] for c in CAMPI}          # chiave -> codice
VECCHI = {c["code"]: c["vecchio"] for c in CAMPI}         # codice nuovo -> codice BioSleep*
