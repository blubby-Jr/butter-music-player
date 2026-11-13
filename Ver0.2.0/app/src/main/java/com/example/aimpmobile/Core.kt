package com.example.aimpmobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max

/** Єдина точка доступу до глобального стану додатка (переживає перестворення Activity). */
object Core {
    lateinit var app: Context
    lateinit var prefs: AppSettings
    lateinit var playlists: PlaylistStore
    val engine: PlayerEngine by lazy { PlayerEngine(app).also { it.audio = prefs.audio } }
    private var inited = false

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        prefs = AppSettings(app)
        playlists = PlaylistStore(app)
        applyAppearance()
        Controller.init()
    }

    /** Мова та тема з поточних налаштувань. */
    fun applyAppearance() {
        S.setLang(prefs.language)
        Theme.apply(prefs)
    }
}

/**
 * Логіка відтворення на рівні додатка: черга, повтор, порядок, аудіофокус, таймер сну.
 * Працює і тоді, коли Activity згорнуто/знищено (музика продовжує грати завдяки сервісу).
 */
object Controller : PlayerEngine.Listener {
    interface Ui {
        fun onChanged()
        fun onMessage(msg: String)
    }

    var ui: Ui? = null
    val observers = CopyOnWriteArrayList<() -> Unit>()
    var all: List<Track> = emptyList()
    var current: Track? = null
    var settings = TrackSettings()
    private var source: List<Track> = emptyList()
    private var queue: List<Track> = emptyList()
    private val main = Handler(Looper.getMainLooper())
    private val engine get() = Core.engine
    private val prefs get() = Core.prefs

    /** Поки відкритий Editor — трек крутиться по колу, а не перемикається. */
    var editorOpen = false
        set(v) {
            field = v
            updateLoop()
        }

    val isPlaying: Boolean get() = engine.wantPlaying

    private var focusReq: AudioFocusRequest? = null
    private var resumeOnGain = false
    private var sleepAt = 0L
    val sleepRemainingMs: Long get() = if (sleepAt > 0) max(0L, sleepAt - SystemClock.elapsedRealtime()) else 0L

