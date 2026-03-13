package com.yuxiang.aiphoto.model

import android.net.Uri
import android.os.Parcelable
import kotlin.math.roundToInt
import kotlinx.parcelize.Parcelize

enum class SceneType {
    PORTRAIT,
    SELFIE,
    PET_OR_CHILD,
    DAILY_GENERIC,
}

enum class BrightnessState {
    BALANCED,
    LOW_LIGHT,
    BACKLIT,
    OVEREXPOSED,
}

enum class TorchSuggestion {
    NONE,
    CONSIDER_ON,
}

enum class ZoomSuggestion {
    NONE,
    MOVE_CLOSER,
    MOVE_BACK,
}

@Parcelize
data class NormalizedPoint(
    val x: Float,
    val y: Float,
) : Parcelable {
    fun clamped(): NormalizedPoint = NormalizedPoint(
        x = x.coerceIn(0f, 1f),
        y = y.coerceIn(0f, 1f),
    )
}

@Parcelize
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) : Parcelable {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val area: Float get() = width * height
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val centerPoint: NormalizedPoint get() = NormalizedPoint(centerX, centerY)

    fun clamped(): NormalizedRect = NormalizedRect(
        left = left.coerceIn(0f, 1f),
        top = top.coerceIn(0f, 1f),
        right = right.coerceIn(0f, 1f),
        bottom = bottom.coerceIn(0f, 1f),
    )

    fun touchesEdge(edgeInset: Float = 0.06f): Boolean {
        return left <= edgeInset || top <= edgeInset || right >= 1f - edgeInset || bottom >= 1f - edgeInset
    }
}

data class CameraAction(
    val focusMeteringPoint: NormalizedPoint? = null,
    val aeMeteringPoint: NormalizedPoint? = null,
    val exposureCompensationDelta: Int = 0,
    val torchSuggestion: TorchSuggestion = TorchSuggestion.NONE,
    val zoomSuggestion: ZoomSuggestion = ZoomSuggestion.NONE,
) {
    companion object {
        val NONE = CameraAction()
    }
}

data class GuidanceFrame(
    val sceneType: SceneType = SceneType.DAILY_GENERIC,
    val subjectBox: NormalizedRect? = null,
    val horizonTiltDeg: Float = 0f,
    val brightnessState: BrightnessState = BrightnessState.BALANCED,
    val recommendationText: String = "",
    val cameraAction: CameraAction = CameraAction.NONE,
    val confidence: Float = 0f,
    val detailText: String = "",
    val isStable: Boolean = false,
) {
    fun detectionSummary(): String {
        val subject = subjectBox?.let {
            "subject=(${it.left.format2()},${it.top.format2()},${it.right.format2()},${it.bottom.format2()})"
        } ?: "subject=none"
        return buildString {
            append("scene=${sceneType.name.lowercase()}")
            append(", brightness=${brightnessState.name.lowercase()}")
            append(", tilt=${horizonTiltDeg.format1()}")
            append(", confidence=${(confidence * 100).roundToInt()}%")
            append(", $subject")
            append(", stable=$isStable")
        }
    }
}

data class LocalPhotoSummary(
    val headline: String,
    val details: String,
    val detectionSummary: String,
)

data class CapturedPhoto(
    val uri: Uri,
    val sceneType: SceneType,
    val localSummary: LocalPhotoSummary,
    val capturedAtMillis: Long,
    val fileName: String,
)

data class PhotoReview(
    val summary: String,
    val strengths: List<String>,
    val issues: List<String>,
    val suggestions: List<String>,
) {
    fun toDisplayText(): String = buildString {
        if (summary.isNotBlank()) {
            append(summary.trim())
        }
        if (strengths.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append("优点：")
            append(strengths.joinToString("；"))
        }
        if (issues.isNotEmpty()) {
            if (isNotEmpty()) append("\n")
            append("问题：")
            append(issues.joinToString("；"))
        }
        if (suggestions.isNotEmpty()) {
            if (isNotEmpty()) append("\n")
            append("建议：")
            append(suggestions.joinToString("；"))
        }
    }
}

sealed interface ReviewUiState {
    data object Idle : ReviewUiState
    data object Loading : ReviewUiState
    data class Success(val review: PhotoReview) : ReviewUiState
    data class Error(val message: String, val fallback: String) : ReviewUiState
}

private fun Float.format1(): String = String.format("%.1f", this)

private fun Float.format2(): String = String.format("%.2f", this)

