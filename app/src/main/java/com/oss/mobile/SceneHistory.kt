package com.oss.mobile

/**
 * Bounded in-memory undo/redo history for one scene editing session.
 * SceneDocument is immutable, so snapshots remain stable.
 */
class SceneHistory(initial: SceneDocument, private val capacity: Int = 50) {
    private val undoStack = mutableListOf(initial)
    private val redoStack = mutableListOf<SceneDocument>()

    init { require(capacity >= 2) }

    fun record(document: SceneDocument) {
        if (undoStack.lastOrNull() == document) return
        undoStack += document
        if (undoStack.size > capacity) undoStack.removeAt(0)
        redoStack.clear()
    }

    fun undo(current: SceneDocument): SceneDocument? {
        if (undoStack.isEmpty()) return null
        if (undoStack.last() != current) {
            redoStack += current
            return undoStack.last()
        }
        if (undoStack.size <= 1) return null
        redoStack += undoStack.removeAt(undoStack.lastIndex)
        return undoStack.last()
    }

    fun redo(): SceneDocument? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack += next
        if (undoStack.size > capacity) undoStack.removeAt(0)
        return next
    }

    fun canUndo(): Boolean = undoStack.size > 1
    fun canRedo(): Boolean = redoStack.isNotEmpty()
}
