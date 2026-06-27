package com.yuxiang.aiphoto.analysis

import com.google.common.truth.Truth.assertThat
import com.yuxiang.aiphoto.model.BrightnessState
import com.yuxiang.aiphoto.model.CaptureReadiness
import com.yuxiang.aiphoto.model.DirectionHint
import com.yuxiang.aiphoto.model.GuidanceFrame
import com.yuxiang.aiphoto.model.LightDirection
import com.yuxiang.aiphoto.model.NormalizedRect
import com.yuxiang.aiphoto.model.RetryReason
import com.yuxiang.aiphoto.model.SceneType
import com.yuxiang.aiphoto.model.ZoomSuggestion
import org.junit.Test

class GuidanceEngineTest {
    private val engine = GuidanceEngine()

    @Test
    fun portraitWithSmallSubject_recommendsMoveCloser() {
        val frame = engine.build(
            sceneType = SceneType.PORTRAIT,
            subjectBox = NormalizedRect(0.35f, 0.18f, 0.55f, 0.58f),
            horizonTiltDeg = 0.6f,
            facePitchDeg = null,
            brightnessState = BrightnessState.BALANCED,
            lightDirection = LightDirection.UNKNOWN,
            faceCount = 1,
            confidence = 0.9f,
        )

        assertThat(frame.recommendationText).contains("靠近")
        assertThat(frame.cameraAction.zoomSuggestion).isEqualTo(ZoomSuggestion.MOVE_CLOSER)
    }

    @Test
    fun backlitFrame_boostsExposureAndWarnsUser() {
        val frame = engine.build(
            sceneType = SceneType.PORTRAIT,
            subjectBox = NormalizedRect(0.22f, 0.1f, 0.72f, 0.85f),
            horizonTiltDeg = 1.2f,
            facePitchDeg = null,
            brightnessState = BrightnessState.BACKLIT,
            lightDirection = LightDirection.LEFT,
            faceCount = 1,
            confidence = 0.92f,
        )

        assertThat(frame.recommendationText).contains("光源")
        assertThat(frame.cameraAction.exposureCompensationDelta).isEqualTo(1)
    }

    @Test
    fun backlitWithLightOnRight_recommendsTurnToLeft() {
        val frame = engine.build(
            sceneType = SceneType.PORTRAIT,
            subjectBox = NormalizedRect(0.22f, 0.1f, 0.72f, 0.85f),
            horizonTiltDeg = 0f,
            facePitchDeg = null,
            brightnessState = BrightnessState.BACKLIT,
            lightDirection = LightDirection.RIGHT,
            faceCount = 1,
            confidence = 0.92f,
        )

        assertThat(frame.recommendationText).contains("左侧光源")
    }

    @Test
    fun lowLightFaceTurnAway_recommendsTurnToLight() {
        val frame = engine.build(
            sceneType = SceneType.SELFIE,
            subjectBox = NormalizedRect(0.28f, 0.15f, 0.72f, 0.82f),
            horizonTiltDeg = 0f,
            facePitchDeg = null,
            brightnessState = BrightnessState.LOW_LIGHT,
            lightDirection = LightDirection.TOP,
            faceCount = 1,
            confidence = 0.9f,
        )

        assertThat(frame.recommendationText).contains("下方光源")
    }

    @Test
    fun facePitchTurnedLeft_recommendsTurnRight() {
        val frame = engine.build(
            sceneType = SceneType.PORTRAIT,
            subjectBox = NormalizedRect(0.35f, 0.12f, 0.65f, 0.78f),
            horizonTiltDeg = 0f,
            facePitchDeg = 15f,
            brightnessState = BrightnessState.BALANCED,
            lightDirection = LightDirection.UNKNOWN,
            faceCount = 1,
            confidence = 0.9f,
        )

        assertThat(frame.recommendationText).contains("向右转")
    }

    @Test
    fun genericSubjectOnEdge_recommendsMoveAwayFromBorder() {
        val frame = engine.build(
            sceneType = SceneType.DAILY_GENERIC,
            subjectBox = NormalizedRect(0.01f, 0.16f, 0.45f, 0.88f),
            horizonTiltDeg = 0.4f,
            facePitchDeg = null,
            brightnessState = BrightnessState.BALANCED,
            lightDirection = LightDirection.UNKNOWN,
            faceCount = 0,
            confidence = 0.56f,
        )

        assertThat(frame.recommendationText).contains("边缘")
    }

    // ===== P0-2: 表情与眨眼 =====

    @Test
    fun blink_returnsNotReady() {
        val frame = engine.build(
            sceneType = SceneType.PORTRAIT,
            subjectBox = NormalizedRect(0.35f, 0.12f, 0.65f, 0.62f),
            horizonTiltDeg = 0f,
            facePitchDeg = null,
            brightnessState = BrightnessState.BALANCED,
            lightDirection = LightDirection.UNKNOWN,
            faceCount = 1,
            confidence = 0.9f,
            smilingProbability = 0.8f,
            leftEyeOpenProb = 0.2f,
            rightEyeOpenProb = 0.25f,
            headEulerY = 0f,
        )
        assertThat(frame.captureReadiness).isEqualTo(CaptureReadiness.NOT_READY)
        assertThat(frame.recommendationText).contains("眨眼")
    }

    @Test
    fun naturalSmile_readyToCapture() {
        val frame = engine.build(
            sceneType = SceneType.PORTRAIT,
            subjectBox = NormalizedRect(0.35f, 0.12f, 0.65f, 0.62f),
            horizonTiltDeg = 0f,
            facePitchDeg = null,
            brightnessState = BrightnessState.BALANCED,
            lightDirection = LightDirection.UNKNOWN,
            faceCount = 1,
            confidence = 0.9f,
            smilingProbability = 0.85f,
            leftEyeOpenProb = 0.55f,
            rightEyeOpenProb = 0.6f,
            headEulerY = 0f,
        )
        // NATURAL_SMILE → expressionScore=1.0，其余各项均满分 → READY
        assertThat(frame.captureReadiness).isEqualTo(CaptureReadiness.READY)
    }

