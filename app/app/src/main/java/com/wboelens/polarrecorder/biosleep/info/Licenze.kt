package com.wboelens.polarrecorder.biosleep.info

/** Un componente di terze parti usato da BioSleep, con la sua licenza (testo in assets/licenze). */
data class Componente(val nome: String, val descrizione: String, val licenza: String, val url: String, val file: String)

/**
 * Componenti di BioSleep e loro licenze. Elenco scritto a mano invece del plugin OSS Licenses di
 * Google: il plugin vede solo le dipendenze Maven (non Python, Chaquopy, Polar Recorder) e non
 * dichiara il supporto ad AGP 9. Da aggiornare quando si aggiunge una libreria.
 */
object Licenze {
  const val PRIVACY_URL = "https://biosleep-oauth.simonegerardini.workers.dev/privacy"

  val ORIGINE =
      Componente(
          "Polar Recorder", "BioSleep nasce da un fork di Polar Recorder, di Wigger Boelens.", "MIT",
          "https://github.com/boelensman1/PolarRecorder", "polar_recorder.txt")

  val COMPONENTI =
      listOf(
          Componente("Polar BLE SDK", "Collegamento alla fascia Polar H10.", "Polar SDK License", "https://github.com/polarofficial/polar-ble-sdk", "polar_ble_sdk.txt"),
          Componente("Chaquopy", "Python dentro l'app, per il coach.", "MIT", "https://github.com/chaquo/chaquopy", "chaquopy.txt"),
          Componente("Python", "Interprete del coach.", "PSF License", "https://www.python.org", "python.txt"),
          Componente("requests", "Chiamate a Intervals.icu e Open-Meteo dal coach.", "Apache 2.0", "https://github.com/psf/requests", "apache-2.0.txt"),
          Componente("urllib3", "Dipendenza di requests.", "MIT", "https://github.com/urllib3/urllib3", "urllib3.txt"),
          Componente("certifi", "Certificati per le connessioni sicure.", "MPL 2.0", "https://github.com/certifi/python-certifi", "certifi.txt"),
          Componente("idna", "Dipendenza di requests.", "BSD 3-Clause", "https://github.com/kjd/idna", "idna.txt"),
          Componente("charset-normalizer", "Dipendenza di requests.", "MIT", "https://github.com/jawah/charset_normalizer", "charset_normalizer.txt"),
          Componente("tzdata", "Fusi orari per il coach.", "Apache 2.0", "https://github.com/python/tzdata", "apache-2.0.txt"),
          Componente("Kotlin e kotlinx.coroutines", "Linguaggio e concorrenza.", "Apache 2.0", "https://github.com/Kotlin/kotlinx.coroutines", "apache-2.0.txt"),
          Componente("AndroidX e Jetpack Compose", "Interfaccia, navigazione, WorkManager, Custom Tab.", "Apache 2.0", "https://developer.android.com/jetpack/androidx", "apache-2.0.txt"),
          Componente("Gson", "Lettura dei dati JSON.", "Apache 2.0", "https://github.com/google/gson", "apache-2.0.txt"),
          Componente("RxJava e RxAndroid", "Usati dal Polar BLE SDK.", "Apache 2.0", "https://github.com/ReactiveX/RxJava", "apache-2.0.txt"),
      )

  /**
   * Servizi Google Play (posizione): distribuiti da Google con i termini dell'Android SDK, non con
   * una licenza open source; si citano a parte.
   */
  const val NOTA_GOOGLE =
      "La posizione approssimativa usa i servizi di localizzazione di Google Play, distribuiti da Google secondo i termini dell'Android Software Development Kit."
}
