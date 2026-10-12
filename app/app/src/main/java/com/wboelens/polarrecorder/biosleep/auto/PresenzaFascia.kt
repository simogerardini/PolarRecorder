package com.wboelens.polarrecorder.biosleep.auto

/**
 * Eventi di presenza di Android (DevicePresenceEvent) per la fascia associata.
 * La H10 indossata puo' farsi vedere in due modi:
 *  - BLE_APPEARED (0): trasmette la sua presenza e nessuno e' collegato;
 *  - BT_CONNECTED (2): si e' gia' collegata al telefono appena accesa (fascia abbinata o
 *    ricollegamento automatico): in quel caso non trasmette e BLE_APPEARED non arriva mai.
 * Prima si ascoltava solo il primo: con la fascia collegata da subito l'avvio automatico non partiva.
 */
object PresenzaFascia {
  const val BLE_COMPARSA = 0
  const val BLE_SCOMPARSA = 1
  const val BT_COLLEGATA = 2
  const val BT_SCOLLEGATA = 3

  /** true = fascia presente, false = non piu' presente, null = evento da ignorare. */
  fun presente(evento: Int): Boolean? =
      when (evento) {
        BLE_COMPARSA, BT_COLLEGATA -> true
        BLE_SCOMPARSA, BT_SCOLLEGATA -> false
        else -> null
      }

  fun nome(evento: Int): String =
      when (evento) {
        BLE_COMPARSA -> "comparsa (BLE)"
        BLE_SCOMPARSA -> "scomparsa (BLE)"
        BT_COLLEGATA -> "collegata al telefono"
        BT_SCOLLEGATA -> "scollegata dal telefono"
        else -> "evento $evento"
      }
}
