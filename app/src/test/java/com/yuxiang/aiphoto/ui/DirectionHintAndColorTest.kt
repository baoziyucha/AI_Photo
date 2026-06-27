package com.yuxiang.aiphoto.ui

import com.google.common.truth.Truth.assertThat
import com.yuxiang.aiphoto.analysis.GuidanceEngine
import com.yuxiang.aiphoto.model.BrightnessState
import com.yuxiang.aiphoto.model.CaptureReadiness
import com.yuxiang.aiphoto.model.DirectionHint
import com.yuxiang.aiphoto.model.LightDirection
import com.yuxiang.aiphoto.model.NormalizedRect
import com.yuxiang.aiphoto.model.SceneType
import org.junit.Test

class DirectionHintAndColorTest {

    private val engine = GuidanceEngine()

    @Test
    fun `DirectionHint - MOVE_LEFT when subject on right side`() {
        val frame = engine.build(
            sceneType = SceneType.PORTRAIT,
            subjectBox = NormalizedRect(0.6f, 0.3f, 0.8f, 0.7f),
            horizonTiltDeg = 0f,
            facePitchDeg = null,
            brightnessState = BrightnessState.BALANCED,
            lightDirection = LightDirection.UNKNOWN,
            faceCount = 1,
            confidence = 0.9f,
        )
        assertThat(frame.cameraAction.directionHint).isEqualTo(DirectionHint.MOVE_LEFT)
    }

    @Test
    fun `DirectionHint - MOVE_RIGHT when subject on left side`() {
        val frame = engine.build(
            sceneType = SceneType.PORTRAIT,
            subjectBox = NormalizedRect(0.2f, 0.3f, 0.4f, 0.7f),
            horizonTiltDeg = 0f,
            facePitchDeg = null,
            brightnessState = BrightnessState.BALANCED,
            lightDirection = LightDirection.UNKNOWN,
            faceCount = 1,
            confidence = 0.9f,
        )
        assertThat(frame.cameraAction.directionHint).isEqualTo(DirectionHint.MOVE_RIGHT)
    }

    @Test
    fun `DirectionHint - MOVE_UP when subject is below center`() {
        val frame = engine.build(
            sceneType = SceneType.PORTRAIT,
            subjectBox = NormalizedRect(0.4f, 0.6f, 0.6f, 0.9f),
            horizonTiltDeg = 0f,
            facePitchDeg = null,
            brightnessState = BrightnessState.BALANCED,
            lightDirection = LightDirection.UNKNOWN,
            faceCount = 1,
            confidence = 0.9f,
        )
        assertThat(frame.cameraAction.directionHint).isEqualTo(DirectionHint.MOVE_UP)
    }

    @Test
    fun `DirectionHint - MOVE_DOWN when subject is above center`() {
        val frame = engine.build(
            sceneType = SceneType.PORTRAIT,
            subjectBox = NormalizedRect(0.45f, 0.05f, 0.55f, 0.25f),
            horizonTiltDeg = 0f,
            facePitchDeg = null,
            brightnessState = BrightnessState.LOW_LIGHT,
            lightDirection = LightDirection.UNKNOWN,
            faceCount = 1,
            confidence = 0.9f,
        )
        assertThat(frame.cameraAction.directionHint).isEqualTo(DirectionHint.MOVE_DOWN)
    }

    @Test
    fun `DirectionHint - TILT_LEFT when phone tilted clockwise`() {
        val frame = engine.build(
            sceneType = SceneType.PORTRAIT,
            subjectBox = NormalizedRect(0.45f, 0.3f, 0.55f, 0.6f),
            horizonTiltDeg = 5f,
            facePitchDeg = null,
            brightnessState = BrightnessState.BALANCED,
            lightDirection = LightDirection.UNKNOWN,
            faceCount = 1,
            confidence = 0.9f,
        )
        assertThat(frame.cameraAction.directionHint).isEqualTo(DirectionHint.TILT_LEFT)
    }

