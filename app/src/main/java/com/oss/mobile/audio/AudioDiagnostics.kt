package com.oss.mobile.audio

import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.pedro.encoder.Frame
import java.util.ArrayDeque
import kotlin.math.sqrt

/**
 * Small in-memory diagnostic journal for audio capture.
 * It stores routing metadata and PCM level statistics only; never stores audio.
 */
object AudioDiagnostics {
    private const val TAG = "OSS-AudioDiag"
    private const val MAX_EVENTS = 120
    private val lock = Any()
    private val events = ArrayDeque<String>()
    private data class PcmStats(
        var startedAtMs: Long = 0L,
        var sumSquares: Double = 0.0,
        var sampleCount: Long = 0L,
        var peak: Int = 0,
        var frames: Int = 0
    )
    private val pcmStats = mutableMapOf<String, PcmStats>()

    fun clear() {
        synchronized(lock) {
            events.clear()
            pcmStats.clear()
        }
        add("Diagnostic session started")
    }

    fun add(message: String) {
        val line = "${SystemClock.elapsedRealtime()}ms $message"
        synchronized(lock) {
            events.addLast(line)
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
        Log.i(TAG, message)
    }

    fun recordPcm(label: String, frame: Frame) {
        val end = frame.offset + frame.size
        if (frame.offset < 0 || frame.size < 2 || end > frame.buffer.size) return

        var sumSquares = 0.0
        var peak = 0
        var samples = 0
        var offset = frame.offset
        while (offset + 1 < end) {
            val low = frame.buffer[offset].toInt() and 0xFF
            val high = frame.buffer[offset + 1].toInt()
            val sample = (high shl 8) or low
            val absolute = kotlin.math.abs(sample)
            if (absolute > peak) peak = absolute
            sumSquares += sample.toDouble() * sample.toDouble()
            samples++
            offset += 2
        }

        val now = SystemClock.elapsedRealtime()
        var report: String? = null
        synchronized(lock) {
            val stats = pcmStats.getOrPut(label) { PcmStats(startedAtMs = now) }
            stats.sumSquares += sumSquares
            stats.sampleCount += samples
            stats.peak = maxOf(stats.peak, peak)
            stats.frames++
            if (now - stats.startedAtMs >= 1000L) {
                val rms = if (stats.sampleCount > 0) sqrt(stats.sumSquares / stats.sampleCount) else 0.0
                report = "$label PCM: rms=${"%.1f".format(java.util.Locale.US, rms)}, peak=${stats.peak}, frames=${stats.frames}/s"
                stats.startedAtMs = now
                stats.sumSquares = 0.0
                stats.sampleCount = 0L
                stats.peak = 0
                stats.frames = 0
            }
        }
        report?.let { add(it) }
    }

    fun recordingConfigurations(configurations: List<AudioRecordingConfiguration>?) {
        if (configurations.isNullOrEmpty()) {
            add("Active recording configurations: none")
            return
        }
        add("Active recording configurations: ${configurations.size}")
        configurations.forEachIndexed { index, config ->
            val client = config.clientFormat
            val device = config.audioDevice
            val packageName = if (Build.VERSION.SDK_INT >= 29) config.clientPackageName else "unknown"
            val silenced = if (Build.VERSION.SDK_INT >= 29) config.isClientSilenced else false
            add(
                "REC[$index] app=$packageName source=${config.clientAudioSource} silenced=$silenced " +
                    "client=${client.sampleRate}Hz/${client.channelCount}ch device=${device?.type}:${device?.productName}"
            )
        }
    }

    fun snapshot(): String = synchronized(lock) {
        if (events.isEmpty()) {
            "Belum ada data diagnostik. Mulai tes audio terlebih dahulu."
        } else {
            buildString {
                appendLine("OSS AUDIO DIAGNOSTICS")
                appendLine("Tidak merekam atau menyimpan suara; hanya metadata dan level PCM.")
                events.forEach { appendLine(it) }
            }
        }
    }
}
