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

    @Test
    fun keepsLastStableGuidanceDuringAdjustment() {
        // 用户按提示移动手机时，指纹会变化导致不稳定，但指导不应消失。
        val stabilizer = GuidanceStabilizer(stableFrames = 3)
        val stable = GuidanceFrame(
            sceneType = SceneType.PORTRAIT,
            subjectBox = NormalizedRect(0.30f, 0.10f, 0.70f, 0.80f),
            brightnessState = BrightnessState.BALANCED,
            recommendationText = "镜头向左一点",
        )

        // 先达到稳定（3 帧相同）
        stabilizer.stabilize(stable)
        stabilizer.stabilize(stable)
        val stableFrame = stabilizer.stabilize(stable)
        assertThat(stableFrame.isStable).isTrue()
        assertThat(stableFrame.recommendationText).isEqualTo("镜头向左一点")

        // 用户开始移动，主体位置变化足够大，跨过指纹分桶边界
        val moving = stable.copy(subjectBox = NormalizedRect(0.42f, 0.10f, 0.82f, 0.80f))
        val duringAdjustment = stabilizer.stabilize(moving)

        // 指导文案应保留，不应清空
        assertThat(duringAdjustment.isStable).isFalse()
        assertThat(duringAdjustment.recommendationText).isEqualTo("镜头向左一点")
        // 主体框应跟随当前帧
        assertThat(duringAdjustment.subjectBox).isEqualTo(moving.subjectBox)
    }
}

