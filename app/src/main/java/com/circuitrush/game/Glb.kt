package com.circuitrush.game

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

class GlbMaterial(
    val name: String,
    val r: Float,
    val g: Float,
    val b: Float,
    val roughness: Float,
    val metallic: Float
) {
    val shine: Float = ((1f - roughness) * 0.6f + metallic * 0.5f).coerceIn(0f, 1f)
}

/** data = interleaved position(3) + normal(3) per vertex, non-indexed triangles. */
class GlbPrim(val data: FloatArray, val vertexCount: Int, val material: Int)

class GlbNode(
    val name: String,
    val children: IntArray,
    val local: FloatArray,
    val tr: FloatArray,
    val mesh: Int
)

class GlbModel(
    val nodes: Array<GlbNode>,
    val meshes: Array<Array<GlbPrim>>,
    val materials: Array<GlbMaterial>,
    val roots: IntArray
)

object GlbLoader {
    fun parse(bytes: ByteArray): GlbModel {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.getInt(0) != 0x46546C67) throw IllegalArgumentException("not a GLB file")
        var off = 12
        var jsonText: String? = null
        var binOff = 0
        var binLen = 0
        while (off + 8 <= bytes.size) {
            val len = bb.getInt(off)
            val type = bb.getInt(off + 4)
            val start = off + 8
            if (type == 0x4E4F534A) {
                jsonText = String(bytes, start, len, Charsets.UTF_8)
            } else if (type == 0x004E4942) {
                binOff = start
                binLen = len
            }
            off = start + len
        }
        val js = JSONObject(jsonText ?: throw IllegalArgumentException("GLB has no JSON chunk"))
        val bin = ByteBuffer.wrap(bytes, binOff, binLen).slice().order(ByteOrder.LITTLE_ENDIAN)

        // materials
        val mats = ArrayList<GlbMaterial>()
        val ma = js.optJSONArray("materials")
        if (ma != null) {
            for (i in 0 until ma.length()) {
                val mo = ma.getJSONObject(i)
                val pbr = mo.optJSONObject("pbrMetallicRoughness")
                var r = 0.8f
                var g = 0.8f
                var b = 0.8f
                var rough = 0.6f
                var metal = 0f
                if (pbr != null) {
                    val bc = pbr.optJSONArray("baseColorFactor")
                    if (bc != null && bc.length() >= 3) {
                        r = bc.getDouble(0).toFloat()
                        g = bc.getDouble(1).toFloat()
                        b = bc.getDouble(2).toFloat()
                    }
                    rough = pbr.optDouble("roughnessFactor", 0.6).toFloat()
                    metal = pbr.optDouble("metallicFactor", 0.0).toFloat()
                }
                mats.add(GlbMaterial(mo.optString("name", "mat$i"), r, g, b, rough, metal))
            }
        }
        if (mats.isEmpty()) mats.add(GlbMaterial("default", 0.8f, 0.8f, 0.8f, 0.6f, 0f))

        // meshes
        val meshes = ArrayList<Array<GlbPrim>>()
        val meshArr = js.optJSONArray("meshes")
        if (meshArr != null) {
            for (mi in 0 until meshArr.length()) {
                val prims = meshArr.getJSONObject(mi).getJSONArray("primitives")
                val list = ArrayList<GlbPrim>()
                for (pi in 0 until prims.length()) {
                    val p = prims.getJSONObject(pi)
                    if (p.optInt("mode", 4) != 4) continue
                    val at = p.getJSONObject("attributes")
                    val pos = readAccessor(js, bin, at.getInt("POSITION"))
                    val nor: FloatArray? = if (at.has("NORMAL")) readAccessor(js, bin, at.getInt("NORMAL")) else null
                    val idx: IntArray? = if (p.has("indices")) readIndices(js, bin, p.getInt("indices")) else null
                    val vc = if (idx != null) idx.size else pos.size / 3
                    val data = FloatArray(vc * 6)
                    for (v in 0 until vc) {
                        val src = if (idx != null) idx[v] else v
                        data[v * 6] = pos[src * 3]
                        data[v * 6 + 1] = pos[src * 3 + 1]
                        data[v * 6 + 2] = pos[src * 3 + 2]
                        if (nor != null) {
                            data[v * 6 + 3] = nor[src * 3]
                            data[v * 6 + 4] = nor[src * 3 + 1]
                            data[v * 6 + 5] = nor[src * 3 + 2]
                        }
                    }
                    if (nor == null) computeFlatNormals(data, vc)
                    val mIdx = p.optInt("material", 0).coerceIn(0, mats.size - 1)
                    list.add(GlbPrim(data, vc, mIdx))
                }
                meshes.add(list.toTypedArray())
            }
        }

