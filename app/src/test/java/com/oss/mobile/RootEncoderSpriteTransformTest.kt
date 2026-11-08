package com.oss.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class RootEncoderSpriteTransformTest {
    @Test
    fun normalizedGeometryConvertsToRootEncoderPercentages() {
        val actual = RootEncoderSpriteTransform.from(
            SceneTransform(
                x = 0.125f,
                y = 0.2f,
                width = 0.4f,
                height = 0.3f,
                rotationDegrees = 27f,
                opacity = 0.65f
            )
        )

        assertEquals(12.5f, actual.xPercent, 0.0001f)
        assertEquals(20f, actual.yPercent, 0.0001f)
        assertEquals(40f, actual.widthPercent, 0.0001f)
        assertEquals(30f, actual.heightPercent, 0.0001f)
        assertEquals(27, actual.rotationDegrees)
        assertEquals(0.65f, actual.alpha, 0.0001f)
    }

    @Test
    fun negativePositionAndRotationArePreserved() {
        val actual = RootEncoderSpriteTransform.from(
            SceneTransform(
                x = -0.1f,
                y = 1.1f,
                width = 0.25f,
                height = 0.5f,
                rotationDegrees = -45.4f,
                opacity = 1f
            )
        )

        assertEquals(-10f, actual.xPercent, 0.0001f)
        assertEquals(110f, actual.yPercent, 0.0001f)
        assertEquals(-45, actual.rotationDegrees)
    }

    @Test
    fun fractionalRotationRoundsToNearestDegree() {
        val actual = RootEncoderSpriteTransform.from(
            SceneTransform(rotationDegrees = 12.6f)
        )

        assertEquals(13, actual.rotationDegrees)
    }
}
