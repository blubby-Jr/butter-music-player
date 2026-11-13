package com.example.aimpmobile

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max

/**
 * Єдина Activity з чотирма «екранами» (як на макетах): main, effects, eq, editor.
 * Інтерфейс побудований програмно — без XML-розміток і зайвих залежностей.
 */
class MainActivity : Activity(), PlayerEngine.Listener {
    private lateinit var engine: PlayerEngine
    private lateinit var playlists: Playlists
    private var allTracks: List<Track> = emptyList()
    private var shown: List<Track> = emptyList()
    private var plIndex = 1
    private var current: Track? = null
    private var settings = TrackSettings()
    private val dirty = LinkedHashSet<String>()
    private val ui = Handler(Looper.getMainLooper())
    private val peaksCache = HashMap<Long, FloatArray>()
    @Volatile private var peaksJobFor = -1L

    private val screens = HashMap<String, View>()
    private var currentScreen = "main"
    private lateinit var content: FrameLayout
    private lateinit var visualizer: VisualizerView
    private lateinit var listView: ListView
    private val adapter = TrackAdapter()
    private val tabViews = ArrayList<TextView>()
    private lateinit var seek: SeekBar
    private lateinit var timeView: TextView
    private lateinit var playBtn: Button
    private lateinit var nowPlaying: TextView
    private var seeking = false

    private lateinit var waveform: WaveformView
    private lateinit var autoView: AutomationView
    private lateinit var editorTitle: TextView
    private lateinit var trimBtn: Button
    private lateinit var fxMain: ParamPanel
    private lateinit var fxEditor: ParamPanel
    private lateinit var eqMain: ParamPanel
    private lateinit var eqEditor: ParamPanel
    private lateinit var bypassMain: Switch
    private lateinit var bypassEditor: Switch
    private var laneDef: ParamDef = Params.volume

