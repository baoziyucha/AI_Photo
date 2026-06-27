package com.yuxiang.aiphoto.analysis

import com.yuxiang.aiphoto.model.GuidanceCategory
import com.yuxiang.aiphoto.model.GuidanceIssue
import com.yuxiang.aiphoto.model.IssueSeverity
import com.yuxiang.aiphoto.model.PromptLexicon
import com.yuxiang.aiphoto.model.RuleVariant
import com.yuxiang.aiphoto.model.SpeechTone
import com.yuxiang.aiphoto.model.StylePreset
import com.yuxiang.aiphoto.model.StyleProfile
import com.yuxiang.aiphoto.model.SubjectAction
import com.yuxiang.aiphoto.model.UserPreference

/**
 * 风格变体解析器：根据 StyleProfile 调整 GuidanceIssue 的权重、动作与话术。
 * 硬规则（SAFETY 类）不可被风格覆盖；风格偏好只影响 LIGHT/COMPOSITION/EXPRESSION/BACKGROUND 等。
 */
object RuleVariantResolver {

    /** P2-7 常见问题 priority 提升量：让用户高频遇到的问题优先被解决。 */
    private const val COMMON_ISSUE_PRIORITY_BOOST = 2

    /**
     * 同一规则在不同风格下的变体表。
     * 覆盖文档 7.6 节核心规则 × 3 套风格（清透/证件、电影/情绪、街拍/纪实）。
     */
    private val variantTable: List<RuleVariant> = buildList {
        // light.face_dark / light.backlit：脸部偏暗
        addAll(variants("light.backlit",
            cleanCert(message = "逆光，转向光源或打开补光。", action = SubjectAction.FACE_TURN_TO_LIGHT, blocksReady = true),
            cinematic(message = "可以保留低调光，但脸再转向光一点，让眼睛里有光。", action = SubjectAction.FACE_TURN_TO_LIGHT, blocksReady = false, severity = IssueSeverity.LOW),
            street(message = "走到有方向性的自然光边缘，让脸上有光。", action = SubjectAction.FACE_TURN_TO_LIGHT, blocksReady = false, severity = IssueSeverity.LOW),
        ))
        // composition.off_center：主体不居中
        addAll(variants("composition.off_center",
            cleanCert(message = "主体偏离中心，回到稳定位置。", action = SubjectAction.RECOMPOSE_MOVE),
            cinematic(message = "可以保留偏边构图，增强孤独感。", action = null, priorityDelta = +20),
            street(message = "让环境多说一点，不急着居中。", action = null, priorityDelta = +20),
        ))
        // expression.no_smile：没笑
        addAll(variants("expression.awkward",
            cleanCert(message = "嘴角放松，给一个轻微友好的表情。", action = SubjectAction.NATURAL_SMILE_HOLD),
            cinematic(message = "可以保留冷静或情绪感，不用勉强笑。", action = null, priorityDelta = +30, severity = IssueSeverity.INFO),
            street(message = "保持酷一点，不用理镜头。", action = null, priorityDelta = +30, severity = IssueSeverity.INFO),
        ))
        // background.clutter：背景杂乱（预留，P2 实现）
        addAll(variants("background.clutter",
            cleanCert(message = "背景有点抢注意，换个角度更干净。", action = SubjectAction.RECOMPOSE_MOVE),
            cinematic(message = "若氛围成立，背景可以保留。", action = null, priorityDelta = +20, severity = IssueSeverity.LOW),
            street(message = "环境是故事的一部分，只避开抢脸元素。", action = null, priorityDelta = +30, severity = IssueSeverity.INFO),
        ))
        // background.hotspot：背景高光抢戏（P2）
        addAll(variants("background.hotspot",
            cleanCert(message = "背景有高光抢戏，换个角度避开。", action = SubjectAction.RECOMPOSE_MOVE),
            cinematic(message = "高光可以保留作为光源氛围，但别抢脸。", action = null, priorityDelta = +20, severity = IssueSeverity.LOW),
            street(message = "光斑有街头感，避开正对人脸方向即可。", action = null, priorityDelta = +30, severity = IssueSeverity.INFO),
        ))
        // light.no_catchlight：无眼神光（P1）
        addAll(variants("light.no_catchlight",
            cleanCert(message = "脸转向光一点，让眼睛有光。", action = SubjectAction.FACE_TURN_TO_LIGHT),
            cinematic(message = "可以保留低调光，但让眼睛有一点光。", action = SubjectAction.FACE_TURN_TO_LIGHT, priorityDelta = +10, severity = IssueSeverity.INFO),
            street(message = "走到光边缘，让眼睛亮一点。", action = SubjectAction.FACE_TURN_TO_LIGHT, priorityDelta = +10, severity = IssueSeverity.LOW),
        ))
        // P2-5 pose.joint_cropped：关节出框
        addAll(variants("pose.joint_cropped",
            cleanCert(message = "肩/肘出框了，挪一步或拉远一点。", action = SubjectAction.RECOMPOSE_MOVE),
            cinematic(message = "可以保留紧凑构图，但别让关节硬切。", action = null, priorityDelta = +10, severity = IssueSeverity.LOW),
            street(message = "环境比边界重要，但如果肩出框可以拉远。", action = null, priorityDelta = +20, severity = IssueSeverity.INFO),
        ))
        // P2-5 pose.hand_covering_face：手遮脸
        addAll(variants("pose.hand_covering_face",
            cleanCert(message = "手遮脸了，放下或挪开。", action = null),
            cinematic(message = "除非是情绪构图，否则手别遮眼。", action = null, priorityDelta = +10, severity = IssueSeverity.LOW),
            street(message = "动作可以自然，但别挡住眼神。", action = null, priorityDelta = +20, severity = IssueSeverity.INFO),
        ))
    }

