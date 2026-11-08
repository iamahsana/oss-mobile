package com.oss.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class SceneTransformTargetTest {
    private class RecordingTarget : SceneTransformTarget {
        var received: SceneTransform? = null

        override fun updateSceneTransform(transform: SceneTransform) {
            received = transform
        }
    }

    @Test
    fun sceneLayerPublishesItsAuthoritativeTransformToRendererTarget() {
        val transform = SceneTransform(
            x = 0.12f,
            y = 0.2f,
            width = 0.45f,
            height = 0.3f,
            rotationDegrees = 17f,
            opacity = 0.7f
        )
        val layer = SceneLayer(
            id = "logo",
            moduleId = "oss.source.image.static",
            displayName = "Logo",
            zIndex = 2,
            transform = transform
        )
        val target = RecordingTarget()

        layer.applyTransformTo(target)

        assertEquals(transform, target.received)
    }
}
