package com.circuitrush.game

import android.content.Context
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** CPU-side geometry chunk: interleaved pos(3) normal(3) color(3) shine(1) = 10 floats per vertex. */
class ChunkData(val data: FloatArray, val count: Int, val cx: Float, val cz: Float, val radius: Float) {
    var vbo = 0
}

class StaticBatch {
    private class Bucket {
        val list = FloatList(8192)
        var minX = 1e9f
        var maxX = -1e9f
        var minZ = 1e9f
        var maxZ = -1e9f
    }

    private val chunk = 96f
    private val map = HashMap<Long, Bucket>()
    private val sp = FloatArray(9)
    private val sn = FloatArray(9)

    fun tri(p: FloatArray, n: FloatArray, r: Float, g: Float, b: Float, shine: Float, flip: Boolean) {
        val cxm = (p[0] + p[3] + p[6]) / 3f
        val czm = (p[2] + p[5] + p[8]) / 3f
        val kx = floor(cxm / chunk).toInt()
        val kz = floor(czm / chunk).toInt()
        val key = (kx.toLong() shl 32) or (kz.toLong() and 0xffffffffL)
        var bk = map[key]
        if (bk == null) {
            bk = Bucket()
            map[key] = bk
        }
        for (k in 0 until 3) {
            val v = if (flip) (if (k == 1) 2 else if (k == 2) 1 else 0) else k
            val px = p[v * 3]
            val py = p[v * 3 + 1]
            val pz = p[v * 3 + 2]
            val l = bk.list
            l.add(px); l.add(py); l.add(pz)
            l.add(n[v * 3]); l.add(n[v * 3 + 1]); l.add(n[v * 3 + 2])
            l.add(r); l.add(g); l.add(b)
            l.add(shine)
            if (px < bk.minX) bk.minX = px
            if (px > bk.maxX) bk.maxX = px
            if (pz < bk.minZ) bk.minZ = pz
            if (pz > bk.maxZ) bk.maxZ = pz
        }
    }

    fun triP(
        x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float,
        nx: Float, ny: Float, nz: Float, r: Float, g: Float, b: Float, shine: Float
    ) {
        sp[0] = x0; sp[1] = y0; sp[2] = z0
        sp[3] = x1; sp[4] = y1; sp[5] = z1
        sp[6] = x2; sp[7] = y2; sp[8] = z2
        for (k in 0 until 3) {
            sn[k * 3] = nx; sn[k * 3 + 1] = ny; sn[k * 3 + 2] = nz
        }
        tri(sp, sn, r, g, b, shine, false)
    }

    fun build(): List<ChunkData> {
        val out = ArrayList<ChunkData>()
        for ((_, bk) in map) {
            val arr = bk.list.toArray()
            val cx = (bk.minX + bk.maxX) * 0.5f
            val cz = (bk.minZ + bk.maxZ) * 0.5f
            val hx = (bk.maxX - bk.minX) * 0.5f
            val hz = (bk.maxZ - bk.minZ) * 0.5f
            out.add(ChunkData(arr, arr.size / 10, cx, cz, sqrt(hx * hx + hz * hz) + 2f))
        }
        return out
    }
}

object Baker {
    private val P = FloatArray(9)
    private val N = FloatArray(9)

    fun bake(batch: StaticBatch, m: GlbModel, world: FloatArray) {
        for (r in m.roots) walk(batch, m, r, world)
    }

