package com.DM.VideoEditor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

// ── Visual Tools ──────────────────────────────────────────────

@SuppressLint("InflateParams")
internal fun VideoEditingActivity.showCropSheet() {
    val act = this
    val d = BottomSheetDialog(act)
    val v = layoutInflater.inflate(R.layout.crop_bottom_sheet_dialog, null)
    v.findViewById<FrameLayout>(R.id.frameAspectRatio1).setOnClickListener { d.dismiss(); cropVideo("16:9") }
    v.findViewById<FrameLayout>(R.id.frameAspectRatio2).setOnClickListener { d.dismiss(); cropVideo("9:16") }
    v.findViewById<FrameLayout>(R.id.frameAspectRatio3).setOnClickListener { d.dismiss(); cropVideo("1:1") }
    v.findViewById<Button>(R.id.btnCancelCrop).setOnClickListener { d.dismiss() }
    d.setContentView(v); d.show()
}

internal fun VideoEditingActivity.cropVideo(ratio: String) {
    val act = this
    val clip = clips.getOrNull(selectedClipIndex) ?: return
    val baseline = clip.copy()
    val f = when (ratio) {
        "16:9" -> "crop=iw:iw*9/16"
        "9:16" -> "crop=ih*9/16:ih"
        "1:1" -> "crop=min(iw\\,ih):min(iw\\,ih)"
        else -> return
    }
    execFfmpegClipReplace(baseline, "cropped") { i, o -> "-i \"$i\" -vf \"$f\" -c:a copy \"$o\" -y" }
}

// ── ROTATE / REVERSE VIDEO ──────────────────────────────
@SuppressLint("InflateParams")
internal fun VideoEditingActivity.showRotateSheet() {
    val act = this
    val options = arrayOf(
        "↻ 90° باتجاه عقارب الساعة",
        "↺ 90° عكس عقارب الساعة",
        "🔄 180° (قلب)",
        "⇄ مرآة أفقية (Mirror)",
        " عكس الفيديو (Reverse)"
    )
    MaterialAlertDialogBuilder(act)
        .setTitle("🔄 تدوير / عكس الفيديو")
        .setItems(options) { _, w ->
            when (w) {
                0 -> rotateVideo("transpose=1")
                1 -> rotateVideo("transpose=2")
                2 -> rotateVideo("transpose=2,transpose=2")
                3 -> rotateVideo("hflip")
                4 -> reverseVideo()
            }
        }.show()
}

internal fun VideoEditingActivity.rotateVideo(vfFilter: String) {
    val act = this
    val clip = clips.getOrNull(selectedClipIndex) ?: return
    execFfmpegClipReplace(clip.copy(), "rotated") { i, o -> "-i \"$i\" -vf \"$vfFilter\" -c:a copy \"$o\" -y" }
}

internal fun VideoEditingActivity.reverseVideo() {
    val act = this
    val clip = clips.getOrNull(selectedClipIndex) ?: return
    showSnack(getString(R.string.snack_reversing_video))
    execFfmpegClipReplace(clip.copy(), "reversed") { i, o -> "-i \"$i\" -vf reverse -af areverse \"$o\" -y" }
}

// ── FILTERS ──────────────────────────────────────────────
internal fun VideoEditingActivity.showFilterSheet() {
    val clip = clips.getOrNull(selectedClipIndex) ?: return
    val d = BottomSheetDialog(this)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(editorColor(R.color.colorSheetBackground))
        setPadding(32, 32, 32, 60)
    }

    TextView(this).apply {
        text = "🎬 فلاتر سينمائية (Cinematic Filters)"
        textSize = 18f; setTextColor(Color.WHITE); setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding(0, 0, 0, 16)
    }.also { root.addView(it) }

    val rv = RecyclerView(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, this@showFilterSheet.run { 360.dp })
        layoutManager = GridLayoutManager(this@showFilterSheet, 3)
    }

    val presets = ColorPreset.getAll()
    rv.adapter = FilterSelectorAdapter(presets, clip.filterCmd) { p ->
        applyFilter(p.filter ?: "")
        showSnack("✓ تم تطبيق فلتر: ${p.label}")
    }
    root.addView(rv)

    MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = getString(R.string.btn_apply_to_all)
        setTextColor(editorColor(R.color.colorAccentOrange))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 }
        setOnClickListener {
            val currentFilter = clips[selectedClipIndex].filterCmd
            val backups = clips.map { it.filterCmd }
            clips.forEach { it.filterCmd = currentFilter }
            clipAdapter.notifyDataSetChanged()
            undoRedo.commit(
                undo = {
                    backups.forEachIndexed { i, s -> clips[i].filterCmd = s }
                    clipAdapter.notifyDataSetChanged()
                },
                redo = {
                    clips.forEach { it.filterCmd = currentFilter }
                    clipAdapter.notifyDataSetChanged()
                }
            )
            d.dismiss()
            showSnack(getString(R.string.snack_applied_to_all))
        }
    }.also { root.addView(it) }

    d.setContentView(root); d.show()
}

internal fun VideoEditingActivity.applyFilter(f: String) {
    val act = this
    val clip = clips.getOrNull(selectedClipIndex) ?: return
    execFfmpegClipReplace(clip.copy(), "filtered") { i, o -> "-i \"$i\" -vf \"$f\" -c:a copy \"$o\" -y" }
}

