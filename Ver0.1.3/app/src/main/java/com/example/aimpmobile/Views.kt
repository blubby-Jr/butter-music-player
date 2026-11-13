package com.example.aimpmobile

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun Context.styledButton(label: String): Button {
    val b = Button(this)
    b.text = label
    b.isAllCaps = false
    b.setTextColor(Theme.TEXT)
    b.textSize = 14f
    b.minHeight = 0
    b.minimumHeight = dp(44)
    b.setPadding(dp(10), dp(4), dp(10), dp(4))
    val bg = GradientDrawable()
    bg.setColor(Theme.PANEL)
    bg.cornerRadius = dpf(12f)
    bg.setStroke(dp(2), Theme.ACCENT)
    b.background = bg
    return b
}

fun Context.label(text: String, size: Float = 14f, color: Int = Theme.TEXT): TextView {
    val t = TextView(this)
    t.text = text
    t.textSize = size
    t.setTextColor(color)
    return t
}

fun fmtTime(ms: Long): String {
    val s = max(0L, ms) / 1000
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

fun formatParam(def: ParamDef, v: Float): String = when (def.unit) {
    "×" -> String.format(Locale.US, "%.2f×", v)
    "st" -> String.format(Locale.US, "%+.2f st", v)
    "dB" -> String.format(Locale.US, "%+.1f dB", v)
    "%" -> String.format(Locale.US, "%.0f%%", v)
    "fps" -> String.format(Locale.US, "%.0f fps", v)
    "dp" -> String.format(Locale.US, "%.0f dp", v)
    "n" -> String.format(Locale.US, "%.0f", v)
    "f2" -> String.format(Locale.US, "%.2f", v)
    else -> String.format(Locale.US, "%.0f%%", v * 100f)
}

/** Спіннер, що слухається теми. onSelect викликається лише при реальній зміні вибору. */
fun Context.themedSpinner(items: List<String>, selected: Int, onSelect: (Int) -> Unit): Spinner {
    val sp = Spinner(this)
    val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = super.getView(position, convertView, parent) as TextView
            v.setTextColor(Theme.TEXT)
            return v
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = super.getDropDownView(position, convertView, parent) as TextView
            v.setTextColor(Theme.TEXT)
            v.setBackgroundColor(Theme.PANEL2)
            v.setPadding(dp(14), dp(12), dp(14), dp(12))
            return v
        }
    }
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    sp.adapter = adapter
    sp.setSelection(selected)
    var cur = selected
    sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
            if (pos != cur) { cur = pos; onSelect(pos) }
        }

        override fun onNothingSelected(p: AdapterView<*>?) {}
    }
    return sp
}

fun Context.themedSwitch(text: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch {
    val s = Switch(this)
    s.text = text
    s.setTextColor(Theme.TEXT)
    s.isChecked = checked
    s.setPadding(dp(12), dp(8), dp(12), dp(8))
    s.setOnCheckedChangeListener { _, on -> onChange(on) }
    return s
}

private fun tint(bar: SeekBar) {
    val c = ColorStateList.valueOf(Theme.ACCENT)
    bar.progressTintList = c
    bar.thumbTintList = c
}

/** Рядок «назва — слайдер — значення». Довге натискання на назву скидає значення. */
class SliderRow(ctx: Context, val def: ParamDef, private val onUser: (ParamDef, Float) -> Unit) : LinearLayout(ctx) {
    private val label = TextView(ctx)
    private val valueView = TextView(ctx)
    private val bar = SeekBar(ctx)
    private var updating = false
    private var touching = false

    init {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(8), ctx.dp(4))
        val top = LinearLayout(ctx)
        top.orientation = LinearLayout.HORIZONTAL
        label.text = S.pl(def)
        label.setTextColor(Theme.TEXT)
        label.textSize = 13f
        valueView.setTextColor(Theme.ACCENT)
        valueView.textSize = 13f
        top.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(valueView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(top)
        bar.max = 1000
        tint(bar)
        addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        label.setOnLongClickListener {
            setValue(def.def)
            onUser(def, def.def)
            true
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser || updating) return
                val v = def.min + (def.max - def.min) * p / 1000f
                valueView.text = formatParam(def, v)
                onUser(def, v)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) { touching = true }
            override fun onStopTrackingTouch(sb: SeekBar?) { touching = false }
        })
        setValue(def.def)
    }

    fun setValue(v: Float) {
        if (touching) return
        updating = true
        bar.progress = ((v - def.min) / (def.max - def.min) * 1000f).roundToInt().coerceIn(0, 1000)
        updating = false
        valueView.text = formatParam(def, v)
    }
}

