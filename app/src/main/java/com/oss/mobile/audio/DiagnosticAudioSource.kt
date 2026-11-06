package com.oss.mobile.audio

import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.sources.audio.AudioSource

/**
 * Pass-through probe used to inspect each selected source before it reaches
 * RootEncoder's encoder. It measures PCM levels without retaining audio data.
 */
class DiagnosticAudioSource(
    private val delegate: AudioSource,
    private val label: String
) : AudioSource() {

    override fun create(
        sampleRate: Int,
        isStereo: Boolean,
        echoCanceler: Boolean,
        noiseSuppressor: Boolean
    ): Boolean {
        val ready = delegate.init(sampleRate, isStereo, echoCanceler, noiseSuppressor)
        AudioDiagnostics.add(
            "Source[$label] create=$ready sampleRate=$sampleRate stereo=$isStereo " +
                "aec=$echoCanceler ns=$noiseSuppressor delegate=${delegate.javaClass.simpleName}"
        )
        return ready
    }

    override fun start(getMicrophoneData: GetMicrophoneData) {
        delegate.start(object : GetMicrophoneData {
            override fun inputPCMData(frame: Frame) {
                AudioDiagnostics.recordPcm(label, frame)
                getMicrophoneData.inputPCMData(frame)
            }
        })
        AudioDiagnostics.add("Source[$label] started")
    }

    override fun stop() {
        delegate.stop()
        AudioDiagnostics.add("Source[$label] stopped")
    }

    override fun isRunning(): Boolean = delegate.isRunning()

    override fun release() {
        delegate.release()
    }
}
