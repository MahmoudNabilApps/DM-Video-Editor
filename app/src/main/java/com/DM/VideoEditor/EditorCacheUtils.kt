package com.DM.VideoEditor

import java.io.File

object EditorCacheUtils {

    private const val CACHE_PREFIX_IN = "ffin_"
    private const val MAX_CACHE_BYTES = 500L * 1024L * 1024L
    private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L

    private val intermediatePrefixes = arrayOf(
        "proc_", "split_a_", "split_b_", "trimmed_", "filtered_", "with_text_",
        "merged_", "merged_trans", "opacity_", "cropped_", "speed_", "rotated_", "reversed_",
        "vol_", "muted_", "audio_mixed_", "compat_", "DM_Video_",
        "text_overlay_", "concat_", "split_src_", "freeze_img_", "freeze_vid_",
        "audio_", "pip_", "canvas_", "blur_canvas_", "voiceover_", "zoom_"
    )

    fun cleanOldFfinFiles(dir: File, maxAgeMs: Long = MAX_AGE_MS) {
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith(CACHE_PREFIX_IN) && now - f.lastModified() > maxAgeMs) {
                try { f.delete() } catch (_: Exception) {}
            }
        }
    }

    fun enforceFfinCacheBudget(dir: File, maxBytes: Long = MAX_CACHE_BYTES) {
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(CACHE_PREFIX_IN) }
            ?.sortedBy { it.lastModified() }
            ?: return
        var total = files.sumOf { it.length() }
        var i = 0
        while (total > maxBytes && i < files.size) {
            val f = files[i++]
            val len = f.length()
            if (try { f.delete() } catch (_: Exception) { false }) total -= len
        }
    }

    fun deleteIntermediateFiles(dir: File) {
        dir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val name = f.name
            val match = name.startsWith(CACHE_PREFIX_IN) ||
                intermediatePrefixes.any { prefix -> name.startsWith(prefix) }
            if (match) try { f.delete() } catch (_: Exception) {}
        }
    }
}