    /** 根据 StyleProfile 解析单个 issue 的变体，返回调整后的 issue。硬规则不可覆盖。 */
    fun resolve(issue: GuidanceIssue, profile: StyleProfile): GuidanceIssue {
        // 硬规则（SAFETY 类）不可被风格覆盖
        if (issue.category == GuidanceCategory.SAFETY) return issue

        val variant = variantTable.firstOrNull {
            it.issueId == issue.id && it.preset == profile.presetId
        }

        // 风格变体解析（若无匹配变体则保留原 issue）
        val styleResolved = if (variant != null) {
            issue.copy(
                severity = variant.severityOverride ?: issue.severity,
                priority = (issue.priority + variant.priorityDelta).coerceAtLeast(0),
                message = variant.messageOverride ?: issue.message,
                ttsMessage = variant.ttsOverride ?: issue.ttsMessage,
                action = variant.actionOverride ?: issue.action,
                blocksReady = variant.blocksReadyOverride ?: issue.blocksReady,
                scoreDelta = variant.scoreDeltaOverride ?: issue.scoreDelta,
            )
        } else {
            issue
        }

        // P2-7 个性化记忆应用：常见问题轻微提升 priority（+2），让用户高频遇到的问题优先被解决
        // 软规则，不覆盖 severity/blocksReady，不影响硬规则判断
        val bias = profile.userBias
        return if (bias.commonIssueIds.isNotEmpty() && styleResolved.id in bias.commonIssueIds) {
            styleResolved.copy(priority = (styleResolved.priority + COMMON_ISSUE_PRIORITY_BOOST).coerceAtLeast(0))
        } else {
            styleResolved
        }
    }

    /**
     * 按 priority → severity → blocksReady 排序，选第一条作为实时主建议。
     * 与 GuidanceEngine.selectPrimaryIssue 一致，但先经过风格变体解析。
     */
    fun selectPrimaryIssue(issues: List<GuidanceIssue>, profile: StyleProfile): GuidanceIssue? {
        if (issues.isEmpty()) return null
        val resolved = issues.map { resolve(it, profile) }
        val severityRank = mapOf(
            IssueSeverity.CRITICAL to 0,
            IssueSeverity.HIGH to 1,
            IssueSeverity.MEDIUM to 2,
            IssueSeverity.LOW to 3,
            IssueSeverity.INFO to 4,
        )
        return resolved.sortedWith(
            compareBy<GuidanceIssue> { it.priority }
                .thenBy { severityRank[it.severity] ?: 5 }
                .thenByDescending { it.blocksReady },
        ).first()
    }

    private fun variants(
        issueId: String,
        cleanCert: VariantSpec,
        cinematic: VariantSpec,
        street: VariantSpec,
    ): List<RuleVariant> = listOf(
        cleanCert.toRuleVariant(issueId, StylePreset.FRESH),
        cleanCert.toRuleVariant(issueId, StylePreset.WORKPLACE),
        cleanCert.toRuleVariant(issueId, StylePreset.ID_PHOTO),
        cinematic.toRuleVariant(issueId, StylePreset.EMOTIONAL),
        cinematic.toRuleVariant(issueId, StylePreset.FILM),
        cinematic.toRuleVariant(issueId, StylePreset.SWEET),
        street.toRuleVariant(issueId, StylePreset.STREET),
        street.toRuleVariant(issueId, StylePreset.COOL),
        street.toRuleVariant(issueId, StylePreset.TRAVEL),
    )

