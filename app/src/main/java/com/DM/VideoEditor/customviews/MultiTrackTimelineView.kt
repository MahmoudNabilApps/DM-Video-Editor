package com.DM.VideoEditor.customviews

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.graphics.withSave

// ── Data models ────────────────────────────────────────────────────────────

enum class TimelineItemType { VIDEO, TEXT, AUDIO }

data class VideoTimelineBlock(
    val id: Long,
    val clipIndex: Int,
    var startMs: Long,
    var durationMs: Long,
    var thumbnail: Bitmap? = null,
    var transitionLabel: String = ""
)

data class TextTimelineBlock(
    val id: Long,
    val overlayIndex: Int,
    var startMs: Long,
    var durationMs: Long,
    val label: String,
    val trackRow: Int = 0
)

data class AudioTimelineBlock(
    val id: Long,
    var startMs: Long,
    var durationMs: Long,
    val label: String = "Audio"
)

// ── Callback interface ──────────────────────────────────────────────────────

interface MultiTrackTimelineCallback {
    fun onSeek(posMs: Long)
    fun onVideoBlockSelected(clipIndex: Int)
    fun onVideoBlockDeselected()
    fun onVideoBlockTrimStart(clipIndex: Int, newStartTrimMs: Long, newDurationMs: Long)
    fun onVideoBlockTrimEnd(clipIndex: Int, newDurationMs: Long)
    fun onVideoBlockReorder(fromIndex: Int, toIndex: Int)
    fun onTextBlockSelected(overlayIndex: Int)
    fun onTextBlockMoved(overlayIndex: Int, newStartMs: Long, newDurationMs: Long)
    fun onTextBlockTrimStart(overlayIndex: Int, newStartMs: Long)
    fun onTextBlockTrimEnd(overlayIndex: Int, newEndMs: Long)
    fun onAddClipTapped()
}

// ── The view ────────────────────────────────────────────────────────────────

