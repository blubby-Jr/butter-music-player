package com.example.aimpmobile

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Двоканальний біквад-фільтр (формули RBJ Audio EQ Cookbook). */
class Biquad {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private val z1 = DoubleArray(2)
    private val z2 = DoubleArray(2)
    var active = false

    private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
        b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0
        a1 = na1 / na0; a2 = na2 / na0
    }

    fun peaking(fs: Double, f: Double, q: Double, gainDb: Double) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f / fs
        val alpha = sin(w0) / (2.0 * q)
        val c = cos(w0)
        set(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
    }

    fun lowShelf(fs: Double, f: Double, gainDb: Double) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f / fs
        val c = cos(w0)
        val alpha = sin(w0) / 2.0 * sqrt(2.0)
        val sa = 2.0 * sqrt(a) * alpha
        set(
            a * ((a + 1) - (a - 1) * c + sa),
            2 * a * ((a - 1) - (a + 1) * c),
            a * ((a + 1) - (a - 1) * c - sa),
            (a + 1) + (a - 1) * c + sa,
            -2 * ((a - 1) + (a + 1) * c),
            (a + 1) + (a - 1) * c - sa
        )
    }

    fun process(x: Float, ch: Int): Float {
        val xd = x.toDouble()
        val y = b0 * xd + z1[ch]
        z1[ch] = b1 * xd - a1 * y + z2[ch]
        z2[ch] = b2 * xd - a2 * y
        return y.toFloat()
    }
}

/** 18-смуговий параметричний еквалайзер (каскад peaking-фільтрів, Q ≈ 1.4). */
class Equalizer(private val fs: Double) {
    private val bands = Array(18) { Biquad() }
    private val gains = DoubleArray(18)
    var bypass = false

    fun setGain(i: Int, db: Double) {
        if (abs(db - gains[i]) < 0.01) return
        gains[i] = db
        val f = Params.EQ_FREQS[i].toDouble()
        bands[i].active = abs(db) > 0.05 && f < fs * 0.45
        if (bands[i].active) bands[i].peaking(fs, f, 1.4, db)
    }

    fun process(buf: FloatArray, frames: Int) {
        if (bypass) return
        for (b in bands) {
            if (!b.active) continue
            for (n in 0 until frames) {
                buf[2 * n] = b.process(buf[2 * n], 0)
                buf[2 * n + 1] = b.process(buf[2 * n + 1], 1)
            }
        }
    }
}

class Echo(private val fs: Int) {
    private val size = fs
    private val buf = Array(2) { FloatArray(size) }
    private var pos = 0
    private var dirty = false

    fun process(b: FloatArray, frames: Int, mix: Float) {
        if (mix < 0.001f) {
            if (dirty) { buf[0].fill(0f); buf[1].fill(0f); dirty = false }
            return
        }
        dirty = true
        val d = (0.35 * fs).toInt()
        val fb = 0.42f
        for (n in 0 until frames) {
            val rp = (pos - d + size) % size
            for (ch in 0..1) {
                val x = b[2 * n + ch]
                val dl = buf[ch][rp]
                buf[ch][pos] = x + dl * fb
                b[2 * n + ch] = x + dl * mix
            }
            pos = (pos + 1) % size
        }
    }
}

/** Лінія затримки з LFO — основа Chorus і Flanger. */
class ModDelay(
    private val fs: Int,
    private val baseMs: Float,
    private val depthMs: Float,
    private val rateHz: Float,
    private val feedback: Float
) {
    private val size = (fs * 0.06).toInt() + 8
    private val buf = Array(2) { FloatArray(size) }
    private var pos = 0
    private var phase = 0.0
    private var dirty = false

    fun process(b: FloatArray, frames: Int, mix: Float) {
        if (mix < 0.001f) {
            if (dirty) { buf[0].fill(0f); buf[1].fill(0f); dirty = false }
            return
        }
        dirty = true
        val inc = 2.0 * PI * rateHz / fs
        for (n in 0 until frames) {
            for (ch in 0..1) {
                val ph = phase + ch * (PI / 2)
                val d = (baseMs + depthMs * sin(ph)) * fs / 1000.0
                var rp = pos - d
                while (rp < 0) rp += size
                val i0 = floor(rp).toInt() % size
                val i1 = (i0 + 1) % size
                val frac = (rp - floor(rp)).toFloat()
                val dl = buf[ch][i0] * (1f - frac) + buf[ch][i1] * frac
                val x = b[2 * n + ch]
                buf[ch][pos] = x + dl * feedback
                b[2 * n + ch] = x + dl * mix
            }
            phase += inc
            pos = (pos + 1) % size
        }
    }
}

