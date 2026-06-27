package com.yuxiang.aiphoto.analysis

import com.yuxiang.aiphoto.model.BrightnessState
import com.yuxiang.aiphoto.model.GazeDirection
import com.yuxiang.aiphoto.model.GuidanceCategory
import com.yuxiang.aiphoto.model.GuidanceFrame
import com.yuxiang.aiphoto.model.GuidanceIssue
import com.yuxiang.aiphoto.model.IssueSeverity
import com.yuxiang.aiphoto.model.LightDirection
import com.yuxiang.aiphoto.model.PhotoScore
import com.yuxiang.aiphoto.model.RetryReason
import com.yuxiang.aiphoto.model.StyleProfile
import com.yuxiang.aiphoto.model.SubjectAction
import com.yuxiang.aiphoto.util.Logger
import kotlin.math.abs

private const val TAG = "PhotoScorer"

// 三分法构图阈值（与 GuidanceEngine 保持一致）
private const val THIRDS_LEFT = 0.33f
private const val THIRDS_RIGHT = 0.67f
private const val THIRDS_TOLERANCE = 0.08f
private const val CENTER_TOLERANCE = 0.05f
private const val FRAME_CENTER = 0.5f
private const val GAZE_THRESHOLD = 5f
private const val NEGATIVE_SPACE_AREA = 0.08f

private fun logD(message: String) {
    Logger.d(TAG, message)
}

/**
 * 拍后评分器：双分制（baseScore + styleScore）。
 *
 * - baseScore：硬规则废片（曝光/眨眼/严重倾斜/主体丢失），不可被风格覆盖。
 * - styleScore：风格加权（构图/光线/姿态/阴阳脸），按 styleProfile 调整。
 * - 综合分：baseScore < 40 时直接降分；否则 baseScore*0.6 + styleScore*0.4。
 */