    fun init() {
        engine.listener = this
        updateLoop()
        val noisy = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
            }
        }
        ContextCompat.registerReceiver(
            Core.app, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun updateLoop() {
        engine.loop = editorOpen || prefs.repeatMode == 2
    }

    fun notifyChanged() {
        ui?.onChanged()
        for (o in observers) o()
    }

    // ---- бібліотека та відновлення стану
    fun libraryLoaded(list: List<Track>) {
        all = list
        current?.let { c -> list.firstOrNull { it.id == c.id }?.let { current = it } } // оновлені теги
        if (current == null && prefs.lastTrackId > 0) {
            val t = list.firstOrNull { it.id == prefs.lastTrackId }
            if (t != null) {
                source = list
                buildQueue(t)
                startTrack(t, false, prefs.lastPosMs)
            }
        }
    }

    /** Перезавантажити поточний трек (після зміни тегів файл переписано) з тієї ж позиції, на паузі. */
    fun reloadCurrent(posMs: Long) {
        val c = current ?: return
        startTrack(all.firstOrNull { it.id == c.id } ?: c, false, posMs)
    }

    // ---- керування
    fun play(t: Track, list: List<Track>) {
        source = list
        buildQueue(t)
        startTrack(t, true, -1L)
    }

    private fun startTrack(t: Track, autoplay: Boolean, startMs: Long) {
        saveSettings()
        current = t
        settings = TrackStore.load(Core.app, t)
        if (autoplay) requestFocus()
        engine.duck = 1f
        engine.load(t, settings, autoplay, startMs)
        prefs.lastTrackId = t.id
        prefs.lastPosMs = if (startMs > 0) startMs else 0L
        prefs.save()
        if (autoplay) startService()
        notifyChanged()
    }

    private fun buildQueue(first: Track?) {
        queue = when (prefs.orderMode) {
            1 -> source.reversed()
            2 -> {
                val l = source.shuffled().toMutableList()
                if (first != null && l.remove(first)) l.add(0, first)
                l
            }
            else -> source
        }
    }

    fun toggle() {
        if (current == null) {
            val t = all.firstOrNull() ?: return
            play(t, all)
        } else if (engine.wantPlaying) pause() else resume()
    }

    fun resume() {
        requestFocus()
        engine.play()
        startService()
        notifyChanged()
    }

    fun pause() {
        engine.pause()
        persistPos()
        notifyChanged()
    }

    fun next(auto: Boolean) {
        val q = if (queue.isEmpty()) all else queue
        if (q.isEmpty()) return
        val i = q.indexOfFirst { it.id == current?.id }
        var n = i + 1
        if (n >= q.size) {
            if (auto && prefs.repeatMode == 0) {
                current?.let { startTrack(it, false, -1L) } // кінець списку: зупиняємось на початку
                return
            }
            n = 0
        }
        startTrack(q[n], true, -1L)
    }

    fun prev() {
        val start = settings.active(engine.durationMs).first().start
        if (engine.positionMs - start > 3000) {
            engine.seekTo(start)
            return
        }
        val q = if (queue.isEmpty()) all else queue
        if (q.isEmpty()) return
        val i = q.indexOfFirst { it.id == current?.id }
        startTrack(q[if (i <= 0) q.size - 1 else i - 1], true, -1L)
    }

    fun stopAll() {
        saveSettings()
        persistPos()
        engine.release()
        current = null
        abandonFocus()
        Core.app.stopService(Intent(Core.app, PlaybackService::class.java))
        notifyChanged()
    }

    fun setOrder(mode: Int) {
        prefs.orderMode = mode
        prefs.save()
        buildQueue(current)
        notifyChanged()
    }

    fun setRepeat(mode: Int) {
        prefs.repeatMode = mode
        prefs.save()
        updateLoop()
        notifyChanged()
    }

    fun setQuickTempo(v: Float) {
        prefs.audio.quickTempo = v
        prefs.save()
        notifyChanged()
    }

    fun setVolume(v: Float) {
        prefs.audio.masterVolume = v.coerceIn(0f, 1f)
        prefs.save()
    }

    // ---- збереження
    fun saveSettings() {
        current?.let { TrackStore.save(Core.app, it, settings) }
    }

    fun persistPos() {
        if (current != null) {
            prefs.lastPosMs = engine.positionMs
            prefs.save()
        }
    }

    // ---- таймер сну (останні 10 секунд — плавне затухання)
    private val sleepTick = object : Runnable {
        override fun run() {
            if (sleepAt <= 0) return
            val left = sleepAt - SystemClock.elapsedRealtime()
            if (left <= 0) {
                sleepAt = 0
                pause()
                engine.duck = 1f
                return
            }
            if (left < 10_000) engine.duck = left / 10_000f
            main.postDelayed(this, 500)
        }
    }

    fun setSleep(minutes: Int) {
        main.removeCallbacks(sleepTick)
        engine.duck = 1f
        if (minutes <= 0) sleepAt = 0 else {
            sleepAt = SystemClock.elapsedRealtime() + minutes * 60_000L
            main.postDelayed(sleepTick, 500)
        }
        notifyChanged()
    }

    // ---- аудіофокус
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { resumeOnGain = false; engine.pause(); notifyChanged() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { resumeOnGain = engine.wantPlaying; engine.pause(); notifyChanged() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> engine.duck = 0.3f
            AudioManager.AUDIOFOCUS_GAIN -> {
                engine.duck = 1f
                if (resumeOnGain) { resumeOnGain = false; engine.play(); notifyChanged() }
            }
        }
    }

    private fun audioManager() = Core.app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun requestFocus() {
        val req = focusReq ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build().also { focusReq = it }
        audioManager().requestAudioFocus(req)
    }

    private fun abandonFocus() {
        focusReq?.let { audioManager().abandonAudioFocusRequest(it) }
    }

    private fun startService() {
        ContextCompat.startForegroundService(Core.app, Intent(Core.app, PlaybackService::class.java))
    }

    // ---- події двигуна
    override fun onTrackEnded() {
        next(true)
    }

    override fun onError(message: String) {
        ui?.onMessage(message)
    }
}
