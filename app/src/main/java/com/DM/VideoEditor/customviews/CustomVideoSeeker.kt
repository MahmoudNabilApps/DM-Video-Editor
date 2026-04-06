package com.DM.VideoEditor.customviews

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.DM.VideoEditor.R

class CustomVideoSeeker @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF7A00.toInt() // Filmora orange
        strokeWidth = 4f
        style = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB800.toInt() // Filmora gold
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FF7A00.toInt() // Filmora orange with alpha
        style = Paint.Style.FILL
    }

    private val handleRect = RectF()
    private var seekPosition = 0f
    private var videoDuration = 0L
    var onSeekListener: ((Float) -> Unit)? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                seekPosition = (event.x / width).coerceIn(0f, 1f)
                onSeekListener?.invoke(seekPosition)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val seekX = width * seekPosition
        val halfW = 6f

        // Glow
        glowPaint.color = 0x22FF7A00.toInt()
        canvas.drawRect(seekX - 14f, 0f, seekX + 14f, height.toFloat(), glowPaint)

        // Main line
        canvas.drawLine(seekX, 0f, seekX, height.toFloat(), linePaint)

        // Handle pill at top
        handleRect.set(seekX - halfW, 2f, seekX + halfW, 26f)
        canvas.drawRoundRect(handleRect, halfW, halfW, handlePaint)

        // Handle pill at bottom
        handleRect.set(seekX - halfW, height - 26f, seekX + halfW, height - 2f)
        canvas.drawRoundRect(handleRect, halfW, halfW, handlePaint)
    }

    fun setVideoDuration(duration: Long) {
        videoDuration = duration
    }

    fun setProgress(position: Long) {
        if (videoDuration > 0) {
            seekPosition = (position.toFloat() / videoDuration).coerceIn(0f, 1f)
            invalidate()
        }
    }
}
