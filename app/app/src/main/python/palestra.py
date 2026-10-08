# -*- coding: utf-8 -*-
"""
PALESTRA — libreria delle schede di forza e scelta deterministica (cervello NoctaliX).
06/10/2026, roadmap punto 6, regole approvate da Simone.

DATI: le schede vengono dal documento "Ottimizzazione_Schede_Palestra_Triathlon.md",
convertito da test/genera_palestra.py (nomi, serie, ripetizioni, recuperi e note fedeli).
La Scheda 20 (TRX) nel documento e' troncata a meta' e resta fuori finche' non e' completa.

SINTASSI INTERVALS.ICU (regole di Simone):
- esercizio a ripetizioni: "- Press lap <codice>. <nome> — <prescrizione> <stima>s";
- esercizio a tempo: step a durata, SENZA "Press lap"; su due lati due step uguali
  ("lato destro" / "lato sinistro"); contrazioni x secondi = durata di ogni lato;
- intervalli: serie, recuperi e tempi prendono il valore piu' basso ("3-4 serie" -> 3x,
  "25-30\"" -> 25s, "Recupero: 60\"-75\"" -> 60s); le ripetizioni restano nel testo;
- "Recupero: 45\" tra esercizi, 75\" al termine": Rest 45s dopo ogni esercizio tranne
  l'ultimo, Rest 75s a fine giro.

SCELTA (scegli_scheda): fase del coach -> fase del documento; slot (full body del lunedi'
o companion del mercoledi'); filtri del profilo (attrezzatura, livello); gara A entro 14
giorni -> solo peak_taper/in_season; tempo < 30' -> solo schede <= 20'; infortuni ->
scheda di prevenzione della zona; rotazione deterministica per settimana.
"""
import re

