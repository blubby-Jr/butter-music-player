package com.example.aimpmobile

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/** Налаштування візуалізацій (читаються з UI-потоку під час малювання). */
class VizSettings {
    /** 0 = стовпчики, 1 = осцилограф, 2 = аналоговий вимірювач, 3 = свої лінії */
    @Volatile var mode = 0
    @Volatile var fps = 60
    /** Швидкість «опадання» (0.5 швидко … 0.97 повільно) */
    @Volatile var fall = 0.86f
    @Volatile var bars = 32
    @Volatile var thicknessDp = 6
    @Volatile var color1 = Color.parseColor("#FF9800")
    @Volatile var color2 = Color.parseColor("#F44336")
    @Volatile var gradient = true
    @Volatile var rainbow = false
    @Volatile var mirror = false
    @Volatile var peaks = true
    @Volatile var rounded = true
}

class AppSettings(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val audio = AudioSettings()
    val viz = VizSettings()

    /** "auto" | "en" | "uk" | "ru" */
    var language = "auto"
    /** "dark" | "light" | "amoled" | "custom" */
    var theme = "dark"
    var customBg = Color.parseColor("#101418")
    var customText = Color.parseColor("#E8E8E8")
    var customAccent = Color.parseColor("#00BCD4")

    /** 0 = без повтору, 1 = повтор списку, 2 = повтор треку */
    var repeatMode = 0
    /** 0 = звичайний, 1 = зворотний, 2 = випадковий */
    var orderMode = 0
    var lastTrackId = -1L
    var lastPosMs = 0L

    init { load() }

    private fun load() {
        language = p.getString("language", "auto") ?: "auto"
        theme = p.getString("theme", "dark") ?: "dark"
        customBg = p.getInt("customBg", customBg)
        customText = p.getInt("customText", customText)
        customAccent = p.getInt("customAccent", customAccent)
        repeatMode = p.getInt("repeatMode", 0)
        orderMode = p.getInt("orderMode", 0)
        lastTrackId = p.getLong("lastTrackId", -1L)
        lastPosMs = p.getLong("lastPosMs", 0L)
        audio.limiterMode = p.getInt("limiterMode", 1)
        audio.ceilingDb = p.getFloat("ceilingDb", -0.3f)
        audio.preampDb = p.getFloat("preampDb", 0f)
        audio.autoHeadroom = p.getBoolean("autoHeadroom", true)
        audio.mono = p.getBoolean("mono", false)
        audio.masterVolume = p.getFloat("masterVolume", 1f)
        audio.quickTempo = p.getFloat("quickTempo", 1f)
        viz.mode = p.getInt("vizMode", 0)
        viz.fps = p.getInt("vizFps", 60)
        viz.fall = p.getFloat("vizFall", 0.86f)
        viz.bars = p.getInt("vizBars", 32)
        viz.thicknessDp = p.getInt("vizThick", 6)
        viz.color1 = p.getInt("vizColor1", viz.color1)
        viz.color2 = p.getInt("vizColor2", viz.color2)
        viz.gradient = p.getBoolean("vizGradient", true)
        viz.rainbow = p.getBoolean("vizRainbow", false)
        viz.mirror = p.getBoolean("vizMirror", false)
        viz.peaks = p.getBoolean("vizPeaks", true)
        viz.rounded = p.getBoolean("vizRounded", true)
    }

    fun save() {
        p.edit()
            .putString("language", language).putString("theme", theme)
            .putInt("customBg", customBg).putInt("customText", customText).putInt("customAccent", customAccent)
            .putInt("repeatMode", repeatMode).putInt("orderMode", orderMode)
            .putLong("lastTrackId", lastTrackId).putLong("lastPosMs", lastPosMs)
            .putInt("limiterMode", audio.limiterMode).putFloat("ceilingDb", audio.ceilingDb)
            .putFloat("preampDb", audio.preampDb).putBoolean("autoHeadroom", audio.autoHeadroom)
            .putBoolean("mono", audio.mono).putFloat("masterVolume", audio.masterVolume)
            .putFloat("quickTempo", audio.quickTempo)
            .putInt("vizMode", viz.mode).putInt("vizFps", viz.fps).putFloat("vizFall", viz.fall)
            .putInt("vizBars", viz.bars).putInt("vizThick", viz.thicknessDp)
            .putInt("vizColor1", viz.color1).putInt("vizColor2", viz.color2)
            .putBoolean("vizGradient", viz.gradient).putBoolean("vizRainbow", viz.rainbow)
            .putBoolean("vizMirror", viz.mirror).putBoolean("vizPeaks", viz.peaks)
            .putBoolean("vizRounded", viz.rounded)
            .apply()
    }
}
