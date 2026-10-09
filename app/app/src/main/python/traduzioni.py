# -*- coding: utf-8 -*-
"""
TRADUZIONI — nomi e note delle sedute sul calendario (Intervals.icu e orologio).
Roadmap 13b (08/10/2026), impostazione approvata da Simone.

Il cervello scrive le sedute in italiano; prima di ogni scrittura su Intervals.icu
(coach_settimanale.scrivi_evento) nome e descrizione passano da traduci_evento().
Le parti tecniche restano identiche in ogni lingua (durate, Z2 HR, % LTHR, % Pace,
Press lap, intensity=..., [[tipo:...]]): si traducono solo parole e note.
Lingue: it (nessuna modifica), en, es. zh -> en sul calendario (decisione di Simone:
molti orologi non mostrano i caratteri cinesi). Le schede di forza (WeightTraining) usano
il catalogo di traduzioni_palestra.py (13c). Regole del testo tradotto: niente apostrofi negli step (" ' "
e' letto come minuti) e niente %, come nell'italiano.
"""
import re

LINGUE_CALENDARIO = {"it": "it", "en": "en", "es": "es", "zh": "en"}

# ── righe intere (prima passata) ─────────────────────────────────────────────────
RIGHE = [
    (r"Heat block CORE: stop se HSI (\S+) o capogiri, nausea, brividi\.",
     r"Heat block CORE: stop at HSI \1 or dizziness, nausea, chills.",
     r"Bloque de calor CORE: para con HSI \1 o mareo, náusea, escalofríos."),
    (r"Liquidi ([\d~-]+) ml/h, sodio ([\d~-]+) mg/h \(HSI (\S+)\)\. Oltre HSI (\S+): solo liquidi\.",
     r"Fluids \1 ml/h, sodium \2 mg/h (HSI \3). Above HSI \4: fluids only.",
     r"Líquidos \1 ml/h, sodio \2 mg/h (HSI \3). Por encima de HSI \4: solo líquidos."),
    (r"Liquidi ~(\d+) ml/h, sodio ~(\d+) mg/h \(sweat test\)\. Oltre HSI (\S+): solo liquidi\.",
     r"Fluids ~\1 ml/h, sodium ~\2 mg/h (sweat test). Above HSI \3: fluids only.",
     r"Líquidos ~\1 ml/h, sodio ~\2 mg/h (sweat test). Por encima de HSI \3: solo líquidos."),
    (r"Liquidi ~(\d+) ml/h \(sweat test\)\. Oltre HSI (\S+): solo liquidi\.",
     r"Fluids ~\1 ml/h (sweat test). Above HSI \2: fluids only.",
     r"Líquidos ~\1 ml/h (sweat test). Por encima de HSI \2: solo líquidos."),
    (r"Sweat test: pesati nudo prima e dopo, pesa la borraccia, non urinare\.",
     r"Sweat test: weigh yourself naked before and after, weigh the bottle, do not urinate.",
     r"Sweat test: pésate desnudo antes y después, pesa el bidón, no orines."),
    (r"Transizione T(\d) del brick: (.+?)\. Obiettivo sotto i (\d+)s\. Nessun target di intensita': si cronometra e basta\.",
     r"Brick T\1 transition: \2. Target under \3s. No intensity target: just time it.",
     r"Transición T\1 del brick: \2. Objetivo por debajo de \3s. Sin objetivo de intensidad: solo cronometrar."),
    # nota della rimodulazione del mattino col caldo (coach_settimanale.rimodula_per_caldo)
    (r"caldo del (\d{4}-\d{2}-\d{2}): (-?\d+) °C, umidita' (\d+)% alle (\d+): accorciata a (\d+)' — esci nelle ore piu' fresche, idratazione ed elettroliti",
     r"heat on \1: \2 °C, humidity \3% at \4h: shortened to \5' — go out in the coolest hours, hydration and electrolytes",
     r"calor el \1: \2 °C, humedad \3% a las \4h: acortada a \5' — sal en las horas más frescas, hidratación y electrolitos"),
    (r"caldo del (\d{4}-\d{2}-\d{2}) \(tag\): accorciata a (\d+)' — esci nelle ore piu' fresche, idratazione ed elettroliti",
     r"heat on \1 (tagged): shortened to \2' — go out in the coolest hours, hydration and electrolytes",
     r"calor el \1 (etiqueta): acortada a \2' — sal en las horas más frescas, hidratación y electrolitos"),
    # protocolli e misure dei test periodici (testi fissi di soglie.TEST_ROTAZIONE)
    (r"15' riscaldamento progressivo, poi 30' a ritmo massimo sostenibile su percorso pianeggiante e regolare \(no traffico, no salite\), 10' defaticamento",
     r"15' progressive warm-up, then 30' at maximum sustainable pace on a flat, even route (no traffic, no hills), 10' cool-down",
     r"15' de calentamiento progresivo, luego 30' a ritmo máximo sostenible en un recorrido llano y regular (sin tráfico, sin subidas), 10' de vuelta a la calma"),
    (r"Misura: LTHR corsa = FC media degli ULTIMI 20' dei 30' \(Friel\); passo soglia = passo medio dei 30'\. La LTHR viene raccolta in automatico \(solo al rialzo\)",
     r"Measure: run LTHR = average HR of the LAST 20' of the 30' (Friel); threshold pace = average pace of the 30'. LTHR is collected automatically (only upward)",
     r"Medida: LTHR carrera = FC media de los ÚLTIMOS 20' de los 30' (Friel); ritmo umbral = ritmo medio de los 30'. La LTHR se recoge automáticamente (solo al alza)"),
    (r"\. Con Stryd: CP = potenza media dei 30', raccolta in automatico \(solo al rialzo\)",
     r". With Stryd: CP = average power of the 30', collected automatically (only upward)",
     r". Con Stryd: CP = potencia media de los 30', recogida automáticamente (solo al alza)"),
    (r"Su strada senza stop o sul rullo\. 20' riscaldamento con 3x1' allunghi, 5' blowout all-out, 5' facile, poi 20' al massimo sostenibile, costante, 10' defaticamento\. Con misuratore di potenza si misura anche la FTP",
     r"On a road without stops or on the trainer. 20' warm-up with 3x1' pickups, 5' all-out blowout, 5' easy, then 20' at maximum sustainable effort, steady, 10' cool-down. With a power meter FTP is measured too",
     r"En carretera sin paradas o en el rodillo. 20' de calentamiento con 3x1' progresiones, 5' blowout a tope, 5' suave, luego 20' al máximo sostenible, constante, 10' de vuelta a la calma. Con potenciómetro también se mide el FTP"),
    (r"Misura: FTP bici = 95 per cento della potenza media dei 20' \(Coggan\): e' il valore su cui si risolvono TUTTI i target  per centoPower delle uscite outdoor\. LTHR bici = FC media dei 20'\. Entrambi vengono raccolti in automatico dagli algoritmi \(solo al rialzo\); il test toglie inoltre alla FTP lo stato di stima PROVVISORIA",
     r"Measure: bike FTP = 95 percent of the average power of the 20' (Coggan): the value behind ALL power targets of outdoor rides. Bike LTHR = average HR of the 20'. Both are collected automatically by the algorithms (only upward); the test also removes the PROVISIONAL estimate status from FTP",
     r"Medida: FTP bici = 95 por ciento de la potencia media de los 20' (Coggan): es el valor de TODOS los objetivos de potencia de las salidas outdoor. LTHR bici = FC media de los 20'. Ambos se recogen automáticamente por los algoritmos (solo al alza); el test además quita al FTP el estado de estimación PROVISIONAL"),
    (r"400m riscaldamento tecnico, 400m a tutta, 5' recupero completo, 200m a tutta, 200m sciolto",
     r"400m technical warm-up, 400m all out, 5' full recovery, 200m all out, 200m easy",
     r"400m de calentamiento técnico, 400m a tope, 5' de recuperación completa, 200m a tope, 200m suave"),
    (r"Misura: CSS \(passo soglia nuoto\) = 200 / \(t400 - t200\) in metri/secondo\. Da qui si tarano tutte le ripetute nuoto",
     r"Measure: CSS (swim threshold pace) = 200 / (t400 - t200) in meters/second. All swim intervals are set from it",
     r"Medida: CSS (ritmo umbral de natación) = 200 / (t400 - t200) en metros/segundo. De aquí se ajustan todas las series de natación"),
    (r"15' riscaldamento, poi 8km su percorso FISSO \(stesso ogni volta\) a FC COSTANTE in alto Z2, senza mai spingere, 10' defaticamento",
     r"15' warm-up, then 8km on a FIXED route (same every time) at STEADY HR at the top of Z2, never pushing, 10' cool-down",
     r"15' de calentamiento, luego 8km en un recorrido FIJO (siempre el mismo) a FC CONSTANTE en la parte alta de Z2, sin apretar nunca, 10' de vuelta a la calma"),
    (r"Misura: Passo medio a pari FC e decoupling Pa:HR \(deve restare sotto il 5 per cento\)\. Se il passo migliora a FC identica, la base aerobica sta funzionando",
     r"Measure: average pace at equal HR and Pa:HR decoupling (must stay under 5 percent). If pace improves at the same HR, the aerobic base is working",
     r"Medida: ritmo medio a igual FC y desacoplamiento Pa:HR (debe quedar por debajo del 5 por ciento). Si el ritmo mejora a igual FC, la base aeróbica funciona"),
]

