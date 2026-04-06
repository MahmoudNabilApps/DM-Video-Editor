package com.DM.VideoEditor

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ── Media Tools ───────────────────────────────────────────────

internal fun VideoEditingActivity.showTransitionSheet(idx: Int = selectedClipIndex) {
    if (idx <= 0) {
        if (clips.size >= 2) {
            val names = (1 until clips.size).map { getString(R.string.transition_before_clip, it + 1) }.toTypedArray()
            MaterialAlertDialogBuilder(this).setTitle(R.string.transition_pick_location_title)
                .setItems(names) { _, w -> showTransitionPicker(w + 1) }.show()
        } else showSnack(getString(R.string.snack_add_two_clips_for_transitions))
        return
    }
    showTransitionPicker(idx)
}

internal fun VideoEditingActivity.showTransitionPicker(idx: Int) {
    if (idx < 0 || idx >= clips.size) return
    val d = BottomSheetDialog(this)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(editorColor(R.color.colorSheetBackground))
        setPadding(32, 32, 32, 48)
    }

    TextView(this).apply {
        text = getString(R.string.transition_dialog_title, idx + 1)
        textSize = 18f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
        setPadding(0, 0, 0, 16)
    }.also { root.addView(it) }

    val rv = RecyclerView(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, this@showTransitionPicker.run { 320.dp })
        layoutManager = GridLayoutManager(this@showTransitionPicker, 3)
    }
    val allTr = TransitionType.getAll()
    val adapter = TransitionSelectorAdapter(allTr, clips[idx].transition) { t ->
        val old = clips[idx].transition
        val newTr = t.code
        clips[idx].transition = newTr
        clipAdapter.notifyItemChanged(idx)
        rebuildTimeline()
        undoRedo.commit(
            undo = {
                clips[idx].transition = old
                clipAdapter.notifyItemChanged(idx)
                rebuildTimeline()
            },
            redo = {
                clips[idx].transition = newTr
                clipAdapter.notifyItemChanged(idx)
                rebuildTimeline()
            }
        )
        showSnack(getString(R.string.snack_transition_applied, t.label))
    }
    rv.adapter = adapter
    root.addView(rv)

    MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.btn_apply_to_all)
        setTextColor(editorColor(R.color.colorAccentOrange))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 }
        setOnClickListener {
            val currentTr = clips[idx].transition
            val backups = clips.map { it.transition }
            for (i in 1 until clips.size) {
                clips[i].transition = currentTr
            }
            clipAdapter.notifyDataSetChanged()
            rebuildTimeline()
            undoRedo.commit(
                undo = {
                    backups.forEachIndexed { i, s -> clips[i].transition = s }
                    clipAdapter.notifyDataSetChanged(); rebuildTimeline()
                },
                redo = {
                    for (i in 1 until clips.size) clips[i].transition = currentTr
                    clipAdapter.notifyDataSetChanged(); rebuildTimeline()
                }
            )
            d.dismiss()
                showSnack(getString(R.string.snack_applied_to_all))
        }
    }.also { root.addView(it) }

    d.setContentView(root); d.show()
}

internal fun VideoEditingActivity.showVolumeSheet(idx: Int) {
    val d = BottomSheetDialog(this)
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 48)
        setBackgroundColor(editorColor(R.color.colorEditorBackground))
    }
    TextView(this).apply {
        text = getString(R.string.volume_sheet_title, idx + 1); textSize = 16f
        setTextColor(Color.WHITE); setPadding(0, 0, 0, 16)
    }.also { container.addView(it) }
    val tvPct = TextView(this).apply {
        text = "${(clips[idx].volume * 100).toInt()}%"; textSize = 28f
        setTextColor(editorColor(R.color.colorCyanAccent)); gravity = Gravity.CENTER
    }.also { container.addView(it) }
    val sb = SeekBar(this).apply {
        max = 200; progress = (clips[idx].volume * 100).toInt()
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { tvPct.text = "$p%" }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }.also { container.addView(it) }
    Button(this).apply {
        text = getString(R.string.btn_apply)
        setOnClickListener {
            // REQ-7: coerce volume to 0–200 %
            val pct = sb.progress.coerceIn(0, 200)
            val v   = pct / 100f
            clips[idx].volume = v
            d.dismiss()
            adjustVol(v)
        }
    }.also { container.addView(it) }
    d.setContentView(container); d.show()
}

