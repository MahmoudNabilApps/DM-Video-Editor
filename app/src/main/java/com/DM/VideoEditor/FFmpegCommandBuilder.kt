package com.DM.VideoEditor

import android.media.MediaMetadataRetriever
import java.io.File
import java.util.Locale

/**
 * All FFmpeg command construction lives here.
 * This keeps VideoEditingActivity clean and all processing logic in one testable place.
 */
object FFmpegCommandBuilder {

    /** True when the file has an audio stream (safe for `-map 0:a`). */
    fun hasAudioStream(filePath: String): Boolean {
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(filePath)
            val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            r.release()
            hasAudio == "yes"
        } catch (_: Exception) {
            false
        }
    }

    // ── atempo chain (handles speeds outside 0.5–2.0 per filter; FFmpeg accepts [0.5, 100]) ──
    fun atempoChain(speed: Float): String {
        val filters = mutableListOf<String>()
        var remaining = speed.coerceIn(0.05f, 8.0f)
        if (remaining < 1.0f) {
            while (remaining < 0.5f) {
                filters.add("atempo=0.5")
                remaining /= 0.5f
            }
            filters.add("atempo=$remaining")
        } else {
            while (remaining > 2.0f) {
                filters.add("atempo=2.0")
                remaining /= 2.0f
            }
            filters.add("atempo=$remaining")
        }
        return filters.joinToString(",")
    }

    // ── FFmpeg xfade code mapping ─────────────────────────────
    fun xfadeCode(transition: String): String = when (transition) {
        "fade"        -> "fade"
        "slide_left"  -> "slideleft"
        "slide_right" -> "slideright"
        "dissolve"    -> "dissolve"
        "zoom"        -> "zoom"
        "wipe"        -> "wipeleft"
        "circleopen"  -> "circleopen"
        "wipeup"      -> "wipeup"
        "wipedown"    -> "wipedown"
        "pixelize"    -> "pixelize"
        "radial"      -> "radial"
        "squeezev"    -> "squeezev"
        "smoothstep"  -> "smoothstep"
        else          -> "fade"
    }

    /**
     * Build a process command for a single clip:
     *   - Optional trim
     *   - Optional speed change
     *   - Optional video filter (hue, colorbalance, eq, etc.)
     *   - Optional volume adjustment
     *   - Normalize to target resolution and pixel format (required for clean merging)
     *
     * @param targetW  Project target width  (e.g. 1280)
     * @param targetH  Project target height (e.g. 720)
     * @param hasAudio When false, never reference `[0:a]` (muted / silent inputs).
     * @param padColor Hex color for letter-box padding (default: blurred — handled separately)
     */
    fun buildProcessClipCmd(
        inputPath: String,
        outputPath: String,
        clip: VideoClip,
        targetW: Int,
        targetH: Int,
        hasAudio: Boolean = true,
        padColor: String = "black"
    ): String {
        val hasTrim   = clip.endTrimMs > 0
        val hasSpeed  = clip.speedFactor != 1f
        val hasFilter = clip.filterCmd.isNotBlank()
        val hasVolume = clip.volume != 1f

        val trimArgs = if (hasTrim) "-ss ${clip.startTrimMs / 1000.0} -to ${clip.endTrimMs / 1000.0}" else ""

        // Video filter chain — always includes scale+pad for uniform resolution
        val scaleFilter = "scale=${targetW}:${targetH}:force_original_aspect_ratio=decrease," +
                          "pad=${targetW}:${targetH}:(ow-iw)/2:(oh-ih)/2:color=${padColor}," +
                          "setsar=1,fps=30,format=yuv420p"

        val vfParts = mutableListOf<String>()
        if (hasSpeed) vfParts.add("setpts=${1f / clip.speedFactor}*PTS")
        if (hasFilter) vfParts.add(clip.filterCmd)
        vfParts.add(scaleFilter)   // always last

        val afParts = mutableListOf<String>()
        if (hasSpeed) afParts.add(atempoChain(clip.speedFactor))
        if (hasVolume) afParts.add("volume=${clip.volume}")

        return when {
            afParts.isNotEmpty() && hasAudio -> {
                val vfStr = "[0:v]${vfParts.joinToString(",")}[v]"
                val afStr = "[0:a]${afParts.joinToString(",")}[a]"
                "$trimArgs -i \"$inputPath\" -filter_complex \"$vfStr;$afStr\" " +
                    "-map \"[v]\" -map \"[a]\" " +
                    "-c:v mpeg4 -q:v 3 -c:a aac -b:a 128k -ar 44100 " +
                    "-movflags +faststart \"$outputPath\" -y"
            }
            afParts.isNotEmpty() && !hasAudio -> {
                // Speed/volume requested but no audio stream — video chain only (setPTS still applies)
                val vfStr = "[0:v]${vfParts.joinToString(",")}[v]"
                "$trimArgs -i \"$inputPath\" -filter_complex \"$vfStr\" " +
                    "-map \"[v]\" -an " +
                    "-c:v mpeg4 -q:v 3 " +
                    "-movflags +faststart \"$outputPath\" -y"
            }
            else -> {
                val vfStr = vfParts.joinToString(",")
                val audioOut = if (hasAudio) "-c:a aac -b:a 128k -ar 44100" else "-an"
                "$trimArgs -i \"$inputPath\" -vf \"$vfStr\" " +
                    "-c:v mpeg4 -q:v 3 $audioOut " +
                    "-movflags +faststart \"$outputPath\" -y"
            }
        }
    }

    /**
     * Build an xfade merge command for N processed clips (all same resolution).
     * Returns the command string, or null if < 2 paths.
     */
    fun buildXfadeMergeCmd(
        paths: List<String>,
        clips: List<VideoClip>,
        outputPath: String
    ): String? {
        if (paths.size < 2) return null

        val mergeAudio = paths.any { hasAudioStream(it) }

        val inputArgs = paths.joinToString(" ") { "-i \"$it\"" }
        val durations = paths.map { p ->
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(p)
                val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toDouble()?.div(1000.0) ?: 3.0
                r.release(); d
            } catch (_: Exception) { 3.0 }
        }

        val sb = StringBuilder()
        if (mergeAudio) {
            for (i in paths.indices) {
                val durSec = String.format(Locale.US, "%.3f", durations[i])
                if (hasAudioStream(paths[i])) {
                    sb.append("[${i}:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo[ap$i];")
                } else {
                    sb.append(
                        "anullsrc=channel_layout=stereo:sample_rate=44100," +
                            "atrim=end=$durSec,asetpts=PTS-STARTPTS[ap$i];"
                    )
                }
            }
        }

        var prevV = "[0:v]"
        var prevA = if (mergeAudio) "[ap0]" else "[0:a]"
        var offset = 0.0

        for (i in 1 until paths.size) {
            val tr = xfadeCode(clips.getOrNull(i)?.transition ?: "none")
            offset += (durations[i - 1] - 1.0).coerceAtLeast(0.1)
            val isLast = i == paths.size - 1
            val vL = if (isLast) "[vout]" else "[v$i]"
            sb.append("${prevV}[$i:v]xfade=transition=$tr:duration=0.8:offset=${String.format(Locale.US, "%.3f", offset)}$vL;")
            if (mergeAudio) {
                val aL = if (isLast) "[aout]" else "[a$i]"
                sb.append("${prevA}[ap$i]acrossfade=d=0.8$aL;")
                prevA = aL
            }
            prevV = vL
        }

        return if (mergeAudio) {
            "$inputArgs -filter_complex \"${sb.trimEnd(';')}\" " +
                "-map \"[vout]\" -map \"[aout]\" " +
                "-c:v mpeg4 -q:v 3 -c:a aac -b:a 128k -ar 44100 " +
                "-movflags +faststart \"$outputPath\" -y"
        } else {
            "$inputArgs -filter_complex \"${sb.trimEnd(';')}\" " +
                "-map \"[vout]\" " +
                "-c:v mpeg4 -q:v 3 -an " +
                "-movflags +faststart \"$outputPath\" -y"
        }
    }

    /**
     * Simple concat (no transitions) — relies on all files being same resolution.
     * @param includeAudio Use false when any segment has no audio (mixed/silent project), output is video-only.
     */
    fun buildConcatCmd(concatListFile: File, outputPath: String, includeAudio: Boolean = true): String {
        val tail = if (includeAudio) {
            "-c:v mpeg4 -q:v 3 -c:a aac -b:a 128k -ar 44100 -movflags +faststart "
        } else {
            "-c:v mpeg4 -q:v 3 -an -movflags +faststart "
        }
        return "-f concat -safe 0 -protocol_whitelist file,crypto,data,saf -i \"${concatListFile.absolutePath}\" " +
            tail + "\"$outputPath\" -y"
    }

    /**
     * Concatenate segments when at least one has audio but not all do — concat demuxer would
     * drop or mis-map streams. Builds matching silent audio for silent segments via anullsrc+atrim.
     */
    fun buildConcatFilterComplexCmd(paths: List<String>, outputPath: String): String {
        require(paths.isNotEmpty())
        val inputArgs = paths.joinToString(" ") { "-i \"$it\"" }
        val durations = paths.map { p ->
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(p)
                val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toDouble()?.div(1000.0) ?: 3.0
                r.release(); d
            } catch (_: Exception) { 3.0 }
        }
        val sb = StringBuilder()
        for (i in paths.indices) {
            sb.append("[$i:v]setpts=PTS-STARTPTS[v$i];")
            val durSec = String.format(Locale.US, "%.3f", durations[i])
            if (hasAudioStream(paths[i])) {
                sb.append("[${i}:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo[a$i];")
            } else {
                sb.append(
                    "anullsrc=channel_layout=stereo:sample_rate=44100," +
                        "atrim=end=$durSec,asetpts=PTS-STARTPTS[a$i];"
                )
            }
        }
        val pairs = paths.indices.joinToString("") { j -> "[v$j][a$j]" }
        sb.append("${pairs}concat=n=${paths.size}:v=1:a=1[vout][aout]")
        val fc = sb.toString()
        return "$inputArgs -filter_complex \"$fc\" " +
            "-map \"[vout]\" -map \"[aout]\" " +
            "-c:v mpeg4 -q:v 3 -c:a aac -b:a 128k -ar 44100 " +
            "-movflags +faststart \"$outputPath\" -y"
    }

    /**
     * Legacy drawtext path — removed. Text export uses [TextOverlayBitmapRenderer] + overlay.
     */
    @Deprecated(
        message = "Use TextOverlayBitmapRenderer + FFmpeg overlay instead. " +
            "drawtext does not support Arabic shaping or text rotation.",
        level = DeprecationLevel.ERROR
    )
    fun buildDrawtextFilters(overlays: List<TextOverlay>, videoW: Int = 1280, videoH: Int = 720): String {
        throw UnsupportedOperationException("Use TextOverlayBitmapRenderer instead")
    }

    /**
     * Detect the target resolution for the project. We look at the first clip.
     * Returns Pair(width, height).
     */
    fun detectProjectResolution(firstClipPath: String): Pair<Int, Int> {
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(firstClipPath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
            r.release()
            // Round to even (required by mpeg4/x264)
            val rW = if (w % 2 == 0) w else w + 1
            val rH = if (h % 2 == 0) h else h + 1
            Pair(rW, rH)
        } catch (_: Exception) {
            Pair(1280, 720)
        }
    }
}