class PhotoScorer(
    private var styleProfile: StyleProfile = StyleProfile.DEFAULT,
) {

    fun setStyle(profile: StyleProfile) {
        styleProfile = profile
    }

    fun score(frame: GuidanceFrame): PhotoScore {
        logD("score: start scoring frame, sceneType=${frame.sceneType}, style=${styleProfile.presetId}")

        val baseIssues = mutableListOf<GuidanceIssue>()
        val baseScore = evaluateBase(frame, baseIssues)
        logD("score: baseScore=$baseScore, baseIssues=${baseIssues.size}")

        val styleIssues = mutableListOf<GuidanceIssue>()
        val styleScore = evaluateStyle(frame, styleIssues)
        logD("score: styleScore=$styleScore, styleIssues=${styleIssues.size}")

        // 综合分：硬规则废片直接降分；否则 baseScore*0.6 + styleScore*0.4
        val finalScore = if (baseScore < 40) {
            baseScore
        } else {
            (baseScore * 0.6f + styleScore * 0.4f).toInt().coerceIn(0, 100)
        }
        logD("score: finalScore=$finalScore")

        val allIssues = baseIssues + styleIssues
        val shouldRetry = finalScore < 60 || baseIssues.any { it.blocksReady }

        return PhotoScore(
            score = finalScore.coerceIn(0, 100),
            baseScore = baseScore,
            styleScore = styleScore,
            styleId = styleProfile.presetId.name.lowercase(),
            strengths = collectStrengths(frame),
            issues = allIssues,
            retryReasons = collectRetryReasons(allIssues),
            shouldRetry = shouldRetry,
            nextActions = collectNextActions(allIssues, shouldRetry),
        )
    }

    /**
     * P2-4 连拍选片：对一组 GuidanceFrame 评分，返回综合分最高的 (score, index)。
     * 调用方通常传入连拍期间缓存的帧快照，索引对应该帧在原列表中的位置。
     */
    fun selectBest(frames: List<GuidanceFrame>): Pair<PhotoScore, Int>? {
        if (frames.isEmpty()) return null
        return frames.map { score(it) }
            .mapIndexed { index, photoScore -> photoScore to index }
            .maxByOrNull { it.first.score }
            ?.also {
                logD("selectBest: bestScore=${it.first.score}, bestIndex=${it.second}, total=${frames.size}")
            }
    }

    // ============ 基础分：硬规则废片（0-100） ============

    private fun evaluateBase(frame: GuidanceFrame, issues: MutableList<GuidanceIssue>): Int {
        var score = 100

        // 1. 主体丢失
        if (frame.subjectBox == null) {
            issues += GuidanceIssue(
                id = "safety.subject_missing",
                category = GuidanceCategory.SAFETY,
                severity = IssueSeverity.CRITICAL,
                confidence = 0.9f,
                priority = 1,
                message = "未检测到主体",
                blocksReady = true,
                retryReason = RetryReason.SUBJECT_OFF_CENTER,
            )
            return 20
        }

        // 2. 眨眼：minEyeOpen < 0.3 直接 30 分
        val leftEye = frame.leftEyeOpenProb
        val rightEye = frame.rightEyeOpenProb
        val minEye = listOfNotNull(leftEye, rightEye).minOrNull()
        if (minEye != null && minEye < 0.3f) {
            issues += GuidanceIssue(
                id = "safety.blinked",
                category = GuidanceCategory.SAFETY,
                severity = IssueSeverity.CRITICAL,
                confidence = 0.85f,
                priority = 2,
                message = "眨眼了，再来一张",
                ttsMessage = "眨眼了，再来一张",
                blocksReady = true,
                retryReason = RetryReason.BLINKED,
            )
            return 30
        }

        // 3. 严重曝光异常
        when (frame.brightnessState) {
            BrightnessState.OVEREXPOSED -> {
                issues += GuidanceIssue(
                    id = "safety.overexposed",
                    category = GuidanceCategory.SAFETY,
                    severity = IssueSeverity.CRITICAL,
                    confidence = 0.9f,
                    priority = 3,
                    message = "光线过强",
                    blocksReady = true,
                    retryReason = RetryReason.LIGHT_OVEREXPOSED,
                )
                score -= 40
            }
            BrightnessState.BACKLIT -> {
                issues += GuidanceIssue(
                    id = "safety.backlit",
                    category = GuidanceCategory.SAFETY,
                    severity = IssueSeverity.HIGH,
                    confidence = 0.8f,
                    priority = 4,
                    message = "逆光拍摄，脸太暗",
                    blocksReady = true,
                    retryReason = RetryReason.LIGHT_BACKLIT,
                )
                score -= 35
            }
            BrightnessState.LOW_LIGHT -> {
                issues += GuidanceIssue(
                    id = "safety.low_light",
                    category = GuidanceCategory.SAFETY,
                    severity = IssueSeverity.HIGH,
                    confidence = 0.8f,
                    priority = 5,
                    message = "光线太暗",
                    blocksReady = true,
                    retryReason = RetryReason.LIGHT_TOO_DARK,
                )
                score -= 30
            }
            BrightnessState.BALANCED -> Unit
        }

        // 4. 严重倾斜
        val absTilt = abs(frame.horizonTiltDeg)
        if (absTilt > 8f) {
            issues += GuidanceIssue(
                id = "safety.horizon_tilted",
                category = GuidanceCategory.SAFETY,
                severity = IssueSeverity.HIGH,
                confidence = 0.85f,
                priority = 6,
                message = "画面倾斜明显",
                blocksReady = true,
                retryReason = RetryReason.HORIZON_TILTED,
            )
            score -= 25
        }

        // 5. 主体贴边
        if (frame.subjectBox.touchesEdge()) {
            issues += GuidanceIssue(
                id = "framing.subject_at_edge",
                category = GuidanceCategory.FRAMING,
                severity = IssueSeverity.MEDIUM,
                confidence = 0.8f,
                priority = 10,
                message = "主体靠近边缘",
                retryReason = RetryReason.SUBJECT_AT_EDGE,
            )
            score -= 15
        }

        return score.coerceIn(0, 100)
    }

    // ============ 风格分：构图/光线/姿态加权（0-100） ============

    private fun evaluateStyle(frame: GuidanceFrame, issues: MutableList<GuidanceIssue>): Int {
        var score = 100

        // 构图（复用三分法/视线留白/负空间逻辑）
        val compositionScore = evaluateComposition(frame, issues)
        score = (score * 0.7f + compositionScore * 100f * 0.3f).toInt()

        // 光线（含阴阳脸）
        val lightScore = evaluateLightStyle(frame, issues)
        score = (score * 0.85f + lightScore * 0.15f).toInt()

        // 姿态（头姿 headEulerX + headEulerZ）
        val poseScore = evaluatePose(frame, issues)
        score = (score * 0.85f + poseScore * 0.15f).toInt()

        // 背景（亮斑/柱子穿头/杂乱）
        val backgroundScore = evaluateBackground(frame, issues)
        score = (score * 0.9f + backgroundScore * 0.1f).toInt()

        return score.coerceIn(0, 100)
    }

    /** P2：背景干扰评分（0-1），仅软提示不阻断 READY。 */
    private fun evaluateBackground(frame: GuidanceFrame, issues: MutableList<GuidanceIssue>): Float {
        val bg = frame.backgroundMetrics ?: return 1.0f
        var score = 1.0f
        if (bg.hasVerticalLineAboveHead == true) {
            issues += GuidanceIssue(
                id = "background.line_through_head",
                category = GuidanceCategory.BACKGROUND,
                severity = IssueSeverity.MEDIUM,
                confidence = 0.7f,
                priority = 50,
                message = "头顶有电线杆/柱子，挪一步换个角度。",
                ttsMessage = "头顶有柱子，挪一步",
            )
            score -= 0.3f
        }
        if (bg.hasHotspotNearHead == true) {
            issues += GuidanceIssue(
                id = "background.hotspot",
                category = GuidanceCategory.BACKGROUND,
                severity = IssueSeverity.MEDIUM,
                confidence = 0.7f,
                priority = 51,
                message = "背景有高光抢戏，换个角度避开。",
                ttsMessage = "背景有高光抢戏，换个角度",
            )
            score -= 0.25f
        }
        val clutter = bg.clutterScore
        if (clutter != null && clutter > 0.6f) {
            issues += GuidanceIssue(
                id = "background.clutter",
                category = GuidanceCategory.BACKGROUND,
                severity = IssueSeverity.LOW,
                confidence = 0.6f,
                priority = 52,
                message = "背景有点杂乱，换个干净点的角度。",
                ttsMessage = "背景有点乱，换个角度",
            )
            score -= 0.15f
        }
        return score.coerceIn(0f, 1f)
    }

    private fun evaluateComposition(
        frame: GuidanceFrame,
        issues: MutableList<GuidanceIssue>,
    ): Float {
        val subject = frame.subjectBox ?: return 0.1f

        var score = 0f

        val gaze = computeGaze(frame.headEulerY)
        val centerX = subject.centerX
        val towardWhitespace = gazeTowardWhitespace(centerX, gaze)

        val area = subject.area
        // 负空间构图：主体极小但非贴边且视线朝向留白 → 意境感，不触发重拍
        val isNegativeSpace = area < NEGATIVE_SPACE_AREA &&
            !subject.touchesEdge() &&
            gaze != GazeDirection.UNKNOWN &&
            towardWhitespace
        when {
            isNegativeSpace -> {
                score += 0.9f
            }
            area < 0.05f -> {
                issues += GuidanceIssue(
                    id = "framing.subject_too_small",
                    category = GuidanceCategory.FRAMING,
                    severity = IssueSeverity.MEDIUM,
                    confidence = 0.8f,
                    priority = 11,
                    message = "主体太小",
                    retryReason = RetryReason.SUBJECT_TOO_SMALL,
                )
                score += 0.2f
            }
            area > 0.6f -> {
                issues += GuidanceIssue(
                    id = "framing.subject_too_large",
                    category = GuidanceCategory.FRAMING,
                    severity = IssueSeverity.MEDIUM,
                    confidence = 0.8f,
                    priority = 12,
                    message = "主体太大",
                    retryReason = RetryReason.SUBJECT_TOO_LARGE,
                )
                score += 0.3f
            }
            area >= 0.15f && area <= 0.35f -> {
                score += 1.0f
            }
            else -> {
                score += 0.6f
            }
        }

        val onThirds = isOnThirdsPoint(centerX)
        when {
            onThirds && towardWhitespace -> {
                score += 1.0f
            }
            onThirds && gaze == GazeDirection.UNKNOWN -> {
                score += 0.7f
            }
            onThirds && !towardWhitespace -> {
                score += 0.4f
            }
            abs(centerX - FRAME_CENTER) <= CENTER_TOLERANCE -> {
                score += 0.8f
            }
            centerX in 0.25f..0.75f -> {
                score += 0.5f
            }
            else -> {
                issues += GuidanceIssue(
                    id = "composition.off_center",
                    category = GuidanceCategory.COMPOSITION,
                    severity = IssueSeverity.MEDIUM,
                    confidence = 0.8f,
                    priority = 13,
                    message = "主体偏离中心",
                    retryReason = RetryReason.SUBJECT_OFF_CENTER,
                )
                score += 0.1f
            }
        }

        // P1：肩颈裁切检查——脸底部太靠画面底部，可能裁脖
        if (subject.bottom > 0.90f) {
            issues += GuidanceIssue(
                id = "framing.neck_cropped",
                category = GuidanceCategory.FRAMING,
                severity = IssueSeverity.MEDIUM,
                confidence = 0.7f,
                priority = 14,
                message = "头像裁脖，下移手机留出肩颈",
                ttsMessage = "下移一点，留出肩颈",
            )
            score *= 0.7f
        }

        return score / 3f
    }

    private fun evaluateLightStyle(frame: GuidanceFrame, issues: MutableList<GuidanceIssue>): Float {
        val ratio = frame.faceLightMetrics?.faceLightRatio
        if (ratio != null && ratio > 1.8f) {
            val isSevere = ratio > FaceLightEvaluator.SEVERE_RATIO_THRESHOLD
            issues += GuidanceIssue(
                id = "light.split_face",
                category = GuidanceCategory.LIGHT,
                severity = if (isSevere) IssueSeverity.HIGH else IssueSeverity.MEDIUM,
                confidence = 0.8f,
                priority = if (isSevere) 22 else 23,
                message = "侧光有点强，脸再转向光源一点",
                ttsMessage = "侧光有点强，脸再转向光源一点",
                action = SubjectAction.FACE_TURN_TO_LIGHT,
                blocksReady = isSevere,
                retryReason = if (isSevere) RetryReason.LIGHT_BACKLIT else null,
            )
            return if (isSevere) 0.3f else 0.6f
        }
        // P1：眼神光检测（catchlight）——无眼神光时软提示，不阻断 READY
        val hasCatchLight = frame.faceLightMetrics?.hasCatchLight
        if (hasCatchLight == false) {
            issues += GuidanceIssue(
                id = "light.no_catchlight",
                category = GuidanceCategory.LIGHT,
                severity = IssueSeverity.LOW,
                confidence = 0.6f,
                priority = 24,
                message = "眼睛没神，脸转向光一点让眼睛有光",
                ttsMessage = "脸转向光一点，让眼睛有光",
                action = SubjectAction.FACE_TURN_TO_LIGHT,
            )
            return 0.75f
        }
        return 1.0f
    }

    /** 姿态评估：用 headEulerX（pitch）+ headEulerZ（roll）替换 deprecated facePitchDeg。 */
    private fun evaluatePose(frame: GuidanceFrame, issues: MutableList<GuidanceIssue>): Float {
        val pitch = frame.headEulerX
        val roll = frame.headEulerZ

        var score = 1.0f

        // 低头/抬头（pitch）
        if (pitch != null) {
            when {
                pitch < -8f -> {
                    issues += GuidanceIssue(
                        id = "pose.chin_low",
                        category = GuidanceCategory.POSE,
                        severity = IssueSeverity.MEDIUM,
                        confidence = 0.8f,
                        priority = 31,
                        message = "下巴过低，眼神弱",
                        ttsMessage = "眼睛看镜头上方一点，下巴轻轻收住",
                        action = SubjectAction.CHIN_RELAX,
                    )
                    score -= 0.3f
                }
                pitch > 8f -> {
                    issues += GuidanceIssue(
                        id = "pose.chin_high",
                        category = GuidanceCategory.POSE,
                        severity = IssueSeverity.MEDIUM,
                        confidence = 0.8f,
                        priority = 32,
                        message = "下巴过高，鼻孔外露",
                        ttsMessage = "下巴稍微收回来一点",
                        action = SubjectAction.CHIN_TUCK,
                    )
                    score -= 0.3f
                }
            }
        }

        // 头歪（roll）
        if (roll != null) {
            when {
                abs(roll) > 6f -> {
                    issues += GuidanceIssue(
                        id = "pose.head_tilt",
                        category = GuidanceCategory.POSE,
                        severity = IssueSeverity.MEDIUM,
                        confidence = 0.8f,
                        priority = 33,
                        message = "头歪过度",
                        ttsMessage = "头扶正一点",
                        action = SubjectAction.HEAD_STRAIGHTEN,
                    )
                    score -= 0.25f
                }
            }
        }

        return score.coerceIn(0f, 1f)
    }

    // ============ 优点收集（独立于 issues） ============

    private fun collectStrengths(frame: GuidanceFrame): List<String> {
        val strengths = mutableListOf<String>()
        if (frame.brightnessState == BrightnessState.BALANCED) {
            strengths += "光线平衡"
        }
        if (abs(frame.horizonTiltDeg) <= 1f) {
            strengths += "画面水平"
        }
        val minEye = listOfNotNull(frame.leftEyeOpenProb, frame.rightEyeOpenProb).minOrNull()
        if (minEye != null && minEye >= 0.6f) {
            strengths += "眼神自然"
        }
        frame.subjectBox?.let { subject ->
            if (subject.area in 0.15f..0.35f) {
                strengths += "主体大小适中"
            }
            // 三分法 + 视线留白
            val gaze = computeGaze(frame.headEulerY)
            val centerX = subject.centerX
            if (isOnThirdsPoint(centerX) && gazeTowardWhitespace(centerX, gaze)) {
                strengths += "三分法构图，视线留白到位"
            }
        }
        // 八分脸加分
        val yaw = frame.headEulerY
        if (yaw != null && abs(yaw) in 15f..30f) {
            strengths += "侧脸角度好"
        }
        // P1：有眼神光加分
        if (frame.faceLightMetrics?.hasCatchLight == true) {
            strengths += "眼神有光"
        }
        // P2：前侧光人像黄金光位加分
        if (frame.brightnessState == BrightnessState.BALANCED &&
            (frame.lightDirection == LightDirection.LEFT || frame.lightDirection == LightDirection.RIGHT) &&
            yaw != null && abs(yaw) in 10f..40f &&
            (frame.faceLightMetrics?.faceLightRatio == null || frame.faceLightMetrics.faceLightRatio < 1.5f)
        ) {
            strengths += "前侧光黄金光位"
        }
        // P2：背景干净加分
        val bg = frame.backgroundMetrics
        if (bg != null &&
            bg.hasHotspotNearHead != true &&
            bg.hasVerticalLineAboveHead != true &&
            (bg.clutterScore == null || bg.clutterScore < 0.4f)
        ) {
            strengths += "背景干净"
        }
        return strengths
    }

    private fun collectRetryReasons(issues: List<GuidanceIssue>): List<RetryReason> {
        return issues.mapNotNull { it.retryReason }.distinct()
    }

    /** P1：基于问题生成下一张动作建议（按 priority 取最重要的 3 条）。 */
    private fun collectNextActions(issues: List<GuidanceIssue>, shouldRetry: Boolean): List<String> {
        if (issues.isEmpty()) {
            return listOf("保持这个状态，可以尝试不同角度")
        }
        return issues
            .sortedBy { it.priority }
            .take(3)
            .mapNotNull { issue -> nextActionForIssue(issue.id) }
            .distinct()
    }

    private fun nextActionForIssue(issueId: String): String? = when (issueId) {
        "safety.blinked" -> "再来一张，注意保持睁眼"
        "safety.subject_missing" -> "确认主体在画面中"
        "safety.overexposed" -> "避开直射光或后退一点"
        "safety.backlit" -> "转向光源，让脸有光"
        "safety.low_light" -> "找光源或开补光"
        "safety.horizon_tilted" -> "扶正手机"
        "framing.subject_at_edge" -> "调整构图，主体离开边缘"
        "framing.neck_cropped" -> "下移手机，留出肩颈"
        "framing.subject_too_small" -> "靠近一点"
        "framing.subject_too_large" -> "后退一点"
        "composition.off_center" -> "调整主体位置"
        "light.split_face" -> "脸转向光源一点"
        "pose.chin_low" -> "下巴收住，看镜头上方"
        "pose.chin_high" -> "下巴收回来一点"
        "pose.head_tilt" -> "头扶正一点"
        "light.no_catchlight" -> "脸转向光源，让眼睛有光"
        else -> null
    }

    // ============ 构图辅助函数 ============

    private fun computeGaze(headEulerY: Float?): GazeDirection {
        val yaw = headEulerY ?: return GazeDirection.UNKNOWN
        return when {
            abs(yaw) < GAZE_THRESHOLD -> GazeDirection.FORWARD
            yaw > GAZE_THRESHOLD -> GazeDirection.LOOK_LEFT
            else -> GazeDirection.LOOK_RIGHT
        }
    }

    private fun isOnThirdsPoint(centerX: Float): Boolean {
        return abs(centerX - THIRDS_LEFT) <= THIRDS_TOLERANCE ||
            abs(centerX - THIRDS_RIGHT) <= THIRDS_TOLERANCE
    }

    private fun gazeTowardWhitespace(centerX: Float, gaze: GazeDirection): Boolean {
        return if (centerX < FRAME_CENTER) gaze == GazeDirection.LOOK_RIGHT
        else gaze == GazeDirection.LOOK_LEFT
    }
}
