package com.DM.VideoEditor

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.Semaphore

/**
 * Runs the same export pipeline as the editor, usable from [ExportForegroundService].
 */
class VideoExportOrchestrator(private val context: Context) {

    private val app = context.applicationContext
    private var projectW = 1280
    private var projectH = 720

    data class ExportOutput(
        val file: File,
        val mediaStoreUri: Uri?,
        val durationMs: Long,
        val textOverlayCount: Int
    )

    suspend fun runExport(
        job: ExportJob,
        onProgress: (percent: Int, status: String) -> Unit
    ): Result<ExportOutput> = withContext(Dispatchers.IO) {
        val clips = job.clips
        val textOverlays = job.textOverlays
        val totalDurationMs = job.totalDurationMs
        val scaleFilter = job.scaleFilter
        try {
            val processedPaths = mutableListOf<String>()
            for (i in clips.indices) {
                val clip = clips[i]
                onProgress(((i.toFloat() / clips.size) * 30).toInt().coerceIn(0, 30), app.getString(R.string.export_status_clip, i + 1, clips.size))
                val path = processClip(clip, i)
                if (path != null) processedPaths.add(path)
            }
            if (processedPaths.isEmpty()) {
                return@withContext Result.failure(Exception(app.getString(R.string.export_err_prepare)))
            }

            onProgress(35, app.getString(R.string.export_status_merge, processedPaths.size))
            val merged = if (processedPaths.size == 1) File(processedPaths[0])
            else mergeWithTransitions(processedPaths, clips)
                ?: return@withContext Result.failure(Exception(app.getString(R.string.export_err_merge)))
            if (!merged.exists()) {
                return@withContext Result.failure(Exception(app.getString(R.string.export_err_merge)))
            }

            onProgress(55, app.getString(R.string.export_status_text))
            val withText = if (textOverlays.isNotEmpty()) applyTextOverlays(merged, textOverlays) else merged

            onProgress(65, app.getString(R.string.export_status_final))
            val finalOut = getOutputFile("DM_Video_${System.currentTimeMillis()}")
            val scaleVf = if (scaleFilter.isNotBlank()) "-vf \"$scaleFilter\"" else ""
            val qv = job.videoQuality
            val cmd =
                "-i \"${withText.absolutePath}\" $scaleVf -c:v mpeg4 -q:v $qv -c:a aac -b:a 128k -movflags +faststart \"${finalOut.absolutePath}\" -y"

            val semaphore = Semaphore(0)
            var finalEncodeOk = false

            FfmpegExecutor.executeAsync(
                cmd,
                { session ->
                    try {
                        finalEncodeOk = FfmpegExecutor.isSuccess(session)
                    } finally {
                        semaphore.release()
                    }
                },
                null,
                { stats ->
                    try {
                        val raw = FfmpegExecutor.statisticsProgress(stats, totalDurationMs)
                        val pct = (65 + raw * 33).toInt().coerceIn(65, 98)
                        onProgress(
                            pct,
                            app.getString(R.string.export_status_encoding, fmtMs(stats.time.toLong()))
                        )
                    } catch (t: Throwable) {
                        Log.e(TAG, "stats", t)
                    }
                }
            )
            semaphore.acquire()

            val exportDur = getClipDurationMs(Uri.fromFile(finalOut))
            val savedUri = saveToMediaStore(finalOut)

            if (finalEncodeOk && finalOut.exists() && finalOut.length() > 0) {
                onProgress(100, app.getString(R.string.export_notification_complete))
                return@withContext Result.success(
                    ExportOutput(
                        file = finalOut,
                        mediaStoreUri = savedUri,
                        durationMs = exportDur,
                        textOverlayCount = textOverlays.size
                    )
                )
            }
            return@withContext Result.failure(Exception(app.getString(R.string.export_err_failed)))
        } catch (e: Exception) {
            Log.e(TAG, "runExport", e)
            return@withContext Result.failure(e)
        }
    }

