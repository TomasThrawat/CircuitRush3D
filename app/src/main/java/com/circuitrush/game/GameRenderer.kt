package com.circuitrush.game

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

fun makeVbo(data: FloatArray): Int {
    val ids = IntArray(1)
    GLES20.glGenBuffers(1, ids, 0)
    val bb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
    bb.asFloatBuffer().put(data)
    bb.position(0)
    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
    GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, bb, GLES20.GL_STATIC_DRAW)
    return ids[0]
}

class CarGpu(val model: GlbModel, val name: String) {
    var vbos: Array<IntArray> = emptyArray()
    val wheelOf = IntArray(model.nodes.size) { -1 }

    init {
        for (i in model.nodes.indices) {
            val nm = model.nodes[i].name
            if (nm == "wheel-front-left") wheelOf[i] = 0
            else if (nm == "wheel-front-right") wheelOf[i] = 1
            else if (nm == "wheel-rear-left") wheelOf[i] = 2
            else if (nm == "wheel-rear-right") wheelOf[i] = 3
        }
    }

    fun upload() {
        vbos = Array(model.meshes.size) { mi ->
            IntArray(model.meshes[mi].size) { pi -> makeVbo(model.meshes[mi][pi].data) }
        }
    }
}

class GameRenderer(
    private val ctx: Context,
    private val host: Host,
    private val sound: EngineSound
) : GLSurfaceView.Renderer {

    private val STEP = 1f / 60f
    private val DRAW_DIST = 620f

    private var loaded = false
    private var track: Track? = null
    private var chunks: List<ChunkData> = emptyList()
    private var cars: Array<CarGpu> = emptyArray()

    private var progMain = 0
    private var progSky = 0
    private var uVP = 0
    private var uModel = 0
    private var uNorm = 0
    private var uCam = 0
    private var uSun = 0
    private var uFog = 0
    private var uFogD = 0
    private var uAlpha = 0
    private var uInvVP = 0
    private var uCamSky = 0
    private var uSunSky = 0
    private var skyVbo = 0
    private var shadowVbo = 0
    private val shadowCount = 20 * 3

    private var vw = 1
    private var vh = 1
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val invVp = FloatArray(16)
    private val ident4 = Mat.identity()
    private val ident3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val nrmTmp = FloatArray(9)
    private val tmp3 = FloatArray(3)

    private var race: Race? = null
    private var previewCar: Car? = null
    private var acc = 0f
    private var lastNs = 0L
    private var lastCount = 4
    private var menuAngle = 0.6f

    private var ex = 0f
    private var ey = 3f
    private var ez = 0f
    private var lx = 0f
    private var ly = 0f
    private var lz = 1f
    private var fov = 60f
    private var camYaw = 0f
    private var snapCam = true

    // ---------------------------------------------------------------- lifecycle
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            if (!loaded) loadAll()
            uploadAll()
            setupPrograms()
            setupSkyAndShadow()
            if (!loaded) {
                loaded = true
                host.onLoaded()
            }
            lastNs = System.nanoTime()
        } catch (t: Throwable) {
            host.onError(t.toString() + "\n" + t.stackTrace.take(5).joinToString("\n"))
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        vw = max(1, width)
        vh = max(1, height)
        GLES20.glViewport(0, 0, vw, vh)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!loaded) {
            GLES20.glClearColor(0.04f, 0.04f, 0.07f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            return
        }
        try {
            val now = System.nanoTime()
            var dt = (now - lastNs) / 1.0e9f
            lastNs = now
            if (dt > 0.05f) dt = 0.05f
            if (dt < 0f) dt = 0f
            update(dt)
            render()
        } catch (t: Throwable) {
            host.onError(t.toString() + "\n" + t.stackTrace.take(5).joinToString("\n"))
            loaded = false
        }
    }

    // ---------------------------------------------------------------- loading
    private fun loadAll() {
        val tj = JSONObject(String(ctx.assets.open("track.json").use { it.readBytes() }, Charsets.UTF_8))
        val tr = Track(tj)
        track = tr
        Game.track = tr
        val cj = JSONArray(String(ctx.assets.open("cars.json").use { it.readBytes() }, Charsets.UTF_8))
        val list = ArrayList<CarGpu>()
        val names = ArrayList<String>()
        for (i in 0 until cj.length()) {
            val o = cj.getJSONObject(i)
            val f = o.getString("file")
            val m = GlbLoader.parse(ctx.assets.open("models/$f.glb").use { it.readBytes() })
            list.add(CarGpu(m, o.getString("name")))
            names.add(o.getString("name"))
        }
        cars = list.toTypedArray()
        Game.carNames = names.toTypedArray()
        chunks = SceneBuilder.build(ctx, tj)
    }

    private fun uploadAll() {
        for (c in chunks) c.vbo = makeVbo(c.data)
        for (g in cars) g.upload()
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val st = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, st, 0)
        if (st[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("Shader compile failed: $log")
        }
        return s
    }

    private fun link(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val st = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, st, 0)
        if (st[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            throw RuntimeException("Program link failed: $log")
        }
        return p
    }

    private fun setupPrograms() {
        val vsMain = """
            #version 300 es
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec3 aColor;
            layout(location = 3) in float aShine;
            uniform mat4 uVP;
            uniform mat4 uModel;
            uniform mat3 uNorm;
            out vec3 vN;
            out vec3 vColor;
            out float vShine;
            out vec3 vWorld;
            void main() {
                vec4 w = uModel * vec4(aPos, 1.0);
                vWorld = w.xyz;
                vN = uNorm * aNormal;
                vColor = aColor;
                vShine = aShine;
                gl_Position = uVP * w;
            }
        """.trimIndent()
        val fsMain = """
            #version 300 es
            precision highp float;
            in vec3 vN;
            in vec3 vColor;
            in float vShine;
            in vec3 vWorld;
            uniform vec3 uCam;
            uniform vec3 uSun;
            uniform vec3 uFog;
            uniform float uFogD;
            uniform float uAlpha;
            out vec4 outColor;
            void main() {
                vec3 n = normalize(vN);
                vec3 L = normalize(uSun);
                float ndl = max(dot(n, L), 0.0);
                float hemi = 0.5 + 0.5 * n.y;
                vec3 amb = mix(vec3(0.10, 0.09, 0.08), vec3(0.30, 0.36, 0.46), hemi);
                vec3 col = vColor * (amb + vec3(1.05, 0.98, 0.88) * ndl * 0.95);
                vec3 V = normalize(uCam - vWorld);
                vec3 Hh = normalize(L + V);
                float spec = pow(max(dot(n, Hh), 0.0), 40.0) * vShine;
                col += vec3(1.0, 0.95, 0.85) * spec * 0.6;
                float dist = length(uCam - vWorld);
                float f = 1.0 - exp(-dist * uFogD);
                col = mix(col, uFog, clamp(f, 0.0, 1.0));
                col = pow(max(col, vec3(0.0)), vec3(0.4545));
                outColor = vec4(col, uAlpha);
            }
        """.trimIndent()
        progMain = link(vsMain, fsMain)
        uVP = GLES20.glGetUniformLocation(progMain, "uVP")
        uModel = GLES20.glGetUniformLocation(progMain, "uModel")
        uNorm = GLES20.glGetUniformLocation(progMain, "uNorm")
        uCam = GLES20.glGetUniformLocation(progMain, "uCam")
        uSun = GLES20.glGetUniformLocation(progMain, "uSun")
        uFog = GLES20.glGetUniformLocation(progMain, "uFog")
        uFogD = GLES20.glGetUniformLocation(progMain, "uFogD")
        uAlpha = GLES20.glGetUniformLocation(progMain, "uAlpha")

        val vsSky = """
            #version 300 es
            layout(location = 0) in vec2 aPos;
            out vec2 vNdc;
            void main() {
                vNdc = aPos;
                gl_Position = vec4(aPos, 0.999, 1.0);
            }
        """.trimIndent()
        val fsSky = """
            #version 300 es
            precision highp float;
            in vec2 vNdc;
            uniform mat4 uInvVP;
            uniform vec3 uCam;
            uniform vec3 uSun;
            out vec4 outColor;
            void main() {
                vec4 wp = uInvVP * vec4(vNdc, 1.0, 1.0);
                vec3 dir = normalize(wp.xyz / wp.w - uCam);
                float h = dir.y;
                vec3 horizon = vec3(0.70, 0.78, 0.86);
                vec3 zenith = vec3(0.16, 0.36, 0.72);
                vec3 col = mix(horizon, zenith, smoothstep(0.0, 0.55, h));
                col = mix(col, vec3(0.50, 0.60, 0.52), smoothstep(0.02, -0.15, h));
                float sd = max(dot(dir, normalize(uSun)), 0.0);
                col += vec3(1.0, 0.85, 0.6) * (pow(sd, 400.0) * 1.5 + pow(sd, 12.0) * 0.15);
                outColor = vec4(col, 1.0);
            }
        """.trimIndent()
        progSky = link(vsSky, fsSky)
        uInvVP = GLES20.glGetUniformLocation(progSky, "uInvVP")
        uCamSky = GLES20.glGetUniformLocation(progSky, "uCam")
        uSunSky = GLES20.glGetUniformLocation(progSky, "uSun")
    }

    private fun setupSkyAndShadow() {
        skyVbo = makeVbo(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        val seg = 20
        val d = FloatArray(seg * 3 * 6)
        var o = 0
        for (k in 0 until seg) {
            val a0 = 2f * PIF * k / seg
            val a1 = 2f * PIF * (k + 1) / seg
            val pts = floatArrayOf(0f, 0f, cos(a0), sin(a0), cos(a1), sin(a1))
            for (v in 0 until 3) {
                d[o++] = pts[v * 2]
                d[o++] = 0f
                d[o++] = pts[v * 2 + 1]
                d[o++] = 0f
                d[o++] = 1f
                d[o++] = 0f
            }
        }
        shadowVbo = makeVbo(d)
    }

    // ---------------------------------------------------------------- game control (GL thread)
    fun setPreview(carIdx: Int, colorIdx: Int) {
        val tr = track ?: return
        if (cars.isEmpty()) return
        val c = Car(0, false)
        c.modelIndex = carIdx.coerceIn(0, cars.size - 1)
        val p = Tune.PALETTE[colorIdx % Tune.PALETTE.size]
        c.paint[0] = p[0]; c.paint[1] = p[1]; c.paint[2] = p[2]
        tr.place(tr.s0 - 14f, 0f, tmp3)
        c.x = tmp3[0]
        c.z = tmp3[1]
        c.yaw = tmp3[2]
        previewCar = c
    }

    fun startRace(cfg: RaceConfig) {
        val tr = track ?: return
        race = Race(tr, cfg, cars.size)
        previewCar = null
        acc = 0f
        lastCount = 4
        snapCam = true
    }

    fun exitRace() {
        race = null
    }

    // ---------------------------------------------------------------- update
    private fun update(dt: Float) {
        val r = race
        if (r != null) {
            if (!Game.paused) {
                acc += dt
                var n = 0
                while (acc >= STEP && n < 5) {
                    r.update(STEP)
                    acc -= STEP
                    n++
                }
                if (n == 5) acc = 0f
                if (r.state == 3 && !r.resultsSent) {
                    r.resultsSent = true
                    host.onResults(r.results(), r.player.position, r.player.bestLap)
                }
                handleBeeps(r)
                sound.rpm = r.player.rpm
                sound.load = if (Game.gas && r.state > 0 && r.player.speed < 84f) 1f else 0.15f
            } else {
                sound.load = 0f
            }
            updateCamera(dt, r.player)
            publishHud(r)
        } else {
            val pc = previewCar
            if (pc != null) {
                menuAngle += dt * 0.35f
                ex = pc.x + sin(menuAngle) * 8.5f
                ey = 2.7f
                ez = pc.z + cos(menuAngle) * 8.5f
                lx = pc.x
                ly = 0.7f
                lz = pc.z
                fov = 42f
            }
            sound.load = 0f
            sound.rpm = 0.12f
            Game.hud = HudState.EMPTY
        }
    }

    private fun handleBeeps(r: Race) {
        if (r.state == 0) {
            val cnt = 3 - floor(r.countT).toInt()
            if (cnt != lastCount) {
                lastCount = cnt
                if (cnt in 1..3) host.onBeep(0)
            }
        } else if (lastCount != 0) {
            lastCount = 0
            host.onBeep(1)
        }
    }

    private fun updateCamera(dt: Float, c: Car) {
        val sp = abs(c.speed)
        val mode = Game.camMode
        if (snapCam) {
            camYaw = c.yaw
            snapCam = false
        } else {
            camYaw += wrapAngle(c.yaw - camYaw) * (1f - exp(-6f * dt))
        }
        if (mode == 2) {
            val fx = sin(c.yaw)
            val fz = cos(c.yaw)
            ex = c.x + fx * 0.5f
            ey = 1.0f
            ez = c.z + fz * 0.5f
            lx = c.x + fx * 30f
            ly = 0.8f
            lz = c.z + fz * 30f
            fov = 74f + sp * 0.1f
        } else {
            val fx = sin(camYaw)
            val fz = cos(camYaw)
            val dist = (if (mode == 0) 8.2f else 13.5f) + sp * 0.035f
            val hgt = (if (mode == 0) 2.9f else 5.0f) + sp * 0.008f
            ex = c.x - fx * dist
            ey = hgt
            ez = c.z - fz * dist
            lx = c.x + fx * 6f
            ly = 0.9f
            lz = c.z + fz * 6f
            fov = 60f + min(sp, 85f) * 0.12f
        }
    }

    private fun publishHud(r: Race) {
        val p = r.player
        val n = r.cars.size
        val xs = FloatArray(n)
        val zs = FloatArray(n)
        val cols = IntArray(n)
        var pi = 0
        for (i in 0 until n) {
            val c = r.cars[i]
            xs[i] = c.x
            zs[i] = c.z
            cols[i] = c.paintArgb
            if (c === p) pi = i
        }
        val tr = r.track
        val lapNow = max(1, min(p.lap, r.laps))
        val lapTime = if (p.lap >= 1 && r.state != 0) r.time - p.lapStart else 0f
        var count = -1
        var msg = ""
        if (r.state == 0) {
            count = 3 - floor(r.countT).toInt().coerceIn(0, 2)
        } else if (r.state == 1 && r.time < 1.0f) {
            msg = "GO!"
        }
        if (r.state >= 2 && r.time - r.finishedAt < 6f) msg = "FINISHED!"
        val wrong = p.idx >= 0 && p.speed > 6f && abs(wrapAngle(p.yaw - tr.ch[p.idx])) > 2.0f
        if (msg.isEmpty() && wrong) msg = "WRONG WAY"
        Game.hud = HudState(
            1, (abs(p.speed) * 3.6f).toInt(), p.gear, p.rpm, lapNow, r.laps, p.position, n,
            lapTime, p.bestLap, p.lastLap, count, msg, wrong, p.offRoad, xs, zs, cols, pi, r.state
        )
    }

    // ---------------------------------------------------------------- render
    private fun render() {
        val tr = track ?: return
        GLES20.glViewport(0, 0, vw, vh)
        GLES20.glClearColor(0.6f, 0.72f, 0.85f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val aspect = vw.toFloat() / vh.toFloat()
        Matrix.perspectiveM(proj, 0, fov, aspect, 0.6f, 1500f)
        Matrix.setLookAtM(view, 0, ex, ey, ez, lx, ly, lz, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        Matrix.invertM(invVp, 0, vp, 0)

        // sky
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glUseProgram(progSky)
        GLES20.glUniformMatrix4fv(uInvVP, 1, false, invVp, 0)
        GLES20.glUniform3f(uCamSky, ex, ey, ez)
        GLES20.glUniform3f(uSunSky, 0.45f, 0.75f, 0.40f)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, skyVbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 8, 0)
        GLES20.glDisableVertexAttribArray(1)
        GLES20.glDisableVertexAttribArray(2)
        GLES20.glDisableVertexAttribArray(3)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        // world
        GLES20.glUseProgram(progMain)
        GLES20.glUniformMatrix4fv(uVP, 1, false, vp, 0)
        GLES20.glUniform3f(uCam, ex, ey, ez)
        GLES20.glUniform3f(uSun, 0.45f, 0.75f, 0.40f)
        GLES20.glUniform3f(uFog, 0.456f, 0.579f, 0.717f)
        GLES20.glUniform1f(uFogD, 0.0028f)
        GLES20.glUniform1f(uAlpha, 1f)
        GLES20.glUniformMatrix4fv(uModel, 1, false, ident4, 0)
        GLES20.glUniformMatrix3fv(uNorm, 1, false, ident3, 0)

        var fdx = lx - ex
        var fdz = lz - ez
        val fl = sqrt(fdx * fdx + fdz * fdz)
        if (fl > 1e-4f) { fdx /= fl; fdz /= fl }
        for (ch in chunks) {
            val dx = ch.cx - ex
            val dz = ch.cz - ez
            val d2 = dx * dx + dz * dz
            val maxd = DRAW_DIST + ch.radius
            if (d2 > maxd * maxd) continue
            if (dx * fdx + dz * fdz < -ch.radius) continue
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ch.vbo)
            GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 40, 0)
            GLES20.glEnableVertexAttribArray(0)
            GLES20.glVertexAttribPointer(1, 3, GLES20.GL_FLOAT, false, 40, 12)
            GLES20.glEnableVertexAttribArray(1)
            GLES20.glVertexAttribPointer(2, 3, GLES20.GL_FLOAT, false, 40, 24)
            GLES20.glEnableVertexAttribArray(2)
            GLES20.glVertexAttribPointer(3, 1, GLES20.GL_FLOAT, false, 40, 36)
            GLES20.glEnableVertexAttribArray(3)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, ch.count)
        }

        // cars
        val r = race
        val list: List<Car> = if (r != null) r.cars else {
            val pc = previewCar
            if (pc != null) listOf(pc) else emptyList()
        }
        for (c in list) {
            val dx = c.x - ex
            val dz = c.z - ez
            if (dx * dx + dz * dz > 420f * 420f) continue
            if (c.modelIndex < 0 || c.modelIndex >= cars.size) continue
            drawCar(cars[c.modelIndex], c)
        }

        // shadows
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        GLES20.glUniform1f(uAlpha, 0.35f)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, shadowVbo)
        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 24, 0)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(1, 3, GLES20.GL_FLOAT, false, 24, 12)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glDisableVertexAttribArray(2)
        GLES20.glDisableVertexAttribArray(3)
        GLES20.glVertexAttrib3f(2, 0f, 0f, 0f)
        GLES20.glVertexAttrib1f(3, 0f)
        for (c in list) {
            val dx = c.x - ex
            val dz = c.z - ez
            if (dx * dx + dz * dz > 300f * 300f) continue
            val m = Mat.placement(c.x, 0.16f, c.z, c.yaw, 1.3f, 1f, 3.0f)
            Mat.upper3(m, nrmTmp)
            GLES20.glUniformMatrix4fv(uModel, 1, false, m, 0)
            GLES20.glUniformMatrix3fv(uNorm, 1, false, nrmTmp, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, shadowCount)
        }
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUniform1f(uAlpha, 1f)
    }

    private fun drawCar(g: CarGpu, c: Car) {
        val base = Mat.placement(c.x, 0f, c.z, c.yaw, 1f, 1f, 1f)
        for (r in g.model.roots) drawNode(g, r, base, c)
    }

    private fun drawNode(g: CarGpu, ni: Int, parent: FloatArray, c: Car) {
        val node = g.model.nodes[ni]
        var local = node.local
        val wi = g.wheelOf[ni]
        if (wi >= 0) {
            val m = Mat.identity()
            Matrix.translateM(m, 0, node.tr[0], node.tr[1], node.tr[2])
            if (wi < 2) Matrix.rotateM(m, 0, c.steerVis * RAD2DEG, 0f, 1f, 0f)
            Matrix.rotateM(m, 0, (c.roll % 6.2831855f) * RAD2DEG, 1f, 0f, 0f)
            local = m
        }
        val w = Mat.mul(parent, local)
        if (node.mesh >= 0 && node.mesh < g.model.meshes.size) drawMesh(g, node.mesh, w, c)
        for (ch in node.children) drawNode(g, ch, w, c)
    }

    private fun drawMesh(g: CarGpu, mi: Int, w: FloatArray, c: Car) {
        Mat.upper3(w, nrmTmp)
        GLES20.glUniformMatrix4fv(uModel, 1, false, w, 0)
        GLES20.glUniformMatrix3fv(uNorm, 1, false, nrmTmp, 0)
        val prims = g.model.meshes[mi]
        for (pi in prims.indices) {
            val prim = prims[pi]
            val mat = g.model.materials[prim.material]
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, g.vbos[mi][pi])
            GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 24, 0)
            GLES20.glEnableVertexAttribArray(0)
            GLES20.glVertexAttribPointer(1, 3, GLES20.GL_FLOAT, false, 24, 12)
            GLES20.glEnableVertexAttribArray(1)
            GLES20.glDisableVertexAttribArray(2)
            GLES20.glDisableVertexAttribArray(3)
            if (mat.name == "paint") {
                GLES20.glVertexAttrib3f(2, c.paint[0], c.paint[1], c.paint[2])
            } else {
                GLES20.glVertexAttrib3f(2, mat.r, mat.g, mat.b)
            }
            GLES20.glVertexAttrib1f(3, mat.shine)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, prim.vertexCount)
        }
    }
}