# ── frammenti (seconda passata: dal piu' lungo al piu' corto, a parole intere) ─────
FRAMMENTI = [
    # nomi delle sedute
    ("Attivazione pre-gara", "Pre-race activation", "Activación precarrera"),
    ("Bici chiave", "Key bike", "Bicicleta clave"),
    ("Bici continua", "Steady ride", "Bicicleta continua"),
    ("Bici sciolta", "Easy spin", "Rodaje suave"),
    ("Bici supporto", "Support ride", "Bicicleta de apoyo"),
    ("Lungo bici", "Long ride", "Fondo largo en bicicleta"),
    ("Soglia", "Threshold", "Umbral"),
    ("aerobica", "aerobic", "aeróbica"),
    ("corsa off-bike", "off-bike run", "carrera tras la bici"),
    ("Corsa di supporto", "Support run", "Carrera de apoyo"),
    ("Corsa facile a seguire", "Easy run after", "Carrera suave a continuación"),
    ("Corsa fluida", "Flowing run", "Carrera fluida"),
    ("Corsa qualità", "Quality run", "Carrera de calidad"),
    ("Heat block CORE", "Heat block CORE", "Bloque de calor CORE"),
    ("Lungo corsa aerobico", "Aerobic long run", "Tirada larga aeróbica"),
    ("Lungo corsa", "Long run", "Tirada larga"),
    ("Nuoto Fondo aerobico", "Swim aerobic endurance", "Natación fondo aeróbico"),
    ("Nuoto Fondo e ritmo gara", "Swim endurance and race pace", "Natación fondo y ritmo de carrera"),
    ("Nuoto Soglia CSS a cento lunga", "Swim CSS threshold 100s long", "Natación umbral CSS a cien larga"),
    ("Nuoto Soglia di richiamo piena", "Swim threshold reminder full", "Natación umbral de recuerdo completa"),
    ("Nuoto Tecnica drill lunga", "Swim technique drills long", "Natación técnica drills larga"),
    ("Nuoto Tecnica drill", "Swim technique drills", "Natación técnica drills"),
    ("Nuoto Velocità a cinquanta lunga", "Swim speed 50s long", "Natación velocidad a cincuenta larga"),
    ("Nuoto Velocità a cinquanta", "Swim speed 50s", "Natación velocidad a cincuenta"),
    ("Nuoto Velocità di richiamo piena", "Swim speed reminder full", "Natación velocidad de recuerdo completa"),
    ("Nuoto Velocità parti forte e tieni lunga", "Swim speed fast start and hold long",
     "Natación velocidad salida fuerte y mantén larga"),
    ("Nuoto rigenerante", "Recovery swim", "Natación regenerativa"),
    ("Richiami a ritmo gara", "Race-pace reminders", "Recordatorios a ritmo de carrera"),
    ("Time Trial corsa (LTHR + passo soglia)", "run time trial (LTHR + threshold pace)",
     "contrarreloj de carrera (LTHR + ritmo umbral)"),
    ("bici (FTP e LTHR)", "bike (FTP and LTHR)", "bicicleta (FTP y LTHR)"),
    ("Test CSS nuoto", "CSS swim test", "Test CSS natación"),
    ("Test aerobico MAF", "MAF aerobic test", "Test aeróbico MAF"),
    ("Sweat test CORE", "Sweat test CORE", "Sweat test CORE"),
    # note tra parentesi
    ("D+ specifico", "specific D+", "D+ específico"),
    ("TEST CSS: a tutta, cronometra", "CSS TEST: all out, time it", "TEST CSS: a tope, cronometra"),
    ("percorso fisso", "fixed route", "recorrido fijo"),
    ("FC costante in alto", "steady HR at the top of", "FC constante en la parte alta de"),
    ("al massimo sostenibile, regolare, percorso piano", "max sustainable, even, flat route",
     "al máximo sostenible, regular, recorrido llano"),
    ("al massimo sostenibile, costante", "max sustainable, steady", "al máximo sostenible, constante"),
    ("blowout: tutto", "blowout: all out", "blowout: a tope"),
    ("bracciata lunga, applica i drill", "long stroke, apply the drills", "brazada larga, aplica los drills"),
    ("su spinning, pre: mobilita+skip", "on the spin bike, pre: mobility and skips",
     "en spinning, pre: movilidad y skipping"),
    ("spinta leggera", "light push", "empuje ligero"),
    ("cadenza alta gambe sciolte", "high cadence loose legs", "cadencia alta piernas sueltas"),
    ("tapis gia' libero e scarpe pronte, si corre entro", "treadmill free and shoes ready, run within",
     "cinta libre y zapatillas listas, se corre en"),
    ("chiusura controllata", "controlled finish", "final controlado"),
    ("da facile a svelto dentro la vasca", "easy to fast within the length", "de suave a rápido dentro del largo"),
    ("discesa", "downhill", "bajada"),
    ("dita che sfiorano l'acqua nel recupero, gomito alto", "fingertips brushing the water on recovery, high elbow",
     "dedos que rozan el agua en el recobro, codo alto"),
    ("entra nel ritmo", "settle into rhythm", "entra en ritmo"),
    ("entrata in linea con la spalla", "entry in line with the shoulder", "entrada alineada con el hombro"),
    ("non recupero pieno", "not full recovery", "sin recuperación completa"),
    ("frequenza alta, bracciata pulita", "high rate, clean stroke", "frecuencia alta, brazada limpia"),
    ("frequenza alta, gambe tranquille", "high rate, easy kick", "frecuencia alta, piernas tranquilas"),
    ("frequenza alta, zero fatica", "high rate, zero strain", "frecuencia alta, cero fatiga"),
    ("gambe da bici, subito dopo la", "bike legs, right after", "piernas de ciclista, justo después de la"),
    ("cadenza alta e passo controllato nei primi minuti", "high cadence and controlled pace in the first minutes",
     "cadencia alta y ritmo controlado en los primeros minutos"),
    ("gambe dall'anca, caviglia morbida, corpo allineato", "kick from the hip, loose ankles, body aligned",
     "patada desde la cadera, tobillo suelto, cuerpo alineado"),
    ("giu' dallo spinning, scarpe da corsa e via sul tapis", "off the spin bike, running shoes on and onto the treadmill",
     "bájate del spinning, zapatillas de correr y a la cinta"),
    ("gomito alto nel recupero", "high elbow on recovery", "codo alto en el recobro"),
    ("gomito alto nella presa", "high elbow in the catch", "codo alto en el agarre"),
    ("idratazione/nutrizione come in gara", "hydration and fueling as in the race", "hidratación y nutrición como en carrera"),
    ("orecchio sul braccio avanti", "ear on the leading arm", "oreja sobre el brazo extendido"),
    ("passo da mezzo ironman", "half-ironman pace", "ritmo de medio ironman"),
    ("passo gara, zero fatica", "race pace, zero strain", "ritmo de carrera, cero fatiga"),
    ("post: stretching", "post: stretching", "post: estiramientos"),
    ("pre: mobilita anca/ginocchio (no skip, no balzi)", "pre: hip and knee mobility (no skips, no bounds)",
     "pre: movilidad cadera y rodilla (sin skipping, sin saltos)"),
    ("pre: mobilita anche/caviglie", "pre: hip and ankle mobility", "pre: movilidad caderas y tobillos"),
    ("pre: mobilita spalle/caviglie", "pre: shoulder and ankle mobility", "pre: movilidad hombros y tobillos"),
    ("pre: mobilita+balzi", "pre: mobility and bounds", "pre: movilidad y saltos"),
    ("pre: mobilita+skip", "pre: mobility and skips", "pre: movilidad y skipping"),
    ("allunghi", "strides", "progresiones"),
    ("progressivo", "progressive", "progresivo"),
    ("recupero al muro tutto", "full recovery at the wall", "recuperación completa en la pared"),
    ("respirazione costante", "steady breathing", "respiración constante"),
    ("rotazione dall'anca", "rotation from the hip", "rotación desde la cadera"),
    ("salita", "uphill", "subida"),
    ("sei battute di gambe sul fianco, poi cambio lato", "six kicks on the side, then switch sides",
     "seis patadas de lado, luego cambia de lado"),
    ("sighting ogni tanto, come in gara", "sighting now and then, as in the race",
     "sighting de vez en cuando, como en carrera"),
    ("solo braccia, niente palette", "arms only, no paddles", "solo brazos, sin palas"),
    ("solo braccia", "arms only", "solo brazos"),
    ("stesso passo su ogni ripetuta", "same pace on every rep", "mismo ritmo en cada repetición"),
    ("surge libero", "free surge", "surge libre"),
    ("svelto e controllato", "quick and controlled", "rápido y controlado"),
    ("ignora la FC, vai a sensazione ~ritmo", "ignore HR, go by feel ~pace", "ignora la FC, ve por sensaciones ~ritmo"),
    ("vai a sensazione ~ritmo", "go by feel ~pace", "ve por sensaciones ~ritmo"),
    ("trazione con l'avambraccio", "pull with the forearm", "tracción con el antebrazo"),
    ("tre bracciate poi occhi fuori: orientamento acque libere", "three strokes then eyes up: open-water sighting",
     "tres brazadas y ojos fuera: orientación en aguas abiertas"),
    ("un braccio solo, l'altro esteso avanti: presa e trazione", "one arm only, the other extended forward: catch and pull",
     "un solo brazo, el otro extendido adelante: agarre y tracción"),
    ("una mano attende l'altra davanti, allunga e scivola", "one hand waits for the other in front, reach and glide",
     "una mano espera a la otra delante, alarga y desliza"),
    ("una mano attende l'altra davanti", "one hand waits for the other in front", "una mano espera a la otra delante"),
    # parole degli step
    ("Sei battute per bracciata", "Six kicks per stroke", "Seis patadas por brazada"),
    ("Battute per bracciata", "Kicks per stroke", "Patadas por brazada"),
    ("Battuta di gambe sul fianco", "Side kick", "Patada de lado"),
    ("Battuta di gambe", "Kick", "Patada"),
    ("Braccio singolo", "Single arm", "Un brazo"),
    ("Crescendo", "Build", "Progresivo"),
    ("Forte", "Hard", "Fuerte"),
    ("Nuotata continua", "Continuous swim", "Nado continuo"),
    ("nuotata continua", "continuous swim", "nado continuo"),
    ("Nuotata regolare", "Steady swim", "Nado regular"),
    ("Nuoto completo", "Full stroke", "Nado completo"),
    ("Pugni chiusi", "Closed fists", "Puños cerrados"),
    ("Pull con boa e palette", "Pull with buoy and paddles", "Pull con pullbuoy y palas"),
    ("Pull con boa", "Pull with buoy", "Pull con pullbuoy"),
    ("Ritmo gara", "Race pace", "Ritmo de carrera"),
    ("ritmo gara", "race pace", "ritmo de carrera"),
    ("Svelto", "Quick", "Rápido"),
    ("Veloce", "Fast", "Veloz"),
    ("pausa al muro", "rest at the wall", "pausa en la pared"),
    ("recupero completo", "full recovery", "recuperación completa"),
    ("recupero attivo", "active recovery", "recuperación activa"),
    ("recupero", "recovery", "recuperación"),
    ("riscaldamento tecnico", "technical warm-up", "calentamiento técnico"),
    ("riscaldamento", "warm-up", "calentamiento"),
    ("defaticamento", "cool-down", "vuelta a la calma"),
    ("sciolto", "easy", "suave"),
    ("transizione", "transition", "transición"),
    ("allungo", "stride", "progresión"),
    ("cadenza", "cadence", "cadencia"),
    ("corsa", "run", "carrera"),
    ("bici", "bike", "bicicleta"),
]