/** Набір слайдерів: 3 колонки на планшеті/в ландшафті, одна колонка — на телефоні. */
class ParamPanel(ctx: Context, columns: List<List<ParamDef>>, onUser: (ParamDef, Float) -> Unit) : LinearLayout(ctx) {
    private val rows = HashMap<String, SliderRow>()

    init {
        val wide = ctx.resources.configuration.screenWidthDp >= 600 && columns.size > 1
        orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        for (col in columns) {
            val holder: LinearLayout
            if (wide) {
                holder = LinearLayout(ctx)
                holder.orientation = LinearLayout.VERTICAL
                addView(holder, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            } else {
                holder = this
            }
            for (d in col) {
                val r = SliderRow(ctx, d, onUser)
                rows[d.id] = r
                holder.addView(r)
            }
        }
    }

    fun setAll(get: (String) -> Float) {
        for ((id, r) in rows) r.setValue(get(id))
    }

    fun setIds(ids: Collection<String>, get: (String) -> Float) {
        for (id in ids) rows[id]?.setValue(get(id))
    }
}

/** Вибір кольору трьома слайдерами RGB + зразок. */
class ColorRow(ctx: Context, title: String, initial: Int, private val onChange: (Int) -> Unit) : LinearLayout(ctx) {
    private var color = initial
    private val swatch = View(ctx)
    private val bars = ArrayList<SeekBar>()

    init {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(12), ctx.dp(6))
        val top = LinearLayout(ctx)
        top.orientation = LinearLayout.HORIZONTAL
        top.addView(ctx.label(title, 14f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(swatch, LinearLayout.LayoutParams(ctx.dp(56), ctx.dp(24)))
        addView(top)
        for (ch in 0..2) {
            val b = SeekBar(ctx)
            b.max = 255
            tint(b)
            b.progress = when (ch) { 0 -> Color.red(initial); 1 -> Color.green(initial); else -> Color.blue(initial) }
            b.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    color = Color.rgb(bars[0].progress, bars[1].progress, bars[2].progress)
                    paintSwatch()
                    onChange(color)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            bars.add(b)
            addView(b)
        }
        paintSwatch()
    }

    private fun paintSwatch() {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = context.dpf(6f)
        g.setStroke(context.dp(1), Theme.DIM)
        swatch.background = g
    }
}

/** Процедурна «екранна» картинка в стилі осцилографа: сітка + крива з трьох синусоїд (залежить від seed). */
object ProceduralArt {
    fun draw(c: Canvas, w: Float, h: Float, seed: Long, bg: Int, fg: Int, unit: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.FILL
        p.color = bg
        c.drawRect(0f, 0f, w, h, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(1f, unit * 0.6f)
        for (i in 0..8) {
            p.color = ColorUtils.setAlphaComponent(fg, if (i == 4) 90 else 38)
            val x = w * i / 8f
            c.drawLine(x, 0f, x, h, p)
        }
        for (i in 0..6) {
            p.color = ColorUtils.setAlphaComponent(fg, if (i == 3) 90 else 38)
            val y = h * i / 6f
            c.drawLine(0f, y, w, y, p)
        }
        val rnd = java.util.Random(seed)
        val f1 = 1 + rnd.nextInt(3)
        val f2 = 3 + rnd.nextInt(5)
        val f3 = 8 + rnd.nextInt(9)
        val p1 = rnd.nextDouble() * 6.28
        val p2 = rnd.nextDouble() * 6.28
        val p3 = rnd.nextDouble() * 6.28
        val a2 = 0.3 + rnd.nextDouble() * 0.35
        val a3 = 0.08 + rnd.nextDouble() * 0.18
        val path = Path()
        var x = 0f
        while (x <= w) {
            val t = x / w * 2.0 * PI
            val v = (sin(f1 * t + p1) + a2 * sin(f2 * t + p2) + a3 * sin(f3 * t + p3)) / (1.0 + a2 + a3)
            val y = (h / 2 - v * h * 0.38).toFloat()
            if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
            x += max(1f, w / 400f)
        }
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.color = ColorUtils.setAlphaComponent(fg, 40); p.strokeWidth = unit * 9; c.drawPath(path, p)
        p.color = ColorUtils.setAlphaComponent(fg, 100); p.strokeWidth = unit * 4.5f; c.drawPath(path, p)
        p.color = ColorUtils.blendARGB(fg, Color.WHITE, 0.6f); p.strokeWidth = unit * 1.8f; c.drawPath(path, p)
    }
}

/**
 * Візуалізація на місці обкладинки. Режими: 0 стовпчики · 1 осцилограф · 2 аналогові вимірювачі · 3 власні лінії.
 * Тап — обкладинка ↔ візуалізація. Частота оновлення береться з налаштувань.
 */
class VisualizerView(ctx: Context, private val engine: PlayerEngine, private val vs: VizSettings) : View(ctx) {
    var cover: Bitmap? = null
    var coverSeed = 0L
    var showCover = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val samples = FloatArray(1024)
    private val re = FloatArray(1024)
    private val im = FloatArray(1024)
    private val window = FloatArray(1024) { (0.5 - 0.5 * cos(2.0 * PI * it / 1023)).toFloat() }
    private var bands = FloatArray(0)
    private var peaks = FloatArray(0)
    private val lBuf = FloatArray(2048)
    private val rBuf = FloatArray(2048)
    private var needleL = 0f
    private var needleR = 0f
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, max(4L, 1000L / max(5, vs.fps) - 2))
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = Theme.VIEWBG
        c.drawRect(0f, 0f, w, h, paint)
        if (showCover) {
            val cv = cover
            if (cv != null) {
                val s = min(w / cv.width, h / cv.height)
                val dw = cv.width * s
                val dh = cv.height * s
                c.drawBitmap(cv, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), paint)
            } else {
                ProceduralArt.draw(c, w, h, coverSeed, Theme.VIEWBG, Theme.ACCENT, context.dpf(1f))
            }
            return
        }
        when (vs.mode) {
            1 -> drawScope(c, w, h)
            2 -> drawMeters(c, w, h)
            3 -> {
                computeBands(vs.bars.coerceIn(4, 128))
                drawLines(c, w, h, vs.thicknessDp, vs.color1, vs.color2, vs.gradient, vs.rainbow, vs.mirror, vs.peaks, vs.rounded)
            }
            else -> {
                computeBands(48)
                drawLines(c, w, h, 0, Theme.ACCENT, Theme.ACCENT, false, false, false, true, false)
            }
        }
    }

