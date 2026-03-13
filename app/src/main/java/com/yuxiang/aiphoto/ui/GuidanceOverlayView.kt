package com.yuxiang.aiphoto.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.yuxiang.aiphoto.R
import com.yuxiang.aiphoto.model.GuidanceFrame
import kotlin.math.min

class GuidanceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.grid_line)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val subjectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.subject_box_warn)
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private var frame: GuidanceFrame = GuidanceFrame()
    private var aiAssistEnabled: Boolean = true

    fun render(frame: GuidanceFrame, aiAssistEnabled: Boolean) {
        this.frame = frame
        this.aiAssistEnabled = aiAssistEnabled
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawGrid(canvas)
        drawLevel(canvas)
        drawSubject(canvas)
    }

    private fun drawGrid(canvas: Canvas) {
        val thirdWidth = width / 3f
        val thirdHeight = height / 3f
        canvas.drawLine(thirdWidth, 0f, thirdWidth, height.toFloat(), gridPaint)
        canvas.drawLine(thirdWidth * 2f, 0f, thirdWidth * 2f, height.toFloat(), gridPaint)
        canvas.drawLine(0f, thirdHeight, width.toFloat(), thirdHeight, gridPaint)
        canvas.drawLine(0f, thirdHeight * 2f, width.toFloat(), thirdHeight * 2f, gridPaint)
    }

    private fun drawLevel(canvas: Canvas) {
        val levelLength = width * 0.42f
        val centerX = width / 2f
        val centerY = height / 2f
        canvas.save()
        canvas.rotate(frame.horizonTiltDeg, centerX, centerY)
        canvas.drawLine(centerX - levelLength / 2f, centerY, centerX + levelLength / 2f, centerY, levelPaint)
        canvas.restore()
    }

    private fun drawSubject(canvas: Canvas) {
        val subject = frame.subjectBox ?: return
        val rect = RectF(
            subject.left * width,
            subject.top * height,
            subject.right * width,
            subject.bottom * height,
        )
        subjectPaint.color = ContextCompat.getColor(
            context,
            if (frame.isStable && aiAssistEnabled) R.color.subject_box_good else R.color.subject_box_warn,
        )
        canvas.drawRoundRect(rect, 28f, 28f, subjectPaint)
        val radius = min(width, height) * 0.008f
        canvas.drawCircle(rect.centerX(), rect.centerY(), radius, centerPaint)
    }
}