_RIGHE = {l: [(re.compile(it, re.M), (en if l == "en" else es)) for it, en, es in RIGHE] for l in ("en", "es")}
_FRAM = {}
for _l in ("en", "es"):
    _FRAM[_l] = [(re.compile(r"(?<![A-Za-zÀ-ÿ])" + re.escape(it) + r"(?![A-Za-zÀ-ÿ])"), en if _l == "en" else es)
                 for it, en, es in sorted(FRAMMENTI, key=lambda x: -len(x[0]))]


# ── schede di forza (13c): catalogo a parte, applicato solo agli eventi WeightTraining ──
def _compila(voci):
    out = {}
    for l in ("en", "es"):
        out[l] = [(re.compile(r"(?<![A-Za-zÀ-ÿ])" + re.escape(it) + r"(?![A-Za-zÀ-ÿ])"), en if l == "en" else es)
                  for it, en, es in sorted(voci, key=lambda x: -len(x[0]))]
    return out


def _palestra():
    global _PAL
    if _PAL is None:
        import traduzioni_palestra as tp
        # un solo elenco, dal frammento piu' lungo al piu' corto: una frase lunga delle
        # prescrizioni ("senza spinta del piede a terra") vince su una corta ("a terra")
        _PAL = (_compila(tp.TITOLI + tp.ESERCIZI + tp.PRESCRIZIONI),)
    return _PAL