SCHEDE = [{'id': 'TRIA-FB-001',
  'num': 1,
  'titolo': 'Full Body A',
  'focus_testo': 'Ottimizzata — Forza Massimale & Spinta',
  'target': ['general_triathlon', 'olympic', 'middle_70_3'],
  'fasi': ['base_1', 'base_2'],
  'livelli': ['intermediate', 'advanced'],
  'attrezzatura': ['barbell_gym'],
  'focus': ['max_strength'],
  'infortuni': [],
  'durata': 45,
  'righe': ['WARM-UP (8 min)',
            'Foam Roller quadricipiti e glutei: 60" per lato',
            'Hip Airplane assistito: 2 x 5 per lato',
            "World's Greatest Stretch: 2 x 4 per lato",
            'BLOCCO A — forza massimale arti inferiori, 4 serie',
            'A1. Back Squat: 4 rip @80-85% 1RM, RIR 2, profondità parallelo, risalita esplosiva',
            'Recupero: 2\'30"',
            'BLOCCO B — unilaterale catena posteriore, 3 serie',
            'B1. Single-Leg Romanian Deadlift con manubrio (o Reverse Lunge): 6 rip x gamba RPE 7.5, discesa '
            'in 3"',
            'Recupero: 90"',
            'BLOCCO C — parte alta in superserie bilanciata, 4 serie',
            'C1. Lat Machine presa neutra: 6 rip RIR 2',
            'C2. Panca inclinata con manubri: 6 rip RIR 2',
            'Recupero: 90"',
            'BLOCCO D — tronco e piede, 3 serie',
            'D1. Suitcase Carry: 30" per lato',
            'D2. Hollow Body Hold: 25-30"',
            'D3. Single-Leg Calf Raise su gradino: 12 rip x gamba con 2" pausa in massimo allungamento',
            'Recupero: 60"']},
 {'id': 'TRIA-FB-002',
  'num': 2,
  'titolo': 'Full Body B',
  'focus_testo': "Complementare — Cerniera d'Anca & Spinta Verticale",
  'target': ['general_triathlon', 'olympic', 'middle_70_3'],
  'fasi': ['base_1', 'base_2'],
  'livelli': ['intermediate', 'advanced'],
  'attrezzatura': ['barbell_gym'],
  'focus': ['max_strength', 'structural_balance'],
  'infortuni': [],
  'durata': 45,
  'righe': ['WARM-UP (8 min)',
            'Mobilità caviglie con elastico a muro: 2 x 8 per lato',
            'Cat-Camel con respirazione profonda: 2 x 8',
            'Glute Bridge bipodalico a terra: 2 x 10',
            'Extra-rotatori con banda elastica: 2 x 12 per lato',
            "BLOCCO A — cerniera d'anca e forza massimale catena posteriore, 4 serie",
            'A1. Trap Bar Deadlift (o Stacco da terra convenzionale): 4-5 rip @80-85% 1RM, RIR 2',
            'Recupero: 2\'30"',
            'BLOCCO B — forza e stabilità unilaterale arti inferiori, 3 serie',
            'B1. Bulgarian Split Squat con manubri (o Step-Up su box): 6 rip x gamba, discesa in 3"',
            'Recupero: 90"',
            'BLOCCO C — spinta e tirata parte alta in superserie, 3-4 serie',
            'C1. Rematore con bilanciere o manubrio: 6-8 rip RIR 2',
            'C2. Military Press con manubri o bilanciere in piedi: 6-8 rip RIR 2',
            'Recupero: 90"',
            'BLOCCO D — stabilità rotazionale e caviglia/tibia, 3 serie',
            'D1. Pallof Press ai cavi o elastico: 10 rip x lato con fermo 2"',
            'D2. Tibialis Raise contro parete: 15-20 rip',
            'D3. Side Plank: 30" per lato',
            'Recupero: 60"']},
 {'id': 'TRIA-POW-001',
  'num': 3,
  'titolo': 'Potenza & Reattività Neuromuscolare',
  'focus_testo': '',
  'target': ['sprint', 'olympic', 'middle_70_3'],
  'fasi': ['build_1', 'build_2'],
  'livelli': ['intermediate', 'advanced'],
  'attrezzatura': ['barbell_gym', 'kettlebell'],
  'focus': ['rate_of_force_development'],
  'infortuni': [],
  'durata': 40,
  'righe': ['WARM-UP (6 min)',
            'Skip A sul posto: 2 x 20"',
            'Leg Swing frontali e laterali: 2 x 10 per lato',
            'Mini-band Monster Walk: 2 x 10 metri',
            'Circonduzioni spalle con elastico: 2 x 10',
            'BLOCCO A — reattività tendinea e stiffness (SSC rapido), 3 serie',
            'A1. Pogo Jump a caviglie rigide: 12-15 rimbalzi veloci, ginocchia tese',
            'Recupero: 60"-75"',
            'BLOCCO B — pliometria e forza esplosiva unilaterale, 3 serie',
            'B1. Split Squat Jump: 4 rip x gamba con massima elevazione e atterraggio morbido',
            'Recupero: 90"-2\'',
            'BLOCCO C — potenza e tripla estensione dinamica, 3-4 serie',
            "C1. Kettlebell Swing pesante: 6-8 rip con snap d'anca violento e massima accelerazione",
            'Recupero: 90"-2\'',
            'BLOCCO D — forza catena posteriore, 3 serie',
            'D1. Hip Thrust con bilanciere: 5-6 rip @RPE 7, fermo 1" al picco',
            'Recupero: 90"',
            'BLOCCO E — parte alta e catena cinetica, 3 serie',
            'E1. Push Press con manubri (impulso gambe): 5 rip esplosive',
            'E2. Trazioni alla sbarra: 4-5 rip RIR 2',
            'E3. Face Pull al cavo o con elastico: 12-15 rip lente',
            'Recupero: 45" tra esercizi, 75" al termine del triset',
            'BLOCCO F — core anti-estensione, 3 serie',
            'F1. Ab Wheel Rollout su ginocchia: 6-8 rip controllate',
            'Recupero: 60"']},
 {'id': 'TRIA-PRE-001',
  'num': 4,
  'titolo': 'Companion',
  'focus_testo': 'Prehab, Arco Plantare, Bacino e Cuffia',
  'target': ['general_triathlon', 'all_year'],
  'fasi': ['all_year', 'off_season'],
  'livelli': ['beginner', 'intermediate', 'advanced'],
  'attrezzatura': ['resistance_bands', 'bodyweight_only'],
  'focus': ['injury_prevention', 'active_recovery'],
  'infortuni': [],
  'durata': 25,
  'righe': ['WARM-UP (4 min)',
            'Cat-Camel con respirazione: 2 x 8',
            'Rotolamento pianta del piede su pallina dura: 45" per lato',
            'BLOCCO A — piede e caviglia, 3 serie',
            'A1. Short Foot monopodalico a piedi nudi: 20" per lato',
            'A2. Toe Yoga (sollevamento alluce alternato alle 4 dita): 10 rip x piede',
            'A3. Calf Raise isometrico a metà altezza (ginocchio dritto o piegato): 30"',
            'Recupero: 45"',
            'BLOCCO B — anca e stabilità gluteo medio, 3 serie',
            'B1. Clamshell con mini-band attorno alle ginocchia: 12-15 rip x lato',
            'B2. Side-Lying Hip Abduction (tallone ruotato in alto e indietro): 12 rip x lato',
            'B3. Single-Leg Glute Bridge a terra: 8-10 rip x gamba con fermo 1"',
            'Recupero: 45"',
            'BLOCCO C — spalle e scapole (nuoto), 2-3 serie',
            "C1. Band Pull-Apart all'altezza del petto: 15 rip lente",
            'C2. Extra-rotazione con elastico (gomito aderente al fianco o a 90°): 12 rip x braccio',
            'Recupero: 45"',
            'BLOCCO D — core multi-planare, 3 serie',
            'D1. Bird Dog con estensione diagonale e fermo 2": 6-8 rip x lato',
            'D2. Side Plank con bacino alto: 25-30" per lato',
            'D3. Hollow Body Hold compatto: 20-25"',
            'Recupero: 45"']},
 {'id': 'TRIA-BW-001',
  'num': 5,
  'titolo': 'Corpo Libero & Eccentrico',
  'focus_testo': 'Calisthenics & Viaggi',
  'target': ['general_triathlon', 'olympic', 'middle_70_3'],
  'fasi': ['all_year', 'off_season'],
  'livelli': ['intermediate', 'advanced'],
  'attrezzatura': ['bodyweight_only', 'hotel_minimal'],
  'focus': ['strength_endurance', 'structural_balance'],
  'infortuni': [],
  'durata': 35,
  'righe': ['WARM-UP (6 min)',
            'Inchworm senza piegamento: 2 x 5',
            "World's Greatest Stretch: 2 x 4 per lato",
            'BLOCCO A — forza unilaterale gambe, 3-4 serie',
            'A1. Bulgarian Split Squat a corpo libero con discesa in 4": 6-8 rip x gamba',
            'A2. Single-Leg Romanian Deadlift a corpo libero: 8 rip x gamba',
            'Recupero: 30" tra le gambe, 60" a fine blocco',
            'BLOCCO B — catena posteriore e flessori ginocchio, 3 serie',
            'B1. Nordic Hamstring assistito con elastico o mani: 4-5 rip con 4" caduta controllata',
            'B2. Single-Leg Glute Bridge con fermo 2" in alto: 8-10 rip x gamba',
            'Recupero: 75"',
            'BLOCCO C — parte alta a corpo libero, 3 serie',
            'C1. Rematore inverso (sotto tavolo robusto o sbarra bassa): 6-8 rip con fermo 1"',
            'C2. Piegamenti classici (piedi rialzati se facili): 8-10 rip',
            'C3. Pike Push-up (spinta verticale): 5-6 rip',
            'Recupero: 30" tra esercizi, 60" a fine giro',
            'BLOCCO D — tronco e piede, 3 serie',
            'D1. Single-Leg Calf Raise su gradino: 12-15 rip x gamba con 3" discesa e 2" allungamento',
            'D2. Side Plank con abduzione gamba libera: 25-30" per lato',
            'D3. Hollow Body Hold: 20-25"',
            'Recupero: 60"']},
 {'id': 'TRIA-SEA-001',
  'num': 6,
  'titolo': 'In-Season / Taper',
  'focus_testo': 'Scarico Neurale e Prontezza Gara',
  'target': ['general_triathlon', 'sprint', 'olympic', 'middle_70_3', 'full_ironman'],
  'fasi': ['peak_taper', 'in_season'],
  'livelli': ['intermediate', 'advanced'],
  'attrezzatura': ['barbell_gym'],
  'focus': ['rate_of_force_development'],
  'infortuni': [],
  'durata': 30,
  'righe': ['WARM-UP (5 min)',
            'Couch Stretch: 2 x 45" per lato',
            'Hip Opener a terra: 2 x 6 per lato',
            'Spiderman con rotazione toracica: 2 x 4 per lato',
            'BLOCCO A — forza neurale arti inferiori, 3 serie',
            'A1. Trap Bar Deadlift (o Box Squat): 3 rip @80% 1RM, RIR 3, risalita esplosiva',
            'Recupero: 2\'30"',
            'BLOCCO B — trazione nuoto, 3 serie',
            'B1. Trazioni zavorrate (o Lat Machine pesante): 4 rip RIR 3',
            "Recupero: 2'",
            'BLOCCO C — spinta orizzontale, 3 serie',
            'C1. Floor Press con manubri: 5 rip RIR 3',
            'Recupero: 90"',
            'BLOCCO D — stiffness ed elasticità, 3 serie',
            'D1. Drop Jump da gradino basso (15-20 cm): 5 salti con rimbalzo reattivo',
            'D2. Pallof Press isometrico con elastico: 20" per lato',
            'Recupero: 60"']},
 {'id': 'TRIA-BIC-001',
  'num': 7,
  'titolo': 'Stabilità Bici',
  'focus_testo': 'Postura Crono/Aero & Catena Posteriore',
  'target': ['middle_70_3', 'full_ironman', 'olympic'],
  'fasi': ['base_2', 'build_1', 'build_2'],
  'livelli': ['intermediate', 'advanced'],
  'attrezzatura': ['barbell_gym'],
  'focus': ['strength_endurance'],
  'infortuni': ['lower_back_crono'],
  'durata': 45,
  'righe': ['WARM-UP (6 min)',
            'Cat-Camel con respirazione diaframmatica: 2 x 8',
            'Prone Cobra: 2 x 30"',
            'Affondo basso con estensione toracica: 2 x 5 per lato',
            'BLOCCO A — catena cinetica posteriore, 4 serie',
            'A1. Good Morning con bilanciere o manubrio: 6 rip RPE 7, fermo 1" al parallelo',
            "Recupero: 2'",
            'BLOCCO B — spinta unilaterale, 3 serie',
            'B1. Step-Up su box (altezza ginocchio): 6 rip x gamba, senza spinta del piede a terra',
            'Recupero: 90"',
            'BLOCCO C — stabilità scapolare e dorsale alto, 3 serie',
            'C1. Rematore a petto in appoggio su panca 30°: 8 rip RIR 2, pausa 2" al petto',
            'C2. Y-T-W con manubri leggeri a pancia in giù: 6 rip per posizione',
            'Recupero: 75"',
            'BLOCCO D — tenuta posizione aero, 3 serie',
            'D1. Bear Crawl isometrico (ginocchia a 2 cm da terra): 35"',
            'D2. Copenhagen Plank su panca (adduzione anca): 20" per lato',
            'D3. Farmer\'s Walk con due manubri pesanti: 40"',
            'Recupero: 60"']},
 {'id': 'TRIA-RUN-001',
  'num': 8,
  'titolo': 'Resilienza Corsa',
  'focus_testo': "Stiffness, Tendine d'Achille & Bacino",
  'target': ['general_triathlon', 'sprint', 'olympic', 'middle_70_3', 'full_ironman'],
  'fasi': ['base_1', 'base_2', 'build_1'],
  'livelli': ['intermediate', 'advanced'],
  'attrezzatura': ['barbell_gym'],
  'focus': ['injury_prevention', 'structural_balance'],
  'infortuni': ['achilles_tendon', 'shin_splint'],
  'durata': 40,
  'righe': ['WARM-UP (6 min)',
            'Ankle Rocks a muro per dorsiflessione caviglia: 2 x 10 per lato',
            'Glute Bridge bipodalico con elastico alle ginocchia: 2 x 12',
            'Skips B sul posto: 2 x 15"',
            "BLOCCO A — cerniera d'anca monopodalica, 4 serie",
            'A1. Single-Leg Romanian Deadlift con manubrio: 6 rip x gamba RPE 7.5, discesa in 3"',
            'Recupero: 90"',
            'BLOCCO B — complesso soleo/polpaccio, 3 serie',
            'B1. Seated Calf Raise (o in piedi con ginocchio piegato a 45°): 8 rip con 3" fermo in basso',
            'Recupero: 75"',
            "BLOCCO C — adduttori e flessori d'anca, 3 serie",
            'C1. Affondi laterali con kettlebell: 6 rip x lato',
            'C2. Sollevamento ginocchio al petto al cavo o con mini-band: 8 rip x gamba controllate',
            'Recupero: 60"',
            'BLOCCO D — stabilità dinamica caviglia e bacino, 3 serie',
            'D1. Single-Leg Hop con atterraggio stabilizzato: 6 salti avanti/indietro x gamba',
            'D2. Side Plank con abduzione gamba tesa: 20" per lato',
            'D3. Tibialis Raise contro parete: 15 rip',
            'Recupero: 60"']},
 {'id': 'TRIA-SWM-001',
  'num': 9,
  'titolo': 'Potenza Nuoto',
  'focus_testo': 'Trazione, Rotazione & Assetto',
  'target': ['general_triathlon', 'olympic', 'middle_70_3', 'full_ironman'],
  'fasi': ['base_2', 'build_1'],
  'livelli': ['intermediate', 'advanced'],
  'attrezzatura': ['barbell_gym'],
  'focus': ['max_strength', 'rate_of_force_development'],
  'infortuni': ['swimmer_shoulder'],
  'durata': 40,
  'righe': ['WARM-UP (6 min)',
            'Arm Circles ampi controllati: 2 x 10 avanti/indietro',
            'Mobilità toracica in quadrupedia: 2 x 6 per lato',
            'Deadbug con mini-band sui piedi: 2 x 8 per lato',
            'BLOCCO A — potenza di trazione, 4 serie',
            'A1. Pullover con manubrio su panca piana: 6 rip RPE 7.5, massima apertura dorsale',
            'Recupero: 90"',
            'BLOCCO B — catena rotazionale e core, 3 serie',
            "B1. Cable Woodchopper (dall'alto verso il basso): 8 rip x lato veloci",
            'B2. Landmine Press a una mano: 6 rip x lato',
            'Recupero: 75"',
            'BLOCCO C — tirata orizzontale e deltoidi posteriori, 3 serie',
            'C1. Rematore ai cavi presa larga con gomiti aperti: 8 rip RIR 2',
            'C2. Straight-Arm Lat Pulldown ai cavi (braccia tese): 10 rip',
            'Recupero: 60"',
            'BLOCCO D — assetto idrodinamico, 3 serie',
            'D1. Plank con estensione alternata di un braccio in linea retta: 30"',
            'D2. Back Extension su panca romana con fermo 2": 10 rip',
            'Recupero: 60"']},
 {'id': 'TRIA-STR-002',
  'num': 10,
  'titolo': 'Forza Sub-Massimale',
  'focus_testo': 'Heavy Days / Periodo Fondamentale',
  'target': ['general_triathlon', 'middle_70_3', 'full_ironman'],
  'fasi': ['base_2'],
  'livelli': ['advanced'],
  'attrezzatura': ['barbell_gym'],
  'focus': ['max_strength'],
  'infortuni': [],
  'durata': 50,
  'righe': ['WARM-UP (7 min)',
            'Foam Roller banda ileotibiale e piriforme: 45" per lato',
            'Cossack Squat a corpo libero: 2 x 6 per lato',
            'Arm Swings e rotazioni toraciche: 2 x 10',
            'BLOCCO A — spinta bipodalica pesante, 4 serie',
            'A1. Front Squat (o Safety Bar Squat): 5 rip @80% 1RM, RIR 2',
            'Recupero: 2\'30"',
            'BLOCCO B — catena posteriore pesante, 4 serie',
            'B1. Stacco Rumeno (RDL) con bilanciere: 6 rip RPE 8, discesa in 3"',
            "Recupero: 2'",
            'BLOCCO C — tronco superiore forza, 3 serie',
            'C1. Military Press con bilanciere in piedi: 5 rip RIR 2',
            'C2. Trazioni zavorrate presa prona (o Lat Machine): 5 rip RIR 2',
            'Recupero: 90"',
            'BLOCCO D — forza isometrica del tronco, 3 serie',
            'D1. Barbell Rollout su ginocchia: 8 rip controllate',
            'D2. Copenhagen Plank a leva corta (ginocchio in appoggio): 25" per lato',
            'Recupero: 60"']},
 {'id': 'TRIA-POW-002',
  'num': 11,
  'titolo': 'Potenza Rotazionale',
  'focus_testo': 'Trasferimento di Potenza Multi-planare',
  'target': ['sprint', 'olympic', 'middle_70_3'],
  'fasi': ['build_1', 'build_2'],
  'livelli': ['intermediate', 'advanced'],
  'attrezzatura': ['barbell_gym'],
  'focus': ['rate_of_force_development'],
  'infortuni': [],
  'durata': 40,
  'righe': ['WARM-UP (6 min)',
            'Spiderman Walk con reach: 2 x 5 per lato',
            'Medicine Ball Slam simulato a corpo libero: 2 x 10',
            'Band Dislocates per spalle: 2 x 12',
            'BLOCCO A — spinta rotazionale balistica, 4 serie',
            'A1. Landmine Rotational Push Press: 4 rip x lato esplosive',
            'Recupero: 90"',
            'BLOCCO B — trazione orizzontale esplosiva, 3 serie',
            'B1. Rematore unilaterale su panca con partenza da fermo (dead-stop): 5 rip x braccio',
            'Recupero: 75"',
            'BLOCCO C — stabilità pelvica dinamica, 3 serie',
            'C1. Affondo posteriore con rotazione del busto con palla medica: 6 rip x lato',
            'C2. Salto laterale da fermo (Heiden jump) con atterraggio stabilizzato: 5 salti x lato',
            'Recupero: 75"',
            'BLOCCO D — anti-rotazione e trasmissione, 3 serie',
            'D1. Pallof Press orizzontale con isometria 3" a braccia tese: 8 rip x lato',
            'D2. Russian Twist con disco/manubrio leggero: 12 rip totali controllate',
            'Recupero: 60"']},
 {'id': 'TRIA-INJ-001',
  'num': 12,
  'titolo': 'Prevenzione Infortuni Runner',
  'focus_testo': 'Anti-Shin Splint & IT Band',
  'target': ['general_triathlon', 'sprint', 'olympic', 'middle_70_3', 'full_ironman'],
  'fasi': ['all_year', 'base_1'],
  'livelli': ['beginner', 'intermediate', 'advanced'],
  'attrezzatura': ['resistance_bands', 'bodyweight_only'],
  'focus': ['injury_prevention'],
  'infortuni': ['shin_splint', 'it_band'],
  'durata': 30,
  'righe': ['WARM-UP (6 min)',
            'Rotolamento pianta del piede con pallina da lacrosse: 60" per piede',
            'Polpacci al muro in allungamento dinamico: 2 x 10 molleggi per gamba',
            'Hip Circles in quadrupedia: 2 x 8 per gamba',
            'BLOCCO A — controllo eccentrico catena posteriore, 3 serie',
            'A1. Slider Leg Curl a due gambe (o fitball leg curl): 8 rip con 3" distensione',
            'Recupero: 75"',
            'BLOCCO B — stabilizzatori pelvici e ginocchio, 3 serie',
            'B1. Step-Down laterale da gradino basso (focus ginocchio in asse): 8 rip x gamba lente',
            'B2. Monster Walk con elastico sulle caviglie: 12 passi avanti + 12 passi indietro',
            'Recupero: 60"',
            'BLOCCO C — piede, caviglia e tibia, 3 serie',
            'C1. Tibialis Raise su talloni contro parete: 15 rip veloci a salire, 2" a scendere',
            'C2. Deficit Single-Leg Calf Raise: 10 rip x gamba (fermo 2" sotto il parallelo)',
            'Recupero: 60"',
            'BLOCCO D — catena laterale, 3 serie',
            'D1. Side Plank con clamshell simultaneo: 10 rip x lato',
            'D2. Deadbug con elastico teso tra mani e ginocchia: 8 rip alternate',
            'Recupero: 45"']},
 {'id': 'TRIA-INJ-002',
  'num': 13,
  'titolo': 'Prevenzione Spalla Swimmer',
  'focus_testo': 'Cuffia & Mobilità Toracica',
  'target': ['general_triathlon', 'olympic', 'middle_70_3', 'full_ironman'],
  'fasi': ['all_year'],
  'livelli': ['beginner', 'intermediate', 'advanced'],
  'attrezzatura': ['resistance_bands', 'barbell_gym'],
  'focus': ['injury_prevention'],
  'infortuni': ['swimmer_shoulder'],
  'durata': 30,
  'righe': ['WARM-UP (5 min)',
            'Foam Roller tratto toracico (estensioni): 60"',
            'Sleeper Stretch leggero a terra: 45" per lato',
            'Cat-Camel su gomiti: 2 x 8',
            'BLOCCO A — depressori scapolari e gran dorsale, 3 serie',
            'A1. Scapular Pull-up (trazioni solo di scapola, braccia tese): 8 rip con fermo 2"',
            "A2. Prone Y-Raise a terra con pollici verso l'alto: 10 rip",
            'Recupero: 60"',
            'BLOCCO B — extrarotatori e centratura omerale, 3 serie',
            'B1. Extrarotazione a 90° al cavo basso o con elastico: 10 rip x lato lente',
            'B2. Face Pull con fune mirato a livello della fronte: 12 rip con contrazione 2"',
            'Recupero: 60"',
            'BLOCCO C — stabilità torace e cassa toracica, 3 serie',
            'C1. Serratus Push-up (piegamenti solo scapolari): 12 rip',
            "C2. Dumbbell Pullover su panca: 10 rip con enfasi sull'allungamento",
            'Recupero: 60"',
            'BLOCCO D — core in assetto streamline, 3 serie',
            'D1. Streamline Plank (mani sovrapposte a terra, braccia distese avanti): 30"',
            'D2. Superman alternato a croce: 10 rip per diagonale',
            'Recupero: 45"']},
 {'id': 'TRIA-HTL-001',
  'num': 14,
  'titolo': 'Trasferta In Hotel',
  'focus_testo': 'Zero Attrezzatura / High Volume Resilienza',
  'target': ['general_triathlon'],
  'fasi': ['all_year'],
  'livelli': ['beginner', 'intermediate'],
  'attrezzatura': ['hotel_minimal', 'bodyweight_only'],
  'focus': ['strength_endurance'],
  'infortuni': [],
  'durata': 30,
  'righe': ['WARM-UP (5 min)',
            'Jumping Jacks controllati: 2 x 25',
            'Inchworm senza piegamento: 2 x 5',
            'Affondi sul posto con torsione del tronco: 2 x 5 per gamba',
            'BLOCCO A — gambe volume e controllo, 3 serie',
            'A1. Tempo Squat a corpo libero (discesa 4", fermo 2" in buca): 12 rip',
            'A2. Affondo bulgaro (piede posteriore sul letto o sedia): 10 rip x gamba',
            'Recupero: 60"',
            'BLOCCO B — catena posteriore e pavimento, 3 serie',
            'B1. Ponte glutei monopodalico con piede su rialzo: 12 rip x gamba',
            'B2. Reverse Hyperextension sul bordo del letto: 12 rip',
            'Recupero: 60"',
            'BLOCCO C — tronco superiore a corpo libero, 3 serie',
            'C1. Piegamenti classici con mani su rialzo se stanchi: 12 rip',
            'C2. Dips per tricipiti su sedia solida: 10 rip',
            'Recupero: 60"',
            'BLOCCO D — core endurance, 3 serie',
            'D1. Plank classico sui gomiti con avanzamento su punte: 40"',
            'D2. Hollow Hold con braccia lungo i fianchi: 25"',
            'Recupero: 45"']},
 {'id': 'TRIA-REC-001',
  'num': 15,
  'titolo': 'Rigenerazione & Mobilità Attiva',
  'focus_testo': 'Active Recovery Day',
  'target': ['general_triathlon', 'all_year'],
  'fasi': ['all_year', 'off_season'],
  'livelli': ['beginner', 'intermediate', 'advanced'],
  'attrezzatura': ['bodyweight_only'],
  'focus': ['active_recovery'],
  'infortuni': [],
  'durata': 25,
  'righe': ['WARM-UP (4 min)',
            'Respirazione diaframmatica a pancia in giù (Crocodile Breathing): 2 min',
            'Rotazioni lente della testa e del collo: 60"',
            'BLOCCO A — mobilizzazione globale, 2 serie',
            'A1. Hip 90/90 con transizione dinamica da seduti: 6 cambi per lato',
            'A2. Spiderman stretch con distensione ginocchio posteriore: 5 per lato',
            'Recupero: 30"',
            'BLOCCO B — stabilità a basso carico, 2 serie',
            'B1. Bird Dog lento con pausa 3" in massima estensione: 6 rip x lato',
            'B2. Glute Bridge bipodalico isometrico con cuscino stretto tra le ginocchia: 40"',
            'Recupero: 30"',
            'BLOCCO C — de-tensione catena posteriore, 2 serie',
            'C1. Downdog to Updog (Cane a testa in giù alternato a cobra): 6 transizioni lente',
            'C2. Child\'s Pose con focus su espansione dorsale: 45"',
            'Recupero: 30"',
            'BLOCCO D — arco plantare e caviglia, 2 serie',
            'D1. Camminata su talloni a punte sollevate: 30 metri',
            'D2. Camminata sulle punte dei piedi a ginocchia dritte: 30 metri',
            'Recupero: 30"']},
 {'id': 'TRIA-BIC-002',
  'num': 16,
  'titolo': 'Cronometro E Salita',
  'focus_testo': 'Potenza Specifica Ciclismo',
  'target': ['middle_70_3', 'full_ironman'],
  'fasi': ['base_2', 'build_1'],
  'livelli': ['intermediate', 'advanced'],
  'attrezzatura': ['barbell_gym'],
  'focus': ['strength_endurance', 'max_strength'],
  'infortuni': [],
  'durata': 45,
  'righe': ['WARM-UP (6 min)',
            'Affondi posteriori dinamici: 2 x 6 per lato',
            "Estensioni d'anca al cavo o con mini-band: 2 x 10 per gamba",
            'Bird Dog con ginocchia staccate da terra: 2 x 20"',
            'BLOCCO A — forza spinta su pendenza, 4 serie',
            'A1. Step-Up esplosivo su box con manubri: 5 rip x gamba RPE 8',
            "Recupero: 2'",
            "BLOCCO B — estensione d'anca pura, 3 serie",
            'B1. Hip Thrust con bilanciere (focus spinta dai talloni): 6 rip @75% 1RM, fermo 2"',
            'Recupero: 90"',
            'BLOCCO C — trazione stabilizzante manubrio, 3 serie',
            'C1. Rematore al petto con due manubri su panca inclinata: 6 rip pesanti',
            'C2. Push-up con mani su manubri (presa a martello): 8 rip lente',
            'Recupero: 75"',
            'BLOCCO D — tenuta lombare e pelvica, 3 serie',
            'D1. Reverse Hyper o Estensioni alla panca romana: 10 rip con disco al petto',
            'D2. Suitcase Carry con manubrio molto pesante: 25" per lato',
            'Recupero: 60"']},
 {'id': 'TRIA-COR-001',
  'num': 17,
  'titolo': 'Core Anti-Fatica',
  'focus_testo': 'Solidità nelle Fasi Finali di Corsa',
  'target': ['middle_70_3', 'full_ironman'],
  'fasi': ['build_1', 'build_2'],
  'livelli': ['intermediate', 'advanced'],
  'attrezzatura': ['bodyweight_only', 'barbell_gym'],
  'focus': ['strength_endurance'],
  'infortuni': [],
  'durata': 30,
  'righe': ['WARM-UP (5 min)',
            'Deadbug a corpo libero: 2 x 8 per lato',
            'Glute Bridge su una gamba: 2 x 6 per lato',
            'Plank to Downdog: 2 x 6 transizioni',
            'BLOCCO A — anti-estensione sotto fatica, 3 serie',
            'A1. Ab Wheel Rollout (o Plank con mani su fitball e cerchi dinamici): 8 rip',
            'Recupero: 75"',
            'BLOCCO B — anti-flessione laterale con carico asimmetrico, 3 serie',
            'B1. Camminata del cameriere (Waiter\'s Walk, un braccio alto e uno basso): 30" per lato',
            'Recupero: 60"',
            'BLOCCO C — stabilità rotazionale del bacino, 3 serie',
            'C1. Leg Lowering su panca con schiena piatta a terra: 8 rip lente',
            'C2. Side Plank con spinta del ginocchio superiore al petto: 8 rip lente x lato',
            'Recupero: 60"',
            'BLOCCO D — tenuta della catena cinetica, 3 serie',
            'D1. Hollow Body Rock (dondolio mantenendo la posizione a banana): 20"',
            'D2. Arch Body Hold (Superman isometrico a gambe e braccia tese): 25"',
            'Recupero: 45"']},
 {'id': 'TRIA-BEG-001',
  'num': 18,
  'titolo': 'Principiante / Onboarding',
  'focus_testo': 'Adattamento Anatomico & Schemi Motori',
  'target': ['general_triathlon', 'sprint', 'olympic'],
  'fasi': ['off_season', 'base_1'],
  'livelli': ['beginner'],
  'attrezzatura': ['barbell_gym'],
  'focus': ['structural_balance'],
  'infortuni': [],
  'durata': 35,
  'righe': ['WARM-UP (6 min)',
            'Cat-Camel: 2 x 8',
            'Glute Bridge bipodalico a terra: 2 x 10 con fermo 2"',
            'Air Squat con braccia tese avanti: 2 x 8 lenti',
            'BLOCCO A — apprendimento cerniera e spinta gambe, 3 serie',
            'A1. Goblet Squat su box (seduta e risalita controllata): 8 rip RIR 3',
            'Recupero: 90"',
            'BLOCCO B — catena posteriore guidata, 3 serie',
            'B1. Stacco Rumeno (RDL) con manubri: 8 rip RIR 3, focus bacino indietro',
            'Recupero: 90"',
            'BLOCCO C — tronco superiore bilanciato, 3 serie',
            'C1. Rematore al petto con manubri su panca a 45°: 8 rip RIR 3',
            'C2. Push-up facilitati con mani su panca: 8 rip controllate',
            'Recupero: 75"',
            'BLOCCO D — stabilizzazione base del tronco, 3 serie',
            'D1. Plank a terra sui gomiti: 30"',
            "D2. Farmer's Walk con due manubri moderati: 40 metri",
            'D3. Calf Raise bipodalico a terra con salita rapida: 12 rip',
            'Recupero: 60"']},
 {'id': 'TRIA-ADV-001',
  'num': 19,
  'titolo': 'Avanzato / Massima Intensità',
  'focus_testo': 'Forza Assoluta & Reclutamento Alto',
  'target': ['middle_70_3', 'full_ironman', 'olympic'],
  'fasi': ['base_2'],
  'livelli': ['advanced'],
  'attrezzatura': ['barbell_gym'],
  'focus': ['max_strength'],
  'infortuni': [],
  'durata': 55,
  'righe': ['WARM-UP (8 min)',
            'Mobilità caviglie con banda elastica: 2 x 10 per lato',
            'Hip Airplane senza supporto: 2 x 5 per lato',
            'Spiderman con apertura toracica: 2 x 5 per lato',
            'BLOCCO A — forza massimale spinta inferiore, 4 serie',
            'A1. Back Squat con bilanciere: 3 rip @85-88% 1RM, RIR 1-2',
            "Recupero: 3'",
            'BLOCCO B — forza massimale catena posteriore, 4 serie',
            'B1. Trap Bar Deadlift presa alta: 3 rip @85% 1RM, risalita esplosiva',
            "Recupero: 3'",
            'BLOCCO C — forza verticale e trazione parte alta, 3 serie',
            'C1. Trazioni con sovraccarico alla cintura: 4 rip RIR 2',
            'C2. Military Press con bilanciere in piedi: 4 rip RIR 2',
            "Recupero: 2'",
            'BLOCCO D — trasmissione della forza sotto carico, 3 serie',
            'D1. Heavy Suitcase Deadlift (stacco unilaterale da terra): 5 rip x lato',
            'D2. Dragon Flag facilitato (o Leg Raise su panca inclinata): 6 rip controllate',
            'Recupero: 90"']},
 {'id': 'TRIA-TRX-001',
  'num': 20,
  'titolo': 'Trx / Suspension Training',
  'focus_testo': 'Stabilità Tridimensionale',
  'target': ['general_triathlon'],
  'fasi': ['all_year', 'off_season'],
  'livelli': ['beginner', 'intermediate'],
  'attrezzatura': ['trx_suspension'],
  'focus': ['structural_balance', 'strength_endurance'],
  'infortuni': [],
  'durata': 30,
  'righe': ['WARM-UP (5 min)',
            'TRX Torso Rotation dinamico: 2 x 8 per lato',
            'TRX Deep Overhead Squat di mobilità: 2 x 8 lenti',
            'TRX Wall Slide per mobilità scapolare: 2 x 10',
            'BLOCCO A — arti inferiori in sospensione, 3 serie',
            'A1. TRX Single-Leg Squat (Pistol squat assistito): 6 rip x gamba lente',
            'Recupero: 75"',
            'BLOCCO B — catena posteriore dinamica, 3 serie',
            'B1. TRX Hamstring Runner (flession']}]

