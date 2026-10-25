package com.wboelens.polarrecorder.biosleep.garmin

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GarminClientTest {
  private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
  private val jwt = b64("""{"alg":"RS256"}""") + "." + b64("""{"exp":1792994759,"iat":1792908359,"scope":["CONNECT_READ"]}""") + ".firma"

  @Test
  fun tokenComeTestoGrezzo() {
    assertEquals(jwt, GarminClient.estraiToken(jwt + "\n"))
    assertEquals(jwt, GarminClient.estraiToken("\"$jwt\""))
  }

  @Test
  fun tokenDentroUnJson() {
    assertEquals(jwt, GarminClient.estraiToken("""{"access_token":"$jwt","expires_in":86400}"""))
  }

  @Test
  fun tokenNonRiconosciuto() {
    assertNull(GarminClient.estraiToken(""))
    assertNull(GarminClient.estraiToken("<html>errore</html>"))
  }

  @Test
  fun scadenzaDalClaimExp() {
    assertEquals(1792994759L, GarminClient.scadenza(jwt))
    assertNull(GarminClient.scadenza("non.un-jwt"))
  }
}
