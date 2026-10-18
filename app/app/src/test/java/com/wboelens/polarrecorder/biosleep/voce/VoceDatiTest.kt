package com.wboelens.polarrecorder.biosleep.voce

import com.wboelens.polarrecorder.voce.Confronto
import com.wboelens.polarrecorder.voce.Disciplina
import com.wboelens.polarrecorder.voce.Semaforo
import com.wboelens.polarrecorder.voce.SedutaVocale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Dati della voce della pagina Oggi (Parte 10). */
class VoceDatiTest {
  @Test
  fun sedutaDalNome() {
    assertEquals(SedutaVocale(Disciplina.CORSA, 45), VoceDati.seduta("RUN · Endurance Z2 · Progressivo", 45))
    assertEquals(SedutaVocale(Disciplina.BICI, null), VoceDati.seduta("BIKE · Soglia", 0))
    assertEquals(null, VoceDati.seduta("Yoga", 30))
  }

  @Test
  fun semaforoEConfronto() {
    assertEquals(Semaforo.VERDE, VoceDati.semaforo("verde"))
    assertEquals(Semaforo.GIALLO, VoceDati.semaforo("giallo"))
    assertEquals(Semaforo.ROSSO, VoceDati.semaforo("rosso"))
    assertEquals(Semaforo.GRIGIO, VoceDati.semaforo(null))
    assertEquals(Confronto.SOPRA, VoceDati.confronto(60.0, 50.0 to 58.0))
    assertEquals(Confronto.NELLA, VoceDati.confronto(55.0, 50.0 to 58.0))
    assertEquals(Confronto.SOTTO, VoceDati.confronto(45.0, 50.0 to 58.0))
    assertEquals(null, VoceDati.confronto(45.0, null))
  }
}
