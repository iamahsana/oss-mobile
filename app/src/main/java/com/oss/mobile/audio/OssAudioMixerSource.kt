package com.oss.mobile.audio

import android.media.AudioDeviceInfo
import android.media.projection.MediaProjection
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource

/**
 * Independent microphone + Android playback capture adapter for RootEncoder.
 *
 * The two capture sources keep their own AudioRecord pipelines. Only their
 * normalized PCM frames meet in AudioMixerCore; neither source writes directly
 * to the encoder.
 */
class OssAudioMixerSource(
    mediaProjection: MediaProjection
) : AudioSource() {

    private val microphoneSource = MicrophoneSource()
    private val internalSource = InternalAudioSource(mediaProjection)
    private val mixer = AudioMixerCore()

    @Volatile
    private var output: GetMicrophoneData? = null

    private val frameLock = Any()
    private var latestMicrophoneFrame: PcmAudioFrame? = null
    private var latestInternalFrame: PcmAudioFrame? = null

    override fun create(
        sampleRate: Int,
        isStereo: Boolean,
        echoCanceler: Boolean,
        noiseSuppressor: Boolean
    ): Boolean {
        val microphoneReady = microphoneSource.init(
            sampleRate, isStereo, echoCanceler, noiseSuppressor
        )
        val internalReady = internalSource.init(
            sampleRate, isStereo, false, false
        )

        mixer.setChannel(
            AudioChannelState(
                channelId = "microphone",
                sourceId = "microphone",
                toLive = true,
                toMonitor = false
            )
        )
        mixer.setChannel(
            AudioChannelState(
                channelId = "internal",
                sourceId = "internal",
                toLive = true,
                toMonitor = false
            )
        )
        return microphoneReady && internalReady
    }

    override fun start(getMicrophoneData: GetMicrophoneData) {
        output = getMicrophoneData
        synchronized(frameLock) {
            latestMicrophoneFrame = null
            latestInternalFrame = null
        }

        try {
            microphoneSource.start(object : GetMicrophoneData {
                override fun inputPCMData(frame: Frame) {
                    acceptFrame("microphone", frame)
                }
            })
            internalSource.start(object : GetMicrophoneData {
                override fun inputPCMData(frame: Frame) {
                    acceptFrame("internal", frame)
                }
            })
        } catch (error: Exception) {
            output = null
            runCatching { microphoneSource.stop() }
            runCatching { internalSource.stop() }
            throw error
        }
    }

    private fun acceptFrame(sourceId: String, frame: Frame) {
        val end = frame.offset + frame.size
        if (frame.offset < 0 || frame.size <= 0 || end > frame.buffer.size) return

        val pcm = PcmAudioFrame(
            sourceId = sourceId,
            data = frame.buffer.copyOfRange(frame.offset, end),
            sampleRate = sampleRate,
            channelCount = if (isStereo) 2 else 1,
            timestampUs = frame.timeStamp
        )

        val mixedFrame = synchronized(frameLock) {
            if (sourceId == "microphone") {
                latestMicrophoneFrame = pcm
            } else {
                latestInternalFrame = pcm
            }

            val mic = latestMicrophoneFrame
            val internal = latestInternalFrame
            if (mic == null || internal == null) {
                null
            } else if (kotlin.math.abs(mic.timestampUs - internal.timestampUs) > 100_000L) {
                // Do not combine frames from substantially different capture times.
                if (mic.timestampUs < internal.timestampUs) latestMicrophoneFrame = null
                else latestInternalFrame = null
                null
            } else {
                val result = mixer.mix(
                    mapOf("microphone" to mic, "internal" to internal),
                    AudioOutputBus.LIVE
                )
                latestMicrophoneFrame = null
                latestInternalFrame = null
                result
            }
        } ?: return

        output?.inputPCMData(
            Frame(
                mixedFrame.data,
                0,
                mixedFrame.data.size,
                mixedFrame.timestampUs
            )
        )
    }

    override fun stop() {
        output = null
        runCatching { microphoneSource.stop() }
        runCatching { internalSource.stop() }
        synchronized(frameLock) {
            latestMicrophoneFrame = null
            latestInternalFrame = null
        }
    }

    override fun isRunning(): Boolean =
        microphoneSource.isRunning() && internalSource.isRunning()

    override fun release() {
        microphoneSource.release()
        internalSource.release()
    }

    fun setPreferredMicrophoneDevice(deviceInfo: AudioDeviceInfo?): Boolean =
        microphoneSource.setPreferredDevice(deviceInfo)
}
