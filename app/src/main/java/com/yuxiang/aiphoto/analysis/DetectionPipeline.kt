package com.yuxiang.aiphoto.analysis

import android.graphics.Rect
import androidx.core.graphics.toRectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.yuxiang.aiphoto.model.BrightnessState
import com.yuxiang.aiphoto.model.FaceLightMetrics
import com.yuxiang.aiphoto.model.LightDirection
import com.yuxiang.aiphoto.model.NormalizedRect
import com.yuxiang.aiphoto.model.SceneType
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.tasks.await

data class FaceDetectionResult(
    val subjectBox: NormalizedRect?,
    val faceCount: Int,
    val confidence: Float,
    @Deprecated("Use headEulerX", ReplaceWith("headEulerX"))
    val facePitchDeg: Float? = null,
    val smilingProbability: Float? = null,
    val leftEyeOpenProb: Float? = null,
    val rightEyeOpenProb: Float? = null,
    val headEulerX: Float? = null,
    val headEulerY: Float? = null,
    val headEulerZ: Float? = null,
    val faceLightMetrics: FaceLightMetrics? = null,
)

data class SalientSubjectResult(
    val subjectBox: NormalizedRect,
    val confidence: Float,
)

data class BrightnessAnalysisResult(
    val brightnessState: BrightnessState,
    val lightDirection: LightDirection,
)

class FaceSubjectDetector {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.12f)
            .enableTracking()
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build(),
    )

    suspend fun detect(
        inputImage: InputImage,
        frameWidth: Int,
        frameHeight: Int,
        rotationDegrees: Int,
        mirrorX: Boolean,
    ): FaceDetectionResult {
        val faces = detector.process(inputImage).await()
        if (faces.isEmpty()) {
            return FaceDetectionResult(subjectBox = null, faceCount = 0, confidence = 0f)
        }
        val orientedWidth = if (rotationDegrees == 90 || rotationDegrees == 270) frameHeight else frameWidth
        val orientedHeight = if (rotationDegrees == 90 || rotationDegrees == 270) frameWidth else frameHeight
        val primary = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        val normalized = primary?.boundingBox?.toNormalizedRect(
            frameWidth = orientedWidth,
            frameHeight = orientedHeight,
            mirrorX = mirrorX,
        )
        val smiling = primary?.smilingProbability
        val leftEyeOpen = primary?.leftEyeOpenProbability
        val rightEyeOpen = primary?.rightEyeOpenProbability
        // 三轴头姿：MLKit 原生 headEulerAngleX/Y/Z
        // - headEulerX（pitch，低头/抬头）：不受水平镜像影响
        // - headEulerY（yaw，左右转脸）：前置镜像下取负，使下游 gazeDirection 统一
        // - headEulerZ（roll，歪头）：前置镜像下取负（左右翻转时 roll 方向反转）
        val headPitchFromMlkit = primary?.headEulerAngleX
        val headYaw = primary?.headEulerAngleY?.let { if (mirrorX) -it else it }
        val headRoll = primary?.headEulerAngleZ?.let { if (mirrorX) -it else it }
        // fallback：MLKit 未返回 headEulerAngleX 时，用 landmarks 几何估算
        @Suppress("DEPRECATION")
        val facePitchFallback = if (headPitchFromMlkit == null) {
            primary?.let { estimateFacePitch(it, orientedWidth, orientedHeight, mirrorX) }
        } else {
            null
        }
        return FaceDetectionResult(
            subjectBox = normalized,
            faceCount = faces.size,
            confidence = 0.92f,
            @Suppress("DEPRECATION")
            facePitchDeg = facePitchFallback,
            smilingProbability = smiling,
            leftEyeOpenProb = leftEyeOpen,
            rightEyeOpenProb = rightEyeOpen,
            headEulerX = headPitchFromMlkit ?: facePitchFallback,
            headEulerY = headYaw,
            headEulerZ = headRoll,
        )
    }

    private fun estimateFacePitch(face: com.google.mlkit.vision.face.Face, frameWidth: Int, frameHeight: Int, mirrorX: Boolean): Float? {
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position

        if (leftEye == null || rightEye == null) return null

        val leftEyeX = normalizeCoord(leftEye.x, frameWidth, mirrorX)
        val rightEyeX = normalizeCoord(rightEye.x, frameWidth, mirrorX)
        val eyeMidY = (leftEye.y + rightEye.y) / 2f

        val targetY = mouthBottom?.y ?: nose?.y ?: return null
        val targetX = mouthBottom?.let { normalizeCoord(it.x, frameWidth, mirrorX) }
            ?: nose?.let { normalizeCoord(it.x, frameWidth, mirrorX) }
            ?: (leftEyeX + rightEyeX) / 2f

        val dx = targetX - (leftEyeX + rightEyeX) / 2f
        val dy = targetY - eyeMidY
        if (abs(dy) < 5f) return null

        val pitchRad = atan2(dx.toDouble(), dy.toDouble())
        val pitchDeg = Math.toDegrees(pitchRad).toFloat()
        return (pitchDeg * 10f).roundToInt() / 10f
    }

    private fun normalizeCoord(x: Float, frameWidth: Int, mirrorX: Boolean): Float {
        return if (mirrorX) {
            frameWidth - x
        } else {
            x
        }
    }

    fun close() {
        detector.close()
    }
}