SCHEDE = [s for s in SCHEDE if s["num"] != 20]   # TRX: troncata nel documento

PER_ID = {s["id"]: s for s in SCHEDE}
ATTREZZATURA = ("barbell_gym", "kettlebell", "trx_suspension", "resistance_bands",
                "bodyweight_only", "hotel_minimal")
SEMPRE_DISPONIBILE = {"bodyweight_only", "hotel_minimal"}
LIVELLI = ("beginner", "intermediate", "advanced")
FOCUS_FULL = {"max_strength", "rate_of_force_development", "strength_endurance", "structural_balance"}
FOCUS_COMPANION = {"injury_prevention", "active_recovery"}
COMPANION_EXTRA = {"TRIA-COR-001"}             # core anti-fatica: companion nelle fasi build
ONBOARDING = "TRIA-BEG-001"
ONBOARDING_SETTIMANE = 4
SCARICO = "TRIA-SEA-001"
# zona del tag dell'app -> schede di prevenzione (in rotazione)
PREVENZIONE = {"caviglia": ("TRIA-RUN-001", "TRIA-INJ-001"), "polpaccio": ("TRIA-RUN-001", "TRIA-INJ-001"),
               "piede": ("TRIA-RUN-001", "TRIA-INJ-001"), "ginocchio": ("TRIA-INJ-001",),
               "anca": ("TRIA-INJ-001",), "spalla": ("TRIA-INJ-002",), "schiena": ("TRIA-BIC-001",)}
