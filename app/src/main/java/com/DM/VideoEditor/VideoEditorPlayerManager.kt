package com.DM.VideoEditor

import android.net.Uri
import android.util.Log
import android.view.View
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ── Player Setup & Management ──────────────────────────────────────────

@OptIn(UnstableApi::class)
internal fun VideoEditingActivity.setupPlayer() {
    val rf = DefaultRenderersFactory(this).apply {
        setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        setEnableDecoderFallback(true)
    }
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(12_000, 48_000, 1_500, 4_000)
        .build()
    player = ExoPlayer.Builder(this, rf)
        .setLoadControl(loadControl)
        .build()
    player.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
    player.repeatMode = Player.REPEAT_MODE_OFF // Constraint 4
    binding.playerView.player = player
    binding.playerView.useController = false

    player.addListener(object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Update UI when playback naturally crosses into the next clip
            val newIdx = player.currentMediaItemIndex
            if (newIdx in clips.indices && newIdx != selectedClipIndex) {
                selectedClipIndex = newIdx
                clipAdapter.setSelected(newIdx)
                binding.tvVideoName.text = clips[newIdx].uri.lastPathSegment?.substringAfterLast("/") ?: "Video ${newIdx + 1}"
                if (isTimelineInitialized()) {
                    multiTrackTimeline.selectVideoBlock(newIdx)
                }
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY && !isVideoLoaded) {
                isVideoLoaded = true
                binding.loadingScreen.root.visibility = View.GONE
                val totalDur = clips.sumOf { effectiveDurationMs(it) }
                val currentGlobalPos = getClipCumulativeStartMs(player.currentMediaItemIndex) + player.currentPosition
                binding.tvTotalTime.text = fmtMs(totalDur)
                binding.tvDuration.text = "${fmtMs(currentGlobalPos)} / ${fmtMs(totalDur)}"
                startProgressUpdater()
                loadThumbsForTimeline()
            }
            updatePlayPause()
        }
        override fun onIsPlayingChanged(playing: Boolean) = updatePlayPause()

        override fun onPlayerError(error: PlaybackException) {
            Log.w("DmEditor", "Player error: ${error.message}")
            val clip = clips.getOrNull(selectedClipIndex) ?: return
            lifecycleScope.launch {
                showSnack(getString(R.string.player_error_loading))
                val transcoded = transcodeForPlayback(clip)
                if (transcoded != null) {
                    clips[selectedClipIndex] = clip.copy(uri = Uri.fromFile(transcoded))
                    reloadPlaylist(maintainPosition = false, targetIdx = selectedClipIndex)
                } else {
                    binding.loadingScreen.root.visibility = View.GONE
                    showSnack(getString(R.string.player_error_cannot_play))
                }
            }
        }
    })

    binding.btnPlayPause.setOnClickListener { if (player.isPlaying) player.pause() else player.play() }
    binding.btnSave.setOnClickListener { showExportQualityDialog() }
    binding.btnHome.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
    // customVideoSeeker is now hidden; seeking is handled by MultiTrackTimelineView
}

internal fun VideoEditingActivity.playClip(idx: Int) {
    if (idx < 0 || idx >= clips.size) return
    selectedClipIndex = idx
    // If player has items, just seek. If not, reload.
    if (player.mediaItemCount > 0 && player.mediaItemCount == clips.size) {
        player.seekToDefaultPosition(idx)
        player.playWhenReady = true
    } else {
        reloadPlaylist(maintainPosition = false, targetIdx = idx)
    }
    binding.tvVideoName.text = clips[idx].uri.lastPathSegment?.substringAfterLast("/") ?: "Video ${idx + 1}"
    if (isTimelineInitialized()) multiTrackTimeline.selectVideoBlock(idx)
}

