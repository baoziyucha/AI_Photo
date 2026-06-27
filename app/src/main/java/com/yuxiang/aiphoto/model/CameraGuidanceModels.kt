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

enum class LightDirection {
    LEFT,
    RIGHT,
    TOP,
    BOTTOM,
    UNKNOWN,
}

enum class TorchSuggestion {
    NONE,
    CONSIDER_ON,
}

enum class ZoomSuggestion {
    NONE,
    MOVE_CLOSER,
    MOVE_BACK,
    STEP_BACK_USE_2X,
}

enum class CaptureReadiness {
    NOT_READY,
    ALMOST_READY,
    READY,
}

enum class DirectionHint {
    NONE,
    MOVE_LEFT,
    MOVE_RIGHT,
    MOVE_UP,
    MOVE_DOWN,
    TILT_LEFT,
    TILT_RIGHT,
}

/** 表情状态：基于 smile 概率与睁眼概率联合判定。 */
enum class ExpressionState {
    NATURAL_SMILE,
    FAKE_SMILE,
    AWKWARD,
    NO_SMILE,
    UNKNOWN,
}

/** 视线方向：基于头部偏航角推断，用于判断留白方向。 */
enum class GazeDirection {
    LOOK_LEFT,
    LOOK_RIGHT,
    FORWARD,
    UNKNOWN,
}

/** 规则类别：用于结构化 GuidanceIssue 分类与风格权重调整。 */
enum class GuidanceCategory {
    SAFETY,
    COMPOSITION,
    LIGHT,
    EXPRESSION,
    POSE,
    FRAMING,
    BACKGROUND,
    LENS,
}

/** 严重度档位：CRITICAL 最高（硬规则废片），INFO 最低（鼓励信息）。 */
enum class IssueSeverity { CRITICAL, HIGH, MEDIUM, LOW, INFO }

/** 被拍者动作指令：摄影师对模特的引导动作。 */
enum class SubjectAction {
    CHIN_TUCK,
    CHIN_RELAX,
    HEAD_STRAIGHTEN,
    FACE_TURN_TO_LIGHT,
    FACE_TURN_BACK,
    SHOULDER_TURN,
    EYES_ON_LENS,
    NATURAL_SMILE_HOLD,
    RECOMPOSE_MOVE,
    STEP_BACK_USE_2X,
    KEEP_CURRENT,
}

/**
 * 统一规则命中模型：让 UI、TTS、PhotoScorer、云端 prompt 消费同一组专业判断。
 * priority 越小越优先；blocksReady=true 时阻断 READY。
 */
data class GuidanceIssue(
    val id: String,
    val category: GuidanceCategory,
    val severity: IssueSeverity,
    val confidence: Float,
    val priority: Int,
    val message: String,
    val ttsMessage: String? = null,
    val action: SubjectAction? = null,
    val blocksReady: Boolean = false,
    val scoreDelta: Int = 0,
    val retryReason: RetryReason? = null,
)

/** 风格方向：决定同一检测问题在不同审美下的解释与建议。 */
enum class StylePreset {
    FRESH,
    WORKPLACE,
    STREET,
    EMOTIONAL,
    FILM,
    SWEET,
    COOL,
    TRAVEL,
    ID_PHOTO,
}

/** 脸部左右侧：用于左右脸光比阴影侧判定。 */
enum class FaceSide { LEFT, RIGHT }

/** 左右脸光比指标：用于识别阴阳脸、脸朝暗面、侧逆光。 */
data class FaceLightMetrics(
    val leftFaceLuma: Float? = null,
    val rightFaceLuma: Float? = null,
    val faceLightRatio: Float? = null,
    val shadowSide: FaceSide? = null,
    val hasCatchLight: Boolean? = null,
)

/**
 * 背景干扰指标（P2 轻量方案，基于 luma 网格 + faceBox 几何，无需分割模型）。
 */
data class BackgroundMetrics(
    val clutterScore: Float? = null,              // 杂乱度 0-1（环带亮度标准差归一化）
    val hasHotspotNearHead: Boolean? = null,       // 头部附近亮斑（高光抢戏）
    val hasVerticalLineAboveHead: Boolean? = null, // 头顶垂直线条（柱子/电线杆穿头）
    val subjectSeparationScore: Float? = null,     // 主体分离度 0-1（低置信度，同色背景失效）
)

/**
 * 姿态指标（P2-5，基于 ML Kit Pose Detection 33 个关节点）。
 * - 关节裁切：肩/肘/腕靠近画面边缘 → 提示挪远或拉远焦段
 * - 手部遮挡脸：腕部坐标落入 faceBox → 提示放下手
 * - 双肩倾斜：肩连线与水平线夹角 > 12° → 提示放松肩膀
 */