DISTANZA_TARGET = {"olimpico": "olympic", "70.3": "middle_70_3", "full": "full_ironman", "sprint": "sprint"}


# ── FASE ─────────────────────────────────────────────────────────────────────
def fase_documento(pos):
    """Fase del coach -> fasi del documento ammesse."""
    fase, idx, n = pos.get("fase"), pos.get("idx_fase", 0) or 0, pos.get("len_fase") or 1
    if pos.get("scarico"):
        return {"in_season"}
    if fase == "senza_gara":
        meta = "1" if idx < 2 else "2"
        return {f"base_{meta}"} if pos.get("blocco", "A") == "A" else {f"build_{meta}"}
    meta = "1" if idx < n / 2 else "2"
    if fase == "base":
        return {f"base_{meta}"}
    if fase == "build":
        return {f"build_{meta}"}
    if fase in ("peak", "taper", "gara"):
        return {"peak_taper", "in_season"}
    if fase == "recupero":
        return {"off_season"}
    return {"base_1"}


# ── SCELTA ───────────────────────────────────────────────────────────────────
def _ammessa(s, attrezzi, livello, distanza):
    richiede = set(s["attrezzatura"]) - SEMPRE_DISPONIBILE
    if not richiede <= set(attrezzi):
        return False
    if livello not in s["livelli"]:
        return False
    tgt = DISTANZA_TARGET.get(distanza)
    return not tgt or "general_triathlon" in s["target"] or tgt in s["target"] or "all_year" in s["target"]


