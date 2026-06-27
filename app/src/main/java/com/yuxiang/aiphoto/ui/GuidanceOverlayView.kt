package com.yuxiang.aiphoto.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.yuxiang.aiphoto.R
import com.yuxiang.aiphoto.model.CaptureReadiness
import com.yuxiang.aiphoto.model.DirectionHint
import com.yuxiang.aiphoto.model.GuidanceFrame
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.PI

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
        color = ContextCompat.getColor(context, R.color.white)
        style = Paint.Style.FILL
    }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }
    private val readyBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.readiness_ready)
        strokeWidth = 8f
        style = Paint.Style.STROKE
    }
    private val almostBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.readiness_almost)
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.readiness_almost)
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.readiness_almost)
        style = Paint.Style.FILL
    }
    private val targetZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.target_zone)
        strokeWidth = 4f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(18f, 14f), 0f)
    }
    private val targetConnectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.target_zone_connector)
        strokeWidth = 3f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
        strokeCap = Paint.Cap.ROUND
    }

    private var frame: GuidanceFrame = GuidanceFrame()
    private var aiAssistEnabled: Boolean = true
    private var cloudGuidanceState: CloudGuidanceUiState = CloudGuidanceUiState.Idle

    fun render(frame: GuidanceFrame, aiAssistEnabled: Boolean) {
        this.frame = frame
        this.aiAssistEnabled = aiAssistEnabled
        invalidate()
    }

    fun renderCloudGuidance(state: CloudGuidanceUiState) {
        this.cloudGuidanceState = state
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawGrid(canvas)
        drawLevel(canvas)
        drawReadinessBorder(canvas)
        drawTargetZone(canvas)
        drawSubject(canvas)
        drawDirectionArrow(canvas)
        drawCloudGuidance(canvas)
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

    private fun drawReadinessBorder(canvas: Canvas) {
        if (!aiAssistEnabled) return
        val inset = 24f
        val rect = RectF(inset, inset, width - inset, height - inset)
        when (frame.captureReadiness) {
            CaptureReadiness.READY -> {
                // READY 时绿框脉冲呼吸，吸引注意
                val pulse = currentPulsePhase()
                readyBorderPaint.alpha = (120 + 135 * pulse).toInt().coerceIn(0, 255)
                canvas.drawRoundRect(rect, 32f, 32f, readyBorderPaint)
            }
            CaptureReadiness.ALMOST_READY -> canvas.drawRoundRect(rect, 32f, 32f, almostBorderPaint)
            CaptureReadiness.NOT_READY -> {}
        }
    }

    /**
     * 基于系统时间的脉冲相位（0..1 平滑振荡，1.2s 周期）。
     * overlay 每帧由相机回调重绘，无需额外 Animator。
     */
    private fun currentPulsePhase(): Float {
        val periodMs = 1200L
        val t = (System.currentTimeMillis() % periodMs) / periodMs.toFloat()
        return (0.5f - 0.5f * cos(t * 2f * PI)).toFloat()
    }

    private fun drawSubject(canvas: Canvas) {
        val subject = frame.subjectBox ?: return
        val rect = RectF(
            subject.left * width,
            subject.top * height,
            subject.right * width,
            subject.bottom * height,
        )
        val subjectColor = when (frame.captureReadiness) {
            CaptureReadiness.READY -> ContextCompat.getColor(context, R.color.readiness_ready)
            CaptureReadiness.ALMOST_READY -> ContextCompat.getColor(context, R.color.readiness_almost)
            CaptureReadiness.NOT_READY -> ContextCompat.getColor(context, R.color.subject_box_warn)
        }
        subjectPaint.color = subjectColor
        canvas.drawRoundRect(rect, 28f, 28f, subjectPaint)
        val radius = min(width, height) * 0.008f
        canvas.drawCircle(rect.centerX(), rect.centerY(), radius, centerPaint)
    }

    private fun drawTargetZone(canvas: Canvas) {
        if (!aiAssistEnabled) return
        val target = frame.targetZone ?: return
        val subject = frame.subjectBox ?: return

        val targetRect = RectF(
            target.rect.left * width,
            target.rect.top * height,
            target.rect.right * width,
            target.rect.bottom * height,
        )
        canvas.drawRoundRect(targetRect, 28f, 28f, targetZonePaint)

        // 连接当前主体中心到目标中心的虚线，提示移动方向
        val subjectCenterX = subject.centerX * width
        val subjectCenterY = subject.centerY * height
        canvas.drawLine(
            subjectCenterX,
            subjectCenterY,
            targetRect.centerX(),
            targetRect.centerY(),
            targetConnectorPaint,
        )
    }

    private fun drawDirectionArrow(canvas: Canvas) {
        if (!aiAssistEnabled) return
        val hint = frame.cameraAction.directionHint
        if (hint == DirectionHint.NONE) return

        val arrowColor = when (frame.captureReadiness) {
            CaptureReadiness.READY -> ContextCompat.getColor(context, R.color.readiness_ready)
            CaptureReadiness.ALMOST_READY -> ContextCompat.getColor(context, R.color.readiness_almost)
            CaptureReadiness.NOT_READY -> ContextCompat.getColor(context, R.color.readiness_not_ready)
        }
        // 箭头呼吸效果，让指导"活"起来
        val pulse = currentPulsePhase()
        val arrowAlpha = (165 + 90 * pulse).toInt().coerceIn(0, 255)
        arrowPaint.color = arrowColor
        arrowPaint.alpha = arrowAlpha
        arrowFillPaint.color = arrowColor
        arrowFillPaint.alpha = arrowAlpha

        val centerX = width / 2f
        val centerY = height / 2f
        val arrowLength = width * 0.15f
        val arrowHeadSize = width * 0.04f

        val path = Path()
        when (hint) {
            DirectionHint.MOVE_LEFT -> {
                val startX = centerX - arrowLength * 0.3f
                val endX = startX - arrowLength
                path.moveTo(endX + arrowHeadSize, centerY - arrowHeadSize)
                path.lineTo(endX, centerY)
                path.lineTo(endX + arrowHeadSize, centerY + arrowHeadSize)
                canvas.drawPath(path, arrowFillPaint)
                canvas.drawLine(endX + arrowHeadSize, centerY, startX, centerY, arrowPaint)
            }
            DirectionHint.MOVE_RIGHT -> {
                val startX = centerX + arrowLength * 0.3f
                val endX = startX + arrowLength
                path.moveTo(endX - arrowHeadSize, centerY - arrowHeadSize)
                path.lineTo(endX, centerY)
                path.lineTo(endX - arrowHeadSize, centerY + arrowHeadSize)
                canvas.drawPath(path, arrowFillPaint)
                canvas.drawLine(startX, centerY, endX - arrowHeadSize, centerY, arrowPaint)
            }
            DirectionHint.MOVE_UP -> {
                val startY = centerY - arrowLength * 0.3f
                val endY = startY - arrowLength
                path.moveTo(centerX - arrowHeadSize, endY + arrowHeadSize)
                path.lineTo(centerX, endY)
                path.lineTo(centerX + arrowHeadSize, endY + arrowHeadSize)
                canvas.drawPath(path, arrowFillPaint)
                canvas.drawLine(centerX, startY, centerX, endY + arrowHeadSize, arrowPaint)
            }
            DirectionHint.MOVE_DOWN -> {
                val startY = centerY + arrowLength * 0.3f
                val endY = startY + arrowLength
                path.moveTo(centerX - arrowHeadSize, endY - arrowHeadSize)
                path.lineTo(centerX, endY)
                path.lineTo(centerX + arrowHeadSize, endY - arrowHeadSize)
                canvas.drawPath(path, arrowFillPaint)
                canvas.drawLine(centerX, startY, centerX, endY - arrowHeadSize, arrowPaint)
            }
            DirectionHint.TILT_LEFT -> {
                val arcRadius = width * 0.12f
                val arcRect = RectF(
                    centerX - arcRadius,
                    centerY - arcRadius,
                    centerX + arcRadius,
                    centerY + arcRadius,
                )
                canvas.drawArc(arcRect, 0f, -60f, false, arrowPaint)
                val arrowTipX = centerX + arcRadius * kotlin.math.cos(kotlin.math.PI * -60f / 180f).toFloat()
                val arrowTipY = centerY + arcRadius * kotlin.math.sin(kotlin.math.PI * -60f / 180f).toFloat()
                path.reset()
                path.moveTo(arrowTipX - arrowHeadSize * 0.7f, arrowTipY - arrowHeadSize)
                path.lineTo(arrowTipX, arrowTipY)
                path.lineTo(arrowTipX + arrowHeadSize, arrowTipY - arrowHeadSize * 0.7f)
                canvas.drawPath(path, arrowFillPaint)
            }
            DirectionHint.TILT_RIGHT -> {
                val arcRadius = width * 0.12f
                val arcRect = RectF(
                    centerX - arcRadius,
                    centerY - arcRadius,
                    centerX + arcRadius,
                    centerY + arcRadius,
                )
                canvas.drawArc(arcRect, 0f, 60f, false, arrowPaint)
                val arrowTipX = centerX + arcRadius * kotlin.math.cos(kotlin.math.PI * 60f / 180f).toFloat()
                val arrowTipY = centerY + arcRadius * kotlin.math.sin(kotlin.math.PI * 60f / 180f).toFloat()
                path.reset()
                path.moveTo(arrowTipX - arrowHeadSize, arrowTipY - arrowHeadSize * 0.7f)
                path.lineTo(arrowTipX, arrowTipY)
                path.lineTo(arrowTipX + arrowHeadSize * 0.7f, arrowTipY - arrowHeadSize)
                canvas.drawPath(path, arrowFillPaint)
            }
            DirectionHint.NONE -> {}
        }
    }

    /**
     * 绘制云端指导状态：
     * - Loading：右上角"AI 分析中"呼吸点动画
     * - Enhanced：右上角显示云端增强文案
     */
    private fun drawCloudGuidance(canvas: Canvas) {
        when (val state = cloudGuidanceState) {
            CloudGuidanceUiState.Loading -> {
                val pulse = currentPulsePhase()
                val dotRadius = (6f + 4f * pulse).coerceIn(4f, 12f)
                val dotAlpha = (120 + 135 * pulse).toInt().coerceIn(0, 255)
                val dotX = width - 80f
                val dotY = 80f
                arrowFillPaint.color = ContextCompat.getColor(context, R.color.target_zone)
                arrowFillPaint.alpha = dotAlpha
                canvas.drawCircle(dotX, dotY, dotRadius, arrowFillPaint)
            }
            is CloudGuidanceUiState.Enhanced -> {
                val message = state.guidance.message
                if (message.isNotBlank()) {
                    val text = "AI：$message"
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = ContextCompat.getColor(context, R.color.target_zone)
                        textSize = 32f
                        style = Paint.Style.FILL
                    }
                    val textWidth = textPaint.measureText(text)
                    val textX = width - textWidth - 24f
                    canvas.drawText(text, textX, 88f, textPaint)
                }
            }
            CloudGuidanceUiState.Idle -> {}
        }
    }
}

