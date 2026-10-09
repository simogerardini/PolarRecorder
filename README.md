# NoctaliX

App Android per il monitoraggio notturno con una fascia cardio Bluetooth (Polar H10 o qualsiasi fascia con il profilo standard Heart Rate): frequenza cardiaca, HRV e fasi del sonno, con un coach di triathlon che gira sul telefono e pianifica gli allenamenti su [Intervals.icu](https://intervals.icu).

## Cosa fa

- Registra la notte in background: battito, intervalli RR e, con la Polar H10, movimento e respiro.
- Calcola FC a riposo, HRV (rMSSD, SDNN), fasi del sonno, punteggio e deficit di sonno, cronotipo.
- Invia il riassunto della notte al tuo account Intervals.icu.
- Il coach (Python sul telefono) legge notte, forma e calendario e pianifica o adatta le sedute: settimana tipo, gare, soglie, schede di forza, giorni caldi.

## Privacy

I dati restano sul telefono, tranne quelli che invii al tuo account Intervals.icu. Informativa completa: https://auth.noctalix.com/privacy

## Origine e licenza

NoctaliX nasce da un fork di [Polar Recorder](https://github.com/boelensman1/PolarRecorder) di Wigger Boelens, distribuito con licenza MIT: il file `LICENSE` conserva la sua nota di copyright, come richiesto dalla licenza. Le licenze delle librerie usate sono nell'app, in Impostazioni → Licenze open source.

NoctaliX è un progetto indipendente, non affiliato né approvato da Polar Electro o da Intervals.icu. "Polar" e i nomi dei dispositivi sono marchi dei rispettivi proprietari. Non è un dispositivo medico: le stime (in particolare le fasi del sonno) sono indicative.
