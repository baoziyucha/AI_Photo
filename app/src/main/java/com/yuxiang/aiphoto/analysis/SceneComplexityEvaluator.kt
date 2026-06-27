package com.yuxiang.aiphoto.analysis

import com.yuxiang.aiphoto.model.BrightnessState
import com.yuxiang.aiphoto.model.GuidanceFrame
import kotlin.math.abs

/**
 * 场景复杂度评估：判断当前帧是否需要触发云端多模态增强。
 *
 * 简单场景（单人脸 + 光线正常 + 倾斜小）→ 纯本地
 * 复杂场景（多人/逆光/侧脸/遮挡）→ 触发云端
 */
object SceneComplexityEvaluator {

    private const val CLOUD_FACE_COUNT_THRESHOLD = 1
    private const val CLOUD_FACE_PITCH_THRESHOLD = 12f
    private const val CLOUD_LOW_CONFIDENCE_THRESHOLD = 0.4f

    fun shouldRequestCloud(frame: GuidanceFrame): Boolean {
        // 多人场景
        if (frame.faceCount > CLOUD_FACE_COUNT_THRESHOLD) return true
        // 逆光
        if (frame.brightnessState == BrightnessState.BACKLIT) return true
        // 侧脸明显
        if (frame.facePitchDeg != null && abs(frame.facePitchDeg) > CLOUD_FACE_PITCH_THRESHOLD) return true
        // 无主体且置信度低（遮挡 / 识别失败）
        if (frame.subjectBox == null && frame.confidence < CLOUD_LOW_CONFIDENCE_THRESHOLD) return true
        return false
    }
}