    private data class VariantSpec(
        val message: String,
        val action: SubjectAction?,
        val priorityDelta: Int = 0,
        val severity: IssueSeverity? = null,
        val blocksReady: Boolean? = null,
    )

    private fun VariantSpec.toRuleVariant(issueId: String, preset: StylePreset) = RuleVariant(
        issueId = issueId,
        preset = preset,
        messageOverride = message,
        actionOverride = action,
        priorityDelta = priorityDelta,
        severityOverride = severity,
        blocksReadyOverride = blocksReady,
    )
}

/** 风格预设工厂：根据 StylePreset 生成对应的 StyleProfile。 */
object StyleProfileFactory {

    fun create(preset: StylePreset): StyleProfile = when (preset) {
        StylePreset.FRESH -> StyleProfile(
            presetId = preset,
            weights = defaultWeights(),
            allowedActions = SubjectAction.values().toSet(),
            promptLexicon = PromptLexicon(
                encouragement = listOf("很好，保持", "这个角度不错", "光线很合适"),
                correction = listOf("稍微调整一下", "再来一点", "放松一点"),
                countdown = listOf("3", "2", "1"),
            ),
            speechTone = SpeechTone.GENTLE,
            userBias = UserPreference(),
            speechRate = 0.95f,
        )
        StylePreset.WORKPLACE -> StyleProfile(
            presetId = preset,
            weights = defaultWeights(),
            allowedActions = SubjectAction.values().toSet(),
            promptLexicon = PromptLexicon(
                encouragement = listOf("很稳，保持", "眼神到位", "姿态专业"),
                correction = listOf("下巴微收", "肩放松", "眼神像在做决定"),
                countdown = listOf("3", "2", "1"),
            ),
            speechTone = SpeechTone.DECISIVE,
            userBias = UserPreference(),
            speechRate = 0.95f,
        )
        StylePreset.STREET -> StyleProfile(
            presetId = preset,
            weights = streetWeights(),
            allowedActions = SubjectAction.values().toSet(),
            promptLexicon = PromptLexicon(
                encouragement = listOf("继续走，不用理镜头", "眼神穿过镜头", "很松弛"),
                correction = listOf("继续走，眼睛看前面", "不用笑", "放松"),
                countdown = listOf("3", "2", "1"),
            ),
            speechTone = SpeechTone.CRISP,
            userBias = UserPreference(),
            speechRate = 1.0f,
        )
        StylePreset.EMOTIONAL -> StyleProfile(
            presetId = preset,
            weights = emotionalWeights(),
            allowedActions = SubjectAction.values().toSet(),
            promptLexicon = PromptLexicon(
                encouragement = listOf("把注意力放到自己身上", "像刚想起一件事", "保留这个情绪"),
                correction = listOf("再内收一点", "侧身一点", "不用看镜头"),
                countdown = listOf("3", "2", "1"),
            ),
            speechTone = SpeechTone.INTIMATE,
            userBias = UserPreference(),
            speechRate = 0.85f,
        )
        StylePreset.FILM -> StyleProfile(
            presetId = preset,
            weights = filmWeights(),
            allowedActions = SubjectAction.values().toSet(),
            promptLexicon = PromptLexicon(
                encouragement = listOf("保留这个氛围", "光影很有质感", "像电影画面"),
                correction = listOf("再沉一点", "收一点光", "不用看镜头"),
                countdown = listOf("3", "2", "1"),
            ),
            speechTone = SpeechTone.NARRATIVE,
            userBias = UserPreference(),
            speechRate = 0.85f,
        )
        StylePreset.SWEET -> StyleProfile(
            presetId = preset,
            weights = sweetWeights(),
            allowedActions = SubjectAction.values().toSet(),
            promptLexicon = PromptLexicon(
                encouragement = listOf("笑得很甜，保持", "眼睛弯弯的很好看", "光线很柔和"),
                correction = listOf("再笑一点点", "头轻轻歪一下", "放松肩膀"),
                countdown = listOf("3", "2", "1"),
            ),
            speechTone = SpeechTone.GENTLE,
            userBias = UserPreference(),
            speechRate = 1.0f,
        )
        StylePreset.COOL -> StyleProfile(
            presetId = preset,
            weights = coolWeights(),
            allowedActions = SubjectAction.values().toSet(),
            promptLexicon = PromptLexicon(
                encouragement = listOf("很酷，保持", "眼神有力量", "姿态很利落"),
                correction = listOf("再冷一点", "不用笑", "下巴微抬"),
                countdown = listOf("3", "2", "1"),
            ),
            speechTone = SpeechTone.CRISP,
            userBias = UserPreference(),
            speechRate = 1.0f,
        )
        StylePreset.TRAVEL -> StyleProfile(
            presetId = preset,
            weights = travelWeights(),
            allowedActions = SubjectAction.values().toSet(),
            promptLexicon = PromptLexicon(
                encouragement = listOf("环境和人很搭", "继续走，自然一点", "像在旅行中"),
                correction = listOf("看远处", "身体放松", "不用看镜头"),
                countdown = listOf("3", "2", "1"),
            ),
            speechTone = SpeechTone.NARRATIVE,
            userBias = UserPreference(),
            speechRate = 1.0f,
        )
        StylePreset.ID_PHOTO -> StyleProfile(
            presetId = preset,
            weights = idPhotoWeights(),
            allowedActions = SubjectAction.values().toSet(),
            promptLexicon = PromptLexicon(
                encouragement = listOf("很标准，保持", "姿势端正", "表情自然"),
                correction = listOf("头扶正", "看镜头", "肩放平"),
                countdown = listOf("3", "2", "1"),
            ),
            speechTone = SpeechTone.DECISIVE,
            userBias = UserPreference(),
            speechRate = 0.95f,
        )
    }