def scegli_scheda(slot, pos, profilo=None, rotazione=0, giorni_gara_a=None, minuti=None,
                  prevenzione=None, settimane_forza=None):
    """Scheda per lo slot ("full" o "companion"), o None (con il motivo) se nessuna va bene.
    Ritorna (scheda|None, motivo|None)."""
    p = profilo or {}
    attrezzi = set(p.get("attrezzatura") or ATTREZZATURA_PREDEFINITA) | SEMPRE_DISPONIBILE
    livello = p.get("livello") or "intermediate"
    distanza = pos.get("distanza")
    fasi = fase_documento(pos)
    if giorni_gara_a is not None and 0 <= giorni_gara_a < 14:
        fasi = {"peak_taper", "in_season"}
    if prevenzione and slot == "companion":
        cand = [PER_ID[i] for i in PREVENZIONE.get(prevenzione, ()) if i in PER_ID]
        cand = [s for s in cand if set(s["attrezzatura"]) - SEMPRE_DISPONIBILE <= attrezzi] or cand[:0]
        if cand:
            return cand[rotazione % len(cand)], f"prevenzione {prevenzione}"
    if (slot == "full" and livello == "beginner" and settimane_forza is not None
            and settimane_forza < ONBOARDING_SETTIMANE and ONBOARDING in PER_ID
            and set(PER_ID[ONBOARDING]["attrezzatura"]) - SEMPRE_DISPONIBILE <= attrezzi):
        return PER_ID[ONBOARDING], "onboarding"
    if slot == "full":
        cand = [s for s in SCHEDE if set(s["focus"]) & FOCUS_FULL and fasi & set(s["fasi"])
                and not (set(s["focus"]) & FOCUS_COMPANION) and s["id"] not in COMPANION_EXTRA]
        if fasi == {"in_season"} and SCARICO in PER_ID:
            cand = [PER_ID[SCARICO]]
    else:
        cand = [s for s in SCHEDE if (set(s["focus"]) & FOCUS_COMPANION or s["id"] in COMPANION_EXTRA)
                and (fasi & set(s["fasi"]) or "all_year" in s["fasi"])]
    cand = [s for s in cand if _ammessa(s, attrezzi, livello, distanza)]
    if minuti is not None and minuti < 30:
        cand = [s for s in cand if s["durata"] <= 20]
        if not cand:
            return None, "meno di 30' disponibili: nessuna scheda di forza fino a 20'"
    if not cand:
        return None, "nessuna scheda compatibile con attrezzatura e livello del profilo"
    cand.sort(key=lambda s: s["num"])
    return cand[rotazione % len(cand)], None


