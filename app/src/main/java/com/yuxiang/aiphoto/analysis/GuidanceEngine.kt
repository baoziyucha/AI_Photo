package com.yuxiang.aiphoto.analysis

import com.yuxiang.aiphoto.model.BrightnessState
import com.yuxiang.aiphoto.model.CameraAction
import com.yuxiang.aiphoto.model.GuidanceFrame
import com.yuxiang.aiphoto.model.NormalizedRect
import com.yuxiang.aiphoto.model.SceneType
import com.yuxiang.aiphoto.model.TorchSuggestion
import com.yuxiang.aiphoto.model.ZoomSuggestion
import kotlin.math.abs
import kotlin.math.roundToInt

class GuidanceEngine {
    fun build(
        sceneType: SceneType,
        subjectBox: NormalizedRect?,
        horizonTiltDeg: Float,
        brightnessState: BrightnessState,
        faceCount: Int,
        confidence: Float,
    ): GuidanceFrame {
        val exposureDelta = when (brightnessState) {
            BrightnessState.BACKLIT,
            BrightnessState.LOW_LIGHT,
            -> 1

            BrightnessState.OVEREXPOSED -> -1
            BrightnessState.BALANCED -> 0
        }

        val zoomSuggestion = determineZoomSuggestion(sceneType, subjectBox)
        val recommendation = recommendationFor(
            sceneType = sceneType,
            subjectBox = subjectBox,
            brightnessState = brightnessState,
            horizonTiltDeg = horizonTiltDeg,
            zoomSuggestion = zoomSuggestion,
        )
        val detail = buildDetail(sceneType, subjectBox, brightnessState, horizonTiltDeg, confidence, faceCount)

        return GuidanceFrame(
            sceneType = sceneType,
            subjectBox = subjectBox,
            horizonTiltDeg = horizonTiltDeg,
            brightnessState = brightnessState,
            recommendationText = recommendation,
            cameraAction = CameraAction(
                focusMeteringPoint = subjectBox?.centerPoint?.clamped(),
                aeMeteringPoint = subjectBox?.centerPoint?.clamped(),
                exposureCompensationDelta = exposureDelta,
                torchSuggestion = if (brightnessState == BrightnessState.BACKLIT || brightnessState == BrightnessState.LOW_LIGHT) {
                    TorchSuggestion.CONSIDER_ON
                } else {
                    TorchSuggestion.NONE
                },
                zoomSuggestion = zoomSuggestion,
            ),
            confidence = confidence.coerceIn(0f, 1f),
            detailText = detail,
        )
    }

    private fun recommendationFor(
        sceneType: SceneType,
        subjectBox: NormalizedRect?,
        brightnessState: BrightnessState,
        horizonTiltDeg: Float,
        zoomSuggestion: ZoomSuggestion,
    ): String {
        if (subjectBox == null) {
            return when (brightnessState) {
                BrightnessState.LOW_LIGHT -> "环境偏暗，先稳住手机并把主体放到中央。"
                BrightnessState.BACKLIT -> "先让主体进入画面，再尝试换到更亮的位置。"
                BrightnessState.OVEREXPOSED -> "高光偏强，先把主体放回画面中央。"
                BrightnessState.BALANCED -> "让主体进入画面中央，AI 会继续给出建议。"
            }
        }

        if (brightnessState == BrightnessState.BACKLIT) {
            return "换到更亮的位置，或打开补光。"
        }
        if (brightnessState == BrightnessState.LOW_LIGHT) {
            return "环境偏暗，先稳住手机或打开补光。"
        }
        if (brightnessState == BrightnessState.OVEREXPOSED) {
            return "画面偏亮，压低曝光后再拍。"
        }

        return when (sceneType) {
            SceneType.PORTRAIT,
            SceneType.SELFIE,
            -> portraitRecommendation(subjectBox, horizonTiltDeg, zoomSuggestion)

            SceneType.PET_OR_CHILD -> childRecommendation(subjectBox, horizonTiltDeg, zoomSuggestion)
            SceneType.DAILY_GENERIC -> genericRecommendation(subjectBox, horizonTiltDeg, zoomSuggestion)
        }
    }

