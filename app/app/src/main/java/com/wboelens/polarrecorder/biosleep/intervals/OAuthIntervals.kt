package com.wboelens.polarrecorder.biosleep.intervals

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wboelens.polarrecorder.BuildConfig
import com.wboelens.polarrecorder.biosleep.cervello.Cervello
import com.wboelens.polarrecorder.biosleep.cervello.EsitoPrepara
import com.wboelens.polarrecorder.biosleep.cervello.SoglieRepo
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Stato del collegamento e della preparazione dei campi, per la schermata Impostazioni. */
data class StatoAccount(val inCorso: Boolean = false, val messaggio: String? = null, val esito: EsitoPrepara? = null)

/**
 * Collegamento OAuth a Intervals.icu. client_id e indirizzi stanno in BuildConfig (non sono
 * segreti); il client_secret lo conosce solo il Worker.
 */
object OAuthIntervals {
  private const val TAG = "BioSleepOAuth"
  private const val PREFS = "biosleep_oauth"
  private const val SCOPE = "ACTIVITY:READ,WELLNESS:WRITE,CALENDAR:WRITE,SETTINGS:WRITE"

  private val _stato = MutableStateFlow(StatoAccount())
  val stato: StateFlow<StatoAccount> = _stato.asStateFlow()

  /** Apre la pagina di autorizzazione di Intervals.icu in una Custom Tab. */
  fun avvia(context: Context) {
    val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .let { android.util.Base64.encodeToString(it, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING) }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString("nonce", nonce).putLong("nonce_ms", System.currentTimeMillis()).apply()
    val url =
        Uri.parse("https://intervals.icu/oauth/authorize").buildUpon()
            .appendQueryParameter("client_id", BuildConfig.INTERVALS_CLIENT_ID)
            .appendQueryParameter("redirect_uri", BuildConfig.OAUTH_REDIRECT_URI)
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("state", nonce)
            .build()
    _stato.value = StatoAccount(messaggio = "Autorizza ${BuildConfig.APP_NAME} su Intervals.icu…")
    CustomTabsIntent.Builder().build().launchUrl(context, url)
  }

  /** true se l'intent e' il ritorno dal Worker (App Link verificato sul suo dominio). */
  fun eRitorno(intent: Intent?): Boolean {
    val u = intent?.data ?: return false
    return intent.action == Intent.ACTION_VIEW && u.scheme == "https" && u.host == BuildConfig.OAUTH_HOST && u.path == "/app"
  }

  /**
   * Ritorno dal Worker: controlla lo state, salva il token e prepara i campi. Il nonce si cancella
   * in ogni caso: un ritorno vale una volta sola. Da chiamare fuori dal main thread.
   */
  fun gestisci(context: Context, uri: Uri) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val nonce = prefs.getString("nonce", null)
    val nonceMs = prefs.getLong("nonce_ms", 0L)
    prefs.edit().remove("nonce").remove("nonce_ms").apply()
    when (val r = RitornoOAuthParser.valuta(RitornoOAuthParser.parametri(uri.encodedFragment, uri.encodedQuery), nonce, nonceMs, System.currentTimeMillis())) {
      is RitornoOAuth.Rifiutato -> {
        Log.w(TAG, "Collegamento rifiutato: ${r.motivo}")
        _stato.value = StatoAccount(messaggio = "Collegamento non riuscito: ${r.motivo}")
      }
      is RitornoOAuth.Collegato -> {
        val s = IntervalsSettings(context)
        s.token = r.token
        s.atletaCollegato = r.atleta
        s.scopeCollegato = r.scope
        Log.i(TAG, "Collegato all'atleta ${r.atleta} (scope ${r.scope})")
        prepara(context)
      }
    }
  }

  /** cervello.prepara_account con le credenziali attuali; aggiorna lo stato per la schermata. */
  fun prepara(context: Context): EsitoPrepara? {
    val c = IntervalsSettings(context).credenziali ?: return null
    _stato.value = _stato.value.copy(inCorso = true, messaggio = "Preparo i campi ${BuildConfig.APP_NAME} su Intervals.icu…")
    val e = Cervello.preparaAccount(context, c)
    if (e.esito == EsitoPrepara.OK) ultimaVersionePreparata(context, BuildConfig.VERSION_CODE)
    _stato.value = StatoAccount(inCorso = false, esito = e)
    // onboarding: subito dopo i campi, il controllo delle soglie (card in Oggi e Profilo)
    if (e.esito == EsitoPrepara.OK) SoglieRepo.controlla(context, forza = true)
    return e
  }

  /** "Scollega": revoca il token su Intervals.icu, poi lo cancella dal telefono in ogni caso. */
  suspend fun scollega(context: Context): String =
      withContext(Dispatchers.IO) {
        val s = IntervalsSettings(context)
        val token = s.token
        val esito =
            if (token.isEmpty()) "Non collegato"
            else
                try {
                  val conn = URL("https://intervals.icu/api/v1/disconnect-app").openConnection() as HttpURLConnection
                  try {
                    conn.requestMethod = "DELETE"
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 15_000
                    conn.setRequestProperty("Authorization", "Bearer $token")
                    val code = conn.responseCode
                    if (code in 200..299 || code == 401) "Scollegato da Intervals.icu"
                    else "Token cancellato dal telefono; Intervals.icu ha risposto HTTP $code: rimuovi ${BuildConfig.APP_NAME} anche dalle app collegate del tuo account"
                  } finally {
                    conn.disconnect()
                  }
                } catch (e: IOException) {
                  "Token cancellato dal telefono, ma Intervals.icu non era raggiungibile: rimuovi ${BuildConfig.APP_NAME} dalle app collegate del tuo account"
                }
        s.scollegaLocale()
        _stato.value = StatoAccount(messaggio = esito)
        esito
      }

  private fun ultimaVersionePreparata(context: Context, versione: Int? = null): Int {
    val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (versione != null) p.edit().putInt("versione_preparata", versione).apply()
    return p.getInt("versione_preparata", -1)
  }

  /** A ogni aggiornamento dell'app: prepara_account una volta (non tocca i campi esistenti). */
  fun preparaSeAggiornata(context: Context) {
    if (!IntervalsSettings(context).isConfigured) return
    if (ultimaVersionePreparata(context) == BuildConfig.VERSION_CODE) return
    val richiesta =
        OneTimeWorkRequestBuilder<PreparaAccountWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
    WorkManager.getInstance(context).enqueueUniqueWork("prepara_account_${BuildConfig.VERSION_CODE}", ExistingWorkPolicy.KEEP, richiesta)
  }
}

class PreparaAccountWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val e = withContext(Dispatchers.IO) { OAuthIntervals.prepara(applicationContext) } ?: return Result.success()
    // errore di rete o del server: riprova piu' tardi; permesso mancante: serve l'utente
    return if (e.esito == EsitoPrepara.ERRORE) Result.retry() else Result.success()
  }
}