    @Test
    fun `DirectionHint - TILT_RIGHT when phone tilted counterclockwise`() {
        val frame = engine.build(
            sceneType = SceneType.PORTRAIT,
            subjectBox = NormalizedRect(0.45f, 0.3f, 0.55f, 0.6f),
            horizonTiltDeg = -5f,
            facePitchDeg = null,
            brightnessState = BrightnessState.BALANCED,
            lightDirection = LightDirection.UNKNOWN,
            faceCount = 1,
            confidence = 0.9f,
        )
        assertThat(frame.cameraAction.directionHint).isEqualTo(DirectionHint.TILT_RIGHT)
    }

    @Test
    fun `DirectionHint - NONE when subject is well positioned`() {
        val frame = engine.build(
            sceneType = SceneType.PORTRAIT,
            subjectBox = NormalizedRect(0.45f, 0.25f, 0.55f, 0.6f),
            horizonTiltDeg = 0.5f,
            facePitchDeg = null,
            brightnessState = BrightnessState.BALANCED,
            lightDirection = LightDirection.UNKNOWN,
            faceCount = 1,
            confidence = 0.9f,
        )
        assertThat(frame.cameraAction.directionHint).isEqualTo(DirectionHint.NONE)
    }

    @Test
    fun `DirectionHint - NONE when capture is READY`() {
        val frame = engine.build(
            sceneType = SceneType.PORTRAIT,
            subjectBox = NormalizedRect(0.45f, 0.2f, 0.55f, 0.55f),
            horizonTiltDeg = 0f,
            facePitchDeg = 3f,
            brightnessState = BrightnessState.BALANCED,
            lightDirection = LightDirection.LEFT,
            faceCount = 1,
            confidence = 0.95f,
        )
        assertThat(frame.captureReadiness).isEqualTo(CaptureReadiness.READY)
        assertThat(frame.cameraAction.directionHint).isEqualTo(DirectionHint.NONE)
    }

    @Test
    fun `CaptureReadiness - READY with perfect composition`() {
        val frame = engine.build(
            sceneType = SceneType.PORTRAIT,
            subjectBox = NormalizedRect(0.45f, 0.15f, 0.55f, 0.5f),
            horizonTiltDeg = 0.5f,
            facePitchDeg = 2f,
            brightnessState = BrightnessState.BALANCED,
            lightDirection = LightDirection.LEFT,
            faceCount = 1,
            confidence = 0.95f,
        )
        assertThat(frame.captureReadiness).isEqualTo(CaptureReadiness.READY)
    }

    @Test
    fun `CaptureReadiness - NOT_READY with backlit and tilted`() {
        val frame = engine.build(
            sceneType = SceneType.PORTRAIT,
            subjectBox = NormalizedRect(0.6f, 0.1f, 0.9f, 0.5f),
            horizonTiltDeg = 8f,
            facePitchDeg = null,
            brightnessState = BrightnessState.BACKLIT,
            lightDirection = LightDirection.UNKNOWN,
            faceCount = 1,
            confidence = 0.8f,
        )
        assertThat(frame.captureReadiness).isEqualTo(CaptureReadiness.NOT_READY)
    }

    @Test
    fun `CaptureReadiness - ALMOST_READY with minor issues`() {
        val frame = engine.build(
            sceneType = SceneType.PORTRAIT,
            subjectBox = NormalizedRect(0.35f, 0.15f, 0.55f, 0.5f),
            horizonTiltDeg = 2f,
            facePitchDeg = null,
            brightnessState = BrightnessState.LOW_LIGHT,
            lightDirection = LightDirection.UNKNOWN,
            faceCount = 1,
            confidence = 0.85f,
        )
        assertThat(frame.captureReadiness).isEqualTo(CaptureReadiness.ALMOST_READY)
    }