@SuppressLint("ClickableViewAccessibility")
class MultiTrackTimelineView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    // ── Callback ───────────────────────────────────────────────────────────
    var callback: MultiTrackTimelineCallback? = null

    // ── Timeline data ──────────────────────────────────────────────────────
    val videoBlocks = mutableListOf<VideoTimelineBlock>()
    private val textBlocks  = mutableListOf<TextTimelineBlock>()
    private val audioBlocks = mutableListOf<AudioTimelineBlock>()
    var totalDurationMs: Long = 30_000L

    // Current playhead position — setter auto-scrolls & redraws
    var currentPositionMs: Long = 0L
        set(v) { field = v; autoScrollToPlayhead(); invalidate() }

    // ── BUG-5 FIX: one-time zoom initialisation ────────────────────────────
    private var pxPerMsInitialized = false

    // ── Zoom & Scroll ──────────────────────────────────────────────────────
    private var pxPerMs: Float = 0f          // set during first onMeasure
    private val pxPerMsMin get() = 0.005f * density
    private val pxPerMsMax get() = 0.8f  * density
    private var scrollX: Float = 0f

    // ── Dimensions (px) ───────────────────────────────────────────────────
    private val density      get() = resources.displayMetrics.density
    private val rulerH       get() = 28f * density
    private val videoH       get() = 72f * density
    private val textRowH     get() = 40f * density
    private val audioRowH    get() = 36f * density
    private val labelW       get() = 44f * density
    private val edgeZone     get() = 22f * density
    private val addBtnW      get() = 44f * density

    // ── Layout helpers ─────────────────────────────────────────────────────
    private val videoTrackTop    get() = rulerH
    private val videoTrackBot    get() = rulerH + videoH
    private fun textRowTop(r: Int) = videoTrackBot + 2f + r * (textRowH + 2f)
    private fun textRowBot(r: Int) = textRowTop(r) + textRowH
    private val maxTextRows      get() = (textBlocks.maxOfOrNull { it.trackRow + 1 } ?: 0).coerceAtLeast(if (textBlocks.isEmpty()) 0 else 1)
    private val hasAudioTrack    get() = audioBlocks.isNotEmpty()
    private val audioTrackTop    get() = if (maxTextRows > 0) textRowBot(maxTextRows - 1) + 4f else videoTrackBot + 4f
    private val audioTrackBot    get() = audioTrackTop + audioRowH
    private val totalTracksH     get() = if (hasAudioTrack) audioTrackBot + 4f
                                         else if (maxTextRows > 0) textRowBot(maxTextRows - 1) + 4f
                                         else videoTrackBot + 4f

    // ── Paints ─────────────────────────────────────────────────────────────
    private val pBg        = Paint().apply { color = Color.parseColor("#0D0D0D") }
    private val pRulerBg   = Paint().apply { color = Color.parseColor("#141414") }
    private val pVTrackBg  = Paint().apply { color = Color.parseColor("#0F0F0F") }
    private val pTTrackBg  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0A0A18") }
    private val pATrackBg  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#061A12") }
    private val pTick      = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#333333"); style = Paint.Style.STROKE; strokeWidth = 1f }
    private val pTickMaj   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#555555"); style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val pRulerTxt  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#888888"); textAlign = Paint.Align.CENTER; typeface = Typeface.MONOSPACE }
    private val pVBlock    = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pVBorder   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pThumb     = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pShimmer   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#22334433"); style = Paint.Style.STROKE; strokeWidth = 6f; strokeCap = Paint.Cap.BUTT }
    private val pTBlock    = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pTBorder   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pABlock    = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pABorder   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pHandle    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val pLabel     = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.LEFT }
    private val pLabelR    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#666666"); textAlign = Paint.Align.CENTER }
    private val pPlayhead  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF3333"); style = Paint.Style.FILL_AND_STROKE }
    private val pTooltipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CC000000") }
    private val pTooltipTx = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF3333"); textAlign = Paint.Align.CENTER; typeface = Typeface.MONOSPACE }
    private val pSep       = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#222222"); style = Paint.Style.STROKE; strokeWidth = 1f }
    private val pLabelBg   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CC0D0D0D") }
    private val pAddBtn    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A1A1A") }
    private val pAddBtnBdr = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#333333"); style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val pAddTxt    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF7A00"); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val pEmpty     = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#333333"); textAlign = Paint.Align.CENTER }

    // FIX-2: pre-allocated Paints that were previously created inside onDraw methods
    private val pShimBg  = Paint().apply { color = Color.parseColor("#1A2A1A") }
    private val pBadgeBg = Paint().apply { color = Color.parseColor("#CC000000") }
    private val pWaveBar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A8A6A"); style = Paint.Style.FILL
    }
    private val pChev    = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF7A00"); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }

    // ── Selection ──────────────────────────────────────────────────────────
    private var selVideoIdx = -1
    private var selTextIdx  = -1

    // ── BUG-3 FIX: drag state — reorder fires only on ACTION_UP ───────────
    private enum class DragMode { NONE, SEEK, VIDEO_MOVE, VIDEO_TRIM_L, VIDEO_TRIM_R,
        TEXT_MOVE, TEXT_TRIM_L, TEXT_TRIM_R, SCROLL }
    private var dragMode        = DragMode.NONE
    private var dragBlockIdx    = -1
    private var dragStartX      = 0f
    private var dragBlockInitSt = 0L
    private var dragBlockInitDu = 0L

    /** FIX-1: undo-flood guard — set to false at drag start, true after first pushUndo().
     *  internal so VideoEditingActivity (same module) can read/write it. */
    internal var dragUndoSaved = false

    // ── Haptic ────────────────────────────────────────────────────────────
    private fun hapticClick() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(18L, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(18L)
            }
        } catch (_: Exception) {
            // Vibration is non-critical — swallow any SecurityException silently
        }
    }

    // ── Gesture detectors ─────────────────────────────────────────────────
    private val gestureDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            handleTap(e.x, e.y); return true
        }
        // BUG-4 FIX: scroll only when no block is being dragged
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (dragMode == DragMode.NONE || dragMode == DragMode.SCROLL) {
                dragMode = DragMode.SCROLL
                applyScroll(dx)
            }
            return true
        }
    })

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val focusMs = xToMs(d.focusX)
            pxPerMs     = (pxPerMs * d.scaleFactor).coerceIn(pxPerMsMin, pxPerMsMax)
            scrollX     = (focusMs * pxPerMs - (d.focusX - labelW)).coerceIn(0f, maxScrollX())
            invalidate(); return true
        }
    })

    // ── Coordinate helpers ─────────────────────────────────────────────────
    private fun msToX(ms: Long): Float  = labelW + ms * pxPerMs - scrollX
    private fun xToMs(x: Float): Long   = ((x - labelW + scrollX) / pxPerMs).toLong().coerceAtLeast(0L)
    private fun maxScrollX(): Float      = (totalDurationMs * pxPerMs + addBtnW - width + labelW).coerceAtLeast(0f)
    private fun snap(ms: Long, grid: Long = 500L) = (ms / grid) * grid

    // ── BUG-5 FIX: stable onMeasure ────────────────────────────────────────
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val h = (totalTracksH + 4f * density).toInt().coerceAtLeast((200 * density).toInt())
        if (!pxPerMsInitialized && w > 0) {
            pxPerMs = 0.05f * density
            pxPerMsInitialized = true
        }
        setMeasuredDimension(w, h)
    }

    // ── Draw ───────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        val d = density

        // UX-3: Empty state
        if (videoBlocks.isEmpty()) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pBg)
            pEmpty.textSize = 14f * d
            canvas.drawText("Tap  +  to add your first video clip",
                width / 2f, height / 2f, pEmpty)
            drawLabels(canvas, d)
            return
        }

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pBg)
        drawTrackBgs(canvas)
        drawRuler(canvas, d)
        drawVideoBlocks(canvas, d)
        drawTextBlocks(canvas, d)
        drawAudioBlocks(canvas, d)
        drawAddClipButton(canvas, d)
        drawPlayhead(canvas, d)     // drawn after blocks so it's always on top
        drawLabels(canvas, d)
    }

    // ─ Track backgrounds ──────────────────────────────────────────────────
    private fun drawTrackBgs(canvas: Canvas) {
        canvas.drawRect(labelW, videoTrackTop, width.toFloat(), videoTrackBot, pVTrackBg)
        for (r in 0 until maxTextRows) {
            // FIX-2: reuse pTTrackBg, just change its color — no allocation
            pTTrackBg.color = if (r % 2 == 0) Color.parseColor("#0A0A18") else Color.parseColor("#0C0C1A")
            canvas.drawRect(labelW, textRowTop(r), width.toFloat(), textRowBot(r), pTTrackBg)
        }
        if (hasAudioTrack)
            canvas.drawRect(labelW, audioTrackTop, width.toFloat(), audioTrackBot, pATrackBg)
    }

    // ─ Time ruler ─────────────────────────────────────────────────────────
    private fun drawRuler(canvas: Canvas, d: Float) {
        canvas.drawRect(0f, 0f, width.toFloat(), rulerH, pRulerBg)
        pRulerTxt.textSize = 9f * d

        val msPerPx    = 1f / pxPerMs
        val intervalMs = when {
            msPerPx < 50   -> 500L
            msPerPx < 200  -> 1_000L
            msPerPx < 1000 -> 5_000L
            msPerPx < 5000 -> 10_000L
            else           -> 30_000L
        }
        val minorMs    = intervalMs / 5

        val startMs = xToMs(labelW)
        val endMs   = xToMs(width.toFloat()) + intervalMs

        var t = (startMs / minorMs) * minorMs
        while (t <= endMs) {
            val x = msToX(t)
            if (x >= labelW && x <= width) {
                val isMajor = t % intervalMs == 0L
                val p = if (isMajor) pTickMaj else pTick
                val tickH = if (isMajor) rulerH * 0.55f else rulerH * 0.3f
                canvas.drawLine(x, rulerH - tickH, x, rulerH, p)
                if (isMajor) {
                    val s = t / 1000L
                    canvas.drawText("%02d:%02d".format(s / 60, s % 60), x, rulerH - tickH - 3f * d, pRulerTxt)
                }
            }
            t += minorMs
        }
        canvas.drawLine(labelW, rulerH, width.toFloat(), rulerH, pSep)
    }

    // ─ Video blocks ───────────────────────────────────────────────────────
    private fun drawVideoBlocks(canvas: Canvas, d: Float) {
        val top = videoTrackTop + 3f * d
        val bot = videoTrackBot - 3f * d
        val r   = 6f * d
        pLabel.textSize = 9f * d

        for ((i, blk) in videoBlocks.withIndex()) {
            val left  = msToX(blk.startMs)
            val right = msToX(blk.startMs + blk.durationMs)
            if (right < labelW || left > width) continue
            val cl   = left.coerceAtLeast(labelW)
            val cr   = right.coerceAtMost(width.toFloat())
            val sel  = i == selVideoIdx
            val rect = RectF(cl, top, cr, bot)

            // Fill
            pVBlock.color = if (sel) Color.parseColor("#1A2E1A") else Color.parseColor("#141E14")
            canvas.drawRoundRect(rect, r, r, pVBlock)

            // UX-1: Thumbnail or shimmer placeholder
            if (blk.thumbnail != null && !blk.thumbnail!!.isRecycled) {
                val bmp   = blk.thumbnail!!
                val destH = bot - top - 4f * d
                val destW = (destH / bmp.height * bmp.width).coerceAtMost(cr - cl)
                canvas.withSave {
                    clipRect(cl + 2f, top + 2f, cr - 2f, bot - 2f)
                    var tx = cl + 2f
                    while (tx < cr - 2f) {
                        val dW  = destW.coerceAtMost(cr - 2f - tx)
                        val src = RectF(0f, 0f, bmp.width * (dW / destW), bmp.height.toFloat())
                        canvas.drawBitmap(bmp, src.toRect(), RectF(tx, top + 2f, tx + dW, top + 2f + destH), pThumb)
                        tx += dW
                    }
                }
            } else {
                // Diagonal-stripe shimmer while loading — FIX-2: use pre-allocated pShimBg
                canvas.drawRoundRect(rect, r, r, pShimBg)
                pShimmer.strokeWidth = 6f * d
                var lx = cl - (bot - top)
                while (lx < cr) {
                    val x1 = lx.coerceAtLeast(cl); val x2 = (lx + (bot - top)).coerceAtMost(cr)
                    if (x2 > x1) canvas.drawLine(x1, top, x2, bot, pShimmer)
                    lx += 14f * d
                }
            }

            // Dark bottom badge — FIX-2: use pre-allocated pBadgeBg
            if (cr - cl > 30f * d) {
                canvas.withSave {
                    clipRect(cl, bot - 14f * d, cr, bot)
                    canvas.drawRoundRect(rect, r, r, pBadgeBg)
                }
                val s = blk.durationMs / 1000f
                val lbl = if (s >= 60) "%dm%02ds".format(s.toInt() / 60, s.toInt() % 60) else "%.1fs".format(s)
                pLabel.color = Color.parseColor("#CCCCCC")
                canvas.drawText(lbl, cl + 5f * d, bot - 3f * d, pLabel)
            }

            // Border
            pVBorder.color       = if (sel) Color.parseColor("#FF7A00") else Color.parseColor("#2A3A2A")
            pVBorder.strokeWidth = (if (sel) 2.5f else 1f) * d
            canvas.drawRoundRect(rect, r, r, pVBorder)

            // Trim handles
            if (sel && cr - cl > 24f * d) {
                drawHandle(canvas, cl + 2f * d, top, bot, d, isLeft = true)
                drawHandle(canvas, cr - 2f * d, top, bot, d, isLeft = false)
            }

            // Transition dot to next block
            if (i < videoBlocks.size - 1 && blk.transitionLabel.isNotEmpty()) {
                val cx = msToX(blk.startMs + blk.durationMs)
                if (cx in labelW..width.toFloat()) {
                    pTBlock.color = Color.parseColor("#FF7A00")
                    canvas.drawCircle(cx, (top + bot) / 2f, 7f * d, pTBlock)
                }
            }
        }
    }

    // ─ Text blocks ────────────────────────────────────────────────────────
    private fun drawTextBlocks(canvas: Canvas, d: Float) {
        val r = 4f * d
        pLabel.textSize = 9f * d

        for ((i, blk) in textBlocks.withIndex()) {
            val left  = msToX(blk.startMs)
            val right = msToX(blk.startMs + blk.durationMs)
            if (right < labelW || left > width) continue
            val cl  = left.coerceAtLeast(labelW)
            val cr  = right.coerceAtMost(width.toFloat())
            val top = textRowTop(blk.trackRow) + 3f * d
            val bot = textRowBot(blk.trackRow) - 3f * d
            val sel = i == selTextIdx
            val rect = RectF(cl, top, cr, bot)

            pTBlock.color = if (sel) Color.parseColor("#1E4080") else Color.parseColor("#13285A")
            canvas.drawRoundRect(rect, r, r, pTBlock)

            if (cr - cl > 10f) {
                pLabel.color = Color.WHITE
                canvas.withSave {
                    clipRect(cl + 4f, top, cr - 4f, bot)
                    canvas.drawText(blk.label.take(30), cl + 5f * d, (top + bot) / 2f + 4f * d, pLabel)
                }
            }

            pTBorder.color       = if (sel) Color.parseColor("#1E88E5") else Color.parseColor("#1A4488")
            pTBorder.strokeWidth = (if (sel) 2f else 1f) * d
            canvas.drawRoundRect(rect, r, r, pTBorder)

            if (sel && cr - cl > 24f * d) {
                drawHandle(canvas, cl + 2f * d, top, bot, d, isLeft = true)
                drawHandle(canvas, cr - 2f * d, top, bot, d, isLeft = false)
            }
        }
    }

    // ─ Audio blocks ───────────────────────────────────────────────────────
    private fun drawAudioBlocks(canvas: Canvas, d: Float) {
        if (!hasAudioTrack) return
        val top = audioTrackTop + 3f * d
        val bot = audioTrackBot - 3f * d
        val r   = 4f * d
        pLabel.textSize = 9f * d

        for (blk in audioBlocks) {
            val left  = msToX(blk.startMs)
            val right = msToX(blk.startMs + blk.durationMs)
            if (right < labelW || left > width) continue
            val cl  = left.coerceAtLeast(labelW)
            val cr  = right.coerceAtMost(width.toFloat())
            val rect = RectF(cl, top, cr, bot)

            pABlock.color = Color.parseColor("#0A3A2A")
            canvas.drawRoundRect(rect, r, r, pABlock)

            // Waveform-like bars — FIX-2: use pre-allocated pWaveBar
            val barW = 3f * d; val barGap = 5f * d; val midY = (top + bot) / 2f; val maxH = (bot - top) * 0.4f
            var bx = cl + barGap
            while (bx < cr - barW) {
                val h = maxH * (0.3f + 0.7f * kotlin.math.sin((bx * 0.1).toDouble()).toFloat().let { kotlin.math.abs(it) })
                canvas.drawRect(bx, midY - h, bx + barW, midY + h, pWaveBar)
                bx += barW + barGap
            }

            if (cr - cl > 20f) {
                pLabel.color = Color.parseColor("#4AC9A0")
                canvas.withSave {
                    clipRect(cl + 4f, top, cr - 4f, bot)
                    canvas.drawText(blk.label, cl + 5f * d, (top + bot) / 2f + 3f * d, pLabel)
                }
            }

            pABorder.color       = Color.parseColor("#1A8A5A")
            pABorder.strokeWidth = 1f * d
            canvas.drawRoundRect(rect, r, r, pABorder)
        }
    }

    // ─ Trim handle pill ───────────────────────────────────────────────────
    private fun drawHandle(canvas: Canvas, cx: Float, top: Float, bot: Float, d: Float, isLeft: Boolean) {
        val w  = 5f * d; val h = (bot - top) * 0.6f; val cy = (top + bot) / 2f
        val rect = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        pHandle.color = Color.WHITE; pHandle.style = Paint.Style.FILL
        canvas.drawRoundRect(rect, w / 2f, w / 2f, pHandle)
        // FIX-2: use pre-allocated pChev, only set strokeWidth (density-dependent)
        pChev.strokeWidth = 1.5f * d
        val ax = if (isLeft) cx - 1.5f * d else cx + 1.5f * d
        val tx = if (isLeft) cx - 3f * d else cx + 3f * d
        canvas.drawLine(ax, cy - 4f * d, tx, cy, pChev)
        canvas.drawLine(ax, cy + 4f * d, tx, cy, pChev)
    }

    // ─ Add Clip button ────────────────────────────────────────────────────
    private fun drawAddClipButton(canvas: Canvas, d: Float) {
        val startX = msToX(totalDurationMs) + 4f * d
        val endX   = startX + addBtnW - 8f * d
        if (endX < labelW || startX > width) return
        val top  = videoTrackTop + 4f * d; val bot = videoTrackBot - 4f * d
        val rect = RectF(startX.coerceAtLeast(labelW), top, endX.coerceAtMost(width.toFloat()), bot)
        canvas.drawRoundRect(rect, 6f * d, 6f * d, pAddBtn)
        canvas.drawRoundRect(rect, 6f * d, 6f * d, pAddBtnBdr)
        pAddTxt.textSize = 22f * d
        canvas.drawText("+", (rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f + 8f * d, pAddTxt)
    }

    // ─ Playhead + UX-6 tooltip ───────────────────────────────────────────
    private fun drawPlayhead(canvas: Canvas, d: Float) {
        val x = msToX(currentPositionMs)
        if (x < labelW || x > width) return

        pPlayhead.strokeWidth = 2f * d
        canvas.drawLine(x, 0f, x, height.toFloat(), pPlayhead)

        val path = Path().apply {
            moveTo(x - 7f * d, 0f); lineTo(x + 7f * d, 0f); lineTo(x, 14f * d); close()
        }
        pPlayhead.style = Paint.Style.FILL
        canvas.drawPath(path, pPlayhead)
        pPlayhead.style = Paint.Style.FILL_AND_STROKE

        // Time tooltip above the triangle
        val timeTxt = fmtMsShort(currentPositionMs)
        pTooltipTx.textSize = 9f * d
        val tw = pTooltipTx.measureText(timeTxt) + 8f * d
        canvas.drawRoundRect(RectF(x - tw / 2f, 1f, x + tw / 2f, 14f * d), 3f * d, 3f * d, pTooltipBg)
        canvas.drawText(timeTxt, x, 11f * d, pTooltipTx)
    }

    // ─ Labels ────────────────────────────────────────────────────────────
    private fun drawLabels(canvas: Canvas, d: Float) {
        canvas.drawRect(0f, 0f, labelW, height.toFloat(), pLabelBg)
        canvas.drawLine(labelW, 0f, labelW, height.toFloat(), pSep)

        pLabelR.textSize = 7f * d; pLabelR.color = Color.parseColor("#666666")
        canvas.drawText("TIME", labelW / 2f, rulerH / 2f + 3f * d, pLabelR)

        pLabelR.textSize = 10f * d; pLabelR.color = Color.parseColor("#FF7A00")
        canvas.drawText("\u25B6", labelW / 2f, (videoTrackTop + videoTrackBot) / 2f + 4f * d, pLabelR)

        pLabelR.textSize = 9f * d; pLabelR.color = Color.parseColor("#1E88E5")
        for (r in 0 until maxTextRows)
            canvas.drawText("T", labelW / 2f, (textRowTop(r) + textRowBot(r)) / 2f + 3f * d, pLabelR)

        if (hasAudioTrack) {
            pLabelR.textSize = 8f * d; pLabelR.color = Color.parseColor("#4AC9A0")
            canvas.drawText("A", labelW / 2f, (audioTrackTop + audioTrackBot) / 2f + 3f * d, pLabelR)
        }
    }

    // ── Touch ──────────────────────────────────────────────────────────────
    // BUG-4 FIX: scaleDetector gets ALL events; gestureDetector only when no scale active
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        if (!scaleDetector.isInProgress) {
            gestureDetector.onTouchEvent(ev)
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!scaleDetector.isInProgress) {
                    dragStartX = ev.x
                    identifyDrag(ev.x, ev.y)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // BUG-4: only handleDragMove when we own the gesture
                if (!scaleDetector.isInProgress && dragMode != DragMode.SCROLL) {
                    handleDragMove(ev.x)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finishDrag()
        }
        return true
    }

    private fun identifyDrag(x: Float, y: Float) {
        // FIX-1: reset undo-flood guard at the start of every new drag gesture
        dragUndoSaved = false

        // Audio track is display-only for now — don't treat taps as seek events
        if (hasAudioTrack && y >= audioTrackTop && y <= audioTrackBot) {
            dragMode = DragMode.NONE
            return
        }

        if (kotlin.math.abs(x - msToX(currentPositionMs)) < 18f * density) {
            dragMode = DragMode.SEEK; return
        }

        if (y >= videoTrackTop && y <= videoTrackBot) {
            for ((i, blk) in videoBlocks.withIndex()) {
                val left = msToX(blk.startMs); val right = msToX(blk.startMs + blk.durationMs)
                if (x < left - edgeZone || x > right + edgeZone) continue
                dragBlockIdx = i; dragBlockInitSt = blk.startMs; dragBlockInitDu = blk.durationMs
                dragMode = when {
                    x <= left  + edgeZone -> DragMode.VIDEO_TRIM_L
                    x >= right - edgeZone -> DragMode.VIDEO_TRIM_R
                    else                  -> DragMode.VIDEO_MOVE
                }; return
            }
            val addX = msToX(totalDurationMs) + 4f * density
            if (x >= addX && x <= addX + addBtnW) { callback?.onAddClipTapped(); return }
        }

        for ((i, blk) in textBlocks.withIndex()) {
            val rowY0 = textRowTop(blk.trackRow); val rowY1 = textRowBot(blk.trackRow)
            if (y < rowY0 || y > rowY1) continue
            val left = msToX(blk.startMs); val right = msToX(blk.startMs + blk.durationMs)
            if (x < left - edgeZone || x > right + edgeZone) continue
            dragBlockIdx = i; dragBlockInitSt = blk.startMs; dragBlockInitDu = blk.durationMs
            dragMode = when {
                x <= left  + edgeZone -> DragMode.TEXT_TRIM_L
                x >= right - edgeZone -> DragMode.TEXT_TRIM_R
                else                  -> DragMode.TEXT_MOVE
            }; return
        }

        dragMode = DragMode.SEEK
    }

    private fun handleDragMove(x: Float) {
        val deltaMs = ((x - dragStartX) / pxPerMs).toLong()
        val grid = when {
            pxPerMs > 0.2f * density  -> 100L
            pxPerMs > 0.05f * density -> 500L
            else                      -> 1_000L
        }
        when (dragMode) {
            DragMode.SEEK -> {
                val ms = xToMs(x).coerceIn(0L, totalDurationMs)
                currentPositionMs = ms          // setter triggers auto-scroll + invalidate
                callback?.onSeek(ms)            // UX-2: fires on every MOVE for real-time display
            }
            // BUG-3 FIX: VIDEO_MOVE only updates visual, callback fires in finishDrag
            DragMode.VIDEO_MOVE -> {
                val i = dragBlockIdx.takeIf { it >= 0 } ?: return
                videoBlocks[i].startMs = snap((dragBlockInitSt + deltaMs).coerceAtLeast(0L), grid)
                invalidate()
            }
            DragMode.VIDEO_TRIM_L -> {
                val i = dragBlockIdx.takeIf { it >= 0 } ?: return
                val newSt = snap((dragBlockInitSt + deltaMs).coerceAtLeast(0L), grid)
                val newDu = (dragBlockInitDu - (newSt - dragBlockInitSt)).coerceAtLeast(500L)
                videoBlocks[i].startMs = newSt; videoBlocks[i].durationMs = newDu
                callback?.onVideoBlockTrimStart(i, newSt, newDu)
                invalidate()
            }
            DragMode.VIDEO_TRIM_R -> {
                val i = dragBlockIdx.takeIf { it >= 0 } ?: return
                val newDu = snap((dragBlockInitDu + deltaMs).coerceAtLeast(500L), grid)
                videoBlocks[i].durationMs = newDu
                callback?.onVideoBlockTrimEnd(i, newDu)
                invalidate()
            }
            DragMode.TEXT_MOVE -> {
                val i = dragBlockIdx.takeIf { it >= 0 } ?: return
                val newSt = snap((dragBlockInitSt + deltaMs).coerceAtLeast(0L), grid)
                textBlocks[i].startMs = newSt
                callback?.onTextBlockMoved(i, newSt, textBlocks[i].durationMs)
                invalidate()
            }
            // UX-4 FIX: trim-left keeps the end time fixed
            DragMode.TEXT_TRIM_L -> {
                val i = dragBlockIdx.takeIf { it >= 0 } ?: return
                val originalEnd = dragBlockInitSt + dragBlockInitDu
                val newSt  = snap((dragBlockInitSt + deltaMs).coerceIn(0L, originalEnd - 500L), grid)
                textBlocks[i].startMs    = newSt
                textBlocks[i].durationMs = originalEnd - newSt
                callback?.onTextBlockTrimStart(i, newSt)
                invalidate()
            }
            DragMode.TEXT_TRIM_R -> {
                val i = dragBlockIdx.takeIf { it >= 0 } ?: return
                val newEnd = snap((dragBlockInitSt + dragBlockInitDu + deltaMs)
                    .coerceAtLeast(dragBlockInitSt + 500L), grid)
                textBlocks[i].durationMs = newEnd - textBlocks[i].startMs
                callback?.onTextBlockTrimEnd(i, newEnd)
                invalidate()
            }
            DragMode.SCROLL -> {
                val dx = -(x - dragStartX); dragStartX = x; applyScroll(dx)
            }
            DragMode.NONE -> {}
        }
    }

    // BUG-3 FIX: fire reorder callback once on release
    private fun finishDrag() {
        if (dragMode == DragMode.VIDEO_MOVE && dragBlockIdx >= 0) {
            val newOrder = computeNewOrder(dragBlockIdx, videoBlocks[dragBlockIdx].startMs)
            if (newOrder != dragBlockIdx)
                callback?.onVideoBlockReorder(dragBlockIdx, newOrder)
            else
                callback?.onSeek(currentPositionMs)   // snap back via rebuildTimeline
        }
        // FIX-1: reset undo-flood guard so next drag gets a fresh undo entry
        dragUndoSaved = false
        dragMode = DragMode.NONE; dragBlockIdx = -1
    }

    private fun handleTap(x: Float, y: Float) {
        if (y >= videoTrackTop && y <= videoTrackBot) {
            for ((i, blk) in videoBlocks.withIndex()) {
                val l = msToX(blk.startMs); val r = msToX(blk.startMs + blk.durationMs)
                if (x !in l..r) continue
                if (selVideoIdx == i) { selVideoIdx = -1; callback?.onVideoBlockDeselected() }
                else { selVideoIdx = i; selTextIdx = -1; callback?.onVideoBlockSelected(i); hapticClick() }
                invalidate(); return
            }
            val addX = msToX(totalDurationMs) + 4f * density
            if (x >= addX) { callback?.onAddClipTapped(); return }
        }
        for ((i, blk) in textBlocks.withIndex()) {
            val rowY0 = textRowTop(blk.trackRow); val rowY1 = textRowBot(blk.trackRow)
            if (y < rowY0 || y > rowY1) continue
            val l = msToX(blk.startMs); val r = msToX(blk.startMs + blk.durationMs)
            if (x !in l..r) continue
            if (selTextIdx == i) selTextIdx = -1
            else { selTextIdx = i; selVideoIdx = -1; callback?.onTextBlockSelected(i); hapticClick() }
            invalidate(); return
        }
        if (x >= labelW) {
            val ms = xToMs(x).coerceIn(0L, totalDurationMs)
            currentPositionMs = ms
            callback?.onSeek(ms)
        }
    }

    private fun applyScroll(dx: Float) {
        scrollX = (scrollX + dx).coerceIn(0f, maxScrollX()); invalidate()
    }

    private fun autoScrollToPlayhead() {
        if (width == 0) return
        val x = msToX(currentPositionMs)
        val cR = width - labelW
        when {
            x > labelW + cR * 0.75f ->
                scrollX = (currentPositionMs * pxPerMs - cR * 0.5f).coerceIn(0f, maxScrollX())
            x < labelW + 20f * density ->
                scrollX = (currentPositionMs * pxPerMs - cR * 0.25f).coerceIn(0f, maxScrollX())
        }
    }

    // BUG-3 FIX: center-based order computation
    private fun computeNewOrder(movedIdx: Int, newStartMs: Long): Int {
        val movedCenter = newStartMs + (videoBlocks.getOrNull(movedIdx)?.durationMs ?: 0L) / 2
        var newIdx = 0
        for ((i, blk) in videoBlocks.withIndex()) {
            if (i == movedIdx) continue
            val center = blk.startMs + blk.durationMs / 2
            if (movedCenter > center) newIdx = i + (if (i < movedIdx) 0 else -1) + 1
        }
        return newIdx.coerceIn(0, (videoBlocks.size - 1).coerceAtLeast(0))
    }

    private fun fmtMsShort(ms: Long): String {
        val s = ms / 1000L; return "%02d:%02d".format(s / 60, s % 60)
    }

    // ── Public API ─────────────────────────────────────────────────────────

    fun setVideoBlocks(blocks: List<VideoTimelineBlock>) {
        videoBlocks.clear(); videoBlocks.addAll(blocks); requestLayout(); invalidate()
    }

    fun setTextBlocks(blocks: List<TextTimelineBlock>) {
        textBlocks.clear(); textBlocks.addAll(blocks); requestLayout(); invalidate()
    }

    fun setAudioBlocks(blocks: List<AudioTimelineBlock>) {
        audioBlocks.clear(); audioBlocks.addAll(blocks); requestLayout(); invalidate()
    }

    // BUG-1 FIX: recycle old bitmap before replacing
    fun updateBlockThumbnail(blockIndex: Int, bmp: Bitmap) {
        videoBlocks.getOrNull(blockIndex)?.let { block ->
            block.thumbnail?.let { old -> if (!old.isRecycled) old.recycle() }
            block.thumbnail = bmp
        }
        invalidate()
    }

    fun selectVideoBlock(idx: Int) { selVideoIdx = idx; selTextIdx = -1; invalidate() }
    fun deselectAll()              { selVideoIdx = -1;  selTextIdx = -1; invalidate() }

    fun setTotalDuration(ms: Long) {
        totalDurationMs = ms.coerceAtLeast(1_000L); requestLayout(); invalidate()
    }

    fun zoomIn() {
        val focusMs = currentPositionMs
        pxPerMs = (pxPerMs * 1.4f).coerceAtMost(pxPerMsMax)
        scrollX = (focusMs * pxPerMs - (width - labelW) * 0.5f).coerceIn(0f, maxScrollX())
        invalidate()
    }

    fun zoomOut() {
        val focusMs = currentPositionMs
        pxPerMs = (pxPerMs / 1.4f).coerceAtLeast(pxPerMsMin)
        scrollX = (focusMs * pxPerMs - (width - labelW) * 0.5f).coerceIn(0f, maxScrollX())
        invalidate()
    }
}

// ── Helper ─────────────────────────────────────────────────────────────────
private fun RectF.toRect(): Rect = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