// ── COLOR ADJUST ──────────────────────────────────────────────
@SuppressLint("InflateParams")
internal fun VideoEditingActivity.showColorAdjustSheet() {
    val act = this
    val d = BottomSheetDialog(act)
    val sv = ScrollView(act)
    val root = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(editorColor(R.color.colorSheetBackground))
        setPadding(24, 24, 24, 60)
    }
    sv.addView(root)

    TextView(act).apply {
        text = "🎛 Color Adjust"; textSize = 18f; setTextColor(Color.WHITE)
        setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 20)
    }.also { root.addView(it) }

    data class AdjParam(val name: String, val emoji: String, val min: Float, val max: Float, val default: Float, val key: String)
    val params = listOf(
        AdjParam("Brightness",  "☀️",  -0.5f, 0.5f,  0f,   "brightness"),
        AdjParam("Contrast",    "◑",   0.5f,  2f,    1f,   "contrast"),
        AdjParam("Saturation",  "🌈",  0f,    2f,    1f,   "saturation"),
        AdjParam("Highlights",  "🔆",  -0.5f, 0.5f,  0f,   "highlights"),
        AdjParam("Shadows",     "🌘",  -0.5f, 0.5f,  0f,   "shadows"),
        AdjParam("Sharpen",     "🔪",  0f,    2f,    0f,   "sharpen"),
        AdjParam("Temperature", "🌡",  -0.3f, 0.3f,  0f,   "temperature")
    )
    val sliderMap = mutableMapOf<String, Slider>()

    params.forEach { p ->
        LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, 8, 0, 8)
            LinearLayout(act).apply rowH@{
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(act).apply {
                    text = "${p.emoji} ${p.name}"; textSize = 13f; setTextColor(editorColor(R.color.colorTextSoft))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                val tvVal = TextView(act).apply {
                    text = String.format("%.2f", p.default); textSize = 12f; setTextColor(editorColor(R.color.colorAccentOrange))
                }
                addView(tvVal)
                Slider(act).apply {
                    valueFrom = p.min; valueTo = p.max; value = p.default; stepSize = 0.01f
                    setLabelFormatter { String.format("%.2f", it) }
                    addOnChangeListener { _, v, _ -> tvVal.text = String.format("%.2f", v) }
                }.also { sl -> sliderMap[p.key] = sl; this@rowH.addView(sl) }
            }.also { addView(it) }
        }.also { root.addView(it) }
    }

    MaterialButton(act).apply {
        text = "✓ Apply"; setBackgroundColor(editorColor(R.color.colorAccentOrange)); setTextColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 }
        setOnClickListener {
            val br = sliderMap["brightness"]?.value ?: 0f
            val co = sliderMap["contrast"]?.value ?: 1f
            val sa = sliderMap["saturation"]?.value ?: 1f
            val sh = sliderMap["sharpen"]?.value ?: 0f
            val te = sliderMap["temperature"]?.value ?: 0f
            val filters = mutableListOf("eq=brightness=$br:contrast=$co:saturation=$sa")
            if (sh > 0.01f) filters.add("unsharp=5:5:${sh * 2f}:5:5:0")
            if (te != 0f) filters.add("colorbalance=rs=$te:gs=${te * 0.3f}:bs=${-te}")
            d.dismiss(); applyFilter(filters.joinToString(","))
        }
    }.also { root.addView(it) }

    d.setContentView(sv); d.show()
}

internal fun VideoEditingActivity.showSingleAdjust(param: String) {
    val act = this
    val d = BottomSheetDialog(act)
    val root = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(editorColor(R.color.colorSheetBackground))
        setPadding(32, 32, 32, 60); gravity = Gravity.CENTER
    }
    val adjTitle: String; val tMin: Float; val tMax: Float; val tDef: Float
    when (param) {
        "brightness"  -> { adjTitle = "☀️ السطوع";     tMin = -0.5f; tMax = 0.5f;  tDef = 0f  }
        "contrast"    -> { adjTitle = "◑ التباين";     tMin = 0.5f;  tMax = 2f;    tDef = 1f  }
        "saturation"  -> { adjTitle = "🌈 التشبع";     tMin = 0f;    tMax = 2f;    tDef = 1f  }
        "highlights"  -> { adjTitle = "🔆 الإضاءة";   tMin = -0.5f; tMax = 0.5f;  tDef = 0f  }
        "shadows"     -> { adjTitle = "🌘 الظلال";    tMin = -0.5f; tMax = 0.5f;  tDef = 0f  }
        "sharpen"     -> { adjTitle = "🔪 الحدة";     tMin = 0f;    tMax = 2f;    tDef = 0f  }
        "temperature" -> { adjTitle = "🌡 الحرارة";   tMin = -0.3f; tMax = 0.3f;  tDef = 0f  }
        else          -> { adjTitle = param;            tMin = -1f;   tMax = 1f;    tDef = 0f  }
    }

    TextView(act).apply { text = adjTitle; textSize = 18f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0, 0, 0, 16) }.also { root.addView(it) }
    val tvVal = TextView(act).apply { text = String.format("%.2f", tDef); textSize = 32f; setTextColor(editorColor(R.color.colorAccentOrange)); gravity = Gravity.CENTER }.also { root.addView(it) }
    val sl = Slider(act).apply {
        valueFrom = tMin; valueTo = tMax; value = tDef; stepSize = 0.01f
        addOnChangeListener { _, v, _ -> tvVal.text = String.format("%.2f", v) }
    }.also { root.addView(it) }

    MaterialButton(act).apply {
        text = "✓ Apply"; setBackgroundColor(editorColor(R.color.colorAccentOrange)); setTextColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 }
        setOnClickListener {
            val v = sl.value
            val filter = when (param) {
                "brightness"  -> "eq=brightness=$v"
                "contrast"    -> "eq=contrast=$v"
                "saturation"  -> "eq=saturation=$v"
                "highlights"  -> "curves=highlights=${0.5f + v}"
                "shadows"     -> "curves=shadows=${0.5f + v}"
                "sharpen"     -> "unsharp=5:5:$v:5:5:0"
                "temperature" -> "colorbalance=rs=$v:gs=${v * 0.3f}:bs=${-v}"
                else          -> return@setOnClickListener
            }
            d.dismiss(); applyFilter(filter)
        }
    }.also { root.addView(it) }

    d.setContentView(root); d.show()
}

