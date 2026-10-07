package com.wboelens.polarrecorder.biosleep.protezione

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProtezioneTest {
  @Test
  fun marche() {
    assertEquals(Marca.XIAOMI, Marca.da("Xiaomi"))
    assertEquals(Marca.XIAOMI, Marca.da("POCO"))
    assertEquals(Marca.SAMSUNG, Marca.da("samsung"))
    assertEquals(Marca.HUAWEI, Marca.da("HONOR"))
    assertEquals(Marca.OPPO, Marca.da("realme"))
    assertEquals(Marca.PIXEL, Marca.da("Google"))
    assertEquals(Marca.ALTRA, Marca.da("Fairphone"))
    assertEquals("https://dontkillmyapp.com/xiaomi", Marca.XIAOMI.dontKillMyApp)
  }

  @Test
  fun interruzione() {
    assertEquals(Interruzione("2026-10-07", 42, "app chiusa dal sistema"),
        Interruzione.da("""{"data": "2026-10-07", "minuti": 42, "causa": "app chiusa dal sistema"}"""))
    assertNull(Interruzione.da("""{"data": "2026-10-07", "minuti": 0, "causa": "x"}"""), "zero minuti: nessuna card")
    assertNull(Interruzione.da("rotto"))
    assertNull(Interruzione.da(null))
  }
}
