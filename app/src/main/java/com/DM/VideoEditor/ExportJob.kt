package com.DM.VideoEditor

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ExportJob(
    val clips: ArrayList<VideoClip>,
    val textOverlays: ArrayList<TextOverlay>,
    val scaleFilter: String,
    val totalDurationMs: Long,
    val videoQuality: Int,
    val isAudioDuckingEnabled: Boolean = false,
    val projectAudioUri: String? = null
) : Parcelable
