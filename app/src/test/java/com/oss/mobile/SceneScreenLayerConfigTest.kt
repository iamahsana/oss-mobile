package com.oss.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class SceneScreenLayerConfigTest {
    @Test
    fun usesStableModuleIdAndDefaultDisplayConfiguration() {
        val config = SceneScreenLayerConfig()
        assertEquals("oss.source.screen.capture", SceneScreenLayerConfig.MODULE_ID)
        assertEquals("default", config.toMap()[SceneScreenLayerConfig.KEY_CAPTURE_DISPLAY])
        assertEquals(config, SceneScreenLayerConfig.from(config.toMap()))
    }

    @Test
    fun acceptsForwardCompatibleConfigurationWithDefaultDisplay() {
        assertEquals(
            SceneScreenLayerConfig(),
            SceneScreenLayerConfig.from(mapOf("future_option" to "ignored"))
        )
    }
}