ATTREZZATURA_PREDEFINITA = ("barbell_gym", "kettlebell", "resistance_bands")


# ── SINTASSI ─────────────────────────────────────────────────────────────────
_SEZIONI = ("WARM-UP", "BLOCCO")


def _primo(testo):
    """Primo numero di un intervallo: "3-4" -> 3, "25-30" -> 25."""
    m = re.search(r"\d+", testo)
    return int(m.group()) if m else None


def _secondi(testo):
    """Durata in secondi del primo tempo nel testo: 45\" -> 45, 2' -> 120, 2'30\" -> 150."""
    m = re.search(r"(\d+)'(?:(\d+)\")?|(\d+)(?:-\d+)?\s*(?:\"|secondi\b|sec\b)|(\d+)\s*min\b", testo)
    if not m:
        return None
    if m.group(1):
        return int(m.group(1)) * 60 + int(m.group(2) or 0)
    if m.group(4):                        # 06/10/2026: "2 min" (Crocodile Breathing)
        return int(m.group(4)) * 60
    return int(m.group(3))


def _recuperi(riga):
    """(tra esercizi, fine giro) in secondi da "Recupero: ...", con il valore piu' basso."""
    corpo = riga.split(":", 1)[1]
    if " tra " in corpo and "," in corpo:
        a, b = corpo.split(",", 1)
        return _secondi(a), _secondi(b)
    return None, _secondi(corpo)


