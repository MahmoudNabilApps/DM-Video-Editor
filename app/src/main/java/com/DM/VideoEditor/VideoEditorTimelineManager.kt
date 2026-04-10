package com.DM.VideoEditor

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

// -- Timeline Manager ------------------------------------------
internal fun VideoEditingActivity.setupClipTimeline() {
        clipAdapter = ClipAdapter(
            clips,
            onClipClick = { idx ->
                selectedClipIndex = idx
                clipAdapter.setSelected(idx)
                player.pause()
                playClip(idx)
            },
            onClipLongClick = { idx -> showQuickClipMenu(idx) },
            onClipDelete = { idx ->
                if (clips.size > 1) {
                    val removed = clips[idx]
                    val delIdx = idx
                    clips.removeAt(delIdx)
                    clipAdapter.onClipRemoved(delIdx)
                    clipAdapter.notifyDataSetChanged()
                    if (selectedClipIndex >= clips.size) selectedClipIndex = (clips.size - 1).coerceAtLeast(0)
                    clipAdapter.setSelected(selectedClipIndex)
                    player.pause()
                    playClip(selectedClipIndex)
                    rebuildTimeline()
                    updateClipCountBadge()
                    loadThumbsForTimeline()
                    undoRedo.commit(
                        undo = {
                            val at = delIdx.coerceAtMost(clips.size)
                            clipAdapter.shiftStripThumbnailsRight(at)
                            clips.add(at, removed)
                            clipAdapter.notifyDataSetChanged()
                            if (selectedClipIndex >= clips.size) selectedClipIndex = clips.size - 1
                            clipAdapter.setSelected(selectedClipIndex)
                            loadThumb(at)
                            player.pause()
                            playClip(selectedClipIndex)
                            rebuildTimeline()
                            updateClipCountBadge()
                            loadThumbsForTimeline()
                        },
                        redo = {
                            if (delIdx in clips.indices) {
                                clips.removeAt(delIdx)
                                clipAdapter.onClipRemoved(delIdx)
                            }
                            clipAdapter.notifyDataSetChanged()
                            if (selectedClipIndex >= clips.size) selectedClipIndex = (clips.size - 1).coerceAtLeast(0)
                            clipAdapter.setSelected(selectedClipIndex)
                            player.pause()
                            playClip(selectedClipIndex)
                            rebuildTimeline()
                            updateClipCountBadge()
                            loadThumbsForTimeline()
                        }
                    )
                } else showSnack(getString(R.string.editor_keep_at_least_one_clip))
            },
            onAddClick = { addVideoLauncher.launch("video/*") },
            onTransitionClick = { idx -> showTransitionSheet(idx) }
        )
        binding.rvClips.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvClips.adapter = clipAdapter

        // Drag to reorder — only CLIP cells (even positions)
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder): Boolean {
                val fromType = vh.itemViewType
                val toType   = t.itemViewType
                if (fromType != ClipAdapter.TYPE_CLIP || toType != ClipAdapter.TYPE_CLIP) return false
                val fromClip = vh.bindingAdapterPosition / 2
                val toClip   = t.bindingAdapterPosition  / 2
                clipAdapter.moveItem(fromClip, toClip)
                swapThumbnailCacheKeys(fromClip, toClip)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
        }).attachToRecyclerView(binding.rvClips)

        // frameRecyclerView is now hidden; thumbnails are loaded into MultiTrackTimelineView
    }