// ── OPACITY ──────────────────────────────────────────────
internal fun VideoEditingActivity.showOpacitySheet() {
    val act = this
    val d = BottomSheetDialog(act)
    val root = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(editorColor(R.color.colorSheetBackground))
        setPadding(32, 32, 32, 60); gravity = Gravity.CENTER
    }
    TextView(act).apply { text = "👁 Opacity"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0, 0, 0, 16) }.also { root.addView(it) }
    val tvPct = TextView(act).apply { text = "100%"; textSize = 32f; setTextColor(editorColor(R.color.colorAccentOrange)); gravity = Gravity.CENTER }.also { root.addView(it) }
    val sb = SeekBar(act).apply {
        max = 100; progress = 100
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { tvPct.text = "$p%" }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }.also { root.addView(it) }
    MaterialButton(act).apply {
        text = "✓ Apply"; setBackgroundColor(editorColor(R.color.colorAccentOrange)); setTextColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 }
        setOnClickListener {
            val opacity = sb.progress / 100f
            val clip = clips.getOrNull(selectedClipIndex) ?: return@setOnClickListener
            val baseline = clip.copy()
            d.dismiss()
            val alphaFilter = if (opacity >= 1f) "format=yuv420p" else "colorchannelmixer=aa=$opacity"
            execFfmpegClipReplace(baseline, "opacity") { i, o -> "-i \"$i\" -vf \"$alphaFilter\" -c:a copy \"$o\" -y" }
        }
    }.also { root.addView(it) }
    d.setContentView(root); d.show()
}

// ── FREEZE FRAME ──────────────────────────────────────────────
internal fun VideoEditingActivity.showFreezeFrameSheet() {
    val act = this
    val currentMs = player.currentPosition
    if (currentMs <= 0) { showSnack(getString(R.string.snack_navigate_to_frame_first)); return }
    val d = BottomSheetDialog(act)
    val root = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(editorColor(R.color.colorSheetBackground))
        setPadding(32, 32, 32, 60)
    }
    TextView(act).apply { text = "⏸ Freeze Frame"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 8) }.also { root.addView(it) }
    TextView(act).apply { text = "Insert a still pause at ${fmtMs(currentMs)}"; textSize = 12f; setTextColor(editorColor(R.color.colorSubtleText)); setPadding(0, 0, 0, 20) }.also { root.addView(it) }
    val tvDur = TextView(act).apply { text = "2.0s"; textSize = 28f; setTextColor(editorColor(R.color.colorAccentOrange)); gravity = Gravity.CENTER }.also { root.addView(it) }
    val sl = Slider(act).apply {
        valueFrom = 0.5f; valueTo = 10f; value = 2f; stepSize = 0.5f
        setLabelFormatter { "${it}s" }
        addOnChangeListener { _, v, _ -> tvDur.text = "${v}s" }
    }.also { root.addView(it) }
    MaterialButton(act).apply {
        text = "⏸ Insert Freeze Frame"; setBackgroundColor(editorColor(R.color.colorAccentOrange)); setTextColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 }
        setOnClickListener { d.dismiss(); insertFreezeFrame(currentMs, sl.value) }
    }.also { root.addView(it) }
    d.setContentView(root); d.show()
}

internal fun VideoEditingActivity.insertFreezeFrame(atMs: Long, durationSec: Float) {
    val act = this
    val clip = clips.getOrNull(selectedClipIndex) ?: return
    lifecycleScope.launch {
        showLoading(true)
        val input = withContext(Dispatchers.IO) { materializeLocalPath(clip.uri) }
        if (input == null) {
            showLoading(false); showSnack(getString(R.string.snack_cannot_read_file)); return@launch
        }
        withContext(Dispatchers.IO) {
            val frameImg = getOutputFile("freeze_img", "png")
            val fr1 = FfmpegExecutor.executeSync("-ss ${atMs / 1000.0} -i \"$input\" -vframes 1 -q:v 2 \"${frameImg.absolutePath}\" -y")
            if (!FfmpegExecutor.isSuccess(fr1) || !frameImg.exists()) {
                withContext(Dispatchers.Main) { showLoading(false); showSnack(getString(R.string.snack_freeze_failed_extract)) }
                return@withContext
            }
            val freezeVid = getOutputFile("freeze_vid")
            val fr2 = FfmpegExecutor.executeSync("-loop 1 -i \"${frameImg.absolutePath}\" -c:v mpeg4 -t $durationSec -pix_fmt yuv420p -q:v 3 \"${freezeVid.absolutePath}\" -y")
            val dur = if (FfmpegExecutor.isSuccess(fr2) && freezeVid.exists() && freezeVid.length() > 0)
                getClipDurationMs(Uri.fromFile(freezeVid)) else 0L
            withContext(Dispatchers.Main) {
                showLoading(false)
                if (dur > 0L) {
                    clipAdapter.shiftStripThumbnailsRight(selectedClipIndex + 1)
                    clips.add(selectedClipIndex + 1, VideoClip(uri = Uri.fromFile(freezeVid), durationMs = dur))
                    clipAdapter.notifyDataSetChanged()
                    loadThumb(selectedClipIndex + 1)
                    updateClipCountBadge(); rebuildTimeline(); loadThumbsForTimeline()
                    showSnack(getString(R.string.snack_freeze_inserted, durationSec.toString(), fmtMs(atMs)))
                } else showSnack(getString(R.string.snack_freeze_create_failed))
            }
        }
    }
}

