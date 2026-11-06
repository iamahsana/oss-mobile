package com.oss.mobile.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AudioMixerCoreTest {

    private fun pcm(source: String, vararg samples: Int, channels: Int = 1): PcmAudioFrame {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, value ->
            bytes[index * 2] = (value and 0xFF).toByte()
            bytes[index * 2 + 1] = (value shr 8).toByte()
        }
        return PcmAudioFrame(source, bytes, 48_000, channels, 1_000L)
    }

    private fun samples(frame: PcmAudioFrame): List<Int> =
        (frame.data.indices step 2).map { index ->
            (((frame.data[index + 1].toInt() shl 8) or
                (frame.data[index].toInt() and 0xFF))).toShort().toInt()
        }

    @Test
    fun channelGainAndMuteAreIndependent() {
        val mixer = AudioMixerCore()
        mixer.setChannel(AudioChannelState("mic", "mic", gain = 0.5f))
        mixer.setChannel(AudioChannelState("game", "game"))
        val frames = mapOf("mic" to pcm("mic", 10_000), "game" to pcm("game", 4_000))

        assertEquals(listOf(9_000), samples(mixer.mix(frames, AudioOutputBus.LIVE)!!))
        mixer.setChannelMuted("mic", true)
        assertEquals(listOf(4_000), samples(mixer.mix(frames, AudioOutputBus.LIVE)!!))
    }

    @Test
    fun liveAndMonitorRoutingAreIndependent() {
        val mixer = AudioMixerCore()
        mixer.setChannel(AudioChannelState("music", "music", toLive = true, toMonitor = false))
        val frames = mapOf("music" to pcm("music", 2_000))
        assertEquals(listOf(2_000), samples(mixer.mix(frames, AudioOutputBus.LIVE)!!))
        assertNull(mixer.mix(frames, AudioOutputBus.MONITOR))
    }

    @Test
    fun masterGainAppliesToSelectedBusOnly() {
        val mixer = AudioMixerCore()
        mixer.setChannel(AudioChannelState("mic", "mic", toLive = true, toMonitor = true))
        mixer.setMasterGain(AudioOutputBus.LIVE, 0.5f)
        val frames = mapOf("mic" to pcm("mic", 8_000))
        assertEquals(listOf(4_000), samples(mixer.mix(frames, AudioOutputBus.LIVE)!!))
        assertEquals(listOf(8_000), samples(mixer.mix(frames, AudioOutputBus.MONITOR)!!))
    }

    @Test
    fun stereoBalanceAttenuatesOppositeSide() {
        val mixer = AudioMixerCore()
        mixer.setChannel(AudioChannelState("stereo", "stereo", balance = 1f))
        val result = mixer.mix(
            mapOf("stereo" to pcm("stereo", 6_000, 6_000, channels = 2)),
            AudioOutputBus.LIVE
        )!!
        assertEquals(listOf(0, 6_000), samples(result))
    }

    @Test
    fun meterReportsNormalizedPeakAndRms() {
        val meter = AudioLevelMeter.from(pcm("mic", 16_384, -16_384))
        assertEquals(0.5f, meter.peak, 0.001f)
        assertEquals(0.5f, meter.rms, 0.001f)
        assertEquals(2, meter.sampleCount)
    }

    @Test
    fun limiterKeepsOutputBelowCeiling() {
        val mixer = AudioMixerCore()
        mixer.setChannel(AudioChannelState("a", "a"))
        mixer.setChannel(AudioChannelState("b", "b"))
        val result = mixer.mix(
            mapOf("a" to pcm("a", 28_000), "b" to pcm("b", 28_000)),
            AudioOutputBus.LIVE
        )
        assertNotNull(result)
        assertEquals(30_000, samples(result!!).single())
    }
}
