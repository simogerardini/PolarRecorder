package com.wboelens.polarrecorder

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Il nome dell'app vive in due copie: build.gradle.kts (BuildConfig.APP_NAME) e python/marchio.py
 * (NOME_APP). Questo test, che gira a ogni build sul Mac, impedisce che divergano.
 * (Sul telefono lo stesso controllo, tramite Chaquopy, e' in androidTest/MarchioChaquopyTest.)
 */
class MarchioTest {
  @Test
  fun nomeUgualeInKotlinEInPython() {
    val f = File("src/main/python/marchio.py")
    val nome = Regex("""^NOME_APP\s*=\s*["'](.+?)["']""", RegexOption.MULTILINE).find(f.readText())?.groupValues?.get(1)
    assertEquals(BuildConfig.APP_NAME, nome, "marchio.NOME_APP diverso da BuildConfig.APP_NAME")
  }
}