    // ---------------------------------------------------------------- життєвий цикл

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = PlayerEngine(applicationContext)
        engine.listener = this
        playlists = Playlists(this)
        buildUi()
        ensurePermissions()
        ui.post(ticker)
    }

    override fun onPause() {
        flushSave()
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        engine.listener = null
        if (isFinishing) {
            flushSave()
            engine.release()
            stopService(Intent(this, PlaybackService::class.java))
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentScreen != "main") show("main") else moveTaskToBack(true) // музика продовжує грати у фоні
    }

    // ---------------------------------------------------------------- дозволи й бібліотека

    private fun ensurePermissions() {
        val need = ArrayList<String>()
        val audio = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(audio) != PackageManager.PERMISSION_GRANTED) need.add(audio)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty()) scan() else requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        scan()
    }

    private fun scan() {
        Thread {
            val list = Library.scan(this)
            runOnUiThread {
                allTracks = list
                showPlaylist(plIndex)
                if (list.isEmpty()) toast("Музику не знайдено або немає дозволу на доступ до аудіо")
            }
        }.start()
    }

    private fun showPlaylist(n: Int) {
        plIndex = n
        shown = if (n == 1) allTracks else {
            val byId = allTracks.associateBy { it.id }
            playlists.ids(n).mapNotNull { byId[it] }
        }
        adapter.notifyDataSetChanged()
        for ((i, tv) in tabViews.withIndex()) {
            tv.setTextColor(if (i + 1 == n) Theme.ACCENT else Theme.DIM)
            tv.setBackgroundColor(if (i + 1 == n) Theme.PANEL2 else Theme.PANEL)
        }
    }

    // ---------------------------------------------------------------- побудова інтерфейсу

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Theme.BG)

        val title = TextView(this)
        title.text = "MAIN_MENU · AIMP Mobile"
        title.setTextColor(Theme.ACCENT)
        title.textSize = 16f
        title.gravity = Gravity.CENTER
        title.setPadding(0, dp(10), 0, dp(10))
        root.addView(title)

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        screens["main"] = buildMain()
        screens["effects"] = buildEffects()
        screens["eq"] = buildEq()
        screens["editor"] = buildEditor()
        for (v in screens.values) content.addView(v)
        show("main")

        root.addView(buildTransport())
        setContentView(root)
    }

    private fun show(name: String) {
        currentScreen = name
        for ((k, v) in screens) v.visibility = if (k == name) View.VISIBLE else View.GONE
        if (name == "editor") {
            editorTitle.text = current?.displayName ?: "Оберіть трек у списку"
            ensurePeaks()
            bindLane()
        }
        refreshPanels()
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f, margin: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h, weight)
        p.setMargins(margin, margin, margin, margin)
        return p
    }

    private fun backBar(titleText: String): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(8), dp(6), dp(8), dp(6))
        val back = styledButton("← Назад")
        back.setOnClickListener { show("main") }
        bar.addView(back)
        val t = TextView(this)
        t.text = titleText
        t.setTextColor(Theme.TEXT)
        t.textSize = 16f
        t.setPadding(dp(12), 0, 0, 0)
        bar.addView(t)
        return bar
    }

    private fun buildMain(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.VERTICAL
        val bEditor = styledButton("Editor")
        bEditor.setOnClickListener { show("editor") }
        val bEffects = styledButton("Effects")
        bEffects.setOnClickListener { show("effects") }
        val bEq = styledButton("EQ")
        bEq.setOnClickListener { show("eq") }
        buttons.addView(bEditor, lp(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT, 0f, dp(6)))
        buttons.addView(bEffects, lp(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT, 0f, dp(6)))
        buttons.addView(bEq, lp(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT, 0f, dp(6)))
        top.addView(buttons)

        visualizer = VisualizerView(this, engine)
        visualizer.setOnClickListener { visualizer.showCover = !visualizer.showCover }
        visualizer.setOnLongClickListener {
            visualizer.mode = (visualizer.mode + 1) % 2
            true
        }
        top.addView(visualizer, lp(0, dp(190), 1f, dp(6)))
        col.addView(top)

        nowPlaying = TextView(this)
        nowPlaying.setTextColor(Theme.DIM)
        nowPlaying.textSize = 13f
        nowPlaying.setPadding(dp(12), dp(2), dp(12), dp(2))
        nowPlaying.text = "Тап по візуалізації — обкладинка, довгий тап — інший режим"
        col.addView(nowPlaying)

        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        for (i in 1..3) {
            val tv = TextView(this)
            tv.text = "Play List $i"
            tv.gravity = Gravity.CENTER
            tv.textSize = 14f
            tv.setPadding(0, dp(12), 0, dp(12))
            tv.setOnClickListener { showPlaylist(i) }
            tabViews.add(tv)
            tabs.addView(tv, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, dp(1)))
        }
        col.addView(tabs)

        listView = ListView(this)
        listView.adapter = adapter
        listView.setBackgroundColor(Theme.BG)
        listView.divider = null
        listView.setOnItemClickListener { _, _, pos, _ -> playTrack(shown[pos]) }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            trackMenu(shown[pos])
            true
        }
        col.addView(listView, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    private fun trackMenu(t: Track) {
        val items = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        for (n in 2..3) {
            if (plIndex == n) {
                items.add("Прибрати з Play List $n")
                actions.add { playlists.remove(n, t.id); showPlaylist(n) }
            } else {
                items.add("Додати до Play List $n")
                actions.add { playlists.add(n, t.id); toast("Додано до Play List $n") }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(t.title)
            .setItems(items.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private inner class TrackAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = shown[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(this@MainActivity).also {
                it.setPadding(dp(14), dp(10), dp(14), dp(10))
                it.textSize = 15f
            }
            val t = shown[position]
            tv.text = "${position + 1}. ${t.title}\n${t.artist} · ${fmtTime(t.durationMs)}"
            tv.setTextColor(if (t.id == current?.id) Theme.ACCENT else Theme.TEXT)
            return tv
        }
    }

    private fun buildEffects(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(backBar("Effects"))
        fxMain = ParamPanel(this, Params.fxColumns) { d, v -> onParamUser(d, v) }
        val sv = ScrollView(this)
        sv.addView(fxMain)
        col.addView(sv, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    private fun buildEq(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(backBar("EQ · 18 смуг"))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(12), 0, dp(12), dp(4))
        bypassMain = Switch(this)
        bypassMain.text = "Bypass  "
        bypassMain.setTextColor(Theme.TEXT)
        bypassMain.setOnCheckedChangeListener { _, on -> setBypass(on) }
        row.addView(bypassMain)
        val reset = styledButton("Скинути EQ")
        reset.setOnClickListener { resetEq() }
        row.addView(reset, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, dp(8)))
        col.addView(row)
        eqMain = ParamPanel(this, listOf(Params.eq)) { d, v -> onParamUser(d, v) }
        val sv = ScrollView(this)
        sv.addView(eqMain)
        col.addView(sv, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    private fun sectionLabel(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(Theme.ACCENT)
        t.textSize = 14f
        t.setPadding(dp(12), dp(14), dp(12), dp(4))
        return t
    }

    private fun buildEditor(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(backBar("Editor"))
        val inner = LinearLayout(this)
        inner.orientation = LinearLayout.VERTICAL

        editorTitle = TextView(this)
        editorTitle.setTextColor(Theme.TEXT)
        editorTitle.textSize = 15f
        editorTitle.gravity = Gravity.CENTER
        inner.addView(editorTitle)

        waveform = WaveformView(this, engine)
        waveform.onSeek = { ms -> engine.seekTo(ms) }
        waveform.onChanged = { scheduleSave(); updateTrimButton() }
        inner.addView(waveform, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(150), 0f, dp(6)))

        val r1 = LinearLayout(this)
        r1.orientation = LinearLayout.HORIZONTAL
        val bStart = styledButton("Початок тут")
        bStart.setOnClickListener { setTrimStart() }
        val bEnd = styledButton("Кінець тут")
        bEnd.setOnClickListener { setTrimEnd() }
        trimBtn = styledButton("Обрізка: вимк.")
        trimBtn.setOnClickListener { toggleTrim() }
        for (b in listOf(bStart, bEnd, trimBtn)) r1.addView(b, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, dp(3)))
        inner.addView(r1)

        val r2 = LinearLayout(this)
        r2.orientation = LinearLayout.HORIZONTAL
        val bPrev = styledButton("◀ Мітка")
        bPrev.setOnClickListener { jumpMarker(-1) }
        val bAdd = styledButton("+ Мітка")
        bAdd.setOnClickListener { addMarker() }
        val bNext = styledButton("Мітка ▶")
        bNext.setOnClickListener { jumpMarker(1) }
        val bReset = styledButton("Скинути обрізку")
        bReset.setOnClickListener { resetTrim() }
        for (b in listOf(bPrev, bAdd, bNext, bReset)) r2.addView(b, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, dp(3)))
        inner.addView(r2)

        inner.addView(sectionLabel("Автоматизація (стиль FL Studio): ефекти, еквалайзер, гучність"))
        val spinner = Spinner(this)
        val names = Params.all.map { if (it.id.startsWith("eq.")) "EQ ${it.label}" else it.label }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                laneDef = Params.all[pos]
                bindLane()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        inner.addView(spinner)
        autoView = AutomationView(this, engine)
        autoView.onChanged = { scheduleSave() }
        inner.addView(autoView, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(150), 0f, dp(6)))
        val clear = styledButton("Очистити доріжку")
        clear.setOnClickListener {
            settings.lane(laneDef.id).clear()
            autoView.invalidate()
            scheduleSave()
        }
        inner.addView(clear, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, dp(6)))

        inner.addView(sectionLabel("Ефекти"))
        fxEditor = ParamPanel(this, Params.fxColumns) { d, v -> onParamUser(d, v) }
        inner.addView(fxEditor)
        val stampFx = styledButton("Add a stamp on Automation Track (ефект, що щойно змінено)")
        stampFx.setOnClickListener { stamp("effect.") }
        inner.addView(stampFx, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, dp(6)))

        inner.addView(sectionLabel("Еквалайзер"))
        bypassEditor = Switch(this)
        bypassEditor.text = "Bypass  "
        bypassEditor.setTextColor(Theme.TEXT)
        bypassEditor.setOnCheckedChangeListener { _, on -> setBypass(on) }
        inner.addView(bypassEditor, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, dp(8)))
        eqEditor = ParamPanel(this, listOf(Params.eq)) { d, v -> onParamUser(d, v) }
        inner.addView(eqEditor)
        val stampEq = styledButton("Add A stamp on Automatization (змінені смуги EQ)")
        stampEq.setOnClickListener { stamp("eq.band.") }
        inner.addView(stampEq, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, dp(6)))

        val sv = ScrollView(this)
        sv.addView(inner)
        col.addView(sv, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    private fun buildTransport(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(Theme.PANEL)
        col.setPadding(dp(8), dp(4), dp(8), dp(8))
        seek = SeekBar(this)
        seek.max = 1000
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar?) { seeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                seeking = false
                val (a, b) = range()
                engine.seekTo(a + (b - a) * (sb?.progress ?: 0) / 1000)
            }
        })
        col.addView(seek)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        timeView = TextView(this)
        timeView.setTextColor(Theme.TEXT)
        timeView.text = "0:00 / 0:00"
        row.addView(timeView, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val prev = styledButton("⏮")
        prev.setOnClickListener { prevTrack() }
        playBtn = styledButton("▶")
        playBtn.setOnClickListener { onPlayPause() }
        val next = styledButton("⏭")
        next.setOnClickListener { step(1) }
        for (b in listOf(prev, playBtn, next)) row.addView(b, lp(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT, 0f, dp(3)))
        col.addView(row)
        return col
    }

    // ---------------------------------------------------------------- відтворення

    private fun range(): Pair<Long, Long> {
        val s = settings
        val dur = engine.durationMs
        val a = if (s.trimEnabled) s.trimStartMs else 0L
        val b = if (s.trimEnabled && s.trimEndMs > a) s.trimEndMs else dur
        return Pair(a, max(a + 1, b))
    }

    private fun playTrack(t: Track) {
        flushSave()
        current = t
        settings = TrackStore.load(this, t)
        dirty.clear()
        engine.load(t, settings, true)
        nowPlaying.text = "${t.artist} — ${t.title}"
        visualizer.cover = null
        Thread {
            val bmp = Library.cover(this, t.uri)
            runOnUiThread { if (current?.id == t.id) visualizer.cover = bmp }
        }.start()
        adapter.notifyDataSetChanged()
        refreshPanels()
        bindLane()
        if (currentScreen == "editor") { editorTitle.text = t.displayName; ensurePeaks() } else waveform.peaks = peaksCache[t.id]
        updateTrimButton()
        startForegroundService(Intent(this, PlaybackService::class.java).putExtra("title", t.title))
    }

    private fun onPlayPause() {
        if (current == null) {
            (if (shown.isNotEmpty()) shown else allTracks).firstOrNull()?.let { playTrack(it) }
        } else {
            engine.toggle()
        }
    }

    private fun step(delta: Int) {
        val list = if (shown.isNotEmpty()) shown else allTracks
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.id == current?.id }
        val n = if (i < 0) 0 else (i + delta + list.size) % list.size
        playTrack(list[n])
    }

    private fun prevTrack() {
        val (a, _) = range()
        if (engine.positionMs - a > 3000) engine.seekTo(a) else step(-1)
    }

    override fun onTrackEnded() { step(1) }
    override fun onError(message: String) { toast(message) }

    // ---------------------------------------------------------------- параметри, автоматизація, обрізка

    private fun onParamUser(d: ParamDef, v: Float) {
        settings.values[d.id] = v
        dirty.add(d.id)
        // дзеркалимо значення в інші панелі
        for (p in listOf(fxMain, fxEditor, eqMain, eqEditor)) p.setIds(listOf(d.id)) { v }
        if (laneDef.id == d.id) autoView.invalidate()
        scheduleSave()
    }

    private fun refreshPanels() {
        val pos = engine.positionMs
        val get: (String) -> Float = { id -> engine.valueAt(id, pos) }
        for (p in listOf(fxMain, fxEditor, eqMain, eqEditor)) p.setAll(get)
        bypassMain.setOnCheckedChangeListener(null)
        bypassEditor.setOnCheckedChangeListener(null)
        bypassMain.isChecked = settings.eqBypass
        bypassEditor.isChecked = settings.eqBypass
        bypassMain.setOnCheckedChangeListener { _, on -> setBypass(on) }
        bypassEditor.setOnCheckedChangeListener { _, on -> setBypass(on) }
    }

    private fun setBypass(on: Boolean) {
        settings.eqBypass = on
        bypassMain.isChecked = on
        bypassEditor.isChecked = on
        scheduleSave()
    }

    private fun resetEq() {
        for (p in Params.eq) settings.values[p.id] = 0f
        refreshPanels()
        scheduleSave()
    }

    private fun bindLane() {
        autoView.manualValue = { settings.values[laneDef.id] ?: laneDef.def }
        autoView.bind(laneDef, settings.lane(laneDef.id))
    }

    /** Кнопка «stamp»: ставить вузол на доріжку для кожного щойно зміненого параметра. */
    private fun stamp(prefix: String) {
        val ids = dirty.filter { it.startsWith(prefix) }
        if (ids.isEmpty()) {
            toast("Спочатку змініть слайдер")
            return
        }
        val t = engine.positionMs
        for (id in ids) {
            val lane = settings.lane(id)
            val v = settings.values[id] ?: Params.byId[id]?.def ?: 0f
            val near = lane.firstOrNull { abs(it.t - t) < 150 }
            if (near != null) near.v = v else lane.add(AutoPoint(t, v))
            lane.sortBy { it.t }
            dirty.remove(id)
        }
        autoView.invalidate()
        scheduleSave()
        toast("Вузлів додано: ${ids.size}")
    }

    private fun setTrimStart() {
        val t = engine.positionMs
        if (settings.trimEndMs in 1..t) { toast("Початок має бути раніше за кінець"); return }
        settings.trimStartMs = t
        settings.trimEnabled = true
        afterTrimChange()
    }

    private fun setTrimEnd() {
        val t = engine.positionMs
        if (t <= settings.trimStartMs) { toast("Кінець має бути пізніше за початок"); return }
        settings.trimEndMs = t
        settings.trimEnabled = true
        afterTrimChange()
    }

    private fun toggleTrim() {
        settings.trimEnabled = !settings.trimEnabled
        if (settings.trimEnabled && settings.trimEndMs <= 0) settings.trimEndMs = engine.durationMs
        afterTrimChange()
    }

    private fun resetTrim() {
        settings.trimEnabled = false
        settings.trimStartMs = 0L
        settings.trimEndMs = -1L
        afterTrimChange()
    }

    private fun afterTrimChange() {
        updateTrimButton()
        waveform.invalidate()
        scheduleSave()
    }

    private fun updateTrimButton() {
        trimBtn.text = if (settings.trimEnabled) "Обрізка: увімк." else "Обрізка: вимк."
    }

    private fun addMarker() {
        if (current == null) return
        settings.markers.add(Marker(engine.positionMs, "Мітка ${settings.markers.size + 1}"))
        settings.markers.sortBy { it.t }
        waveform.invalidate()
        scheduleSave()
    }

    private fun jumpMarker(dir: Int) {
        val pos = engine.positionMs
        val m = if (dir > 0) settings.markers.filter { it.t > pos + 500 }.minByOrNull { it.t }
        else settings.markers.filter { it.t < pos - 1500 }.maxByOrNull { it.t }
        if (m != null) engine.seekTo(m.t) else toast("Міток там немає")
    }

    private fun ensurePeaks() {
        val t = current ?: return
        peaksCache[t.id]?.let { waveform.peaks = it; return }
        waveform.peaks = null
        peaksJobFor = t.id
        Thread {
            val p = WaveformExtractor.extract(this, t, 1000) { peaksJobFor != t.id }
            if (p != null) runOnUiThread {
                peaksCache[t.id] = p
                if (current?.id == t.id) waveform.peaks = p
            }
        }.start()
    }

    // ---------------------------------------------------------------- збереження та таймер

    private val saveRunnable = Runnable { flushSave() }

    private fun scheduleSave() {
        ui.removeCallbacks(saveRunnable)
        ui.postDelayed(saveRunnable, 700)
    }

    private fun flushSave() {
        ui.removeCallbacks(saveRunnable)
        current?.let { TrackStore.save(this, it, settings) }
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            ui.postDelayed(this, 100)
        }
    }

    private fun tick() {
        val pos = engine.positionMs
        val (a, b) = range()
        if (!seeking) seek.progress = (((pos - a) * 1000) / (b - a)).toInt().coerceIn(0, 1000)
        timeView.text = "${fmtTime(pos - a)} / ${fmtTime(b - a)}"
        playBtn.text = if (engine.wantPlaying) "⏸" else "▶"
        // слайдери «рухаються» за автоматизацією
        val automated = settings.lanes.filter { it.value.isNotEmpty() }.keys
        if (automated.isNotEmpty() && currentScreen != "main") {
            val get: (String) -> Float = { id -> engine.valueAt(id, pos) }
            for (p in listOf(fxMain, fxEditor, eqMain, eqEditor)) p.setIds(automated, get)
        }
        if (currentScreen == "editor") {
            waveform.invalidate()
            autoView.invalidate()
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
