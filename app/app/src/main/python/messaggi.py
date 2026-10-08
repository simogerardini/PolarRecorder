# -*- coding: utf-8 -*-
"""
MESSAGGI — codici stabili per i testi del riepilogo (motivi, avvisi, notifiche).
Cervello NoctaliX, roadmap punto 13a (lingue), 07/10/2026, impostazione approvata da Simone.

L'app traduce i messaggi nelle sue lingue usando il CODICE e i VALORI, non il testo
italiano. Il cervello continua a scrivere i suoi testi in italiano dove nascono (nessun
modulo toccato): questo catalogo li riconosce e li trasforma in
{"codice", "valori", "testo"}. La suite verifica che ogni messaggio prodotto negli scenari
di prova abbia un codice: un testo nuovo o cambiato senza aggiornare il catalogo fa
fallire i test, cosi' l'app non riceve mai un messaggio che non sa tradurre.
Ordine: dal piu' specifico al piu' generico (vince il primo che corrisponde).
"""
import re

_D = r"(?P<data>\d{4}-\d{2}-\d{2})"
_NUM = r"-?\d+(?:\.\d+)?"
_METEO = (r"caldo del " + _D + r"(?: \(tag\)|: (?P<temp>-?\d+) °C, umidita' (?P<umidita>\d+)%"
          r"(?: alle (?P<ora>\d+))?)")

CATALOGO = [
    # ── biometria e freni ─────────────────────────────────────────────────────────
    ("hrv_sotto_range_forte", rf"HRV: media 7gg oltre 1 SD sotto il normal range \((?P<z>{_NUM}|None)\)"),
    ("hrv_sotto_range", rf"HRV: media 7gg sotto il normal range \((?P<z>{_NUM}|None) SD\)"),
    ("hrv_sotto_range_persistente", r"HRV sotto il normal range da (?P<giorni>\d+)gg"),
    ("banda_rossa_persistente", r"banda rossa persistente: la curva biometrica prescrive recupero"),
    ("sonno_medio_basso", rf"sonno medio 7gg (?P<ore>{_NUM})h"),
    ("temperatura_alta", rf"deviazione temperatura \+(?P<gradi>{_NUM})°C \(possibile infiammazione\)"),
    ("readiness_bassa", rf"readiness media (?P<valore>{_NUM})"),
    ("fc_riposo_alta", rf"FC (?:a )?riposo \+(?P<bpm>{_NUM}|None) ?bpm sulla baseline"),
    ("rampa_ctl_alta", rf"rampa CTL \+(?P<rampa>{_NUM})/7gg"),
    ("tsb_basso", rf"TSB (?P<tsb>{_NUM})"),
    ("baseline_poche_notti", r"Solo (?P<giorni>\d+) giorni di HRV disponibili: servono ~14\+ giorni per una "
                             r"baseline affidabile\. Non prendere decisioni drastiche sul solo dato di oggi\."),
    ("baseline_date_illeggibili", r"Date dei dati Oura illeggibili: baseline non calcolabile\."),
    ("baseline_non_disponibile", r"baseline biometrica non disponibile"),
    ("dato_biometrico_vecchio", r"ultimo dato biometrico di (?P<giorni>\d+) giorni fa"),
    # ── settimana e forza ─────────────────────────────────────────────────────────
    ("settimana_senza_spazio", r"(?P<seduta>.+): non c'e' spazio nella settimana tipo"),
    ("forza_tolta_tempo", _D.join([r"forza del ", r" tolta: meno di 30' disponibili: nessuna scheda di forza fino a 20'"])),
    ("forza_tolta_profilo", _D.join([r"forza del ", r" tolta: nessuna scheda compatibile con attrezzatura e livello del profilo"])),
    ("companion_prevenzione", r"companion sostituita con (?P<scheda>.+) \(prevenzione (?P<zona>\w+)\)"),
    # ── tag ───────────────────────────────────────────────────────────────────────
    ("tag_malattia_riposo", rf"tag malattia del {_D}: riposo"),
    ("tag_malattia_leggero", rf"tag malattia: {_D} solo attivita' leggera"),
    ("tag_infortunio", rf"tag infortunio (?P<zona>\w+): {_D} senza (?P<discipline>.+)"),
    ("tag_viaggio", rf"tag viaggio del {_D}: niente qualita' ne' forza, max 45'"),
    ("tag_dolore_muscolare", rf"tag dolore muscolare del {_D}: niente qualita'"),
    ("tag_dolore_seduta", rf"tag dolore (?P<zona>\w+) nella seduta del (?P<data_seduta>\d{{4}}-\d{{2}}-\d{{2}}): "
                          rf"{_D} (?:senza (?P<discipline>.+?))?; companion di prevenzione"),
    ("tag_fatica_seduta", rf"tag di fatica sulla seduta del (?P<data_seduta>\d{{4}}-\d{{2}}-\d{{2}}): "
                          rf"(?P<seduta>.+) del {_D} resa aerobica"),
    ("tag_fatica", r"tag di fatica sulla seduta del (?P<data_seduta>\d{4}-\d{2}-\d{2})"),
    ("tag_sconosciuti", r"tag sconosciuti ignorati: (?P<chiavi>.+)"),
    # ── caldo ─────────────────────────────────────────────────────────────────────
    ("caldo_corsa_convertita", _METEO + r": corsa facile convertita in bici indoor (?P<minuti>\d+)' "
                                        rf"\(x(?P<fattore>{_NUM})\)"),
    ("caldo_corsa_accorciata", _METEO + r": (?:(?P<seduta>.+?) )?accorciata a (?P<minuti>\d+)' — esci nelle ore "
                                        r"piu' fresche, idratazione ed elettroliti"),
    # ── DETP ──────────────────────────────────────────────────────────────────────
    ("detp_sospeso", r"DETP: heat block sospeso, banda biometrica (?P<banda>\w+)"),
    ("detp_gara_calda", rf"DETP: (?P<n>\d+) heat block per la gara calda del {_D}"),
    ("detp_sweat_test", rf"DETP: sweat test il {_D}"),
    ("detp_heat_block", rf"DETP: heat block il {_D}"),
    ("sweat_test_programmato", rf"sweat test CORE in programma il {_D}"),
    # ── soglie e test ─────────────────────────────────────────────────────────────
    ("lthr_aggiornata", r"LTHR (?P<sport>\w+) (?P<da>\w+) -> (?P<a>\d+) aggiornata su Intervals\.icu dagli sforzi reali"),
    ("ftp_aggiornata", r"FTP bici (?P<da>\w+) -> (?P<a>\d+) W aggiornata su Intervals\.icu"),
    ("cp_aggiornata", r"CP corsa (?P<da>\w+) -> (?P<a>\d+) W aggiornata su Intervals\.icu"),
    ("test_programmato", rf"test in programma il {_D}: (?P<nome>.+)"),
    ("css_tempi_richiesti", r"test CSS svolto: inserisci nell'app i tempi dei 400 e dei 200 metri"),
    ("passo_soglia_aggiornato", rf"passo soglia corsa (?P<passo>\d+:\d{{2}}/km) dal test del {_D}, aggiornato su Intervals\.icu"),
    ("passo_soglia_da_confermare", rf"test di corsa del {_D}: passo (?P<passo>\d+:\d{{2}}/km) troppo diverso "
                                   r"dall'attuale (?P<attuale>\d+:\d{2}/km) \(oltre il 15%\): non scritto, "
                                   r"conferma tu il valore su Intervals\.icu"),
    ("passo_soglia_non_misurabile", rf"test di corsa del {_D}: passo non misurabile, passo soglia invariato"),
    ("passo_soglia_scrittura_fallita", rf"test di corsa del {_D}: scrittura del passo soglia non riuscita"),
    ("test_corsa_non_trovato", rf"test di corsa del {_D} non trovato tra le attivita': passo soglia invariato"),
    ("soglie_da_completare", r"soglie da completare su Intervals\.icu: (?P<elenco>.+)"),
    # ── profilo e sistema ─────────────────────────────────────────────────────────
    ("settimana_tipo_non_valida", r"settimana tipo non valida, uso quella predefinita: (?P<errori>.+)"),
    ("fuso_non_valido", r"fuso orario non valido \((?P<fuso>.+)\): uso Europe/Rome"),
    ("tsb_fuori_fascia", rf"TSB previsto a domenica (?P<tsb>{_NUM}) fuori dalla fascia attesa "
                         rf"(?P<min>{_NUM})/(?P<max>{_NUM})"),
    ("sedute_non_scritte", r"(?P<n>\d+) sedute non scritte a calendario"),
    ("seduta_non_scritta", rf"{_D} — (?P<seduta>.+?): (?P<errore>.*)"),
    # ── rimodulazione del mattino ─────────────────────────────────────────────────
    ("brick_alleggerito", r"brick alleggerito: corsa di qualita' di ieri oltre il 125% del TSS pianificato"),
    ("seduta_rimossa", r"(?P<seduta>.+) rimossa — (?P<motivo>.+)"),
    ("seduta_resa_aerobica", r"(?P<seduta>.+) convertita in aerobica — (?P<motivo>.+)"),
]
# notifiche (testi lunghi, multiriga): riconosciute dall'inizio
NOTIFICHE = [
    ("piano_rimodulato", r"🗓️ Piano settimanale — rimodulazione del " + _D + r", banda (?P<banda>\w+):"),
    ("piano_settimanale", r"🗓️ Piano settimanale"),
]
_COMPILATI = [(c, re.compile(r"^" + p + r"$", re.S)) for c, p in CATALOGO]
_NOTIFICHE = [(c, re.compile(r"^" + p, re.S)) for c, p in NOTIFICHE]
NON_CODIFICATO = "non_codificato"


