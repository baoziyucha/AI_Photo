package com.yuxiang.aiphoto.analysis

import com.google.common.truth.Truth.assertThat
import com.yuxiang.aiphoto.model.BrightnessState
import com.yuxiang.aiphoto.model.NormalizedRect
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
            brightnessState = BrightnessState.BALANCED,
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
            brightnessState = BrightnessState.BACKLIT,
            faceCount = 1,
            confidence = 0.92f,
        )

        assertThat(frame.recommendationText).contains("更亮")
        assertThat(frame.cameraAction.exposureCompensationDelta).isEqualTo(1)
    }

    @Test
    fun genericSubjectOnEdge_recommendsMoveAwayFromBorder() {
        val frame = engine.build(
            sceneType = SceneType.DAILY_GENERIC,
            subjectBox = NormalizedRect(0.01f, 0.16f, 0.45f, 0.88f),
            horizonTiltDeg = 0.4f,
            brightnessState = BrightnessState.BALANCED,
            faceCount = 0,
            confidence = 0.56f,
        )

        assertThat(frame.recommendationText).contains("边缘")
    }
}