// ── CANVAS BACKGROUND ──────────────────────────────────────────────
internal fun VideoEditingActivity.showCanvasSheet() {
    val act = this
    val d = BottomSheetDialog(act)
    val root = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(editorColor(R.color.colorSheetBackground))
        setPadding(24, 24, 24, 60)
    }
    TextView(act).apply { text = "🖼 Canvas Background"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 8) }.also { root.addView(it) }
    TextView(act).apply { text = "Add a colored or blurred background behind your video"; textSize = 12f; setTextColor(editorColor(R.color.colorSubtleText)); setPadding(0, 0, 0, 16) }.also { root.addView(it) }

    // label, icon, color (null=blur), isBlur
    val optsLabel  = listOf("Black",    "White",    "Dark Gray", "Dark Blue", "Purple",    "Blurred")
    val optsIcon   = listOf("⬛",        "⬜",        "🔲",        "🟦",        "🟣",        "🌫")
    val optsColor  = listOf("#000000",  "#FFFFFF",  "#222222",   "#0A0A2A",   "#4A0070",   null)
    val optsBlur   = listOf(false,      false,      false,       false,       false,       true)
    val grid = GridLayout(this@showCanvasSheet).apply {
        columnCount = 3
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16 }
    }
    for (oi in optsLabel.indices) {
        LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = GridLayout.LayoutParams().apply {
                this.width = 0; this.height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(6, 6, 6, 6)
            }
            setPadding(12, 16, 12, 16); setBackgroundColor(editorColor(R.color.colorCardDark))
            isClickable = true; isFocusable = true
            addView(TextView(act).apply { text = optsIcon[oi]; textSize = 28f; gravity = Gravity.CENTER })
            addView(TextView(act).apply { text = optsLabel[oi]; textSize = 11f; setTextColor(editorColor(R.color.colorTextMuted)); gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 6 } })
            setOnClickListener { d.dismiss(); if (optsBlur[oi]) applyBlurBackground() else optsColor[oi]?.let { applyColorBackground(it) } }
        }.also { grid.addView(it) }
    }
    root.addView(grid)

    MaterialButton(act, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = "🎨 Custom Color"; setTextColor(editorColor(R.color.colorAccentOrange))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        setOnClickListener {
            d.dismiss()
            val colors = arrayOf("Red #FF0000","Green #00FF00","Blue #0000FF","Yellow #FFFF00","Orange #FF8C00","Pink #FF69B4","Teal #008080","Brown #8B4513")
            val hexes  = arrayOf("#FF0000","#00FF00","#0000FF","#FFFF00","#FF8C00","#FF69B4","#008080","#8B4513")
            MaterialAlertDialogBuilder(act).setTitle("Choose Background Color")
                .setItems(colors) { _, i -> applyColorBackground(hexes[i]) }.show()
        }
    }.also { root.addView(it) }
    d.setContentView(root); d.show()
}

internal fun VideoEditingActivity.applyColorBackground(colorHex: String) {
    val act = this
    val clip = clips.getOrNull(selectedClipIndex) ?: return
    val baseline = clip.copy()
    val r = colorHex.removePrefix("#")
    execFfmpegClipReplace(baseline, "canvas") { i, o -> "-i \"$i\" -vf \"pad=iw:ih:0:0:color=0x${r}\" -c:a copy \"$o\" -y" }
}

internal fun VideoEditingActivity.applyBlurBackground() {
    val act = this
    val clip = clips.getOrNull(selectedClipIndex) ?: return
    val baseline = clip.copy()
    execFfmpegClipReplace(baseline, "blur_canvas") { i, o ->
        "-i \"$i\" -filter_complex \"[0:v]split=2[bg][fg];[bg]scale=ih*9/16:ih,boxblur=20:5[blurred];[blurred][fg]overlay=(W-w)/2:(H-h)/2\" -c:a copy \"$o\" -y"
    }
}

// ── VOICEOVER ──────────────────────────────────────────────
internal fun VideoEditingActivity.showVoiceoverSheet() {
    val act = this
    if (ContextCompat.checkSelfPermission(act, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
        showSnack(getString(R.string.snack_microphone_permission_needed))
        return
    }
    val d = BottomSheetDialog(act)
    val root = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(editorColor(R.color.colorSheetBackground))
        setPadding(32, 32, 32, 60); gravity = Gravity.CENTER
    }
    TextView(act).apply { text = "🎤 Voiceover"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0, 0, 0, 8) }.also { root.addView(it) }
    TextView(act).apply { text = "Record audio to overlay on your video"; textSize = 12f; setTextColor(editorColor(R.color.colorSubtleText)); gravity = Gravity.CENTER; setPadding(0, 0, 0, 24) }.also { root.addView(it) }
    val tvStatus = TextView(act).apply { text = "● Ready to Record"; textSize = 16f; setTextColor(editorColor(R.color.colorSubtleText)); gravity = Gravity.CENTER; setPadding(0, 0, 0, 16) }.also { root.addView(it) }
    val btnRecord = MaterialButton(act).apply {
        text = "🎤 Start Recording"; setBackgroundColor(editorColor(R.color.colorAccentOrange)); setTextColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }.also { root.addView(it) }
    val btnApply = MaterialButton(act).apply {
        text = "✓ Add to Video"; setBackgroundColor(editorColor(R.color.colorSuccess)); setTextColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 12 }
        isEnabled = false; alpha = 0.5f
    }.also { root.addView(it) }
    btnRecord.setOnClickListener {
        if (!isRecording) {
            try {
                voiceoverFile = getOutputFile("voiceover", "m4a")
                @Suppress("DEPRECATION")
                mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    MediaRecorder(act) else MediaRecorder()).apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(44100); setAudioEncodingBitRate(128000)
                    setOutputFile(voiceoverFile?.absolutePath)
                    prepare(); start()
                }
                isRecording = true
                btnRecord.text = "⏸ Stop Recording"; btnRecord.setBackgroundColor(editorColor(R.color.colorError))
                tvStatus.text = "🔴 Recording..."; tvStatus.setTextColor(editorColor(R.color.colorError))
                player.play()
            } catch (e: Exception) { showSnack(getString(R.string.snack_recording_start_failed, e.message ?: "")) }
        } else {
            try {
                mediaRecorder?.apply { stop(); release() }; mediaRecorder = null; isRecording = false
                player.pause()
                btnRecord.text = getString(R.string.start_recording); btnRecord.setBackgroundColor(editorColor(R.color.colorAccentOrange))
                tvStatus.text = getString(R.string.snack_recording_saved); tvStatus.setTextColor(editorColor(R.color.colorSuccess))
                btnApply.isEnabled = true; btnApply.alpha = 1f
            } catch (e: Exception) { showSnack(getString(R.string.snack_recording_stop_failed, e.message ?: "")) }
        }
    }
    btnApply.setOnClickListener {
        val vf = voiceoverFile ?: return@setOnClickListener
        if (!vf.exists()) { showSnack(getString(R.string.snack_recording_not_found)); return@setOnClickListener }
        d.dismiss(); addAudioTrack(Uri.fromFile(vf)); showSnack(getString(R.string.snack_voiceover_added))
    }
    d.setOnDismissListener {
        if (isRecording) try { mediaRecorder?.apply { stop(); release() }; mediaRecorder = null; isRecording = false } catch (_: Exception) {}
    }
    d.setContentView(root); d.show()
}

