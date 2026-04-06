package com.DM.VideoEditor

import android.content.ContentValues
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// -- Export Manager ------------------------------------------
internal fun VideoEditingActivity.showExportQualityDialog() {
        val totalMs = clips.sumOf { c ->
            val d = if (c.endTrimMs > 0) c.endTrimMs - c.startTrimMs else c.durationMs
            (d / c.speedFactor).toLong()
        }

        val d = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(editorColor(R.color.colorEditorBackground))
            setPadding(32, 32, 32, 60)
        }

        // Title
        TextView(this).apply {
            text = "🎬 تصدير الفيديو"
            textSize = 20f; setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 20)
        }.also { root.addView(it) }

        // Info card
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(editorColor(R.color.colorSurfaceDeep))
            setPadding(24, 16, 24, 16)
            (layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = (16 * resources.displayMetrics.density).toInt()
            addView(TextView(this@showExportQualityDialog).apply {
                text = "📹 مقاطع: ${clips.size}   📝 نصوص: ${textOverlays.size}   ⏱ ${fmtMs(totalMs)}"
                textSize = 14f; setTextColor(editorColor(R.color.colorInfoText))
            })
        }.also { root.addView(it) }

        // Section label
        TextView(this).apply {
            text = "اختر جودة التصدير:"
            textSize = 14f; setTextColor(editorColor(R.color.colorSubtleText))
            setPadding(0, 0, 0, 12)
        }.also { root.addView(it) }

        // Quality buttons
        data class Quality(val label: String, val icon: String, val sub: String, val scale: String, val qv: Int)
        val qualities = listOf(
            Quality("1080p", "🏆", "جودة سينمائية عالية", "scale=-2:1080", 2),
            Quality("720p",  "⚖️", "متوازنة — موصى بها", "scale=-2:720", 3),
            Quality("480p",  "📦", "حجم صغير — مشاركة سريعة", "scale=-2:480", 5),
            Quality("أصلية","📋", "الجودة الأصلية كما هي", "", 3)
        )
        qualities.forEach { q ->
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(editorColor(R.color.colorPanelInner))
                setPadding(20, 16, 20, 16)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() }
                layoutParams = lp

                // Icon
                TextView(this@showExportQualityDialog).apply {
                    text = q.icon; textSize = 22f; setPadding(0, 0, 16, 0)
                }.also { addView(it) }

                // Text block
                LinearLayout(this@showExportQualityDialog).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@showExportQualityDialog).apply {
                        text = q.label; textSize = 16f; setTextColor(Color.WHITE)
                        setTypeface(null, Typeface.BOLD)
                    })
                    addView(TextView(this@showExportQualityDialog).apply {
                        text = q.sub; textSize = 12f; setTextColor(editorColor(R.color.colorSubtleText))
                    })
                }.also { addView(it) }

                // Arrow
                TextView(this@showExportQualityDialog).apply {
                    text = "›"; textSize = 22f; setTextColor(editorColor(R.color.colorPurpleAccent))
                }.also { addView(it) }

                setOnClickListener { d.dismiss(); startExport(q.scale, totalMs, q.qv) }
            }.also { root.addView(it) }
        }

        d.setContentView(root)
        d.show()
    }
internal fun VideoEditingActivity.startExport(scaleFilter: String, totalDurationMs: Long, qv: Int = 3) {
        val job = ExportJob(ArrayList(clips), ArrayList(textOverlays), scaleFilter, totalDurationMs, qv)
        ExportForegroundService.start(this, job)
        showSnack(getString(R.string.export_started_snackbar))
    }
internal fun VideoEditingActivity.runFFmpegSave(cmd: String, output: File) {
        lifecycleScope.launch {
            showLoading(true)
            try {
                val saveResult = withContext(Dispatchers.IO) { FfmpegExecutor.executeSync(cmd) }
                if (FfmpegExecutor.isSuccess(saveResult)) {
                    withContext(Dispatchers.IO) { saveToMediaStore(output) }
                    showSnack(getString(R.string.snack_saved))
                } else showSnack(getString(R.string.snack_operation_failed))
            } finally {
                showLoading(false)
            }
        }
    }
internal fun VideoEditingActivity.saveToMediaStore(file: File): android.net.Uri? {
        val mimeType = if (file.extension == "mp3") "audio/mpeg" else "video/mp4"
        val isAudio = mimeType.startsWith("audio")
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (isAudio) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else { if (isAudio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        return try {
            val uri = contentResolver.insert(collection, values) ?: return null
            contentResolver.openOutputStream(uri)?.use { os -> file.inputStream().use { it.copyTo(os) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            uri
        } catch (e: Exception) { Log.e("SaveMedia", "${e.message}"); null }
    }


