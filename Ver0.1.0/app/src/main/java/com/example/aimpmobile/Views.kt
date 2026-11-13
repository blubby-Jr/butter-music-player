package com.example.aimpmobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Темна «ностальгічна» палітра в дусі класичного AIMP. */
object Theme {
    val BG = Color.parseColor("#16181C")
    val PANEL = Color.parseColor("#1F2227")
    val PANEL2 = Color.parseColor("#2A2E35")
    val ACCENT = Color.parseColor("#FF9800")
    val TEXT = Color.parseColor("#E6E6E6")
    val DIM = Color.parseColor("#8A8F98")
}

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

fun fmtTime(ms: Long): String {
    val s = max(0L, ms) / 1000
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

fun formatParam(def: ParamDef, v: Float): String = when (def.unit) {
    "×" -> String.format(Locale.US, "%.2f×", v)
    "st" -> String.format(Locale.US, "%+.2f st", v)
    "дБ" -> String.format(Locale.US, "%+.1f дБ", v)
    "%" -> String.format(Locale.US, "%.0f%%", v)
    else -> String.format(Locale.US, "%.0f%%", v * 100f)
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
        label.text = def.label
        label.setTextColor(Theme.TEXT)
        label.textSize = 13f
        valueView.setTextColor(Theme.ACCENT)
        valueView.textSize = 13f
        top.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(valueView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(top)
        bar.max = 1000
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

/** Візуалізація на місці обкладинки: спектр-бари / осцилограф. Тап — обкладинка ↔ візуалізація. */
class VisualizerView(ctx: Context, private val engine: PlayerEngine) : View(ctx) {
    var cover: Bitmap? = null
    var showCover = false
    var mode = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val samples = FloatArray(1024)
    private val re = FloatArray(1024)
    private val im = FloatArray(1024)
    private val window = FloatArray(1024) { (0.5 - 0.5 * Math.cos(2.0 * Math.PI * it / 1023)).toFloat() }
    private val bars = FloatArray(48)
    private val peaks = FloatArray(48)
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, 33)
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
        paint.color = Color.parseColor("#0D0E10")
        c.drawRect(0f, 0f, w, h, paint)
        val cv = cover
        if (showCover && cv != null) {
            val s = min(w / cv.width, h / cv.height)
            val dw = cv.width * s
            val dh = cv.height * s
            c.drawBitmap(cv, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), paint)
            return
        }
        engine.viz.latest(samples)
        if (mode == 0) drawBars(c, w, h) else drawScope(c, w, h)
    }

    private fun drawBars(c: Canvas, w: Float, h: Float) {
        for (i in 0 until 1024) {
            re[i] = samples[i] * window[i]
            im[i] = 0f
        }
        Fft.transform(re, im)
        val n = bars.size
        for (k in 0 until n) {
            val lo = (2.0 * 250.0.pow(k.toDouble() / n)).toInt().coerceIn(1, 510)
            val hi = max(lo + 1, (2.0 * 250.0.pow((k + 1).toDouble() / n)).toInt()).coerceAtMost(511)
            var m = 0f
            for (b in lo..hi) m = max(m, hypot(re[b], im[b]))
            val db = 20f * log10(m * 4f / 1024f + 1e-6f)
            val level = ((db + 70f) / 70f).coerceIn(0f, 1f)
            bars[k] = max(level, bars[k] * 0.86f)
            peaks[k] = max(bars[k], peaks[k] - 0.012f)
        }
        val bw = w / n
        for (k in 0 until n) {
            paint.color = Theme.ACCENT
            val bh = bars[k] * (h - 8f)
            c.drawRect(k * bw + 2f, h - bh, (k + 1) * bw - 2f, h, paint)
            paint.color = Color.parseColor("#FFE0B2")
            val py = h - peaks[k] * (h - 8f)
            c.drawRect(k * bw + 2f, py - 3f, (k + 1) * bw - 2f, py, paint)
        }
    }

    private fun drawScope(c: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dpf(2f)
        paint.color = Theme.ACCENT
        val p = Path()
        for (i in 0 until 1024 step 2) {
            val x = i / 1023f * w
            val y = h / 2 - samples[i].coerceIn(-1f, 1f) * h * 0.45f
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        c.drawPath(p, paint)
        paint.style = Paint.Style.FILL
    }
}

/** Хвиля треку + віртуальна обрізка (зелена/червона межі), мітки та курсор відтворення. */
class WaveformView(ctx: Context, private val engine: PlayerEngine) : View(ctx) {
    var peaks: FloatArray? = null
    var onSeek: ((Long) -> Unit)? = null
    var onChanged: (() -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var drag = 0 // 0 нічого, 1 початок, 2 кінець, 3 тап

    private fun dur(): Long = max(1L, engine.durationMs)
    private fun endMs(): Long {
        val s = engine.settings
        return if (s.trimEndMs > 0) s.trimEndMs else dur()
    }

    private fun xOf(t: Long): Float = t.toFloat() / dur() * width
    private fun tOf(x: Float): Long = (x / max(1, width) * dur()).toLong().coerceIn(0L, dur())

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = engine.settings
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#0D0E10")
        c.drawRect(0f, 0f, w, h, paint)
        val pk = peaks
        val xs = xOf(if (s.trimEnabled) s.trimStartMs else 0L)
        val xe = xOf(if (s.trimEnabled) endMs() else dur())
        if (pk == null) {
            paint.color = Theme.DIM
            paint.textSize = context.dpf(13f)
            c.drawText("Хвиля завантажується…", context.dpf(12f), h / 2, paint)
        } else {
            paint.strokeWidth = 1f
            var x = 0f
            while (x < w) {
                val bin = (x / w * pk.size).toInt().coerceIn(0, pk.size - 1)
                val a = pk[bin] * h * 0.45f
                paint.color = if (x in xs..xe) Theme.ACCENT else Color.parseColor("#4A3E2A")
                c.drawLine(x, h / 2 - a, x, h / 2 + a, paint)
                x += 2f
            }
        }
        // межі обрізки
        paint.strokeWidth = context.dpf(3f)
        paint.color = Color.parseColor("#4CAF50")
        c.drawLine(xs, 0f, xs, h, paint)
        paint.color = Color.parseColor("#F44336")
        c.drawLine(xe, 0f, xe, h, paint)
        // мітки
        paint.strokeWidth = context.dpf(1.5f)
        paint.textSize = context.dpf(11f)
        for (m in s.markers) {
            val mx = xOf(m.t)
            paint.color = Color.parseColor("#00BCD4")
            c.drawLine(mx, 0f, mx, h, paint)
            c.drawText(m.label, mx + 4f, context.dpf(12f), paint)
        }
        // курсор
        paint.color = Color.WHITE
        paint.strokeWidth = context.dpf(2f)
        val px = xOf(engine.positionMs)
        c.drawLine(px, 0f, px, h, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val s = engine.settings
        val tol = context.dpf(28f)
        val xs = xOf(if (s.trimEnabled) s.trimStartMs else 0L)
        val xe = xOf(if (s.trimEnabled) endMs() else dur())
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                drag = when {
                    abs(e.x - xs) < tol && abs(e.x - xs) <= abs(e.x - xe) -> 1
                    abs(e.x - xe) < tol -> 2
                    else -> 3
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val t = tOf(e.x)
                if (drag == 1) {
                    s.trimEnabled = true
                    if (s.trimEndMs <= 0) s.trimEndMs = dur()
                    s.trimStartMs = min(t, s.trimEndMs - 500).coerceAtLeast(0L)
                    invalidate()
                } else if (drag == 2) {
                    s.trimEnabled = true
                    s.trimEndMs = max(t, s.trimStartMs + 500).coerceAtMost(dur())
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (drag == 3) onSeek?.invoke(tOf(e.x)) else onChanged?.invoke()
                drag = 0
            }
            MotionEvent.ACTION_CANCEL -> drag = 0
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
        return (d.min + f.coerceIn(0f, 1f) * (d.max - d.min))
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#0D0E10")
        c.drawRect(0f, 0f, w, h, paint)
        val d = def ?: return
        paint.color = Theme.PANEL2
        paint.strokeWidth = 1f
        for (i in 0..4) c.drawLine(pad, pad + i * (h - 2 * pad) / 4, w - pad, pad + i * (h - 2 * pad) / 4, paint)
        val l = lane
        val pts = l?.toTypedArray() ?: emptyArray()
        paint.strokeWidth = context.dpf(2f)
        paint.color = Theme.ACCENT
        if (pts.isEmpty()) {
            val y = yOf(manualValue())
            paint.color = Theme.DIM
            c.drawLine(pad, y, w - pad, y, paint)
            paint.textSize = context.dpf(12f)
            c.drawText("«${d.label}»: немає вузлів — торкніться, щоб додати", pad + 6f, h - pad - 6f, paint)
        } else {
            val path = Path()
            path.moveTo(pad, yOf(pts[0].v))
            for (p in pts) path.lineTo(xOf(p.t), yOf(p.v))
            path.lineTo(w - pad, yOf(pts[pts.size - 1].v))
            paint.style = Paint.Style.STROKE
            c.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            for (p in pts) {
                paint.color = Color.WHITE
                c.drawCircle(xOf(p.t), yOf(p.v), context.dpf(8f), paint)
                paint.color = Theme.ACCENT
                c.drawCircle(xOf(p.t), yOf(p.v), context.dpf(5f), paint)
            }
        }
        paint.color = Color.WHITE
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