    private fun walk(batch: StaticBatch, m: GlbModel, ni: Int, parent: FloatArray) {
        val node = m.nodes[ni]
        val w = Mat.mul(parent, node.local)
        if (node.mesh >= 0 && node.mesh < m.meshes.size) {
            val nm = Mat.normalRows(w)
            val flip = Mat.det3(w) < 0f
            for (prim in m.meshes[node.mesh]) {
                val mat = m.materials[prim.material]
                val d = prim.data
                var i = 0
                while (i + 2 < prim.vertexCount) {
                    for (k in 0 until 3) {
                        val o = (i + k) * 6
                        val x = d[o]
                        val y = d[o + 1]
                        val z = d[o + 2]
                        P[k * 3] = w[0] * x + w[4] * y + w[8] * z + w[12]
                        P[k * 3 + 1] = w[1] * x + w[5] * y + w[9] * z + w[13]
                        P[k * 3 + 2] = w[2] * x + w[6] * y + w[10] * z + w[14]
                        val nx = d[o + 3]
                        val ny = d[o + 4]
                        val nz = d[o + 5]
                        var ax = nm[0] * nx + nm[1] * ny + nm[2] * nz
                        var ay = nm[3] * nx + nm[4] * ny + nm[5] * nz
                        var az = nm[6] * nx + nm[7] * ny + nm[8] * nz
                        val l = sqrt(ax * ax + ay * ay + az * az)
                        if (l > 1e-6f) { ax /= l; ay /= l; az /= l }
                        N[k * 3] = ax; N[k * 3 + 1] = ay; N[k * 3 + 2] = az
                    }
                    batch.tri(P, N, mat.r, mat.g, mat.b, mat.shine, flip)
                    i += 3
                }
            }
        }
        for (c in node.children) walk(batch, m, c, w)
    }
}

object SceneBuilder {
    fun build(ctx: Context, js: JSONObject): List<ChunkData> {
        val batch = StaticBatch()
        val cache = HashMap<String, GlbModel>()
        fun model(name: String): GlbModel {
            val cached = cache[name]
            if (cached != null) return cached
            val m = GlbLoader.parse(ctx.assets.open("models/$name.glb").use { it.readBytes() })
            cache[name] = m
            return m
        }

        // ground tiles
        val bnd = js.getJSONArray("bounds")
        val gx0 = bnd.getDouble(0).toFloat() - 360f
        val gz0 = bnd.getDouble(1).toFloat() - 360f
        val gx1 = bnd.getDouble(2).toFloat() + 360f
        val gz1 = bnd.getDouble(3).toFloat() + 360f
        val tile = 40f
        var ix = 0
        var x = gx0
        while (x < gx1) {
            var iz = 0
            var z = gz0
            while (z < gz1) {
                val even = ((ix + iz) and 1) == 0
                val r = if (even) 0.040f else 0.050f
                val g = if (even) 0.215f else 0.255f
                val b = if (even) 0.045f else 0.055f
                batch.triP(x, -0.03f, z, x, -0.03f, z + tile, x + tile, -0.03f, z + tile, 0f, 1f, 0f, r, g, b, 0f)
                batch.triP(x, -0.03f, z, x + tile, -0.03f, z + tile, x + tile, -0.03f, z, 0f, 1f, 0f, r, g, b, 0f)
                z += tile
                iz++
            }
            x += tile
            ix++
        }

        // rebuilt road, kerbs and distant mountain silhouettes
        addRoadSurface(batch, js)
        addMountainRidges(batch, bnd)

        // track pieces
        val pieces = js.getJSONArray("pieces")
        for (i in 0 until pieces.length()) {
            val p = pieces.getJSONArray(i)
            val w = Mat.placement(
                p.getDouble(1).toFloat(), 0f, p.getDouble(2).toFloat(), p.getDouble(3).toFloat(),
                p.getInt(4).toFloat(), 1f, p.getDouble(5).toFloat()
            )
            Baker.bake(batch, model("track_" + p.getString(0)), w)
        }

        // props
        val props = js.getJSONArray("props")
        for (i in 0 until props.length()) {
            val p = props.getJSONArray(i)
            val s = p.getDouble(4).toFloat()
            val w = Mat.placement(p.getDouble(1).toFloat(), 0f, p.getDouble(2).toFloat(), p.getDouble(3).toFloat(), s, s, s)
            Baker.bake(batch, model("prop_" + p.getString(0)), w)
        }

        // trees
        val trees = js.getJSONArray("trees")
        for (i in 0 until trees.length()) {
            val t = trees.getJSONArray(i)
            addTree(batch, t.getDouble(0).toFloat(), t.getDouble(1).toFloat(), t.getDouble(2).toFloat(), t.getInt(3))
        }
        return batch.build()
    }

