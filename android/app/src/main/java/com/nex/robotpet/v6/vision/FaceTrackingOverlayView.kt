package com.nex.robotpet.v6.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Draws neon cyber tracking reticles around faces detected by on-device ML Kit Face Detection.
 */
class FaceTrackingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val normalizedBoxes = mutableListOf<RectF>()

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4400E676")
        style = Paint.Style.STROKE
        strokeWidth = 13f
    }

    private val drawRect = RectF()

    fun updateNormalizedFaces(boxes: List<RectF>) {
        normalizedBoxes.clear()
        normalizedBoxes.addAll(boxes)
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        for (norm in normalizedBoxes) {
            drawRect.set(
                norm.left * w,
                norm.top * h,
                norm.right * w,
                norm.bottom * h
            )
            canvas.drawRoundRect(drawRect, 18f, 18f, glowPaint)
            canvas.drawRoundRect(drawRect, 18f, 18f, boxPaint)
        }
    }
}
