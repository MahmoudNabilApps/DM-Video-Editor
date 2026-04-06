package com.DM.VideoEditor

/**
 * Undo/redo for editor actions. New user actions must call [register] or [commit] — not [undo]/[redo].
 * - [commit] / [register]: clear redo stack and record the pair. The forward action must already
 *   have run before you call either method (otherwise the UI would duplicate redo on commit).
 */
class UndoRedoManager(private val maxDepth: Int = 20) {

    data class Edit(val undo: () -> Unit, val redo: () -> Unit)

    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    /** Clears history (e.g. new project / replace timeline). */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    fun commit(undo: () -> Unit, redo: () -> Unit) {
        redoStack.clear()
        undoStack.addLast(Edit(undo, redo))
        trimUndo()
    }

    /** Alias for [commit] — forward action already applied; only record undo/redo pair. */
    fun register(undo: () -> Unit, redo: () -> Unit) {
        commit(undo, redo)
    }

    fun performUndo() {
        val e = undoStack.removeLastOrNull() ?: return
        e.undo()
        redoStack.addLast(e)
    }

    fun performRedo() {
        val e = redoStack.removeLastOrNull() ?: return
        e.redo()
        undoStack.addLast(e)
    }

    private fun trimUndo() {
        while (undoStack.size > maxDepth) undoStack.removeFirst()
    }
}