        // nodes
        val nodeArr = js.getJSONArray("nodes")
        val nodes = Array(nodeArr.length()) { i ->
            val o = nodeArr.getJSONObject(i)
            val ch = o.optJSONArray("children")
            val children = IntArray(ch?.length() ?: 0) { k -> ch!!.getInt(k) }
            val t = floatArrayOf(0f, 0f, 0f)
            val local: FloatArray
            if (o.has("matrix")) {
                val ma2 = o.getJSONArray("matrix")
                local = FloatArray(16) { k -> ma2.getDouble(k).toFloat() }
                t[0] = local[12]; t[1] = local[13]; t[2] = local[14]
            } else {
                val ta = o.optJSONArray("translation")
                if (ta != null) {
                    t[0] = ta.getDouble(0).toFloat(); t[1] = ta.getDouble(1).toFloat(); t[2] = ta.getDouble(2).toFloat()
                }
                val q = floatArrayOf(0f, 0f, 0f, 1f)
                val qa = o.optJSONArray("rotation")
                if (qa != null) {
                    for (k in 0 until 4) q[k] = qa.getDouble(k).toFloat()
                }
                val s = floatArrayOf(1f, 1f, 1f)
                val sa = o.optJSONArray("scale")
                if (sa != null) {
                    for (k in 0 until 3) s[k] = sa.getDouble(k).toFloat()
                }
                local = Mat.fromTRS(t, q, s)
            }
            GlbNode(o.optString("name", "node$i"), children, local, t, o.optInt("mesh", -1))
        }

        // scene roots
        val scenes = js.optJSONArray("scenes")
        val roots: IntArray
        if (scenes != null && scenes.length() > 0) {
            val sc = scenes.getJSONObject(js.optInt("scene", 0).coerceIn(0, scenes.length() - 1))
            val na = sc.optJSONArray("nodes")
            roots = IntArray(na?.length() ?: 0) { k -> na!!.getInt(k) }
        } else {
            roots = intArrayOf(0)
        }
        return GlbModel(nodes, meshes.toTypedArray(), mats.toTypedArray(), roots)
    }

    private fun computeFlatNormals(d: FloatArray, vc: Int) {
        var i = 0
        while (i + 2 < vc) {
            val ax = d[(i + 1) * 6] - d[i * 6]
            val ay = d[(i + 1) * 6 + 1] - d[i * 6 + 1]
            val az = d[(i + 1) * 6 + 2] - d[i * 6 + 2]
            val bx = d[(i + 2) * 6] - d[i * 6]
            val by = d[(i + 2) * 6 + 1] - d[i * 6 + 1]
            val bz = d[(i + 2) * 6 + 2] - d[i * 6 + 2]
            var nx = ay * bz - az * by
            var ny = az * bx - ax * bz
            var nz = ax * by - ay * bx
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            if (l > 1e-9f) { nx /= l; ny /= l; nz /= l }
            for (k in 0 until 3) {
                d[(i + k) * 6 + 3] = nx
                d[(i + k) * 6 + 4] = ny
                d[(i + k) * 6 + 5] = nz
            }
            i += 3
        }
    }

    private fun readAccessor(js: JSONObject, bin: ByteBuffer, idx: Int): FloatArray {
        val acc = js.getJSONArray("accessors").getJSONObject(idx)
        val bv = js.getJSONArray("bufferViews").getJSONObject(acc.getInt("bufferView"))
        val count = acc.getInt("count")
        val comps = when (acc.getString("type")) {
            "SCALAR" -> 1
            "VEC2" -> 2
            "VEC3" -> 3
            "VEC4" -> 4
            else -> 1
        }
        val ct = acc.getInt("componentType")
        val normalized = acc.optBoolean("normalized", false)
        val csize = when (ct) {
            5120, 5121 -> 1
            5122, 5123 -> 2
            else -> 4
        }
        val stride = if (bv.has("byteStride")) bv.getInt("byteStride") else comps * csize
        val base = bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
        val out = FloatArray(count * comps)
        for (i in 0 until count) {
            for (c in 0 until comps) {
                val p = base + i * stride + c * csize
                val v: Float = when (ct) {
                    5126 -> bin.getFloat(p)
                    5122 -> {
                        val s = bin.getShort(p).toInt()
                        if (normalized) max(s / 32767f, -1f) else s.toFloat()
                    }
                    5123 -> {
                        val s = bin.getShort(p).toInt() and 0xFFFF
                        if (normalized) s / 65535f else s.toFloat()
                    }
                    5120 -> {
                        val s = bin.get(p).toInt()
                        if (normalized) max(s / 127f, -1f) else s.toFloat()
                    }
                    5121 -> {
                        val s = bin.get(p).toInt() and 0xFF
                        if (normalized) s / 255f else s.toFloat()
                    }
                    else -> 0f
                }
                out[i * comps + c] = v
            }
        }
        return out
    }

    private fun readIndices(js: JSONObject, bin: ByteBuffer, idx: Int): IntArray {
        val acc = js.getJSONArray("accessors").getJSONObject(idx)
        val bv = js.getJSONArray("bufferViews").getJSONObject(acc.getInt("bufferView"))
        val count = acc.getInt("count")
        val ct = acc.getInt("componentType")
        val csize = when (ct) {
            5121 -> 1
            5123 -> 2
            else -> 4
        }
        val stride = if (bv.has("byteStride")) bv.getInt("byteStride") else csize
        val base = bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
        return IntArray(count) { i ->
            val p = base + i * stride
            when (ct) {
                5121 -> bin.get(p).toInt() and 0xFF
                5123 -> bin.getShort(p).toInt() and 0xFFFF
                else -> bin.getInt(p)
            }
        }
    }
}
