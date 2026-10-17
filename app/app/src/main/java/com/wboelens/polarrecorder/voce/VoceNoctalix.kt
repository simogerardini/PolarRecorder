package com.wboelens.polarrecorder.voce

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Voce di NoctaliX (Parte 10).
 * Frasario a segmenti pre-generato con ElevenLabs: assets/voce/<f|m>/<lingua>/<id>.mp3.
 * Nessuna rete, nessun costo per lettura. File generato da installa_voce.py: non modificare a mano.
 */
object VoceNoctalix {
    private const val TAG = "VoceNoctalix"
    private const val PREFS = "noctalix_voce"
    private const val CHIAVE_VOCE = "voce"

    enum class Voce(val cartella: String) { FEMMINILE("f"), MASCHILE("m"), DISATTIVATA("") }

    enum class Evento(val id: String) {
        REGISTRAZIONE_AVVIATA("ev_rec_avviata"),
        REGISTRAZIONE_CHIUSA("ev_rec_chiusa"),
        RIEPILOGO_PRONTO("ev_riepilogo_pronto"),
    }

    private val esecutore = Executors.newSingleThreadExecutor()
    private val generazione = AtomicInteger(0)
    private val principale = Handler(Looper.getMainLooper())

    fun voceScelta(ctx: Context): Voce {
        val v = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CHIAVE_VOCE, Voce.FEMMINILE.name)
        return Voce.values().firstOrNull { it.name == v } ?: Voce.FEMMINILE
    }

    fun impostaVoce(ctx: Context, voce: Voce) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(CHIAVE_VOCE, voce.name).apply()
        ferma()
        MotoreAudio.svuotaCache()
    }

    /** Lingua del frasario: segue la lingua dell'app; inglese per le lingue non coperte. */
    fun lingua(ctx: Context): String {
        val conf = ctx.resources.configuration
        val lang = if (Build.VERSION.SDK_INT >= 24) conf.locales[0].language
        else @Suppress("DEPRECATION") conf.locale.language
        return when (lang) { "it" -> "it"; "es" -> "es"; "zh" -> "zh"; else -> "en" }
    }

    /** Legge il riepilogo del mattino. Passa il contesto dell'Activity (lingua dell'app). */
    fun leggiRiepilogo(ctx: Context, dati: DatiRiepilogoVocale, alFine: (() -> Unit)? = null) =
        parla(ctx, FrasarioRiepilogo.componi(dati), alFine, null)

    fun annuncia(ctx: Context, evento: Evento, alFine: (() -> Unit)? = null) =
        parla(ctx, listOf(Elemento.Seg(evento.id)), alFine, null)

    /** Anteprima di una voce nelle impostazioni (anche se non ancora salvata). */
    fun anteprima(ctx: Context, voce: Voce, alFine: (() -> Unit)? = null) =
        parla(ctx, listOf(Elemento.Seg("ev_benvenuto")), alFine, voce)

    fun ferma() {
        generazione.incrementAndGet()
        MotoreAudio.ferma()
    }

    private fun parla(ctx: Context, elementi: List<Elemento>, alFine: (() -> Unit)?, forzata: Voce?) {
        val app = ctx.applicationContext
        val voce = forzata ?: voceScelta(app)
        val lingua = lingua(ctx)
        if (voce == Voce.DISATTIVATA) { alFine?.invoke(); return }
        ferma()
        val mia = generazione.get()
        esecutore.execute {
            try {
                MotoreAudio.riproduci(app, "voce/${voce.cartella}/$lingua", elementi) {
                    generazione.get() != mia
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Riproduzione fallita", t)
            } finally {
                if (alFine != null) principale.post { alFine() }
            }
        }
    }
}