data class PoseMetrics(
    val hasJointAtEdge: Boolean = false,
    val croppedJointName: String? = null,
    val hasHandCoveringFace: Boolean = false,
    val shoulderImbalanceDeg: Float? = null,
)

/** 语音语调：影响 TTS 语速、节奏、剧本风格。 */
enum class SpeechTone { GENTLE, DECISIVE, INTIMATE, CRISP, NARRATIVE }

/** 话术库：风格化文案，避免同一句式重复。 */
data class PromptLexicon(
    val encouragement: List<String>,
    val correction: List<String>,
    val countdown: List<String>,
)

/** 用户偏好：记录喜欢/不喜欢的风格与类别权重，不允许覆盖硬规则。 */
data class UserPreference(
    val likedStyles: Set<StylePreset> = emptySet(),
    val dislikedStyles: Set<StylePreset> = emptySet(),
    val biasWeights: Map<GuidanceCategory, Float> = emptyMap(),
    val allowHistoryOverride: Boolean = true,
    // P2-7 个性化记忆：从历史高分照片聚合
    val bestHeadEulerY: Float? = null,           // 用户最佳侧脸角度（正脸=0，左侧脸<0，右侧脸>0）
    val bestSmilingProbability: Float? = null,  // 最佳笑容概率（0-1）
    val commonIssueIds: List<String> = emptyList(), // 常见问题 top 3（按频次）
    val totalCaptures: Int = 0,                  // 累计拍摄数
    val highScoreCaptures: Int = 0,              // 高分（>=80）拍摄数
)

/** 同一规则在不同风格下的权重、严重度、动作和话术变体。 */
data class RuleVariant(
    val issueId: String,
    val preset: StylePreset,
    val severityOverride: IssueSeverity? = null,
    val priorityDelta: Int = 0,
    val messageOverride: String? = null,
    val ttsOverride: String? = null,
    val actionOverride: SubjectAction? = null,
    val blocksReadyOverride: Boolean? = null,
    val scoreDeltaOverride: Int? = null,
)

/** 运行时风格实例：由 preset + userPreference + sessionContext 合成。 */
data class StyleProfile(
    val presetId: StylePreset,
    val weights: Map<GuidanceCategory, Float>,
    val allowedActions: Set<SubjectAction>,
    val promptLexicon: PromptLexicon,
    val speechTone: SpeechTone,
    val userBias: UserPreference,
    val speechRate: Float = 0.95f,
) {
    companion object {
        /** 默认清新风格。 */
        val DEFAULT = StyleProfile(
            presetId = StylePreset.FRESH,
            weights = emptyMap(),
            allowedActions = SubjectAction.values().toSet(),
            promptLexicon = PromptLexicon(
                encouragement = listOf("很好，保持", "这个角度不错", "光线很合适"),
                correction = listOf("稍微调整一下", "再来一点", "放松一点"),
                countdown = listOf("3", "2", "1"),
            ),
            speechTone = SpeechTone.GENTLE,
            userBias = UserPreference(),
        )
    }
}

/**
 * 主体应移动到的理想位置。保留主体当前尺寸，仅平移到目标中心。
 * 用于在取景画面上绘制"移到这里"的虚线引导框。
 */
@Parcelize
data class TargetZone(
    val rect: NormalizedRect,
) : Parcelable

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
    val directionHint: DirectionHint = DirectionHint.NONE,
) {
    companion object {
        val NONE = CameraAction()
    }
}

