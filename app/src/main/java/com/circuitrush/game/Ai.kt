package com.circuitrush.game

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object Ai {
    fun drive(c: Car, r: Race, dt: Float) {
        val t = r.track
        val idx = if (c.idx >= 0) c.idx else 0
        val sp = max(c.speed, 0f)

        if (r.time < c.reaction) {
            c.throttle = 0f
            c.brake = 0f
            c.steerCmd = 0f
            return
        }

        val look = 6.5f + sp * 0.4f
        val steps = max(2, (look / t.ds).toInt())
        val ti = (idx + steps) % t.n

        // preferred lane on the racing line: outside before a corner, inside at the apex
        val kA = t.ck[ti]
        val kB = t.ck[(ti + 30) % t.n]
        var target = (kA * 85f - kB * 30f + c.laneBase).coerceIn(-4f, 4f)

        // avoidance of cars ahead
        var blockSpeed = 1000f
        var blockDist = 1000f
        for (o in r.cars) {
            if (o === c) continue
            var d = o.sRel - c.sRel
            if (d < 0f) d += t.length
            if (d > 0.5f && d < 30f) {
                val dl = o.lat - c.lat
                if (abs(dl) < 3.3f) {
                    if (d < blockDist) {
                        blockDist = d
                        blockSpeed = o.speed
                    }
                    val side = if (dl > 0f) -1f else 1f
                    target = (c.lat + side * 3.4f).coerceIn(-4.3f, 4.3f)
                }
            }
        }
        c.laneTarget = target
        c.lane += (c.laneTarget - c.lane) * (1f - exp(-2.2f * dt))

        // steering: pure pursuit -> desired yaw rate -> wheel angle -> normalised input
        val tx = t.cx[ti] + cos(t.ch[ti]) * c.lane
        val tz = t.cz[ti] - sin(t.ch[ti]) * c.lane
        val err = wrapAngle(atan2(tx - c.x, tz - c.z) - c.yaw)
        val wantRate = (err * 2.4f).coerceIn(-1.7f, 1.7f)
        val delta = atan(wantRate * Tune.WHEELBASE / max(sp, 4f))
        val lim = Tune.steerLimit(sp)
        c.steerCmd = (delta / lim).coerceIn(-1f, 1f)

        // speed
        var vt = r.profile[(idx + 6) % t.n] * c.skill * (0.6f + 0.4f * c.power) * r.aiFactor(c)
        if (abs(c.lat) > t.half) vt *= 0.6f
        if (blockDist < 12f) vt = min(vt, max(blockSpeed, 5f) * 1.02f)
        if (sp < vt - 0.8f) {
            c.throttle = 1f
            c.brake = 0f
        } else if (sp > vt + 1.2f) {
            c.throttle = 0f
            c.brake = ((sp - vt) / 6f).coerceIn(0.2f, 1f)
        } else {
            c.throttle = 0.35f
            c.brake = 0f
        }
    }
}
