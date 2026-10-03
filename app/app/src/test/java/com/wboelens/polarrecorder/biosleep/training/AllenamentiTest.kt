package com.wboelens.polarrecorder.biosleep.training

import com.google.gson.JsonParser
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Calendario, settimana e struttura delle sedute su oggetti con la forma reale di Intervals.icu. */
class AllenamentiTest {
  private val oggi = LocalDate.of(2026, 10, 2)

  private fun ev(json: String) = Allenamenti.evento(JsonParser.parseString(json).asJsonObject)!!

  private fun att(json: String) = Allenamenti.attivita(JsonParser.parseString(json).asJsonObject)!!

  private val soglia =
      ev("""{"id":139840663,"start_date_local":"2026-10-02T00:00:00","category":"WORKOUT","type":"Run",
          "name":"2x6min soglia (tapis)","moving_time":2340,"icu_training_load":40,"distance":0.0,
          "paired_activity_id":"i192720692","workout_doc":{"steps":[
            {"ramp":true,"power":{"end":75,"start":60,"units":"%ftp"},"warmup":true,"duration":600},
            {"reps":2,"text":"2x","steps":[
              {"power":{"units":"%ftp","value":95},"duration":360},
              {"power":{"units":"%ftp","value":60},"duration":180}],"duration":1080},
            {"ramp":true,"power":{"end":60,"start":75,"units":"%ftp"},"cooldown":true,"duration":660}]}}""")
  private val nuoto =
      ev("""{"id":1,"start_date_local":"2026-09-30T00:00:00","category":"WORKOUT","type":"Swim","name":"Easy",
          "moving_time":2845,"icu_training_load":39,"distance":1750.0,"workout_doc":{"steps":[
            {"pace":{"end":77,"start":68,"units":"%pace"},"text":"riscaldamento e drills","warmup":true,
             "distance":325,"duration":569,"intensity":"warmup"},
            {"text":"pausa al muro","duration":20,"intensity":"rest"}]}}""")
  private val domani =
      ev("""{"id":2,"start_date_local":"2026-10-03T00:00:00","category":"WORKOUT","type":"Ride",
          "name":"Bike Z2","moving_time":5400,"icu_training_load":57}""")
  private val ieriSaltata =
      ev("""{"id":3,"start_date_local":"2026-10-01T00:00:00","category":"WORKOUT","type":"Run",
          "name":"Corsa","moving_time":3600,"icu_training_load":50}""")
  private val corsaFatta =
      att("""{"id":"i192720692","start_date_local":"2026-10-02T18:53:22","type":"VirtualRun",
          "name":"2x6min soglia (tapis)","moving_time":2410,"distance":8221.16,"icu_training_load":51,
          "paired_event_id":139840663,"compliance":127.5}""")
  private val nuotoLibero =
      att("""{"id":"i192333251","start_date_local":"2026-10-01T18:11:29","type":"Swim","name":"Nuoto Z2",
          "moving_time":2229,"distance":1800.0,"icu_training_load":35,"paired_event_id":null,"compliance":null}""")

  @Test
  fun abbinamentoEdEsiti() {
    val giorni =
        Allenamenti.giorni(listOf(soglia, domani, ieriSaltata), listOf(corsaFatta, nuotoLibero),
            oggi.minusDays(1), oggi.plusDays(1), oggi)
    assertEquals(3, giorni.size)
    val (g1, g2, g3) = giorni
    assertEquals(Esito.NON_SVOLTA, g1.pianificate.single().esito)
    assertEquals("i192333251", g1.nonPianificate.single().id, "nuoto non pianificato")
    assertEquals(Esito.SVOLTA, g2.pianificate.single().esito)
    assertEquals("i192720692", g2.pianificate.single().svolta?.id)
    assertEquals(0, g2.nonPianificate.size, "attivita' abbinata non ripetuta tra le libere")
    assertEquals(Esito.PIANIFICATA, g3.pianificate.single().esito)
  }

  @Test
  fun daFareSeOggiNonCeAncoraAttivita() {
    val g = Allenamenti.giorni(listOf(soglia), emptyList(), oggi, oggi, oggi).single()
    assertEquals(Esito.DA_FARE, g.pianificate.single().esito)
  }

  @Test
  fun settimanaLunediDomenica() {
    val lun = Allenamenti.lunedi(oggi)
    assertEquals(LocalDate.of(2026, 9, 28), lun)
    val tss = Allenamenti.settimana(listOf(soglia, domani, ieriSaltata), listOf(corsaFatta, nuotoLibero), lun, Metrica.TSS)
    assertEquals(7, tss.size)
    assertEquals(35.0, tss[3].svolto) // giovedi' 1/10
    assertEquals(50.0, tss[3].pianificato)
    assertEquals(51.0, tss[4].svolto)
    assertEquals(40.0, tss[4].pianificato)
    val km = Allenamenti.settimana(emptyList(), listOf(corsaFatta), lun, Metrica.DISTANZA)
    assertEquals(8.22116, km[4].svolto, 1e-9)
  }

  @Test
  fun strutturaConRipetuteEspanse() {
    val s = Struttura.segmenti(soglia.passi)
    assertEquals(6, s.size)
    assertEquals(2340, s.sumOf { it.durataS }, "la somma dei passi e' la durata pianificata")
    assertEquals(60.0, s[0].da)
    assertEquals(75.0, s[0].a)
    assertEquals(95.0, s[1].da)
    assertEquals(
        listOf("Riscaldamento · 10' · rampa 60–75% FTP", "2x", "6' · 95% FTP", "3' · 60% FTP",
            "Defaticamento · 11' · rampa 75–60% FTP"),
        Struttura.righe(soglia.passi).map { it.testo })
    assertEquals(listOf(0, 0, 1, 1, 0), Struttura.righe(soglia.passi).map { it.livello })
  }

