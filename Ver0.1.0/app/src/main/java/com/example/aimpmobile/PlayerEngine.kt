package com.example.aimpmobile

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Кільцевий буфер останніх семплів — джерело даних для візуалізації. */
class VizBuffer {
    private val ring = FloatArray(4096)
    private var pos = 0

    @Synchronized
    fun push(stereo: FloatArray, frames: Int) {
        for (n in 0 until frames) {
            ring[pos] = (stereo[2 * n] + stereo[2 * n + 1]) * 0.5f
            pos = (pos + 1) and 4095
        }
    }

    @Synchronized
    fun latest(out: FloatArray) {
        var p = (pos - out.size) and 4095
        for (i in out.indices) {
            out[i] = ring[p]
            p = (p + 1) and 4095
        }
    }
}

/**
 * Аудіодвижок: MediaExtractor/MediaCodec (декодування) → DspChain (EQ, ефекти, автоматизація)
 * → AudioTrack (float PCM). Темп/питч/швидкість — через PlaybackParams AudioTrack.
 *
 * Усе важке працює в окремому потоці; UI лише міняє значення в TrackSettings.
 */
class PlayerEngine(private val ctx: Context) {
    interface Listener {
        fun onTrackEnded()
        fun onError(message: String)
    }

    var listener: Listener? = null
    val viz = VizBuffer()

    @Volatile var settings = TrackSettings()
    @Volatile var positionMs = 0L
    @Volatile var durationMs = 0L
    @Volatile var wantPlaying = false
    @Volatile private var paused = true
    @Volatile private var run = false
    @Volatile private var seekReqMs = -1L

    private var thread: Thread? = null
    private var track: Track? = null
    private val main = Handler(Looper.getMainLooper())

    private class Session {
        var at: AudioTrack? = null
        var playing = false
        var rate = 44100
        var builtRate = 0
        var bufMs = 150
        var lastSp = Float.NaN
        var lastPp = Float.NaN
    }

    /** Значення параметра з урахуванням автоматизації в момент t (мс від початку оригінального файлу). */
    fun valueAt(id: String, tMs: Long): Float {
        val s = settings
        val lane = s.lanes[id]
        if (lane != null) {
            val a = Automation.valueAt(lane, tMs)
            if (a != null) return a
        }
        return s.values[id] ?: Params.byId[id]?.def ?: 0f
    }

    fun load(t: Track, s: TrackSettings, autoplay: Boolean) {
        stopThread()
        track = t
        settings = s
        durationMs = t.durationMs
        positionMs = if (s.trimEnabled) s.trimStartMs else 0L
        paused = !autoplay
        wantPlaying = autoplay
        seekReqMs = -1L
        startThread()
    }

    fun play() {
        wantPlaying = true
        paused = false
        if (thread?.isAlive != true && track != null) {
            positionMs = if (settings.trimEnabled) settings.trimStartMs else 0L
            startThread()
        }
    }

    fun pause() {
        wantPlaying = false
        paused = true
    }

    fun toggle() {
        if (wantPlaying) pause() else play()
    }

    fun seekTo(ms: Long) {
        positionMs = ms
        seekReqMs = ms
    }

    fun release() {
        stopThread()
        wantPlaying = false
    }

    private fun startThread() {
        val t = track ?: return
        run = true
        thread = Thread({ decodeLoop(t) }, "aimp-engine").also { it.start() }
    }

    private fun stopThread() {
        run = false
        thread?.let {
            try { it.join(1500) } catch (e: InterruptedException) { }
        }
        thread = null
    }

    private fun fail(msg: String) {
        main.post { listener?.onError(msg) }
    }

    /** true = можна писати звук; false = потік зупиняють або є запит на seek. */
    private fun gate(ss: Session): Boolean {
        while (run) {
            if (seekReqMs >= 0) return false
            if (paused) {
                if (ss.playing) {
                    try { ss.at?.pause() } catch (e: Exception) { }
                    ss.playing = false
                }
                try { Thread.sleep(15) } catch (e: InterruptedException) { }
                continue
            }
            if (!ss.playing) {
                val a = ss.at
                if (a != null) {
                    try { a.play() } catch (e: Exception) { }
                    ss.playing = true
                    ss.lastSp = Float.NaN
                    ss.lastPp = Float.NaN
                }
            }
            return true
        }
        return false
    }