    private fun portraitRecommendation(
        subjectBox: NormalizedRect,
        horizonTiltDeg: Float,
        zoomSuggestion: ZoomSuggestion,
    ): String {
        return when {
            zoomSuggestion == ZoomSuggestion.MOVE_CLOSER -> "再靠近一点，让主体占满画面。"
            zoomSuggestion == ZoomSuggestion.MOVE_BACK -> "稍微后退一些，保留更自然的人像空间。"
            subjectBox.centerX < 0.42f -> "镜头向左一点，把人物放回中间。"
            subjectBox.centerX > 0.58f -> "镜头向右一点，把人物放回中间。"
            subjectBox.top < 0.08f -> "镜头再低一点，留出头顶空间。"
            subjectBox.top > 0.15f -> "镜头再高一点，让视线接近上三分线。"
            abs(horizonTiltDeg) > 2f -> "扶正画面。"
            else -> "构图稳定，可以拍摄。"
        }
    }

    private fun childRecommendation(
        subjectBox: NormalizedRect,
        horizonTiltDeg: Float,
        zoomSuggestion: ZoomSuggestion,
    ): String {
        return when {
            zoomSuggestion == ZoomSuggestion.MOVE_CLOSER -> "再靠近一点，抓住主体表情。"
            subjectBox.centerX < 0.4f -> "镜头向左一点，别让主体贴边。"
            subjectBox.centerX > 0.6f -> "镜头向右一点，别让主体贴边。"
            abs(horizonTiltDeg) > 2f -> "扶正画面，避免地平线歪斜。"
            else -> "主体位置不错，保持稳定就能拍。"
        }
    }

    private fun genericRecommendation(
        subjectBox: NormalizedRect,
        horizonTiltDeg: Float,
        zoomSuggestion: ZoomSuggestion,
    ): String {
        return when {
            subjectBox.touchesEdge() -> "让主体离边缘远一点。"
            zoomSuggestion == ZoomSuggestion.MOVE_CLOSER -> "靠近主体一点，让画面更聚焦。"
            zoomSuggestion == ZoomSuggestion.MOVE_BACK -> "退后一点，给主体更多呼吸空间。"
            subjectBox.centerX < 0.38f -> "镜头向左一点，主体更容易居中。"
            subjectBox.centerX > 0.62f -> "镜头向右一点，主体更容易居中。"
            abs(horizonTiltDeg) > 2f -> "扶正画面。"
            else -> "构图平稳，可以拍摄。"
        }
    }

    private fun determineZoomSuggestion(sceneType: SceneType, subjectBox: NormalizedRect?): ZoomSuggestion {
        if (subjectBox == null) return ZoomSuggestion.NONE
        val area = subjectBox.area
        val lowTarget = when (sceneType) {
            SceneType.PORTRAIT,
            SceneType.SELFIE,
            -> 0.35f

            SceneType.PET_OR_CHILD -> 0.22f
            SceneType.DAILY_GENERIC -> 0.18f
        }
        val highTarget = when (sceneType) {
            SceneType.PORTRAIT,
            SceneType.SELFIE,
            -> 0.6f

            SceneType.PET_OR_CHILD -> 0.55f
            SceneType.DAILY_GENERIC -> 0.65f
        }
        return when {
            area < lowTarget -> ZoomSuggestion.MOVE_CLOSER
            area > highTarget -> ZoomSuggestion.MOVE_BACK
            else -> ZoomSuggestion.NONE
        }
    }

    private fun buildDetail(
        sceneType: SceneType,
        subjectBox: NormalizedRect?,
        brightnessState: BrightnessState,
        horizonTiltDeg: Float,
        confidence: Float,
        faceCount: Int,
    ): String {
        val sceneLabel = when (sceneType) {
            SceneType.PORTRAIT -> "人像"
            SceneType.SELFIE -> "自拍"
            SceneType.PET_OR_CHILD -> "宠物 / 孩童"
            SceneType.DAILY_GENERIC -> "日常"
        }
        val brightnessLabel = when (brightnessState) {
            BrightnessState.BALANCED -> "光线正常"
            BrightnessState.LOW_LIGHT -> "光线偏暗"
            BrightnessState.BACKLIT -> "逆光"
            BrightnessState.OVEREXPOSED -> "高光偏强"
        }
        val areaPercent = subjectBox?.let { (it.area * 100).roundToInt() } ?: 0
        return buildString {
            append(sceneLabel)
            append(" · ")
            append(brightnessLabel)
            append(" · 倾斜 ")
            append(String.format("%.1f", abs(horizonTiltDeg)))
            append("°")
            if (areaPercent > 0) {
                append(" · 主体 ")
                append(areaPercent)
                append("%")
            }
            if (faceCount > 1) {
                append(" · 多人场景，优先跟主脸")
            }
            append(" · 置信度 ")
            append((confidence * 100).roundToInt())
            append("%")
        }
    }
}

