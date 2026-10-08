package com.wboelens.polarrecorder

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** I caratteri del sito sono nell'app con la loro licenza OFL (obbligatoria per includerli). */
class StileNoctalixTest {
  @Test
  fun caratteriELicenze() {
    for (f in listOf("inter_regular", "inter_medium", "inter_semibold", "space_grotesk_medium", "space_grotesk_bold")) {
      assertTrue(File("src/main/res/font/$f.ttf").length() > 50_000, "manca o e' vuoto $f.ttf")
    }
    for (f in listOf("inter_ofl.txt", "space_grotesk_ofl.txt")) {
      assertTrue(File("src/main/assets/licenze/$f").readText().contains("SIL Open Font License"), "manca la licenza $f")
    }
  }
}
