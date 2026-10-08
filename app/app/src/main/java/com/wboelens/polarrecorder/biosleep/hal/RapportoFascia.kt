package com.wboelens.polarrecorder.biosleep.hal

import com.wboelens.polarrecorder.BuildConfig
import org.json.JSONObject

/**
 * "Rapporto fascia" per i tester: descrive la fascia e la qualita' del collegamento, SENZA dati
 * sanitari (niente frequenza cardiaca, HRV, sonno, date o orari delle notti).
 */
data class RapportoFascia(
    val nome: String,
    val capacita: Capacita,
    val statoRr: StatoRr,
    val motivoRr: String,
    val percentualeRrValidi: Double?,
    val pacchettiScartatiSenzaContatto: Int,
    val pacchettiMalformati: Int,
    val disconnessioni: Int,
    val durataMinuti: Int,
    val versioneApp: String,
    val telefono: String,
    val android: Int,
) {
  fun testo(): String = buildString {
    appendLine("RAPPORTO FASCIA ${BuildConfig.APP_NAME} (nessun dato sanitario)")
    appendLine("Fascia: $nome")
    appendLine("Produttore: ${capacita.produttore.ifBlank { "non dichiarato" }}")
    appendLine("Modello: ${capacita.modello.ifBlank { "non dichiarato" }}")
    appendLine("Firmware: ${capacita.firmware.ifBlank { "non dichiarato" }}")
    appendLine("Tipo: ${capacita.tipo.testo()}   Driver: ${capacita.driver}")
    appendLine("Capacità: FC ${sino(capacita.hr)} · RR ${capacita.rr?.let { sino(it) } ?: "non ancora noto"} · ACC ${sino(capacita.acc)}")
    appendLine("RR: ${statoRr.testo()} — $motivoRr")
    appendLine("RR validi: ${percentualeRrValidi?.let { "%.1f%%".format(it) } ?: "-"}")
    appendLine("Pacchetti senza contatto scartati: $pacchettiScartatiSenzaContatto · malformati: $pacchettiMalformati")
    appendLine("Disconnessioni: $disconnessioni in $durataMinuti minuti")
    appendLine("App $versioneApp · $telefono · Android API $android")
  }

  fun json(): String =
      JSONObject()
          .put("nome", nome)
          .put("produttore", capacita.produttore)
          .put("modello", capacita.modello)
          .put("firmware", capacita.firmware)
          .put("tipo", capacita.tipo.name)
          .put("driver", capacita.driver.name)
          .put("hr", capacita.hr)
          .put("rr", capacita.rr ?: JSONObject.NULL)
          .put("acc", capacita.acc)
          .put("stato_rr", statoRr.name)
          .put("motivo_rr", motivoRr)
          .put("rr_validi_pct", percentualeRrValidi ?: JSONObject.NULL)
          .put("scartati_senza_contatto", pacchettiScartatiSenzaContatto)
          .put("malformati", pacchettiMalformati)
          .put("disconnessioni", disconnessioni)
          .put("durata_min", durataMinuti)
          .put("app", versioneApp)
          .put("telefono", telefono)
          .put("android", android)
          .toString(2)

  private fun sino(b: Boolean) = if (b) "sì" else "no"

  companion object {
    /** Ricostruisce il rapporto salvato con json(). */
    fun daJson(testo: String): RapportoFascia? =
        try {
          val o = JSONObject(testo)
          RapportoFascia(
              nome = o.getString("nome"),
              capacita =
                  Capacita(
                      driver = TipoDriver.valueOf(o.getString("driver")),
                      hr = o.getBoolean("hr"),
                      rr = if (o.isNull("rr")) null else o.getBoolean("rr"),
                      acc = o.getBoolean("acc"),
                      tipo = TipoFascia.valueOf(o.getString("tipo")),
                      modello = o.optString("modello"),
                      produttore = o.optString("produttore"),
                      firmware = o.optString("firmware")),
              statoRr = StatoRr.valueOf(o.getString("stato_rr")),
              motivoRr = o.optString("motivo_rr"),
              percentualeRrValidi = if (o.isNull("rr_validi_pct")) null else o.getDouble("rr_validi_pct"),
              pacchettiScartatiSenzaContatto = o.optInt("scartati_senza_contatto"),
              pacchettiMalformati = o.optInt("malformati"),
              disconnessioni = o.optInt("disconnessioni"),
              durataMinuti = o.optInt("durata_min"),
              versioneApp = o.optString("app"),
              telefono = o.optString("telefono"),
              android = o.optInt("android"))
        } catch (e: org.json.JSONException) {
          null
        } catch (e: IllegalArgumentException) {
          null
        }
  }
}

fun TipoFascia.testo() = when (this) {
  TipoFascia.PETTO -> "da petto"
  TipoFascia.OTTICA -> "ottica (braccio/polso)"
  TipoFascia.SCONOSCIUTO -> "non riconosciuto dal nome"
}

fun StatoRr.testo() = when (this) {
  StatoRr.IN_VALUTAZIONE -> "in valutazione"
  StatoRr.AFFIDABILI -> "affidabili"
  StatoRr.SOLO_FC -> "solo FC"
  StatoRr.NON_AFFIDABILI -> "non affidabili"
}
