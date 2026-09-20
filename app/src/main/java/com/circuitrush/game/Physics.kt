package com.circuitrush.game

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tan

class Car(val id: Int, val isPlayer: Boolean) {
    var x = 0f
    var z = 0f
    var yaw = 0f
    var vx = 0f
    var vz = 0f
    var speed = 0f
    var steerCmd = 0f
    var steer = 0f
    var throttle = 0f
    var brake = 0f
    var roll = 0f
    var steerVis = 0f
    var idx = -1
    var lat = 0f
    var sRel = 0f
    var lap = 0
    var raceDist = 0f
    var finished = false
    var finishTime = 0f
    var lapStart = 0f
    var lastLap = 0f
    var bestLap = 0f
    var offRoad = false
    var modelIndex = 0
    val paint = FloatArray(3)
    var paintArgb = 0
    var power = 1f
    var skill = 1f
    var laneBase = 0f
    var lane = 0f
    var laneTarget = 0f
    var reaction = 0f
    var name = ""
    var autopilot = false
    var gear = 1
    var rpm = 0f
    var position = 1
    var impact = 0f
}

object Physics {
    private val gearTop = floatArrayOf(22f, 36f, 50f, 62f, 72f, 90f)

    fun step(c: Car, dt: Float) {
        val fx = sin(c.yaw)
        val fz = cos(c.yaw)
        val rx = cos(c.yaw)
        val rz = -sin(c.yaw)
        var vF = c.vx * fx + c.vz * fz
        val vL = c.vx * rx + c.vz * rz

        // steering smoothing (returns to centre faster than it turns in)
        val diff = c.steerCmd - c.steer
        val quick = abs(c.steerCmd) < abs(c.steer) || c.steerCmd * c.steer < 0f
        val stepMax = (if (quick) 9f else 5f) * dt
        c.steer += diff.coerceIn(-stepMax, stepMax)

        // longitudinal
        val top = Tune.TOP_SPEED * c.power * (if (c.offRoad) 0.55f else 1f)
        var a = 0f
        val sg = if (vF > 0.1f) 1f else if (vF < -0.1f) -1f else 0f
        if (c.throttle > 0.01f && c.brake < 0.05f) {
            val ratio = (vF / top).coerceIn(0f, 1.3f)
            val taper = (1f - ratio.pow(1.5f)).coerceAtLeast(0f)
            a += Tune.ENGINE_ACC * c.throttle * taper * (if (vF < 0f) 2f else 1f)
        }
        var braking = false
        if (c.brake > 0.05f) {
            if (vF > 0.8f) {
                a -= Tune.BRAKE * c.brake
                braking = true
            } else if (c.throttle < 0.05f) {
                a -= Tune.REVERSE_ACC * c.brake
            }
        }
        a -= sg * (Tune.DRAG * vF * vF + (if (c.offRoad) 4.5f else 0.35f))
        var nvF = vF + a * dt
        if (braking && nvF < 0f) nvF = 0f
        if (nvF < -12f) nvF = -12f
        if (c.throttle < 0.05f && c.brake < 0.05f && sg != 0f && nvF * sg < 0f) nvF = 0f
        vF = nvF

        // yaw from bicycle model with grip limit
        val sAbs = abs(vF)
        val delta = c.steer * Tune.steerLimit(sAbs)
        var yawRate = vF / Tune.WHEELBASE * tan(delta)
        val aMax = (if (c.offRoad) 11f else Tune.GRIP_BASE) + Tune.DOWNFORCE * vF * vF
        if (sAbs > 0.5f) {
            val need = abs(yawRate) * sAbs
            if (need > aMax) yawRate = sign(yawRate) * aMax / sAbs
        }

        val wvx = fx * vF + rx * vL
        val wvz = fz * vF + rz * vL
        c.yaw += yawRate * dt
        val nfx = sin(c.yaw)
        val nfz = cos(c.yaw)
        val nrx = cos(c.yaw)
        val nrz = -sin(c.yaw)
        val pF = wvx * nfx + wvz * nfz
        val pL = wvx * nrx + wvz * nrz
        val k = if (c.offRoad) 3.5f else 9f
        val newL = pL * exp(-k * dt)
        var fin = pF
        val scrub = (abs(pL) - abs(newL)) * 0.35f
        if (fin > 0f) fin -= min(scrub, fin) else if (fin < 0f) fin += min(scrub, -fin)
        c.vx = nfx * fin + nrx * newL
        c.vz = nfz * fin + nrz * newL
        c.x += c.vx * dt
        c.z += c.vz * dt
        c.speed = fin

        // visuals
        c.roll += fin / 0.36f * dt
        if (c.roll > 6.2831855f) c.roll -= 6.2831855f
        if (c.roll < -6.2831855f) c.roll += 6.2831855f
        c.steerVis = c.steer * 0.32f
        val av = abs(fin)
        var g = 0
        while (g < 5 && av > gearTop[g]) g++
        val lo = if (g == 0) 0f else gearTop[g - 1]
        c.gear = g + 1
        val frac = ((av - lo) / (gearTop[g] - lo)).coerceIn(0f, 1f)
        c.rpm = (0.30f + 0.70f * frac).coerceIn(0.2f, 1f)
    }
}