/** Реверберація за схемою Шредера/Freeverb: 4 comb + 2 all-pass на канал. */
class Reverb(fs: Int) {
    private class Comb(n: Int) {
        val buf = FloatArray(n)
        var i = 0
        var store = 0f
        fun p(x: Float, fb: Float, damp: Float): Float {
            val o = buf[i]
            store = o * (1f - damp) + store * damp
            buf[i] = x + store * fb
            i = (i + 1) % buf.size
            return o
        }
    }

    private class AllPass(n: Int) {
        val buf = FloatArray(n)
        var i = 0
        fun p(x: Float): Float {
            val b = buf[i]
            val o = -x + b
            buf[i] = x + b * 0.5f
            i = (i + 1) % buf.size
            return o
        }
    }

    private val scale = fs / 44100.0
    private val tun = intArrayOf(1116, 1188, 1277, 1356)
    private val combs = Array(2) { ch -> Array(4) { Comb(((tun[it] + ch * 23) * scale).toInt().coerceAtLeast(8)) } }
    private val aps = Array(2) { ch -> arrayOf(AllPass(((556 + ch * 23) * scale).toInt()), AllPass(((441 + ch * 23) * scale).toInt())) }
    private var dirty = false

    fun process(b: FloatArray, frames: Int, mix: Float) {
        if (mix < 0.001f) {
            if (dirty) {
                for (ch in 0..1) {
                    for (c in combs[ch]) { c.buf.fill(0f); c.store = 0f }
                    for (a in aps[ch]) a.buf.fill(0f)
                }
                dirty = false
            }
            return
        }
        dirty = true
        for (n in 0 until frames) {
            val inp = (b[2 * n] + b[2 * n + 1]) * 0.5f * 0.12f
            for (ch in 0..1) {
                var s = 0f
                for (c in combs[ch]) s += c.p(inp, 0.84f, 0.25f)
                for (a in aps[ch]) s = a.p(s)
                b[2 * n + ch] += s * mix * 3f
            }
        }
    }
}

/** Лімітер: не дає сигналу піднятися вище ≈ -0.3 dBFS (без кліпінгу). */
class Limiter {
    private var g = 1f
    fun process(b: FloatArray, count: Int) {
        val ceil = 0.97f
        for (i in 0 until count) {
            val a = abs(b[i])
            val need = if (a > ceil) ceil / a else 1f
            if (need < g) g = need else g += (1f - g) * 0.0005f
            var y = b[i] * g
            if (y > 1f) y = 1f else if (y < -1f) y = -1f
            b[i] = y
        }
    }
}

/**
 * Весь ланцюжок обробки: EQ → бас → хорус → фланжер → ехо → реверб → стереобаза → гучність → лімітер.
 * Значення параметрів (з урахуванням автоматизації) беруться через get(id).
 */
class DspChain(private val fs: Int) {
    val eq = Equalizer(fs.toDouble())
    private val bass = Biquad()
    private var bassDb = -1.0
    private val chorus = ModDelay(fs, 20f, 4f, 0.8f, 0f)
    private val flanger = ModDelay(fs, 3f, 2.5f, 0.3f, 0.55f)
    private val echo = Echo(fs)
    private val reverb = Reverb(fs)
    private val limiter = Limiter()
    private var prevVol = 1f

    fun process(buf: FloatArray, frames: Int, get: (String) -> Float) {
        for (i in 0 until 18) eq.setGain(i, get("eq.band.$i").toDouble())
        eq.process(buf, frames)

        val bd = (get("effect.bass") / 100f * 12f).toDouble()
        if (abs(bd - bassDb) > 0.05) {
            bassDb = bd
            bass.lowShelf(fs.toDouble(), 100.0, bd)
        }
        if (bassDb > 0.05) {
            for (n in 0 until frames) {
                buf[2 * n] = bass.process(buf[2 * n], 0)
                buf[2 * n + 1] = bass.process(buf[2 * n + 1], 1)
            }
        }

        chorus.process(buf, frames, get("effect.chorus") / 100f * 0.7f)
        flanger.process(buf, frames, get("effect.flanger") / 100f * 0.7f)
        echo.process(buf, frames, get("effect.echo") / 100f * 0.6f)
        reverb.process(buf, frames, get("effect.reverb") / 100f * 0.4f)

        val w = get("effect.stereo_width")
        if (w > 0.5f) {
            val sg = 1f + w / 100f
            for (n in 0 until frames) {
                val l = buf[2 * n]
                val r = buf[2 * n + 1]
                val m = (l + r) * 0.5f
                val s = (l - r) * 0.5f * sg
                buf[2 * n] = m + s
                buf[2 * n + 1] = m - s
            }
        }

        val vol = get("volume")
        if (abs(vol - 1f) > 0.001f || abs(prevVol - 1f) > 0.001f) {
            val step = (vol - prevVol) / frames
            var g = prevVol
            for (n in 0 until frames) {
                g += step
                buf[2 * n] *= g
                buf[2 * n + 1] *= g
            }
        }
        prevVol = vol

        limiter.process(buf, frames * 2)
    }
}
