package com.wboelens.polarrecorder.voce

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Decodifica i segmenti MP3, toglie i silenzi ai bordi e li concatena in un unico
 * flusso PCM riprodotto con AudioTrack (niente buchi tra un segmento e l'altro).
 * File generato da installa_voce.py.
 */
internal object MotoreAudio {
    private const val TAG = "VoceNoctalix"
    private const val SOGLIA = 400          // ampiezza (16 bit) oltre cui c'e' voce
    private const val STACCO_MS = 60        // micro-pausa tra segmenti consecutivi

    private class Clip(val pcm: ShortArray, val sr: Int)

    private val cache = HashMap<String, Clip>()
    private val elenchi = HashMap<String, Set<String>>()
    @Volatile private var tracciaCorrente: AudioTrack? = null

    @Synchronized fun svuotaCache() { cache.clear(); elenchi.clear() }

    fun ferma() {
        tracciaCorrente?.let { runCatching { it.pause(); it.flush(); it.stop() } }
    }

    fun riproduci(ctx: Context, cartella: String, elementi: List<Elemento>, annullato: () -> Boolean) {
        val presenti = elenco(ctx, cartella)
        val pezzi = ArrayList<ShortArray>()
        var sr = 0
        for (el in elementi) {
            if (annullato()) return
            when (el) {
                is Elemento.Seg -> {
                    if ("${el.id}.mp3" !in presenti) {
                        Log.d(TAG, "Segmento assente: $cartella/${el.id}")
                        continue
                    }
                    val c = clip(ctx, "$cartella/${el.id}.mp3") ?: continue
                    if (sr == 0) sr = c.sr
                    if (c.sr != sr) { Log.w(TAG, "Frequenza diversa in ${el.id}: ${c.sr} vs $sr"); continue }
                    pezzi += c.pcm
                    pezzi += ShortArray(sr * STACCO_MS / 1000)
                }
                is Elemento.Pausa -> if (sr > 0) pezzi += ShortArray(sr * el.ms / 1000)
            }
        }
        val totale = pezzi.sumOf { it.size }
        if (totale == 0 || sr == 0) { Log.w(TAG, "Niente da leggere in $cartella"); return }
        val audio = ShortArray(totale)
        var pos = 0
        for (p in pezzi) { System.arraycopy(p, 0, audio, pos, p.size); pos += p.size }
        if (annullato()) return
        suona(ctx, audio, sr, annullato)
    }

    private fun suona(ctx: Context, audio: ShortArray, sr: Int, annullato: () -> Boolean) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focus = richiediFocus(am, attr)
        val traccia = AudioTrack.Builder()
            .setAudioAttributes(attr)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(audio.size * 2)
            .build()
        try {
            traccia.write(audio, 0, audio.size)
            tracciaCorrente = traccia
            traccia.play()
            val limite = System.currentTimeMillis() + audio.size * 1000L / sr + 1000
            while (!annullato() &&
                traccia.playState == AudioTrack.PLAYSTATE_PLAYING &&
                traccia.playbackHeadPosition < audio.size &&
                System.currentTimeMillis() < limite
            ) Thread.sleep(50)
        } finally {
            tracciaCorrente = null
            runCatching { traccia.stop() }
            traccia.release()
            rilasciaFocus(am, focus)
        }
    }

    private fun richiediFocus(am: AudioManager, attr: AudioAttributes): Any? =
        if (Build.VERSION.SDK_INT >= 26) {
            val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attr).build()
            am.requestAudioFocus(r)
            r
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            null
        }

    private fun rilasciaFocus(am: AudioManager, focus: Any?) {
        if (Build.VERSION.SDK_INT >= 26 && focus is AudioFocusRequest) am.abandonAudioFocusRequest(focus)
        else @Suppress("DEPRECATION") am.abandonAudioFocus(null)
    }

    @Synchronized private fun elenco(ctx: Context, cartella: String): Set<String> =
        elenchi.getOrPut(cartella) {
            try { ctx.assets.list(cartella)?.toSet() ?: emptySet() } catch (e: IOException) { emptySet() }
        }

    @Synchronized private fun clip(ctx: Context, percorso: String): Clip? {
        cache[percorso]?.let { return it }
        val c: Clip? = try {
            decodifica(ctx, percorso)
        } catch (t: Throwable) {
            Log.w(TAG, "Decodifica fallita: $percorso", t)
            null
        }
        if (c == null) return null
        cache[percorso] = c
        return c
    }

    private fun decodifica(ctx: Context, percorso: String): Clip? {
        val afd = ctx.assets.openFd(percorso)
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            val tr = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            ex.selectTrack(tr)
            val fmt = ex.getTrackFormat(tr)
            var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var canali = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = dec
            dec.configure(fmt, null, null, 0)
            dec.start()

            val blocchi = ArrayList<ShortArray>()
            val info = MediaCodec.BufferInfo()
            var fineIn = false
            var fineOut = false
            while (!fineOut) {
                if (!fineIn) {
                    val i = dec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = dec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            fineIn = true
                        } else {
                            dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val ob = dec.getOutputBuffer(o)!!
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    val dati = ob.slice().order(ByteOrder.nativeOrder())
                    if (float) {
                        val fb = dati.asFloatBuffer()
                        blocchi += ShortArray(fb.remaining()) {
                            (fb.get().coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                        }
                    } else {
                        val sb = dati.asShortBuffer()
                        val s = ShortArray(sb.remaining()); sb.get(s); blocchi += s
                    }
                    dec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) fineOut = true
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = dec.outputFormat
                    sr = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    canali = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (Build.VERSION.SDK_INT >= 24 && nf.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        float = nf.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                }
            }
            var pcm = ShortArray(blocchi.sumOf { it.size })
            var p = 0
            for (b in blocchi) { System.arraycopy(b, 0, pcm, p, b.size); p += b.size }
            if (canali == 2) pcm = ShortArray(pcm.size / 2) { ((pcm[2 * it] + pcm[2 * it + 1]) / 2).toShort() }
            return Clip(rifila(pcm, sr), sr)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
            afd.close()
        }
    }

    /** Toglie il silenzio iniziale/finale (padding MP3 compreso) e ammorbidisce i bordi. */
    private fun rifila(pcm: ShortArray, sr: Int): ShortArray {
        if (pcm.isEmpty()) return pcm
        val fin = maxOf(1, sr / 100)
        fun voce(da: Int): Boolean {
            val a = maxOf(0, da); val b = minOf(pcm.size, a + fin)
            for (k in a until b) if (abs(pcm[k].toInt()) > SOGLIA) return true
            return false
        }
        var inizio = 0
        while (inizio < pcm.size && !voce(inizio)) inizio += fin
        var fine = pcm.size
        while (fine > inizio && !voce(fine - fin)) fine -= fin
        if (fine <= inizio) return pcm
        val margine = sr * 15 / 1000
        inizio = maxOf(0, inizio - margine)
        fine = minOf(pcm.size, fine + margine)
        val out = pcm.copyOfRange(inizio, fine)
        val sfuma = minOf(out.size / 2, sr * 5 / 1000)
        for (k in 0 until sfuma) {
            val g = k.toFloat() / sfuma
            out[k] = (out[k] * g).toInt().toShort()
            out[out.size - 1 - k] = (out[out.size - 1 - k] * g).toInt().toShort()
        }
        return out
    }
}