internal fun VideoEditingActivity.rebuildTimeline() {
        if (!isTimelineInitialized()) return

        // ── Video blocks ──────────────────────────────────────
        var cumulativeMs = 0L
        val vBlocks = clips.mapIndexed { idx, clip ->
            val effectiveDur = effectiveDurationMs(clip)
            val blk = com.DM.VideoEditor.customviews.VideoTimelineBlock(
                id              = idx.toLong(),
                clipIndex       = idx,
                startMs         = cumulativeMs,
                durationMs      = effectiveDur,
                transitionLabel = if (idx > 0) clip.transition else ""
            )
            cumulativeMs += effectiveDur
            blk
        }
        val totalMs = cumulativeMs.coerceAtLeast(1_000L)

        // ── Text blocks — assign to rows avoiding overlap ─────
        data class RowSlot(val startMs: Long, val endMs: Long)
        val rowSlots = mutableListOf<MutableList<RowSlot>>()

        val tBlocks = textOverlays.mapIndexed { idx, ov ->
            val startMs = (ov.startSec * 1000).toLong().coerceAtLeast(0L)
            val endMs   = if (ov.endSec >= 0) (ov.endSec * 1000).toLong() else totalMs
            val durMs   = (endMs - startMs).coerceAtLeast(500L)

            // Find first row with no overlap
            var row = 0
            while (row < rowSlots.size) {
                val conflict = rowSlots[row].any { slot ->
                    startMs < slot.endMs && startMs + durMs > slot.startMs
                }
                if (!conflict) break
                row++
            }
            if (row >= rowSlots.size) rowSlots.add(mutableListOf())
            rowSlots[row].add(RowSlot(startMs, startMs + durMs))

            com.DM.VideoEditor.customviews.TextTimelineBlock(
                id           = ov.id,
                overlayIndex = idx,
                startMs      = startMs,
                durationMs   = durMs,
                label        = ov.text,
                trackRow     = row
            )
        }

        // ── Sticker blocks ─────
        val baseRow = rowSlots.size
        val sBlocks = stickerOverlays.mapIndexed { idx, st ->
            val startMs = (st.startSec * 1000).toLong().coerceAtLeast(0L)
            val endMs   = if (st.endSec >= 0) (st.endSec * 1000).toLong() else totalMs
            val durMs   = (endMs - startMs).coerceAtLeast(500L)

            // For stickers, we simply assign them to high rows to avoid text overlap visually
            val row = baseRow + idx

            com.DM.VideoEditor.customviews.TextTimelineBlock(
                id           = st.id,
                overlayIndex = 1000 + idx, // offset for sticker distinction
                startMs      = startMs,
                durationMs   = durMs,
                label        = "✨ Sticker",
                trackRow     = row
            )
        }

        multiTrackTimeline.setTotalDuration(totalMs)
        multiTrackTimeline.setVideoBlocks(vBlocks)
        multiTrackTimeline.setTextBlocks(tBlocks + sBlocks)

        // REQ-6: Audio track — show last added audio URI as a teal block
        val aBlocks = if (projectAudioUri != null) {
            val audioDur = projectAudioDurationMs
            listOf(
                com.DM.VideoEditor.customviews.AudioTimelineBlock(
                    id        = 1L,
                    startMs   = 0L,
                    durationMs = audioDur.coerceAtLeast(totalMs),
                    label     = projectAudioUri!!.lastPathSegment
                        ?.substringAfterLast("/") ?: "Audio"
                )
            )
        } else emptyList()
        multiTrackTimeline.setAudioBlocks(aBlocks)

        // Update total-time display
        binding.tvTotalTime.text = fmtMs(totalMs)
    }