/**
 * P2-5 姿态检测器：包装 ML Kit PoseDetection，STREAM_MODE 跟踪最显著人体。
 * 仅在 FaceSubjectDetector 找到人脸后调用，避免无谓的人体检索开销。
 * 返回 ML Kit Pose 对象（已应用旋转矫正），由 PoseEvaluator 进一步分析。
 */
class PoseSubjectDetector {
    private val detector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build(),
    )

    suspend fun detect(inputImage: InputImage): Pose? {
        return runCatching { detector.process(inputImage).await() }.getOrNull()
    }

    fun close() {
        detector.close()
    }
}

class LumaSubjectDetector {
    fun detect(
        luma: ByteArray,
        width: Int,
        height: Int,
        mirrorX: Boolean,
    ): SalientSubjectResult? {
        val columns = 16
        val rows = 12
        val cellWidth = width / columns
        val cellHeight = height / rows
        if (cellWidth <= 0 || cellHeight <= 0) return null

        val means = computeGrid(luma, width, height)
        var globalSum = 0f
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                globalSum += means[row][column]
            }
        }
        val globalMean = globalSum / (rows * columns)
        val scores = Array(rows) { FloatArray(columns) }
        var totalScore = 0f
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val localContrast = neighborContrast(means, row, column)
                val centerBias = 1f - ((abs(column - (columns - 1) / 2f) / columns) + (abs(row - (rows - 1) / 2f) / rows))
                val score = abs(means[row][column] - globalMean) * 0.65f + localContrast * 0.75f + centerBias * 10f
                scores[row][column] = score
                totalScore += score
            }
        }

        val averageScore = totalScore / (rows * columns)
        val threshold = averageScore * 1.18f
        var minColumn = columns
        var minRow = rows
        var maxColumn = -1
        var maxRow = -1
        var selectedCells = 0
        var selectedScore = 0f
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val score = scores[row][column]
                if (score < threshold) continue
                minColumn = minOf(minColumn, column)
                minRow = minOf(minRow, row)
                maxColumn = maxOf(maxColumn, column)
                maxRow = maxOf(maxRow, row)
                selectedCells++
                selectedScore += score
            }
        }
        if (selectedCells < 4 || maxColumn < 0 || maxRow < 0) return null

        val rect = NormalizedRect(
            left = minColumn / columns.toFloat(),
            top = minRow / rows.toFloat(),
            right = (maxColumn + 1) / columns.toFloat(),
            bottom = (maxRow + 1) / rows.toFloat(),
        ).clamped()
        if (rect.area < 0.06f) return null

        val mirrored = if (mirrorX) {
            NormalizedRect(
                left = 1f - rect.right,
                top = rect.top,
                right = 1f - rect.left,
                bottom = rect.bottom,
            )
        } else {
            rect
        }

        val confidence = ((selectedScore / selectedCells) / 60f).coerceIn(0.18f, 0.8f)
        return SalientSubjectResult(mirrored.clamped(), confidence)
    }

    /** 计算 16×12 亮度网格，供 FaceLightEvaluator 复用。 */
    fun computeGrid(luma: ByteArray, width: Int, height: Int): Array<FloatArray> {
        val columns = 16
        val rows = 12
        val cellWidth = width / columns
        val cellHeight = height / rows
        val means = Array(rows) { FloatArray(columns) }
        if (cellWidth <= 0 || cellHeight <= 0) return means
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                var sum = 0f
                var count = 0
                val startX = column * cellWidth
                val endX = if (column == columns - 1) width else (column + 1) * cellWidth
                val startY = row * cellHeight
                val endY = if (row == rows - 1) height else (row + 1) * cellHeight
                for (y in startY until endY step 2) {
                    val base = y * width
                    for (x in startX until endX step 2) {
                        sum += luma[base + x].toUByte().toInt()
                        count++
                    }
                }
                means[row][column] = if (count == 0) 0f else sum / count
            }
        }
        return means
    }

    private fun neighborContrast(means: Array<FloatArray>, row: Int, column: Int): Float {
        val current = means[row][column]
        var total = 0f
        var count = 0
        for (rowOffset in -1..1) {
            for (columnOffset in -1..1) {
                if (rowOffset == 0 && columnOffset == 0) continue
                val targetRow = row + rowOffset
                val targetColumn = column + columnOffset
                if (targetRow !in means.indices || targetColumn !in means[0].indices) continue
                total += abs(current - means[targetRow][targetColumn])
                count++
            }
        }
        return if (count == 0) 0f else total / count
    }
}

