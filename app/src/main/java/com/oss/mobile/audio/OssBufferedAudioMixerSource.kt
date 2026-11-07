package com.oss.mobile.audio

import android.media.AudioDeviceInfo
import android.media.projection.MediaProjection
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.BufferAudioSource

/**
 * Experimental encoder-facing buffer bridge.
 *
 * The existing OSS mixer remains unchanged and continues to capture the
 * microphone and internal playback independently. Its mixed PCM frames are
 * submitted to RootEncoder's BufferAudioSource, which owns output pacing and
 * the bounded jitter buffer before forwarding frames to the encoder.
 *
 * This adapter is intentionally isolated for device testing before adoption.
 */
class OssBufferedAudioMixerSource(
    mediaProjection: MediaProjection,
    private val bufferCapacityMs: Int = 1000,
    private val latencyMs: Int = 100
) : AudioSource() {

    private val mixerSource = OssAudioMixerSource(mediaProjection)
    private val bufferSource = BufferAudioSource(bufferCapacityMs, latencyMs)

    override fun create(
        sampleRate: Int,
        isStereo: Boolean,
        echoCanceler: Boolean,
        noiseSuppressor: Boolean
    ): Boolean {
        val mixerReady = mixerSource.init(
            sampleRate,
            isStereo,
            echoCanceler,
            noiseSuppressor
        )
        val bufferReady = bufferSource.init(
            sampleRate,
            isStereo,
            false,
            false
        )
        return mixerReady && bufferReady
    }

    override fun start(getMicrophoneData: GetMicrophoneData) {
        bufferSource.start(getMicrophoneData)
        try {
            mixerSource.start(object : GetMicrophoneData {
                override fun inputPCMData(frame: Frame) {
                    bufferSource.setBuffer(
                        frame.buffer,
                        frame.timeStamp,
                        frame.offset,
                        frame.size
                    )
                }
            })
        } catch (error: Exception) {
            runCatching { bufferSource.stop() }
            throw error
        }
    }

    override fun stop() {
        runCatching { mixerSource.stop() }
        runCatching { bufferSource.stop() }
    }

    override fun isRunning(): Boolean =
        mixerSource.isRunning() && bufferSource.isRunning()

    override fun release() {
        mixerSource.release()
        bufferSource.release()
    }

    fun setChannelGain(channelId: String, gain: Float): Boolean =
        mixerSource.setChannelGain(channelId, gain)

    fun setChannelMuted(channelId: String, muted: Boolean): Boolean =
        mixerSource.setChannelMuted(channelId, muted)

    fun setChannelEnabled(channelId: String, enabled: Boolean): Boolean =
        mixerSource.setChannelEnabled(channelId, enabled)

    fun setChannelBalance(channelId: String, balance: Float): Boolean =
        mixerSource.setChannelBalance(channelId, balance)

    fun setChannelRouting(
        channelId: String,
        toLive: Boolean,
        toMonitor: Boolean
    ): Boolean = mixerSource.setChannelRouting(channelId, toLive, toMonitor)

    fun setMasterGain(bus: AudioOutputBus, gain: Float) =
        mixerSource.setMasterGain(bus, gain)

    fun channelMeters(): Map<String, AudioLevelMeter> =
        mixerSource.channelMeters()

    fun channelState(channelId: String): AudioChannelState? =
        mixerSource.channelState(channelId)

    fun masterGain(bus: AudioOutputBus): Float =
        mixerSource.masterGain(bus)

    fun registerChannel(channel: AudioChannelState) =
        mixerSource.registerChannel(channel)

    fun removeChannel(channelId: String) =
        mixerSource.removeChannel(channelId)

    fun channels(): List<AudioChannelState> =
        mixerSource.channels()

    fun setPreferredMicrophoneDevice(deviceInfo: AudioDeviceInfo?): Boolean =
        mixerSource.setPreferredMicrophoneDevice(deviceInfo)
}
