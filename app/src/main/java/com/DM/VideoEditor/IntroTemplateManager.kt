package com.DM.VideoEditor

import android.graphics.Color
import android.net.Uri
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

internal fun VideoEditingActivity.showIntroTemplateSheet() {
    val d = BottomSheetDialog(this)
    val v = layoutInflater.inflate(R.layout.intro_templates_bottom_sheet, null)
    val rv = v.findViewById<RecyclerView>(R.id.introTemplateRecyclerView)

    val templates = listOf(
        IntroTemplate("Blue Sky", Color.parseColor("#3498db"), "Welcome", "Let's Begin"),
        IntroTemplate("Sunset", Color.parseColor("#e67e22"), "Story Time", "By Jules"),
        IntroTemplate("Nature", Color.parseColor("#27ae60"), "Adventure", "Exploration"),
        IntroTemplate("Modern", Color.parseColor("#2c3e50"), "Vlog #1", "New Video"),
        IntroTemplate("Bold", Color.parseColor("#c0392b"), "BREAKING", "NEWS")
    )

    rv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
    rv.adapter = IntroTemplateAdapter(templates) { template ->
        d.dismiss()
        applyIntroTemplate(template)
    }

    d.setContentView(v)
    d.show()
}

internal fun VideoEditingActivity.applyIntroTemplate(template: IntroTemplate) {
    lifecycleScope.launch {
        showLoading(true)
        val out = getOutputFile("intro")
        val colorHex = String.format("0x%06X", (0xFFFFFF and template.color))

        // Generate a 3-second solid color video with text
        // We use drawtext to burn the title and subtitle.
        // Note: fontfile path is often required on Android.
        val fontPath = "/system/fonts/Roboto-Regular.ttf"
        val fontArg = if (File(fontPath).exists()) "fontfile=$fontPath:" else ""

        // Escape single quotes for drawtext
        val escapedTitle = template.title.replace("'", "\\'")
        val escapedSubtitle = template.subtitle.replace("'", "\\'")

        val cmd = "-f lavfi -i color=c=$colorHex:s=1280x720:d=3 -vf \"" +
                  "drawtext=${fontArg}text='$escapedTitle':fontcolor=white:fontsize=64:x=(w-text_w)/2:y=(h-text_h)/2-40:enable='between(t,0,3)'," +
                  "drawtext=${fontArg}text='$escapedSubtitle':fontcolor=white:fontsize=32:x=(w-text_w)/2:y=(h-text_h)/2+40:enable='between(t,0.5,3)'\" " +
                  "-c:v libx264 -t 3 -pix_fmt yuv420p \"${out.absolutePath}\" -y"

        val result = withContext(Dispatchers.IO) { FfmpegExecutor.executeSync(cmd) }

        if (FfmpegExecutor.isSuccess(result) && out.exists()) {
            val uri = Uri.fromFile(out)
            val dur = withContext(Dispatchers.IO) { getClipDurationMs(uri) }
            val newClip = VideoClip(uri = uri, durationMs = dur)

            // Insert at beginning
            clips.add(0, newClip)
            clipAdapter.notifyDataSetChanged()
            updateClipCountBadge()
            rebuildTimeline()
            loadThumb(0)
            loadThumbsForTimeline()

            // Re-select first clip
            selectedClipIndex = 0
            clipAdapter.setSelected(0)
            reloadPlaylist(maintainPosition = false, targetIdx = 0)

            showSnack("تمت إضافة المقدمة بنجاح")

            undoRedo.register(
                undo = {
                    if (clips.isNotEmpty()) {
                        clips.removeAt(0)
                        clipAdapter.notifyDataSetChanged()
                        updateClipCountBadge()
                        rebuildTimeline()
                        reloadPlaylist(maintainPosition = false)
                    }
                },
                redo = {
                    clips.add(0, newClip)
                    clipAdapter.notifyDataSetChanged()
                    updateClipCountBadge()
                    rebuildTimeline()
                    reloadPlaylist(maintainPosition = false, targetIdx = 0)
                }
            )
        } else {
            showSnack("فشل إنشاء المقدمة")
        }
        showLoading(false)
    }
}
