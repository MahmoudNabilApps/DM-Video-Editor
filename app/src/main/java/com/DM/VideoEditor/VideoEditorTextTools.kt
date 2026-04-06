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
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.graphics.drawable.ColorDrawable
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.chip.ChipGroup
import com.google.android.material.chip.Chip
import android.text.InputType
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

internal fun VideoEditingActivity.updateTextPreview(currentSec: Float) {
        val act = this
        val container = binding.textPreviewContainer

        // Rebuild when overlays list changes (version mismatch)
        if (container.tag != overlaysVersion) {
            container.removeAllViews()
            container.tag = overlaysVersion

            textOverlays.forEachIndexed { idx, overlay ->
                val dragView = com.DM.VideoEditor.customviews.DraggableTextView(
                    context = this,
                    onPositionChanged = { nx, ny, sc, rot ->
                        overlay.normalizedX  = nx
                        overlay.normalizedY  = ny
                        overlay.textScale    = sc
                        overlay.textRotation = rot
                    },
                    onDoubleTap = { showTextSheet(idx) },
                    onSelected  = { dv ->
                        selectedDraggable?.isOverlaySelected = false
                        selectedDraggable = dv
                        dv.isOverlaySelected = true
                    }
                )
                dragView.textView.apply {
                    text = overlay.text
                    textSize = (overlay.fontSize * 0.38f).coerceIn(10f, 48f)
                    setTextColor(overlayColorToInt(overlay.color))
                    val style = when {
                        overlay.bold && overlay.italic -> Typeface.BOLD_ITALIC
                        overlay.bold   -> Typeface.BOLD
                        overlay.italic -> Typeface.ITALIC
                        else           -> Typeface.NORMAL
                    }
                    setTypeface(null, style)
                    if (overlay.shadow) setShadowLayer(4f, 2f, 2f, Color.BLACK)
                    if (overlay.bgAlpha > 0.01f) {
                        background = ColorDrawable(Color.argb((overlay.bgAlpha * 255).toInt(), 0, 0, 0))
                        val pd = (8 * resources.displayMetrics.density).toInt()
                        val pdV = (3 * resources.displayMetrics.density).toInt()
                        setPadding(pd, pdV, pd, pdV)
                    }
                }
                dragView.textScale    = overlay.textScale
                dragView.textRotation = overlay.textRotation
                dragView.normalizedX  = overlay.normalizedX
                dragView.normalizedY  = overlay.normalizedY

                container.addView(dragView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ))
                // Position after measure
                dragView.post { dragView.placeInContainer(container.width, container.height) }
            }
        }

        // Update visibility based on current time
        for (i in 0 until container.childCount) {
            val child   = container.getChildAt(i) as? com.DM.VideoEditor.customviews.DraggableTextView ?: continue
            val overlay = textOverlays.getOrNull(i) ?: continue
            val visible = currentSec >= overlay.startSec && (overlay.endSec < 0 || currentSec <= overlay.endSec)
            child.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

internal fun VideoEditingActivity.showTextSheet(editIdx: Int?) {
        val act = this
        FfmpegExecutor.setFontDirectory(this, "/system/fonts", null)
        val existing = editIdx?.let { textOverlays.getOrNull(it) }
        val d = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.text_bottom_sheet_dialog_v2, null)
        val etText  = v.findViewById<TextInputEditText>(R.id.etTextInput)
        val etSize  = v.findViewById<TextInputEditText>(R.id.fontSize)
        val spinner = v.findViewById<Spinner>(R.id.spinnerTextPosition)
        val etStart = v.findViewById<TextInputEditText>(R.id.etTextStart)
        val etEnd   = v.findViewById<TextInputEditText>(R.id.etTextEnd)
        val cgColor = v.findViewById<ChipGroup>(R.id.chipGroupColor)
        v.findViewById<TextView>(R.id.tvSheetTitle).text = if (editIdx != null) getString(R.string.text_sheet_title_edit) else getString(R.string.text_sheet_title_add)

        val positions = arrayOf("Bottom Center", "Center", "Top Center", "Top Left", "Top Right", "Bottom Left", "Bottom Right")
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, positions)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        existing?.let {
            etText.setText(it.text)
            etSize.setText(it.fontSize.toString())
            spinner.setSelection(positions.indexOf(it.position).coerceAtLeast(0))
            etStart.setText(it.startSec.toString())
            if (it.endSec >= 0) etEnd.setText(it.endSec.toString())
        } ?: spinner.setSelection(0)

        var selColor  = existing?.color  ?: "white"
        var isBold    = existing?.bold   ?: false
        var isItalic  = existing?.italic ?: false
        var isShadow  = existing?.shadow ?: true
        var bgAlpha   = existing?.bgAlpha ?: 0.4f

        // Color chips
        mapOf(
            "white" to "#FFFFFF", "yellow" to "#FFD700", "red" to "#FF4444",
            "cyan"  to "#00FFFF", "green"  to "#00FF88", "orange" to "#FF8C00",
            "pink"  to "#FF69B4", "black"  to "#111111", "blue"   to "#4488FF",
            "purple" to "#CC44FF"
        ).forEach { (name, hex) ->
            Chip(this).apply {
                text = name; isCheckable = true
                setChipBackgroundColorResource(android.R.color.transparent)
                setTextColor(Color.parseColor(hex))
                if (name == selColor) isChecked = true
                setOnClickListener { selColor = name }
            }.also { cgColor?.addView(it) }
        }

        // Style toggles: Bold / Italic / Shadow / Background
        val styleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * resources.displayMetrics.density).toInt() }
        }
        val toggleSymbols = listOf("B",     "I",     "S",   "BG")
        val toggleLabels  = listOf("عريض",  "مائل",  "ظل",  "خلفية")
        val toggleSetters: List<(Boolean) -> Unit> = listOf(
            { v2 -> isBold   = v2 },
            { v2 -> isItalic = v2 },
            { v2 -> isShadow = v2 },
            { v2 -> bgAlpha  = if (v2) 0.4f else 0f }
        )
        val initStates = listOf(isBold, isItalic, isShadow, bgAlpha > 0.01f)
        for (i in toggleSymbols.indices) {
            com.google.android.material.button.MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = "${toggleSymbols[i]}\n${toggleLabels[i]}"
                textSize = 10f
                isCheckable = true
                isChecked = initStates[i]
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = (4 * resources.displayMetrics.density).toInt() }
                val setter = toggleSetters[i]
                addOnCheckedChangeListener { _, checked -> setter(checked) }
            }.also { styleContainer.addView(it) }
        }
        // Insert style row before the Done button
        val parentLayout = v.findViewById<android.widget.Button>(R.id.btnDoneText).parent as? ViewGroup
        parentLayout?.addView(styleContainer, parentLayout.indexOfChild(v.findViewById(R.id.btnDoneText)))

        // Current-time shortcut
        val btnTime = MaterialButton(this).apply {
            text = getString(R.string.btn_set_start_now, fmtMs(player.currentPosition))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() }
            setOnClickListener {
                etStart.setText((player.currentPosition / 1000f).toString())
                text = getString(R.string.btn_start_set)
            }
        }
        parentLayout?.addView(btnTime, parentLayout.indexOfChild(v.findViewById(R.id.btnDoneText)))

        v.findViewById<android.widget.Button>(R.id.btnDoneText).setOnClickListener {
            val txt = etText.text.toString().ifBlank { showSnack(getString(R.string.snack_enter_text_first)); return@setOnClickListener }
            val sz  = etSize.text.toString().toIntOrNull() ?: 36
            val pos = spinner.selectedItem.toString()
            val st  = etStart.text.toString().toFloatOrNull() ?: 0f
            val en  = etEnd.text.toString().toFloatOrNull()   ?: -1f
            val ov  = TextOverlay(
                id        = existing?.id ?: System.currentTimeMillis(),
                text      = txt, fontSize = sz, color = selColor, position = pos,
                startSec  = st, endSec   = en,
                bold      = isBold, italic = isItalic, shadow = isShadow, bgAlpha = bgAlpha,
                normalizedX  = existing?.normalizedX  ?: 0.5f,
                normalizedY  = existing?.normalizedY  ?: 0.8f,
                textScale    = existing?.textScale    ?: 1.0f,
                textRotation = existing?.textRotation ?: 0f
            )
            if (editIdx != null) {
                val ei = editIdx
                val oldOv = textOverlays[ei]
                textOverlays[ei] = ov
                textAdapter.notifyItemChanged(ei); bumpOverlaysVersion(); updateTextBadge(); rebuildTimeline()
                undoRedo.commit(
                    undo = {
                        textOverlays[ei] = oldOv
                        textAdapter.notifyItemChanged(ei); bumpOverlaysVersion(); updateTextBadge(); rebuildTimeline()
                    },
                    redo = {
                        textOverlays[ei] = ov
                        textAdapter.notifyItemChanged(ei); bumpOverlaysVersion(); updateTextBadge(); rebuildTimeline()
                    }
                )
                showSnack(getString(R.string.snack_text_updated))
            } else {
                textOverlays.add(ov)
                textAdapter.notifyItemInserted(textOverlays.lastIndex)
                bumpOverlaysVersion(); updateTextBadge(); rebuildTimeline()
                undoRedo.commit(
                    undo = {
                        if (textOverlays.isNotEmpty()) {
                            val rm = textOverlays.lastIndex
                            textOverlays.removeAt(rm)
                            textAdapter.notifyItemRemoved(rm)
                        }
                        bumpOverlaysVersion(); updateTextBadge(); rebuildTimeline()
                    },
                    redo = {
                        textOverlays.add(ov)
                        textAdapter.notifyItemInserted(textOverlays.lastIndex)
                        bumpOverlaysVersion(); updateTextBadge(); rebuildTimeline()
                    }
                )
                showSnack(getString(R.string.snack_text_added_timeline))
            }
            d.dismiss()
        }
        d.setContentView(v); d.show()
    }