// ── PIP ──────────────────────────────────────────────
internal fun VideoEditingActivity.showPipSheet() {
    val act = this
    MaterialAlertDialogBuilder(act).setTitle("📱 Picture-in-Picture")
        .setItems(arrayOf("↗ Top Right", "↖ Top Left", "↘ Bottom Right", "â†™ Bottom Left", "⊕ Center")) { _, pos ->
            pipPosition = pos; pipVideoLauncher.launch("video/*")
        }.show()
}

internal fun VideoEditingActivity.showPipPositionPicker(overlayUri: Uri) {
    val act = this
    val d = BottomSheetDialog(act)
    val root = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(editorColor(R.color.colorSheetBackground)); setPadding(32, 32, 32, 60)
    }
    TextView(act).apply { text = "📱 PIP Settings"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 16) }.also { root.addView(it) }
    val tvSize = TextView(act).apply { text = "Overlay size: 30%"; textSize = 13f; setTextColor(editorColor(R.color.colorTextSoft)); setPadding(0, 0, 0, 4) }.also { root.addView(it) }
    val sl = Slider(act).apply {
        valueFrom = 15f; valueTo = 60f; value = 30f; stepSize = 5f
        setLabelFormatter { "${it.toInt()}%" }
        addOnChangeListener { _, v, _ -> tvSize.text = "Overlay size: ${v.toInt()}%" }
    }.also { root.addView(it) }
    MaterialButton(act).apply {
        text = "✓ Apply PIP"; setBackgroundColor(editorColor(R.color.colorAccentOrange)); setTextColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 }
        setOnClickListener { d.dismiss(); applyPip(overlayUri, sl.value.toInt() / 100f, pipPosition) }
    }.also { root.addView(it) }
    d.setContentView(root); d.show()
}

internal fun VideoEditingActivity.applyPip(overlayUri: Uri, sizeFraction: Float, position: Int) {
    val act = this
    val clip = clips.getOrNull(selectedClipIndex) ?: return
    val baseline = clip.copy()
    val idx = selectedClipIndex
    val scaleS = "scale=iw*${sizeFraction}:-1"
    val pos = when (position) { 0 -> "W-w-16:16"; 1 -> "16:16"; 2 -> "W-w-16:H-h-16"; 3 -> "16:H-h-16"; else -> "(W-w)/2:(H-h)/2" }
    showSnack(getString(R.string.snack_applying_pip))
    lifecycleScope.launch {
        showLoading(true)
        try {
            val mainIn = withContext(Dispatchers.IO) { materializeLocalPath(clip.uri) }
                ?: return@launch showSnack(getString(R.string.snack_cannot_read_main_video))
            val ovIn = withContext(Dispatchers.IO) { materializeLocalPath(overlayUri) }
                ?: return@launch showSnack(getString(R.string.snack_cannot_read_overlay_video))
            val out = getOutputFile("pip")
            val cmd = "-i \"$mainIn\" -i \"$ovIn\" -filter_complex \"[1:v]${scaleS}[ovr];[0:v][ovr]overlay=${pos}:shortest=1\" -c:a copy \"${out.absolutePath}\" -y"
            val pipResult = withContext(Dispatchers.IO) { FfmpegExecutor.executeSync(cmd) }
            if (!FfmpegExecutor.isSuccess(pipResult) || !out.exists() || out.length() == 0L) {
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

// ── PAN & ZOOM ──────────────────────────────────────────────
internal fun VideoEditingActivity.showPanZoomSheet() {
    val act = this
    val d = BottomSheetDialog(act)
    val sv = ScrollView(act)
    val root = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(editorColor(R.color.colorSheetBackground)); setPadding(24, 24, 24, 60)
    }
    sv.addView(root)
    TextView(act).apply { text = "🔎 Pan & Zoom (Ken Burns)"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 8) }.also { root.addView(it) }
    TextView(act).apply { text = "Animates zoom & pan across your clip"; textSize = 12f; setTextColor(editorColor(R.color.colorSubtleText)); setPadding(0, 0, 0, 20) }.also { root.addView(it) }

    data class ZPreset(val label: String, val desc: String, val filter: String)
    listOf(
        ZPreset("🔍 Zoom In",         "Center zoom in",       "zoompan=z='min(zoom+0.0015,1.5)':d=1:x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)',scale=1280:720"),
        ZPreset("🔎 Zoom Out",        "Center zoom out",      "zoompan=z='if(lte(zoom,1.0),1.5,max(1.001,zoom-0.0015))':d=1:x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)',scale=1280:720"),
        ZPreset("↗ Zoom + Pan Right", "Zoom & pan right",     "zoompan=z='min(zoom+0.001,1.4)':d=1:x='if(gte(zoom,1.4),iw/4,0)':y='ih/2-(ih/zoom/2)',scale=1280:720"),
        ZPreset("↖ Zoom + Pan Left",  "Zoom & pan left",      "zoompan=z='min(zoom+0.001,1.4)':d=1:x='if(gte(zoom,1.4),iw*3/4,iw/2)':y='ih/2-(ih/zoom/2)',scale=1280:720"),
        ZPreset("↓ Pan Down",         "Slow pan downward",    "zoompan=z='1.2':d=1:x='iw/2-(iw/zoom/2)':y='min(on*2,ih/5)',scale=1280:720"),
        ZPreset("↑ Pan Up",           "Slow pan upward",      "zoompan=z='1.2':d=1:x='iw/2-(iw/zoom/2)':y='max(0,ih/5-on*2)',scale=1280:720")
    ).forEach { preset ->
        LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(editorColor(R.color.colorCardDark))
            setPadding(16, 14, 16, 14)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8 }
            isClickable = true; isFocusable = true
            LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(act).apply { text = preset.label; textSize = 14f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD) })
                addView(TextView(act).apply { text = preset.desc; textSize = 12f; setTextColor(editorColor(R.color.colorSubtleText)) })
            }.also { addView(it) }
            addView(TextView(act).apply { text = "›"; textSize = 22f; setTextColor(editorColor(R.color.colorAccentOrange)) })
            setOnClickListener { d.dismiss(); applyFilter(preset.filter); showSnack(getString(R.string.snack_applying_pan_zoom, preset.label)) }
        }.also { root.addView(it) }
    }
    d.setContentView(sv); d.show()
}

