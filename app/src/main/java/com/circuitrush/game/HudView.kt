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
    // padA = left slot (steers RIGHT), padB = right slot (steers LEFT)
    private val padA = RectF()
    private val padB = RectF()
    private val rGas = RectF()
    private val rBrake = RectF()
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
        u = vh / 10f
        val m = 0.3f * u
        padA.set(m, vh - m - 2.6f * u, m + 2.4f * u, vh - m)
        padB.set(m + 2.7f * u, vh - m - 2.6f * u, m + 5.1f * u, vh - m)
        rGas.set(vw - m - 2.6f * u, vh - m - 3.6f * u, vw - m, vh - m)
        rBrake.set(vw - m - 5.3f * u, vh - m - 2.6f * u, vw - m - 2.9f * u, vh - m)
        rPause.set(m, m, m + 1.1f * u, m + 1.1f * u)
        rCam.set(m + 1.4f * u, m, m + 2.5f * u, m + 1.1f * u)
        rMap.set(vw - m - 3.2f * u, m, vw - m, m + 3.2f * u)
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
        p.setShadowLayer(size * 0.10f, 0f, size * 0.05f, Color.argb(190, 0, 0, 0))
        c.drawText(s, x, y, p)
        p.clearShadowLayer()
    }

    private fun pad(c: Canvas, r: RectF, pressed: Boolean, red: Int, green: Int, blue: Int) {
        val rad = 0.22f * u
        p.style = Paint.Style.FILL
        p.color = Color.argb(if (pressed) 130 else 55, red, green, blue)
        c.drawRoundRect(r, rad, rad, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.04f * u
        p.color = Color.argb(140, 255, 255, 255)
        c.drawRoundRect(r, rad, rad, p)
        p.style = Paint.Style.FILL
    }

    private fun drawArrow(c: Canvas, r: RectF, dir: Int) {
        val cx = r.centerX()
        val cy = r.centerY()
        val a = 0.5f * u
        arrow.reset()
        arrow.moveTo(cx + dir * a, cy)
        arrow.lineTo(cx - dir * a * 0.6f, cy - a)
        arrow.lineTo(cx - dir * a * 0.6f, cy + a)
        arrow.close()
        p.style = Paint.Style.FILL
        p.color = Color.argb(210, 255, 255, 255)
        c.drawPath(arrow, p)
    }

    private fun buildMinimap() {
        val tr = Game.track ?: return
        val bw = tr.maxX - tr.minX
        val bh = tr.maxZ - tr.minZ
        val pd = 0.3f * u
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
        val soft = Color.argb(200, 255, 255, 255)

        // steering pads: right-arrow in the LEFT slot, left-arrow in the RIGHT slot
        if (!Game.useTilt) {
            pad(c, padA, Game.right, 255, 255, 255)
            pad(c, padB, Game.left, 255, 255, 255)
            drawArrow(c, padA, 1)
            drawArrow(c, padB, -1)
        } else {
            txt(c, "TILT TO STEER", 0.4f * u, vh - 0.5f * u, 0.32f * u, soft, Paint.Align.LEFT)
        }
        pad(c, rBrake, Game.brake, 255, 60, 50)
        txt(c, "BRAKE", rBrake.centerX(), rBrake.centerY() + 0.13f * u, 0.36f * u, white, Paint.Align.CENTER)
        pad(c, rGas, Game.gas, 70, 235, 100)
        txt(c, "GAS", rGas.centerX(), rGas.centerY() + 0.18f * u, 0.5f * u, white, Paint.Align.CENTER)

        // pause + camera
        pad(c, rPause, false, 255, 255, 255)
        p.style = Paint.Style.FILL
        p.color = white
        val px = rPause.centerX()
        val py = rPause.centerY()
        c.drawRect(px - 0.24f * u, py - 0.3f * u, px - 0.08f * u, py + 0.3f * u, p)
        c.drawRect(px + 0.08f * u, py - 0.3f * u, px + 0.24f * u, py + 0.3f * u, p)
        pad(c, rCam, false, 255, 255, 255)
        txt(c, "CAM " + (Game.camMode + 1), rCam.centerX(), rCam.centerY() + 0.12f * u, 0.3f * u, white, Paint.Align.CENTER)

        // position (top-left, next to buttons)
        val lx = rCam.right + 0.35f * u
        txt(c, "POS", lx, 0.62f * u, 0.28f * u, soft, Paint.Align.LEFT)
        txt(c, "" + s.pos + "/" + s.total, lx, 1.4f * u, 0.85f * u, white, Paint.Align.LEFT)

        // lap block (top-left column)
        val bx = 0.3f * u
        txt(c, "LAP " + s.lap + "/" + s.laps, bx, 2.35f * u, 0.5f * u, white, Paint.Align.LEFT)
        txt(c, Game.fmt(s.lapTime), bx, 3.05f * u, 0.68f * u, white, Paint.Align.LEFT)
        txt(c, "BEST " + Game.fmt(s.bestLap), bx, 3.65f * u, 0.32f * u, Color.argb(235, 255, 220, 90), Paint.Align.LEFT)

        // speed block (left of minimap)
        val sx = rMap.left - 0.35f * u
        txt(c, "" + s.speedKmh, sx, 1.4f * u, 1.25f * u, white, Paint.Align.RIGHT)
        txt(c, "km/h   G" + s.gear, sx, 1.9f * u, 0.32f * u, soft, Paint.Align.RIGHT)
        val segs = 12
        val bw = 0.2f * u
        val gap = 0.05f * u
        val total = segs * (bw + gap) - gap
        val bx0 = sx - total
        p.style = Paint.Style.FILL
        for (i in 0 until segs) {
            val on = (i + 1).toFloat() / segs <= s.rpm + 0.04f
            val col = if (i < 7) Color.rgb(80, 220, 110) else if (i < 10) Color.rgb(250, 210, 60) else Color.rgb(240, 60, 50)
            p.color = if (on) col else Color.argb(70, 255, 255, 255)
            val x0 = bx0 + i * (bw + gap)
            c.drawRect(x0, 2.2f * u, x0 + bw, 2.5f * u, p)
        }

        // minimap
        if (!mmBuilt) buildMinimap()
        p.style = Paint.Style.FILL
        p.color = Color.argb(100, 0, 0, 0)
        c.drawRoundRect(rMap, 0.22f * u, 0.22f * u, p)
        if (mmBuilt) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 0.09f * u
            p.color = Color.argb(200, 230, 230, 240)
            c.drawPath(mmPath, p)
            p.style = Paint.Style.FILL
            for (i in s.carX.indices) {
                if (i == s.playerIdx) continue
                p.color = s.carColor[i]
                c.drawCircle(mmOx + s.carX[i] * mmScale, mmOy - s.carZ[i] * mmScale, 0.1f * u, p)
            }
            val pi = s.playerIdx
            if (pi >= 0 && pi < s.carX.size) {
                val mx = mmOx + s.carX[pi] * mmScale
                val my = mmOy - s.carZ[pi] * mmScale
                p.color = Color.WHITE
                c.drawCircle(mx, my, 0.18f * u, p)
                p.color = s.carColor[pi]
                c.drawCircle(mx, my, 0.12f * u, p)
            }
        }

        // centre messages
        if (s.countdown > 0) {
            txt(c, "" + s.countdown, vw * 0.5f, vh * 0.40f, 2.4f * u, Color.rgb(255, 80, 60), Paint.Align.CENTER)
        }
        if (s.message.isNotEmpty()) {
            val col = if (s.message == "GO!") Color.rgb(90, 255, 120) else if (s.message == "WRONG WAY") Color.rgb(255, 70, 60) else white
            txt(c, s.message, vw * 0.5f, vh * 0.38f, 1.1f * u, col, Paint.Align.CENTER)
        }
        if (s.offRoad && s.raceState == 1) {
            txt(c, "OFF TRACK", vw * 0.5f, vh * 0.52f, 0.42f * u, Color.rgb(255, 170, 40), Paint.Align.CENTER)
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
                if (padA.contains(x, y)) r = true
                if (padB.contains(x, y)) l = true
                if (rGas.contains(x, y)) g = true
                if (rBrake.contains(x, y)) b = true
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
