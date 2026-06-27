package com.yuxiang.aiphoto.analysis

import com.google.common.truth.Truth.assertThat
import com.yuxiang.aiphoto.model.GuidanceCategory
import com.yuxiang.aiphoto.model.GuidanceIssue
import com.yuxiang.aiphoto.model.IssueSeverity
import com.yuxiang.aiphoto.model.PromptLexicon
import com.yuxiang.aiphoto.model.SpeechTone
import com.yuxiang.aiphoto.model.StylePreset
import com.yuxiang.aiphoto.model.StyleProfile
import com.yuxiang.aiphoto.model.SubjectAction
import com.yuxiang.aiphoto.model.UserPreference
import org.junit.Test

/**
 * P2-7 验证 userBias（个性化记忆）如何影响 RuleVariantResolver.resolve()。
 */
class UserBiasResolutionTest {

    private val baseProfile = StyleProfile(
        presetId = StylePreset.FRESH,
        weights = emptyMap(),
        allowedActions = SubjectAction.values().toSet(),
        promptLexicon = PromptLexicon(
            encouragement = listOf("很好"),
            correction = listOf("调整"),
            countdown = listOf("3", "2", "1"),
        ),
        speechTone = SpeechTone.GENTLE,
        userBias = UserPreference(),
    )

    @Test
    fun commonIssue_priorityBoosted() {
        val issue = GuidanceIssue(
            id = "composition.off_center",
            category = GuidanceCategory.COMPOSITION,
            severity = IssueSeverity.MEDIUM,
            confidence = 0.8f,
            priority = 13,
            message = "主体偏了",
        )
        val biasedProfile = baseProfile.copy(
            userBias = UserPreference(commonIssueIds = listOf("composition.off_center")),
        )

        val resolved = RuleVariantResolver.resolve(issue, biasedProfile)
        // priority 13 + boost 2 = 15
        assertThat(resolved.priority).isEqualTo(15)
    }

    @Test
    fun nonCommonIssue_priorityUnchanged() {
        val issue = GuidanceIssue(
            id = "light.backlit",
            category = GuidanceCategory.LIGHT,
            severity = IssueSeverity.HIGH,
            confidence = 0.9f,
            priority = 50,
            message = "逆光",
        )
        val biasedProfile = baseProfile.copy(
            userBias = UserPreference(commonIssueIds = listOf("composition.off_center")),
        )

        val resolved = RuleVariantResolver.resolve(issue, biasedProfile)
        // 不在 commonIssueIds 中，priority 不变（仍受风格变体影响）
        assertThat(resolved.priority).isEqualTo(issue.priority)
    }

    @Test
    fun emptyCommonIssueIds_noBoostApplied() {
        val issue = GuidanceIssue(
            id = "composition.off_center",
            category = GuidanceCategory.COMPOSITION,
            severity = IssueSeverity.MEDIUM,
            confidence = 0.8f,
            priority = 13,
            message = "主体偏了",
        )
        // userBias 为空（默认）
        val resolved = RuleVariantResolver.resolve(issue, baseProfile)
        assertThat(resolved.priority).isEqualTo(issue.priority)
    }

    @Test
    fun safetyIssue_notAffectedByBias() {
        val issue = GuidanceIssue(
            id = "safety.backlit",
            category = GuidanceCategory.SAFETY,
            severity = IssueSeverity.CRITICAL,
            confidence = 0.95f,
            priority = 1,
            message = "严重逆光",
        )
        val biasedProfile = baseProfile.copy(
            userBias = UserPreference(commonIssueIds = listOf("safety.backlit")),
        )

        val resolved = RuleVariantResolver.resolve(issue, biasedProfile)
        // SAFETY 类硬规则不可被风格/偏好覆盖
        assertThat(resolved.priority).isEqualTo(1)
        assertThat(resolved.severity).isEqualTo(IssueSeverity.CRITICAL)
    }

    @Test
    fun primaryIssueSelection_prefersCommonIssueWhenPrioritiesClose() {
        val commonIssue = GuidanceIssue(
            id = "composition.off_center",
            category = GuidanceCategory.COMPOSITION,
            severity = IssueSeverity.MEDIUM,
            confidence = 0.8f,
            priority = 13,
            message = "主体偏了",
        )
        val otherIssue = GuidanceIssue(
            id = "background.clutter",
            category = GuidanceCategory.BACKGROUND,
            severity = IssueSeverity.LOW,
            confidence = 0.6f,
            priority = 14,  // 略高
            message = "背景乱",
        )
        val biasedProfile = baseProfile.copy(
            userBias = UserPreference(commonIssueIds = listOf("composition.off_center")),
        )

        // 无 bias：otherIssue priority=14 > commonIssue priority=13 → 选 otherIssue
        val primaryNoBias = RuleVariantResolver.selectPrimaryIssue(
            listOf(commonIssue, otherIssue), baseProfile,
        )
        assertThat(primaryNoBias?.id).isEqualTo("background.clutter")

        // 有 bias：commonIssue priority=13+2=15 > otherIssue priority=14 → 选 commonIssue
        val primaryWithBias = RuleVariantResolver.selectPrimaryIssue(
            listOf(commonIssue, otherIssue), biasedProfile,
        )
        assertThat(primaryWithBias?.id).isEqualTo("composition.off_center")
    }
}
