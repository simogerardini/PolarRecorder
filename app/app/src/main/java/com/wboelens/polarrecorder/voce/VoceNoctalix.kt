package com.wboelens.polarrecorder.voce

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet
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

    /** Pagine che possono essere lette in automatico alla prima apertura. */
    enum class Pagina { COACH, NOTTI, OGGI, ETA }

    enum class Evento(val id: String) {
        REGISTRAZIONE_AVVIATA("ev_rec_avviata"),
        REGISTRAZIONE_CHIUSA("ev_rec_chiusa"),
        RIEPILOGO_PRONTO("ev_riepilogo_pronto"),
    }

    private val esecutore = Executors.newSingleThreadExecutor()
    private val generazione = AtomicInteger(0)
    private val principale = Handler(Looper.getMainLooper())
    private val ascoltatori = CopyOnWriteArraySet<(Boolean) -> Unit>()
    @Volatile private var attiva = false

    // ---------------------------------------------------------- impostazioni
    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun voceScelta(ctx: Context): Voce {
        val v = prefs(ctx).getString(CHIAVE_VOCE, Voce.FEMMINILE.name)
        return Voce.values().firstOrNull { it.name == v } ?: Voce.FEMMINILE
    }

    fun impostaVoce(ctx: Context, voce: Voce) {
        prefs(ctx).edit().putString(CHIAVE_VOCE, voce.name).apply()
        ferma()
        MotoreAudio.svuotaCache()
    }

    /** Toggle "leggi in automatico" della pagina (default: spento). */
    fun letturaAutomatica(ctx: Context, pagina: Pagina): Boolean =
        prefs(ctx).getBoolean("auto_${pagina.name}", false)

    fun impostaLetturaAutomatica(ctx: Context, pagina: Pagina, attivo: Boolean) {
        prefs(ctx).edit().putBoolean("auto_${pagina.name}", attivo).apply()
    }

    /** true se la voce non e' disattivata: la UI mostra il pulsante "ripeti" solo in questo caso. */
    fun voceAttiva(ctx: Context): Boolean = voceScelta(ctx) != Voce.DISATTIVATA

    /** Lingua del frasario: segue la lingua dell'app; inglese per le lingue non coperte. */
    fun lingua(ctx: Context): String {
        val conf = ctx.resources.configuration
        val lang = if (Build.VERSION.SDK_INT >= 24) conf.locales[0].language
        else @Suppress("DEPRECATION") conf.locale.language
        return when (lang) { "it" -> "it"; "es" -> "es"; "zh" -> "zh"; else -> "en" }
    }

    // ------------------------------------------------------------- lettura
    /**
     * Da chiamare all'apertura di una pagina. Legge SOLO se:
     * voce attiva + toggle della pagina acceso + contenuto mai letto prima.
     * chiaveContenuto identifica cio' che la pagina mostra (es. data della notte o del riepilogo):
     * a contenuto nuovo la pagina viene letta di nuovo una volta. Restituisce true se ha iniziato.
     */
    fun leggiAllApertura(
        ctx: Context, pagina: Pagina, chiaveContenuto: String,
        elementi: List<Elemento>, alFine: (() -> Unit)? = null,
    ): Boolean {
        if (!voceAttiva(ctx) || !letturaAutomatica(ctx, pagina)) return false
        val chiave = "letta_${pagina.name}_$chiaveContenuto"
        val p = prefs(ctx)
        if (p.getBoolean(chiave, false)) return false
        p.edit().putBoolean(chiave, true).apply()
        pulisciVecchie(ctx, pagina, chiave)
        parla(ctx, elementi, alFine, null)
        return true
    }

    /** Pulsante "ripeti" in alto nella pagina: legge sempre (se la voce e' attiva). */
    fun ripeti(ctx: Context, elementi: List<Elemento>, alFine: (() -> Unit)? = null) =
        parla(ctx, elementi, alFine, null)

    /** Compatibilita': legge subito il riepilogo della pagina OGGI. */
    fun leggiRiepilogo(ctx: Context, dati: DatiRiepilogoVocale, alFine: (() -> Unit)? = null) =
        parla(ctx, FrasarioRiepilogo.componi(dati), alFine, null)

    fun annuncia(ctx: Context, evento: Evento, alFine: (() -> Unit)? = null) =
        parla(ctx, listOf(Elemento.Seg(evento.id)), alFine, null)

    /** Anteprima di una voce nelle impostazioni (anche se non ancora salvata). */
    fun anteprima(ctx: Context, voce: Voce, alFine: (() -> Unit)? = null) =
        parla(ctx, listOf(Elemento.Seg("ev_benvenuto")), alFine, voce)

    fun inRiproduzione(): Boolean = attiva

    /** La UI si registra per aggiornare l'icona del pulsante (true = sta parlando). */
    fun aggiungiAscoltatore(a: (Boolean) -> Unit) { ascoltatori += a }
    fun rimuoviAscoltatore(a: (Boolean) -> Unit) { ascoltatori -= a }

    fun ferma() {
        generazione.incrementAndGet()
        MotoreAudio.ferma()
        if (attiva) { attiva = false; notifica(false) }
    }

    // ------------------------------------------------------------- interni
    private fun notifica(stato: Boolean) = principale.post { ascoltatori.forEach { it(stato) } }

    /** Tiene solo le ultime 60 chiavi "gia' letta" per pagina. */
    private fun pulisciVecchie(ctx: Context, pagina: Pagina, nuova: String) {
        val p = prefs(ctx)
        val prefisso = "letta_${pagina.name}_"
        val elenco = p.getString("storico_${pagina.name}", "")!!
            .split('\n').filter { it.isNotBlank() && it != nuova } + nuova
        val ed = p.edit()
        elenco.dropLast(60).forEach { if (it.startsWith(prefisso)) ed.remove(it) }
        ed.putString("storico_${pagina.name}", elenco.takeLast(60).joinToString("\n")).apply()
    }

    private fun parla(ctx: Context, elementi: List<Elemento>, alFine: (() -> Unit)?, forzata: Voce?) {
        val app = ctx.applicationContext
        val voce = forzata ?: voceScelta(app)
        val lingua = lingua(ctx)
        if (voce == Voce.DISATTIVATA) { alFine?.invoke(); return }
        ferma()
        val mia = generazione.get()
        attiva = true
        notifica(true)
        esecutore.execute {
            try {
                MotoreAudio.riproduci(app, "voce/${voce.cartella}/$lingua", elementi) {
                    generazione.get() != mia
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Riproduzione fallita", t)
            } finally {
                if (generazione.get() == mia) { attiva = false; notifica(false) }
                if (alFine != null) principale.post { alFine() }
            }
        }
    }
}
