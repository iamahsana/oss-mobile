package com.oss.mobile.audio

/**
 * PCM frame normalized to signed 16-bit little-endian samples.
 * Timestamps use microseconds, matching the stream encoder's audio timeline.
 */
data class PcmAudioFrame(
    val sourceId: String,
    val data: ByteArray,
    val sampleRate: Int,
    val channelCount: Int,
    val timestampUs: Long
) {
    init {
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channelCount == 1 || channelCount == 2) { "Only mono and stereo PCM are supported" }
        require(data.size % (channelCount * 2) == 0) { "PCM data must contain complete 16-bit samples" }
    }
}

enum class AudioOutputBus {
    LIVE,
    MONITOR
}

/** Routing and gain state for one independently captured source. */
data class AudioChannelState(
    val channelId: String,
    val sourceId: String,
    val enabled: Boolean = true,
    val muted: Boolean = false,
    val gain: Float = 1f,
    val toLive: Boolean = true,
    val toMonitor: Boolean = false
) {
    init {
        require(channelId.isNotBlank()) { "channelId must not be blank" }
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        require(gain.isFinite() && gain in 0f..2f) { "gain must be between 0.0 and 2.0" }
    }

    fun routesTo(bus: AudioOutputBus): Boolean = when (bus) {
        AudioOutputBus.LIVE -> toLive
        AudioOutputBus.MONITOR -> toMonitor
    }
}

/**
 * Core PCM mixer. It does not capture audio or own an encoder; capture adapters
 * provide normalized frames and the streaming adapter forwards the LIVE result.
 *
 * Calls are synchronized so a routing/gain update cannot partially affect a frame.
 */
class AudioMixerCore {
    private val channels = LinkedHashMap<String, AudioChannelState>()

    private companion object {
        // Leave headroom below signed PCM16 full scale to prevent hard clipping.
        const val LIMITER_CEILING = 30_000
    }

    @Synchronized
    fun setChannel(channel: AudioChannelState) {
        channels[channel.channelId] = channel
    }

    @Synchronized
    fun removeChannel(channelId: String) {
        channels.remove(channelId)
    }

    @Synchronized
    fun channels(): List<AudioChannelState> = channels.values.toList()

    @Synchronized
    fun mix(
        framesBySource: Map<String, PcmAudioFrame>,
        bus: AudioOutputBus
    ): PcmAudioFrame? {
        val routed = channels.values
            .asSequence()
            .filter { it.enabled && !it.muted && it.routesTo(bus) }
            .mapNotNull { channel ->
                framesBySource[channel.sourceId]?.let { channel to it }
            }
            .toList()

        if (routed.isEmpty()) return null

        val reference = routed.first().second
        val compatible = routed.filter { (_, frame) ->
            frame.sampleRate == reference.sampleRate &&
                frame.channelCount == reference.channelCount
        }
        if (compatible.isEmpty()) return null

        // Mix only the common complete sample range; capture adapters are expected
        // to align frame boundaries before passing frames to this core.
        val byteCount = compatible.minOf { (_, frame) -> frame.data.size }
        val alignedByteCount = byteCount - (byteCount % (reference.channelCount * 2))
        if (alignedByteCount <= 0) return null

        val sampleCount = alignedByteCount / 2
        val accumulator = IntArray(sampleCount)
        var outputTimestampUs = reference.timestampUs

        for ((channel, frame) in compatible) {
            outputTimestampUs = maxOf(outputTimestampUs, frame.timestampUs)
            val gain = channel.gain
            var offset = 0
            var sampleIndex = 0
            while (sampleIndex < sampleCount) {
                val low = frame.data[offset].toInt() and 0xFF
                val high = frame.data[offset + 1].toInt()
                val sample = (high shl 8) or low
                accumulator[sampleIndex] += (sample * gain).toInt()
                offset += 2
                sampleIndex++
            }
        }

        // Frame-linked peak limiter: if the summed frame exceeds the ceiling,
        // attenuate the whole frame proportionally. This preserves the balance
        // between channels and avoids the hard saturation seen in PCM diagnostics.
        var framePeak = 0
        for (sample in accumulator) {
            framePeak = maxOf(framePeak, kotlin.math.abs(sample.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        val limiterGain = if (framePeak > LIMITER_CEILING) {
            LIMITER_CEILING.toFloat() / framePeak.toFloat()
        } else {
            1f
        }

        val mixed = ByteArray(alignedByteCount)
        for (i in 0 until sampleCount) {
            val sample = (accumulator[i] * limiterGain)
                .toInt()
                .coerceIn(-LIMITER_CEILING, LIMITER_CEILING)
            mixed[i * 2] = (sample and 0xFF).toByte()
            mixed[i * 2 + 1] = (sample shr 8).toByte()
        }

        return PcmAudioFrame(
            sourceId = "oss-mix-${bus.name.lowercase()}",
            data = mixed,
            sampleRate = reference.sampleRate,
            channelCount = reference.channelCount,
            timestampUs = outputTimestampUs
        )
    }
}

/** Optional processing contract; the core mixer remains usable without plugins. */
fun interface AudioFrameProcessor {
    fun process(frame: PcmAudioFrame): PcmAudioFrame
}
