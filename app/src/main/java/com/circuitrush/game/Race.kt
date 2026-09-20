package com.circuitrush.game

import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class RaceConfig(val car: Int, val color: Int, val laps: Int, val opp: Int, val diff: Int)

class Race(val track: Track, val cfg: RaceConfig, modelCount: Int) {
    val cars = ArrayList<Car>()
    val player: Car
    val laps = cfg.laps
    var state = 0            // 0 countdown, 1 racing, 2 player finished, 3 done
    var countT = 0f
    var time = 0f
    var finishedAt = 0f
    var resultsSent = false
    val profile: FloatArray = track.profile(72f, 21f, 26f)
    private val rnd = Random(System.nanoTime())
    private val tmp = FloatArray(3)

    init {
        val n = 1 + cfg.opp
        val playerSlot = n / 2
        val base = when (cfg.diff) {
            0 -> 0.80f
            1 -> 0.90f
            else -> 0.97f
        }
        var colorCursor = cfg.color + 1
        var modelCursor = cfg.car + 1
        var pl: Car? = null
        for (slot in 0 until n) {
            val isPl = slot == playerSlot
            val c = Car(slot, isPl)
            if (isPl) {
                c.modelIndex = cfg.car
                setPaint(c, cfg.color)
                c.name = "YOU"
                c.skill = 0.85f
                pl = c
            } else {
                c.modelIndex = modelCursor % modelCount
                modelCursor += 2
                var ci = colorCursor % Tune.PALETTE.size
                if (ci == cfg.color) ci = (ci + 1) % Tune.PALETTE.size
                colorCursor++
                setPaint(c, ci)
                c.name = Tune.AI_NAMES[slot % Tune.AI_NAMES.size]
                c.skill = base + rnd.nextFloat() * 0.06f
                c.laneBase = (rnd.nextFloat() - 0.5f) * 3f
                c.reaction = rnd.nextFloat() * 0.5f
            }
            c.power = Tune.CAR_POWER[c.modelIndex % Tune.CAR_POWER.size]
            val row = slot / 2
            val col = slot % 2
            val lat = if (col == 0) -3.2f else 3.2f
            val s = track.s0 - 9f - row * 9.5f - col * 4.5f
            track.place(s, lat, tmp)
            c.x = tmp[0]
            c.z = tmp[1]
            c.yaw = tmp[2]
            cars.add(c)
        }
        player = pl!!
        for (c in cars) progress(c, true)
        rank()
    }

    private fun setPaint(c: Car, ci: Int) {
        val p = Tune.PALETTE[ci % Tune.PALETTE.size]
        c.paint[0] = p[0]; c.paint[1] = p[1]; c.paint[2] = p[2]
        c.paintArgb = Tune.paintArgb(ci)
    }

    fun aiFactor(c: Car): Float {
        if (state >= 2) return 1f
        val gap = c.raceDist - player.raceDist
        return when {
            gap > 150f -> 0.93f
            gap > 60f -> 0.97f
            gap < -200f -> 1.04f
            gap < -80f -> 1.02f
            else -> 1f
        }
    }

    fun update(dt: Float) {
        if (state == 0) {
            countT += dt
            player.rpm = if (Game.gas) 0.8f else 0.25f
            if (countT >= 3f) {
                state = 1
                time = 0f
                for (c in cars) c.lapStart = 0f
            }
            return
        }
        time += dt
        for (c in cars) control(c, dt)
        for (c in cars) Physics.step(c, dt)
        for (i in cars.indices) {
            for (j in i + 1 until cars.size) collide(cars[i], cars[j])
        }
        for (c in cars) {
            progress(c, false)
            walls(c)
        }
        rank()
        if (state == 2) {
            var all = true
            for (c in cars) if (!c.finished) all = false
            if (all || time - finishedAt > 10f) state = 3
        }
    }

    private fun control(c: Car, dt: Float) {
        if (c.isPlayer && !c.autopilot) {
            c.steerCmd = if (Game.useTilt) Game.tilt else ((if (Game.right) 1f else 0f) - (if (Game.left) 1f else 0f))
            c.throttle = if (Game.gas) 1f else 0f
            c.brake = if (Game.brake) 1f else 0f
        } else {
            Ai.drive(c, this, dt)
        }
    }