// ── NOISE REDUCTION ──────────────────────────────────────────────
internal fun VideoEditingActivity.showNoiseReduceSheet() {
    val act = this
    val d = BottomSheetDialog(act)
    val sv = ScrollView(act)
    val root = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(editorColor(R.color.colorSheetBackground)); setPadding(24, 24, 24, 60)
    }
    sv.addView(root)
    TextView(act).apply { text = "🔈 Noise Reduction"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 16) }.also { root.addView(it) }

    data class NR(val label: String, val desc: String, val filter: String)
    listOf(
        NR("Light Denoise",    "Subtle noise removal",           "hqdn3d=2:1:2:3"),
        NR("Medium Denoise",   "Balanced noise removal",         "hqdn3d=4:3:6:4.5"),
        NR("Heavy Denoise",    "Aggressive noise removal",       "hqdn3d=9:5:9:6"),
        NR("Smooth + Denoise", "Smooth texture & denoise",       "hqdn3d=4:3:6:4.5,unsharp=3:3:1:3:3:0"),
        NR("Deshake",          "Reduce hand-held camera shake",  "deshake=x=-1:y=-1:w=-1:h=-1:rx=64:ry=64")
    ).forEach { opt ->
        LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(editorColor(R.color.colorCardDark))
            setPadding(16, 14, 16, 14)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8 }
            isClickable = true; isFocusable = true
            addView(TextView(act).apply { text = opt.label; textSize = 14f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD) })
            addView(TextView(act).apply { text = opt.desc; textSize = 12f; setTextColor(editorColor(R.color.colorSubtleText)); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 4 } })
            setOnClickListener { d.dismiss(); applyFilter(opt.filter); showSnack(getString(R.string.snack_applying_effect, opt.label)) }
        }.also { root.addView(it) }
    }
    d.setContentView(sv); d.show()
}

// ── STABILIZE ──────────────────────────────────────────────
internal fun VideoEditingActivity.showStabilizeSheet() {
    val act = this
    MaterialAlertDialogBuilder(act).setTitle("📷 تثبيت الصورة المتقدم (Advanced Stabilization)")
        .setMessage("يقدم وضع التثبيت المتقدم نتائج أفضل لتقليل اهتزاز الكاميرا بشكل طبيعي.\n\n" + getString(R.string.stabilize_description))
        .setPositiveButton("تثبيت ذكي (Smart)") { _, _ ->
            // Use vidstabdetect + vidstabtransform equivalent via deshake for immediate single-pass results
            // but with professional parameters.
            applyFilter("deshake=edge=mirror:blocksize=16:contrast=125:search=64")
            showSnack(getString(R.string.snack_stabilizing_video))
        }
        .setNeutralButton("تثبيت سريع (Fast)") { _, _ ->
            applyFilter("deshake=x=-1:y=-1:w=-1:h=-1:rx=64:ry=64")
            showSnack(getString(R.string.snack_stabilizing_video))
        }
        .setNegativeButton(R.string.cancel) { d, _ -> d.dismiss() }
        .show()
}

// ── DUPLICATE CLIP ──────────────────────────────────────────────
internal fun VideoEditingActivity.duplicateClip() {
    val act = this
    val clip = clips.getOrNull(selectedClipIndex) ?: return
    val ins = selectedClipIndex + 1
    clipAdapter.shiftStripThumbnailsRight(ins)
    clips.add(ins, clip.copy())
    clipAdapter.notifyDataSetChanged()
    loadThumb(ins)
    updateClipCountBadge()
    rebuildTimeline()
    showSnack(getString(R.string.snack_clip_duplicated))
}

// ── REPLACE CLIP ──────────────────────────────────────────────
internal fun VideoEditingActivity.showReplaceClipPicker() {
    val act = this
    MaterialAlertDialogBuilder(act).setTitle(getString(R.string.replace_clip_dialog_title, selectedClipIndex + 1))
        .setMessage(R.string.replace_clip_dialog_message)
        .setPositiveButton(R.string.btn_choose_video) { _, _ -> replaceClipLauncher.launch("video/*") }
        .setNegativeButton(R.string.cancel) { d, _ -> d.dismiss() }
        .show()
}