internal fun VideoEditingActivity.reloadPlaylist(maintainPosition: Boolean = true, targetIdx: Int? = null) {
    val currentGlobalTime = if (player.mediaItemCount > 0) {
        getClipCumulativeStartMs(player.currentMediaItemIndex) + player.currentPosition
    } else 0L

    isVideoLoaded = false
    binding.loadingScreen.root.visibility = View.VISIBLE
    player.stop()

    val mediaItems = clips.map { clip ->
        val builder = MediaItem.Builder().setUri(clip.uri)
        // Constraint 1: Explicitly handle clipping globally
        val dur = if (clip.endTrimMs > 0L) clip.endTrimMs else clip.durationMs
        if (dur > 0L) {
            builder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.startTrimMs)
                    .setEndPositionMs(dur)
                    .build()
            )
        }
        builder.build()
    }
    player.setMediaItems(mediaItems)
    player.prepare()

    if (maintainPosition && currentGlobalTime > 0) {
        // Constraint 2 and 3: Preserve Global Position & seekTo(window, pos)
        val (idx, locMs) = getPlaylistPositionFromGlobalTime(currentGlobalTime)
        if (idx in clips.indices) player.seekTo(idx, locMs)
    } else if (targetIdx != null && targetIdx in clips.indices) {
        player.seekToDefaultPosition(targetIdx)
    } else if (selectedClipIndex in clips.indices) {
        player.seekToDefaultPosition(selectedClipIndex)
    }
    player.playWhenReady = true // Constraint 4 (start playing smoothly)
}

internal fun VideoEditingActivity.getPlaylistPositionFromGlobalTime(globalPosMs: Long): Pair<Int, Long> {
    var cumulative = 0L
    for (i in clips.indices) {
        val dur = effectiveDurationMs(clips[i])
        if (globalPosMs < cumulative + dur || i == clips.size - 1) {
            return Pair(i, globalPosMs - cumulative)
        }
        cumulative += dur
    }
    return Pair(0, 0L)
}

internal suspend fun VideoEditingActivity.transcodeForPlayback(clip: VideoClip): File? {
    val input = materializeLocalPath(clip.uri) ?: return null
    val out = getOutputFile("compat")
    val cmd = "-i \"$input\" " +
                "-vf \"scale='min(iw,1920)':'min(ih,1080)':force_original_aspect_ratio=decrease\" " +
                "-c:v mpeg4 -q:v 5 -c:a aac -b:a 96k " +
                "-movflags +faststart \"${out.absolutePath}\" -y"
    val result = withContext(Dispatchers.IO) { FfmpegExecutor.executeSync(cmd) }
    return if (FfmpegExecutor.isSuccess(result) && out.exists()) out else null
}

internal fun VideoEditingActivity.startProgressUpdater() {
    progressUpdateJob?.cancel()
    progressUpdateJob = lifecycleScope.launch {
        while (isActive) {
            val localPos = player.currentPosition
            val currentIdx = player.currentMediaItemIndex
            val globalPos = getClipCumulativeStartMs(currentIdx) + localPos
            val totalDuration = clips.sumOf { effectiveDurationMs(it) }

            if (player.isPlaying || isVideoLoaded) {
                binding.tvCurrentTime.text = fmtMs(globalPos)
                binding.tvDuration.text = "${fmtMs(globalPos)} / ${fmtMs(totalDuration)}"
                if (isTimelineInitialized()) {
                    multiTrackTimeline.currentPositionMs = globalPos
                }
            }
            // Always update text overlay preview
            updateTextPreview(globalPos / 1000f)
            delay(100)
        }
    }
}

internal fun VideoEditingActivity.updatePlayPause() {
    binding.btnPlayPause.setImageResource(
        if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
}

/** Returns the cumulative start position (ms) of clip[index] in the project. */
internal fun VideoEditingActivity.getClipCumulativeStartMs(index: Int): Long {
    var ms = 0L
    for (i in 0 until index) ms += effectiveDurationMs(clips[i])
    return ms
}

/** Effective playback duration of a clip respecting trim and speed. */
internal fun VideoEditingActivity.effectiveDurationMs(clip: VideoClip): Long {
    val raw = if (clip.endTrimMs > 0L) clip.endTrimMs - clip.startTrimMs else clip.durationMs
    return (raw / clip.speedFactor).toLong().coerceAtLeast(100L)
}

