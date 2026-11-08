package com.oss.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompositorDiffTest {
    @Test
    fun unchangedSceneProducesEmptyDelta() {
        val image = Any()
        val current = listOf("background" to image)
        val desired = listOf("background" to image)

        assertEquals(emptyList<Pair<String, Any>>(), compositorAppendDelta(current, desired))
    }

    @Test
    fun topmostLayerProducesAppendOnlyDelta() {
        val background = Any()
        val badge = Any()
        val current = listOf("background" to background)
        val desired = listOf("background" to background, "badge" to badge)

        assertEquals(listOf("badge" to badge), compositorAppendDelta(current, desired))
    }

    @Test
    fun removalRequiresRebuild() {
        val background = Any()
        val badge = Any()
        val current = listOf("background" to background, "badge" to badge)
        val desired = listOf("background" to background)

        assertNull(compositorAppendDelta(current, desired))
    }

    @Test
    fun reorderingRequiresRebuild() {
        val background = Any()
        val badge = Any()
        val current = listOf("background" to background, "badge" to badge)
        val desired = listOf("badge" to badge, "background" to background)

        assertNull(compositorAppendDelta(current, desired))
    }

    @Test
    fun structuralRebuildDetectsAnyRendererThatWouldBeReusedAfterRelease() {
        val background = Any()
        val replacement = Any()

        assertEquals(
            true,
            compositorHasRendererOverlap(
                listOf("background" to background),
                listOf("background" to background, "badge" to replacement)
            )
        )
        assertEquals(
            false,
            compositorHasRendererOverlap(
                listOf("background" to background),
                listOf("background" to replacement)
            )
        )
    }

    @Test
    fun replacingRendererUnderSameIdRequiresRebuild() {
        val oldRenderer = Any()
        val newRenderer = Any()

        assertNull(
            compositorAppendDelta(
                listOf("image" to oldRenderer),
                listOf("image" to newRenderer)
            )
        )
    }
}
