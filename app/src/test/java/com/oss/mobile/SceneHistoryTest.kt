package com.oss.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneHistoryTest {
    private fun scene(name: String) = SceneDocument(id = "test", name = name)

    @Test
    fun undoAndRedoRestoreImmutableSnapshots() {
        val first = scene("First")
        val second = scene("Second")
        val third = scene("Third")
        val history = SceneHistory(first)

        history.record(first)
        assertEquals(first, history.undo(second))
        assertEquals(second, history.redo())
        assertEquals(third, run {
            history.record(second)
            history.record(third)
            history.undo(third)
            history.redo()
        })
    }

    @Test
    fun newEditClearsRedoBranch() {
        val first = scene("First")
        val second = scene("Second")
        val third = scene("Third")
        val history = SceneHistory(first)

        history.record(first)
        assertEquals(first, history.undo(second))
        history.record(third)
        assertFalse(history.canRedo())
        assertEquals(first, history.undo(third))
    }

    @Test
    fun historyIsBounded() {
        val history = SceneHistory(scene("0"), capacity = 3)
        history.record(scene("1"))
        history.record(scene("2"))
        history.record(scene("3"))
        assertTrue(history.canUndo())
        assertEquals(scene("2"), history.undo(scene("3")))
        assertEquals(scene("1"), history.undo(scene("2")))
        assertEquals(scene("0"), history.undo(scene("1")))
    }
}
