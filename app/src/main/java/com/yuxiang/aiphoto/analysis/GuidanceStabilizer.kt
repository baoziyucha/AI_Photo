package com.yuxiang.aiphoto.analysis

import com.yuxiang.aiphoto.model.CameraAction
import com.yuxiang.aiphoto.model.CaptureReadiness
import com.yuxiang.aiphoto.model.DirectionHint
import com.yuxiang.aiphoto.model.GuidanceFrame

class GuidanceStabilizer(
    private val stableFrames: Int = 3,
) {
    private var lastFingerprint: String? = null
    private var consecutiveFrames = 0
    private var lastReadiness: CaptureReadiness? = null
    private var readinessConsecutiveFrames = 0

    // 缓存上一次稳定帧的指导文案与方向，用于用户调整期间保持指导可见
    private var lastStableRecommendation: String = ""
    private var lastStableDirectionHint: DirectionHint = DirectionHint.NONE

    fun stabilize(raw: GuidanceFrame): GuidanceFrame {
        val fingerprint = buildFingerprint(raw)
        consecutiveFrames = if (fingerprint == lastFingerprint) consecutiveFrames + 1 else 1
        lastFingerprint = fingerprint

        val readinessFingerprint = raw.captureReadiness
        readinessConsecutiveFrames = if (readinessFingerprint == lastReadiness) {
            readinessConsecutiveFrames + 1
        } else {
            1
        }
        lastReadiness = readinessFingerprint

        val stableReadiness = if (readinessConsecutiveFrames >= stableFrames) {
            raw.captureReadiness
        } else {
            CaptureReadiness.NOT_READY
        }

        return if (consecutiveFrames >= stableFrames) {
            lastStableRecommendation = raw.recommendationText
            lastStableDirectionHint = raw.cameraAction.directionHint
            raw.copy(
                isStable = true,
                captureReadiness = stableReadiness,
            )
        } else {
            // 不稳定帧：保留上一次稳定的指导文案与方向箭头，避免用户调整时指导消失。
            // 主体框、亮度、倾斜等检测数据仍用当前帧，保证主体跟踪与测光跟手。
            raw.copy(
                recommendationText = lastStableRecommendation,
                cameraAction = raw.cameraAction.copy(directionHint = lastStableDirectionHint),
                isStable = false,
                captureReadiness = stableReadiness,
            )
        }
    }

    fun reset() {
        lastFingerprint = null
        consecutiveFrames = 0
        lastReadiness = null
        readinessConsecutiveFrames = 0
        lastStableRecommendation = ""
        lastStableDirectionHint = DirectionHint.NONE
    }

    private fun buildFingerprint(frame: GuidanceFrame): String {
        val subject = frame.subjectBox?.let {
            "${bucket(it.centerX)}:${bucket(it.centerY)}:${bucket(it.area)}"
        } ?: "none"
        val yawBucket = frame.headEulerY?.let { (it / 5f).toInt() } ?: "_"       // ±5° 一档
        val rollBucket = frame.headEulerZ?.let { (it / 3f).toInt() } ?: "_"        // ±3° 一档
        val ratioBucket = frame.faceLightMetrics?.faceLightRatio?.let { r ->
            when { r > 2.0f -> 2; r > 1.5f -> 1; else -> 0 }
        } ?: "_"
        val bgBucket = frame.backgroundMetrics?.let { bg ->
            val h = if (bg.hasHotspotNearHead == true) 1 else 0
            val v = if (bg.hasVerticalLineAboveHead == true) 1 else 0
            val c = bg.clutterScore?.let { if (it > 0.6f) 1 else 0 } ?: 0
            "$h$v$c"
        } ?: "_"
        // P2-5 pose 指纹：三位二进制（joint/hand/shoulder），变化时重新触发稳定窗口
        val poseBucket = frame.poseMetrics?.let { p ->
            val j = if (p.hasJointAtEdge) 1 else 0
            val h = if (p.hasHandCoveringFace) 1 else 0
            val s = if (p.shoulderImbalanceDeg != null) 1 else 0
            "$j$h$s"
        } ?: "_"
        return listOf(
            frame.sceneType.name,
            frame.brightnessState.name,
            frame.recommendationText,
            subject,
            bucket(frame.horizonTiltDeg / 15f),
            "yaw=$yawBucket",
            "roll=$rollBucket",
            "ratio=$ratioBucket",
            "light=${frame.lightDirection.name.first()}",
            "bg=$bgBucket",
            "pose=$poseBucket",
        ).joinToString("|")
    }

    private fun bucket(value: Float): Int = (value * 10f).toInt()
}