    private fun applyRate(ss: Session, sp: Float, pp: Float) {
        val a = ss.at ?: return
        if (!ss.playing) return
        val s = sp.coerceIn(0.25f, 4f)
        val p = pp.coerceIn(0.25f, 4f)
        if (abs(s - ss.lastSp) < 0.002f && abs(p - ss.lastPp) < 0.002f) return
        try {
            a.playbackParams = PlaybackParams().setSpeed(s).setPitch(p)
            ss.lastSp = s
            ss.lastPp = p
        } catch (e: Exception) {
            // деякі пристрої не підтримують частину діапазону
        }
    }

    private fun buildTrack(ss: Session): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(ss.rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val bytes = max(minBuf * 3, ss.rate * 8 * 15 / 100)
        ss.bufMs = (bytes / 8L * 1000L / ss.rate).toInt()
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(ss.rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun decodeLoop(t: Track) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        val ss = Session()
        var natural = false
        try {
            ex.setDataSource(ctx, t.uri, null)
            var idx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { idx = i; fmt = f; break }
            }
            if (idx < 0 || fmt == null) { fail("Файл не містить аудіодоріжки"); return }
            ex.selectTrack(idx)
            val cd = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = cd
            cd.configure(fmt, null, null, 0)
            cd.start()

            ss.rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false
            var chain: DspChain? = null
            var stereo = FloatArray(8192)
            var skipBeforeUs = 0L
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()

            val first = if (settings.trimEnabled) settings.trimStartMs else 0L
            if (first > 0) {
                ex.seekTo(first * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                skipBeforeUs = first * 1000
            }

            while (run && !outputDone) {
                val sr = seekReqMs
                if (sr >= 0) {
                    seekReqMs = -1L
                    cd.flush()
                    ex.seekTo(sr * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    inputDone = false
                    skipBeforeUs = sr * 1000
                    ss.at?.let { try { it.pause(); it.flush() } catch (e: Exception) { } }
                    ss.playing = false
                }
                if (!gate(ss)) continue

                if (!inputDone) {
                    val ii = cd.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val ib = cd.getInputBuffer(ii)!!
                        val n = ex.readSampleData(ib, 0)
                        if (n < 0) {
                            cd.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            cd.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }

                val oi = cd.dequeueOutputBuffer(info, 5000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = cd.outputFormat
                    ss.rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcmFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    if (ss.at != null && ss.builtRate != ss.rate) {
                        try { ss.at?.release() } catch (e: Exception) { }
                        ss.at = null
                        ss.playing = false
                        chain = null
                    }
                } else if (oi >= 0) {
                    val ptsUs = info.presentationTimeUs
                    val ob = cd.getOutputBuffer(oi)!!
                    val endMs = settings.trimEndMs
                    if (settings.trimEnabled && endMs > 0 && ptsUs / 1000 >= endMs) {
                        outputDone = true
                        natural = true
                    } else if (info.size > 0 && ptsUs >= skipBeforeUs) {
                        ob.order(ByteOrder.nativeOrder())
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val frames: Int
                        if (pcmFloat) {
                            frames = info.size / (4 * ch)
                            if (stereo.size < frames * 2) stereo = FloatArray(frames * 2)
                            val fb = ob.asFloatBuffer()
                            for (n in 0 until frames) {
                                val l = fb.get()
                                var r = l
                                if (ch > 1) { r = fb.get(); for (k in 2 until ch) fb.get() }
                                stereo[2 * n] = l
                                stereo[2 * n + 1] = r
                            }
                        } else {
                            frames = info.size / (2 * ch)
                            if (stereo.size < frames * 2) stereo = FloatArray(frames * 2)
                            val sb = ob.asShortBuffer()
                            for (n in 0 until frames) {
                                val l = sb.get() / 32768f
                                var r = l
                                if (ch > 1) { r = sb.get() / 32768f; for (k in 2 until ch) sb.get() }
                                stereo[2 * n] = l
                                stereo[2 * n + 1] = r
                            }
                        }

                        if (ss.at == null) {
                            ss.at = buildTrack(ss)
                            ss.builtRate = ss.rate
                            ss.playing = false
                            chain = DspChain(ss.rate)
                        }
                        val c = chain ?: DspChain(ss.rate).also { chain = it }
                        val tr = ss.at!!
                        val tMs = ptsUs / 1000
                        c.eq.bypass = settings.eqBypass
                        c.process(stereo, frames) { id -> valueAt(id, tMs) }
                        viz.push(stereo, frames)

                        val sp = valueAt("effect.speed", tMs)
                        val tp = valueAt("effect.tempo", tMs)
                        val pi = valueAt("effect.pitch_st", tMs)
                        // швидкість (resample) змінює і темп, і висоту; темп — лише тривалість; пітч — лише висоту
                        val playSpeed = sp * tp
                        val playPitch = sp * 2.0.pow(pi / 12.0).toFloat()

                        var off = 0
                        while (off < frames) {
                            if (!gate(ss)) break
                            val n = min(2048, frames - off)
                            positionMs = tMs + off * 1000L / ss.rate
                            applyRate(ss, playSpeed, playPitch)
                            val w = tr.write(stereo, off * 2, n * 2, AudioTrack.WRITE_BLOCKING)
                            if (w < 0) { fail("Помилка виводу звуку ($w)"); run = false; break }
                            off += n
                        }
                    }
                    cd.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                        natural = true
                    }
                }
            }

            if (natural && run && ss.playing) {
                // дати програтися залишку буфера
                try { Thread.sleep(min(ss.bufMs + 50, 700).toLong()) } catch (e: InterruptedException) { }
            }
            if (natural && run) main.post { listener?.onTrackEnded() }
        } catch (e: Exception) {
            if (run) fail("Не вдалося відтворити файл: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            try { codec?.stop() } catch (e: Exception) { }
            try { codec?.release() } catch (e: Exception) { }
            try { ex.release() } catch (e: Exception) { }
            ss.at?.let {
                try { it.stop() } catch (e: Exception) { }
                try { it.release() } catch (e: Exception) { }
            }
        }
    }
}

/** Швидке отримання «піків» для малювання хвилі в редакторі (повне декодування у фоні). */
object WaveformExtractor {
    fun extract(ctx: Context, t: Track, bins: Int, cancelled: () -> Boolean): FloatArray? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, t.uri, null)
            var idx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { idx = i; fmt = f; break }
            }
            if (idx < 0 || fmt == null) return null
            ex.selectTrack(idx)
            val cd = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = cd
            cd.configure(fmt, null, null, 0)
            cd.start()
            val durUs = max(1L, t.durationMs * 1000)
            val peaks = FloatArray(bins)
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            var pcmFloat = false
            while (!outDone) {
                if (cancelled()) return null
                if (!inDone) {
                    val ii = cd.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val ib = cd.getInputBuffer(ii)!!
                        val n = ex.readSampleData(ib, 0)
                        if (n < 0) {
                            cd.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            cd.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = cd.dequeueOutputBuffer(info, 5000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = cd.outputFormat
                    pcmFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (oi >= 0) {
                    if (info.size > 0) {
                        val ob = cd.getOutputBuffer(oi)!!
                        ob.order(ByteOrder.nativeOrder())
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        var m = 0f
                        if (pcmFloat) {
                            val fb = ob.asFloatBuffer()
                            while (fb.hasRemaining()) m = max(m, abs(fb.get()))
                        } else {
                            val sb = ob.asShortBuffer()
                            while (sb.hasRemaining()) m = max(m, abs(sb.get() / 32768f))
                        }
                        val bin = (info.presentationTimeUs * bins / durUs).toInt().coerceIn(0, bins - 1)
                        if (m > peaks[bin]) peaks[bin] = m
                    }
                    cd.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            val mx = peaks.maxOrNull() ?: 0f
            if (mx > 0f) for (i in peaks.indices) peaks[i] /= mx
            return peaks
        } catch (e: Exception) {
            return null
        } finally {
            try { codec?.stop() } catch (e: Exception) { }
            try { codec?.release() } catch (e: Exception) { }
            try { ex.release() } catch (e: Exception) { }
        }
    }
}
