package com.circuitrush.game

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.max
import kotlin.math.sin

class EngineSound {
    @Volatile var rpm = 0.12f
    @Volatile var load = 0f
    @Volatile var enabled = true
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            try {
                val sr = 22050
                val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(max(minBuf, 2048) * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.play()
                val buf = ShortArray(512)
                var phase = 0.0
                var freq = 60.0
                var vol = 0.0
                var noise = 0.0
                val twoPi = 2.0 * Math.PI
                while (running) {
                    val targetF = 42.0 + rpm * 240.0
                    val targetV = if (enabled) 0.10 + 0.55 * load else 0.0
                    for (i in buf.indices) {
                        freq += (targetF - freq) * 0.0009
                        vol += (targetV - vol) * 0.0012
                        phase += twoPi * freq / sr
                        if (phase > twoPi) phase -= twoPi
                        noise = noise * 0.9 + (Math.random() - 0.5) * 0.3
                        val s = sin(phase) * 0.50 + sin(phase * 2.0) * 0.30 + sin(phase * 3.0) * 0.16 +
                            sin(phase * 4.5) * 0.08 + noise * (0.3 + 0.7 * load)
                        buf[i] = (s * vol * 9000.0).toInt().coerceIn(-32000, 32000).toShort()
                    }
                    track.write(buf, 0, buf.size)
                }
                track.stop()
                track.release()
            } catch (t: Throwable) {
                running = false
            }
        }
        t.isDaemon = true
        t.start()
        thread = t
    }

    fun stop() {
        running = false
        thread = null
    }
}