internal fun VideoEditingActivity.replaceCurrentClip(newUri: Uri) {
    val act = this
    val old = clips.getOrNull(selectedClipIndex) ?: return
    val replaceIdx = selectedClipIndex
    recycleTimelineThumbnail(replaceIdx)
    lifecycleScope.launch {
        val dur = withContext(Dispatchers.IO) { getClipDurationMs(newUri) }
        val newClip = old.copy(uri = newUri, durationMs = dur, startTrimMs = 0L, endTrimMs = 0L)
        withContext(Dispatchers.Main) {
            undoRedo.register(
                undo = {
                    clips[replaceIdx] = old
                    clipAdapter.notifyItemChanged(replaceIdx)
                    thumbnailCache.remove(replaceIdx)
                    playClip(replaceIdx)
                    rebuildTimeline()
                    loadThumbsForTimeline()
                },
                redo = {
                    clips[replaceIdx] = newClip
                    clipAdapter.notifyItemChanged(replaceIdx)
                    thumbnailCache.remove(replaceIdx)
                    playClip(replaceIdx)
                    rebuildTimeline()
                    loadThumbsForTimeline()
                }
            )
            clips[replaceIdx] = newClip
            clipAdapter.notifyItemChanged(replaceIdx)
            loadThumb(replaceIdx)
            playClip(replaceIdx)
            rebuildTimeline()
            loadThumbsForTimeline()
            showSnack(getString(R.string.snack_clip_replaced))
        }
    }
}

// ── EFFECT PRESETS ──────────────────────────────────────────────
internal fun VideoEditingActivity.applyEffectPreset(name: String) {
    val act = this
    val filter = when (name) {
        "glow"      -> "eq=saturation=1.5,unsharp=5:5:1.5:5:5:0"
        "retro"     -> "colorbalance=rs=0.08:gs=-0.02:bs=-0.12,eq=contrast=1.05:saturation=0.85,vignette=PI/4"
        "cinematic" -> "eq=contrast=1.3:saturation=0.8,vignette=PI/5,colorbalance=rs=0.05:bs=-0.05"
        "blur"      -> "boxblur=5:1"
        else        -> return
    }
    applyFilter(filter); showSnack(getString(R.string.snack_applying_effect, name))
}

internal fun VideoEditingActivity.showChromaKeySheet() {
    val idx = selectedClipIndex
    val clip = clips.getOrNull(idx) ?: return
    val d = BottomSheetDialog(this)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(editorColor(R.color.colorSheetBackground))
        setPadding(32, 32, 32, 60)
    }

    TextView(this).apply {
        text = "💚 " + getString(R.string.chroma_key); textSize = 18f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
        setPadding(0, 0, 0, 16)
    }.also { root.addView(it) }

    // Key Color Selection
    TextView(this).apply { text = getString(R.string.key_color); textSize = 12f; setTextColor(editorColor(R.color.colorTextMuted)) }.also { root.addView(it) }
    val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 8, 0, 16) }
    val colors = mapOf("Green" to "00FF00", "Blue" to "0000FF", "Red" to "FF0000")
    var selectedColor = clip.chromaKeyColor ?: "00FF00"

    colors.forEach { (name, hex) ->
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = name; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { selectedColor = hex; showSnack("Selected: $name") }
        }.also { colorRow.addView(it) }
    }
    root.addView(colorRow)

    // Similarity Slider
    TextView(this).apply { text = getString(R.string.similarity); textSize = 12f; setTextColor(editorColor(R.color.colorTextMuted)) }.also { root.addView(it) }
    val slSim = Slider(this).apply { valueFrom = 0.01f; valueTo = 1.0f; stepSize = 0.01f; value = clip.chromaSimilarity }
    root.addView(slSim)

    // Smoothness Slider
    TextView(this).apply { text = getString(R.string.smoothness); textSize = 12f; setTextColor(editorColor(R.color.colorTextMuted)) }.also { root.addView(it) }
    val slSmooth = Slider(this).apply { valueFrom = 0.01f; valueTo = 0.5f; stepSize = 0.01f; value = clip.chromaSmoothness }
    root.addView(slSmooth)

    MaterialButton(this).apply {
        text = "✓ Apply Chroma Key"; setBackgroundColor(editorColor(R.color.colorAccentOrange)); setTextColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 24 }
        setOnClickListener {
            val old = clip.copy()
            clip.chromaKeyColor = selectedColor
            clip.chromaSimilarity = slSim.value
            clip.chromaSmoothness = slSmooth.value
            val new = clip.copy()

            undoRedo.register(
                undo = { clips[idx] = old; clipAdapter.notifyItemChanged(idx); rebuildTimeline() },
                redo = { clips[idx] = new; clipAdapter.notifyItemChanged(idx); rebuildTimeline() }
            )

            d.dismiss()
            applyChromaKeyFilter(idx)
        }
    }.also { root.addView(it) }

    d.setContentView(root); d.show()
}

internal fun VideoEditingActivity.applyChromaKeyFilter(idx: Int) {
    val clip = clips.getOrNull(idx) ?: return
    val color = clip.chromaKeyColor ?: "00FF00"
    val sim = clip.chromaSimilarity
    val smooth = clip.chromaSmoothness

    // Chroma key followed by a default background (black) to show transparency effect
    val filter = "chromakey=0x${color}:${sim}:${smooth}"
    applyFilter(filter)
}