class BrightnessEvaluator {
    fun evaluate(luma: ByteArray, width: Int, height: Int, subjectBox: NormalizedRect?): BrightnessAnalysisResult {
        val globalMean = averageLuma(luma, width, height, 0f, 0f, 1f, 1f)
        val subjectMean = subjectBox?.let {
            averageLuma(luma, width, height, it.left, it.top, it.right, it.bottom)
        } ?: averageLuma(luma, width, height, 0.28f, 0.28f, 0.72f, 0.72f)

        val brightnessState = when {
            globalMean >= 215f || subjectMean >= 225f -> BrightnessState.OVEREXPOSED
            globalMean >= 145f && subjectMean + 18f < globalMean -> BrightnessState.BACKLIT
            globalMean <= 72f || subjectMean <= 65f -> BrightnessState.LOW_LIGHT
            else -> BrightnessState.BALANCED
        }

        val lightDirection = estimateLightDirection(luma, width, height, subjectBox)
        return BrightnessAnalysisResult(brightnessState, lightDirection)
    }

    private fun estimateLightDirection(
        luma: ByteArray,
        width: Int,
        height: Int,
        subjectBox: NormalizedRect?,
    ): LightDirection {
        val leftMean = averageLuma(luma, width, height, 0f, 0f, 0.5f, 1f)
        val rightMean = averageLuma(luma, width, height, 0.5f, 0f, 1f, 1f)
        val topMean = averageLuma(luma, width, height, 0f, 0f, 1f, 0.5f)
        val bottomMean = averageLuma(luma, width, height, 0f, 0.5f, 1f, 1f)

        val threshold = 15f

        val horizontalDiff = leftMean - rightMean
        val verticalDiff = topMean - bottomMean

        return when {
            abs(horizontalDiff) > threshold && horizontalDiff > abs(verticalDiff) -> {
                if (horizontalDiff > 0) LightDirection.LEFT else LightDirection.RIGHT
            }
            abs(verticalDiff) > threshold && verticalDiff > abs(horizontalDiff) -> {
                if (verticalDiff > 0) LightDirection.TOP else LightDirection.BOTTOM
            }
            else -> LightDirection.UNKNOWN
        }
    }

