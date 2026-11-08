package com.oss.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StaticImageLayerConfigTest {

    @Test
    fun parsesDefaultsAndExplicitTransform() {
        val config = StaticImageLayerConfig.from(
            mapOf(
                "resource_id" to "123",
                "x_percent" to "12.5",
                "y_percent" to "8",
                "width_percent" to "30",
                "opacity" to "0.75"
            )
        )
        assertEquals(123, config.resourceId)
        assertEquals(12.5f, config.xPercent)
        assertEquals(8f, config.yPercent)
        assertEquals(30f, config.widthPercent)
        assertEquals(0.75f, config.opacity)
    }

    @Test
    fun appliesSafeDefaults() {
        val config = StaticImageLayerConfig.from(mapOf("resource_id" to "7"))
        assertEquals(5f, config.xPercent)
        assertEquals(5f, config.yPercent)
        assertEquals(24f, config.widthPercent)
        assertEquals(1f, config.opacity)
    }

    @Test
    fun parsesImageFlipOptionsAndKeepsDefaultsOff() {
        val defaults = StaticImageLayerConfig.from(mapOf("resource_id" to "9"))
        assertEquals(false, defaults.flipHorizontal)
        assertEquals(false, defaults.flipVertical)

        val flipped = StaticImageLayerConfig.from(
            mapOf(
                "resource_id" to "9",
                "flip_horizontal" to "true",
                "flip_vertical" to "true"
            )
        )
        assertEquals(true, flipped.flipHorizontal)
        assertEquals(true, flipped.flipVertical)
    }

    @Test
    fun rejectsMissingResourceId() {
        assertThrows(IllegalArgumentException::class.java) {
            StaticImageLayerConfig.from(emptyMap())
        }
    }

    @Test
    fun rejectsOutOfRangeOpacity() {
        assertThrows(IllegalArgumentException::class.java) {
            StaticImageLayerConfig.from(mapOf("resource_id" to "7", "opacity" to "1.5"))
        }
    }
}
