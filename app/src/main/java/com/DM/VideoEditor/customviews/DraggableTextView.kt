package com.DM.VideoEditor.customviews

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * A fully interactive overlay view that supports:
 *  - Dragging (single finger)
 *  - Pinch-to-zoom (two fingers)
 *  - Two-finger rotation
 *  - Double-tap to edit
 *  - Active selection visual (border + handles)
 */
@SuppressLint("ViewConstructor")
class DraggableTextView(
    context: Context,
    /**
     * Called with (normalizedX, normalizedY) in 0..1 range relative to the container,
     * and the current scale & rotation, when the user finishes dragging.
     */
    val onPositionChanged: (normX: Float, normY: Float, scale: Float, rotation: Float) -> Unit,
    val onDoubleTap: () -> Unit,
    val onSelected: (DraggableTextView) -> Unit
) : FrameLayout(context) {

    // ── Public state ──────────────────────────────────────────
    var normalizedX: Float = 0.5f   // 0 = left, 1 = right
    var normalizedY: Float = 0.5f   // 0 = top , 1 = bottom
    var textScale: Float = 1.0f
    var textRotation: Float = 0f
    var isOverlaySelected: Boolean = false
        set(v) { field = v; invalidate() }

    // ── Inner TextView ────────────────────────────────────────
    val textView: TextView = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 32f
        gravity = Gravity.CENTER
        setShadowLayer(6f, 3f, 3f, Color.BLACK)
    }

    // ── Touch helpers ─────────────────────────────────────────
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var initialSpan = 0f
    private var initialRotation = 0f
    private var initialScale = 1.0f
    private var isDragging = false
    private var activePointerCount = 0

    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#00C8FF")
        strokeWidth = 3f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00C8FF")
    }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                textScale = (initialScale * detector.scaleFactor).coerceIn(0.3f, 5f)
                applyTransform()
                return true
            }
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                initialScale = textScale
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                onDoubleTap.invoke()
                return true
            }
        })

    init {
        setWillNotDraw(false)
        addView(textView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        elevation = 8f
    }

    // ── Draw selection border ─────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isOverlaySelected) {
            val rect = RectF(2f, 2f, width - 2f, height - 2f)
            canvas.drawRoundRect(rect, 8f, 8f, selectionPaint)
            // Corner handles
            val hs = 12f
            listOf(
                2f to 2f,
                width - 2f to 2f,
                2f to height - 2f,
                width - 2f to height - 2f
            ).forEach { (cx, cy) ->
                canvas.drawCircle(cx, cy, hs / 2, handlePaint)
            }
        }
    }

    // ── Touch handling ────────────────────────────────────────
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        activePointerCount = event.pointerCount

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onSelected(this)
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                isDragging = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Two-finger: record initial rotation
                if (event.pointerCount == 2) {
                    val dx = event.getX(1) - event.getX(0)
                    val dy = event.getY(1) - event.getY(0)
                    initialRotation = textRotation - Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    if (activePointerCount == 2) {
                        // Rotate
                        val dx = event.getX(1) - event.getX(0)
                        val dy = event.getY(1) - event.getY(0)
                        textRotation = initialRotation + Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
                        applyTransform()
                    } else if (activePointerCount == 1 && isDragging) {
                        // Drag
                        val parent = parent as? View ?: return true
                        val dx = event.rawX - lastTouchX
                        val dy = event.rawY - lastTouchY
                        x = (x + dx).coerceIn(0f, (parent.width - width).toFloat().coerceAtLeast(0f))
                        y = (y + dy).coerceIn(0f, (parent.height - height).toFloat().coerceAtLeast(0f))
                        lastTouchX = event.rawX
                        lastTouchY = event.rawY

                        // Update normalized position
                        val pW = parent.width.toFloat().coerceAtLeast(1f)
                        val pH = parent.height.toFloat().coerceAtLeast(1f)
                        normalizedX = (x + width / 2f) / pW
                        normalizedY = (y + height / 2f) / pH
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                onPositionChanged(normalizedX, normalizedY, textScale, textRotation)
            }
        }
        return true
    }

    // ── Apply scale & rotation transforms ────────────────────
    private fun applyTransform() {
        scaleX = textScale
        scaleY = textScale
        rotation = textRotation
        invalidate()
    }

    // ── Programmatic positioning ──────────────────────────────
    fun placeInContainer(containerWidth: Int, containerHeight: Int) {
        val cx = normalizedX * containerWidth
        val cy = normalizedY * containerHeight
        x = (cx - width / 2f).coerceIn(0f, (containerWidth - width).toFloat().coerceAtLeast(0f))
        y = (cy - height / 2f).coerceIn(0f, (containerHeight - height).toFloat().coerceAtLeast(0f))
        scaleX = textScale
        scaleY = textScale
        rotation = textRotation
    }
}