_PAL = None


def traduci_palestra(testo, lingua):
    """Testo di una scheda di forza (titoli, esercizi, prescrizioni), dal frammento piu'
    lungo al piu' corto: le parole brevi come "con" ed "e" per ultime. [[tipo:Gym]] resta."""
    l = lingua_calendario(lingua)
    if l == "it" or not testo:
        return testo
    (tutti,) = _palestra()
    righe = []
    for riga in testo.split("\n"):
        if riga.startswith("[[") and riga.endswith("]]"):
            righe.append(riga)
            continue
        for rx, sost in tutti[l]:
            riga = rx.sub(sost, riga)
        righe.append(riga)
    return "\n".join(righe)


def lingua_calendario(lingua):
    return LINGUE_CALENDARIO.get((lingua or "it").lower()[:2], "it")


def traduci(testo, lingua):
    """Testo del calendario nella lingua (it = invariato). Idempotente."""
    l = lingua_calendario(lingua)
    if l == "it" or not testo:
        return testo
    for rx, sost in _RIGHE[l]:
        testo = rx.sub(sost, testo)
    righe = []
    for riga in testo.split("\n"):
        if riga.startswith("[[") and riga.endswith("]]"):      # [[tipo:...]]: tecnico
            righe.append(riga)
            continue
        for rx, sost in _FRAM[l]:
            riga = rx.sub(sost, riga)
        righe.append(riga)
    return "\n".join(righe)


def traduci_evento(ev, lingua):
    """Copia dell'evento con nome e descrizione tradotti (13c: anche le schede di forza)."""
    if lingua_calendario(lingua) == "it":
        return ev
    f = traduci_palestra if ev.get("type") == "WeightTraining" else traduci
    out = dict(ev)
    for k in ("name", "description"):
        if isinstance(out.get(k), str):
            out[k] = f(out[k], lingua)
    return out
