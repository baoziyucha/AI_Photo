package com.yuxiang.aiphoto.analysis

import com.yuxiang.aiphoto.model.FaceLightMetrics
import com.yuxiang.aiphoto.model.FaceSide
import com.yuxiang.aiphoto.model.NormalizedRect
import kotlin.math.roundToInt

/**
 * 左右脸光比评估器：把"看光"的摄影经验固化为可计算事实。
 * 把 LumaSubjectDetector 的 16×12 网格按 faceBox 范围切分为左右两半，分别计算均值。
 * faceLightRatio = max(left, right) / min(left, right)（防除零）。
 */
object FaceLightEvaluator {

    /** 左右脸光比阈值：>1.5 判定阴阳脸。 */
    const val RATIO_THRESHOLD = 1.5f

    /** 严重阴阳脸阈值：>2.0 降级到 NOT_READY。 */
    const val SEVERE_RATIO_THRESHOLD = 2.0f

    private const val GRID_COLUMNS = 16
    private const val GRID_ROWS = 12

    /**
     * @param faceBox 人脸框（归一化坐标）
     * @param lumaGrid 16×12 的亮度网格（来自 LumaSubjectDetector 的 means）
     * @param mirrorX 是否镜像（前置摄像头）
     */
    fun evaluate(
        faceBox: NormalizedRect,
        lumaGrid: Array<FloatArray>,
        mirrorX: Boolean,
    ): FaceLightMetrics {
        if (lumaGrid.isEmpty() || lumaGrid[0].isEmpty()) {
            return FaceLightMetrics()
        }
        val rows = lumaGrid.size
        val cols = lumaGrid[0].size

        // 将 faceBox 映射到网格坐标
        val startCol = (faceBox.left * cols).toInt().coerceIn(0, cols - 1)
        val endCol = (faceBox.right * cols).toInt().coerceIn(startCol + 1, cols)
        val startRow = (faceBox.top * rows).toInt().coerceIn(0, rows - 1)
        val endRow = (faceBox.bottom * rows).toInt().coerceIn(startRow + 1, rows)

        val midCol = (startCol + endCol) / 2

        var leftSum = 0f
        var leftCount = 0
        var rightSum = 0f
        var rightCount = 0

        for (row in startRow until endRow) {
            for (col in startCol until endCol) {
                val value = luminanceAt(lumaGrid, row, col)
                // 镜像时左右互换，保证 shadowSide 与用户视角一致
                val isLeftHalf = if (mirrorX) col >= midCol else col < midCol
                if (isLeftHalf) {
                    leftSum += value
                    leftCount++
                } else {
                    rightSum += value
                    rightCount++
                }
            }
        }

        if (leftCount == 0 || rightCount == 0) return FaceLightMetrics()

        val leftLuma = (leftSum / leftCount * 10f).roundToInt() / 10f
        val rightLuma = (rightSum / rightCount * 10f).roundToInt() / 10f
        val ratio = if (leftLuma <= 0f || rightLuma <= 0f) {
            1f
        } else {
            maxOf(leftLuma, rightLuma) / minOf(leftLuma, rightLuma)
        }
        val shadowSide = when {
            leftLuma < rightLuma -> FaceSide.LEFT
            rightLuma < leftLuma -> FaceSide.RIGHT
            else -> null
        }

        return FaceLightMetrics(
            leftFaceLuma = leftLuma,
            rightFaceLuma = rightLuma,
            faceLightRatio = ratio,
            shadowSide = shadowSide,
        )
    }

    private fun luminanceAt(grid: Array<FloatArray>, row: Int, col: Int): Float {
        val r = row.coerceIn(0, grid.lastIndex)
        val c = col.coerceIn(0, grid[r].lastIndex)
        return grid[r][c]
    }
}
