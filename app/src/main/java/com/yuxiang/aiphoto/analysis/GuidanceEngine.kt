package com.yuxiang.aiphoto.analysis

import com.yuxiang.aiphoto.model.BrightnessState
import com.yuxiang.aiphoto.model.CameraAction
import com.yuxiang.aiphoto.model.CaptureReadiness
import com.yuxiang.aiphoto.model.DirectionHint
import com.yuxiang.aiphoto.model.ExpressionState
import com.yuxiang.aiphoto.model.BackgroundMetrics
import com.yuxiang.aiphoto.model.FaceLightMetrics
import com.yuxiang.aiphoto.model.GazeDirection
import com.yuxiang.aiphoto.model.GuidanceCategory
import com.yuxiang.aiphoto.model.GuidanceFrame
import com.yuxiang.aiphoto.model.GuidanceIssue
import com.yuxiang.aiphoto.model.IssueSeverity
import com.yuxiang.aiphoto.model.LightDirection
import com.yuxiang.aiphoto.model.NormalizedRect
import com.yuxiang.aiphoto.model.PoseMetrics
import com.yuxiang.aiphoto.model.RetryReason
import com.yuxiang.aiphoto.model.SceneType
import com.yuxiang.aiphoto.model.StyleProfile
import com.yuxiang.aiphoto.model.SubjectAction
import com.yuxiang.aiphoto.model.TargetZone
import com.yuxiang.aiphoto.model.TorchSuggestion
import com.yuxiang.aiphoto.model.ZoomSuggestion
import com.yuxiang.aiphoto.util.Logger
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TAG = "GuidanceEngine"

private fun logD(message: String) {
    Logger.d(TAG, message)
}

