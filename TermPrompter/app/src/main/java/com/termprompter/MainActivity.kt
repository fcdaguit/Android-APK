package com.termprompter

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var lm: LinearLayoutManager
    private lateinit var adapter: PhraseAdapter
    private lateinit var status: TextView
    private lateinit var panel: View
    private lateinit var btnPlay: Button
    private lateinit var btnMode: Button
    private lateinit var btnLang: Button
    private lateinit var lblSize: TextView
    private lateinit var lblSpeed: TextView
    private lateinit var lblTol: TextView
    private lateinit var prefs: SharedPreferences
    private lateinit var store: ScriptStore
    private lateinit var models: ModelManager

    private val engine = SpeechEngine()
    private val matcher = Matcher()
    private val ui = Handler(Looper.getMainLooper())

    private var lang = Lang.ENGLISH
    private var timerMode = false
    private var playing = false
    private var started = false
    private var busy = false
    private var scriptText = ""
    private var phrases: List<Phrase> = emptyList()
    private var tol = 3
    private var speedDp = 60f
    private var pendingImportLang = Lang.ENGLISH

    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startPlayback() else toast("Microphone permission is needed for voice tracking. Timer mode works without it.")
    }
    private val openText = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val t = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            setScript(t)
        } catch (e: Exception) { toast("Could not read that file") }
    }
    private val openModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri, pendingImportLang)
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        store = ScriptStore(this)
        models = ModelManager(this)

        list = findViewById(R.id.list)
        status = findViewById(R.id.status)
        panel = findViewById(R.id.panel)
        btnPlay = findViewById(R.id.btnPlay)
        btnMode = findViewById(R.id.btnMode)
        btnLang = findViewById(R.id.btnLang)
        lblSize = findViewById(R.id.lblSize)
        lblSpeed = findViewById(R.id.lblSpeed)
        lblTol = findViewById(R.id.lblTol)

        lang = Lang.values().firstOrNull { it.code == prefs.getString("lang", "en") } ?: Lang.ENGLISH
        timerMode = prefs.getBoolean("timer", false)
        tol = prefs.getInt("tol", 3)
        speedDp = prefs.getFloat("speed", 60f)
        val sizeSp = prefs.getFloat("size", 36f)

        adapter = PhraseAdapter().also { it.textSp = sizeSp }
        lm = LinearLayoutManager(this)
        list.layoutManager = lm
        list.adapter = adapter
        list.itemAnimator = null
        list.post {
            // Padding lets the first/last phrase reach the reading line (25% from the top).
            list.setPadding(0, (list.height * 0.25f).toInt(), 0, (list.height * 0.75f).toInt())
        }

        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean { togglePlay(); return false }
            override fun onLongPress(e: MotionEvent) { togglePanel() }
        })
        list.setOnTouchListener { _, ev -> tap.onTouchEvent(ev); false }   // false keeps manual scrolling working

        findViewById<View>(R.id.menuBtn).setOnClickListener { togglePanel() }
        btnPlay.setOnClickListener { togglePlay() }
        findViewById<Button>(R.id.btnReset).setOnClickListener { resetPlayback() }
        findViewById<Button>(R.id.btnScript).setOnClickListener { showScriptMenu() }
        btnLang.setOnClickListener { showLangDialog() }
        btnMode.setOnClickListener {
            pausePlayback(); timerMode = !timerMode
            prefs.edit().putBoolean("timer", timerMode).apply(); refreshButtons(); setStatus()
        }

        bindSeek(R.id.seekSize, ((sizeSp - 16f).toInt()).coerceIn(0, 80)) { p ->
            adapter.textSp = 16f + p; adapter.notifyDataSetChanged()
            prefs.edit().putFloat("size", adapter.textSp).apply(); lblSize.text = "Text size: ${16 + p} sp"
        }
        bindSeek(R.id.seekSpeed, ((speedDp - 10f) / 4f).toInt().coerceIn(0, 100)) { p ->
            speedDp = 10f + p * 4f
            prefs.edit().putFloat("speed", speedDp).apply(); lblSpeed.text = "Timer-mode speed: ${speedDp.toInt()} dp/s"
        }
        bindSeek(R.id.seekTol, (tol - 1).coerceIn(0, 4)) { p ->
            tol = p + 1; matcher.profile = Profile(tol, lang)
            prefs.edit().putInt("tol", tol).apply()
            lblTol.text = "Voice tolerance: $tol/5  (${listOf("strict", "tight", "balanced", "relaxed", "very forgiving")[p]})"
        }

        matcher.profile = Profile(tol, lang)
        for (l in Lang.values()) models.installFromAssetsIfPresent(l)   // bundled model(s), if any

        scriptText = prefs.getString("script", null) ?: SAMPLE
        setScript(scriptText)
        refreshButtons(); setStatus()
    }

    override fun onStop() { super.onStop(); pausePlayback() }          // release mic + save battery
    override fun onDestroy() { super.onDestroy(); engine.release() }

    // ---------------------------------------------------------------- script

    private fun setScript(text: String) {
        pausePlayback()
        scriptText = text
        prefs.edit().putString("script", text).apply()
        phrases = TextUtil.splitPhrases(text, lang)
        adapter.submit(phrases)
        matcher.profile = Profile(tol, lang)
        matcher.load(phrases)
        started = false
        list.scrollToPosition(0)
        refreshButtons(); setStatus()
    }

    private fun showScriptMenu() {
        val items = arrayOf("Paste / edit script", "Open text file…", "Save script…", "Load saved script…", "Delete saved script…")
        dialog().setTitle("Script").setItems(items) { _, i ->
            when (i) {
                0 -> editDialog()
                1 -> openText.launch(arrayOf("text/*"))
                2 -> saveDialog()
                3 -> pickSaved("Load script") { setScript(store.load(it)) }
                4 -> pickSaved("Delete script") { store.delete(it); toast("Deleted") }
            }
        }.show()
    }

    private fun editDialog() {
        val et = styledEdit(scriptText, 12)
        dialog().setTitle("Paste or type your script").setView(et)
            .setPositiveButton("Use") { _, _ -> setScript(et.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun saveDialog() {
        val et = styledEdit("", 1); et.hint = "Script name"; et.setHintTextColor(PhraseAdapter.DIM)
        dialog().setTitle("Save script").setView(et)
            .setPositiveButton("Save") { _, _ ->
                val n = et.text.toString()
                if (n.isBlank()) toast("Enter a name") else { store.save(n, scriptText); toast("Saved") }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun pickSaved(title: String, action: (String) -> Unit) {
        val names = store.names()
        if (names.isEmpty()) { toast("No saved scripts yet"); return }
        dialog().setTitle(title).setItems(names.toTypedArray()) { _, i -> action(names[i]) }.show()
    }

    // ---------------------------------------------------------------- language / models

    private fun showLangDialog() {
        val langs = Lang.values()
        val labels = langs.map { l ->
            val state = when {
                models.installed(l) -> "model installed"
                l == Lang.BISAYA && models.installed(Lang.FILIPINO) -> "using Filipino model"
                else -> "no model — import one"
            }
            (if (l == lang) "● " else "○ ") + l.label + "  [" + state + "]"
        } + "Import voice model (.zip)…"
        dialog().setTitle("Speech language").setItems(labels.toTypedArray()) { _, i ->
            if (i < langs.size) setLang(langs[i]) else chooseImportSlot()
        }.show()
    }

    private fun setLang(l: Lang) {
        pausePlayback()
        lang = l
        prefs.edit().putString("lang", l.code).apply()
        setScript(scriptText)               // re-tokenize with the language's spelling rules
        if (models.resolve(l) == null) toast("No offline model for ${l.label} yet. Use Lang › Import voice model.")
    }

    private fun chooseImportSlot() {
        val langs = Lang.values()
        dialog().setTitle("Import model for…").setItems(langs.map { it.label }.toTypedArray()) { _, i ->
            pendingImportLang = langs[i]
            openModel.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }.show()
    }

    private fun importModel(uri: android.net.Uri, l: Lang) {
        pausePlayback(); busy = true; setStatus("Installing ${l.label} model…")
        Thread {
            val err = try {
                contentResolver.openInputStream(uri)!!.use { models.installFromZip(it, l) }; null
            } catch (t: Throwable) { t.message ?: "Import failed" }
            ui.post {
                busy = false
                toast(err ?: "${l.label} model installed"); setStatus()
            }
        }.start()
    }

    // ---------------------------------------------------------------- playback

    private fun togglePlay() { if (playing) pausePlayback() else startPlayback() }

    private fun startPlayback() {
        if (busy) return
        if (phrases.isEmpty()) { toast("Add a script first"); return }
        if (timerMode) {
            playing = true; started = true
            frame.last = 0L
            Choreographer.getInstance().postFrameCallback(frame)
            panel.visibility = View.GONE
            refreshButtons(); setStatus(); return
        }
        val res = models.resolve(lang)
        if (res == null) { toast("No offline voice model for ${lang.label}. Open Lang › Import voice model."); showLangDialog(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPerm.launch(Manifest.permission.RECORD_AUDIO); return
        }
        busy = true; setStatus("Loading voice model…")
        engine.loadAsync(res.first) { ok, err ->
            busy = false
            if (!ok) { toast("Model error: $err"); setStatus(); return@loadAsync }
            matcher.endUtterance()
            val up = engine.start({ t, f -> ui.post { onSpeech(t, f) } }, { m -> ui.post { toast(m); pausePlayback() } })
            if (!up) { setStatus(); return@loadAsync }
            playing = true; started = true
            panel.visibility = View.GONE
            refreshButtons(); applyMatcherState()
        }
    }

    private fun pausePlayback() {
        if (!playing) return
        playing = false
        engine.stop()
        matcher.endUtterance()
        if (::btnPlay.isInitialized) { refreshButtons(); setStatus() }
    }

    private fun resetPlayback() {
        pausePlayback()
        started = false
        matcher.reset()
        adapter.adlib = false
        adapter.setCurrent(0)
        list.scrollToPosition(0)
        refreshButtons(); setStatus()
    }

    private fun onSpeech(text: String, final: Boolean) {
        if (!playing) return
        val changed = matcher.feed(TextUtil.tokenize(text, lang))
        if (final) matcher.endUtterance()
        if (changed) applyMatcherState()
    }

    private fun applyMatcherState() {
        adapter.adlib = matcher.adlib
        if (matcher.finished) {
            adapter.setCurrent(phrases.size)
            pausePlayback(); started = false
            toast("End of script"); return
        }
        val c = matcher.cur
        if (c != adapter.current) { adapter.setCurrent(c); scrollToPhrase(c) } else adapter.refreshCurrent()
        setStatus()
    }

    /** Smoothly brings phrase [i] to the reading line (25% from the top). */
    private fun scrollToPhrase(i: Int) {
        val s = object : LinearSmoothScroller(this) {
            override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int): Int =
                (boxStart + (boxEnd - boxStart) * 0.25f).toInt() - viewStart
            override fun calculateSpeedPerPixel(dm: DisplayMetrics): Float = 40f / dm.densityDpi
        }
        s.targetPosition = i
        lm.startSmoothScroll(s)
    }

    // Timer mode: constant-speed scroll; the highlight follows the reading line.
    private val frame = object : Choreographer.FrameCallback {
        var last = 0L
        var rem = 0f
        var acc = 0L
        override fun doFrame(t: Long) {
            if (!playing || !timerMode) { last = 0L; return }
            if (last != 0L) {
                val dt = (t - last) / 1e9f
                rem += speedDp * resources.displayMetrics.density * dt
                val dy = rem.toInt()
                if (dy != 0) { list.scrollBy(0, dy); rem -= dy }
                acc += t - last
                if (acc > 250_000_000L) { acc = 0; followReadingLine() }
                if (!list.canScrollVertically(1)) { pausePlayback(); toast("End of script"); return }
            }
            last = t
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun followReadingLine() {
        val v = list.findChildViewUnder(list.width / 2f, list.height * 0.25f + 2f) ?: return
        val p = list.getChildAdapterPosition(v)
        if (p >= 0 && p != adapter.current) adapter.setCurrent(p)
    }

    // ---------------------------------------------------------------- UI helpers

    private fun togglePanel() { panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }

    private fun refreshButtons() {
        btnPlay.text = when { playing -> "Pause"; started -> "Resume"; else -> "Play" }
        btnMode.text = if (timerMode) "Mode: Timer" else "Mode: Voice"
        btnLang.text = "Lang: ${lang.code.uppercase()}"
    }

    private fun setStatus(msg: String? = null) {
        val m = msg ?: when {
            busy -> "Working…"
            !started -> "READY"
            !playing -> "PAUSED"
            timerMode -> "TIMER"
            matcher.adlib -> "AD-LIB — waiting for script"
            else -> "LISTENING"
        }
        val fallback = !timerMode && models.resolve(lang)?.second == true
        status.text = "$m · ${lang.code.uppercase()}" + if (fallback) " (Filipino model)" else ""
    }

    private fun bindSeek(id: Int, initial: Int, onChange: (Int) -> Unit) {
        val sb = findViewById<SeekBar>(id)
        sb.progress = initial
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) = onChange(p)
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        onChange(initial)
    }

    private fun dialog() = AlertDialog.Builder(this, R.style.DarkDialog)

    private fun styledEdit(text: String, lines: Int) = EditText(this).apply {
        setText(text)
        setTextColor(PhraseAdapter.NEON)
        setBackgroundColor(Color.BLACK)
        typeface = android.graphics.Typeface.MONOSPACE
        minLines = lines
        maxLines = 14
        gravity = android.view.Gravity.TOP
        setPadding(24, 24, 24, 24)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    companion object {
        private const val SAMPLE = "Welcome to TermPrompter.\n" +
            "Read this text out loud, and the screen will follow your voice.\n" +
            "If you stop to add your own comments, the text will wait for you.\n" +
            "When you return to the script and finish the line, it moves on again.\n" +
            "Tap anywhere to pause or resume. Long-press or tap the dots for settings."
    }
}
