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
    fun parsesCropZoomAndMaskWithSafeDefaults() {
        val defaults = StaticImageLayerConfig.from(mapOf("resource_id" to "9"))
        assertEquals(1f, defaults.cropZoom)
        assertEquals(StaticImageLayerConfig.MASK_RECTANGLE, defaults.maskShape)

        val edited = StaticImageLayerConfig.from(
            mapOf(
                "resource_id" to "9",
                "crop_zoom" to "2.25",
                "mask_shape" to StaticImageLayerConfig.MASK_CIRCLE
            )
        )
        assertEquals(2.25f, edited.cropZoom)
        assertEquals(StaticImageLayerConfig.MASK_CIRCLE, edited.maskShape)
    }

    @Test
    fun rejectsInvalidCropZoomAndMaskShape() {
        assertThrows(IllegalArgumentException::class.java) {
            StaticImageLayerConfig.from(mapOf("resource_id" to "9", "crop_zoom" to "3.5"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StaticImageLayerConfig(
                resourceId = 9,
                maskShape = "triangle"
            )
        }
    }

    @Test
    fun parsesCropPositionAndBorderOptions() {
        val config = StaticImageLayerConfig.from(
            mapOf(
                "resource_id" to "9",
                "crop_offset_x" to "0.5",
                "crop_offset_y" to "-0.25",
                "border_width" to "0.04",
                "border_color" to "#00E676"
            )
        )
        assertEquals(0.5f, config.cropOffsetX)
        assertEquals(-0.25f, config.cropOffsetY)
        assertEquals(0.04f, config.borderWidth)
        assertEquals("#00E676", config.borderColor)
    }

    @Test
    fun rejectsInvalidCropPositionAndBorder() {
        assertThrows(IllegalArgumentException::class.java) {
            StaticImageLayerConfig(resourceId = 9, cropOffsetX = 1.5f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StaticImageLayerConfig(resourceId = 9, borderWidth = 0.1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StaticImageLayerConfig(resourceId = 9, borderColor = "red")
        }
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