internal fun VideoEditingActivity.showLyricsDialog() {
        val act = this
        val d = BottomSheetDialog(this)
        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 60)
            setBackgroundColor(editorColor(R.color.colorEditorBackground))
        }
        fun addText(text: String, size: Int, color: String, bold: Boolean = false) = TextView(this).apply {
            this.text = text; textSize = size.toFloat(); setTextColor(Color.parseColor(color))
            if (bold) setTypeface(null, Typeface.BOLD)
        }.also { container.addView(it) }
        fun addSpacer(dp: Int = 12) = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (dp * resources.displayMetrics.density).toInt())
        }.also { container.addView(it) }

        addText("🎵 إضافة كلمات / ترجمة", 18, "#FFFFFF", true)
        addSpacer(4)
        addText("كل سطر = نص منفصل يظهر تلقائياً في وقته", 12, "#AAAAAA")
        addSpacer(16)

        addText("📝 الكلمات (سطر بسطر):", 13, "#AAAAAA")
        addSpacer(4)
        val etLyrics = EditText(this).apply {
            hint = "الكلمة الأولى\nالكلمة الثانية\nالكلمة الثالثة..."
            minLines = 6; gravity = Gravity.TOP or Gravity.START
            setTextColor(Color.WHITE); setHintTextColor(editorColor(R.color.colorTextHint))
            setBackgroundColor(editorColor(R.color.colorSurface2))
            setPadding(16, 12, 16, 12)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }.also { container.addView(it) }

        addSpacer()
        addText("⏱ مدة كل سطر (ثانية):", 13, "#AAAAAA")
        addSpacer(4)
        val etInterval = EditText(this).apply {
            setText("3"); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(Color.WHITE); setBackgroundColor(editorColor(R.color.colorSurface2))
            setPadding(16, 12, 16, 12)
        }.also { container.addView(it) }

        addSpacer()
        addText("🕐 وقت البداية (ثانية):", 13, "#AAAAAA")
        addSpacer(4)
        val etStartTime = EditText(this).apply {
            setText("0"); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(Color.WHITE); setBackgroundColor(editorColor(R.color.colorSurface2))
            setPadding(16, 12, 16, 12)
        }.also { container.addView(it) }

        addSpacer()
        addText("📐 حجم الخط:", 13, "#AAAAAA")
        addSpacer(4)
        val etFontSize = EditText(this).apply {
            setText("40"); inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE); setBackgroundColor(editorColor(R.color.colorSurface2))
            setPadding(16, 12, 16, 12)
        }.also { container.addView(it) }

        addSpacer()
        addText("📍 موضع الكلمات:", 13, "#AAAAAA")
        addSpacer(4)
        val positions = arrayOf("Bottom Center", "Center", "Top Center")
        val spinnerPos = Spinner(this).apply {
            adapter = ArrayAdapter(act, android.R.layout.simple_spinner_item, positions)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }.also { container.addView(it) }

        addSpacer()
        // Color selection
        addText("🎨 لون النص:", 13, "#AAAAAA")
        addSpacer(4)
        var lyricsColor = "white"
        val colorContainer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mapOf("white" to "#FFFFFF", "yellow" to "#FFD700", "cyan" to "#00FFFF", "orange" to "#FF8C00").forEach { (name, hex) ->
            Button(this).apply {
                text = name; setTextColor(Color.parseColor(hex)); background = null; textSize = 12f
                setOnClickListener { lyricsColor = name; showSnack(getString(R.string.snack_color_selected, name)) }
            }.also { colorContainer.addView(it) }
        }
        container.addView(colorContainer)

        addSpacer()
        // Quick load current time button
        Button(this).apply {
            text = getString(R.string.btn_use_current_time, fmtMs(player.currentPosition))
            textSize = 12f
            setOnClickListener { etStartTime.setText((player.currentPosition / 1000f).toString()) }
        }.also { container.addView(it) }

        addSpacer()
        MaterialButton(this).apply {
            text = "✓ إضافة الكلمات للفيديو"; textSize = 15f
            setBackgroundColor(editorColor(R.color.colorPurpleAccent))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val interval = etInterval.text.toString().toFloatOrNull() ?: 3f
                val startOffset = etStartTime.text.toString().toFloatOrNull() ?: 0f
                val lines = etLyrics.text.toString().split("\n").filter { it.isNotBlank() }
                val fontSize = etFontSize.text.toString().toIntOrNull() ?: 40
                val posText = spinnerPos.selectedItem.toString()

                if (lines.isEmpty()) { showSnack(getString(R.string.snack_enter_lyrics_first)); return@setOnClickListener }

                val addedCount = lines.size
                val snapshots = lines.mapIndexed { i, line ->
                    val startSec = startOffset + i * interval
                    val endSec = startSec + interval - 0.2f
                    TextOverlay(
                        text = line.trim(),
                        fontSize = fontSize,
                        color = lyricsColor,
                        position = posText,
                        startSec = startSec,
                        endSec = endSec,
                        bold = true,
                        shadow = true
                    )
                }
                snapshots.forEach { textOverlays.add(it) }
                textAdapter.notifyDataSetChanged()
                bumpOverlaysVersion(); updateTextBadge(); rebuildTimeline()
                undoRedo.commit(
                    undo = {
                        repeat(addedCount) { textOverlays.removeLastOrNull() }
                        textAdapter.notifyDataSetChanged()
                        bumpOverlaysVersion(); updateTextBadge(); rebuildTimeline()
                    },
                    redo = {
                        snapshots.forEach { textOverlays.add(it) }
                        textAdapter.notifyDataSetChanged()
                        bumpOverlaysVersion(); updateTextBadge(); rebuildTimeline()
                    }
                )
                showSnack(getString(R.string.snack_lyrics_added, lines.size))
                d.dismiss()
            }
        }.also { container.addView(it) }

        scrollView.addView(container)
        d.setContentView(scrollView)
        d.show()
    }

internal fun VideoEditingActivity.toggleTextPanel() {
        val act = this
        // text panel is now the multi-track timeline text track; show snack for guidance
        showSnack(getString(R.string.snack_timeline_text_hint))
    }

internal fun VideoEditingActivity.updateTextBadge() {
        val act = this
        binding.tvTextBadge.text = if (textOverlays.isEmpty()) "" else textOverlays.size.toString()
        binding.tvTextBadge.visibility = if (textOverlays.isEmpty()) View.GONE else View.VISIBLE
    }

internal fun VideoEditingActivity.overlayColorToInt(color: String): Int = try {
        Color.parseColor(when (color) {
            "white"  -> "#FFFFFF"; "yellow" -> "#FFD700"; "red"    -> "#FF4444"
            "cyan"   -> "#00FFFF"; "green"  -> "#00FF88"; "orange" -> "#FF8C00"
            "pink"   -> "#FF69B4"; "black"  -> "#111111"; "blue"   -> "#4488FF"
            "purple" -> "#CC44FF"; else -> "#FFFFFF"
        })
    } catch (_: Exception) { Color.WHITE }

