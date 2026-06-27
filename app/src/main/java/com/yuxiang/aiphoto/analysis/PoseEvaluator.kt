package com.yuxiang.aiphoto.analysis

import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import com.yuxiang.aiphoto.model.NormalizedRect
import com.yuxiang.aiphoto.model.PoseMetrics
import kotlin.math.atan2
import kotlin.math.abs

/**
 * P2-5 姿态评估：基于 ML Kit Pose 33 个关节点。
 *
 * 三项检查：
 * 1. 关节裁切：肩/肘/腕置信度高但位置贴边 → 出框风险
 * 2. 手部遮挡脸：腕部坐标落入 faceBox → 提示放下手
 * 3. 双肩倾斜：肩连线与水平线夹角 > 12° → 提示放松肩膀
 *
 * 所有坐标都按 ML Kit 输出（已通过 InputImage 旋转矫正）归一化到 [0,1]，
 * 前置相机时由调用方对 x 做镜像后再传入。
 */
object PoseEvaluator {

    /** 关节置信度阈值：低于此值视为关节不可见/出框。 */
    private const val CONFIDENCE_THRESHOLD = 0.5f

    /** 关节贴边容差（归一化 0-1，距边 5% 内视为贴边）。 */
    private const val EDGE_TOLERANCE = 0.05f

    /** 双肩倾斜阈值（度）：超过此值提示歪肩。 */
    private const val SHOULDER_TILT_THRESHOLD = 12f

    /** 需要检查"贴边裁切"的关节清单（左侧成对，前置镜像已处理）。 */
    private val edgeCheckJoints = listOf(
        PoseLandmark.LEFT_SHOULDER to "左肩",
        PoseLandmark.RIGHT_SHOULDER to "右肩",
        PoseLandmark.LEFT_ELBOW to "左肘",
        PoseLandmark.RIGHT_ELBOW to "右肘",
        PoseLandmark.LEFT_WRIST to "左腕",
        PoseLandmark.RIGHT_WRIST to "右腕",
    )

    fun evaluate(
        pose: Pose,
        imageWidth: Int,
        imageHeight: Int,
        faceBox: NormalizedRect?,
        mirrorX: Boolean,
    ): PoseMetrics {
        if (imageWidth <= 0 || imageHeight <= 0) {
            return PoseMetrics()
        }

        // 1. 关节贴边检测：返回第一个贴边的关节名
        val croppedJoint = edgeCheckJoints.firstNotNullOfOrNull { (type, name) ->
            val pos = normalizedLandmark(pose, type, imageWidth, imageHeight, mirrorX)
            if (pos != null && isAtEdge(pos.first, pos.second)) name else null
        }

        // 2. 手部遮挡脸：腕坐标落入 faceBox
        val handCoveringFace = faceBox != null && (
            isWristInsideFace(pose, PoseLandmark.LEFT_WRIST, imageWidth, imageHeight, mirrorX, faceBox) ||
            isWristInsideFace(pose, PoseLandmark.RIGHT_WRIST, imageWidth, imageHeight, mirrorX, faceBox)
        )

        // 3. 双肩倾斜
        val leftShoulder = normalizedLandmark(pose, PoseLandmark.LEFT_SHOULDER, imageWidth, imageHeight, mirrorX)
        val rightShoulder = normalizedLandmark(pose, PoseLandmark.RIGHT_SHOULDER, imageWidth, imageHeight, mirrorX)
        val shoulderAngle = if (leftShoulder != null && rightShoulder != null) {
            val dy = rightShoulder.second - leftShoulder.second
            val dx = rightShoulder.first - leftShoulder.first
            val angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
            // 归一化到 -90..90
            val normalized = when {
                angleDeg > 90f -> angleDeg - 180f
                angleDeg < -90f -> angleDeg + 180f
                else -> angleDeg
            }
            normalized
        } else null

        val shoulderImbalance = shoulderAngle?.let { abs(it) }?.takeIf { it > SHOULDER_TILT_THRESHOLD }

        return PoseMetrics(
            hasJointAtEdge = croppedJoint != null,
            croppedJointName = croppedJoint,
            hasHandCoveringFace = handCoveringFace,
            shoulderImbalanceDeg = shoulderImbalance,
        )
    }

    /** 取归一化的关节坐标，置信度不足或关节缺失时返回 null。前置镜像在此时应用。 */
    private fun normalizedLandmark(
        pose: Pose,
        type: Int,
        imageWidth: Int,
        imageHeight: Int,
        mirrorX: Boolean,
    ): Pair<Float, Float>? {
        val landmark = pose.getPoseLandmark(type) ?: return null
        if (landmark.inFrameLikelihood < CONFIDENCE_THRESHOLD) return null
        val rawX = (landmark.x / imageWidth).coerceIn(0f, 1f)
        val x = if (mirrorX) 1f - rawX else rawX
        val y = (landmark.y / imageHeight).coerceIn(0f, 1f)
        return x to y
    }

    private fun isAtEdge(x: Float, y: Float): Boolean {
        return x < EDGE_TOLERANCE || x > 1f - EDGE_TOLERANCE ||
            y < EDGE_TOLERANCE || y > 1f - EDGE_TOLERANCE
    }

    private fun isWristInsideFace(
        pose: Pose,
        type: Int,
        imageWidth: Int,
        imageHeight: Int,
        mirrorX: Boolean,
        faceBox: NormalizedRect,
    ): Boolean {
        val wrist = normalizedLandmark(pose, type, imageWidth, imageHeight, mirrorX) ?: return false
        return wrist.first in faceBox.left..faceBox.right &&
            wrist.second in faceBox.top..faceBox.bottom
    }
}