    private fun progress(c: Car, first: Boolean) {
        val t = track
        c.idx = t.nearest(c.x, c.z, if (first) -1 else c.idx)
        val h = t.ch[c.idx]
        val dx = c.x - t.cx[c.idx]
        val dz = c.z - t.cz[c.idx]
        val sn = sin(h)
        val cs = cos(h)
        c.lat = dx * cs - dz * sn
        val along = dx * sn + dz * cs
        val len = t.length
        var s = c.idx * t.ds + along - t.s0
        s = ((s % len) + len) % len
        if (!first) {
            val prev = c.sRel
            if (prev > 0.75f * len && s < 0.25f * len) {
                c.lap += 1
                onLap(c)
            } else if (prev < 0.25f * len && s > 0.75f * len) {
                if (c.lap > 0) c.lap -= 1
            }
        }
        c.sRel = s
        c.raceDist = (c.lap - 1) * len + s
        c.offRoad = abs(c.lat) > t.half + 0.4f
    }

    private fun onLap(c: Car) {
        if (c.lap >= 2) {
            val lt = time - c.lapStart
            c.lastLap = lt
            if (c.bestLap == 0f || lt < c.bestLap) c.bestLap = lt
        }
        if (c.lap == laps + 1 && !c.finished) {
            c.finished = true
            c.finishTime = time
            if (c.isPlayer) {
                state = 2
                c.autopilot = true
                finishedAt = time
            }
        }
        c.lapStart = time
    }

    private fun walls(c: Car) {
        val t = track
        val lim = t.barrier - 1.15f
        val h = t.ch[c.idx]
        val cs = cos(h)
        val sn = sin(h)
        if (c.lat > lim) {
            val ex = c.lat - lim
            c.x -= cs * ex
            c.z += sn * ex
            val vn = c.vx * cs - c.vz * sn
            if (vn > 0f) {
                c.vx -= 1.3f * vn * cs
                c.vz += 1.3f * vn * sn
                hit(c, vn)
            }
            c.lat = lim
        } else if (c.lat < -lim) {
            val ex = -lim - c.lat
            c.x += cs * ex
            c.z -= sn * ex
            val vn = -(c.vx * cs - c.vz * sn)
            if (vn > 0f) {
                c.vx += 1.3f * vn * cs
                c.vz -= 1.3f * vn * sn
                hit(c, vn)
            }
            c.lat = -lim
        }
    }

    private fun hit(c: Car, vn: Float) {
        val k = (1f - 0.03f * vn).coerceAtLeast(0.6f)
        c.vx *= k
        c.vz *= k
        c.impact = max(c.impact, vn)
    }

    private fun collide(a: Car, b: Car) {
        val dxc = b.x - a.x
        val dzc = b.z - a.z
        if (dxc * dxc + dzc * dzc > 49f) return
        val afx = sin(a.yaw)
        val afz = cos(a.yaw)
        val bfx = sin(b.yaw)
        val bfz = cos(b.yaw)
        for (i in -1..1) {
            for (j in -1..1) {
                val ax = a.x + afx * i * 1.9f
                val az = a.z + afz * i * 1.9f
                val bx = b.x + bfx * j * 1.9f
                val bz = b.z + bfz * j * 1.9f
                val dx = bx - ax
                val dz = bz - az
                val d2 = dx * dx + dz * dz
                if (d2 < 4f && d2 > 1e-6f) {
                    val d = sqrt(d2)
                    val nx = dx / d
                    val nz = dz / d
                    val pen = 2f - d
                    a.x -= nx * pen * 0.5f
                    a.z -= nz * pen * 0.5f
                    b.x += nx * pen * 0.5f
                    b.z += nz * pen * 0.5f
                    val vn = (a.vx - b.vx) * nx + (a.vz - b.vz) * nz
                    if (vn > 0f) {
                        val jj = vn * 0.6f
                        a.vx -= nx * jj
                        a.vz -= nz * jj
                        b.vx += nx * jj
                        b.vz += nz * jj
                        a.impact = max(a.impact, vn)
                        b.impact = max(b.impact, vn)
                    }
                }
            }
        }
    }

    private fun rank() {
        val order = cars.sortedWith(Comparator { a, b ->
            if (a.finished && b.finished) a.finishTime.compareTo(b.finishTime)
            else if (a.finished) -1
            else if (b.finished) 1
            else b.raceDist.compareTo(a.raceDist)
        })
        for (i in order.indices) order[i].position = i + 1
    }

    fun results(): List<ResultRow> {
        val sorted = cars.sortedBy { it.position }
        return sorted.map { ResultRow(it.name, if (it.finished) it.finishTime else 0f, it.bestLap, it.finished, it.isPlayer) }
    }
}
