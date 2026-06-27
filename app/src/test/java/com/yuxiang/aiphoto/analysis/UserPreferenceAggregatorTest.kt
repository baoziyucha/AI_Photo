package com.yuxiang.aiphoto.analysis

import com.google.common.truth.Truth.assertThat
import com.yuxiang.aiphoto.model.BrightnessState
import com.yuxiang.aiphoto.model.GuidanceCategory
import com.yuxiang.aiphoto.model.GuidanceFrame
import com.yuxiang.aiphoto.model.GuidanceIssue
import com.yuxiang.aiphoto.model.IssueSeverity
import com.yuxiang.aiphoto.model.LightDirection
import com.yuxiang.aiphoto.model.NormalizedRect
import com.yuxiang.aiphoto.model.PhotoScore
import com.yuxiang.aiphoto.model.SceneType
import com.yuxiang.aiphoto.model.UserPreference
import org.junit.Test

class UserPreferenceAggregatorTest {

    private val aggregator = UserPreferenceAggregator(windowSize = 3)

    @Test
    fun firstHighScore initializesBestYawAndSmile() {
        val score = highScorePhoto()
        val result = aggregator.aggregate(UserPreference(), score, frame(yaw = -8f, smile = 0.85f))

        assertThat(result.totalCaptures).isEqualTo(1)
        assertThat(result.highScoreCaptures).isEqualTo(1)
        assertThat(result.bestHeadEulerY).isEqualTo(-8f)
        assertThat(result.bestSmilingProbability).isEqualTo(0.85f)
    }

    @Test
    fun lowScorePhoto_doesNotUpdateBestYawAndSmile() {
        // 第一张高分建立基线
        val first = aggregator.aggregate(
            UserPreference(),
            highScorePhoto(),
            frame(yaw = -5f, smile = 0.8f),
        )
        // 第二张低分不应更新 bestYaw/bestSmile
        val second = aggregator.aggregate(
            first,
            lowScorePhoto(),
            frame(yaw = 20f, smile = 0.1f),
        )

        assertThat(second.totalCaptures).isEqualTo(2)
        assertThat(second.highScoreCaptures).isEqualTo(1)
        // bestYaw/bestSmile 保持第一张的值
        assertThat(second.bestHeadEulerY).isEqualTo(-5f)
        assertThat(second.bestSmilingProbability).isEqualTo(0.8f)
    }

    @Test
    fun ewma_blendsNewSampleIntoBestValue() {
        // 第一张：yaw=-5
        val first = aggregator.aggregate(
            UserPreference(),
            highScorePhoto(),
            frame(yaw = -5f, smile = 0.8f),
        )
        // 第二张：yaw=-8，EWMA(windowSize=3, alpha=1/3) → -5*(2/3) + -8*(1/3) = -6
        val second = aggregator.aggregate(
            first,
            highScorePhoto(),
            frame(yaw = -8f, smile = 0.9f),
        )

        assertThat(second.bestHeadEulerY).isWithin(0.01f).of(-6f)
        assertThat(second.bestSmilingProbability).isWithin(0.001f).of(0.8333f)
    }

    @Test
    fun commonIssues_accumulatesAndRanksByFrequency() {
        val issueA = issue("light.backlit")
        val issueB = issue("composition.off_center")
        val issueC = issue("pose.shoulder_tilted")

        // 第一张：A + B
        val first = aggregator.aggregate(
            UserPreference(),
            PhotoScore(score = 85, baseScore = 85, strengths = emptyList(),
                issues = listOf(issueA, issueB), retryReasons = emptyList(), shouldRetry = false),
            frame(),
        )
        // 第二张：A + C（A 出现两次，应排第一）
        val second = aggregator.aggregate(
            first,
            PhotoScore(score = 82, baseScore = 82, strengths = emptyList(),
                issues = listOf(issueA, issueC), retryReasons = emptyList(), shouldRetry = false),
            frame(),
        )

        assertThat(second.commonIssueIds).hasSize(3)
        // A 出现两次（每次计 2 票 = 4 票），B/C 各一次（2 票），但 A 频次最高
        assertThat(second.commonIssueIds.first()).isEqualTo("light.backlit")
    }

    @Test
    fun commonIssues_truncatesToTop3() {
        // 4 个不同 issue，应只保留 top 3
        val issues = listOf(
            issue("issue.a"), issue("issue.b"), issue("issue.c"), issue("issue.d"),
        )
        val result = aggregator.aggregate(
            UserPreference(),
            PhotoScore(score = 80, baseScore = 80, strengths = emptyList(),
                issues = issues, retryReasons = emptyList(), shouldRetry = false),
            frame(),
        )

        assertThat(result.commonIssueIds).hasSize(3)
    }

    @Test
    fun nullFrame_keepsBestValuesUnchanged() {
        val first = aggregator.aggregate(
            UserPreference(),
            highScorePhoto(),
            frame(yaw = -5f, smile = 0.8f),
        )
        // frame=null 时不应更新 bestYaw/bestSmile
        val second = aggregator.aggregate(
            first,
            highScorePhoto(),
            null,
        )

        assertThat(second.bestHeadEulerY).isEqualTo(-5f)
        assertThat(second.bestSmilingProbability).isEqualTo(0.8f)
        assertThat(second.totalCaptures).isEqualTo(2)
        assertThat(second.highScoreCaptures).isEqualTo(2)
    }

    @Test
    fun emptyIssuesHistory_preservedWhenNoNewIssues() {
        val initial = UserPreference(commonIssueIds = listOf("old.issue"))
        val result = aggregator.aggregate(
            initial,
            PhotoScore(score = 80, baseScore = 80, strengths = emptyList(),
                issues = emptyList(), retryReasons = emptyList(), shouldRetry = false),
            frame(),
        )

        // 没有 new issues，历史应保留
        assertThat(result.commonIssueIds).containsExactly("old.issue")
    }

    // ===== Helpers =====

    private fun highScorePhoto(): PhotoScore =
        PhotoScore(
            score = 88,
            baseScore = 88,
            strengths = emptyList(),
            issues = emptyList(),
            retryReasons = emptyList(),
            shouldRetry = false,
        )

    private fun lowScorePhoto(): PhotoScore =
        PhotoScore(
            score = 50,
            baseScore = 50,
            strengths = emptyList(),
            issues = emptyList(),
            retryReasons = emptyList(),
            shouldRetry = true,
        )

    private fun frame(yaw: Float? = null, smile: Float? = null): GuidanceFrame = GuidanceFrame(
        sceneType = SceneType.PORTRAIT,
        subjectBox = NormalizedRect(0.3f, 0.1f, 0.7f, 0.8f),
        horizonTiltDeg = 0f,
        smilingProbability = smile,
        headEulerY = yaw,
        brightnessState = BrightnessState.BALANCED,
        lightDirection = LightDirection.UNKNOWN,
        faceCount = 1,
        confidence = 0.9f,
    )

    private fun issue(id: String): GuidanceIssue = GuidanceIssue(
        id = id,
        category = GuidanceCategory.COMPOSITION,
        severity = IssueSeverity.LOW,
        confidence = 0.7f,
        priority = 50,
        message = "",
    )
}
