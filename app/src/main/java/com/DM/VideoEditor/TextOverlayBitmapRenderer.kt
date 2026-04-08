package com.DM.VideoEditor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.io.FileOutputStream

/**
 * Renders [TextOverlay] into a full-frame transparent PNG using Android text layout
 * (correct Arabic shaping, RTL, emoji). Composited with FFmpeg [overlay].
 */
object TextOverlayBitmapRenderer {

    fun overlayColorToArgb(colorName: String): Int = try {
        Color.parseColor(
            when (colorName) {
                "white" -> "#FFFFFF"; "yellow" -> "#FFD700"; "red" -> "#FF4444"
                "cyan" -> "#00FFFF"; "green" -> "#00FF88"; "orange" -> "#FF8C00"
                "pink" -> "#FF69B4"; "black" -> "#111111"; "blue" -> "#4488FF"
                "purple" -> "#CC44FF"
                else -> if (colorName.startsWith("#")) colorName else "#FFFFFF"
            }
        )
    } catch (_: Exception) {
        Color.WHITE
    }

    /**
     * @return true if PNG was written
     */
    fun renderToPng(overlay: TextOverlay, frameW: Int, frameH: Int, pngOut: File): Boolean {
        if (frameW < 2 || frameH < 2) return false
        val bmp = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Scale vs reference height (720): frame dimensions are export/project pixels from detectProjectResolution()
        val scale = frameH / 720f
        val textSizePx = (overlay.fontSize * overlay.textScale * scale).coerceIn(8f, 320f)

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textSizePx
            color = overlayColorToArgb(overlay.color)
            typeface = Typeface.create(
                Typeface.DEFAULT,
                when {
                    overlay.bold && overlay.italic -> Typeface.BOLD_ITALIC
                    overlay.bold -> Typeface.BOLD
                    overlay.italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                }
            )
            if (overlay.shadow) setShadowLayer(6f, 3f, 3f, Color.argb(220, 0, 0, 0))
        }

        val maxTextWidth = (frameW * 0.92f).toInt().coerceAtLeast(32)
        val layout = StaticLayout.Builder.obtain(overlay.text, 0, overlay.text.length, textPaint, maxTextWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(true)
            .build()

        val cx = overlay.normalizedX * frameW
        val cy = overlay.normalizedY * frameH

        val padH = (12f * scale).coerceAtLeast(6f)
        val padV = (8f * scale).coerceAtLeast(4f)
        val boxW = layout.width + padH * 2f
        val boxH = layout.height + padV * 2f
        val left = cx - boxW / 2f
        val top = cy - boxH / 2f

        canvas.save()
        canvas.rotate(overlay.textRotation, cx, cy)

        if (overlay.bgAlpha > 0.01f) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb((overlay.bgAlpha * 255).toInt().coerceIn(0, 255), 0, 0, 0)
            }
            val r = (10f * scale).coerceAtLeast(4f)
            canvas.drawRoundRect(RectF(left, top, left + boxW, top + boxH), r, r, bgPaint)
        }

        canvas.translate(left + padH, top + padV)
        layout.draw(canvas)
        canvas.restore()

        return try {
            FileOutputStream(pngOut).use { os ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, os)
            }
            bmp.recycle()
            pngOut.exists() && pngOut.length() > 0L
        } catch (_: Exception) {
            bmp.recycle()
            false
        }
    }

    /**
     * Renders a sticker placeholder (or actual first frame) for the export pipeline.
     */
    fun renderStickerPlaceholder(sticker: StickerOverlay, frameW: Int, frameH: Int, pngOut: File): Boolean {
        if (frameW < 2 || frameH < 2) return false
        val bmp = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 255, 122, 0) // Pro Orange Theme Color
            textSize = 24f * (frameH / 720f)
            textAlign = Paint.Align.CENTER
        }

        val cx = sticker.normalizedX * frameW
        val cy = sticker.normalizedY * frameH

        // Draw a professional "✨ STICKER" placeholder for the current version
        canvas.drawCircle(cx, cy, 40f * sticker.scale * (frameH / 720f), paint)
        canvas.drawText("✨ STICKER", cx, cy + 60f * sticker.scale, paint)

        return try {
            FileOutputStream(pngOut).use { os -> bmp.compress(Bitmap.CompressFormat.PNG, 100, os) }
            bmp.recycle()
            pngOut.exists()
        } catch (_: Exception) {
            bmp.recycle(); false
        }
    }
}