    private fun computeBands(n: Int) {
        if (bands.size != n) { bands = FloatArray(n); peaks = FloatArray(n) }
        engine.viz.latest(samples)
        for (i in 0 until 1024) { re[i] = samples[i] * window[i]; im[i] = 0f }
        Fft.transform(re, im)
        for (k in 0 until n) {
            val lo = (2.0 * 250.0.pow(k.toDouble() / n)).toInt().coerceIn(1, 510)
            val hi = max(lo + 1, (2.0 * 250.0.pow((k + 1).toDouble() / n)).toInt()).coerceAtMost(511)
            var m = 0f
            for (b in lo..hi) m = max(m, hypot(re[b], im[b]))
            val db = 20f * log10(m * 4f / 1024f + 1e-6f)
            val level = ((db + 70f) / 70f).coerceIn(0f, 1f)
            bands[k] = max(level, bands[k] * vs.fall)
            peaks[k] = max(bands[k], peaks[k] - 0.012f)
        }
    }

    /** Універсальний рендер «ліній»: режим 0 і режим 3 (з налаштуваннями) — одна й та сама функція. */
    private fun drawLines(
        c: Canvas, w: Float, h: Float, thicknessDp: Int, c1: Int, c2: Int,
        gradient: Boolean, rainbow: Boolean, mirror: Boolean, peaksOn: Boolean, rounded: Boolean
    ) {
        val n = bands.size
        val step = w / n
        val thick = if (thicknessDp <= 0) max(2f, step - 4f) else min(context.dpf(thicknessDp.toFloat()), max(1f, step - 1f))
        val pad = if (rounded) thick / 2 else 0f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = thick
        paint.strokeCap = if (rounded) Paint.Cap.ROUND else Paint.Cap.BUTT
        val maxH = (if (mirror) h / 2 else h) - 6f - pad
        for (k in 0 until n) {
            val x = step * (k + 0.5f)
            val lv = bands[k]
            paint.color = when {
                rainbow -> Color.HSVToColor(floatArrayOf(k * 300f / n, 0.9f, 1f))
                gradient -> ColorUtils.blendARGB(c1, c2, lv)
                else -> c1
            }
            val bh = lv * maxH
            if (mirror) c.drawLine(x, h / 2 - bh, x, h / 2 + bh, paint)
            else c.drawLine(x, h - pad - 1f, x, h - pad - 1f - bh, paint)
            if (peaksOn) {
                paint.color = ColorUtils.blendARGB(paint.color, Color.WHITE, 0.6f)
                val py = peaks[k] * maxH
                if (mirror) {
                    c.drawLine(x, h / 2 - py - thick, x, h / 2 - py - thick + 1f, paint)
                    c.drawLine(x, h / 2 + py + thick, x, h / 2 + py + thick - 1f, paint)
                } else c.drawLine(x, h - pad - 1f - py - thick, x, h - pad - 1f - py - thick + 1f, paint)
            }
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawScope(c: Canvas, w: Float, h: Float) {
        engine.viz.latest(samples)
        val path = Path()
        for (i in 0 until 1024 step 2) {
            val x = i / 1023f * w
            val y = h / 2 - samples[i].coerceIn(-1f, 1f) * h * 0.45f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = ColorUtils.setAlphaComponent(Theme.ACCENT, 60)
        paint.strokeWidth = context.dpf(6f)
        c.drawPath(path, paint)
        paint.color = Theme.ACCENT
        paint.strokeWidth = context.dpf(2f)
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    private fun level(a: FloatArray): Float {
        var s = 0.0
        for (x in a) s += x * x
        val db = 20.0 * log10(sqrt(s / a.size) + 1e-9)
        return ((db + 32.0) / 26.0).coerceIn(0.0, 1.0).toFloat() // −32…−6 dBFS = −20…+6 VU (0 VU = −12 dBFS)
    }

    private fun drawMeters(c: Canvas, w: Float, h: Float) {
        engine.viz.latestLR(lBuf, rBuf)
        val lv = level(lBuf)
        val rv = level(rBuf)
        needleL += (lv - needleL) * (if (lv > needleL) 0.35f else 0.10f)
        needleR += (rv - needleR) * (if (rv > needleR) 0.35f else 0.10f)
        val gw = w / 2
        gauge(c, 0f, gw, h, needleL, "L")
        gauge(c, gw, gw, h, needleR, "R")
    }

    private fun ang(lvl: Float): Double = Math.toRadians((-52.0 + lvl * 104.0))

    private fun gauge(c: Canvas, x0: Float, gw: Float, h: Float, value: Float, name: String) {
        val m = context.dpf(6f)
        val face = RectF(x0 + m, m, x0 + gw - m, h - m)
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#F1E4BF")
        c.drawRoundRect(face, m * 2, m * 2, paint)
        c.save()
        c.clipRect(face)
        val cx = face.centerX()
        val r = min(face.width() * 0.62f, face.height() * 0.92f)
        val cy = face.bottom + r * 0.10f
        val rect = RectF(cx - r * 0.86f, cy - r * 0.86f, cx + r * 0.86f, cy + r * 0.86f)
        paint.style = Paint.Style.STROKE
        // червона зона від 0 VU
        paint.color = Color.parseColor("#C62828")
        paint.strokeWidth = context.dpf(5f)
        val aRed = Math.toDegrees(ang(0.769f)).toFloat()
        val aEnd = Math.toDegrees(ang(1f)).toFloat()
        c.drawArc(rect, aRed - 90f, aEnd - aRed, false, paint)
        // шкала: основна дуга і поділки
        paint.color = Color.parseColor("#3B3220")
        paint.strokeWidth = context.dpf(1.5f)
        c.drawArc(rect, Math.toDegrees(ang(0f)).toFloat() - 90f, Math.toDegrees(ang(0.769f) - ang(0f)).toFloat(), false, paint)
        paint.textSize = context.dpf(10f)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        val labels = listOf(0f to "-20", 0.385f to "-10", 0.577f to "-5", 0.769f to "0", 0.885f to "+3", 1f to "+6")
        for (i in 0..13) {
            val lv = i / 13f
            val a = ang(lv)
            val long = labels.any { abs(it.first - lv) < 0.04f }
            val r1 = r * 0.86f
            val r2 = r * (if (long) 0.74f else 0.80f)
            paint.style = Paint.Style.STROKE
            paint.color = if (lv >= 0.769f) Color.parseColor("#C62828") else Color.parseColor("#3B3220")
            c.drawLine(cx + (r1 * sin(a)).toFloat(), cy - (r1 * cos(a)).toFloat(), cx + (r2 * sin(a)).toFloat(), cy - (r2 * cos(a)).toFloat(), paint)
        }
        paint.style = Paint.Style.FILL
        for ((lv, t) in labels) {
            val a = ang(lv)
            paint.color = if (lv >= 0.769f) Color.parseColor("#C62828") else Color.parseColor("#3B3220")
            c.drawText(t, cx + (r * 0.64f * sin(a)).toFloat(), cy - (r * 0.64f * cos(a)).toFloat() + paint.textSize / 3, paint)
        }
        paint.textSize = context.dpf(13f)
        paint.color = Color.parseColor("#3B3220")
        paint.typeface = Typeface.DEFAULT_BOLD
        c.drawText("VU", cx, face.top + r * 0.42f, paint)
        c.drawText(name, face.left + context.dpf(14f), face.bottom - context.dpf(10f), paint)
        paint.typeface = Typeface.DEFAULT
        // стрілка
        val a = ang(value)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.parseColor("#1A1A1A")
        paint.strokeWidth = context.dpf(2f)
        c.drawLine(cx, cy, cx + (r * 0.92f * sin(a)).toFloat(), cy - (r * 0.92f * cos(a)).toFloat(), paint)
        paint.style = Paint.Style.FILL
        c.drawCircle(cx, cy, context.dpf(9f), paint)
        c.restore()
        paint.textAlign = Paint.Align.LEFT
    }
}

/** Іконка керування у стилі Telegram: гучність, порядок, повтор, швидкість, закрити. */
class TransportIcon(ctx: Context, private val kind: Int) : View(ctx) {
    companion object {
        const val VOLUME = 0
        const val ORDER = 1
        const val REPEAT = 2
        const val SPEED = 3
        const val CLOSE = 4
    }

    private var active = false
    private var variant = 0
    private var text = ""
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(active: Boolean, variant: Int = 0, text: String = "") {
        this.active = active
        this.variant = variant
        this.text = text
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(context.dp(44), context.dp(40))
    }

    private fun arrowHead(c: Canvas, x: Float, y: Float, dx: Float, dy: Float, s: Float) {
        // голівка стрілки, що вказує в напрямі (dx,dy)
        val px = -dy
        val py = dx
        val path = Path()
        path.moveTo(x - dx * s + px * s * 0.7f, y - dy * s + py * s * 0.7f)
        path.lineTo(x, y)
        path.lineTo(x - dx * s - px * s * 0.7f, y - dy * s - py * s * 0.7f)
        c.drawPath(path, p)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val cy = h / 2
        val s = min(w, h) * 0.30f
        val col = if (active) Theme.ACCENT else Theme.TEXT
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = context.dpf(2f)
        p.color = col
        when (kind) {
            CLOSE -> {
                c.drawLine(cx - s * 0.8f, cy - s * 0.8f, cx + s * 0.8f, cy + s * 0.8f, p)
                c.drawLine(cx - s * 0.8f, cy + s * 0.8f, cx + s * 0.8f, cy - s * 0.8f, p)
            }
            VOLUME -> {
                val sp = Path()
                sp.moveTo(cx - s, cy - s * 0.4f); sp.lineTo(cx - s * 0.4f, cy - s * 0.4f); sp.lineTo(cx + s * 0.1f, cy - s)
                sp.lineTo(cx + s * 0.1f, cy + s); sp.lineTo(cx - s * 0.4f, cy + s * 0.4f); sp.lineTo(cx - s, cy + s * 0.4f); sp.close()
                c.drawPath(sp, p)
                c.drawArc(RectF(cx - s * 0.3f, cy - s * 0.55f, cx + s * 0.6f, cy + s * 0.55f), -50f, 100f, false, p)
                c.drawArc(RectF(cx - s * 0.2f, cy - s * 1.0f, cx + s * 1.1f, cy + s * 1.0f), -50f, 100f, false, p)
            }
            ORDER -> if (variant == 2) {
                c.drawLine(cx - s, cy + s * 0.7f, cx + s, cy - s * 0.7f, p)
                c.drawLine(cx - s, cy - s * 0.7f, cx + s, cy + s * 0.7f, p)
                arrowHead(c, cx + s, cy - s * 0.7f, 0.8f, -0.6f, s * 0.5f)
                arrowHead(c, cx + s, cy + s * 0.7f, 0.8f, 0.6f, s * 0.5f)
            } else {
                val x1 = cx - s * 0.55f
                val x2 = cx + s * 0.55f
                c.drawLine(x1, cy + s, x1, cy - s, p)
                arrowHead(c, x1, cy - s, 0f, -1f, s * 0.55f)
                c.drawLine(x2, cy - s, x2, cy + s, p)
                arrowHead(c, x2, cy + s, 0f, 1f, s * 0.55f)
            }
            REPEAT -> {
                c.drawRoundRect(RectF(cx - s, cy - s * 0.6f, cx + s, cy + s * 0.6f), s * 0.5f, s * 0.5f, p)
                p.style = Paint.Style.FILL
                val t1 = Path()
                t1.moveTo(cx + s * 0.15f, cy - s * 0.6f - s * 0.38f); t1.lineTo(cx + s * 0.15f, cy - s * 0.6f + s * 0.38f); t1.lineTo(cx + s * 0.55f, cy - s * 0.6f); t1.close()
                c.drawPath(t1, p)
                val t2 = Path()
                t2.moveTo(cx - s * 0.15f, cy + s * 0.6f - s * 0.38f); t2.lineTo(cx - s * 0.15f, cy + s * 0.6f + s * 0.38f); t2.lineTo(cx - s * 0.55f, cy + s * 0.6f); t2.close()
                c.drawPath(t2, p)
                if (variant == 1) {
                    p.color = Theme.ACCENT
                    c.drawCircle(cx + s * 0.95f, cy - s * 0.75f, s * 0.55f, p)
                    p.color = Theme.BG
                    p.textSize = s * 0.85f
                    p.textAlign = Paint.Align.CENTER
                    p.typeface = Typeface.DEFAULT_BOLD
                    c.drawText("1", cx + s * 0.95f, cy - s * 0.75f + s * 0.3f, p)
                    p.typeface = Typeface.DEFAULT
                    p.textAlign = Paint.Align.LEFT
                }
            }
            SPEED -> {
                p.strokeWidth = context.dpf(1.2f)
                c.drawRoundRect(RectF(cx - s * 1.1f, cy - s * 0.8f, cx + s * 1.1f, cy + s * 0.8f), s * 0.3f, s * 0.3f, p)
                p.style = Paint.Style.FILL
                p.textSize = s * 1.05f
                p.textAlign = Paint.Align.CENTER
                p.typeface = Typeface.DEFAULT_BOLD
                c.drawText(text, cx, cy + s * 0.36f, p)
                p.typeface = Typeface.DEFAULT
                p.textAlign = Paint.Align.LEFT
            }
        }
    }
}

/** Хвиля треку + кілька виділених ділянок («дропів»), мітки та курсор відтворення. */
class WaveformView(ctx: Context, private val engine: PlayerEngine) : View(ctx) {
    var peaks: FloatArray? = null
    var onSeek: ((Long) -> Unit)? = null
    var onChanged: (() -> Unit)? = null
    /** true — перетягування по порожньому місцю створює нову ділянку; false — тап перемотує. */
    var selectMode = false
    var selected = -1
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var dragKind = 0 // 0 нічого, 1 початок ділянки, 2 кінець, 3 тап, 4 створення
    private var dragRegion: Region? = null
    private var anchor = 0L
    private var moved = false

    private fun dur(): Long = max(1L, engine.durationMs)
    private fun xOf(t: Long): Float = t.toFloat() / dur() * width
    private fun tOf(x: Float): Long = (x / max(1, width) * dur()).toLong().coerceIn(0L, dur())

    fun deleteSelected() {
        val s = engine.settings
        val list = s.regions.sortedBy { it.start }
        if (list.isEmpty()) return
        val idx = if (selected in list.indices) selected else list.size - 1
        s.regions = list.filterIndexed { i, _ -> i != idx }
        selected = -1
        invalidate()
        onChanged?.invoke()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = engine.settings
        val regs = s.regions.sortedBy { it.start }
        paint.style = Paint.Style.FILL
        paint.color = Theme.VIEWBG
        c.drawRect(0f, 0f, w, h, paint)
        val pk = peaks
        val dimOutside = s.selectionOnly && regs.isNotEmpty()
        if (pk == null) {
            paint.color = Theme.DIM
            paint.textSize = context.dpf(13f)
            c.drawText(S.t("wave.loading"), context.dpf(12f), h / 2, paint)
        } else {
            paint.strokeWidth = 1f
            var x = 0f
            while (x < w) {
                val bin = (x / w * pk.size).toInt().coerceIn(0, pk.size - 1)
                val a = pk[bin] * h * 0.45f
                val t = (x / w * dur()).toLong()
                val inside = !dimOutside || regs.any { t >= it.start && t <= it.end }
                paint.color = if (inside) Theme.ACCENT else ColorUtils.blendARGB(Theme.VIEWBG, Theme.ACCENT, 0.25f)
                c.drawLine(x, h / 2 - a, x, h / 2 + a, paint)
                x += 2f
            }
        }
        for ((i, r) in regs.withIndex()) {
            val xs = xOf(r.start)
            val xe = xOf(r.end)
            paint.style = Paint.Style.FILL
            paint.color = ColorUtils.setAlphaComponent(Theme.ACCENT, if (i == selected) 90 else 45)
            c.drawRect(xs, 0f, xe, h, paint)
            paint.strokeWidth = context.dpf(if (i == selected) 4f else 3f)
            paint.color = Color.parseColor("#4CAF50")
            c.drawLine(xs, 0f, xs, h, paint)
            paint.color = Color.parseColor("#F44336")
            c.drawLine(xe, 0f, xe, h, paint)
        }
        paint.strokeWidth = context.dpf(1.5f)
        paint.textSize = context.dpf(11f)
        for (m in s.markers) {
            val mx = xOf(m.t)
            paint.color = Color.parseColor("#00BCD4")
            c.drawLine(mx, 0f, mx, h, paint)
            c.drawText(m.label, mx + 4f, context.dpf(12f), paint)
        }
        paint.color = if (Theme.isLight) Color.BLACK else Color.WHITE
        paint.strokeWidth = context.dpf(2f)
        val px = xOf(engine.positionMs)
        c.drawLine(px, 0f, px, h, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val s = engine.settings
        val tol = context.dpf(28f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                moved = false
                dragKind = 0
                dragRegion = null
                var best = tol
                for (r in s.regions) {
                    val ds = abs(e.x - xOf(r.start))
                    val de = abs(e.x - xOf(r.end))
                    if (ds < best) { best = ds; dragKind = 1; dragRegion = r }
                    if (de < best) { best = de; dragKind = 2; dragRegion = r }
                }
                if (dragKind == 0) {
                    if (selectMode) { dragKind = 4; anchor = tOf(e.x) } else dragKind = 3
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val t = tOf(e.x)
                val r = dragRegion
                when (dragKind) {
                    1 -> if (r != null) { r.start = min(t, r.end - 200).coerceAtLeast(0L); moved = true }
                    2 -> if (r != null) { r.end = max(t, r.start + 200).coerceAtMost(dur()); moved = true }
                    4 -> {
                        if (abs(t - anchor) > 150 || dragRegion != null) {
                            var nr = dragRegion
                            if (nr == null) {
                                nr = Region(anchor, anchor + 1)
                                dragRegion = nr
                                s.regions = s.regions + nr
                            }
                            nr.start = min(anchor, t)
                            nr.end = max(anchor, t)
                            moved = true
                        }
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val t = tOf(e.x)
                if (dragKind == 3 || (dragKind == 4 && !moved)) {
                    val sorted = s.regions.sortedBy { it.start }
                    val hit = sorted.indexOfFirst { t >= it.start && t <= it.end }
                    if (selectMode && hit >= 0) selected = hit else onSeek?.invoke(t)
                } else if (moved) {
                    val focus = dragRegion
                    s.regions = Sel.normalize(s.regions)
                    val sorted = s.regions
                    selected = if (focus != null) sorted.indexOfFirst { focus.start >= it.start && focus.start <= it.end } else -1
                    onChanged?.invoke()
                }
                dragKind = 0
                dragRegion = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { dragKind = 0; dragRegion = null }
        }
        return true
    }
}

/** Доріжка автоматизації (як у FL Studio): тап — новий вузол, перетягування — зміна, довгий тап — видалення. */
class AutomationView(ctx: Context, private val engine: PlayerEngine) : View(ctx) {
    private var def: ParamDef? = null
    private var lane: CopyOnWriteArrayList<AutoPoint>? = null
    var manualValue: () -> Float = { 0f }
    var onChanged: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private var dragging: AutoPoint? = null
    private var downX = 0f
    private var downY = 0f
    private var longPressFired = false
    private val longPress = Runnable {
        val p = dragging
        val l = lane
        if (p != null && l != null) {
            l.remove(p)
            dragging = null
            longPressFired = true
            invalidate()
            onChanged?.invoke()
        }
    }

    fun bind(d: ParamDef, l: CopyOnWriteArrayList<AutoPoint>) {
        def = d
        lane = l
        invalidate()
    }

    private val pad get() = context.dpf(14f)
    private fun dur(): Long = max(1L, engine.durationMs)
    private fun xOf(t: Long) = pad + t.toFloat() / dur() * (width - 2 * pad)
    private fun yOf(v: Float): Float {
        val d = def ?: return height / 2f
        return pad + (1f - (v - d.min) / (d.max - d.min)) * (height - 2 * pad)
    }
    private fun tOf(x: Float) = ((x - pad) / max(1f, width - 2 * pad) * dur()).toLong().coerceIn(0L, dur())
    private fun vOf(y: Float): Float {
        val d = def ?: return 0f
        val f = 1f - (y - pad) / max(1f, height - 2 * pad)
        return d.min + f.coerceIn(0f, 1f) * (d.max - d.min)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = Theme.VIEWBG
        c.drawRect(0f, 0f, w, h, paint)
        val d = def ?: return
        paint.color = Theme.PANEL2
        paint.strokeWidth = 1f
        for (i in 0..4) c.drawLine(pad, pad + i * (h - 2 * pad) / 4, w - pad, pad + i * (h - 2 * pad) / 4, paint)
        val pts = lane?.toTypedArray() ?: emptyArray()
        paint.strokeWidth = context.dpf(2f)
        paint.color = Theme.ACCENT
        if (pts.isEmpty()) {
            val y = yOf(manualValue())
            paint.color = Theme.DIM
            c.drawLine(pad, y, w - pad, y, paint)
            paint.textSize = context.dpf(12f)
            c.drawText(S.f("auto.empty", S.pl(d)), pad + 6f, h - pad - 6f, paint)
        } else {
            val path = Path()
            path.moveTo(pad, yOf(pts[0].v))
            for (p in pts) path.lineTo(xOf(p.t), yOf(p.v))
            path.lineTo(w - pad, yOf(pts[pts.size - 1].v))
            paint.style = Paint.Style.STROKE
            c.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            for (p in pts) {
                paint.color = Theme.TEXT
                c.drawCircle(xOf(p.t), yOf(p.v), context.dpf(8f), paint)
                paint.color = Theme.ACCENT
                c.drawCircle(xOf(p.t), yOf(p.v), context.dpf(5f), paint)
            }
        }
        paint.color = Theme.TEXT
        paint.strokeWidth = context.dpf(1.5f)
        val px = xOf(engine.positionMs)
        c.drawLine(px, pad, px, h - pad, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val l = lane ?: return true
        if (def == null) return true
        val tol = context.dpf(22f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                downX = e.x
                downY = e.y
                longPressFired = false
                var hit: AutoPoint? = null
                var best = tol
                for (p in l) {
                    val dist = hypot(e.x - xOf(p.t), e.y - yOf(p.v))
                    if (dist < best) { best = dist; hit = p }
                }
                if (hit == null) {
                    hit = AutoPoint(tOf(e.x), vOf(e.y))
                    l.add(hit)
                    l.sortBy { it.t }
                } else {
                    handler.postDelayed(longPress, 650)
                }
                dragging = hit
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(e.x - downX, e.y - downY) > context.dpf(8f)) handler.removeCallbacks(longPress)
                val p = dragging
                if (p != null) {
                    p.t = tOf(e.x)
                    p.v = vOf(e.y)
                    l.sortBy { it.t }
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                if (dragging != null && !longPressFired) onChanged?.invoke()
                dragging = null
            }
        }
        return true
    }
}
