package com.example.aimpmobile

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/**
 * Єдина Activity з екранами: main, effects, eq, editor, settings, import.
 * Весь стан відтворення живе в Controller/Core, тому поворот екрана, зміна теми чи мови музику не переривають.
 */
class MainActivity : Activity(), Controller.Ui {
    private val ui = Handler(Looper.getMainLooper())
    private val engine get() = Core.engine
    private val prefs get() = Core.prefs
    private val settings get() = Controller.settings

    private var addMusicBtn: android.widget.Button? = null
    private var pendingPl = 0L // плейлист, у який додаємо музику з picker-а
    private var currentPl = 0L // 0 = «Уся музика», інакше id власного плейлиста
    private var query = ""
    private var shown: List<Track> = emptyList()
    private var shownTrackId = -1L
    private val peaksCache = HashMap<Long, FloatArray>()
    @Volatile private var peaksJobFor = -1L
    private val dirty = LinkedHashSet<String>()

    private val screens = HashMap<String, View>()
    private var currentScreen = "main"
    private lateinit var titleView: TextView
    private lateinit var content: FrameLayout
    private lateinit var visualizer: VisualizerView
    private lateinit var listView: ListView
    private val adapter = TrackAdapter()
    private lateinit var tabsRow: LinearLayout
    private lateinit var nowPlaying: TextView
    private lateinit var seek: SeekBar
    private lateinit var timeView: TextView
    private lateinit var playBtn: android.widget.Button
    private lateinit var icVolume: TransportIcon
    private lateinit var icOrder: TransportIcon
    private lateinit var icRepeat: TransportIcon
    private lateinit var icSpeed: TransportIcon
    private var seeking = false

    private lateinit var waveform: WaveformView
    private lateinit var autoView: AutomationView
    private lateinit var editorTitle: TextView
    private lateinit var selectBtn: android.widget.Button
    private lateinit var onlyBtn: android.widget.Button
    private lateinit var fxMain: ParamPanel
    private lateinit var fxEditor: ParamPanel
    private lateinit var eqMain: ParamPanel
    private lateinit var eqEditor: ParamPanel
    private lateinit var bypassMain: android.widget.Switch
    private lateinit var bypassEditor: android.widget.Switch
    private var laneDef: ParamDef = Params.volume
    private var pendingA = -1L
    private lateinit var importResult: LinearLayout

