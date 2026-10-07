package com.wboelens.polarrecorder.biosleep.hal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InfoFasciaTest {
  private fun rapporto(nome: String, stato: StatoRr, acc: Boolean = false) =
      RapportoFascia(nome, Capacita(TipoDriver.GATT_180D, true, stato == StatoRr.AFFIDABILI, acc, TipoFascia.PETTO),
          stato, "", 99.0, 0, 0, 0, 480, "x", "y", 34)

  @Test
  fun primaDellaNotte() {
    assertEquals(PrevisioneFascia(true, true, false), InfoFascia.prevedi("Polar H10 0F291832", TipoFascia.PETTO, null))
    assertEquals(PrevisioneFascia(true, false, false), InfoFascia.prevedi("Polar Verity Sense", TipoFascia.OTTICA, null))
    assertEquals(PrevisioneFascia(null, false, false), InfoFascia.prevedi("HRM-Dual:123", TipoFascia.PETTO, null))
    assertEquals(PrevisioneFascia(false, false, false), InfoFascia.prevedi("Wahoo TICKR FIT", TipoFascia.OTTICA, null))
  }

  @Test
  fun dopoUnaNotteDecideIlRapporto() {
    assertEquals(false, InfoFascia.prevedi("HRM-Dual:123", TipoFascia.PETTO, rapporto("HRM-Dual:123", StatoRr.NON_AFFIDABILI)).hrv)
    assertEquals(true, InfoFascia.prevedi("HRM-Dual:123", TipoFascia.PETTO, rapporto("HRM-Dual:123", StatoRr.AFFIDABILI)).verificata)
    // rapporto di un'altra fascia: non conta
    assertEquals(null, InfoFascia.prevedi("HRM-Dual:123", TipoFascia.PETTO, rapporto("Coospo H6", StatoRr.SOLO_FC)).hrv)
  }
}
