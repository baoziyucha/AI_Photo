package com.yuxiang.aiphoto.analysis

import com.google.common.truth.Truth.assertThat
import com.yuxiang.aiphoto.model.BrightnessState
import com.yuxiang.aiphoto.model.GuidanceFrame
import com.yuxiang.aiphoto.model.NormalizedRect
import com.yuxiang.aiphoto.model.SceneType
import org.junit.Test

class GuidanceStabilizerTest {
    @Test
    fun suppressesRecommendationUntilThirdStableFrame() {
        val stabilizer = GuidanceStabilizer(stableFrames = 3)
        val raw = GuidanceFrame(
            sceneType = SceneType.PORTRAIT,
            subjectBox = NormalizedRect(0.2f, 0.1f, 0.7f, 0.8f),
            brightnessState = BrightnessState.BALANCED,
            recommendationText = "镜头向左一点",
        )

        val first = stabilizer.stabilize(raw)
        val second = stabilizer.stabilize(raw)
        val third = stabilizer.stabilize(raw)

        assertThat(first.recommendationText).isEmpty()
        assertThat(second.recommendationText).isEmpty()
        assertThat(third.recommendationText).isEqualTo("镜头向左一点")
        assertThat(third.isStable).isTrue()
    }

    @Test
    fun resetClearsPreviousStability() {
        val stabilizer = GuidanceStabilizer(stableFrames = 2)
        val raw = GuidanceFrame(
            sceneType = SceneType.DAILY_GENERIC,
            subjectBox = NormalizedRect(0.28f, 0.22f, 0.76f, 0.84f),
            brightnessState = BrightnessState.BALANCED,
            recommendationText = "扶正画面",
        )

        stabilizer.stabilize(raw)
        stabilizer.reset()
        val afterReset = stabilizer.stabilize(raw)

        assertThat(afterReset.recommendationText).isEmpty()
        assertThat(afterReset.isStable).isFalse()
    }
}

