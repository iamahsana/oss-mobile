package com.oss.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneLayerOperationsTest {
    private fun fixture(): SceneDocument = SceneDocument(
        id = "scene",
        name = "Test",
        layers = listOf(
            SceneLayer("a", "image", "Logo", 0, SceneTransform(x = 0.1f, y = 0.2f)),
            SceneLayer("b", "text", "Title", 1, SceneTransform(x = 0.3f, y = 0.4f))
        )
    )

    @Test fun duplicateCreatesIndependentTopLayerWithOffset() {
        val original = fixture()
        val result = SceneLayerOperations.duplicate(original, "a")
        val copy = result.layers.last()
        assertEquals(3, result.layers.size)
        assertNotEquals("a", copy.id)
        assertEquals("Logo copy", copy.displayName)
        assertEquals(2, copy.zIndex)
        assertEquals(0.125f, copy.transform.x, 0.0001f)
        assertEquals(0.225f, copy.transform.y, 0.0001f)
        assertFalse(copy.locked)
        assertEquals(2, original.layers.size)
    }

    @Test fun duplicateNamesRemainUnique() {
        val once = SceneLayerOperations.duplicate(fixture(), "a")
        val twice = SceneLayerOperations.duplicate(once, "a")
        assertEquals("Logo copy 2", twice.layers.last().displayName)
    }

    @Test fun moveToFrontAndBackNormalizeZOrder() {
        val original = fixture()
        val front = SceneLayerOperations.moveToFront(original, "a")
        assertEquals(listOf("b", "a"), front.renderOrder().map { it.id })
        assertEquals(listOf(0, 1), front.layers.sortedBy { it.zIndex }.map { it.zIndex })

        val back = SceneLayerOperations.moveToBack(front, "a")
        assertEquals(listOf("a", "b"), back.renderOrder().map { it.id })
    }

    @Test fun visibilityAndLockOperationsOnlyChangeRequestedLayer() {
        val original = fixture()
        val hidden = SceneLayerOperations.toggleVisibility(original, "a")
        assertFalse(hidden.layers.first { it.id == "a" }.visible)
        assertTrue(hidden.layers.first { it.id == "b" }.visible)

        val locked = SceneLayerOperations.toggleLock(original, "b")
        assertTrue(locked.layers.first { it.id == "b" }.locked)
        assertFalse(locked.layers.first { it.id == "a" }.locked)
    }

    @Test fun removeAndUnknownIdsAreSafe() {
        val original = fixture()
        assertEquals(listOf("b"), SceneLayerOperations.remove(original, "a").layers.map { it.id })
        assertSame(original, SceneLayerOperations.remove(original, "missing"))
        assertSame(original, SceneLayerOperations.moveToFront(original, "missing"))
    }
}
