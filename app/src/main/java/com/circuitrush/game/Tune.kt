package com.circuitrush.game

object Tune {
    const val TOP_SPEED = 84f
    const val ENGINE_ACC = 17f
    const val BRAKE = 31f
    const val REVERSE_ACC = 9f
    const val DRAG = 0.00055f
    const val WHEELBASE = 3.4f
    const val GRIP_BASE = 24f
    const val DOWNFORCE = 0.0055f

    val PALETTE_NAMES = arrayOf("Red", "Blue", "Yellow", "Green", "Orange", "White", "Purple", "Cyan")
    val PALETTE = arrayOf(
        floatArrayOf(0.75f, 0.03f, 0.02f),
        floatArrayOf(0.02f, 0.10f, 0.65f),
        floatArrayOf(0.90f, 0.62f, 0.02f),
        floatArrayOf(0.02f, 0.45f, 0.08f),
        floatArrayOf(0.90f, 0.22f, 0.02f),
        floatArrayOf(0.85f, 0.85f, 0.85f),
        floatArrayOf(0.35f, 0.05f, 0.55f),
        floatArrayOf(0.02f, 0.55f, 0.65f)
    )
    val CAR_POWER = floatArrayOf(0.86f, 0.88f, 0.92f, 0.95f, 0.97f, 0.98f, 1.0f, 0.93f, 0.99f)
    val AI_NAMES = arrayOf("Vega", "Rossi", "Keller", "Tanaka", "Moreau", "Silva", "Novak", "Hassan", "Berg", "Ortiz")
    val DIFF_NAMES = arrayOf("Easy", "Medium", "Hard")

    fun steerLimit(v: Float): Float {
        val r = v / 14f
        return 0.5f / (1f + 0.8f * r * r)
    }

    fun paintArgb(i: Int): Int {
        val c = PALETTE[i % PALETTE.size]
        val r = (Math.pow(c[0].toDouble(), 0.4545) * 255.0).toInt().coerceIn(0, 255)
        val g = (Math.pow(c[1].toDouble(), 0.4545) * 255.0).toInt().coerceIn(0, 255)
        val b = (Math.pow(c[2].toDouble(), 0.4545) * 255.0).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
