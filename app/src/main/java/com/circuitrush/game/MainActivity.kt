package com.circuitrush.game

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.sign

class MainActivity : Activity(), Host, SensorEventListener {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: GameRenderer
    private lateinit var root: FrameLayout
    private lateinit var hud: HudView
    private lateinit var menuLayer: FrameLayout
    private lateinit var pauseLayer: FrameLayout
    private lateinit var resultLayer: FrameLayout
    private lateinit var resultBody: LinearLayout
    private lateinit var loading: TextView
    private lateinit var bestText: TextView
    private lateinit var prefs: SharedPreferences
    private val sound = EngineSound()
    private var sm: SensorManager? = null
    private var tone: ToneGenerator? = null

    private val LAPS = intArrayOf(2, 3, 5, 8, 10)
    private val OPPS = intArrayOf(1, 3, 5, 7)
    private val CONTROLS = arrayOf("Touch buttons", "Tilt")

    private var carIdx = 6
    private var colorIdx = 0
    private var lapIdx = 1
    private var oppIdx = 3
    private var diff = 1
    private var ctrl = 0
    private var soundOn = true
    private var inRace = false

    // ------------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        prefs = getSharedPreferences("cr", Context.MODE_PRIVATE)
        loadSettings()
        sound.enabled = soundOn

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        glView = GLSurfaceView(this)
        glView.setEGLContextClientVersion(3)
        glView.setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        renderer = GameRenderer(applicationContext, this, sound)
        glView.setRenderer(renderer)
        glView.preserveEGLContextOnPause = true
        root.addView(glView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        hud = HudView(this)
        hud.visibility = View.GONE
        hud.onPause = { runOnUiThread { showPause() } }
        root.addView(hud, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        loading = TextView(this)
        loading.text = "Loading 3D assets..."
        loading.setTextColor(Color.WHITE)
        loading.textSize = 18f
        loading.gravity = Gravity.CENTER
        loading.setBackgroundColor(0xFF0B0B12.toInt())
        root.addView(loading, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        setContentView(root)

        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        try {
            tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        } catch (t: Throwable) {
            tone = null
        }
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        sound.start()
        val s = sm
        if (s != null) {
            val a = s.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (a != null) s.registerListener(this, a, SensorManager.SENSOR_DELAY_GAME)
        }
        if (inRace) showPause()
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        sound.stop()
        sm?.unregisterListener(this)
        Game.gas = false
        Game.brake = false
        Game.left = false
        Game.right = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (!::menuLayer.isInitialized || !::resultLayer.isInitialized) {
            super.onBackPressed()
            return
        }
        if (inRace) {
            if (Game.paused) resumeRace() else showPause()
        } else if (resultLayer.visibility == View.VISIBLE) {
            showMenu()
        } else {
            super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ sensors
    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        @Suppress("DEPRECATION")
        val rot = windowManager.defaultDisplay.rotation
        val ay = e.values[1]
        val s = if (rot == Surface.ROTATION_270) -ay else ay
        var v = (s / 9.81f).coerceIn(-1f, 1f)
        val dead = 0.05f
        v = if (abs(v) < dead) 0f else (v - dead * sign(v)) / (0.55f - dead)
        Game.tilt = v.coerceIn(-1f, 1f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ------------------------------------------------------------------ Host (GL thread callbacks)
    override fun onLoaded() {
        runOnUiThread {
            loading.visibility = View.GONE
            buildMenu()
            buildPause()
            buildResults()
            showMenu()
        }
    }

    override fun onError(msg: String) {
        runOnUiThread {
            loading.visibility = View.VISIBLE
            loading.setTextColor(Color.rgb(255, 120, 100))
            loading.textSize = 13f
            loading.text = "ERROR:\n$msg"
        }
    }

    override fun onResults(rows: List<ResultRow>, playerPos: Int, playerBest: Float) {
        runOnUiThread { showResults(rows, playerPos, playerBest) }
    }

    override fun onBeep(kind: Int) {
        if (!soundOn) return
        try {
            tone?.startTone(if (kind == 0) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_ACK, if (kind == 0) 150 else 400)
        } catch (t: Throwable) {
        }
    }

    // ------------------------------------------------------------------ settings
    private fun loadSettings() {
        carIdx = prefs.getInt("car", 6)
        colorIdx = prefs.getInt("color", 0)
        lapIdx = prefs.getInt("lap", 1)
        oppIdx = prefs.getInt("opp", 3)
        diff = prefs.getInt("diff", 1)
        ctrl = prefs.getInt("ctrl", 0)
        soundOn = prefs.getBoolean("sound", true)
    }

    private fun saveSettings() {
        prefs.edit().putInt("car", carIdx).putInt("color", colorIdx).putInt("lap", lapIdx)
            .putInt("opp", oppIdx).putInt("diff", diff).putInt("ctrl", ctrl).putBoolean("sound", soundOn).apply()
    }

    // ------------------------------------------------------------------ UI helpers
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun roundBg(color: Int, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        return g
    }

    private fun btn(text: String, color: Int, size: Float, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(Color.WHITE)
        t.textSize = size
        t.gravity = Gravity.CENTER
        t.setPadding(dp(14), dp(10), dp(14), dp(10))
        t.background = roundBg(color, 10)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams {
        val l = LinearLayout.LayoutParams(w, h, weight)
        return l
    }

    private fun optionRow(label: String, valueOf: () -> String, prev: () -> Unit, next: () -> Unit): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(3), 0, dp(3))
        val name = TextView(this)
        name.text = label
        name.setTextColor(0xFFAAB0C0.toInt())
        name.textSize = 13f
        val value = TextView(this)
        value.setTextColor(Color.WHITE)
        value.textSize = 15f
        value.gravity = Gravity.CENTER
        value.text = valueOf()
        val b1 = btn("<", 0xFF2A2C3A.toInt(), 16f) { prev(); value.text = valueOf() }
        val b2 = btn(">", 0xFF2A2C3A.toInt(), 16f) { next(); value.text = valueOf() }
        row.addView(name, lp(dp(92), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(b1, lp(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(value, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(b2, lp(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun cyc(v: Int, d: Int, n: Int): Int = ((v + d) % n + n) % n

    private fun previewChanged() {
        saveSettings()
        val c = carIdx
        val col = colorIdx
        glView.queueEvent { renderer.setPreview(c, col) }
    }

    // ------------------------------------------------------------------ menu
    private fun buildMenu() {
        menuLayer = FrameLayout(this)
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(20), dp(14), dp(20), dp(14))
        panel.background = roundBg(0xD90B0B12.toInt(), 16)

        val title = TextView(this)
        title.text = "CIRCUIT RUSH 3D"
        title.setTextColor(Color.WHITE)
        title.textSize = 22f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD_ITALIC)
        panel.addView(title)
        val sub = TextView(this)
        sub.text = "Single player  |  vs AI"
        sub.setTextColor(0xFFE10600.toInt())
        sub.textSize = 12f
        sub.setPadding(0, 0, 0, dp(8))
        panel.addView(sub)

        panel.addView(btn("START RACE", 0xFFE10600.toInt(), 20f) { startRace() }, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val names = Game.carNames
        panel.addView(optionRow("Car", { if (names.isEmpty()) "-" else names[carIdx.coerceIn(0, names.size - 1)] },
            { carIdx = cyc(carIdx, -1, maxOf(1, names.size)); previewChanged() },
            { carIdx = cyc(carIdx, 1, maxOf(1, names.size)); previewChanged() }))
        panel.addView(optionRow("Paint", { Tune.PALETTE_NAMES[colorIdx % Tune.PALETTE_NAMES.size] },
            { colorIdx = cyc(colorIdx, -1, Tune.PALETTE_NAMES.size); previewChanged() },
            { colorIdx = cyc(colorIdx, 1, Tune.PALETTE_NAMES.size); previewChanged() }))
        panel.addView(optionRow("Laps", { "" + LAPS[lapIdx] },
            { lapIdx = cyc(lapIdx, -1, LAPS.size); saveSettings() },
            { lapIdx = cyc(lapIdx, 1, LAPS.size); saveSettings() }))
        panel.addView(optionRow("Opponents", { "" + OPPS[oppIdx] },
            { oppIdx = cyc(oppIdx, -1, OPPS.size); saveSettings() },
            { oppIdx = cyc(oppIdx, 1, OPPS.size); saveSettings() }))
        panel.addView(optionRow("Difficulty", { Tune.DIFF_NAMES[diff] },
            { diff = cyc(diff, -1, 3); saveSettings(); refreshBest() },
            { diff = cyc(diff, 1, 3); saveSettings(); refreshBest() }))
        panel.addView(optionRow("Steering", { CONTROLS[ctrl] },
            { ctrl = cyc(ctrl, -1, 2); saveSettings() },
            { ctrl = cyc(ctrl, 1, 2); saveSettings() }))
        panel.addView(optionRow("Sound", { if (soundOn) "On" else "Off" },
            { soundOn = !soundOn; sound.enabled = soundOn; saveSettings() },
            { soundOn = !soundOn; sound.enabled = soundOn; saveSettings() }))

        bestText = TextView(this)
        bestText.setTextColor(0xFFFFDC5A.toInt())
        bestText.textSize = 13f
        bestText.setPadding(0, dp(8), 0, 0)
        panel.addView(bestText)
        refreshBest()

        val scroll = ScrollView(this)
        scroll.addView(panel)
        val flp = FrameLayout.LayoutParams(dp(360), FrameLayout.LayoutParams.MATCH_PARENT)
        flp.leftMargin = dp(16)
        flp.topMargin = dp(8)
        flp.bottomMargin = dp(8)
        menuLayer.addView(scroll, flp)
        root.addView(menuLayer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun refreshBest() {
        val b = prefs.getFloat("best_$diff", 0f)
        bestText.text = "Best lap (" + Tune.DIFF_NAMES[diff] + "): " + Game.fmt(b)
    }

    private fun centerPanel(): LinearLayout {
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(24), dp(18), dp(24), dp(18))
        panel.background = roundBg(0xEE0B0B12.toInt(), 16)
        return panel
    }

    private fun buildPause() {
        pauseLayer = FrameLayout(this)
        pauseLayer.setBackgroundColor(0x88000000.toInt())
        val panel = centerPanel()
        val t = TextView(this)
        t.text = "PAUSED"
        t.setTextColor(Color.WHITE)
        t.textSize = 22f
        t.gravity = Gravity.CENTER
        panel.addView(t)
        panel.addView(btn("RESUME", 0xFF1E9E4A.toInt(), 18f) { resumeRace() }, lp(dp(240), LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(btn("RESTART", 0xFF2A2C3A.toInt(), 16f) { startRace() }, lp(dp(240), LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(btn("QUIT TO MENU", 0xFFB02A20.toInt(), 16f) { showMenu() }, lp(dp(240), LinearLayout.LayoutParams.WRAP_CONTENT))
        val flp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        flp.gravity = Gravity.CENTER
        pauseLayer.addView(panel, flp)
        pauseLayer.visibility = View.GONE
        pauseLayer.setOnClickListener { }
        root.addView(pauseLayer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun buildResults() {
        resultLayer = FrameLayout(this)
        resultLayer.setBackgroundColor(0x99000000.toInt())
        val panel = centerPanel()
        resultBody = LinearLayout(this)
        resultBody.orientation = LinearLayout.VERTICAL
        val sc = ScrollView(this)
        sc.addView(resultBody)
        panel.addView(sc, lp(dp(340), dp(230)))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(btn("RACE AGAIN", 0xFFE10600.toInt(), 16f) { startRace() }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(btn("MENU", 0xFF2A2C3A.toInt(), 16f) { showMenu() }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(row, lp(dp(340), LinearLayout.LayoutParams.WRAP_CONTENT))
        val flp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        flp.gravity = Gravity.CENTER
        resultLayer.addView(panel, flp)
        resultLayer.visibility = View.GONE
        resultLayer.setOnClickListener { }
        root.addView(resultLayer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    // ------------------------------------------------------------------ flow
    private fun showMenu() {
        inRace = false
        Game.paused = false
        hud.visibility = View.GONE
        pauseLayer.visibility = View.GONE
        resultLayer.visibility = View.GONE
        menuLayer.visibility = View.VISIBLE
        refreshBest()
        val c = carIdx
        val col = colorIdx
        glView.queueEvent {
            renderer.exitRace()
            renderer.setPreview(c, col)
        }
    }

    private fun startRace() {
        saveSettings()
        val cfg = RaceConfig(carIdx, colorIdx, LAPS[lapIdx], OPPS[oppIdx], diff)
        Game.useTilt = ctrl == 1
        Game.paused = false
        Game.gas = false
        Game.brake = false
        menuLayer.visibility = View.GONE
        resultLayer.visibility = View.GONE
        pauseLayer.visibility = View.GONE
        hud.visibility = View.VISIBLE
        inRace = true
        glView.queueEvent { renderer.startRace(cfg) }
    }

    private fun showPause() {
        if (!inRace || resultLayer.visibility == View.VISIBLE) return
        Game.paused = true
        Game.gas = false
        Game.brake = false
        Game.left = false
        Game.right = false
        pauseLayer.visibility = View.VISIBLE
    }

    private fun resumeRace() {
        Game.paused = false
        pauseLayer.visibility = View.GONE
    }

    private fun showResults(rows: List<ResultRow>, playerPos: Int, playerBest: Float) {
        var newBest = false
        val key = "best_$diff"
        val old = prefs.getFloat(key, 0f)
        if (playerBest > 0f && (old == 0f || playerBest < old)) {
            prefs.edit().putFloat(key, playerBest).apply()
            newBest = true
        }
        resultBody.removeAllViews()
        val head = TextView(this)
        head.text = "RESULT   P$playerPos"
        head.setTextColor(Color.WHITE)
        head.textSize = 20f
        resultBody.addView(head)
        for (i in rows.indices) {
            val r = rows[i]
            val t = TextView(this)
            val tm = if (r.finished) Game.fmt(r.time) else "--"
            t.text = "" + (i + 1) + ".  " + r.name + "     " + tm
            t.setTextColor(if (r.isPlayer) 0xFFFFDC5A.toInt() else 0xFFDDDDEE.toInt())
            t.textSize = 15f
            t.setPadding(0, dp(2), 0, dp(2))
            resultBody.addView(t)
        }
        val bl = TextView(this)
        bl.text = "Your best lap: " + Game.fmt(playerBest) + (if (newBest) "   NEW RECORD!" else "")
        bl.setTextColor(0xFF8FE3A0.toInt())
        bl.textSize = 14f
        bl.setPadding(0, dp(8), 0, 0)
        resultBody.addView(bl)
        Game.paused = true
        pauseLayer.visibility = View.GONE
        resultLayer.visibility = View.VISIBLE
        refreshBest()
    }
}