    private suspend fun processClip(clip: VideoClip, idx: Int): String? {
        val input = materializeLocalPath(clip.uri) ?: return null
        if (idx == 0) {
            val (w, h) = FFmpegCommandBuilder.detectProjectResolution(input)
            projectW = w
            projectH = h
        }
        val hasAudio = FFmpegCommandBuilder.hasAudioStream(input)
        val out = getOutputFile("proc_$idx")
        val cmd = FFmpegCommandBuilder.buildProcessClipCmd(
            inputPath = input,
            outputPath = out.absolutePath,
            clip = clip,
            targetW = projectW,
            targetH = projectH,
            hasAudio = hasAudio
        )
        val result = FfmpegExecutor.executeSync(cmd)
        return if (FfmpegExecutor.isSuccess(result) && out.exists() && out.length() > 0L) {
            out.absolutePath
        } else {
            Log.w(TAG, "proc[$idx] failed, using original")
            input
        }
    }

    private fun mergeWithTransitions(paths: List<String>, clips: List<VideoClip>): File? {
        val hasTransitions = clips.drop(1).any { it.transition != "none" }
        if (!hasTransitions) return simpleConcatFiles(paths)
        return try {
            val out = getOutputFile("merged_trans")
            val cmd = FFmpegCommandBuilder.buildXfadeMergeCmd(paths, clips, out.absolutePath)
                ?: return simpleConcatFiles(paths)
            val xr = FfmpegExecutor.executeSync(cmd)
            val ok = FfmpegExecutor.isSuccess(xr)
            if (ok && out.exists() && out.length() > 0L) out
            else {
                Log.w(TAG, "xfade failed, concat fallback")
                simpleConcatFiles(paths)
            }
        } catch (e: Exception) {
            Log.e(TAG, "merge: ${e.message}")
            simpleConcatFiles(paths)
        }
    }

    private fun simpleConcatFiles(paths: List<String>): File? {
        if (paths.isEmpty()) return null
        val out = getOutputFile("merged")
        val allAudio = paths.all { FFmpegCommandBuilder.hasAudioStream(it) }
        val anyAudio = paths.any { FFmpegCommandBuilder.hasAudioStream(it) }
        val cmd = when {
            allAudio -> {
                val lf = File(app.cacheDir, "concat_${System.currentTimeMillis()}.txt")
                lf.writeText(paths.joinToString("\n") { "file '$it'" })
                FFmpegCommandBuilder.buildConcatCmd(lf, out.absolutePath, true)
            }
            anyAudio -> FFmpegCommandBuilder.buildConcatFilterComplexCmd(paths, out.absolutePath)
            else -> {
                val lf = File(app.cacheDir, "concat_${System.currentTimeMillis()}.txt")
                lf.writeText(paths.joinToString("\n") { "file '$it'" })
                FFmpegCommandBuilder.buildConcatCmd(lf, out.absolutePath, false)
            }
        }
        val cr = FfmpegExecutor.executeSync(cmd)
        val ok = FfmpegExecutor.isSuccess(cr)
        return if (ok && out.exists() && out.length() > 0L) out else null
    }