def _testo(t):
    """Testo dello step senza virgolette (Intervals le legge come durate) ne' %."""
    t = re.sub(r'\b1"', "1 secondo", t)
    t = t.replace('"', " secondi").replace("'", " ").replace("%", " per cento")
    t = re.sub(r"\s+", " ", t).strip()
    nome, _, presc = t.partition(":")
    return f"{nome.strip()} — {presc.strip()}" if presc else nome.strip()


def _a_tempo(riga):
    """(secondi per step, numero di step) per un esercizio a tempo, None se a ripetizioni."""
    _, _, presc = riga.partition(":")
    if re.search(r"\brip\b|\bripetizion|rimbalzi|salti|metri|lanci|rotazioni", presc.lower()):
        return None
    # BUG FIX (06/10/2026): "2 x 10 con fermo 2\"" sono 10 ripetizioni con un fermo, non un
    # esercizio da 2 secondi. E' a tempo solo se la prima quantita' (dopo "N x") e' un tempo
    # o un numero di contrazioni/tenute.
    quantita = re.sub(r"^\s*\d+\s*x\s+", "", presc)
    if not re.match(r"\s*\d+(?:-\d+)?\s*(?:\"|secondi\b|sec\b|'|min\b|contrazioni|tenute)", quantita):
        return None
    sec = _secondi(presc)
    if not sec:
        return None
    contr = re.search(r"(\d+)\s*(?:contrazioni|tenute)", presc)
    serie = re.match(r"\s*(\d+)\s*x\s", presc)
    lati = 2 if re.search(r"(per|x)\s+(lato|gamba|braccio|piede)", presc) else 1
    return sec * (int(contr.group(1)) if contr else 1), (int(serie.group(1)) if serie else 1) * lati


