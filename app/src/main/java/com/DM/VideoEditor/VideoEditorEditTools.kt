package com.DM.VideoEditor

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// -- Edit Tools ------------------------------------------------
internal fun VideoEditingActivity.showQuickClipMenu(idx: Int) {
        selectedClipIndex = idx
        clipAdapter.setSelected(idx)
        player.pause()
        playClip(idx)
        val items = arrayOf(
            getString(R.string.tool_trim),
            getString(R.string.tool_split),
            getString(R.string.tool_speed),
            getString(R.string.tool_filter),
            getString(R.string.tool_volume),
            getString(R.string.delete)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.quick_clip_actions)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showTrimSheet()
                    1 -> splitClipAtCurrentPosition()
                    2 -> showSpeedSheet()
                    3 -> showFilterSheet()
                    4 -> showVolumeSheet(idx)
                    5 -> if (clips.size > 1) deleteCurrentClip()
                    else showSnack(getString(R.string.editor_keep_at_least_one_clip))
                }
            }
            .show()
    }
internal fun VideoEditingActivity.showTrimSheet() {
        val d = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.trim_bottom_sheet_dialog, null)
        val sl = v.findViewById<RangeSlider>(R.id.rangeSlider)
        val ts = v.findViewById<TextView>(R.id.tvTrimStart); val te = v.findViewById<TextView>(R.id.tvTrimEnd)
        val durSec = player.duration / 1000f; val step = 0.1f
        val rd = Math.ceil((durSec / step).toDouble()).toFloat() * step
        sl.valueFrom = 0f; sl.valueTo = rd; sl.stepSize = step; sl.values = listOf(0f, rd)
        ts.text = fmtMs(0); te.text = fmtMs(player.duration)
        sl.addOnChangeListener { s, _, _ ->
            ts.text = fmtMs((s.values[0] * 1000).toLong())
            te.text = fmtMs((s.values[1] * 1000).toLong())
            player.seekTo((s.values[0] * 1000).toLong())
        }
        v.findViewById<Button>(R.id.btnDoneTrim).setOnClickListener {
            val st = sl.values[0]; val en = sl.values[1]
            // REQ-7: ensure at least 100 ms remains after trim
            if (en - st < 0.1f) {
                showSnack(getString(R.string.snack_trim_range_too_small))
                return@setOnClickListener
            }
            val baseline = clips.getOrNull(selectedClipIndex)?.copy() ?: return@setOnClickListener
            clips.getOrNull(selectedClipIndex)?.let { it.startTrimMs = (st * 1000).toLong(); it.endTrimMs = (en * 1000).toLong() }
            clipAdapter.notifyItemChanged(selectedClipIndex); d.dismiss(); trimClip(st, en, baseline)
        }
        d.setContentView(v); d.show()
    }
internal fun VideoEditingActivity.trimClip(start: Float, end: Float, undoBaseline: VideoClip) {
        clips.getOrNull(selectedClipIndex) ?: return
        execFfmpegClipReplace(undoBaseline, "trimmed") { i, oAbs ->
            "-ss $start -to $end -i \"$i\" -c:v mpeg4 -q:v 3 -c:a aac -b:a 128k \"$oAbs\" -y"
        }
    }
internal fun VideoEditingActivity.copyUriToLocalFile(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        return try {
            val ext = contentResolver.getType(uri)
                ?.substringAfterLast('/')
                ?.let { if (it == "mpeg") "mp4" else it }
                ?: "mp4"
            val local = File(cacheDir, "split_src_${System.currentTimeMillis()}.$ext")
            contentResolver.openInputStream(uri)?.use { ins ->
                local.outputStream().use { out -> ins.copyTo(out) }
            }
            if (local.exists() && local.length() > 0) local.absolutePath else null
        } catch (_: Exception) { null }
    }
