package com.circuitrush.game

import android.opengl.Matrix
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

const val PIF = 3.14159265f
const val RAD2DEG = 57.29578f

class FloatList(cap: Int = 1024) {
    var data = FloatArray(cap)
    var size = 0

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    fun toArray(): FloatArray = data.copyOf(size)
}

fun wrapAngle(a: Float): Float {
    var r = a
    while (r > PIF) r -= 2f * PIF
    while (r < -PIF) r += 2f * PIF
    return r
}

object Mat {
    fun identity(): FloatArray {
        val m = FloatArray(16)
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
        return m
    }

    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        Matrix.multiplyMM(r, 0, a, 0, b, 0)
        return r
    }

    /** T * Ry(yaw) * S, yaw in radians (same convention as glRotatef about +Y). */
    fun placement(x: Float, y: Float, z: Float, yaw: Float, sx: Float, sy: Float, sz: Float): FloatArray {
        val c = cos(yaw)
        val s = sin(yaw)
        val m = FloatArray(16)
        m[0] = c * sx; m[1] = 0f; m[2] = -s * sx; m[3] = 0f
        m[4] = 0f; m[5] = sy; m[6] = 0f; m[7] = 0f
        m[8] = s * sz; m[9] = 0f; m[10] = c * sz; m[11] = 0f
        m[12] = x; m[13] = y; m[14] = z; m[15] = 1f
        return m
    }

    fun fromTRS(t: FloatArray, q: FloatArray, s: FloatArray): FloatArray {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val m = FloatArray(16)
        m[0] = (1f - 2f * (y * y + z * z)) * s[0]
        m[1] = (2f * (x * y + z * w)) * s[0]
        m[2] = (2f * (x * z - y * w)) * s[0]
        m[4] = (2f * (x * y - z * w)) * s[1]
        m[5] = (1f - 2f * (x * x + z * z)) * s[1]
        m[6] = (2f * (y * z + x * w)) * s[1]
        m[8] = (2f * (x * z + y * w)) * s[2]
        m[9] = (2f * (y * z - x * w)) * s[2]
        m[10] = (1f - 2f * (x * x + y * y)) * s[2]
        m[12] = t[0]; m[13] = t[1]; m[14] = t[2]; m[15] = 1f
        return m
    }

    fun det3(m: FloatArray): Float {
        val a00 = m[0]; val a10 = m[1]; val a20 = m[2]
        val a01 = m[4]; val a11 = m[5]; val a21 = m[6]
        val a02 = m[8]; val a12 = m[9]; val a22 = m[10]
        return a00 * (a11 * a22 - a21 * a12) - a01 * (a10 * a22 - a20 * a12) + a02 * (a10 * a21 - a20 * a11)
    }

    /** Row-major inverse-transpose of the upper-left 3x3 (normal matrix). */
    fun normalRows(m: FloatArray): FloatArray {
        val a00 = m[0]; val a10 = m[1]; val a20 = m[2]
        val a01 = m[4]; val a11 = m[5]; val a21 = m[6]
        val a02 = m[8]; val a12 = m[9]; val a22 = m[10]
        val det = a00 * (a11 * a22 - a21 * a12) - a01 * (a10 * a22 - a20 * a12) + a02 * (a10 * a21 - a20 * a11)
        val out = FloatArray(9)
        if (abs(det) < 1e-12f) {
            out[0] = 1f; out[4] = 1f; out[8] = 1f
            return out
        }
        val inv = 1f / det
        out[0] = (a11 * a22 - a12 * a21) * inv
        out[1] = -(a10 * a22 - a12 * a20) * inv
        out[2] = (a10 * a21 - a11 * a20) * inv
        out[3] = -(a01 * a22 - a02 * a21) * inv
        out[4] = (a00 * a22 - a02 * a20) * inv
        out[5] = -(a00 * a21 - a01 * a20) * inv
        out[6] = (a01 * a12 - a02 * a11) * inv
        out[7] = -(a00 * a12 - a02 * a10) * inv
        out[8] = (a00 * a11 - a01 * a10) * inv
        return out
    }

    /** Column-major mat3 taken directly from the upper-left of a 4x4 (for uniform-scale rigid transforms). */
    fun upper3(m: FloatArray, out: FloatArray) {
        out[0] = m[0]; out[1] = m[1]; out[2] = m[2]
        out[3] = m[4]; out[4] = m[5]; out[5] = m[6]
        out[6] = m[8]; out[7] = m[9]; out[8] = m[10]
    }
}
