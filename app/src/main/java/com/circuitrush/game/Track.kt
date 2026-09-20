package com.circuitrush.game

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class Track(js: JSONObject) {
    val n: Int = js.getInt("n")
    val ds: Float = js.getDouble("ds").toFloat()
    val length: Float = js.getDouble("L").toFloat()
    val s0: Float = js.getDouble("s0").toFloat()
    val half: Float = js.getDouble("half").toFloat()
    val kerb: Float = js.getDouble("kerb").toFloat()
    val barrier: Float = js.getDouble("barrier").toFloat()
    val cx = FloatArray(n)
    val cz = FloatArray(n)
    val ch = FloatArray(n)
    val ck = FloatArray(n)
    var minX = 1e9f
    var maxX = -1e9f
    var minZ = 1e9f
    var maxZ = -1e9f

    init {
        val cl = js.getJSONArray("cl")
        for (i in 0 until n) {
            cx[i] = cl.getDouble(i * 4).toFloat()
            cz[i] = cl.getDouble(i * 4 + 1).toFloat()
            ch[i] = cl.getDouble(i * 4 + 2).toFloat()
            ck[i] = cl.getDouble(i * 4 + 3).toFloat()
            if (cx[i] < minX) minX = cx[i]
            if (cx[i] > maxX) maxX = cx[i]
            if (cz[i] < minZ) minZ = cz[i]
            if (cz[i] > maxZ) maxZ = cz[i]
        }
    }

    /** Index of the nearest centerline sample. hint < 0 -> global search, otherwise local window. */
    fun nearest(x: Float, z: Float, hint: Int): Int {
        var best = 0
        var bd = Float.MAX_VALUE
        if (hint < 0) {
            for (i in 0 until n) {
                val dx = x - cx[i]
                val dz = z - cz[i]
                val d = dx * dx + dz * dz
                if (d < bd) { bd = d; best = i }
            }
        } else {
            for (o in -14..14) {
                val i = ((hint + o) % n + n) % n
                val dx = x - cx[i]
                val dz = z - cz[i]
                val d = dx * dx + dz * dz
                if (d < bd) { bd = d; best = i }
            }
            if (bd > 3600f) return nearest(x, z, -1)
        }
        return best
    }

    /** out = (x, z, heading) of the point at arc position s and lateral offset lat (+ = right). */
    fun place(s: Float, lat: Float, out: FloatArray) {
        val sw = ((s % length) + length) % length
        val i = ((sw / ds).toInt()) % n
        val h = ch[i]
        out[0] = cx[i] + cos(h) * lat
        out[1] = cz[i] - sin(h) * lat
        out[2] = h
    }

    /** Target speed profile (m/s) per sample: corner limit + braking look-ahead. */
    fun profile(vTop: Float, aLat: Float, aBrake: Float): FloatArray {
        val v = FloatArray(n)
        for (i in 0 until n) {
            val k = abs(ck[i])
            v[i] = if (k < 0.002f) vTop else min(vTop, sqrt(aLat / k))
        }
        for (pass in 0 until 2) {
            for (ii in n - 1 downTo 0) {
                val nx = v[(ii + 1) % n]
                val lim = sqrt(nx * nx + 2f * aBrake * ds)
                if (v[ii] > lim) v[ii] = lim
            }
        }
        return v
    }
}
