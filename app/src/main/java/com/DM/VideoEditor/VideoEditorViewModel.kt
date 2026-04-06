package com.DM.VideoEditor

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class ExportProgressState {
    data object Idle : ExportProgressState()
    data class Running(val percent: Int, val message: String) : ExportProgressState()
    data class Finished(val success: Boolean) : ExportProgressState()
}

/**
 * Holds export UI state for incremental migration off the Activity.
 * Clip/text editing state remains in [VideoEditingActivity] until adapters are list-based.
 */
class VideoEditorViewModel : ViewModel() {

    val undoRedo = UndoRedoManager(maxDepth = 20)

    private val _exportProgress = MutableStateFlow<ExportProgressState>(ExportProgressState.Idle)
    val exportProgress: StateFlow<ExportProgressState> = _exportProgress.asStateFlow()

    fun setExportProgress(percent: Int, message: String) {
        _exportProgress.value = ExportProgressState.Running(percent, message)
    }

    fun resetExportProgress() {
        _exportProgress.value = ExportProgressState.Idle
    }

    fun markExportFinished(success: Boolean) {
        _exportProgress.value = ExportProgressState.Finished(success)
    }
}