    // ---------------------------------------------------------------- життєвий цикл

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(applicationContext)
        Core.applyAppearance()
        Controller.ui = this
        applyWindow()
        buildUi()
        if (Controller.all.isEmpty()) ensurePermissions() else refreshList()
        onTrackChanged()
        updateTransport()
        ui.post(ticker)
    }

    @Suppress("DEPRECATION")
    private fun applyWindow() {
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        var f = 0
        if (Theme.isLight) {
            f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 27) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = f
    }

    override fun onStart() {
        super.onStart()
        Controller.editorOpen = currentScreen == "editor"
    }

    override fun onStop() {
        Controller.editorOpen = false // у фоні трек не зациклюємо
        Controller.saveSettings()
        Controller.persistPos()
        super.onStop()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        if (Controller.ui === this) Controller.ui = null
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
        if (requestCode == REQ_TAG_PERM) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) performTagWrite() else toast(S.t("tags.fail"))
            return
        }
        scan()
    }

    private fun scan() {
        Thread {
            val list = Library.scan(this)
            runOnUiThread {
                Controller.libraryLoaded(list)
                refreshList()
                onTrackChanged()
                if (list.isEmpty()) toast(S.t("msg.nomusic"))
            }
        }.start()
    }

    // ---------------------------------------------------------------- допоміжне

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun lp(w: Int, h: Int, weight: Float = 0f, margin: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h, weight)
        p.setMargins(margin, margin, margin, margin)
        return p
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun promptText(titleKey: String, initial: String, onOk: (String) -> Unit) {
        val et = EditText(this)
        et.setText(initial)
        et.setSelection(initial.length)
        et.setSingleLine()
        AlertDialog.Builder(this)
            .setTitle(S.t(titleKey))
            .setView(et)
            .setPositiveButton(S.t("ok")) { _, _ -> onOk(et.text.toString()) }
            .setNegativeButton(S.t("cancel"), null)
            .show()
    }

    // ---------------------------------------------------------------- побудова інтерфейсу

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Theme.BG)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(12), dp(6), dp(8), dp(6))
        titleView = label("AIMP Mobile", 16f, Theme.ACCENT)
        bar.addView(titleView, lp(0, WRAP, 1f))
        val bImport = styledButton(S.t("import.btn"))
        bImport.minimumHeight = dp(36)
        bImport.setOnClickListener { show("import") }
        val bSettings = styledButton(S.t("settings"))
        bSettings.minimumHeight = dp(36)
        bSettings.setOnClickListener { show("settings") }
        bar.addView(bImport, lp(WRAP, WRAP, 0f, dp(3)))
        bar.addView(bSettings, lp(WRAP, WRAP, 0f, dp(3)))
        root.addView(bar)

        content = FrameLayout(this)
        root.addView(content, lp(MATCH, 0, 1f))
        screens["main"] = buildMain()
        screens["effects"] = buildEffects()
        screens["eq"] = buildEq()
        screens["editor"] = buildEditor()
        screens["settings"] = buildSettings()
        screens["import"] = buildImport()
        for (v in screens.values) content.addView(v)

        root.addView(buildTransport())
        setContentView(root)
        show("main")
    }

    private fun show(name: String) {
        currentScreen = name
        for ((k, v) in screens) v.visibility = if (k == name) View.VISIBLE else View.GONE
        Controller.editorOpen = name == "editor"
        titleView.text = when (name) {
            "effects" -> S.t("effects")
            "eq" -> S.t("eq.title")
            "editor" -> S.t("editor")
            "settings" -> S.t("settings")
            "import" -> S.t("import.title")
            else -> "AIMP Mobile"
        }
        if (name == "editor") {
            editorTitle.text = Controller.current?.displayName ?: S.t("editor.pick")
            ensurePeaks()
            bindLane()
        }
        refreshPanels()
    }

    private fun backBar(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(8), dp(2), dp(8), dp(6))
        val back = styledButton("← " + S.t("back"))
        back.setOnClickListener { show("main") }
        bar.addView(back)
        return bar
    }

    private fun buildMain(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.VERTICAL
        for ((key, scr) in listOf("editor" to "editor", "effects" to "effects", "eq.short" to "eq")) {
            val b = styledButton(S.t(key))
            b.setOnClickListener { show(scr) }
            buttons.addView(b, lp(dp(100), WRAP, 0f, dp(6)))
        }
        top.addView(buttons)

        visualizer = VisualizerView(this, engine, prefs.viz)
        visualizer.setOnClickListener { visualizer.showCover = !visualizer.showCover }
        visualizer.setOnLongClickListener {
            prefs.viz.mode = (prefs.viz.mode + 1) % 4
            prefs.save()
            true
        }
        top.addView(visualizer, lp(0, dp(190), 1f, dp(6)))
        col.addView(top)

        nowPlaying = label(S.t("viz.hint"), 13f, Theme.DIM)
        nowPlaying.setPadding(dp(12), dp(2), dp(12), dp(2))
        col.addView(nowPlaying)

        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        tabsRow = LinearLayout(this)
        tabsRow.orientation = LinearLayout.HORIZONTAL
        hs.addView(tabsRow)
        col.addView(hs)

        val addMusic = styledButton(S.t("pl.addmusic"))
        addMusic.visibility = View.GONE
        addMusic.setOnClickListener { addMusicDialog() }
        addMusicBtn = addMusic
        col.addView(addMusic, lp(MATCH, WRAP, 0f, dp(4)))

        val search = EditText(this)
        search.hint = S.t("search.hint")
        search.setHintTextColor(Theme.DIM)
        search.setTextColor(Theme.TEXT)
        search.setSingleLine()
        search.textSize = 14f
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                refreshList()
            }

            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        col.addView(search, lp(MATCH, WRAP, 0f, dp(4)))

        listView = ListView(this)
        listView.adapter = adapter
        listView.setBackgroundColor(Theme.BG)
        listView.divider = null
        listView.setOnItemClickListener { _, _, pos, _ ->
            val t = shown[pos]
            val queue = if (currentPl == 0L) shown else shown.filter { it.id == t.id || !Core.playlists.isDisabled(currentPl, it.id) }
            Controller.play(t, queue)
        }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            trackMenu(shown[pos])
            true
        }
        col.addView(listView, lp(MATCH, 0, 1f))
        return col
    }

    // ---- плейлисти
    private fun rebuildTabs() {
        tabsRow.removeAllViews()
        fun tab(text: String, selected: Boolean): TextView {
            val tv = label(text, 14f, if (selected) Theme.ACCENT else Theme.DIM)
            tv.setBackgroundColor(if (selected) Theme.PANEL2 else Theme.PANEL)
            tv.setPadding(dp(16), dp(12), dp(16), dp(12))
            tabsRow.addView(tv, lp(WRAP, WRAP, 0f, dp(1)))
            return tv
        }
        tab(S.t("pl.all"), currentPl == 0L).setOnClickListener { currentPl = 0L; refreshList() }
        for (p in Core.playlists.lists) {
            val tv = tab(p.name, currentPl == p.id)
            tv.setOnClickListener { currentPl = p.id; refreshList() }
            tv.setOnLongClickListener { playlistMenu(p); true }
        }
        tab("＋", false).setOnClickListener {
            promptText("pl.new", Playlist.AUTO) { name ->
                val p = Core.playlists.create(name)
                currentPl = p.id
                refreshList()
            }
        }
    }

    private fun playlistMenu(p: Playlist) {
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(arrayOf(S.t("pl.rename"), S.t("pl.delete"), S.t("pl.enableall"))) { _, which ->
                when (which) {
                    0 -> promptText("pl.rename", p.name) { Core.playlists.rename(p.id, it); refreshList() }
                    1 -> AlertDialog.Builder(this)
                        .setMessage(S.f("pl.delete.q", p.name))
                        .setPositiveButton(S.t("ok")) { _, _ ->
                            Core.playlists.delete(p.id)
                            if (currentPl == p.id) currentPl = 0L
                            refreshList()
                        }
                        .setNegativeButton(S.t("cancel"), null)
                        .show()
                    else -> { Core.playlists.enableAll(p.id); refreshList() }
                }
            }
            .show()
    }

    private fun trackMenu(t: Track) {
        val items = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        items.add(S.t("pl.add"))
        actions.add { addToPlaylistDialog(t) }
        if (currentPl != 0L) {
            val off = Core.playlists.isDisabled(currentPl, t.id)
            items.add(S.t(if (off) "pl.enable" else "pl.disable"))
            actions.add { Core.playlists.setDisabled(currentPl, t.id, !off); refreshList() }
            items.add(S.t("pl.remove"))
            actions.add { Core.playlists.remove(currentPl, t.id); refreshList() }
        }
        items.add(S.t("tags.edit"))
        actions.add { editTagsDialog(t) }
        AlertDialog.Builder(this)
            .setTitle(t.title)
            .setItems(items.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun addToPlaylistDialog(t: Track) {
        val lists = Core.playlists.lists
        val names = lists.map { it.name } + S.t("pl.new")
        AlertDialog.Builder(this)
            .setTitle(S.t("pl.add"))
            .setItems(names.toTypedArray()) { _, which ->
                if (which < lists.size) {
                    Core.playlists.add(lists[which].id, t.id)
                    toast(S.f("pl.added", lists[which].name))
                } else {
                    promptText("pl.new", Playlist.AUTO) { name ->
                        val p = Core.playlists.create(name)
                        Core.playlists.add(p.id, t.id)
                        refreshList()
                    }
                }
            }
            .show()
    }

    private fun refreshList() {
        if (currentPl != 0L && Core.playlists.get(currentPl) == null) currentPl = 0L
        val base: List<Track> = if (currentPl == 0L) Controller.all else {
            val byId = Controller.all.associateBy { it.id }
            Core.playlists.get(currentPl)?.tracks?.mapNotNull { byId[it] } ?: emptyList()
        }
        val q = query.trim().lowercase()
        shown = if (q.isEmpty()) base else base.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q)
        }
        adapter.notifyDataSetChanged()
        rebuildTabs()
        addMusicBtn?.visibility = if (currentPl != 0L) View.VISIBLE else View.GONE
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
            val off = currentPl != 0L && Core.playlists.isDisabled(currentPl, t.id)
            tv.text = "${if (off) "☐ " else ""}${position + 1}. ${t.title}\n${t.artist} · ${fmtTime(t.durationMs)}"
            tv.setTextColor(if (t.id == Controller.current?.id) Theme.ACCENT else if (off) Theme.DIM else Theme.TEXT)
            return tv
        }
    }

    // ---- ефекти, еквалайзер
    private fun buildEffects(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(backBar())
        fxMain = ParamPanel(this, Params.fxColumns) { d, v -> onParamUser(d, v) }
        val sv = ScrollView(this)
        sv.addView(fxMain)
        col.addView(sv, lp(MATCH, 0, 1f))
        return col
    }

    private fun buildEq(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(backBar())
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(12), 0, dp(12), dp(4))
        bypassMain = themedSwitch("Bypass  ", false) { setBypass(it) }
        row.addView(bypassMain)
        val presets = styledButton(S.t("eq.presets"))
        presets.setOnClickListener { presetsDialog() }
        row.addView(presets, lp(WRAP, WRAP, 0f, dp(6)))
        val reset = styledButton(S.t("eq.reset"))
        reset.setOnClickListener { applyEq(FloatArray(18)) }
        row.addView(reset, lp(WRAP, WRAP, 0f, dp(6)))
        col.addView(row)
        eqMain = ParamPanel(this, listOf(Params.eq)) { d, v -> onParamUser(d, v) }
        val sv = ScrollView(this)
        sv.addView(eqMain)
        col.addView(sv, lp(MATCH, 0, 1f))
        return col
    }

    /** Форма кривої за 5 опорними точками (низькі … високі), лінійна інтерполяція на 18 смуг. */
    private fun shape(a: Float, b: Float, c: Float, d: Float, e: Float): FloatArray {
        val xs = intArrayOf(0, 4, 9, 13, 17)
        val ys = floatArrayOf(a, b, c, d, e)
        return FloatArray(18) { i ->
            var k = 0
            while (k < 3 && i > xs[k + 1]) k++
            val f = (i - xs[k]).toFloat() / (xs[k + 1] - xs[k])
            ys[k] + (ys[k + 1] - ys[k]) * f
        }
    }

    private fun presetsDialog() {
        val names = listOf("flat", "rock", "pop", "bass", "vocal", "treble", "classical", "electronic")
        val data = listOf(
            shape(0f, 0f, 0f, 0f, 0f), shape(4f, 2f, -1f, 3f, 4f), shape(-1f, 2f, 3f, 2f, -1f), shape(6f, 3f, 0f, 0f, 0f),
            shape(-2f, 0f, 3f, 2f, 0f), shape(0f, 0f, 0f, 3f, 6f), shape(3f, 2f, -1f, 2f, 3f), shape(5f, 2f, 0f, 2f, 4f)
        )
        AlertDialog.Builder(this)
            .setTitle(S.t("eq.presets"))
            .setItems(names.map { S.t("pre.$it") }.toTypedArray()) { _, which -> applyEq(data[which]) }
            .show()
    }

    private fun applyEq(v: FloatArray) {
        for (i in 0 until 18) settings.values["eq.band.$i"] = v[i]
        refreshPanels()
        scheduleSave()
    }

    // ---- редактор
    private fun sectionLabel(text: String): TextView {
        val t = label(text, 14f, Theme.ACCENT)
        t.setPadding(dp(12), dp(14), dp(12), dp(4))
        return t
    }

    private fun buildEditor(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(backBar())
        val inner = LinearLayout(this)
        inner.orientation = LinearLayout.VERTICAL

        editorTitle = label("", 15f)
        editorTitle.gravity = Gravity.CENTER
        inner.addView(editorTitle)
        val loopNote = label(S.t("editor.loop"), 12f, Theme.DIM)
        loopNote.gravity = Gravity.CENTER
        inner.addView(loopNote)

        waveform = WaveformView(this, engine)
        waveform.onSeek = { ms -> engine.seekTo(ms) }
        waveform.onChanged = { afterSelectionChange() }
        inner.addView(waveform, lp(MATCH, dp(150), 0f, dp(6)))

        val r1 = LinearLayout(this)
        r1.orientation = LinearLayout.HORIZONTAL
        selectBtn = styledButton("")
        selectBtn.setOnClickListener { waveform.selectMode = !waveform.selectMode; afterSelectionChange() }
        onlyBtn = styledButton("")
        onlyBtn.setOnClickListener {
            settings.selectionOnly = !settings.selectionOnly
            if (settings.selectionOnly && settings.regions.isEmpty()) toast(S.t("sel.none"))
            afterSelectionChange()
        }
        r1.addView(selectBtn, lp(0, WRAP, 1f, dp(3)))
        r1.addView(onlyBtn, lp(0, WRAP, 1f, dp(3)))
        inner.addView(r1)

        val r2 = LinearLayout(this)
        r2.orientation = LinearLayout.HORIZONTAL
        val bA = styledButton("A")
        bA.setOnClickListener { pendingA = engine.positionMs; toast(S.f("sel.a", fmtTime(pendingA))) }
        val bB = styledButton("B → +")
        bB.setOnClickListener { markB() }
        val bDel = styledButton(S.t("sel.delete"))
        bDel.setOnClickListener { waveform.deleteSelected() }
        val bClear = styledButton(S.t("sel.clear"))
        bClear.setOnClickListener {
            settings.regions = emptyList()
            waveform.selected = -1
            afterSelectionChange()
        }
        for (b in listOf(bA, bB, bDel, bClear)) r2.addView(b, lp(0, WRAP, 1f, dp(3)))
        inner.addView(r2)
        inner.addView(label(S.t("sel.hint"), 12f, Theme.DIM).also { it.setPadding(dp(12), dp(2), dp(12), dp(2)) })

        val r3 = LinearLayout(this)
        r3.orientation = LinearLayout.HORIZONTAL
        val bPrev = styledButton("◀ " + S.t("marker"))
        bPrev.setOnClickListener { jumpMarker(-1) }
        val bAdd = styledButton("+ " + S.t("marker"))
        bAdd.setOnClickListener { addMarker() }
        val bNext = styledButton(S.t("marker") + " ▶")
        bNext.setOnClickListener { jumpMarker(1) }
        for (b in listOf(bPrev, bAdd, bNext)) r3.addView(b, lp(0, WRAP, 1f, dp(3)))
        inner.addView(r3)

        inner.addView(sectionLabel(S.t("auto.title")))
        val names = Params.all.map { if (it.id.startsWith("eq.")) "EQ ${S.pl(it)}" else S.pl(it) }
        inner.addView(themedSpinner(names, 0) { pos ->
            laneDef = Params.all[pos]
            bindLane()
        })
        autoView = AutomationView(this, engine)
        autoView.onChanged = { scheduleSave() }
        inner.addView(autoView, lp(MATCH, dp(150), 0f, dp(6)))
        val clear = styledButton(S.t("auto.clear"))
        clear.setOnClickListener {
            settings.lane(laneDef.id).clear()
            autoView.invalidate()
            scheduleSave()
        }
        inner.addView(clear, lp(WRAP, WRAP, 0f, dp(6)))

        inner.addView(sectionLabel(S.t("effects")))
        fxEditor = ParamPanel(this, Params.fxColumns) { d, v -> onParamUser(d, v) }
        inner.addView(fxEditor)
        val stampFx = styledButton(S.t("stamp.fx"))
        stampFx.setOnClickListener { stamp("effect.") }
        inner.addView(stampFx, lp(MATCH, WRAP, 0f, dp(6)))

        inner.addView(sectionLabel(S.t("eq.title")))
        bypassEditor = themedSwitch("Bypass  ", false) { setBypass(it) }
        inner.addView(bypassEditor)
        eqEditor = ParamPanel(this, listOf(Params.eq)) { d, v -> onParamUser(d, v) }
        inner.addView(eqEditor)
        val stampEq = styledButton(S.t("stamp.eq"))
        stampEq.setOnClickListener { stamp("eq.band.") }
        inner.addView(stampEq, lp(MATCH, WRAP, 0f, dp(6)))

        val sv = ScrollView(this)
        sv.addView(inner)
        col.addView(sv, lp(MATCH, 0, 1f))
        return col
    }

    // ---- нижня панель керування (стиль Telegram)
    private fun buildTransport(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(Theme.PANEL)
        col.setPadding(dp(8), dp(4), dp(8), dp(8))

        val tb = LinearLayout(this)
        tb.orientation = LinearLayout.HORIZONTAL
        tb.gravity = Gravity.CENTER_VERTICAL
        timeView = label("0:00 / 0:00", 14f)
        timeView.setPadding(dp(6), 0, 0, 0)
        tb.addView(timeView, lp(0, WRAP, 1f))
        icVolume = TransportIcon(this, TransportIcon.VOLUME)
        icVolume.setOnClickListener { volumeDialog() }
        icOrder = TransportIcon(this, TransportIcon.ORDER)
        icOrder.setOnClickListener { orderMenu() }
        icRepeat = TransportIcon(this, TransportIcon.REPEAT)
        icRepeat.setOnClickListener { Controller.setRepeat((prefs.repeatMode + 1) % 3) }
        icSpeed = TransportIcon(this, TransportIcon.SPEED)
        icSpeed.setOnClickListener { speedMenu() }
        val icClose = TransportIcon(this, TransportIcon.CLOSE)
        icClose.setOnClickListener { Controller.stopAll() }
        for (v in listOf(icVolume, icOrder, icRepeat, icSpeed, icClose)) tb.addView(v)
        col.addView(tb)

        seek = SeekBar(this)
        seek.max = 1000
        val c = android.content.res.ColorStateList.valueOf(Theme.ACCENT)
        seek.progressTintList = c
        seek.thumbTintList = c
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar?) { seeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                seeking = false
                val a = act()
                engine.seekTo(Sel.toSource(a, Sel.total(a) * (sb?.progress ?: 0) / 1000))
            }
        })
        col.addView(seek)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        val prev = styledButton("⏮")
        prev.setOnClickListener { Controller.prev() }
        playBtn = styledButton("▶")
        playBtn.setOnClickListener { Controller.toggle() }
        val next = styledButton("⏭")
        next.setOnClickListener { Controller.next(false) }
        for (b in listOf(prev, playBtn, next)) row.addView(b, lp(dp(72), WRAP, 0f, dp(4)))
        col.addView(row)
        return col
    }

    private fun act(): List<Region> = settings.active(engine.durationMs)

    private fun volumeDialog() {
        val box = LinearLayout(this)
        box.setPadding(dp(24), dp(16), dp(24), dp(8))
        val sb = SeekBar(this)
        sb.max = 100
        sb.progress = (prefs.audio.masterVolume * 100).toInt()
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) { Controller.setVolume(p / 100f); updateTransport() }
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        box.addView(sb, LinearLayout.LayoutParams(MATCH, WRAP))
        AlertDialog.Builder(this).setTitle(S.t("volume")).setView(box).setPositiveButton(S.t("ok"), null).show()
    }

    private fun orderMenu() {
        val pm = PopupMenu(this, icOrder)
        val keys = listOf("order.normal", "order.reverse", "order.shuffle")
        for ((i, k) in keys.withIndex()) pm.menu.add(0, i, i, (if (prefs.orderMode == i) "✓ " else "") + S.t(k))
        pm.setOnMenuItemClickListener { Controller.setOrder(it.itemId); true }
        pm.show()
    }

    private fun speedLabel(q: Float): String =
        if (abs(q - Math.round(q)) < 0.001f) "${Math.round(q)}X" else "${q}X"

    private fun speedMenu() {
        val pm = PopupMenu(this, icSpeed)
        val values = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        for ((i, v) in values.withIndex()) pm.menu.add(0, i, i, (if (abs(prefs.audio.quickTempo - v) < 0.001f) "✓ " else "") + speedLabel(v))
        pm.setOnMenuItemClickListener { Controller.setQuickTempo(values[it.itemId]); true }
        pm.show()
    }

    private fun updateTransport() {
        icVolume.set(prefs.audio.masterVolume < 0.999f)
        icOrder.set(prefs.orderMode != 0, if (prefs.orderMode == 2) 2 else 0)
        icRepeat.set(prefs.repeatMode != 0, if (prefs.repeatMode == 2) 1 else 0)
        val q = prefs.audio.quickTempo
        icSpeed.set(abs(q - 1f) > 0.001f, 0, speedLabel(q))
        playBtn.text = if (Controller.isPlaying) "⏸" else "▶"
    }

    // ---------------------------------------------------------------- налаштування

    private fun buildSettings(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(backBar())
        val inner = LinearLayout(this)
        inner.orientation = LinearLayout.VERTICAL

        fun slider(titleKey: String, min: Float, max: Float, value: Float, unit: String, onChange: (Float) -> Unit): SliderRow {
            val d = ParamDef("set.$titleKey", S.t(titleKey), min, max, value, unit)
            return SliderRow(this, d) { _, v -> onChange(v); prefs.save() }
        }

        // мова
        inner.addView(sectionLabel(S.t("set.language")))
        val langs = listOf("auto", "en", "uk", "ru")
        inner.addView(themedSpinner(listOf(S.t("lang.auto"), "English", "Українська", "Русский"), langs.indexOf(prefs.language).coerceAtLeast(0)) { i ->
            prefs.language = langs[i]
            prefs.save()
            recreate()
        })

        // тема
        inner.addView(sectionLabel(S.t("set.theme")))
        val themes = listOf("dark", "light", "amoled", "custom")
        inner.addView(themedSpinner(themes.map { S.t("theme.$it") }, themes.indexOf(prefs.theme).coerceAtLeast(0)) { i ->
            prefs.theme = themes[i]
            prefs.save()
            recreate()
        })
        if (prefs.theme == "custom") {
            inner.addView(ColorRow(this, S.t("col.bg"), prefs.customBg) { prefs.customBg = it })
            inner.addView(ColorRow(this, S.t("col.text"), prefs.customText) { prefs.customText = it })
            inner.addView(ColorRow(this, S.t("col.accent"), prefs.customAccent) { prefs.customAccent = it })
            val apply = styledButton(S.t("apply"))
            apply.setOnClickListener { prefs.save(); recreate() }
            inner.addView(apply, lp(WRAP, WRAP, 0f, dp(8)))
        }

        // звук
        inner.addView(sectionLabel(S.t("set.sound")))
        val modes = listOf("lim.off", "lim.hard", "lim.soft")
        inner.addView(themedSpinner(modes.map { S.t(it) }, prefs.audio.limiterMode) { prefs.audio.limiterMode = it; prefs.save() })
        inner.addView(slider("set.ceiling", -12f, 0f, prefs.audio.ceilingDb, "dB") { prefs.audio.ceilingDb = it })
        inner.addView(slider("set.preamp", -12f, 0f, prefs.audio.preampDb, "dB") { prefs.audio.preampDb = it })
        inner.addView(themedSwitch(S.t("set.headroom"), prefs.audio.autoHeadroom) { prefs.audio.autoHeadroom = it; prefs.save() })
        inner.addView(themedSwitch(S.t("set.mono"), prefs.audio.mono) { prefs.audio.mono = it; prefs.save() })
        inner.addView(label(S.t("set.sound.note"), 12f, Theme.DIM).also { it.setPadding(dp(12), dp(2), dp(12), dp(2)) })

        // візуалізація
        val vs = prefs.viz
        inner.addView(sectionLabel(S.t("set.viz")))
        inner.addView(themedSpinner(listOf("viz.bars", "viz.scope", "viz.meter", "viz.custom").map { S.t(it) }, vs.mode) { vs.mode = it; prefs.save() })
        inner.addView(slider("set.fps", 10f, 90f, vs.fps.toFloat(), "fps") { vs.fps = it.toInt() })
        inner.addView(slider("set.fall", 0.5f, 0.97f, vs.fall, "f2") { vs.fall = it })
        inner.addView(label(S.t("set.viz.custom"), 13f, Theme.ACCENT).also { it.setPadding(dp(12), dp(10), dp(12), dp(2)) })
        inner.addView(slider("set.bars", 4f, 128f, vs.bars.toFloat(), "n") { vs.bars = it.toInt() })
        inner.addView(slider("set.thick", 1f, 24f, vs.thicknessDp.toFloat(), "dp") { vs.thicknessDp = it.toInt() })
        inner.addView(ColorRow(this, S.t("col.line1"), vs.color1) { vs.color1 = it; prefs.save() })
        inner.addView(themedSwitch(S.t("set.gradient"), vs.gradient) { vs.gradient = it; prefs.save() })
        inner.addView(ColorRow(this, S.t("col.line2"), vs.color2) { vs.color2 = it; prefs.save() })
        inner.addView(themedSwitch(S.t("set.rainbow"), vs.rainbow) { vs.rainbow = it; prefs.save() })
        inner.addView(themedSwitch(S.t("set.mirror"), vs.mirror) { vs.mirror = it; prefs.save() })
        inner.addView(themedSwitch(S.t("set.peaks"), vs.peaks) { vs.peaks = it; prefs.save() })
        inner.addView(themedSwitch(S.t("set.rounded"), vs.rounded) { vs.rounded = it; prefs.save() })

        // відтворення
        inner.addView(sectionLabel(S.t("set.playback")))
        val sleep = styledButton(S.t("sleep"))
        sleep.setOnClickListener { sleepDialog() }
        inner.addView(sleep, lp(WRAP, WRAP, 0f, dp(8)))

        // про програму
        inner.addView(sectionLabel(S.t("set.about")))
        val avatar = object : View(this) {
            override fun onDraw(c: android.graphics.Canvas) {
                ProceduralArt.draw(c, width.toFloat(), height.toFloat(), 2026L, Theme.VIEWBG, Theme.ACCENT, context.dpf(1f))
            }
        }
        inner.addView(avatar, lp(dp(140), dp(110), 0f, dp(12)))
        inner.addView(label("AIMP Mobile 0.2", 13f, Theme.DIM).also { it.setPadding(dp(12), 0, dp(12), dp(24)) })

        val sv = ScrollView(this)
        sv.addView(inner)
        col.addView(sv, lp(MATCH, 0, 1f))
        return col
    }

    private fun sleepDialog() {
        val mins = listOf(0, 15, 30, 45, 60, 90)
        AlertDialog.Builder(this)
            .setTitle(S.t("sleep"))
            .setItems(mins.map { if (it == 0) S.t("sleep.off") else S.f("sleep.min", it) }.toTypedArray()) { _, which ->
                Controller.setSleep(mins[which])
                toast(if (mins[which] == 0) S.t("sleep.off") else S.f("sleep.min", mins[which]))
            }
            .show()
    }

    // ---------------------------------------------------------------- імпорт списків та пошук у сервісах

    private fun buildImport(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(backBar())
        val inner = LinearLayout(this)
        inner.orientation = LinearLayout.VERTICAL

        inner.addView(sectionLabel(S.t("import.file")))
        inner.addView(label(S.t("import.help"), 13f, Theme.DIM).also { it.setPadding(dp(12), dp(2), dp(12), dp(6)) })
        val pick = styledButton(S.t("import.pick"))
        pick.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            startActivityForResult(i, 77)
        }
        inner.addView(pick, lp(WRAP, WRAP, 0f, dp(8)))
        importResult = LinearLayout(this)
        importResult.orientation = LinearLayout.VERTICAL
        inner.addView(importResult)

        inner.addView(sectionLabel(S.t("import.search")))
        val q = EditText(this)
        q.hint = S.t("import.query")
        q.setHintTextColor(Theme.DIM)
        q.setTextColor(Theme.TEXT)
        q.setSingleLine()
        inner.addView(q, lp(MATCH, WRAP, 0f, dp(8)))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for ((i, name) in listOf("Spotify", "SoundCloud", "YouTube Music").withIndex()) {
            val b = styledButton(name)
            b.setOnClickListener { if (q.text.isNotBlank()) openSearch(i, q.text.toString()) }
            row.addView(b, lp(0, WRAP, 1f, dp(3)))
        }
        inner.addView(row)
        inner.addView(label(S.t("import.note"), 12f, Theme.DIM).also { it.setPadding(dp(12), dp(10), dp(12), dp(24)) })

        val sv = ScrollView(this)
        sv.addView(inner)
        col.addView(sv, lp(MATCH, 0, 1f))
        return col
    }

    private fun openSearch(service: Int, q: String) {
        val enc = Uri.encode(q)
        val url = when (service) {
            0 -> "https://open.spotify.com/search/$enc"
            1 -> "https://soundcloud.com/search?q=$enc"
            else -> "https://music.youtube.com/search?q=$enc"
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast(S.t("msg.noapp"))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == 77 && resultCode == RESULT_OK && uri != null) importFile(uri)
        if (requestCode == REQ_TAG_WRITE) {
            if (resultCode == RESULT_OK) performTagWrite() else toast(S.t("tags.fail"))
            return
        }
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == REQ_PICK_FILES) {
            val uris = ArrayList<Uri>()
            val clip = data.clipData
            if (clip != null) for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
            else if (uri != null) uris.add(uri)
            if (uris.isNotEmpty()) addPicked(pendingPl, null) { PickImport.fromFiles(this, uris) }
        } else if (requestCode == REQ_PICK_FOLDER && uri != null) {
            addPicked(pendingPl, { PickImport.treeName(this, uri) }) { PickImport.fromTree(this, uri) }
        }
    }

    // ---- додати музику у плейлист (файли або папка)
    private val REQ_PICK_FILES = 78
    private val REQ_PICK_FOLDER = 79
    private val REQ_TAG_WRITE = 80
    private val REQ_TAG_PERM = 2

    private fun addMusicDialog() {
        if (currentPl == 0L) return
        pendingPl = currentPl
        AlertDialog.Builder(this)
            .setTitle(S.t("pl.addmusic"))
            .setItems(arrayOf(S.t("pl.pick.files"), S.t("pl.pick.folder"))) { _, which ->
                try {
                    if (which == 0) {
                        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                        i.addCategory(Intent.CATEGORY_OPENABLE)
                        i.type = "audio/*"
                        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        startActivityForResult(i, REQ_PICK_FILES)
                    } else {
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PICK_FOLDER)
                    }
                } catch (e: Exception) {
                    toast(S.t("pl.nopicker"))
                }
            }
            .show()
    }

    /** folderName != null — додаємо папку: плейлист з назвою <AutoName> отримає ім'я цієї папки. */
    private fun addPicked(plId: Long, folderName: (() -> String)?, load: () -> List<Picked>) {
        Thread {
            val picked = load()
            val name = folderName?.invoke()
            var (ids, missing) = PickImport.match(picked, Controller.all)
            var fresh: List<Track>? = null
            val paths = missing.mapNotNull { it.path }
            if (paths.isNotEmpty()) { // файли ще не в MediaStore — просимо систему проіндексувати
                PickImport.scanPaths(this, paths)
                val list = Library.scan(this)
                fresh = list
                val r = PickImport.match(picked, list)
                ids = r.first
                missing = r.second
            }
            val missingCount = missing.size
            runOnUiThread {
                fresh?.let { Controller.libraryLoaded(it) }
                val p = Core.playlists.get(plId) ?: return@runOnUiThread
                val before = p.tracks.size
                Core.playlists.addAll(plId, ids)
                val added = p.tracks.size - before
                if (!name.isNullOrBlank() && p.name == Playlist.AUTO && ids.isNotEmpty()) Core.playlists.rename(plId, name)
                refreshList()
                toast(
                    when {
                        ids.isEmpty() -> S.t("pl.addmusic.none")
                        missingCount > 0 -> S.f("pl.addmusic.done", added) + "\n" + S.f("pl.addmusic.skipped", missingCount)
                        else -> S.f("pl.addmusic.done", added)
                    }
                )
            }
        }.start()
    }

    // ---- редактор тегів
    private var tagTrack: Track? = null
    private var tagOrig = Tags()
    private var tagNew = Tags()

    private fun editTagsDialog(t: Track) {
        if (!TagEditor.supported(t.displayName)) { toast(S.t("tags.unsupported")); return }
        Thread {
            var tags: Tags? = null
            var unsupported = false
            try {
                tags = TagEditor.read(this, t.uri, t.displayName)
            } catch (e: TagUnsupported) {
                unsupported = true
            } catch (e: Exception) {
            }
            val res = tags
            val uns = unsupported
            runOnUiThread {
                when {
                    res != null -> showTagForm(t, res)
                    uns -> toast(S.t("tags.unsupported2"))
                    else -> toast(S.t("msg.readfail"))
                }
            }
        }.start()
    }

    private fun showTagForm(t: Track, orig: Tags) {
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(8), dp(16), dp(8))
        sv.addView(col)
        fun field(labelKey: String, value: String): EditText {
            val l = TextView(this)
            l.text = S.t(labelKey)
            l.textSize = 12f
            col.addView(l)
            val et = EditText(this)
            et.setText(value)
            et.setSingleLine()
            col.addView(et)
            return et
        }
        val fTitle = field("tags.title", orig.title)
        val fArtist = field("tags.artist", orig.artist)
        val fAlbum = field("tags.album", orig.album)
        val fYear = field("tags.year", orig.year)
        val fTrack = field("tags.track", orig.track)
        val fGenre = field("tags.genre", orig.genre)
        AlertDialog.Builder(this)
            .setTitle(S.t("tags.edit") + " — " + t.displayName)
            .setView(sv)
            .setPositiveButton(S.t("tags.save")) { _, _ ->
                val n = Tags(
                    fTitle.text.toString().trim(), fArtist.text.toString().trim(), fAlbum.text.toString().trim(),
                    fYear.text.toString().trim(), fTrack.text.toString().trim(), fGenre.text.toString().trim()
                )
                if (n != orig) saveTags(t, orig, n)
            }
            .setNegativeButton(S.t("cancel"), null)
            .show()
    }

    /** Запис у чужий медіафайл потребує дозволу: Android 11+ — createWriteRequest, 10 — RecoverableSecurityException, ≤9 — WRITE_EXTERNAL_STORAGE. */
    private fun saveTags(t: Track, orig: Tags, new: Tags) {
        tagTrack = t; tagOrig = orig; tagNew = new
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val pi = MediaStore.createWriteRequest(contentResolver, listOf(t.uri))
                startIntentSenderForResult(pi.intentSender, REQ_TAG_WRITE, null, 0, 0, 0)
            } catch (e: Exception) {
                toast(S.t("tags.fail"))
            }
        } else if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_TAG_PERM)
        } else {
            performTagWrite()
        }
    }

    private fun performTagWrite() {
        val t = tagTrack ?: return
        val orig = tagOrig
        val new = tagNew
        val wasCurrent = Controller.current?.id == t.id
        val pos = if (wasCurrent) engine.positionMs else 0L
        if (wasCurrent) Controller.pause() // файл не можна переписувати, поки він грає
        Thread {
            var err: Exception? = null
            try {
                TagEditor.write(this, t.uri, t.displayName, orig, new)
                TagEditor.updateMediaStore(this, t.uri, new)
            } catch (e: Exception) {
                err = e
            }
            val list = if (err == null) Library.scan(this) else null
            runOnUiThread {
                val e = err
                if (e != null) {
                    if (Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException) {
                        try {
                            startIntentSenderForResult(e.userAction.actionIntent.intentSender, REQ_TAG_WRITE, null, 0, 0, 0)
                        } catch (x: Exception) {
                            toast(S.t("tags.fail"))
                        }
                    } else if (e is TagUnsupported) toast(S.t("tags.unsupported2")) else toast(S.t("tags.fail"))
                } else {
                    Controller.libraryLoaded(list ?: Controller.all)
                    if (wasCurrent) Controller.reloadCurrent(pos)
                    refreshList()
                    toast(S.t("tags.saved"))
                }
            }
        }.start()
    }

    private fun importFile(uri: Uri) {
        Thread {
            var text = ""
            var name = "Imported"
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) name = it.getString(0)?.substringBeforeLast('.') ?: name
                }
                text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
            } catch (e: Exception) {
                runOnUiThread { toast(S.t("msg.readfail")) }
                return@Thread
            }
            val wanted = parseWanted(text)
            val lib = Controller.all
            val byTitle = lib.map { it.title to it.artist }
            val byFile = lib.map { it.baseName to it.artist }
            val matched = ArrayList<Long>()
            val missing = ArrayList<Wanted>()
            for (w in wanted) {
                var i = ImportParser.bestMatch(w, byTitle)
                if (i < 0) i = ImportParser.bestMatch(w, byFile)
                if (i >= 0) matched.add(lib[i].id) else missing.add(w)
            }
            runOnUiThread {
                if (wanted.isEmpty()) { toast(S.t("msg.emptyimport")); return@runOnUiThread }
                if (matched.isNotEmpty()) {
                    val p = Core.playlists.create(name)
                    Core.playlists.addAll(p.id, matched.distinct())
                    currentPl = p.id
                    refreshList()
                }
                showImportResult(wanted.size, matched.distinct().size, missing)
            }
        }.start()
    }

    private fun showImportResult(total: Int, found: Int, missing: List<Wanted>) {
        importResult.removeAllViews()
        importResult.addView(label(S.f("import.result", found, total), 14f, Theme.ACCENT).also { it.setPadding(dp(12), dp(6), dp(12), dp(6)) })
        if (missing.isNotEmpty()) importResult.addView(label(S.t("import.missing"), 12f, Theme.DIM).also { it.setPadding(dp(12), 0, dp(12), dp(4)) })
        for (w in missing.take(60)) {
            val tv = label("• ${if (w.artist.isNotEmpty()) w.artist + " — " else ""}${w.title}", 14f)
            tv.setPadding(dp(14), dp(8), dp(14), dp(8))
            tv.setOnClickListener { openSearch(0, "${w.artist} ${w.title}".trim()) }
            tv.setOnLongClickListener {
                AlertDialog.Builder(this).setItems(arrayOf("Spotify", "SoundCloud", "YouTube Music")) { _, i ->
                    openSearch(i, "${w.artist} ${w.title}".trim())
                }.show()
                true
            }
            importResult.addView(tv)
        }
    }

    private fun parseWanted(text: String): List<Wanted> {
        val t = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (t.startsWith("{") || t.startsWith("[")) {
            return try {
                val out = ArrayList<Wanted>()
                collectJson(if (t.startsWith("[")) JSONArray(t) else JSONObject(t), out)
                out
            } catch (e: Exception) {
                emptyList()
            }
        }
        return ImportParser.parse(text)
    }

    private fun jsonString(o: JSONObject, keys: List<String>): String? {
        for (k in keys) {
            val v = o.opt(k)
            if (v is String && v.isNotBlank()) return v
        }
        return null
    }

    private fun jsonArtist(o: JSONObject): String? {
        jsonString(o, listOf("artistName", "artist_name", "artist", "author", "uploader", "channel"))?.let { return it }
        val arr = o.optJSONArray("artists")
        if (arr != null && arr.length() > 0) {
            val f = arr.opt(0)
            if (f is String) return f
            if (f is JSONObject) return jsonString(f, listOf("name", "artistName"))
        }
        val user = o.optJSONObject("user")
        if (user != null) return jsonString(user, listOf("username", "name"))
        return null
    }

    private fun collectJson(o: Any?, out: MutableList<Wanted>) {
        when (o) {
            is JSONArray -> for (i in 0 until o.length()) collectJson(o.opt(i), out)
            is JSONObject -> {
                val title = jsonString(o, listOf("trackName", "track_name", "track", "title", "song", "name"))
                val artist = jsonArtist(o)
                if (title != null && artist != null) out.add(Wanted(title, artist))
                else {
                    val keys = o.keys()
                    while (keys.hasNext()) collectJson(o.opt(keys.next()), out)
                }
            }
        }
    }

    // ---------------------------------------------------------------- параметри, автоматизація, виділення

    private fun onParamUser(d: ParamDef, v: Float) {
        settings.values[d.id] = v
        dirty.add(d.id)
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

    private fun bindLane() {
        autoView.manualValue = { settings.values[laneDef.id] ?: laneDef.def }
        autoView.bind(laneDef, settings.lane(laneDef.id))
    }

    /** Кнопка «stamp»: ставить вузол на доріжку для кожного щойно зміненого параметра. */
    private fun stamp(prefix: String) {
        val ids = dirty.filter { it.startsWith(prefix) }
        if (ids.isEmpty()) {
            toast(S.t("stamp.none"))
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
        toast(S.f("stamp.done", ids.size))
    }

    private fun updateEditorButtons() {
        selectBtn.text = S.t(if (waveform.selectMode) "sel.mode.on" else "sel.mode.off")
        onlyBtn.text = S.t(if (settings.selectionOnly) "sel.only.on" else "sel.only.off")
    }

    private fun afterSelectionChange() {
        updateEditorButtons()
        waveform.invalidate()
        scheduleSave()
    }

    private fun markB() {
        if (pendingA < 0) { toast(S.t("sel.needA")); return }
        val b = engine.positionMs
        val s = minOf(pendingA, b)
        val e = maxOf(pendingA, b)
        if (e - s < 300) { toast(S.t("sel.short")); return }
        settings.regions = Sel.normalize(settings.regions + Region(s, e))
        pendingA = -1L
        afterSelectionChange()
    }

    private fun addMarker() {
        if (Controller.current == null) return
        settings.markers.add(Marker(engine.positionMs, "${S.t("marker")} ${settings.markers.size + 1}"))
        settings.markers.sortBy { it.t }
        waveform.invalidate()
        scheduleSave()
    }

    private fun jumpMarker(dir: Int) {
        val pos = engine.positionMs
        val m = if (dir > 0) settings.markers.filter { it.t > pos + 500 }.minByOrNull { it.t }
        else settings.markers.filter { it.t < pos - 1500 }.maxByOrNull { it.t }
        if (m != null) engine.seekTo(m.t) else toast(S.t("marker.none"))
    }

    private fun ensurePeaks() {
        val t = Controller.current ?: return
        peaksCache[t.id]?.let { waveform.peaks = it; return }
        waveform.peaks = null
        peaksJobFor = t.id
        Thread {
            val p = WaveformExtractor.extract(this, t, 1000) { peaksJobFor != t.id }
            if (p != null) runOnUiThread {
                peaksCache[t.id] = p
                if (Controller.current?.id == t.id) waveform.peaks = p
            }
        }.start()
    }

    // ---------------------------------------------------------------- події контролера, збереження, таймер

    override fun onChanged() {
        onTrackChanged()
        updateTransport()
        adapter.notifyDataSetChanged()
    }

    override fun onMessage(msg: String) {
        toast(msg)
    }

    private fun onTrackChanged() {
        val t = Controller.current
        val id = t?.id ?: -1L
        if (id == shownTrackId) return
        shownTrackId = id
        pendingA = -1L
        nowPlaying.text = if (t != null) "${t.artist} — ${t.title}" else S.t("viz.hint")
        visualizer.cover = null
        visualizer.coverSeed = id
        if (t != null) {
            Thread {
                val bmp = Library.cover(this, t.uri)
                runOnUiThread { if (Controller.current?.id == t.id) visualizer.cover = bmp }
            }.start()
        }
        refreshPanels()
        bindLane()
        updateEditorButtons()
        waveform.selected = -1
        if (currentScreen == "editor") {
            editorTitle.text = t?.displayName ?: S.t("editor.pick")
            ensurePeaks()
        } else waveform.peaks = peaksCache[id]
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("screen", currentScreen)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        show(savedInstanceState.getString("screen") ?: "main")
    }

    private val saveRunnable = Runnable { Controller.saveSettings() }

    private fun scheduleSave() {
        ui.removeCallbacks(saveRunnable)
        ui.postDelayed(saveRunnable, 700)
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            ui.postDelayed(this, 100)
        }
    }

    private fun tick() {
        val pos = engine.positionMs
        val a = act()
        val total = Sel.total(a)
        val el = Sel.elapsed(a, pos)
        if (!seeking && total > 0) seek.progress = ((el * 1000) / total).toInt().coerceIn(0, 1000)
        timeView.text = "${fmtTime(el)} / ${fmtTime(total)}"
        // слайдери «рухаються» за автоматизацією
        val automated = settings.lanes.filter { it.value.isNotEmpty() }.keys
        if (automated.isNotEmpty() && (currentScreen == "effects" || currentScreen == "eq" || currentScreen == "editor")) {
            val get: (String) -> Float = { id -> engine.valueAt(id, pos) }
            for (p in listOf(fxMain, fxEditor, eqMain, eqEditor)) p.setIds(automated, get)
        }
        if (currentScreen == "editor") {
            waveform.invalidate()
            autoView.invalidate()
        }
    }
}
