package com.wboelens.polarrecorder.biosleep.voce

import com.wboelens.polarrecorder.voce.Confronto
import com.wboelens.polarrecorder.voce.Disciplina
import com.wboelens.polarrecorder.voce.Semaforo
import com.wboelens.polarrecorder.voce.SedutaVocale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Dati della voce dalle strutture dell'app (Parte 10). */
class VoceDatiTest {
  @Test
  fun seduteDalRiepilogo() {
    assertEquals(
        listOf(SedutaVocale(Disciplina.CORSA, 45), SedutaVocale(Disciplina.BICI, 60), SedutaVocale(Disciplina.FORZA, null)),
        VoceDati.seduteDaRiepilogo("RUN · Endurance Z2 · Progressivo (45min); BIKE · Soglia · 4x10min (60min); STR · Core; Yoga"))
    assertEquals(emptyList<SedutaVocale>(), VoceDati.seduteDaRiepilogo(null))
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

  @Test
  fun codiciDelCoach() {
    val json = """{"messaggi":[{"tipo":"avviso","codice":"tsb_fuori_fascia"},{"tipo":"decisione","codice":"decisione_rispetta_piano"},
      {"tipo":"motivo_decisione","codice":"motivo_banda_segue_piano"},{"tipo":"zona","codice":"zona_grigia"}]}"""
    assertEquals(listOf("decisione_rispetta_piano", "motivo_banda_segue_piano"), VoceDati.codiciCoach(json, "RISPETTA"))
    assertEquals(listOf("decisione_riduci"), VoceDati.codiciCoach(null, "RIDUCI"))
    assertEquals(emptyList<String>(), VoceDati.codiciCoach("{rotto", null))
  }
}