    private fun defaultWeights(): Map<GuidanceCategory, Float> = mapOf(
        GuidanceCategory.COMPOSITION to 1.0f,
        GuidanceCategory.LIGHT to 1.0f,
        GuidanceCategory.EXPRESSION to 1.0f,
        GuidanceCategory.POSE to 1.0f,
        GuidanceCategory.BACKGROUND to 1.0f,
    )

    private fun streetWeights(): Map<GuidanceCategory, Float> = mapOf(
        GuidanceCategory.COMPOSITION to 0.7f,
        GuidanceCategory.LIGHT to 0.7f,
        GuidanceCategory.EXPRESSION to 0.5f,
        GuidanceCategory.POSE to 0.8f,
        GuidanceCategory.BACKGROUND to 0.4f,
    )

    private fun emotionalWeights(): Map<GuidanceCategory, Float> = mapOf(
        GuidanceCategory.COMPOSITION to 0.8f,
        GuidanceCategory.LIGHT to 0.6f,
        GuidanceCategory.EXPRESSION to 0.5f,
        GuidanceCategory.POSE to 0.9f,
        GuidanceCategory.BACKGROUND to 0.7f,
    )

    private fun filmWeights(): Map<GuidanceCategory, Float> = mapOf(
        GuidanceCategory.COMPOSITION to 0.8f,
        GuidanceCategory.LIGHT to 0.5f,
        GuidanceCategory.EXPRESSION to 0.6f,
        GuidanceCategory.POSE to 0.9f,
        GuidanceCategory.BACKGROUND to 0.6f,
    )

    private fun sweetWeights(): Map<GuidanceCategory, Float> = mapOf(
        GuidanceCategory.COMPOSITION to 0.9f,
        GuidanceCategory.LIGHT to 1.1f,
        GuidanceCategory.EXPRESSION to 1.2f,
        GuidanceCategory.POSE to 0.9f,
        GuidanceCategory.BACKGROUND to 0.9f,
    )

    private fun coolWeights(): Map<GuidanceCategory, Float> = mapOf(
        GuidanceCategory.COMPOSITION to 0.8f,
        GuidanceCategory.LIGHT to 0.8f,
        GuidanceCategory.EXPRESSION to 0.5f,
        GuidanceCategory.POSE to 1.1f,
        GuidanceCategory.BACKGROUND to 0.7f,
    )

    private fun travelWeights(): Map<GuidanceCategory, Float> = mapOf(
        GuidanceCategory.COMPOSITION to 0.7f,
        GuidanceCategory.LIGHT to 0.8f,
        GuidanceCategory.EXPRESSION to 0.7f,
        GuidanceCategory.POSE to 0.8f,
        GuidanceCategory.BACKGROUND to 1.1f,
    )

    private fun idPhotoWeights(): Map<GuidanceCategory, Float> = mapOf(
        GuidanceCategory.COMPOSITION to 1.2f,
        GuidanceCategory.LIGHT to 1.1f,
        GuidanceCategory.EXPRESSION to 1.0f,
        GuidanceCategory.POSE to 1.2f,
        GuidanceCategory.BACKGROUND to 1.0f,
    )
}
