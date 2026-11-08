package com.oss.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneDocumentTest {

    @Test
    fun usesPortraitCanvasDefaultsAndComputesAspectRatio() {
        val canvas = SceneCanvas()
        assertEquals(720, canvas.width)
        assertEquals(1280, canvas.height)
        assertEquals(0.5625f, canvas.aspectRatio)
    }

    @Test
    fun renderOrderIsStableBackToFrontAndSkipsHiddenLayers() {
        val document = SceneDocument(
            id = "main",
            name = "Main",
            layers = listOf(
                layer("top", z = 20),
                layer("hidden", z = -5, visible = false),
                layer("same-b", z = 10),
                layer("same-a", z = 10),
                layer("bottom", z = 0)
            )
        )

        assertEquals(
            listOf("bottom", "same-a", "same-b", "top"),
            document.renderOrder().map { it.id }
        )
        assertFalse(document.renderOrder().any { it.id == "hidden" })
    }

    @Test
    fun keepsGeometryIndependentOfCanvasPixelDimensions() {
        val transform = SceneTransform(x = 0.1f, y = 0.2f, width = 0.3f, height = 0.4f)
        val portrait = SceneDocument("p", "Portrait", SceneCanvas(720, 1280), listOf(layer("a", transform = transform)))
        val landscape = SceneDocument("l", "Landscape", SceneCanvas(1920, 1080), listOf(layer("a", transform = transform)))

        assertEquals(portrait.layers.single().transform, landscape.layers.single().transform)
    }

    @Test
    fun rejectsDuplicateLayerIds() {
        assertThrows(IllegalArgumentException::class.java) {
            SceneDocument("main", "Main", layers = listOf(layer("same", 0), layer("same", 1)))
        }
    }

    @Test
    fun rejectsUnsupportedSchemaVersion() {
        assertThrows(IllegalArgumentException::class.java) {
            SceneDocument("main", "Main", schemaVersion = 99)
        }
    }

    @Test
    fun rejectsNonFiniteAndInvalidTransformValues() {
        assertThrows(IllegalArgumentException::class.java) {
            SceneTransform(x = Float.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SceneTransform(opacity = 1.1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SceneTransform(width = 0f)
        }
    }

    @Test
    fun preservesVisibilityAndLockStateAsIndependentProperties() {
        val hiddenLocked = layer("logo", visible = false, locked = true)
        assertFalse(hiddenLocked.visible)
        assertTrue(hiddenLocked.locked)
    }

    private fun layer(
        id: String,
        z: Int = 0,
        visible: Boolean = true,
        locked: Boolean = false,
        transform: SceneTransform = SceneTransform()
    ) = SceneLayer(
        id = id,
        moduleId = "oss.source.image.static",
        displayName = id,
        zIndex = z,
        transform = transform,
        visible = visible,
        locked = locked
    )
}
