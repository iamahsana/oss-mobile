package com.oss.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeLayerConfigTest {
    @Test
    fun serializesAllShapeProperties() {
        val config = ShapeLayerConfig(
            shape = ShapeLayerConfig.Shape.CIRCLE,
            fillEnabled = false,
            strokeWidth = 14f,
            cornerRadius = 42f
        )
        val restored = ShapeLayerConfig.from(config.toMap())
        assertEquals(config.shape, restored.shape)
        assertEquals(config.fillEnabled, restored.fillEnabled)
        assertEquals(config.strokeWidth, restored.strokeWidth)
        assertEquals(config.cornerRadius, restored.cornerRadius)
    }

    @Test
    fun defaultsToRectangleAndClampsMalformedNumericSettings() {
        val config = ShapeLayerConfig.from(
            mapOf(
                ShapeLayerConfig.KEY_STROKE_WIDTH to "99",
                ShapeLayerConfig.KEY_CORNER_RADIUS to "-5"
            )
        )
        assertEquals(ShapeLayerConfig.Shape.RECTANGLE, config.shape)
        assertEquals(48f, config.strokeWidth)
        assertEquals(0f, config.cornerRadius)
        assertTrue(config.fillEnabled)
    }
}