internal fun VideoEditingActivity.showOverlaySheet() {
    val clip = clips.getOrNull(selectedClipIndex) ?: return
    val d = BottomSheetDialog(this)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(editorColor(R.color.colorSheetBackground))
        setPadding(32, 32, 32, 60)
    }

    TextView(this).apply {
        text = "🎬 تأثيرات التراكب (Video Overlays)"
        textSize = 18f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
        setPadding(0, 0, 0, 16)
    }.also { root.addView(it) }

    val rv = RecyclerView(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, this@showOverlaySheet.run { 320.dp })
        layoutManager = GridLayoutManager(this@showOverlaySheet, 3)
    }

    val effects = OverlayEffect.getAll()
    rv.adapter = OverlaySelectorAdapter(effects, clip.overlayEffect) { e ->
        clip.overlayEffect = e.filter
        applyOverlayEffect(selectedClipIndex)
        showSnack("✓ تم تطبيق: ${e.label}")
    }
    root.addView(rv)

    MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = getString(R.string.btn_apply_to_all)
        setTextColor(editorColor(R.color.colorAccentOrange))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 }
        setOnClickListener {
            val currentEff = clip.overlayEffect
            val backups = clips.map { it.overlayEffect }
            clips.forEach { it.overlayEffect = currentEff }
            clipAdapter.notifyDataSetChanged()
            undoRedo.commit(
                undo = { backups.forEachIndexed { i, s -> clips[i].overlayEffect = s }; clipAdapter.notifyDataSetChanged() },
                redo = { clips.forEach { it.overlayEffect = currentEff }; clipAdapter.notifyDataSetChanged() }
            )
            d.dismiss()
            showSnack(getString(R.string.snack_applied_to_all))
        }
    }.also { root.addView(it) }

    d.setContentView(root); d.show()
}

internal fun VideoEditingActivity.applyOverlayEffect(idx: Int) {
    val clip = clips.getOrNull(idx) ?: return
    val filter = clip.overlayEffect ?: ""
    applyFilter(filter) // Re-uses the existing filter application logic
}

internal fun VideoEditingActivity.showSplitScreenSheet() {
    val d = BottomSheetDialog(this)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(editorColor(R.color.colorSheetBackground))
        setPadding(32, 32, 32, 60)
    }
    TextView(this).apply { text = "🔲 " + getString(R.string.split_screen); textSize = 18f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 20) }.also { root.addView(it) }

    fun addOption(label: String, icon: String, mode: Int) {
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 16, 16, 16); setBackgroundColor(editorColor(R.color.colorCardDark))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8 }
            isClickable = true; isFocusable = true
            addView(TextView(this@showSplitScreenSheet).apply { text = icon; textSize = 24f; setPadding(0, 0, 16, 0) })
            addView(TextView(this@showSplitScreenSheet).apply { text = label; textSize = 16f; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            setOnClickListener { d.dismiss(); splitScreenMode = mode; splitScreenPicker.launch("video/*") }
        }.also { root.addView(it) }
    }

    addOption(getString(R.string.split_side_by_side), "🌓", 1)
    addOption(getString(R.string.split_top_bottom), "➗", 2)
    addOption(getString(R.string.split_quad), "田", 3)

    d.setContentView(root); d.show()
}

internal fun VideoEditingActivity.applySplitScreen(uris: List<Uri>) {
    if (uris.isEmpty()) return
    val idx = selectedClipIndex
    val clip = clips.getOrNull(idx) ?: return
    val baseline = clip.copy()

    lifecycleScope.launch {
        showLoading(true)
        try {
            val mainIn = withContext(Dispatchers.IO) { materializeLocalPath(clip.uri) } ?: return@launch
            val secondIn = withContext(Dispatchers.IO) { materializeLocalPath(uris[0]) } ?: return@launch
            val out = getOutputFile("split_screen")

            // FFmpeg Split Screen Logic
            val filter = when (splitScreenMode) {
                1 -> "[0:v]scale=iw/2:ih[v0];[1:v]scale=iw/2:ih[v1];[v0][v1]hstack=inputs=2"
                2 -> "[0:v]scale=iw:ih/2[v0];[1:v]scale=iw:ih/2[v1];[v0][v1]vstack=inputs=2"
                3 -> {
                    val thirdIn = if (uris.size > 1) withContext(Dispatchers.IO) { materializeLocalPath(uris[1]) } else mainIn
                    val fourthIn = if (uris.size > 2) withContext(Dispatchers.IO) { materializeLocalPath(uris[2]) } else mainIn
                    "-i \"$thirdIn\" -i \"$fourthIn\" -filter_complex \"[0:v]scale=iw/2:ih/2[v0];[1:v]scale=iw/2:ih/2[v1];[2:v]scale=iw/2:ih/2[v2];[3:v]scale=iw/2:ih/2[v3];[v0][v1][v2][v3]xstack=inputs=4:layout=0_0|w0_0|0_h0|w0_h0\""
                }
                else -> ""
            }

            val cmd = if (splitScreenMode == 3) {
                 "-i \"$mainIn\" -i \"$secondIn\" $filter -c:a copy \"${out.absolutePath}\" -y"
            } else {
                 "-i \"$mainIn\" -i \"$secondIn\" -filter_complex \"$filter\" -c:a copy \"${out.absolutePath}\" -y"
            }

            val result = withContext(Dispatchers.IO) { FfmpegExecutor.executeSync(cmd) }
            if (FfmpegExecutor.isSuccess(result)) {
                val newUri = Uri.fromFile(out)
                val dur = withContext(Dispatchers.IO) { getClipDurationMs(newUri) }
                val newClip = baseline.copy(uri = newUri, durationMs = dur, startTrimMs = 0L, endTrimMs = 0L)
                withContext(Dispatchers.Main) {
                    clips[idx] = newClip
                    clipAdapter.notifyItemChanged(idx)
                    reloadPlaylist(maintainPosition = true, targetIdx = idx)
                    rebuildTimeline(); loadThumb(idx)
                    showSnack("✓ Split screen applied")
                }
            }
        } finally {
            showLoading(false)
        }
    }
}
