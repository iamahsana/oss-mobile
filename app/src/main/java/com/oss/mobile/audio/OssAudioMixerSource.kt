package com.oss.mobile.audio

import android.media.AudioDeviceInfo
import android.media.projection.MediaProjection
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import java.util.ArrayDeque
import kotlin.math.abs

/**
 * Independent microphone + Android playback capture adapter for RootEncoder.
 * Each source has a bounded FIFO so callback scheduling jitter does not cause
 * otherwise usable PCM frames to be discarded immediately.
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
    private val microphoneFrames = ArrayDeque<PcmAudioFrame>()
    private val internalFrames = ArrayDeque<PcmAudioFrame>()

    private companion object {
        const val MAX_QUEUED_FRAMES = 8
        const val MAX_PAIR_DELTA_US = 150_000L
    }

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
            microphoneFrames.clear()
            internalFrames.clear()
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

        AudioDiagnostics.recordPcm(sourceId, frame)
        val pcm = PcmAudioFrame(
            sourceId = sourceId,
            data = frame.buffer.copyOfRange(frame.offset, end),
            sampleRate = sampleRate,
            channelCount = if (isStereo) 2 else 1,
            timestampUs = frame.timeStamp
        )

        val mixedFrames = synchronized(frameLock) {
            val queue = if (sourceId == "microphone") microphoneFrames else internalFrames
            queue.addLast(pcm)
            while (queue.size > MAX_QUEUED_FRAMES) queue.removeFirst()

            val ready = ArrayList<PcmAudioFrame>()
            while (microphoneFrames.isNotEmpty() && internalFrames.isNotEmpty()) {
                val mic = microphoneFrames.peekFirst()
                val internal = internalFrames.peekFirst()
                val delta = mic.timestampUs - internal.timestampUs

                if (abs(delta) <= MAX_PAIR_DELTA_US) {
                    microphoneFrames.removeFirst()
                    internalFrames.removeFirst()
                    val mixed = mixer.mix(
                        mapOf("microphone" to mic, "internal" to internal),
                        AudioOutputBus.LIVE
                    )
                    if (mixed != null) ready.add(mixed)
                } else if (delta < 0L) {
                    // The microphone frame is too old to pair with this playback frame.
                    microphoneFrames.removeFirst()
                } else {
                    // The playback frame is too old to pair with this microphone frame.
                    internalFrames.removeFirst()
                }
            }
            ready
        }

        for (mixedFrame in mixedFrames) {
            AudioDiagnostics.recordPcm("mixed-output", Frame(mixedFrame.data, 0, mixedFrame.data.size, mixedFrame.timestampUs))
            output?.inputPCMData(
                Frame(
                    mixedFrame.data,
                    0,
                    mixedFrame.data.size,
                    mixedFrame.timestampUs
                )
            )
        }
    }

    override fun stop() {
        output = null
        runCatching { microphoneSource.stop() }
        runCatching { internalSource.stop() }
        synchronized(frameLock) {
            microphoneFrames.clear()
            internalFrames.clear()
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