internal fun VideoEditingActivity.splitClipAtCurrentPosition() {
        val clip = clips.getOrNull(selectedClipIndex) ?: return
        val currentMs = player.currentPosition
        val totalMs = player.duration

        if (totalMs <= 0) { showSnack(getString(R.string.snack_no_video_to_split)); return }
        if (currentMs <= 300 || currentMs >= totalMs - 300) {
            showSnack(getString(R.string.snack_move_playhead_first)); return
        }

        val out1 = getOutputFile("split_a")
        val out2 = getOutputFile("split_b")
        val splitSec = currentMs / 1000.0

        lifecycleScope.launch {
            showLoading(true)
            showSnack(getString(R.string.snack_split_preparing))

            // Always copy to a true local file before running two FFmpeg passes.
            // A SAF path (saf:N.mp4) is a one-shot file-descriptor: the kernel
            // closes it after the first FFmpeg session reads it, so the second
            // session would fail with "SAF id N not found / moov atom not found".
            val input = withContext(Dispatchers.IO) { copyUriToLocalFile(clip.uri) }
            if (input == null) {
                showLoading(false)
                showSnack(getString(R.string.snack_cannot_read_source_file))
                return@launch
            }

            withContext(Dispatchers.IO) {
                val r1 = FfmpegExecutor.executeSync(
                    "-to $splitSec -i \"$input\" -c:v mpeg4 -q:v 3 -c:a aac \"${out1.absolutePath}\" -y"
                )
                val r2 = FfmpegExecutor.executeSync(
                    "-ss $splitSec -i \"$input\" -c:v mpeg4 -q:v 3 -c:a aac \"${out2.absolutePath}\" -y"
                )
                val ok1 = FfmpegExecutor.isSuccess(r1) && out1.exists() && out1.length() > 0
                val ok2 = FfmpegExecutor.isSuccess(r2) && out2.exists() && out2.length() > 0
                val dur1 = if (ok1) getClipDurationMs(Uri.fromFile(out1)) else 0L
                val dur2 = if (ok2) getClipDurationMs(Uri.fromFile(out2)) else 0L
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    if (ok1 && ok2) {
                        val oldClip = clip.copy()
                        val insertIdx = selectedClipIndex
                        val nc1 = VideoClip(uri = Uri.fromFile(out1), durationMs = dur1)
                        val nc2 = VideoClip(uri = Uri.fromFile(out2), durationMs = dur2)
                        undoRedo.register(
                            undo = {
                                if (insertIdx + 1 in clips.indices) clips.removeAt(insertIdx + 1)
                                clips[insertIdx] = oldClip
                                clipAdapter.recycleAllStripThumbnails()
                                clipAdapter.notifyDataSetChanged()
                                updateClipCountBadge()
                                rebuildTimeline()
                                for (i in clips.indices) loadThumb(i)
                                loadThumbsForTimeline()
                            },
                            redo = {
                                clips[insertIdx] = nc1
                                clips.add(insertIdx + 1, nc2)
                                clipAdapter.recycleAllStripThumbnails()
                                clipAdapter.notifyDataSetChanged()
                                updateClipCountBadge()
                                rebuildTimeline()
                                for (i in clips.indices) loadThumb(i)
                                loadThumbsForTimeline()
                            }
                        )
                        clips[selectedClipIndex] = nc1
                        clips.add(selectedClipIndex + 1, nc2)
                        clipAdapter.recycleAllStripThumbnails()
                        clipAdapter.notifyDataSetChanged()
                        for (i in clips.indices) loadThumb(i)
                        updateClipCountBadge()
                        rebuildTimeline()
                        loadThumbsForTimeline()
                        showSnack(getString(R.string.snack_split_done_at, fmtMs(currentMs)))
                    } else {
                        val log1 = r1.exceptionOrNull()?.message ?: ""
                        val log2 = r2.exceptionOrNull()?.message ?: ""
                        Log.e("DmSplit", "s1 ok=$ok1 log=$log1")
                        Log.e("DmSplit", "s2 ok=$ok2 log=$log2")
                        showSnack(getString(R.string.snack_split_failed))
                    }
                }
            }
        }
    }
