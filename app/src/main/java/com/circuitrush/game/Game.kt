package com.circuitrush.game

class HudState(
    val mode: Int,
    val speedKmh: Int,
    val gear: Int,
    val rpm: Float,
    val lap: Int,
    val laps: Int,
    val pos: Int,
    val total: Int,
    val lapTime: Float,
    val bestLap: Float,
    val lastLap: Float,
    val countdown: Int,
    val message: String,
    val wrongWay: Boolean,
    val offRoad: Boolean,
    val carX: FloatArray,
    val carZ: FloatArray,
    val carColor: IntArray,
    val playerIdx: Int,
    val raceState: Int
) {
    companion object {
        val EMPTY = HudState(
            0, 0, 1, 0f, 1, 1, 1, 1, 0f, 0f, 0f, -1, "", false, false,
            FloatArray(0), FloatArray(0), IntArray(0), 0, 0
        )
    }
}

class ResultRow(val name: String, val time: Float, val best: Float, val finished: Boolean, val isPlayer: Boolean)

interface Host {
    fun onLoaded()
    fun onError(msg: String)
    fun onResults(rows: List<ResultRow>, playerPos: Int, playerBest: Float)
    fun onBeep(kind: Int)
}

object Game {
    @Volatile var gas = false
    @Volatile var brake = false
    @Volatile var left = false
    @Volatile var right = false
    @Volatile var tilt = 0f
    @Volatile var useTilt = false
    @Volatile var paused = false
    @Volatile var camMode = 0
    @Volatile var hud: HudState = HudState.EMPTY
    @Volatile var track: Track? = null
    @Volatile var carNames: Array<String> = emptyArray()

    fun fmt(t: Float): String {
        if (t <= 0f) return "--:--.---"
        val m = (t / 60f).toInt()
        val s = t - m * 60f
        return String.format(java.util.Locale.US, "%d:%06.3f", m, s)
    }
}