def codifica(testo, tipo="motivo"):
    """{"tipo", "codice", "valori", "testo"}. Testi uniti da "; " (rimodulazione del
    mattino) -> codice "multiplo" con i messaggi in "valori.messaggi"."""
    t = (testo or "").strip()
    if tipo == "avviso" and t.startswith("tag: "):
        t = t[5:]
    elenco = _NOTIFICHE + _COMPILATI if tipo == "notifica" else _COMPILATI
    for codice, rx in elenco:
        m = rx.match(t)
        if m:
            return {"tipo": tipo, "codice": codice,
                    "valori": {k: v for k, v in m.groupdict().items() if v is not None}, "testo": testo}
    if "; " in t:
        parti = [codifica(p, tipo) for p in t.split("; ")]
        if all(p["codice"] != NON_CODIFICATO for p in parti):
            return {"tipo": tipo, "codice": "multiplo", "valori": {"messaggi": parti}, "testo": testo}
    return {"tipo": tipo, "codice": NON_CODIFICATO, "valori": {}, "testo": testo}


def messaggi_riepilogo(rie, notifiche=()):
    """Lista "messaggi" del riepilogo: motivi, righe degli avvisi, notifiche."""
    out = [codifica(m, "motivo") for m in rie.get("motivi") or []]
    out += [codifica(r, "avviso") for r in (rie.get("avvisi") or "").splitlines() if r.strip()]
    out += [codifica(n, "notifica") for n in notifiche or []]
    return out