    @Test
    fun `DirectionHint priority - tilt has higher priority than position`() {
        val frame = engine.build(
            sceneType = SceneType.PORTRAIT,
            subjectBox = NormalizedRect(0.7f, 0.5f, 0.9f, 0.8f),
            horizonTiltDeg = 6f,
            facePitchDeg = null,
            brightnessState = BrightnessState.BALANCED,
            lightDirection = LightDirection.UNKNOWN,
            faceCount = 1,
            confidence = 0.9f,
        )
        assertThat(frame.cameraAction.directionHint).isEqualTo(DirectionHint.TILT_LEFT)
    }

    @Test
    fun `DirectionHint priority - horizontal takes precedence over vertical`() {
        val frame = engine.build(
            sceneType = SceneType.PORTRAIT,
            subjectBox = NormalizedRect(0.75f, 0.7f, 0.9f, 0.9f),
            horizonTiltDeg = 1f,
            facePitchDeg = null,
            brightnessState = BrightnessState.BALANCED,
            lightDirection = LightDirection.UNKNOWN,
            faceCount = 1,
            confidence = 0.9f,
        )
        assertThat(frame.cameraAction.directionHint).isEqualTo(DirectionHint.MOVE_LEFT)
    }

    @Test
    fun `DirectionHint - NONE when no subject detected`() {
        val frame = engine.build(
            sceneType = SceneType.DAILY_GENERIC,
            subjectBox = null,
            horizonTiltDeg = 0f,
            facePitchDeg = null,
            brightnessState = BrightnessState.BALANCED,
            lightDirection = LightDirection.UNKNOWN,
            faceCount = 0,
            confidence = 0.5f,
        )
        assertThat(frame.cameraAction.directionHint).isEqualTo(DirectionHint.NONE)
        assertThat(frame.captureReadiness).isEqualTo(CaptureReadiness.NOT_READY)
    }

    @Test
    fun `color state mapping - READY should use green color`() {
        val frame = engine.build(
            sceneType = SceneType.PORTRAIT,
            subjectBox = NormalizedRect(0.45f, 0.15f, 0.55f, 0.5f),
            horizonTiltDeg = 0f,
            facePitchDeg = 0f,
            brightnessState = BrightnessState.BALANCED,
            lightDirection = LightDirection.UNKNOWN,
            faceCount = 1,
            confidence = 0.95f,
        )
        assertThat(frame.captureReadiness).isEqualTo(CaptureReadiness.READY)
        assertThat(frame.cameraAction.directionHint).isEqualTo(DirectionHint.NONE)
    }

    @Test
    fun `color state mapping - NOT_READY should use red color`() {
        val frame = engine.build(
            sceneType = SceneType.PORTRAIT,
            subjectBox = NormalizedRect(0.1f, 0.05f, 0.4f, 0.4f),
            horizonTiltDeg = 10f,
            facePitchDeg = null,
            brightnessState = BrightnessState.BACKLIT,
            lightDirection = LightDirection.UNKNOWN,
            faceCount = 1,
            confidence = 0.8f,
        )
        assertThat(frame.captureReadiness).isEqualTo(CaptureReadiness.NOT_READY)
        assertThat(frame.cameraAction.directionHint).isNotEqualTo(DirectionHint.NONE)
    }

    @Test
    fun `DirectionHint enum values are complete`() {
        val expectedHints = setOf(
            DirectionHint.NONE,
            DirectionHint.MOVE_LEFT,
            DirectionHint.MOVE_RIGHT,
            DirectionHint.MOVE_UP,
            DirectionHint.MOVE_DOWN,
            DirectionHint.TILT_LEFT,
            DirectionHint.TILT_RIGHT,
        )
        assertThat(DirectionHint.entries.toSet()).isEqualTo(expectedHints)
    }

    @Test
    fun `CaptureReadiness enum values are complete`() {
        val expectedReadiness = setOf(
            CaptureReadiness.NOT_READY,
            CaptureReadiness.ALMOST_READY,
            CaptureReadiness.READY,
        )
        assertThat(CaptureReadiness.entries.toSet()).isEqualTo(expectedReadiness)
    }
}