# 06/10/2026 — correzione di Simone (Scheda 07): tra le serie a tempo del riscaldamento
# "- Rest 30s intensity=rest" (step a durata, senza Press lap).
RECUPERO_RISCALDAMENTO = 30
_UNITA_PRESCRIZIONE = r"(?:rip|ripetizion|metri|rimbalzi|salti|lanci|rotazioni|passi|molleggi|transizioni)"


def _riscaldamento(e, stima):
    """Esercizio del riscaldamento. Con "N x" (serie): blocco "Nx" con un solo step
    (a ripetizioni: "Press lap <nome> — <n> rip"; a tempo: lo step a durata e poi
    "Rest 30s"). Senza serie: come gli esercizi dei blocchi."""
    nome_raw, _, presc = e.partition(":")
    nome = _testo(nome_raw)
    m = re.match(r"\s*(\d+)\s*x\s+(.*)$", presc)
    t = _a_tempo(e)
    lati = bool(re.search(r"(per|x)\s+(lato|gamba|braccio|piede)", presc))
    if not m:
        if t:
            sec, passi = t
            return "\n".join(f"- {nome}" + ((" — lato destro" if k % 2 == 0 else " — lato sinistro")
                                              if lati else "") + f" {sec}s intensity=active"
                             for k in range(passi))
        return f"- Press lap {_testo(e)} {stima}s intensity=active"
    n, resto = int(m.group(1)), m.group(2).strip()
    if t:
        sec = t[0]
        righe = [f"- {nome}" + ((" — lato destro" if k == 0 else " — lato sinistro") if lati else "")
                 + f" {sec}s intensity=active" for k in range(2 if lati else 1)]
        righe.append(f"- Rest {RECUPERO_RISCALDAMENTO}s intensity=rest")
    else:
        # "8" -> "8 rip"; "5 per lato" -> "5 rip per lato"; "10 metri" resta
        if not re.match(r"\d+\s*" + _UNITA_PRESCRIZIONE, resto):
            resto = re.sub(r"^(\d+)", r"\1 rip", resto)
        righe = [f"- Press lap {nome} — {_testo(resto)} {stima}s intensity=active"]
    return f"{n}x\n" + "\n".join(righe)


def descrizione(scheda):
    """Descrizione Intervals.icu della scheda con le regole di sintassi di Simone."""
    blocchi, sez = [], None
    for r in scheda["righe"]:
        if r.startswith(_SEZIONI):
            m = re.search(r"(\d+)(?:-\d+)?\s*serie", r)
            sez = {"titolo": r, "n": int(m.group(1)) if m else 1,
                   "warmup": r.startswith("WARM-UP"), "es": [], "tra": None, "fine": None}
            blocchi.append(sez)
        elif r.lower().startswith("recupero") and sez:
            sez["tra"], sez["fine"] = _recuperi(r)
        elif sez:
            sez["es"].append(r)
    # durata stimata degli esercizi a ripetizioni: riempie la durata della scheda
    tempo_fisso = rip = 0
    for b in blocchi:
        for e in b["es"]:
            t = _a_tempo(e)
            serie = re.match(r"[^:]*:\s*(\d+)\s*x\s", e) if b["warmup"] else None
            if t:
                tempo_fisso += b["n"] * t[0] * t[1]
                if serie:
                    tempo_fisso += int(serie.group(1)) * RECUPERO_RISCALDAMENTO
            else:
                rip += b["n"] * (int(serie.group(1)) if serie else 1)
        n_rec = (len(b["es"]) - 1) if b["tra"] else 0
        tempo_fisso += b["n"] * ((b["tra"] or 0) * n_rec + (b["fine"] or 0))
    # durata stimata di uno step Press lap: riempie la durata della scheda (minimo 10s: con
    # molte serie a tempo e recuperi al lap, 20s fissi sforavano la durata dichiarata)
    stima = max(10, min(120, round((scheda["durata"] * 60 - tempo_fisso) / max(rip, 1))))
    out = [f"[[tipo:Gym]]\nSchema palestra — {scheda['titolo']}"
           + (f" ({scheda['focus_testo']})" if scheda.get("focus_testo") else "")]
    for b in blocchi:
        if b["warmup"]:
            # 06/10/2026 — correzione di Simone (Scheda 07): nel riscaldamento "N x ..."
            # sono N serie dello stesso esercizio -> un blocco "Nx" per esercizio.
            out += [_riscaldamento(e, stima) for e in b["es"]]
            continue
        righe = []
        for i, e in enumerate(b["es"]):
            t = _a_tempo(e)
            if t:
                sec, passi = t
                lati = passi > 1 and re.search(r"(per|x)\s+(lato|gamba|braccio|piede)", e)
                nome = _testo(e.split(":", 1)[0])
                for k in range(passi):
                    lato = (" — lato destro" if k % 2 == 0 else " — lato sinistro") if lati else ""
                    righe.append(f"- {nome}{lato} {sec}s intensity=active")
            else:
                serie = re.match(r"\s*(\d+)\s*x\s", e.split(":", 1)[1]) if ":" in e else None
                testo = _testo(e)
                if b["warmup"] and serie:
                    testo = re.sub(r"— (\d+)\s*x\s", r"— \1 serie da ", testo)
                righe.append(f"- Press lap {testo} {stima}s intensity=active")
            if b["tra"] and i < len(b["es"]) - 1:
                righe.append(f"- Rest {b['tra']}s intensity=rest")
        if b["fine"]:
            righe.append(f"- Rest {b['fine']}s intensity=rest")
        n = 1 if b["warmup"] else b["n"]
        out.append((f"{n}x\n" if n > 1 else "") + "\n".join(righe))
    return "\n\n".join(out) + "\n"


def evento(scheda, data_str, nome=None):
    return {"category": "WORKOUT", "start_date_local": f"{data_str}T00:00:00",
            "type": "WeightTraining", "name": nome or f"Forza — {scheda['titolo']} {scheda['durata']}min",
            "description": descrizione(scheda), "moving_time": scheda["durata"] * 60,
            "external_id": f"coach:Gym:{data_str}"}


def scheda_da_nome(nome):
    """Scheda di un evento scritto da evento() ("Forza — <titolo>"), None altrimenti."""
    for s in SCHEDE:
        if nome and re.sub(r"\s+\d+min$", "", nome).endswith(s["titolo"]):
            return s
    return None


def e_companion(scheda):
    return bool(set(scheda["focus"]) & FOCUS_COMPANION) or scheda["id"] in COMPANION_EXTRA