    @Test
    fun fakeSmile_recommendsRealSmile() {
        val frame = engine.build(
            sceneType = SceneType.PORTRAIT,
            subjectBox = NormalizedRect(0.35f, 0.12f, 0.65f, 0.62f),
            horizonTiltDeg = 0f,
            facePitchDeg = null,
            brightnessState = BrightnessState.BALANCED,
            lightDirection = LightDirection.UNKNOWN,
            faceCount = 1,
            confidence = 0.9f,
            smilingProbability = 0.6f,
            leftEyeOpenProb = 0.95f,
            rightEyeOpenProb = 0.95f,
            headEulerY = 0f,
        )
        assertThat(frame.recommendationText).contains("真笑")
    }

    @Test
    fun nullFaceFields_degradesGracefully() {
        val frame = engine.build(
            sceneType = SceneType.DAILY_GENERIC,
            subjectBox = NormalizedRect(0.4f, 0.15f, 0.6f, 0.6f),
            horizonTiltDeg = 0f,
            facePitchDeg = null,
            brightnessState = BrightnessState.BALANCED,
            lightDirection = LightDirection.UNKNOWN,
            faceCount = 0,
            confidence = 0.5f,
            smilingProbability = null,
            leftEyeOpenProb = null,
            rightEyeOpenProb = null,
            headEulerY = null,
        )
        // UNKNOWN → 走 BASE_WEIGHT 四项归一化，不 crash
        assertThat(frame.captureReadiness).isAnyOf(
            CaptureReadiness.READY,
            CaptureReadiness.ALMOST_READY,
            CaptureReadiness.NOT_READY,
        )
    }

    // ===== P0-3: 三分法 + 视线留白 + 负空间 =====

    @Test
    fun thirdsLeftGazeRight_readyNoDirectionHint() {
        val frame = engine.build(
            sceneType = SceneType.PORTRAIT,
            subjectBox = NormalizedRect(0.08f, 0.10f, 0.58f, 0.82f), // centerX≈0.33, area≈0.36（不触发zoom）
            horizonTiltDeg = 0f,
            facePitchDeg = null,
            brightnessState = BrightnessState.BALANCED,
            lightDirection = LightDirection.UNKNOWN,
            faceCount = 1,
            confidence = 0.9f,
            smilingProbability = 0.85f,
            leftEyeOpenProb = 0.55f,
            rightEyeOpenProb = 0.6f,
            headEulerY = -10f, // Y<-5 → LOOK_RIGHT
        )
        assertThat(frame.captureReadiness).isEqualTo(CaptureReadiness.READY)
        assertThat(frame.cameraAction.directionHint).isEqualTo(DirectionHint.NONE)
    }

    @Test
    fun thirdsLeftGazeLeft_recommendsTurnToDepth() {
        val frame = engine.build(
            sceneType = SceneType.PORTRAIT,
            subjectBox = NormalizedRect(0.08f, 0.10f, 0.58f, 0.82f), // centerX≈0.33, area≈0.36（不触发zoom）
            horizonTiltDeg = 0f,
            facePitchDeg = null,
            brightnessState = BrightnessState.BALANCED,
            lightDirection = LightDirection.UNKNOWN,
            faceCount = 1,
            confidence = 0.9f,
            smilingProbability = null,
            leftEyeOpenProb = null,
            rightEyeOpenProb = null,
            headEulerY = 10f, // Y>5 → LOOK_LEFT（看墙）
        )
        assertThat(frame.recommendationText).contains("画面深处")
    }

    @Test
    fun centeredSubject_isValidComposition() {
        val frame = engine.build(
            sceneType = SceneType.PORTRAIT,
            subjectBox = NormalizedRect(0.4f, 0.12f, 0.6f, 0.62f), // centerX=0.5
            horizonTiltDeg = 0f,
            facePitchDeg = null,
            brightnessState = BrightnessState.BALANCED,
            lightDirection = LightDirection.UNKNOWN,
            faceCount = 1,
            confidence = 0.9f,
            smilingProbability = 0.85f,
            leftEyeOpenProb = 0.55f,
            rightEyeOpenProb = 0.6f,
            headEulerY = 0f, // FORWARD
        )
        // 正中 → centerScore=0.8，仍可达 READY
        assertThat(frame.captureReadiness).isEqualTo(CaptureReadiness.READY)
    }

    @Test
    fun negativeSpaceComposition_doesNotTriggerRetry() {
        val scorer = PhotoScorer()
        val frame = GuidanceFrame(
            sceneType = SceneType.PORTRAIT,
            subjectBox = NormalizedRect(0.7f, 0.4f, 0.76f, 0.52f), // area≈0.0072<0.08，非贴边，centerX≈0.73
            horizonTiltDeg = 0f,
            facePitchDeg = null,
            smilingProbability = null,
            leftEyeOpenProb = null,
            rightEyeOpenProb = null,
            headEulerY = 10f, // Y>5 → LOOK_LEFT，centerX>0.5 需朝左看才面向留白
            brightnessState = BrightnessState.BALANCED,
            faceCount = 1,
            confidence = 0.9f,
        )
        val score = scorer.score(frame)
        assertThat(score.strengths).contains("负空间构图，意境感强")
        // 负空间分支不应因主体太小触发重拍
        assertThat(score.retryReasons).doesNotContain(RetryReason.SUBJECT_TOO_SMALL)
    }
}