internal fun VideoEditingActivity.showAudioSheet() {
    MaterialAlertDialogBuilder(this).setTitle(R.string.audio_sheet_title)
        .setItems(arrayOf(
            "🎵 Add Background Music",
            "🔇 Mute Video",
            "🎙 Extract Audio as MP3",
            "🔊 Volume ×2",
            "🔉 Volume ×0.5",
            "🎛 Custom Volume Level"
        )) { _, w ->
            when (w) {
                0 -> audioPickerLauncher.launch("audio/*")
                1 -> muteVideo()
                2 -> extractAudio()
                3 -> adjustVol(2f)
                4 -> adjustVol(0.5f)
                5 -> showVolumeSheet(selectedClipIndex)
            }
        }.show()
}

internal fun VideoEditingActivity.addAudioTrack(audioUri: Uri) {
    projectAudioUri = audioUri
    val clip = clips.getOrNull(selectedClipIndex) ?: return
    val baseline = clip.copy()
    val idx = selectedClipIndex
    lifecycleScope.launch {
        projectAudioDurationMs = withContext(Dispatchers.IO) { getClipDurationMs(audioUri) }
        rebuildTimeline()
        showLoading(true)
        try {
            val vi = withContext(Dispatchers.IO) { materializeLocalPath(clip.uri) }
                ?: return@launch showSnack(getString(R.string.snack_cannot_read_video))
            val ai = withContext(Dispatchers.IO) { materializeLocalPath(audioUri) }
                ?: return@launch showSnack(getString(R.string.snack_cannot_read_audio))
            val out = getOutputFile("audio_mixed")
            val cmd = "-i \"$vi\" -i \"$ai\" -filter_complex \"[0:a][1:a]amix=inputs=2:duration=first:dropout_transition=3[a]\" -map 0:v -map \"[a]\" -c:v copy \"${out.absolutePath}\" -y"
            val mixResult = withContext(Dispatchers.IO) { FfmpegExecutor.executeSync(cmd) }
            if (!FfmpegExecutor.isSuccess(mixResult) || !out.exists() || out.length() == 0L) {
                showSnack(getString(R.string.snack_operation_failed)); return@launch
            }
            val newUri = Uri.fromFile(out)
            val dur = withContext(Dispatchers.IO) { getClipDurationMs(newUri) }
            val newClip = baseline.copy(uri = newUri, durationMs = dur, startTrimMs = 0L, endTrimMs = 0L)
            undoRedo.register(
                undo = {
                    clips[idx] = baseline
                    clipAdapter.notifyItemChanged(idx)
                    thumbnailCache.remove(idx)
                    reloadPlaylist(maintainPosition = true, targetIdx = idx)
                    rebuildTimeline(); loadThumb(idx); loadThumbsForTimeline()
                },
                redo = {
                    clips[idx] = newClip
                    clipAdapter.notifyItemChanged(idx)
                    thumbnailCache.remove(idx)
                    reloadPlaylist(maintainPosition = true, targetIdx = idx)
                    rebuildTimeline(); loadThumb(idx); loadThumbsForTimeline()
                }
            )
            clips[idx] = newClip
            clipAdapter.notifyItemChanged(idx)
            thumbnailCache.remove(idx)
            reloadPlaylist(maintainPosition = true, targetIdx = idx)
            rebuildTimeline()
            loadThumbsForTimeline()
            withContext(Dispatchers.IO) { saveToMediaStore(out) }
            showSnack(getString(R.string.snack_audio_saved))
        } finally {
            showLoading(false)
        }
    }
}

internal fun VideoEditingActivity.muteVideo() {
    val c = clips.getOrNull(selectedClipIndex) ?: return
    execFfmpegClipReplace(c.copy(), "muted") { i, o -> "-i \"$i\" -c:v copy -an \"$o\" -y" }
}

internal fun VideoEditingActivity.extractAudio() {
    val c = clips.getOrNull(selectedClipIndex) ?: return
    lifecycleScope.launch {
        showLoading(true)
        try {
            val i = withContext(Dispatchers.IO) { materializeLocalPath(c.uri) }
                ?: return@launch showSnack(getString(R.string.snack_cannot_read_file))
            val o = getOutputFile("audio", "mp3")
            val exResult = withContext(Dispatchers.IO) {
                FfmpegExecutor.executeSync("-i \"$i\" -vn -acodec mp3 -q:a 2 \"${o.absolutePath}\" -y")
            }
            if (FfmpegExecutor.isSuccess(exResult)) {
                withContext(Dispatchers.IO) { saveToMediaStore(o) }
                showSnack(getString(R.string.snack_saved))
            } else showSnack(getString(R.string.snack_operation_failed))
        } finally {
            showLoading(false)
        }
    }
}

internal fun VideoEditingActivity.adjustVol(f: Float) {
    val safeF = f.coerceIn(0f, 4f)
    val c = clips.getOrNull(selectedClipIndex) ?: return
    execFfmpegClipReplace(c.copy(), "vol") { i, o -> "-i \"$i\" -filter:a \"volume=$safeF\" -c:v copy \"$o\" -y" }
}