    private fun addRoadSurface(b: StaticBatch, js: JSONObject) {
        val cl = js.getJSONArray("cl")
        val n = js.getInt("n")
        val half = js.getDouble("half").toFloat()
        val kerb = js.getDouble("kerb").toFloat()
        val roadY = 0.045f
        val kerbY = 0.055f
        for (i in 0 until n) {
            val j = (i + 1) % n
            val x0 = cl.getDouble(i * 4).toFloat()
            val z0 = cl.getDouble(i * 4 + 1).toFloat()
            val h0 = cl.getDouble(i * 4 + 2).toFloat()
            val x1 = cl.getDouble(j * 4).toFloat()
            val z1 = cl.getDouble(j * 4 + 1).toFloat()
            val h1 = cl.getDouble(j * 4 + 2).toFloat()

            val l0x = x0 - cos(h0) * half
            val l0z = z0 + sin(h0) * half
            val r0x = x0 + cos(h0) * half
            val r0z = z0 - sin(h0) * half
            val l1x = x1 - cos(h1) * half
            val l1z = z1 + sin(h1) * half
            val r1x = x1 + cos(h1) * half
            val r1z = z1 - sin(h1) * half

            val roadR = if ((i and 1) == 0) 0.075f else 0.085f
            val roadG = if ((i and 1) == 0) 0.085f else 0.095f
            val roadB = if ((i and 1) == 0) 0.10f else 0.11f
            b.triP(l0x, roadY, l0z, r0x, roadY, r0z, r1x, roadY, r1z, 0f, 1f, 0f, roadR, roadG, roadB, 0.08f)
            b.triP(l0x, roadY, l0z, r1x, roadY, r1z, l1x, roadY, l1z, 0f, 1f, 0f, roadR, roadG, roadB, 0.08f)

            val stripe = ((i / 5) and 1) == 0
            val cr = if (stripe) 0.82f else 0.88f
            val cg = if (stripe) 0.03f else 0.88f
            val cb = if (stripe) 0.02f else 0.88f

            val ll0x = x0 - cos(h0) * kerb
            val ll0z = z0 + sin(h0) * kerb
            val rr0x = x0 + cos(h0) * kerb
            val rr0z = z0 - sin(h0) * kerb
            val ll1x = x1 - cos(h1) * kerb
            val ll1z = z1 + sin(h1) * kerb
            val rr1x = x1 + cos(h1) * kerb
            val rr1z = z1 - sin(h1) * kerb

            b.triP(l0x, kerbY, l0z, ll0x, kerbY, ll0z, ll1x, kerbY, ll1z, 0f, 1f, 0f, cr, cg, cb, 0f)
            b.triP(l0x, kerbY, l0z, ll1x, kerbY, ll1z, l1x, kerbY, l1z, 0f, 1f, 0f, cr, cg, cb, 0f)
            b.triP(r0x, kerbY, r0z, r1x, kerbY, r1z, rr1x, kerbY, rr1z, 0f, 1f, 0f, cr, cg, cb, 0f)
            b.triP(r0x, kerbY, r0z, rr1x, kerbY, rr1z, rr0x, kerbY, rr0z, 0f, 1f, 0f, cr, cg, cb, 0f)
        }
    }

