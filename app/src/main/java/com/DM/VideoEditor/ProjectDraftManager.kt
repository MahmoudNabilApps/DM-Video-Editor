package com.DM.VideoEditor

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles auto-save and restore of editing projects (Drafts).
 * Format: JSON files stored in app's private files dir → "drafts/"
 *
 * Each draft contains:
 *   - id         : unique timestamp string
 *   - name       : human-readable name (e.g. "Draft — 03 Apr 16:45")
 *   - savedAt    : ISO timestamp
 *   - clips      : list of { uri, startTrimMs, endTrimMs, speedFactor, volume, filterCmd, transition }
 *   - textOverlays: list of overlay objects
 *   - projectAudioUri : optional background music URI
 */
object ProjectDraftManager {

    private const val DRAFTS_DIR = "drafts"
    private val DATE_FMT = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())

    // ─── File helpers ─────────────────────────────────────────────────────────

    private fun draftsDir(ctx: Context): File =
        File(ctx.filesDir, DRAFTS_DIR).also { it.mkdirs() }

    private fun draftFile(ctx: Context, id: String): File =
        File(draftsDir(ctx), "$id.json")

    // ─── Save ─────────────────────────────────────────────────────────────────

    /**
     * Save the current project state.
     * Returns the draft id so the caller can update their "currentDraftId".
     */
    fun save(
        ctx: Context,
        draftId: String?,                     // null → create new draft
        clips: List<VideoClip>,
        textOverlays: List<TextOverlay>,
        projectAudioUri: Uri?
    ): String {
        val id = draftId ?: System.currentTimeMillis().toString()
        val name = "مشروع — ${DATE_FMT.format(Date())}"

        val clipsArr = JSONArray()
        for (c in clips) {
            clipsArr.put(JSONObject().apply {
                put("uri",          c.uri.toString())
                put("durationMs",   c.durationMs)
                put("startTrimMs",  c.startTrimMs)
                put("endTrimMs",    c.endTrimMs)
                put("speedFactor",  c.speedFactor.toDouble())
                put("volume",       c.volume.toDouble())
                put("filterCmd",    c.filterCmd)
                put("transition",   c.transition)
            })
        }

        val overlaysArr = JSONArray()
        for (o in textOverlays) {
            overlaysArr.put(JSONObject().apply {
                put("id",           o.id)
                put("text",         o.text)
                put("fontSize",     o.fontSize)
                put("color",        o.color)
                put("position",     o.position)
                put("startSec",     o.startSec.toDouble())
                put("endSec",       o.endSec.toDouble())
                put("bold",         o.bold)
                put("italic",       o.italic)
                put("bgAlpha",      o.bgAlpha.toDouble())
                put("shadow",       o.shadow)
                put("normalizedX",  o.normalizedX.toDouble())
                put("normalizedY",  o.normalizedY.toDouble())
                put("textScale",    o.textScale.toDouble())
                put("textRotation", o.textRotation.toDouble())
                put("animationType", o.animationType)
            })
        }

        val root = JSONObject().apply {
            put("id",               id)
            put("name",             name)
            put("savedAt",          System.currentTimeMillis())
            put("clips",            clipsArr)
            put("textOverlays",     overlaysArr)
            put("projectAudioUri",  projectAudioUri?.toString() ?: "")
        }

        draftFile(ctx, id).writeText(root.toString(2))
        return id
    }

    // ─── Load ─────────────────────────────────────────────────────────────────

    data class DraftData(
        val id: String,
        val name: String,
        val savedAt: Long,
        val clips: List<VideoClip>,
        val textOverlays: List<TextOverlay>,
        val projectAudioUri: Uri?
    )

    fun load(ctx: Context, id: String): DraftData? {
        val file = draftFile(ctx, id)
        if (!file.exists()) return null
        return try {
            parse(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    // ─── List all drafts ──────────────────────────────────────────────────────

    data class DraftSummary(val id: String, val name: String, val savedAt: Long, val clipCount: Int)

    fun listDrafts(ctx: Context): List<DraftSummary> {
        return draftsDir(ctx)
            .listFiles { f -> f.extension == "json" }
            .orEmpty()
            .mapNotNull { file ->
                try {
                    val json = JSONObject(file.readText())
                    DraftSummary(
                        id       = json.getString("id"),
                        name     = json.getString("name"),
                        savedAt  = json.getLong("savedAt"),
                        clipCount = json.getJSONArray("clips").length()
                    )
                } catch (_: Exception) { null }
            }
            .sortedByDescending { it.savedAt }
    }

    // ─── Delete ───────────────────────────────────────────────────────────────

    fun delete(ctx: Context, id: String) {
        draftFile(ctx, id).delete()
    }

    // ─── Internal parser ──────────────────────────────────────────────────────

    private fun parse(json: String): DraftData {
        val root = JSONObject(json)
        val clipsArr = root.getJSONArray("clips")
        val overlaysArr = root.getJSONArray("textOverlays")

        val clips = (0 until clipsArr.length()).map { i ->
            val c = clipsArr.getJSONObject(i)
            VideoClip(
                uri          = Uri.parse(c.getString("uri")),
                durationMs   = c.optLong("durationMs", 0L),
                startTrimMs  = c.getLong("startTrimMs"),
                endTrimMs    = c.getLong("endTrimMs"),
                speedFactor  = c.getDouble("speedFactor").toFloat(),
                volume       = c.getDouble("volume").toFloat(),
                filterCmd    = c.optString("filterCmd", ""),
                transition   = c.optString("transition", "none")
            )
        }

        val overlays = (0 until overlaysArr.length()).map { i ->
            val o = overlaysArr.getJSONObject(i)
            TextOverlay(
                id           = o.getLong("id"),
                text         = o.getString("text"),
                fontSize     = o.getInt("fontSize"),
                color        = o.getString("color"),
                position     = o.optString("position", "Bottom Center"),
                startSec     = o.getDouble("startSec").toFloat(),
                endSec       = o.getDouble("endSec").toFloat(),
                bold         = o.getBoolean("bold"),
                italic       = o.getBoolean("italic"),
                bgAlpha      = o.getDouble("bgAlpha").toFloat(),
                shadow       = o.getBoolean("shadow"),
                normalizedX  = o.getDouble("normalizedX").toFloat(),
                normalizedY  = o.getDouble("normalizedY").toFloat(),
                textScale    = o.getDouble("textScale").toFloat(),
                textRotation = o.getDouble("textRotation").toFloat(),
                animationType = o.optString("animationType", "none")
            )
        }

        val audioUriStr = root.optString("projectAudioUri", "")
        val audioUri = if (audioUriStr.isNotBlank()) Uri.parse(audioUriStr) else null

        return DraftData(
            id              = root.getString("id"),
            name            = root.getString("name"),
            savedAt         = root.getLong("savedAt"),
            clips           = clips,
            textOverlays    = overlays,
            projectAudioUri = audioUri
        )
    }
}
