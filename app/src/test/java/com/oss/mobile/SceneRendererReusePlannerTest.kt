package com.oss.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneRendererReusePlannerTest {
    private fun layer(
        id: String,
        module: String = "image",
        config: Map<String, String> = emptyMap(),
        z: Int = 0,
        transform: SceneTransform = SceneTransform()
    ) = SceneLayer(id, module, id, z, transform, configuration = config)

    private fun signature(layer: SceneLayer) =
        SceneRendererSignature(layer.id, layer.moduleId, layer.configuration)

    @Test
    fun geometryAndZIndexChangesKeepRendererReusableWhenOrderIsUnchanged() {
        val old = layer("logo", z = 1)
        val changed = layer(
            "logo",
            z = 8,
            transform = SceneTransform(x = 0.2f, y = 0.3f, width = 0.4f, height = 0.2f)
        )

        assertTrue(canReuseSceneRendererPrefix(listOf(signature(old)), listOf(changed)))
    }

    @Test
    fun appendingTopmostLayerKeepsExistingPrefixReusable() {
        val background = layer("background")
        val badge = layer("badge", z = 1)

        assertTrue(
            canReuseSceneRendererPrefix(
                listOf(signature(background)),
                listOf(background, badge)
            )
        )
    }

    @Test
    fun removalRequiresFreshRenderersForRemainingLayers() {
        val background = layer("background")
        val title = layer("title", z = 1)

        assertFalse(
            canReuseSceneRendererPrefix(
                listOf(signature(background), signature(title)),
                listOf(background)
            )
        )
    }

    @Test
    fun insertionBeforeExistingLayerRequiresFreshRenderers() {
        val title = layer("title", z = 1)
        val background = layer("background", z = 0)

        assertFalse(
            canReuseSceneRendererPrefix(
                listOf(signature(title)),
                listOf(background, title)
            )
        )
    }

    @Test
    fun sourceOrConfigurationChangeRequiresFreshRenderers() {
        val old = layer("logo", config = mapOf("resource_id" to "1"))
        val changed = layer("logo", config = mapOf("resource_id" to "2"))

        assertFalse(canReuseSceneRendererPrefix(listOf(signature(old)), listOf(changed)))
    }
}