internal fun VideoEditingActivity.loadThumbsForTimeline() {
        clips.forEachIndexed { blockIdx, clip ->
            val cached = thumbnailCache[blockIdx]
            if (cached != null && !cached.isRecycled) {
                if (isTimelineInitialized())
                    multiTrackTimeline.updateBlockThumbnail(blockIdx, cached)
                return@forEachIndexed
            }
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val r = MediaMetadataRetriever()
                    r.setDataSource(this@loadThumbsForTimeline, clip.uri)
                    val midUs = (clip.durationMs / 2) * 1000L
                    val bmp = r.getFrameAtTime(midUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    r.release()
                    if (bmp != null && !bmp.isRecycled) {
                        val scaled = Bitmap.createScaledBitmap(bmp, 160, 90, true)
                        if (bmp !== scaled) bmp.recycle()
                        withContext(Dispatchers.Main) {
                            thumbnailCache[blockIdx]?.takeIf { it != scaled && !it.isRecycled }?.recycle()
                            thumbnailCache[blockIdx] = scaled
                            if (isTimelineInitialized())
                                multiTrackTimeline.updateBlockThumbnail(blockIdx, scaled)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }
internal fun VideoEditingActivity.loadThumb(idx: Int) {
        if (idx >= clips.size) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(this@loadThumb, clips[idx].uri)
                val bmp = r.getFrameAtTime(0)
                r.release()
                withContext(Dispatchers.Main) {
                    if (idx < clips.size && bmp != null) {
                        val scaled = Bitmap.createScaledBitmap(bmp, 120, 80, true)
                        if (scaled != bmp) bmp.recycle()
                        clipAdapter.setThumbnail(idx, scaled)
                    } else bmp?.recycle()
                }
            } catch (_: Exception) {}
        }
    }
internal fun VideoEditingActivity.loadThumbsStaggered(indices: List<Int>) {
        if (indices.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            for (idx in indices) {
                if (idx >= clips.size) break
                try {
                    val r = MediaMetadataRetriever()
                    r.setDataSource(this@loadThumbsStaggered, clips[idx].uri)
                    val bmp = r.getFrameAtTime(0)
                    r.release()
                    if (bmp != null) {
                        val scaled = Bitmap.createScaledBitmap(bmp, 120, 80, true)
                        if (scaled != bmp) bmp.recycle()
                        withContext(Dispatchers.Main) {
                            if (idx < clips.size) clipAdapter.setThumbnail(idx, scaled)
                        }
                    }
                } catch (_: Exception) {
                }
                delay(40)
            }
        }
    }
internal fun VideoEditingActivity.recycleTimelineThumbnail(clipIndex: Int) {
        thumbnailCache.remove(clipIndex)?.takeIf { !it.isRecycled }?.recycle()
        if (!isTimelineInitialized()) return
        multiTrackTimeline.videoBlocks.getOrNull(clipIndex)?.thumbnail?.let { bmp ->
            if (!bmp.isRecycled) bmp.recycle()
            multiTrackTimeline.videoBlocks.getOrNull(clipIndex)?.thumbnail = null
        }
    }
internal fun VideoEditingActivity.setupMultiTrackTimeline() {
        multiTrackTimeline = binding.multiTrackTimeline
        multiTrackTimeline.callback = object : com.DM.VideoEditor.customviews.MultiTrackTimelineCallback {

            override fun onSeek(posMs: Long) {
                // Determine which clip and offset this global time belongs to
                val (windowIndex, localMs) = getPlaylistPositionFromGlobalTime(posMs)
                player.seekTo(windowIndex, localMs) // Constraint 3: Double parameter seek
                binding.tvCurrentTime.text = fmtMs(posMs)
            }

            override fun onVideoBlockSelected(clipIndex: Int) {
                selectedClipIndex = clipIndex
                clipAdapter.setSelected(clipIndex)
                player.pause()
                playClip(clipIndex)
            }

            override fun onVideoBlockDeselected() {
                multiTrackTimeline.deselectAll()
            }

            override fun onVideoBlockTrimStart(clipIndex: Int, newStartTrimMs: Long, newDurationMs: Long) {
                clips.getOrNull(clipIndex)?.let { clip ->
                    // BUG-2 FIX: newStartTrimMs is the block's new cumulative timeline position.
                    // Compute how many ms were trimmed from the left on the timeline, then
                    // apply that delta to the clip's media trim points.
                    val originalCumulativeStart = getClipCumulativeStartMs(clipIndex)
                    val trimmedFromLeft = (newStartTrimMs - originalCumulativeStart).coerceAtLeast(0L)
                    val newMediaStart = clip.startTrimMs + trimmedFromLeft
                    val newMediaEnd   = if (clip.endTrimMs > 0L) clip.endTrimMs else clip.durationMs
                    if (newMediaStart < newMediaEnd - 200L) {
                        if (!multiTrackTimeline.dragUndoSaved) {
                            val oldStart = clip.startTrimMs
                            val ci = clipIndex
                            undoRedo.register(
                                undo = {
                                    clips.getOrNull(ci)?.let { it.startTrimMs = oldStart }
                                    rebuildTimeline()
                                },
                                redo = {
                                    clips.getOrNull(ci)?.let { it.startTrimMs = pendingVideoTrimStartRedo[0] }
                                    rebuildTimeline()
                                }
                            )
                            multiTrackTimeline.dragUndoSaved = true
                        }
                        pendingVideoTrimStartRedo[0] = newMediaStart
                        clip.startTrimMs = newMediaStart
                        rebuildTimeline()
                    }
                }
            }

            override fun onVideoBlockTrimEnd(clipIndex: Int, newDurationMs: Long) {
                clips.getOrNull(clipIndex)?.let { clip ->
                    val st = if (clip.endTrimMs > 0L) clip.startTrimMs else 0L
                    val newEnd = st + newDurationMs
                    if (newEnd > st + 200L) {
                        if (!multiTrackTimeline.dragUndoSaved) {
                            val oldEnd = clip.endTrimMs
                            val ci = clipIndex
                            undoRedo.register(
                                undo = {
                                    clips.getOrNull(ci)?.let { it.endTrimMs = oldEnd }
                                    rebuildTimeline()
                                },
                                redo = {
                                    clips.getOrNull(ci)?.let { it.endTrimMs = pendingVideoTrimEndRedo[0] }
                                    rebuildTimeline()
                                }
                            )
                            multiTrackTimeline.dragUndoSaved = true
                        }
                        pendingVideoTrimEndRedo[0] = newEnd
                        clip.endTrimMs = newEnd
                        rebuildTimeline()
                    }
                }
            }

            override fun onVideoBlockReorder(fromIndex: Int, toIndex: Int) {
                if (fromIndex == toIndex) return
                if (fromIndex < 0 || toIndex < 0 || fromIndex >= clips.size || toIndex >= clips.size) return
                Collections.swap(clips, fromIndex, toIndex)
                swapThumbnailCacheKeys(fromIndex, toIndex)
                clipAdapter.swapStripThumbnailIndices(fromIndex, toIndex)
                clipAdapter.notifyDataSetChanged()
                rebuildTimeline()
            }

            override fun onTextBlockSelected(overlayIndex: Int) {
                showTextSheet(overlayIndex)
            }

            override fun onTextBlockMoved(overlayIndex: Int, newStartMs: Long, newDurationMs: Long) {
                textOverlays.getOrNull(overlayIndex)?.let { ov ->
                    if (!multiTrackTimeline.dragUndoSaved) {
                        val oldStart = ov.startSec; val oldEnd = ov.endSec
                        val oi = overlayIndex
                        undoRedo.register(
                            undo = {
                                textOverlays.getOrNull(oi)?.let {
                                    it.startSec = oldStart; it.endSec = oldEnd
                                }
                                bumpOverlaysVersion(); rebuildTimeline()
                            },
                            redo = {
                                textOverlays.getOrNull(oi)?.let {
                                    it.startSec = pendingTextMoveRedo[0]
                                    it.endSec = pendingTextMoveRedo[1]
                                }
                                bumpOverlaysVersion(); rebuildTimeline()
                            }
                        )
                        multiTrackTimeline.dragUndoSaved = true
                    }
                    pendingTextMoveRedo[0] = newStartMs / 1000f
                    pendingTextMoveRedo[1] = (newStartMs + newDurationMs) / 1000f
                    ov.startSec = pendingTextMoveRedo[0]
                    ov.endSec   = pendingTextMoveRedo[1]
                }
                bumpOverlaysVersion()
            }

            override fun onTextBlockTrimStart(overlayIndex: Int, newStartMs: Long) {
                textOverlays.getOrNull(overlayIndex)?.let { ov ->
                    if (!multiTrackTimeline.dragUndoSaved) {
                        val oldStart = ov.startSec
                        val oi = overlayIndex
                        undoRedo.register(
                            undo = {
                                textOverlays.getOrNull(oi)?.startSec = oldStart
                                bumpOverlaysVersion(); rebuildTimeline()
                            },
                            redo = {
                                textOverlays.getOrNull(oi)?.startSec = pendingTextTrimStartRedo[0]
                                bumpOverlaysVersion(); rebuildTimeline()
                            }
                        )
                        multiTrackTimeline.dragUndoSaved = true
                    }
                    pendingTextTrimStartRedo[0] = newStartMs / 1000f
                    ov.startSec = pendingTextTrimStartRedo[0]
                }
                bumpOverlaysVersion()
            }

            override fun onTextBlockTrimEnd(overlayIndex: Int, newEndMs: Long) {
                textOverlays.getOrNull(overlayIndex)?.let { ov ->
                    if (!multiTrackTimeline.dragUndoSaved) {
                        val oldEnd = ov.endSec
                        val oi = overlayIndex
                        undoRedo.register(
                            undo = {
                                textOverlays.getOrNull(oi)?.endSec = oldEnd
                                bumpOverlaysVersion(); rebuildTimeline()
                            },
                            redo = {
                                textOverlays.getOrNull(oi)?.endSec = pendingTextTrimEndRedo[0]
                                bumpOverlaysVersion(); rebuildTimeline()
                            }
                        )
                        multiTrackTimeline.dragUndoSaved = true
                    }
                    pendingTextTrimEndRedo[0] = newEndMs / 1000f
                    ov.endSec = pendingTextTrimEndRedo[0]
                }
                bumpOverlaysVersion()
            }

            override fun onAddClipTapped() {
                addVideoLauncher.launch("video/*")
            }
        }

        // Zoom-in / zoom-out buttons in the play control bar
        binding.btnZoomIn.setOnClickListener  { multiTrackTimeline.zoomIn() }
        binding.btnZoomOut.setOnClickListener { multiTrackTimeline.zoomOut() }
    }





