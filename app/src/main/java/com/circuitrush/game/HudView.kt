package com.circuitrush.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

class HudView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rl = RectF()
    private val rr = RectF()
    private val rg = RectF()
    private val rb = RectF()
    private val rPause = RectF()
    private val rCam = RectF()
    private val rMap = RectF()
    private var u = 10f
    private var vw = 0f
    private var vh = 0f
    private val mmPath = Path()
    private var mmBuilt = false
    private var mmScale = 1f
    private var mmOx = 0f
    private var mmOy = 0f
    private val arrow = Path()
    var onPause: (() -> Unit)? = null

    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) {
        vw = nw.toFloat()
        vh = nh.toFloat()
        u = vh / 6.2f
        rl.set(0.4f * u, vh - 3.1f * u, 2.9f * u, vh - 0.6f * u)
        rr.set(3.2f * u, vh - 3.1f * u, 5.7f * u, vh - 0.6f * u)
        rg.set(vw - 3.5f * u, vh - 4.2f * u, vw - 0.5f * u, vh - 0.6f * u)
        rb.set(vw - 6.6f * u, vh - 3.1f * u, vw - 3.8f * u, vh - 0.6f * u)
        rMap.set(vw - 4.4f * u, 0.35f * u, vw - 0.35f * u, 4.4f * u)
        rPause.set(0.4f * u, 0.35f * u, 1.65f * u, 1.6f * u)
        rCam.set(1.95f * u, 0.35f * u, 3.2f * u, 1.6f * u)
        mmBuilt = false
    }

    override fun onDraw(c: Canvas) {
        val s = Game.hud
        if (s.mode == 1) drawHud(c, s)
        postInvalidateOnAnimation()
    }

    private fun txt(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align) {
        p.style = Paint.Style.FILL
        p.color = color
        p.textSize = size
        p.textAlign = align
        p.typeface = Typeface.DEFAULT_BOLD
        p.setShadowLayer(size * 0.08f, 0f, size * 0.05f, Color.argb(170, 0, 0, 0))
        c.drawText(s, x, y, p)
        p.clearShadowLayer()
    }

    private fun pad(c: Canvas, r: RectF, pressed: Boolean, rr: Int, gg: Int, bb: Int) {
        p.style = Paint.Style.FILL
        p.color = Color.argb(if (pressed) 150 else 70, rr, gg, bb)
        c.drawRoundRect(r, 0.3f * u, 0.3f * u, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.05f * u
        p.color = Color.argb(150, 255, 255, 255)
        c.drawRoundRect(r, 0.3f * u, 0.3f * u, p)
        p.style = Paint.Style.FILL
    }

    private fun drawArrow(c: Canvas, r: RectF, dir: Int) {
        val cx = r.centerX()
        val cy = r.centerY()
        val a = 0.55f * u
        arrow.reset()
        arrow.moveTo(cx + dir * a, cy)
        arrow.lineTo(cx - dir * a * 0.6f, cy - a)
        arrow.lineTo(cx - dir * a * 0.6f, cy + a)
        arrow.close()
        p.style = Paint.Style.FILL
        p.color = Color.argb(220, 255, 255, 255)
        c.drawPath(arrow, p)
    }

    private fun buildMinimap() {
        val tr = Game.track ?: return
        val bw = tr.maxX - tr.minX
        val bh = tr.maxZ - tr.minZ
        val pd = 0.35f * u
        val aw = rMap.width() - 2f * pd
        val ah = rMap.height() - 2f * pd
        mmScale = min(aw / bw, ah / bh)
        mmOx = rMap.left + pd + (aw - bw * mmScale) * 0.5f - tr.minX * mmScale
        mmOy = rMap.top + pd + (ah - bh * mmScale) * 0.5f + tr.maxZ * mmScale
        mmPath.reset()
        var i = 0
        while (i < tr.n) {
            val x = mmOx + tr.cx[i] * mmScale
            val y = mmOy - tr.cz[i] * mmScale
            if (i == 0) mmPath.moveTo(x, y) else mmPath.lineTo(x, y)
            i += 3
        }
        mmPath.close()
        mmBuilt = true
    }

    private fun drawHud(c: Canvas, s: HudState) {
        val white = Color.WHITE
        // touch controls
        if (!Game.useTilt) {
            pad(c, rl, Game.left, 255, 255, 255)
            pad(c, rr, Game.right, 255, 255, 255)
            drawArrow(c, rl, -1)
            drawArrow(c, rr, 1)
        } else {
            txt(c, "TILT TO STEER", 0.5f * u, vh - 0.8f * u, 0.4f * u, Color.argb(180, 255, 255, 255), Paint.Align.LEFT)
        }
        pad(c, rb, Game.brake, 255, 60, 50)
        txt(c, "BRAKE", rb.centerX(), rb.centerY() + 0.18f * u, 0.5f * u, white, Paint.Align.CENTER)
        pad(c, rg, Game.gas, 70, 235, 100)
        txt(c, "GAS", rg.centerX(), rg.centerY() + 0.25f * u, 0.7f * u, white, Paint.Align.CENTER)

        // pause + camera buttons
        pad(c, rPause, false, 255, 255, 255)
        p.color = white
        c.drawRect(rPause.centerX() - 0.28f * u, rPause.centerY() - 0.35f * u, rPause.centerX() - 0.1f * u, rPause.centerY() + 0.35f * u, p)
        c.drawRect(rPause.centerX() + 0.1f * u, rPause.centerY() - 0.35f * u, rPause.centerX() + 0.28f * u, rPause.centerY() + 0.35f * u, p)
        pad(c, rCam, false, 255, 255, 255)
        txt(c, "CAM " + (Game.camMode + 1), rCam.centerX(), rCam.centerY() + 0.15f * u, 0.36f * u, white, Paint.Align.CENTER)

        // position + lap
        txt(c, "POS", 3.55f * u, 0.85f * u, 0.36f * u, Color.argb(200, 255, 255, 255), Paint.Align.LEFT)
        txt(c, "" + s.pos + "/" + s.total, 3.5f * u, 2.05f * u, 1.25f * u, white, Paint.Align.LEFT)

        // lap + time (top centre)
        txt(c, "LAP " + s.lap + "/" + s.laps, vw * 0.5f, 0.95f * u, 0.62f * u, white, Paint.Align.CENTER)
        txt(c, Game.fmt(s.lapTime), vw * 0.5f, 1.85f * u, 0.85f * u, white, Paint.Align.CENTER)
        txt(c, "BEST " + Game.fmt(s.bestLap), vw * 0.5f, 2.55f * u, 0.42f * u, Color.argb(230, 255, 220, 90), Paint.Align.CENTER)

        // speedometer (left of minimap)
        val sx = rMap.left - 0.45f * u
        txt(c, "" + s.speedKmh, sx, 2.15f * u, 1.7f * u, white, Paint.Align.RIGHT)
        txt(c, "km/h   G" + s.gear, sx, 2.75f * u, 0.42f * u, Color.argb(220, 255, 255, 255), Paint.Align.RIGHT)
        // rev bar
        val segs = 12
        val bw = 0.34f * u
        val gap = 0.08f * u
        val total = segs * (bw + gap)
        val bx0 = sx - total
        for (i in 0 until segs) {
            val on = (i + 1).toFloat() / segs <= s.rpm + 0.04f
            val col = if (i < 7) Color.rgb(80, 220, 110) else if (i < 10) Color.rgb(250, 210, 60) else Color.rgb(240, 60, 50)
            p.style = Paint.Style.FILL
            p.color = if (on) col else Color.argb(70, 255, 255, 255)
            val x0 = bx0 + i * (bw + gap)
            c.drawRect(x0, 3.05f * u, x0 + bw, 3.45f * u, p)
        }

        // minimap
        if (!mmBuilt) buildMinimap()
        p.style = Paint.Style.FILL
        p.color = Color.argb(110, 0, 0, 0)
        c.drawRoundRect(rMap, 0.3f * u, 0.3f * u, p)
        if (mmBuilt) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 0.13f * u
            p.color = Color.argb(200, 230, 230, 240)
            c.drawPath(mmPath, p)
            p.style = Paint.Style.FILL
            for (i in s.carX.indices) {
                if (i == s.playerIdx) continue
                p.color = s.carColor[i]
                c.drawCircle(mmOx + s.carX[i] * mmScale, mmOy - s.carZ[i] * mmScale, 0.15f * u, p)
            }
            val pi = s.playerIdx
            if (pi >= 0 && pi < s.carX.size) {
                val px = mmOx + s.carX[pi] * mmScale
                val py = mmOy - s.carZ[pi] * mmScale
                p.color = Color.WHITE
                c.drawCircle(px, py, 0.27f * u, p)
                p.color = s.carColor[pi]
                c.drawCircle(px, py, 0.19f * u, p)
            }
        }

        // centre messages
        if (s.countdown > 0) {
            txt(c, "" + s.countdown, vw * 0.5f, vh * 0.48f, 3.6f * u, Color.rgb(255, 80, 60), Paint.Align.CENTER)
        }
        if (s.message.isNotEmpty()) {
            val col = if (s.message == "GO!") Color.rgb(90, 255, 120) else if (s.message == "WRONG WAY") Color.rgb(255, 70, 60) else white
            txt(c, s.message, vw * 0.5f, vh * 0.46f, 1.6f * u, col, Paint.Align.CENTER)
        }
        if (s.offRoad && s.raceState == 1) {
            txt(c, "OFF TRACK", vw * 0.5f, vh * 0.62f, 0.6f * u, Color.rgb(255, 170, 40), Paint.Align.CENTER)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val action = e.actionMasked
        var l = false
        var r = false
        var g = false
        var b = false
        if (action != MotionEvent.ACTION_CANCEL && action != MotionEvent.ACTION_UP) {
            for (i in 0 until e.pointerCount) {
                if (action == MotionEvent.ACTION_POINTER_UP && i == e.actionIndex) continue
                val x = e.getX(i)
                val y = e.getY(i)
                if (rl.contains(x, y)) l = true
                if (rr.contains(x, y)) r = true
                if (rg.contains(x, y)) g = true
                if (rb.contains(x, y)) b = true
            }
        }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            val i = e.actionIndex
            val x = e.getX(i)
            val y = e.getY(i)
            if (rPause.contains(x, y)) onPause?.invoke()
            if (rCam.contains(x, y)) Game.camMode = (Game.camMode + 1) % 3
        }
        Game.left = l
        Game.right = r
        Game.gas = g
        Game.brake = b
        return true
    }
}
