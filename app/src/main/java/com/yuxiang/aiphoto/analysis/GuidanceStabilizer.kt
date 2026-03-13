package com.yuxiang.aiphoto.analysis

import com.yuxiang.aiphoto.model.CameraAction
import com.yuxiang.aiphoto.model.GuidanceFrame

class GuidanceStabilizer(
    private val stableFrames: Int = 3,
) {
    private var lastFingerprint: String? = null
    private var consecutiveFrames = 0

    fun stabilize(raw: GuidanceFrame): GuidanceFrame {
        val fingerprint = buildFingerprint(raw)
        consecutiveFrames = if (fingerprint == lastFingerprint) consecutiveFrames + 1 else 1
        lastFingerprint = fingerprint
        return if (consecutiveFrames >= stableFrames) {
            raw.copy(isStable = true)
        } else {
            raw.copy(
                recommendationText = "",
                cameraAction = CameraAction.NONE,
                isStable = false,
            )
        }
    }

    fun reset() {
        lastFingerprint = null
        consecutiveFrames = 0
    }

    private fun buildFingerprint(frame: GuidanceFrame): String {
        val subject = frame.subjectBox?.let {
            "${bucket(it.centerX)}:${bucket(it.centerY)}:${bucket(it.area)}"
        } ?: "none"
        return listOf(
            frame.sceneType.name,
            frame.brightnessState.name,
            frame.recommendationText,
            subject,
            bucket(frame.horizonTiltDeg / 15f),
        ).joinToString("|")
    }

    private fun bucket(value: Float): Int = (value * 10f).toInt()
}