  @Test
  fun strutturaNuotoADistanza() {
    assertEquals(
        listOf("Riscaldamento · 325 m · 68–77% passo · riscaldamento e drills", "20\" · pausa al muro"),
        Struttura.righe(nuoto.passi).map { it.testo })
    assertEquals(true, Struttura.segmenti(nuoto.passi)[1].recupero)
  }

  @Test
  fun obiettiviAZone() {
    val e =
        ev("""{"id":4,"start_date_local":"2026-09-27T00:00:00","category":"WORKOUT","type":"Run","name":"Long",
            "workout_doc":{"steps":[{"hr":{"units":"hr_zone","value":2},"duration":3900},
              {"hr":{"units":"hr_zone","start":1,"end":2},"cooldown":true,"duration":300}]}}""")
    assertEquals(listOf("65' · Z2", "Defaticamento · 5' · Z1–Z2"), Struttura.righe(e.passi).map { it.testo })
    assertEquals(70.0, Struttura.segmenti(e.passi)[0].da)
  }

  // NOTE specchio come la scrive il coach (_nota_specchio in intervals_coach.py)
  private val brick =
      ev("""{"id":9100,"start_date_local":"2026-10-04T00:00:00","category":"NOTE","name":"Brick Z2",
          "external_id":"coach:SpecchioGarmin:111",
          "description":"Multisport su Garmin Connect: Ride 60' + Transition 2' + Run 28'\n[[seduta_garmin:{\"tipo\": \"Brick\", \"nome\": \"Brick Z2\", \"durata_min\": 90, \"parti\": [{\"sport\": \"Ride\", \"min\": 60}, {\"sport\": \"Transition\", \"min\": 2}, {\"sport\": \"Run\", \"min\": 28}]}]]"}""")

  private fun parte(id: String, tipo: String, s: Int) =
      att("""{"id":"$id","start_date_local":"2026-10-04T18:00:00","type":"$tipo","name":"Multisport","moving_time":$s,"icu_training_load":20}""")

  @Test
  fun noteSpecchioComeSedutaPianificata() {
    assertEquals(true, brick.specchio)
    assertEquals(false, brick.nota)
    assertEquals(Sport.MULTISPORT, brick.sport)
    assertEquals(5400, brick.durataS)
    assertEquals(listOf(ParteGarmin(Sport.BICI, 60), ParteGarmin(Sport.TRANSIZIONE, 2), ParteGarmin(Sport.CORSA, 28)), brick.parti)
    val dom = LocalDate.of(2026, 10, 4)
    val futuro = Allenamenti.giorni(listOf(brick), emptyList(), dom, dom, oggi).single()
    assertEquals(Esito.PIANIFICATA, futuro.pianificate.single().esito)
    assertEquals(0, futuro.note.size, "la NOTE specchio non e' una nota")
    // svolta: le tre attivita' separate di Intervals.icu diventano la seduta multisport
    val parti = listOf(parte("i3", "VirtualRun", 1700), parte("i1", "VirtualRide", 3600), parte("i2", "Transition", 100))
    val g = Allenamenti.giorni(listOf(brick), parti + nuotoLibero, dom, dom, dom).single()
    val s = g.pianificate.single()
    assertEquals(Esito.SVOLTA, s.esito)
    assertEquals(listOf("i1", "i2", "i3"), s.svolte.map { it.id })
    assertEquals(0, g.nonPianificate.size)
    assertEquals(5400, s.consuntivo!!.durataS)
    assertEquals(100.0, s.consuntivo!!.compliance)
    assertEquals(60, s.consuntivo!!.tss)
    // settimana: conta come un WORKOUT
    val ore = Allenamenti.settimana(listOf(brick), parti, Allenamenti.lunedi(dom), Metrica.DURATA)
    assertEquals(1.5, ore[6].pianificato, 1e-9)
    assertEquals(1.5, ore[6].svolto, 1e-9)
  }

  @Test
  fun noteSpecchioIllegibileNonCompare() {
    // tag rovinato: non e' una seduta, e come ogni NOTE "coach:" non e' una nota dell'atleta
    val rotta =
        ev("""{"id":9101,"start_date_local":"2026-10-04T00:00:00","category":"NOTE","name":"Brick",
            "external_id":"coach:SpecchioGarmin:112","description":"[[seduta_garmin:{rotto]]"}""")
    assertEquals(false, rotta.specchio)
    assertEquals(false, rotta.nota)
    val atleta = ev("""{"id":9102,"start_date_local":"2026-10-04T00:00:00","category":"NOTE","name":"Gamba pesante"}""")
    assertEquals(true, atleta.nota)
  }

  @Test
  fun aderenzaEFormati() {
    assertEquals(Allenamenti.Aderenza.GIALLA, Allenamenti.aderenza(127.5))
    assertEquals(Allenamenti.Aderenza.VERDE, Allenamenti.aderenza(102.4))
    assertEquals(Allenamenti.Aderenza.ARANCIO, Allenamenti.aderenza(158.8))
    assertEquals(Allenamenti.Aderenza.NESSUNA, Allenamenti.aderenza(0.0))
    assertEquals("39'", Formato.durata(2340))
    assertEquals("1h30", Formato.durata(5400))
    assertEquals("8,2 km", Formato.distanza(8221.16))
    assertEquals("325 m", Formato.distanza(325.0))
    assertNull(domani.distanzaM)
  }
}