    private fun addMountainRidges(b: StaticBatch, bnd: org.json.JSONArray) {
        val minX = bnd.getDouble(0).toFloat()
        val minZ = bnd.getDouble(1).toFloat()
        val maxX = bnd.getDouble(2).toFloat()
        val maxZ = bnd.getDouble(3).toFloat()
        val span = 230f
        val stepH = 48f
        val stepV = 48f

        fun hRidge(z: Float, toward: Float, seed: Float, height: Float, rr: Float, gg: Float, bb: Float) {
            var x = minX - span
            while (x < maxX + span) {
                val x1 = min(x + stepH, maxX + span)
                val mid = (x + x1) * 0.5f
                val w = 0.56f + 0.44f * sin(mid * 0.032f + seed)
                val w2 = 0.50f + 0.50f * cos(mid * 0.068f - seed * 1.4f)
                val h = height * (0.58f + 0.42f * w) + 7f * w2
                val depth = z + toward * 70f
                b.triP(x, 0f, z, mid, h, z, x1, 0f, z, 0f, 0.58f, -toward * 0.82f, rr, gg, bb, 0f)
                b.triP(x, 0f, depth, x1, 0f, depth, mid, h, z + toward * 26f, 0f, 0.58f, -toward * 0.82f, rr * 0.88f, gg * 0.88f, bb * 0.88f, 0f)
                x = x1
            }
        }

        fun vRidge(x: Float, toward: Float, seed: Float, height: Float, rr: Float, gg: Float, bb: Float) {
            var z = minZ - span
            while (z < maxZ + span) {
                val z1 = min(z + stepV, maxZ + span)
                val mid = (z + z1) * 0.5f
                val w = 0.56f + 0.44f * sin(mid * 0.028f + seed)
                val w2 = 0.50f + 0.50f * cos(mid * 0.061f - seed)
                val h = height * (0.58f + 0.42f * w) + 7f * w2
                val depth = x + toward * 70f
                b.triP(x, 0f, z, x, h, mid, x, 0f, z1, toward * 0.82f, 0.58f, 0f, rr, gg, bb, 0f)
                b.triP(depth, 0f, z, depth, 0f, z1, x, h, mid, toward * 0.82f, 0.58f, 0f, rr * 0.88f, gg * 0.88f, bb * 0.88f, 0f)
                z = z1
            }
        }

        hRidge(maxZ + span, -1f, 2.0f, 86f, 0.16f, 0.28f, 0.30f)
        hRidge(minZ - span, 1f, 5.0f, 74f, 0.14f, 0.26f, 0.32f)
        vRidge(minX - span, 1f, 7.0f, 70f, 0.15f, 0.27f, 0.24f)
        vRidge(maxX + span, -1f, 11.0f, 80f, 0.17f, 0.27f, 0.28f)
    }

    private fun addTree(b: StaticBatch, x: Float, z: Float, s: Float, type: Int) {
        val trunk = floatArrayOf(0.12f, 0.06f, 0.02f)
        val foliage = when (type) {
            0 -> floatArrayOf(0.02f, 0.13f, 0.03f)
            1 -> floatArrayOf(0.03f, 0.17f, 0.04f)
            else -> floatArrayOf(0.05f, 0.11f, 0.02f)
        }
        prism(b, x, z, 0f, 0.22f * s, 1.4f * s, 6, trunk)
        cone(b, x, z, 0.9f * s, 1.7f * s, 2.2f * s, 8, foliage)
        cone(b, x, z, 2.3f * s, 1.35f * s, 2.0f * s, 8, foliage)
        cone(b, x, z, 3.5f * s, 0.95f * s, 1.8f * s, 8, foliage)
    }

    private fun cone(b: StaticBatch, cx: Float, cz: Float, baseY: Float, r: Float, h: Float, segs: Int, c: FloatArray) {
        for (k in 0 until segs) {
            val a0 = 2f * PIF * k / segs
            val a1 = 2f * PIF * (k + 1) / segs
            val am = (a0 + a1) * 0.5f
            var nx = h * cos(am)
            var ny = r
            var nz = h * sin(am)
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            nx /= l; ny /= l; nz /= l
            b.triP(
                cx + r * cos(a0), baseY, cz + r * sin(a0),
                cx + r * cos(a1), baseY, cz + r * sin(a1),
                cx, baseY + h, cz,
                nx, ny, nz, c[0], c[1], c[2], 0f
            )
        }
    }

    private fun prism(b: StaticBatch, cx: Float, cz: Float, baseY: Float, r: Float, h: Float, segs: Int, c: FloatArray) {
        for (k in 0 until segs) {
            val a0 = 2f * PIF * k / segs
            val a1 = 2f * PIF * (k + 1) / segs
            val am = (a0 + a1) * 0.5f
            val nx = cos(am)
            val nz = sin(am)
            val x0 = cx + r * cos(a0)
            val z0 = cz + r * sin(a0)
            val x1 = cx + r * cos(a1)
            val z1 = cz + r * sin(a1)
            b.triP(x0, baseY, z0, x1, baseY, z1, x1, baseY + h, z1, nx, 0f, nz, c[0], c[1], c[2], 0f)
            b.triP(x0, baseY, z0, x1, baseY + h, z1, x0, baseY + h, z0, nx, 0f, nz, c[0], c[1], c[2], 0f)
        }
    }
}