    private fun applyTextOverlays(input: File, textOverlays: List<TextOverlay>): File {
        if (textOverlays.isEmpty()) return input
        val pngs = mutableListOf<File>()
        try {
            for ((idx, o) in textOverlays.withIndex()) {
                val f = File(app.cacheDir, "text_overlay_${idx}_${System.currentTimeMillis()}.png")
                if (!TextOverlayBitmapRenderer.renderToPng(o, projectW, projectH, f)) {
                    Log.e(TAG, "PNG render failed for overlay $idx")
                    return input
                }
                pngs.add(f)
            }
            val out = getOutputFile("with_text")
            val inputArgs = buildString {
                append("-i \"${input.absolutePath}\" ")
                pngs.forEach { append("-loop 1 -i \"${it.absolutePath}\" ") }
            }
            val sb = StringBuilder()
            var label = "[0:v]"
            textOverlays.forEachIndexed { i, o ->
                val next = if (i == textOverlays.lastIndex) "[vout]" else "[vt$i]"
                val enable = when {
                    o.endSec > 0 && o.startSec > 0 -> "between(t,${o.startSec},${o.endSec})"
                    o.endSec > 0 -> "lte(t,${o.endSec})"
                    o.startSec > 0 -> "gte(t,${o.startSec})"
                    else -> "1"
                }

                // Animation logic
                val animExpr = when (o.animationType) {
                    "slide_in" -> {
                        // Slide from bottom for 0.5s
                        val start = o.startSec
                        val dur = 0.5f
                        "x=0:y='if(between(t,$start,${start+dur}), H-(H-0)*((t-$start)/$dur), 0)'"
                    }
                    "zoom_fade" -> {
                        // Simple alpha fade in 0.5s
                        val start = o.startSec
                        val dur = 0.5f
                        "alpha='if(between(t,$start,${start+dur}), (t-$start)/$dur, 1)'"
                    }
                    "typewriter" -> {
                        // Typewriter handled via pre-rendered PNG frames? No, too complex.
                        // FFmpeg overlay doesn't support changing the image over time easily.
                        // For MVP, we use a simple 'pop' fade.
                        "alpha='if(lt(t,${o.startSec + 0.1}), 0, 1)'"
                    }
                    else -> ""
                }

                val baseOverlay = if (animExpr.contains("x=") || animExpr.contains("y=")) "overlay=shortest=1" else "overlay=0:0:shortest=1"
                val opts = if (animExpr.isNotEmpty()) ":$animExpr" else ""
                sb.append("${label}[${i + 1}:v]${baseOverlay}:enable='$enable'$opts$next;")
                label = next
            }
            val fc = sb.toString().trimEnd(';')
            val withAudio = FFmpegCommandBuilder.hasAudioStream(input.absolutePath)
            val audioOpts = if (withAudio) "-map 0:a -c:a copy" else "-an"
            val cmd = "$inputArgs-filter_complex \"$fc\" -map \"[vout]\" -c:v mpeg4 -q:v 3 $audioOpts \"${out.absolutePath}\" -y"
            val ovResult = FfmpegExecutor.executeSync(cmd)
            return if (FfmpegExecutor.isSuccess(ovResult) && out.exists() && out.length() > 0) out
            else {
                Log.e(TAG, "overlay failed: ${ovResult.exceptionOrNull()?.message}")
                input
            }
        } finally {
            pngs.forEach { try { it.delete() } catch (_: Exception) {} }
        }
    }

    private suspend fun materializeLocalPath(uri: Uri): String? = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") return@withContext uri.path
        try {
            val ext = app.contentResolver.getType(uri)
                ?.substringAfterLast('/')
                ?.let { if (it == "mpeg" || it == "quicktime") "mp4" else it }
                ?: "mp4"
            val key = uri.toString().replace(Regex("[^a-zA-Z0-9]"), "_").takeLast(60)
            val tmp = File(app.cacheDir, "ffin_$key.$ext")
            if (!tmp.exists() || tmp.length() == 0L) {
                app.contentResolver.openInputStream(uri)?.use { inp ->
                    tmp.outputStream().use { out -> inp.copyTo(out) }
                }
            }
            EditorCacheUtils.enforceFfinCacheBudget(app.cacheDir)
            if (tmp.exists() && tmp.length() > 0L) tmp.absolutePath else null
        } catch (e: Exception) {
            Log.e(TAG, "materializeLocalPath: $uri", e)
            null
        }
    }

    private fun getOutputFile(prefix: String, ext: String = "mp4"): File {
        val dir = app.getExternalFilesDir(null) ?: app.cacheDir
        dir.mkdirs()
        return File(dir, "${prefix}_${System.currentTimeMillis()}.$ext")
    }

    private suspend fun getClipDurationMs(uri: Uri): Long = withContext(Dispatchers.IO) {
        try {
            val r = MediaMetadataRetriever()
            when (uri.scheme) {
                "file" -> r.setDataSource(uri.path)
                else -> r.setDataSource(app, uri)
            }
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            r.release()
            d
        } catch (_: Exception) {
            0L
        }
    }

    private fun saveToMediaStore(file: File): Uri? {
        val mimeType = if (file.extension == "mp3") "audio/mpeg" else "video/mp4"
        val isAudio = mimeType.startsWith("audio")
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (isAudio) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            if (isAudio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        return try {
            val uri = app.contentResolver.insert(collection, values) ?: return null
            app.contentResolver.openOutputStream(uri)?.use { os -> file.inputStream().use { it.copyTo(os) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            uri
        } catch (e: Exception) {
            Log.e(TAG, "saveToMediaStore", e)
            null
        }
    }

    private fun fmtMs(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.getDefault(), "%02d:%02d", s / 60, s % 60)
    }

    companion object {
        private const val TAG = "VideoExportOrchestrator"
    }
}