    private fun averageLuma(
        luma: ByteArray,
        width: Int,
        height: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Float {
        val startX = (left.coerceIn(0f, 1f) * width).roundToInt().coerceIn(0, width - 1)
        val endX = (right.coerceIn(0f, 1f) * width).roundToInt().coerceIn(startX + 1, width)
        val startY = (top.coerceIn(0f, 1f) * height).roundToInt().coerceIn(0, height - 1)
        val endY = (bottom.coerceIn(0f, 1f) * height).roundToInt().coerceIn(startY + 1, height)
        var sum = 0f
        var count = 0
        for (y in startY until endY step 3) {
            val base = y * width
            for (x in startX until endX step 3) {
                sum += luma[base + x].toUByte().toInt()
                count++
            }
        }
        return if (count == 0) 0f else sum / count
    }
}

class ImageTiltEstimator {
    fun estimate(luma: ByteArray, width: Int, height: Int): Float? {
        if (width < 8 || height < 8) return null
        val bins = FloatArray(61)
        var totalEnergy = 0f
        for (y in 2 until height - 2 step 4) {
            for (x in 2 until width - 2 step 4) {
                val gx = sample(luma, width, x + 1, y) - sample(luma, width, x - 1, y)
                val gy = sample(luma, width, x, y + 1) - sample(luma, width, x, y - 1)
                val magnitude = sqrt(gx * gx + gy * gy)
                if (magnitude < 18f) continue
                var lineAngle = Math.toDegrees(atan2(gy.toDouble(), gx.toDouble())).toFloat() + 90f
                while (lineAngle > 90f) lineAngle -= 180f
                while (lineAngle < -90f) lineAngle += 180f
                if (abs(lineAngle) > 30f) continue
                val index = (lineAngle + 30f).roundToInt().coerceIn(0, bins.lastIndex)
                bins[index] += magnitude
                totalEnergy += magnitude
            }
        }
        if (totalEnergy < 180f) return null
        val bestIndex = bins.indices.maxByOrNull { bins[it] } ?: return null
        val confidence = bins[bestIndex] / totalEnergy
        if (confidence < 0.09f) return null
        return bestIndex - 30f
    }

    private fun sample(luma: ByteArray, width: Int, x: Int, y: Int): Float {
        return luma[y * width + x].toUByte().toInt().toFloat()
    }
}

object SceneClassifier {
    fun classify(
        faceCount: Int,
        subjectBox: NormalizedRect?,
        isFrontCamera: Boolean,
    ): SceneType {
        if (faceCount > 0) {
            return if (isFrontCamera) SceneType.SELFIE else SceneType.PORTRAIT
        }
        if (subjectBox != null && subjectBox.height <= 0.45f && subjectBox.bottom > 0.58f) {
            return SceneType.PET_OR_CHILD
        }
        return SceneType.DAILY_GENERIC
    }
}

private fun Rect.toNormalizedRect(frameWidth: Int, frameHeight: Int, mirrorX: Boolean): NormalizedRect {
    val raw = toRectF()
    val rect = NormalizedRect(
        left = raw.left / frameWidth,
        top = raw.top / frameHeight,
        right = raw.right / frameWidth,
        bottom = raw.bottom / frameHeight,
    ).clamped()
    return if (mirrorX) {
        NormalizedRect(
            left = 1f - rect.right,
            top = rect.top,
            right = 1f - rect.left,
            bottom = rect.bottom,
        ).clamped()
    } else {
        rect
    }
}
