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

internal fun VideoEditingActivity.showEmojiSheet() {
        val act = this
        val groups = mapOf(
            "😀 تعبيرات" to listOf("😀","😂","😍","🥰","😎","🤩","😭","😡","🤔","😴","🤣","😘","🥺","😏","🤗"),
            "❤️ قلوب" to listOf("❤️","💕","💖","💗","💓","🧡","💛","💚","💙","💜","🖤","🤍","💔","â™¥ï","💞"),
            "🎉 احتفال" to listOf("🎉","🎊","🎈","🥳","🎂","🎁","🏆","⭐","🌟","✨","🎆","🎇","🥂","🎵","🎶"),
            "🌑¿ طبيعة" to listOf("🌹","🌺","🌸","🍀","🌈","☀️","🌙","⛅","🌊","🔥","🌴","🌵","🍁","🌻","🌼"),
            "👍 إيماءات" to listOf("👍","👎","👏","🙌","🤝","💪","💙","✌️","🤞","👌","🤜","🤛","✋","🖐️","👋"),
            "🚀 متنوعة" to listOf("🚀","💎","👑","🔥","⚡","💫","🎯","🏅","🎪","🎭","🎨","🎬","📸","🎮","🎲")
        )
        val d = BottomSheetDialog(this)
        val sv = ScrollView(this)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        TextView(this).apply { text = "اختر إيموجي"; textSize = 18f; setTextColor(Color.WHITE); setPadding(0, 0, 0, 16) }.also { container.addView(it) }
        groups.forEach { (gname, emojis) ->
            TextView(this).apply { text = gname; textSize = 13f; setTextColor(editorColor(R.color.colorTextMuted)); setPadding(0, 16, 0, 8) }.also { container.addView(it) }
            val grid = GridLayout(this).apply { columnCount = 5 }
            emojis.forEach { emoji ->
                Button(this).apply {
                    text = emoji; textSize = 24f; setPadding(8, 8, 8, 8); background = null
                    setOnClickListener { d.dismiss(); pickEmojiOptions(emoji) }
                }.also { grid.addView(it) }
            }
            container.addView(grid)
        }
        sv.addView(container); d.setContentView(sv); d.show()
    }

internal fun VideoEditingActivity.pickEmojiOptions(emoji: String) {
        val act = this
        val sizes    = arrayOf("Small (48)", "Medium (72)", "Large (100)", "XL (140)")
        val sizeVals = intArrayOf(48, 72, 100, 140)
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.emoji_size_dialog_title, emoji))
            .setItems(sizes) { _, si ->
                val startSec = player.currentPosition / 1000f
                textOverlays.add(TextOverlay(
                    text        = emoji,
                    fontSize    = sizeVals[si],
                    color       = "white",
                    position    = "Center",
                    startSec    = startSec,
                    endSec      = -1f,
                    bgAlpha     = 0f,
                    normalizedX = 0.5f,
                    normalizedY = 0.5f
                ))
                textAdapter.notifyItemInserted(textOverlays.size - 1)
                bumpOverlaysVersion()
                updateTextBadge()
                rebuildTimeline()
                showSnack(getString(R.string.snack_emoji_added, emoji))
            }.show()
    }

internal fun VideoEditingActivity.showEmojiSheetCategory(category: String) {
        val act = this
        val emojis = when (category) {
            "hearts" -> listOf("\u2764\uFE0F","\uD83D\uDC95","\uD83D\uDC96","\uD83D\uDC97","\uD83D\uDC93","\uD83E\uDDE1","\uD83D\uDC9B","\uD83D\uDC9A","\uD83D\uDC99","\uD83D\uDC9C","\uD83D\uDDA4","\uD83E\uDD0D","\uD83D\uDC94","\u2665\uFE0F","\uD83D\uDC9E")
            "party"  -> listOf("\uD83C\uDF89","\uD83C\uDF8A","\uD83C\uDF88","\uD83E\uDD73","\uD83C\uDF82","\uD83C\uDF81","\uD83C\uDFC6","\u2B50","\uD83C\uDF1F","\u2728","\uD83C\uDF86","\uD83C\uDF87","\uD83E\uDD42","\uD83C\uDFB5","\uD83C\uDFB6")
            "stars"  -> listOf("\u2B50","\uD83C\uDF1F","\u2728","\uD83D\uDCAB","\uD83C\uDF20","\u26A1","\uD83D\uDD25","\uD83D\uDCA5","\uD83C\uDF08","\uD83C\uDF19","\u2600\uFE0F","\u26C5","\uD83C\uDF24","\uD83C\uDF00","\u2744\uFE0F")
            "music"  -> listOf("\uD83C\uDFB5","\uD83C\uDFB6","\uD83C\uDFB8","\uD83C\uDFB9","\uD83C\uDFBA","\uD83C\uDFBB","\uD83E\uDD41","\uD83C\uDFB7","\uD83C\uDFA4","\uD83C\uDFA7","\uD83D\uDCFB","\uD83C\uDFBC","\uD83C\uDF99","\uD83C\uDF9A","\uD83C\uDF9B")
            else     -> listOf("\uD83D\uDE00","\uD83D\uDE02","\uD83D\uDE0D","\uD83E\uDD70","\uD83D\uDE0E","\uD83E\uDD29","\uD83D\uDE2D","\uD83D\uDE21","\uD83E\uDD14","\uD83D\uDE34","\uD83E\uDD23","\uD83D\uDE18","\uD83E\uDD7A","\uD83D\uDE0F","\uD83E\uDD17")
        }
        val d = BottomSheetDialog(this)
        val grid = GridLayout(this).apply { columnCount = 5; setPadding(24, 24, 24, 48) }
        emojis.forEach { emoji ->
            Button(this).apply {
                text = emoji; textSize = 26f; setPadding(8, 8, 8, 8); background = null
                setOnClickListener { d.dismiss(); pickEmojiOptions(emoji) }
            }.also { grid.addView(it) }
        }
        d.setContentView(grid); d.show()
    }

internal fun VideoEditingActivity.showStickerSheet() {
    val d = com.google.android.material.bottomsheet.BottomSheetDialog(this)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(editorColor(R.color.colorSheetBackground))
        setPadding(24, 24, 24, 60)
    }
    TextView(this).apply { text = "✨ ملصقات متحركة (Stickers)"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 16) }.also { root.addView(it) }

    val rv = androidx.recyclerview.widget.RecyclerView(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (300 * resources.displayMetrics.density).toInt())
        layoutManager = androidx.recyclerview.widget.GridLayoutManager(this@showStickerSheet, 3)
    }
    val mockStickers = StickerMetadata.getMockAssets()
    rv.adapter = StickerSelectorAdapter(mockStickers) { s ->
        d.dismiss()
        addStickerOverlay(s)
    }
    root.addView(rv)
    d.setContentView(root); d.show()
}

internal fun VideoEditingActivity.addStickerOverlay(meta: StickerMetadata) {
    val startSec = player.currentPosition / 1000f
    val overlay = StickerOverlay(
        lottieUrl = meta.lottieUrl,
        startSec = startSec
    )
    stickerOverlays.add(overlay)
    bumpOverlaysVersion()
    rebuildTimeline()
    showSnack("✓ تمت إضافة ملصق: ${meta.name}")
}
