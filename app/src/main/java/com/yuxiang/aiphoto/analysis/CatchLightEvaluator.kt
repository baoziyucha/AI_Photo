package com.yuxiang.aiphoto.analysis

import com.yuxiang.aiphoto.model.NormalizedRect

/**
 * 眼神光（catchlight）检测器：在 luma 上采样眼部周围 patch，判定是否有高亮点。
 *
 * 摄影经验：好人像通常在眼睛里有一个小高光（眼神光），让眼睛"有神"。
 * 无眼神光时提示"脸再转向光一点"。
 *
 * 用 faceBox 上 1/3 近似眼部位置，不依赖 landmark 导出，避免跨层数据流改动。
 */
object CatchLightEvaluator {

    /** catchlight 判定：patch 内最高亮度 > 此值。 */
    private const val MAX_LUMA_THRESHOLD = 140

    /** catchlight 判定：最高亮度与均值之差 > 此值（突出高光点）。 */
    private const val CONTRAST_THRESHOLD = 50f

    /**
     * @param luma Y 平面字节数组
     * @param width luma 宽度（像素）
     * @param height luma 高度（像素）
     * @param faceBox 人脸框（归一化坐标）
     * @param leftEyeOpenProb 左眼睁开概率
     * @param rightEyeOpenProb 右眼睁开概率
     * @return null 无法判定（眼睛未睁开或数据缺失）；true 有眼神光；false 无眼神光
     */
    fun evaluate(
        luma: ByteArray,
        width: Int,
        height: Int,
        faceBox: NormalizedRect,
        leftEyeOpenProb: Float?,
        rightEyeOpenProb: Float?,
    ): Boolean? {
        // 眼睛未睁开时不判定（闭眼时无 catchlight 意义）
        val minEyeOpen = listOfNotNull(leftEyeOpenProb, rightEyeOpenProb).minOrNull()
        if (minEyeOpen != null && minEyeOpen < 0.5f) return null

        // 眼部位置近似：faceBox 上 1/3 的中心，左右各偏移
        val eyeY = faceBox.top + faceBox.height * 0.33f
        val eyeOffsetX = faceBox.width * 0.22f
        val eyeCenterX = (faceBox.left + faceBox.right) / 2f
        val leftEyeX = eyeCenterX - eyeOffsetX
        val rightEyeX = eyeCenterX + eyeOffsetX

        val leftHas = hasCatchLight(luma, width, height, leftEyeX, eyeY, faceBox.width)
        val rightHas = hasCatchLight(luma, width, height, rightEyeX, eyeY, faceBox.width)
        return leftHas || rightHas
    }

    private fun hasCatchLight(
        luma: ByteArray,
        width: Int,
        height: Int,
        normX: Float,
        normY: Float,
        faceWidth: Float,
    ): Boolean {
        val cx = (normX * width).toInt().coerceIn(0, width - 1)
        val cy = (normY * height).toInt().coerceIn(0, height - 1)
        // patch 半径 = faceWidth * width * 0.04（约脸宽的 4%）
        val radius = (faceWidth * width * 0.04f).toInt().coerceIn(3, 20)

        var maxLuma = 0
        var sumLuma = 0f
        var count = 0
        for (dy in -radius..radius step 2) {
            for (dx in -radius..radius step 2) {
                val x = (cx + dx).coerceIn(0, width - 1)
                val y = (cy + dy).coerceIn(0, height - 1)
                val v = luma[y * width + x].toUByte().toInt()
                if (v > maxLuma) maxLuma = v
                sumLuma += v
                count++
            }
        }
        if (count == 0) return false
        val meanLuma = sumLuma / count
        // catchlight：patch 内有高亮点（max > 阈值）且明显高于均值
        return maxLuma > MAX_LUMA_THRESHOLD && (maxLuma - meanLuma) > CONTRAST_THRESHOLD
    }
}