class GuidanceEngine(
    var styleProfile: StyleProfile = StyleProfile.DEFAULT,
) {
    companion object {
        // 三分法构图阈值
        private const val THIRDS_LEFT = 0.33f
        private const val THIRDS_RIGHT = 0.67f
        private const val THIRDS_TOLERANCE = 0.08f
        private const val CENTER_TOLERANCE = 0.05f

        // 主体顶部留白阈值
        private const val TOP_IDEAL_MIN = 0.08f
        private const val TOP_IDEAL_MAX = 0.15f
        private const val TOP_OK_MIN = 0.05f
        private const val TOP_OK_MAX = 0.2f

        // 拍摄就绪度评分权重（含表情，五项和=1.0）
        private const val WEIGHT_COMPOSITION = 0.35f
        private const val WEIGHT_BRIGHTNESS = 0.25f
        private const val WEIGHT_TILT = 0.15f
        private const val WEIGHT_ZOOM = 0.10f
        private const val WEIGHT_EXPRESSION = 0.15f

        // 表情字段缺失（UNKNOWN）时回退的原始四项权重（和=1.0，保证无脸场景无回归）
        private const val BASE_WEIGHT_COMPOSITION = 0.4f
        private const val BASE_WEIGHT_BRIGHTNESS = 0.3f
        private const val BASE_WEIGHT_TILT = 0.2f
        private const val BASE_WEIGHT_ZOOM = 0.1f

        // 拍摄就绪度阈值
        private const val READINESS_READY_THRESHOLD = 0.85f
        private const val READINESS_ALMOST_THRESHOLD = 0.6f

        // 倾斜评分阈值（度）
        private const val TILT_PERFECT = 1f
        private const val TILT_GOOD = 2f
        private const val TILT_FAIR = 4f
        private const val TILT_POOR = 8f

        // 方向提示阈值
        private const val DIRECTION_TILT_THRESHOLD = 3f
        private const val DIRECTION_OFFSET_THRESHOLD = 0.08f
        private const val FRAME_CENTER = 0.5f

        // 脸部俯仰阈值（度）
        private const val FACE_PITCH_OBLIQUE = 12f
        private const val FACE_PITCH_SLIGHT = 6f
        private const val FACE_PITCH_LIGHT_COMPENSATION = 8f

        // 表情判定阈值
        private const val SMILE_TRUE = 0.7f
        private const val SMILE_FAKE = 0.5f
        private const val SMILE_LOW = 0.2f
        private const val EYE_TRUE_MIN = 0.4f
        private const val EYE_TRUE_MAX = 0.8f
        private const val EYE_FAKE = 0.9f
        private const val BLINK_THRESHOLD = 0.3f

        // 视线方向阈值（度）
        private const val GAZE_THRESHOLD = 5f

        // 目标框理想位置（FORWARD/UNKNOWN 默认居中）
        private const val TARGET_CENTER_X = 0.5f
        private const val TARGET_TOP_PORTRAIT = 0.12f
        private const val TARGET_TOP_OTHER = 0.15f

        // 主体面积阈值（按场景）
        private const val AREA_LOW_PORTRAIT = 0.35f
        private const val AREA_LOW_PET = 0.22f
        private const val AREA_LOW_GENERIC = 0.18f
        private const val AREA_HIGH_PORTRAIT = 0.6f
        private const val AREA_HIGH_PET = 0.55f
        private const val AREA_HIGH_GENERIC = 0.65f

        // 孩童/通用场景水平偏移阈值
        private const val CHILD_CENTER_LOW = 0.4f
        private const val CHILD_CENTER_HIGH = 0.6f
        private const val GENERIC_CENTER_LOW = 0.38f
        private const val GENERIC_CENTER_HIGH = 0.62f
    }

    fun build(
        sceneType: SceneType,
        subjectBox: NormalizedRect?,
        horizonTiltDeg: Float,
        @Suppress("DEPRECATION") facePitchDeg: Float?,
        brightnessState: BrightnessState,
        lightDirection: LightDirection,
        faceCount: Int,
        confidence: Float,
        smilingProbability: Float? = null,
        leftEyeOpenProb: Float? = null,
        rightEyeOpenProb: Float? = null,
        headEulerX: Float? = null,
        headEulerY: Float? = null,
        headEulerZ: Float? = null,
        faceLightMetrics: FaceLightMetrics? = null,
        backgroundMetrics: BackgroundMetrics? = null,
        poseMetrics: PoseMetrics? = null,
    ): GuidanceFrame {
        val exposureDelta = when (brightnessState) {
            BrightnessState.BACKLIT,
            BrightnessState.LOW_LIGHT,
            -> 1

            BrightnessState.OVEREXPOSED -> -1
            BrightnessState.BALANCED -> 0
        }

        val zoomSuggestion = determineZoomSuggestion(sceneType, subjectBox)
        logD("build: sceneType=$sceneType, subjectArea=${subjectBox?.area}, zoomSuggestion=$zoomSuggestion")
        // 优先使用 headEulerX，fallback 到 facePitchDeg（兼容旧字段）
        val effectivePitch = headEulerX ?: facePitchDeg
        val recommendation = recommendationFor(
            sceneType = sceneType,
            subjectBox = subjectBox,
            horizonTiltDeg = horizonTiltDeg,
            facePitchDeg = effectivePitch,
            brightnessState = brightnessState,
            lightDirection = lightDirection,
            faceCount = faceCount,
            zoomSuggestion = zoomSuggestion,
            smilingProbability = smilingProbability,
            leftEyeOpenProb = leftEyeOpenProb,
            rightEyeOpenProb = rightEyeOpenProb,
            headEulerY = headEulerY,
        )
        logD("build: brightnessState=$brightnessState, lightDirection=$lightDirection, headEulerX=$headEulerX, headEulerZ=$headEulerZ")
        logD("build: recommendation=\"$recommendation\"")
        val detail = buildDetail(
            sceneType = sceneType,
            subjectBox = subjectBox,
            horizonTiltDeg = horizonTiltDeg,
            facePitchDeg = effectivePitch,
            brightnessState = brightnessState,
            lightDirection = lightDirection,
            confidence = confidence,
            faceCount = faceCount,
        )

        val captureReadiness = calculateCaptureReadiness(
            subjectBox = subjectBox,
            horizonTiltDeg = horizonTiltDeg,
            brightnessState = brightnessState,
            zoomSuggestion = zoomSuggestion,
            smilingProbability = smilingProbability,
            leftEyeOpenProb = leftEyeOpenProb,
            rightEyeOpenProb = rightEyeOpenProb,
            headEulerY = headEulerY,
            faceLightMetrics = faceLightMetrics,
        )
        logD("build: captureReadiness=$captureReadiness")

        val directionHint = determineDirectionHint(
            subjectBox = subjectBox,
            horizonTiltDeg = horizonTiltDeg,
            captureReadiness = captureReadiness,
            headEulerY = headEulerY,
        )
        logD("build: directionHint=$directionHint")

        val targetZone = computeTargetZone(sceneType, subjectBox, captureReadiness, headEulerY)

        // 产生结构化 GuidanceIssue 列表并选主建议（一次只说一件事）
        val issues = buildIssues(
            subjectBox = subjectBox,
            horizonTiltDeg = horizonTiltDeg,
            brightnessState = brightnessState,
            lightDirection = lightDirection,
            faceCount = faceCount,
            headEulerX = headEulerX,
            headEulerY = headEulerY,
            headEulerZ = headEulerZ,
            smilingProbability = smilingProbability,
            leftEyeOpenProb = leftEyeOpenProb,
            rightEyeOpenProb = rightEyeOpenProb,
            sceneType = sceneType,
            faceLightMetrics = faceLightMetrics,
            backgroundMetrics = backgroundMetrics,
            poseMetrics = poseMetrics,
        )
        // 经 RuleVariantResolver 风格变体解析后选主建议
        val primaryIssue = RuleVariantResolver.selectPrimaryIssue(issues, styleProfile)
        val finalRecommendation = primaryIssue?.message ?: recommendation
        val ttsMessage = primaryIssue?.ttsMessage
        logD("build: issues=${issues.size}, primaryIssue=${primaryIssue?.id}, style=${styleProfile.presetId}")

        return GuidanceFrame(
            sceneType = sceneType,
            subjectBox = subjectBox,
            horizonTiltDeg = horizonTiltDeg,
            @Suppress("DEPRECATION")
            facePitchDeg = facePitchDeg,
            smilingProbability = smilingProbability,
            leftEyeOpenProb = leftEyeOpenProb,
            rightEyeOpenProb = rightEyeOpenProb,
            headEulerX = headEulerX,
            headEulerY = headEulerY,
            headEulerZ = headEulerZ,
            faceCount = faceCount,
            brightnessState = brightnessState,
            lightDirection = lightDirection,
            recommendationText = finalRecommendation,
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
                directionHint = directionHint,
            ),
            confidence = confidence.coerceIn(0f, 1f),
            detailText = detail,
            captureReadiness = captureReadiness,
            targetZone = targetZone,
            issues = issues,
            primaryIssue = primaryIssue,
            ttsMessage = ttsMessage,
            styleProfile = styleProfile,
            faceLightMetrics = faceLightMetrics,
            backgroundMetrics = backgroundMetrics,
            poseMetrics = poseMetrics,
        )
    }

    /**
     * 计算主体应移动到的理想位置。保留主体当前尺寸，仅平移到场景对应的目标中心。
     * READY 时不返回目标框（主体已到位，无需引导）。
     */
    private fun computeTargetZone(
        sceneType: SceneType,
        subjectBox: NormalizedRect?,
        captureReadiness: CaptureReadiness,
        headEulerY: Float?,
    ): TargetZone? {
        if (subjectBox == null) return null
        if (captureReadiness == CaptureReadiness.READY) return null

        // 视线朝向决定目标框水平中心：脸朝右留白在左(0.33)，脸朝左留白在右(0.67)
        val gaze = gazeDirection(headEulerY)
        val dynamicCenterX = when (gaze) {
            GazeDirection.LOOK_RIGHT -> THIRDS_LEFT
            GazeDirection.LOOK_LEFT -> THIRDS_RIGHT
            GazeDirection.FORWARD, GazeDirection.UNKNOWN -> TARGET_CENTER_X
        }
        val idealTop = when (sceneType) {
            SceneType.PORTRAIT, SceneType.SELFIE -> TARGET_TOP_PORTRAIT
            SceneType.PET_OR_CHILD -> TARGET_TOP_OTHER
            SceneType.DAILY_GENERIC -> TARGET_TOP_OTHER
        }

        val width = subjectBox.width
        val height = subjectBox.height
        val left = (dynamicCenterX - width / 2f).coerceIn(0f, 1f)
        val right = (dynamicCenterX + width / 2f).coerceIn(0f, 1f)
        val top = idealTop.coerceIn(0f, 1f)
        val bottom = (idealTop + height).coerceIn(0f, 1f)
        return TargetZone(NormalizedRect(left, top, right, bottom))
    }

    private fun determineDirectionHint(
        subjectBox: NormalizedRect?,
        horizonTiltDeg: Float,
        captureReadiness: CaptureReadiness,
        headEulerY: Float?,
    ): DirectionHint {
        if (captureReadiness == CaptureReadiness.READY) return DirectionHint.NONE
        if (subjectBox == null) return DirectionHint.NONE

        val centerX = subjectBox.centerX
        val centerY = subjectBox.centerY

        val horizontalOffset = abs(centerX - FRAME_CENTER)
        val verticalOffset = abs(centerY - FRAME_CENTER)
        val absTilt = abs(horizonTiltDeg)

        if (absTilt > DIRECTION_TILT_THRESHOLD) {
            return if (horizonTiltDeg > 0) DirectionHint.TILT_LEFT else DirectionHint.TILT_RIGHT
        }

        // 主体落在三分点且视线朝向留白 → 合法规图，不纠正位置
        val gaze = gazeDirection(headEulerY)
        if (isOnThirdsPoint(centerX) && gazeTowardWhitespace(centerX, gaze)) {
            return DirectionHint.NONE
        }

        if (horizontalOffset > verticalOffset && horizontalOffset > DIRECTION_OFFSET_THRESHOLD) {
            return if (centerX < FRAME_CENTER) DirectionHint.MOVE_RIGHT else DirectionHint.MOVE_LEFT
        }

        if (verticalOffset > DIRECTION_OFFSET_THRESHOLD) {
            return if (centerY < FRAME_CENTER) DirectionHint.MOVE_DOWN else DirectionHint.MOVE_UP
        }

        return DirectionHint.NONE
    }

    /** 主体中心是否落在左/右三分点容差范围内。 */
    private fun isOnThirdsPoint(centerX: Float): Boolean {
        return abs(centerX - THIRDS_LEFT) <= THIRDS_TOLERANCE ||
            abs(centerX - THIRDS_RIGHT) <= THIRDS_TOLERANCE
    }

    /** 视线是否朝向留白侧：主体偏左→留白在右→需 LOOK_RIGHT；主体偏右→留白在左→需 LOOK_LEFT。 */
    private fun gazeTowardWhitespace(centerX: Float, gaze: GazeDirection): Boolean {
        return if (centerX < FRAME_CENTER) gaze == GazeDirection.LOOK_RIGHT
        else gaze == GazeDirection.LOOK_LEFT
    }

    private fun calculateCaptureReadiness(
        subjectBox: NormalizedRect?,
        horizonTiltDeg: Float,
        brightnessState: BrightnessState,
        zoomSuggestion: ZoomSuggestion,
        smilingProbability: Float?,
        leftEyeOpenProb: Float?,
        rightEyeOpenProb: Float?,
        headEulerY: Float?,
        faceLightMetrics: FaceLightMetrics? = null,
    ): CaptureReadiness {
        if (subjectBox == null) return CaptureReadiness.NOT_READY

        // 眨眼拦截：双眼都可用且最小睁眼概率低于阈值 → 强制不就绪
        if (leftEyeOpenProb != null && rightEyeOpenProb != null) {
            val eyeOpen = minOf(leftEyeOpenProb, rightEyeOpenProb)
            if (eyeOpen < BLINK_THRESHOLD) {
                logD("calculateCaptureReadiness: blinked → NOT_READY (eyeOpen=$eyeOpen)")
                return CaptureReadiness.NOT_READY
            }
        }

        // 阴阳脸阻断：faceLightRatio > 2.0 强制 NOT_READY
        val splitRatio = faceLightMetrics?.faceLightRatio
        if (splitRatio != null && splitRatio > FaceLightEvaluator.SEVERE_RATIO_THRESHOLD) {
            logD("calculateCaptureReadiness: split_face severe → NOT_READY (ratio=$splitRatio)")
            return CaptureReadiness.NOT_READY
        }

        val gaze = gazeDirection(headEulerY)
        val compositionScore = evaluateComposition(subjectBox, gaze)
        val brightnessScore = evaluateBrightness(brightnessState)
        val tiltScore = evaluateTilt(horizonTiltDeg)
        val zoomScore = evaluateZoom(zoomSuggestion)
        logD("calculateCaptureReadiness: compositionScore=$compositionScore, brightnessScore=$brightnessScore, tiltScore=$tiltScore, zoomScore=$zoomScore")

        val expressionState = evaluateExpression(smilingProbability, leftEyeOpenProb, rightEyeOpenProb)
        val score = if (expressionState == ExpressionState.UNKNOWN) {
            // 表情字段缺失：回退原始四项权重，不惩罚无脸/低配场景
            compositionScore * BASE_WEIGHT_COMPOSITION +
                brightnessScore * BASE_WEIGHT_BRIGHTNESS +
                tiltScore * BASE_WEIGHT_TILT +
                zoomScore * BASE_WEIGHT_ZOOM
        } else {
            val expressionScore = evaluateExpressionScore(expressionState)
            logD("calculateCaptureReadiness: expressionState=$expressionState, expressionScore=$expressionScore")
            compositionScore * WEIGHT_COMPOSITION +
                brightnessScore * WEIGHT_BRIGHTNESS +
                tiltScore * WEIGHT_TILT +
                zoomScore * WEIGHT_ZOOM +
                expressionScore * WEIGHT_EXPRESSION
        }

        logD("calculateCaptureReadiness: totalScore=$score")

        return when {
            score >= READINESS_READY_THRESHOLD -> CaptureReadiness.READY
            score >= READINESS_ALMOST_THRESHOLD -> CaptureReadiness.ALMOST_READY
            else -> CaptureReadiness.NOT_READY
        }
    }

    /**
     * 三分法构图评分：主体在三分点 + 视线朝向留白 → 满分；
     * 三分点但视线看墙 → 0.4；正中 → 0.8（保留为合法构图）；其他 → 0.2。
     * 叠加顶部留白与贴边评估。
     */
    private fun evaluateComposition(subjectBox: NormalizedRect, gaze: GazeDirection): Float {
        val centerX = subjectBox.centerX
        val onThirds = isOnThirdsPoint(centerX)
        val towardWhitespace = gazeTowardWhitespace(centerX, gaze)
        val centerScore = when {
            onThirds && towardWhitespace -> 1.0f
            onThirds && !towardWhitespace && gaze != GazeDirection.UNKNOWN -> 0.4f
            abs(centerX - FRAME_CENTER) <= CENTER_TOLERANCE -> 0.8f
            else -> 0.2f
        }

        val topScore = when {
            subjectBox.top >= TOP_IDEAL_MIN && subjectBox.top <= TOP_IDEAL_MAX -> 1.0f
            subjectBox.top >= TOP_OK_MIN && subjectBox.top <= TOP_OK_MAX -> 0.6f
            else -> 0.2f
        }

        val edgeScore = if (subjectBox.touchesEdge()) 0.0f else 1.0f

        return (centerScore * 0.5f + topScore * 0.3f + edgeScore * 0.2f)
    }

    /** 表情评估：基于 smile 概率与双眼最小睁眼概率联合判定。任一字段缺失 → UNKNOWN。 */
    private fun evaluateExpression(smiling: Float?, leftEye: Float?, rightEye: Float?): ExpressionState {
        if (smiling == null || leftEye == null || rightEye == null) return ExpressionState.UNKNOWN
        val eyeOpen = minOf(leftEye, rightEye)
        return when {
            smiling > SMILE_TRUE && eyeOpen in EYE_TRUE_MIN..EYE_TRUE_MAX -> ExpressionState.NATURAL_SMILE
            smiling > SMILE_FAKE && eyeOpen > EYE_FAKE -> ExpressionState.FAKE_SMILE
            smiling >= SMILE_LOW && smiling <= SMILE_FAKE && eyeOpen > EYE_FAKE -> ExpressionState.AWKWARD
            smiling < SMILE_LOW -> ExpressionState.NO_SMILE
            else -> ExpressionState.UNKNOWN
        }
    }

    /** 表情评分映射。UNKNOWN 走归一化分支不调用此值。 */
    private fun evaluateExpressionScore(state: ExpressionState): Float = when (state) {
        ExpressionState.NATURAL_SMILE -> 1.0f
        ExpressionState.FAKE_SMILE -> 0.4f
        ExpressionState.AWKWARD -> 0.3f
        ExpressionState.NO_SMILE -> 0.5f
        ExpressionState.UNKNOWN -> 0.7f
    }

    /**
     * 产生结构化 GuidanceIssue 列表。优先级与 recommendationFor 的 if/else 顺序一致，
     * 但每条规则独立产出 issue，便于拍后复盘、风格变体和云端同步。
     */
    private fun buildIssues(
        subjectBox: NormalizedRect?,
        horizonTiltDeg: Float,
        brightnessState: BrightnessState,
        lightDirection: LightDirection,
        faceCount: Int,
        headEulerX: Float?,
        headEulerY: Float?,
        headEulerZ: Float?,
        smilingProbability: Float?,
        leftEyeOpenProb: Float?,
        rightEyeOpenProb: Float?,
        sceneType: SceneType,
        faceLightMetrics: FaceLightMetrics? = null,
        backgroundMetrics: BackgroundMetrics? = null,
        poseMetrics: PoseMetrics? = null,
    ): List<GuidanceIssue> {
        val issues = mutableListOf<GuidanceIssue>()
        val isPortraitScene = sceneType == SceneType.PORTRAIT || sceneType == SceneType.SELFIE

        // P0 安全废片（阻断 READY）
        if (subjectBox == null) {
            issues += GuidanceIssue(
                id = "safety.subject_lost",
                category = GuidanceCategory.SAFETY,
                severity = IssueSeverity.CRITICAL,
                confidence = 0.9f,
                priority = 10,
                message = "让主体进入画面中央，AI 会继续给出建议。",
                action = SubjectAction.RECOMPOSE_MOVE,
                blocksReady = true,
                retryReason = RetryReason.SUBJECT_OFF_CENTER,
            )
        } else {
            // 眨眼
            val minEyeOpen = listOfNotNull(leftEyeOpenProb, rightEyeOpenProb).minOrNull()
            if (minEyeOpen != null && minEyeOpen < BLINK_THRESHOLD) {
                issues += GuidanceIssue(
                    id = "safety.blink",
                    category = GuidanceCategory.SAFETY,
                    severity = IssueSeverity.CRITICAL,
                    confidence = 0.95f,
                    priority = 11,
                    message = "你眨眼了，再来一张。",
                    ttsMessage = "你眨眼了，再来一张",
                    action = SubjectAction.EYES_ON_LENS,
                    blocksReady = true,
                    retryReason = RetryReason.BLINKED,
                )
            }
            // 严重过曝
            if (brightnessState == BrightnessState.OVEREXPOSED) {
                issues += GuidanceIssue(
                    id = "safety.overexposed",
                    category = GuidanceCategory.SAFETY,
                    severity = IssueSeverity.HIGH,
                    confidence = 0.85f,
                    priority = 12,
                    message = "画面偏亮，压低曝光后再拍。",
                    blocksReady = true,
                    retryReason = RetryReason.LIGHT_OVEREXPOSED,
                )
            }
            // 严重倾斜
            if (abs(horizonTiltDeg) > TILT_POOR) {
                issues += GuidanceIssue(
                    id = "safety.severe_tilt",
                    category = GuidanceCategory.SAFETY,
                    severity = IssueSeverity.HIGH,
                    confidence = 0.85f,
                    priority = 13,
                    message = "画面倾斜明显，扶正手机。",
                    ttsMessage = "扶正画面",
                    blocksReady = true,
                    retryReason = RetryReason.HORIZON_TILTED,
                )
            }
        }

        // P1 光线废片
        if (brightnessState == BrightnessState.BACKLIT) {
            issues += GuidanceIssue(
                id = "light.backlit",
                category = GuidanceCategory.LIGHT,
                severity = IssueSeverity.HIGH,
                confidence = 0.8f,
                priority = 20,
                message = "逆光，转向光源或打开补光。",
                ttsMessage = "逆光，转向光源",
                action = SubjectAction.FACE_TURN_TO_LIGHT,
                blocksReady = true,
                retryReason = RetryReason.LIGHT_BACKLIT,
            )
        } else if (brightnessState == BrightnessState.LOW_LIGHT) {
            issues += GuidanceIssue(
                id = "light.low_light",
                category = GuidanceCategory.LIGHT,
                severity = IssueSeverity.HIGH,
                confidence = 0.8f,
                priority = 21,
                message = "环境偏暗，先稳住手机或打开补光。",
                action = SubjectAction.FACE_TURN_TO_LIGHT,
                blocksReady = true,
                retryReason = RetryReason.LIGHT_TOO_DARK,
            )
        }

        // P2 光线废片：阴阳脸（侧光）
        val splitRatio = faceLightMetrics?.faceLightRatio
        val hasSplitFace = splitRatio != null && splitRatio > FaceLightEvaluator.RATIO_THRESHOLD
        if (hasSplitFace) {
            val isSevere = splitRatio!! > FaceLightEvaluator.SEVERE_RATIO_THRESHOLD
            issues += GuidanceIssue(
                id = "light.split_face",
                category = GuidanceCategory.LIGHT,
                severity = if (isSevere) IssueSeverity.HIGH else IssueSeverity.MEDIUM,
                confidence = 0.8f,
                priority = if (isSevere) 22 else 23,
                message = "侧光有点强，脸再转向光源一点。",
                ttsMessage = "侧光有点强，脸再转向光源一点",
                action = SubjectAction.FACE_TURN_TO_LIGHT,
                blocksReady = isSevere,
                retryReason = if (isSevere) RetryReason.LIGHT_BACKLIT else null,
            )
        }

        // P2 主动型提醒：前侧光人像黄金光位（INFO 级夸赞，不阻断 READY）
        // 条件：人像场景 + 光线平衡 + 有明确侧向光源 + 脸略微侧转(10-40°) + 无阴阳脸
        if (isPortraitScene &&
            brightnessState == BrightnessState.BALANCED &&
            (lightDirection == LightDirection.LEFT || lightDirection == LightDirection.RIGHT) &&
            !hasSplitFace
        ) {
            val yaw = headEulerY
            val yawGood = yaw != null && abs(yaw) in 10f..40f
            if (yawGood) {
                issues += GuidanceIssue(
                    id = "light.front_side_good",
                    category = GuidanceCategory.LIGHT,
                    severity = IssueSeverity.INFO,
                    confidence = 0.7f,
                    priority = 95,
                    message = "现在光线很好，适合拍人像。",
                    ttsMessage = "现在光线很好，适合拍人像",
                )
            }
        }

        // P3 姿态废片（仅人像/自拍场景）
        if (isPortraitScene) {
            // 头歪（headEulerZ）
            if (headEulerZ != null && abs(headEulerZ) > 6f) {
                issues += GuidanceIssue(
                    id = "pose.head_tilt",
                    category = GuidanceCategory.POSE,
                    severity = IssueSeverity.MEDIUM,
                    confidence = 0.8f,
                    priority = 30,
                    message = "头扶正一点，会更精神。",
                    ttsMessage = "头扶正一点",
                    action = SubjectAction.HEAD_STRAIGHTEN,
                )
            }
            // 下巴过低/过高（headEulerX）
            if (headEulerX != null) {
                when {
                    headEulerX > 8f -> issues += GuidanceIssue(
                        id = "pose.chin_high",
                        category = GuidanceCategory.POSE,
                        severity = IssueSeverity.MEDIUM,
                        confidence = 0.8f,
                        priority = 31,
                        message = "下巴稍微收回来一点。",
                        ttsMessage = "下巴稍微收回来一点",
                        action = SubjectAction.CHIN_TUCK,
                    )
                    headEulerX < -8f -> issues += GuidanceIssue(
                        id = "pose.chin_low",
                        category = GuidanceCategory.POSE,
                        severity = IssueSeverity.MEDIUM,
                        confidence = 0.8f,
                        priority = 32,
                        message = "眼睛看镜头上方一点，下巴轻轻收住。",
                        ttsMessage = "下巴轻轻收住，眼睛看镜头上方一点",
                        action = SubjectAction.CHIN_TUCK,
                    )
                }
            }
            // 脸转太多（headEulerY）
            if (headEulerY != null && abs(headEulerY) > 35f) {
                issues += GuidanceIssue(
                    id = "pose.face_turn_too_much",
                    category = GuidanceCategory.POSE,
                    severity = IssueSeverity.MEDIUM,
                    confidence = 0.75f,
                    priority = 33,
                    message = "脸转回来一点，看得到眼神更好。",
                    ttsMessage = "脸转回来一点",
                    action = SubjectAction.FACE_TURN_BACK,
                )
            }
        }

        // P4 表情微调
        val expressionState = evaluateExpression(smilingProbability, leftEyeOpenProb, rightEyeOpenProb)
        when (expressionState) {
            ExpressionState.FAKE_SMILE -> issues += GuidanceIssue(
                id = "expression.fake_smile",
                category = GuidanceCategory.EXPRESSION,
                severity = IssueSeverity.LOW,
                confidence = 0.7f,
                priority = 40,
                message = "放松点，想想开心的事，真笑一下。",
                ttsMessage = "放松点，想想开心的事",
                action = SubjectAction.NATURAL_SMILE_HOLD,
            )
            ExpressionState.AWKWARD -> issues += GuidanceIssue(
                id = "expression.awkward",
                category = GuidanceCategory.EXPRESSION,
                severity = IssueSeverity.LOW,
                confidence = 0.65f,
                priority = 41,
                message = "表情有点紧，嘴角放松一下。",
                ttsMessage = "嘴角放松一下",
            )
            ExpressionState.NATURAL_SMILE -> issues += GuidanceIssue(
                id = "expression.natural_smile",
                category = GuidanceCategory.EXPRESSION,
                severity = IssueSeverity.INFO,
                confidence = 0.9f,
                priority = 90,
                message = "很好，这个笑很自然，保持。",
                ttsMessage = "很好，保持",
                action = SubjectAction.NATURAL_SMILE_HOLD,
            )
            else -> Unit
        }

        // P2 背景干扰（软提示，不阻断 READY）
        if (isPortraitScene && backgroundMetrics != null) {
            // 柱子/电线杆穿头
            if (backgroundMetrics.hasVerticalLineAboveHead == true) {
                issues += GuidanceIssue(
                    id = "background.line_through_head",
                    category = GuidanceCategory.BACKGROUND,
                    severity = IssueSeverity.MEDIUM,
                    confidence = 0.7f,
                    priority = 50,
                    message = "头顶有电线杆/柱子，挪一步换个角度。",
                    ttsMessage = "头顶有柱子，挪一步",
                )
            }
            // 亮斑抢戏
            if (backgroundMetrics.hasHotspotNearHead == true) {
                issues += GuidanceIssue(
                    id = "background.hotspot",
                    category = GuidanceCategory.BACKGROUND,
                    severity = IssueSeverity.MEDIUM,
                    confidence = 0.7f,
                    priority = 51,
                    message = "背景有高光抢戏，换个角度避开。",
                    ttsMessage = "背景有高光抢戏，换个角度",
                )
            }
            // 背景杂乱
            val clutter = backgroundMetrics.clutterScore
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
            }
        }

        // P2-5 姿态干扰（软提示，不阻断 READY）
        if (isPortraitScene && poseMetrics != null) {
            // 关节贴边（肩/肘/腕出框）
            if (poseMetrics.hasJointAtEdge) {
                issues += GuidanceIssue(
                    id = "pose.joint_cropped",
                    category = GuidanceCategory.POSE,
                    severity = IssueSeverity.MEDIUM,
                    confidence = 0.7f,
                    priority = 53,
                    message = "${poseMetrics.croppedJointName ?: "肩部"}出框了，挪一步或拉远一点。",
                    ttsMessage = "${poseMetrics.croppedJointName ?: "肩部"}出框，挪一步",
                    action = SubjectAction.RECOMPOSE_MOVE,
                )
            }
            // 手部遮挡脸
            if (poseMetrics.hasHandCoveringFace) {
                issues += GuidanceIssue(
                    id = "pose.hand_covering_face",
                    category = GuidanceCategory.POSE,
                    severity = IssueSeverity.MEDIUM,
                    confidence = 0.75f,
                    priority = 54,
                    message = "手遮脸了，放下或挪开。",
                    ttsMessage = "手遮脸了，放下",
                )
            }
            // 双肩倾斜
            val shoulderTilt = poseMetrics.shoulderImbalanceDeg
            if (shoulderTilt != null) {
                issues += GuidanceIssue(
                    id = "pose.shoulder_tilted",
                    category = GuidanceCategory.POSE,
                    severity = IssueSeverity.LOW,
                    confidence = 0.6f,
                    priority = 55,
                    message = "肩膀有点歪，放松一下。",
                    ttsMessage = "肩膀放松一点",
                )
            }
        }

        return issues
    }

    /** 按 priority → severity → blocksReady 排序，选第一条作为实时主建议。 */
    private fun selectPrimaryIssue(issues: List<GuidanceIssue>): GuidanceIssue? {
        if (issues.isEmpty()) return null
        val severityRank = mapOf(
            IssueSeverity.CRITICAL to 0,
            IssueSeverity.HIGH to 1,
            IssueSeverity.MEDIUM to 2,
            IssueSeverity.LOW to 3,
            IssueSeverity.INFO to 4,
        )
        return issues.sortedWith(
            compareBy<GuidanceIssue> { it.priority }
                .thenBy { severityRank[it.severity] ?: 5 }
                .thenByDescending { it.blocksReady },
        ).first()
    }

    /** 视线方向：基于头部偏航角。null → UNKNOWN。 */
    private fun gazeDirection(headEulerY: Float?): GazeDirection {
        val yaw = headEulerY ?: return GazeDirection.UNKNOWN
        return when {
            abs(yaw) < GAZE_THRESHOLD -> GazeDirection.FORWARD
            yaw > GAZE_THRESHOLD -> GazeDirection.LOOK_LEFT
            else -> GazeDirection.LOOK_RIGHT
        }
    }

    private fun evaluateBrightness(brightnessState: BrightnessState): Float {
        return when (brightnessState) {
            BrightnessState.BALANCED -> 1.0f
            BrightnessState.LOW_LIGHT -> 0.3f
            BrightnessState.BACKLIT -> 0.2f
            BrightnessState.OVEREXPOSED -> 0.1f
        }
    }

    private fun evaluateTilt(horizonTiltDeg: Float): Float {
        val absTilt = abs(horizonTiltDeg)
        return when {
            absTilt <= TILT_PERFECT -> 1.0f
            absTilt <= TILT_GOOD -> 0.8f
            absTilt <= TILT_FAIR -> 0.5f
            absTilt <= TILT_POOR -> 0.2f
            else -> 0.0f
        }
    }

    private fun evaluateZoom(zoomSuggestion: ZoomSuggestion): Float {
        return when (zoomSuggestion) {
            ZoomSuggestion.NONE -> 1.0f
            ZoomSuggestion.MOVE_CLOSER -> 0.4f
            ZoomSuggestion.MOVE_BACK -> 0.5f
            ZoomSuggestion.STEP_BACK_USE_2X -> 0.5f
        }
    }

    private fun recommendationFor(
        sceneType: SceneType,
        subjectBox: NormalizedRect?,
        horizonTiltDeg: Float,
        facePitchDeg: Float?,
        brightnessState: BrightnessState,
        lightDirection: LightDirection,
        faceCount: Int,
        zoomSuggestion: ZoomSuggestion,
        smilingProbability: Float?,
        leftEyeOpenProb: Float?,
        rightEyeOpenProb: Float?,
        headEulerY: Float?,
    ): String {
        if (subjectBox == null) {
            logD("recommendationFor: no subject, brightnessState=$brightnessState")
            return when (brightnessState) {
                BrightnessState.LOW_LIGHT -> "环境偏暗，先稳住手机并把主体放到中央。"
                BrightnessState.BACKLIT -> "先让主体进入画面，再尝试换到更亮的位置。"
                BrightnessState.OVEREXPOSED -> "高光偏强，先把主体放回画面中央。"
                BrightnessState.BALANCED -> "让主体进入画面中央，AI 会继续给出建议。"
            }
        }

        if (brightnessState == BrightnessState.BACKLIT || brightnessState == BrightnessState.LOW_LIGHT) {
            val lightAdvice = getLightDirectionAdvice(brightnessState, lightDirection, facePitchDeg, faceCount > 0)
            logD("recommendationFor: lowLight branch, lightDirection=$lightDirection, facePitchDeg=$facePitchDeg, advice=$lightAdvice")
            if (lightAdvice != null) return lightAdvice
            return if (brightnessState == BrightnessState.BACKLIT) {
                "换到更亮的位置，或打开补光。"
            } else {
                "环境偏暗，先稳住手机或打开补光。"
            }
        }
        if (brightnessState == BrightnessState.OVEREXPOSED) {
            logD("recommendationFor: overexposed")
            return "画面偏亮，压低曝光后再拍。"
        }

        val facePitchAdvice = getFacePitchAdvice(facePitchDeg, sceneType)
        if (facePitchAdvice != null) {
            logD("recommendationFor: facePitch branch, facePitchDeg=$facePitchDeg, advice=$facePitchAdvice")
            return facePitchAdvice
        }

        // 表情分支：插入在 facePitch 之后、场景文案之前
        val gaze = gazeDirection(headEulerY)
        val expressionAdvice = expressionAdvice(smilingProbability, leftEyeOpenProb, rightEyeOpenProb)
        if (expressionAdvice != null) {
            logD("recommendationFor: expression branch, gaze=$gaze, advice=$expressionAdvice")
            return expressionAdvice
        }

        return when (sceneType) {
            SceneType.PORTRAIT,
            SceneType.SELFIE,
            -> portraitRecommendation(subjectBox, horizonTiltDeg, zoomSuggestion, gaze)

            SceneType.PET_OR_CHILD -> childRecommendation(subjectBox, horizonTiltDeg, zoomSuggestion)
            SceneType.DAILY_GENERIC -> genericRecommendation(subjectBox, horizonTiltDeg, zoomSuggestion)
        }
    }

    /**
     * 表情文案：眨眼/假笑/尴尬/没笑返回提示；真笑或字段缺失返回 null（落回场景文案）。
     */
    private fun expressionAdvice(
        smiling: Float?,
        leftEye: Float?,
        rightEye: Float?,
    ): String? {
        // 眨眼：双眼都可用且最小睁眼概率低于阈值
        if (leftEye != null && rightEye != null && minOf(leftEye, rightEye) < BLINK_THRESHOLD) {
            return "你眨眼了，再来一张"
        }
        return when (evaluateExpression(smiling, leftEye, rightEye)) {
            ExpressionState.FAKE_SMILE -> "放松点，想想开心的事，真笑一下"
            ExpressionState.AWKWARD -> "深呼吸，放松脸部"
            ExpressionState.NO_SMILE -> "可以笑一下"
            ExpressionState.NATURAL_SMILE, ExpressionState.UNKNOWN -> null
        }
    }

    private fun getLightDirectionAdvice(
        brightnessState: BrightnessState,
        lightDirection: LightDirection,
        facePitchDeg: Float?,
        hasFace: Boolean,
    ): String? {
        if (lightDirection == LightDirection.UNKNOWN || !hasFace) return null

        val pitchCompensation = facePitchDeg?.let {
            when {
                it > FACE_PITCH_LIGHT_COMPENSATION -> LightDirection.TOP
                it < -FACE_PITCH_LIGHT_COMPENSATION -> LightDirection.BOTTOM
                else -> null
            }
        }

        val effectiveDirection = pitchCompensation ?: lightDirection
        if (effectiveDirection == LightDirection.UNKNOWN) return null

        val targetDirection = when (effectiveDirection) {
            LightDirection.LEFT -> "右侧"
            LightDirection.RIGHT -> "左侧"
            LightDirection.TOP -> "下方"
            LightDirection.BOTTOM -> "上方"
            LightDirection.UNKNOWN -> return null
        }

        return when (brightnessState) {
            BrightnessState.BACKLIT -> "脸转向${targetDirection}光源"
            BrightnessState.LOW_LIGHT -> "转向${targetDirection}光源增加面部亮度"
            else -> null
        }
    }

    private fun getFacePitchAdvice(facePitchDeg: Float?, sceneType: SceneType): String? {
        if (facePitchDeg == null) return null
        if (sceneType != SceneType.PORTRAIT && sceneType != SceneType.SELFIE) return null

        return when {
            facePitchDeg > FACE_PITCH_OBLIQUE -> "手机稍微向右转一点，让脸更自然。"
            facePitchDeg < -FACE_PITCH_OBLIQUE -> "手机稍微向左转一点，让脸更自然。"
            facePitchDeg > FACE_PITCH_SLIGHT -> "脸可以稍微转正一点。"
            facePitchDeg < -FACE_PITCH_SLIGHT -> "脸可以稍微转正一点。"
            else -> null
        }
    }

    private fun portraitRecommendation(
        subjectBox: NormalizedRect,
        horizonTiltDeg: Float,
        zoomSuggestion: ZoomSuggestion,
        gaze: GazeDirection,
    ): String {
        val centerX = subjectBox.centerX
        val onThirds = isOnThirdsPoint(centerX)
        val towardWhitespace = gazeTowardWhitespace(centerX, gaze)
        val rec = when {
            zoomSuggestion == ZoomSuggestion.MOVE_CLOSER -> "再靠近一点，让主体占满画面。"
            zoomSuggestion == ZoomSuggestion.STEP_BACK_USE_2X -> "退一步，用 2x 拍，避免广角畸变。"
            zoomSuggestion == ZoomSuggestion.MOVE_BACK -> "稍微后退一些，保留更自然的人像空间。"
            subjectBox.touchesEdge() -> "让人物离边缘远一点。"
            onThirds && towardWhitespace -> "构图稳定，可以拍摄。"
            onThirds && gaze != GazeDirection.UNKNOWN && !towardWhitespace -> "让脸转向画面深处。"
            subjectBox.top < TOP_IDEAL_MIN -> "镜头再低一点，留出头顶空间。"
            subjectBox.top > TOP_IDEAL_MAX -> "镜头再高一点，让视线接近上三分线。"
            abs(horizonTiltDeg) > TILT_GOOD -> "扶正画面。"
            else -> "构图稳定，可以拍摄。"
        }
        logD("portraitRecommendation: centerX=${String.format("%.2f", centerX)}, top=${String.format("%.2f", subjectBox.top)}, tilt=${String.format("%.1f", horizonTiltDeg)}, gaze=$gaze, rec=\"$rec\"")
        return rec
    }

    private fun childRecommendation(
        subjectBox: NormalizedRect,
        horizonTiltDeg: Float,
        zoomSuggestion: ZoomSuggestion,
    ): String {
        val rec = when {
            zoomSuggestion == ZoomSuggestion.MOVE_CLOSER -> "再靠近一点，抓住主体表情。"
            subjectBox.centerX < CHILD_CENTER_LOW -> "镜头向左一点，别让主体贴边。"
            subjectBox.centerX > CHILD_CENTER_HIGH -> "镜头向右一点，别让主体贴边。"
            abs(horizonTiltDeg) > TILT_GOOD -> "扶正画面，避免地平线歪斜。"
            else -> "主体位置不错，保持稳定就能拍。"
        }
        logD("childRecommendation: centerX=${String.format("%.2f", subjectBox.centerX)}, tilt=${String.format("%.1f", horizonTiltDeg)}, rec=\"$rec\"")
        return rec
    }

    private fun genericRecommendation(
        subjectBox: NormalizedRect,
        horizonTiltDeg: Float,
        zoomSuggestion: ZoomSuggestion,
    ): String {
        val rec = when {
            subjectBox.touchesEdge() -> "让主体离边缘远一点。"
            zoomSuggestion == ZoomSuggestion.MOVE_CLOSER -> "靠近主体一点，让画面更聚焦。"
            zoomSuggestion == ZoomSuggestion.MOVE_BACK -> "退后一点，给主体更多呼吸空间。"
            subjectBox.centerX < GENERIC_CENTER_LOW -> "镜头向左一点，主体更容易居中。"
            subjectBox.centerX > GENERIC_CENTER_HIGH -> "镜头向右一点，主体更容易居中。"
            abs(horizonTiltDeg) > TILT_GOOD -> "扶正画面。"
            else -> "构图平稳，可以拍摄。"
        }
        logD("genericRecommendation: centerX=${String.format("%.2f", subjectBox.centerX)}, touchesEdge=${subjectBox.touchesEdge()}, tilt=${String.format("%.1f", horizonTiltDeg)}, rec=\"$rec\"")
        return rec
    }

    private fun determineZoomSuggestion(sceneType: SceneType, subjectBox: NormalizedRect?): ZoomSuggestion {
        if (subjectBox == null) return ZoomSuggestion.NONE
        val area = subjectBox.area
        val lowTarget = when (sceneType) {
            SceneType.PORTRAIT,
            SceneType.SELFIE,
            -> AREA_LOW_PORTRAIT

            SceneType.PET_OR_CHILD -> AREA_LOW_PET
            SceneType.DAILY_GENERIC -> AREA_LOW_GENERIC
        }
        val highTarget = when (sceneType) {
            SceneType.PORTRAIT,
            SceneType.SELFIE,
            -> AREA_HIGH_PORTRAIT

            SceneType.PET_OR_CHILD -> AREA_HIGH_PET
            SceneType.DAILY_GENERIC -> AREA_HIGH_GENERIC
        }
        return when {
            area < lowTarget -> ZoomSuggestion.MOVE_CLOSER
            area > highTarget -> {
                // P1：人像场景广角怼脸，建议退一步用 2x 拍
                if (sceneType == SceneType.PORTRAIT || sceneType == SceneType.SELFIE) {
                    ZoomSuggestion.STEP_BACK_USE_2X
                } else {
                    ZoomSuggestion.MOVE_BACK
                }
            }
            else -> ZoomSuggestion.NONE
        }
    }

    private fun buildDetail(
        sceneType: SceneType,
        subjectBox: NormalizedRect?,
        horizonTiltDeg: Float,
        facePitchDeg: Float?,
        brightnessState: BrightnessState,
        lightDirection: LightDirection,
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
        val lightLabel = if (lightDirection != LightDirection.UNKNOWN) {
            " · 光源在${when (lightDirection) {
                LightDirection.LEFT -> "左侧"
                LightDirection.RIGHT -> "右侧"
                LightDirection.TOP -> "上方"
                LightDirection.BOTTOM -> "下方"
                LightDirection.UNKNOWN -> ""
            }}"
        } else ""
        val pitchLabel = facePitchDeg?.let {
            " · 脸型 ${if (it > 0) "偏左" else "偏右"} ${abs(it).roundToInt()}°"
        } ?: ""
        val areaPercent = subjectBox?.let { (it.area * 100).roundToInt() } ?: 0
        return buildString {
            append(sceneLabel)
            append(" · ")
            append(brightnessLabel)
            append(lightLabel)
            append(" · 倾斜 ")
            append(String.format("%.1f", abs(horizonTiltDeg)))
            append("°")
            append(pitchLabel)
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