internal fun VideoEditingActivity.showSpeedSheet() {
        val d = BottomSheetDialog(this); val v = layoutInflater.inflate(R.layout.speed_bottom_sheet_dialog, null)
        val sl = v.findViewById<Slider>(R.id.speedSlider); val tv = v.findViewById<TextView>(R.id.tvSpeedValue)

        // REQ-SpeedRamp: Add ramp buttons
        val rampContainer = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(0, 16, 0, 16)
        }
        fun addRamp(label: String, type: String?) {
            com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label; layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    val clip = clips.getOrNull(selectedClipIndex) ?: return@setOnClickListener
                    clip.speedRamp = type
                    showSnack("Ramp selected: $label")
                }
            }.also { rampContainer.addView(it) }
        }
        addRamp("Normal", null)
        addRamp("Slow-Fast", "slow_fast")
        addRamp("Fast-Slow", "fast_slow")

        (v as android.view.ViewGroup).addView(rampContainer, v.indexOfChild(v.findViewById(R.id.btnDoneSpeed)))

        sl.addOnChangeListener { _, value, _ -> tv.text = "${value}×" }
        fun set(x: Float) { sl.value = x.coerceIn(sl.valueFrom, sl.valueTo); tv.text = "${x}×" }
        v.findViewById<MaterialButton>(R.id.btn025x)?.setOnClickListener { set(0.25f) }
        v.findViewById<MaterialButton>(R.id.btn05x)?.setOnClickListener { set(0.5f) }
        v.findViewById<MaterialButton>(R.id.btn1x)?.setOnClickListener { set(1f) }
        v.findViewById<MaterialButton>(R.id.btn2x)?.setOnClickListener { set(2f) }
        v.findViewById<MaterialButton>(R.id.btn4x)?.setOnClickListener { set(4f) }
        v.findViewById<MaterialButton>(R.id.btnDoneSpeed)?.setOnClickListener {
            val safeSpeed = sl.value.coerceIn(0.1f, 8.0f)
            val baseline = clips.getOrNull(selectedClipIndex)?.copy() ?: return@setOnClickListener
            clips.getOrNull(selectedClipIndex)?.speedFactor = safeSpeed
            d.dismiss(); changeSpeed(safeSpeed, baseline)
        }
        d.setContentView(v); d.show()
    }
internal fun VideoEditingActivity.changeSpeed(speed: Float, undoBaseline: VideoClip) {
        clips.getOrNull(selectedClipIndex) ?: return
        val vf = 1f / speed; val at = atempoChain(speed)
        execFfmpegClipReplace(undoBaseline, "speed") { i, oAbs ->
            "-i \"$i\" -filter_complex \"[0:v]setpts=${vf}*PTS[v];[0:a]${at}[a]\" -map \"[v]\" -map \"[a]\" \"$oAbs\" -y"
        }
    }
internal fun VideoEditingActivity.atempoChain(s: Float) = FFmpegCommandBuilder.atempoChain(s)
internal fun VideoEditingActivity.deleteCurrentClip() {
        val idx = selectedClipIndex
        if (clips.size <= 1) { showSnack(getString(R.string.editor_keep_at_least_one_clip)); return }
        val removed = clips[idx]
        val delIdx = idx
        recycleTimelineThumbnail(idx)
        clips.removeAt(delIdx)
        clipAdapter.onClipRemoved(delIdx)
        clipAdapter.notifyDataSetChanged()
        if (selectedClipIndex >= clips.size) selectedClipIndex = (clips.size - 1).coerceAtLeast(0)
        clipAdapter.setSelected(selectedClipIndex)
        player.pause()
        playClip(selectedClipIndex)
        updateClipCountBadge()
        rebuildTimeline()
        loadThumbsForTimeline()
        undoRedo.commit(
            undo = {
                val at = delIdx.coerceAtMost(clips.size)
                clipAdapter.shiftStripThumbnailsRight(at)
                clips.add(at, removed)
                clipAdapter.notifyDataSetChanged()
                loadThumb(at)
                clipAdapter.setSelected(selectedClipIndex.coerceIn(0, (clips.size - 1).coerceAtLeast(0)))
                player.pause()
                playClip(selectedClipIndex)
                updateClipCountBadge()
                rebuildTimeline()
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
                updateClipCountBadge()
                rebuildTimeline()
                loadThumbsForTimeline()
            }
        )
    }