data class GuidanceFrame(
    val sceneType: SceneType = SceneType.DAILY_GENERIC,
    val subjectBox: NormalizedRect? = null,
    val horizonTiltDeg: Float = 0f,
    @Deprecated("Use headEulerX", ReplaceWith("headEulerX"))
    val facePitchDeg: Float? = null,
    val smilingProbability: Float? = null,
    val leftEyeOpenProb: Float? = null,
    val rightEyeOpenProb: Float? = null,
    val headEulerX: Float? = null,
    val headEulerY: Float? = null,
    val headEulerZ: Float? = null,
    val faceCount: Int = 0,
    val brightnessState: BrightnessState = BrightnessState.BALANCED,
    val lightDirection: LightDirection = LightDirection.UNKNOWN,
    val recommendationText: String = "",
    val cameraAction: CameraAction = CameraAction.NONE,
    val confidence: Float = 0f,
    val detailText: String = "",
    val isStable: Boolean = false,
    val captureReadiness: CaptureReadiness = CaptureReadiness.NOT_READY,
    val targetZone: TargetZone? = null,
    val issues: List<GuidanceIssue> = emptyList(),
    val primaryIssue: GuidanceIssue? = null,
    val ttsMessage: String? = null,
    val styleProfile: StyleProfile = StyleProfile.DEFAULT,
    val faceLightMetrics: FaceLightMetrics? = null,
    val backgroundMetrics: BackgroundMetrics? = null,
    val poseMetrics: PoseMetrics? = null,
) {
    fun detectionSummary(): String {
        val subject = subjectBox?.let {
            "subject=(${it.left.format2()},${it.top.format2()},${it.right.format2()},${it.bottom.format2()})"
        } ?: "subject=none"
        return buildString {
            append("scene=${sceneType.name.lowercase()}")
            append(", brightness=${brightnessState.name.lowercase()}")
            append(", lightDir=${lightDirection.name.lowercase()}")
            append(", tilt=${horizonTiltDeg.format1()}")
            headEulerX?.let { append(", headPitch=${it.format1()}") }
            headEulerY?.let { append(", headYaw=${it.format1()}") }
            headEulerZ?.let { append(", headRoll=${it.format1()}") }
            @Suppress("DEPRECATION")
            if (facePitchDeg != null && headEulerX == null) {
                append(", facePitch=${facePitchDeg.format1()}")
            }
            smilingProbability?.let { append(", smile=${it.format2()}") }
            append(", faceCount=$faceCount")
            append(", confidence=${(confidence * 100).roundToInt()}%")
            append(", $subject")
            append(", stable=$isStable")
            append(", readiness=${captureReadiness.name.lowercase()}")
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

enum class RetryReason {
    SUBJECT_TOO_SMALL,
    SUBJECT_TOO_LARGE,
    SUBJECT_OFF_CENTER,
    SUBJECT_AT_EDGE,
    LIGHT_TOO_DARK,
    LIGHT_BACKLIT,
    LIGHT_OVEREXPOSED,
    HORIZON_TILTED,
    FACE_ANGLE_TOO_OBLIQUE,
    BLINKED,
}

data class PhotoScore(
    val score: Int,
    val baseScore: Int = score,            // 基础分：硬规则（曝光/眨眼/严重倾斜）
    val styleScore: Int = score,           // 风格分：构图/光线/表情/姿态/背景
    val styleId: String = "",              // 当前风格 preset
    val strengths: List<String>,
    val issues: List<GuidanceIssue> = emptyList(),   // 结构化问题
    val issuesLegacy: List<String> = emptyList(),    // 旧文案兼容
    val retryReasons: List<RetryReason>,
    val shouldRetry: Boolean,
    val nextActions: List<String> = emptyList(),      // P1：下一张动作建议
) {
    fun toDisplayText(): String = buildString {
        append("评分：$score 分（基础 $baseScore · 风格 $styleScore）")
        if (strengths.isNotEmpty()) {
            append("\n优点：")
            append(strengths.joinToString("；"))
        }
        val issueTexts = issues.map { it.message }.ifEmpty { issuesLegacy }
        if (issueTexts.isNotEmpty()) {
            append("\n问题：")
            append(issueTexts.joinToString("；"))
        }
        if (shouldRetry && retryReasons.isNotEmpty()) {
            append("\n建议重拍：")
            append(retryReasons.map { it.toDisplayText() }.joinToString("；"))
        }
        if (nextActions.isNotEmpty()) {
            append("\n下一张：")
            append(nextActions.joinToString("；"))
        }
    }
}

fun RetryReason.toDisplayText(): String = when (this) {
    RetryReason.SUBJECT_TOO_SMALL -> "主体太小，靠近一点"
    RetryReason.SUBJECT_TOO_LARGE -> "主体太大，后退一点"
    RetryReason.SUBJECT_OFF_CENTER -> "主体偏离中心，调整位置"
    RetryReason.SUBJECT_AT_EDGE -> "主体靠近边缘，调整构图"
    RetryReason.LIGHT_TOO_DARK -> "光线太暗，找光源或开闪光灯"
    RetryReason.LIGHT_BACKLIT -> "逆光拍摄，转向光源方向"
    RetryReason.LIGHT_OVEREXPOSED -> "光线过强，避开直射光"
    RetryReason.HORIZON_TILTED -> "画面倾斜，扶正手机"
    RetryReason.FACE_ANGLE_TOO_OBLIQUE -> "脸部角度偏，正对镜头"
    RetryReason.BLINKED -> "眨眼了，再来一张"
}

private fun Float.format1(): String = String.format("%.1f", this)

private fun Float.format2(): String = String.format("%.2f", this)

