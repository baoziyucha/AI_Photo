package com.yuxiang.aiphoto.prefs

import android.content.Context
import com.yuxiang.aiphoto.model.StylePreset
import com.yuxiang.aiphoto.model.UserPreference

data class UserSettings(
    val cloudReviewEnabled: Boolean,
    val reviewEndpoint: String,
    val voiceGuidanceEnabled: Boolean,
)

class UserPreferencesRepository(context: Context) {
    private val preferences = context.getSharedPreferences("ai_photo_settings", Context.MODE_PRIVATE)

    fun load(): UserSettings = UserSettings(
        cloudReviewEnabled = preferences.getBoolean(KEY_CLOUD_REVIEW_ENABLED, false),
        reviewEndpoint = preferences.getString(KEY_REVIEW_ENDPOINT, "").orEmpty(),
        voiceGuidanceEnabled = preferences.getBoolean(KEY_VOICE_GUIDANCE_ENABLED, true),
    )

    fun saveCloudReviewEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_CLOUD_REVIEW_ENABLED, enabled).apply()
    }

    fun saveReviewEndpoint(endpoint: String) {
        preferences.edit().putString(KEY_REVIEW_ENDPOINT, endpoint.trim()).apply()
    }

    fun saveVoiceGuidanceEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_VOICE_GUIDANCE_ENABLED, enabled).apply()
    }

    // ===== P2-7 个性化记忆持久化 =====

    /** 读取持久化的用户偏好（个性化记忆）。 */
    fun loadUserPreference(): UserPreference {
        val bestYaw = preferences.getString(KEY_BEST_HEAD_EULER_Y, null)?.toFloatOrNull()
        val bestSmile = preferences.getString(KEY_BEST_SMILING_PROB, null)?.toFloatOrNull()
        val commonIssues = preferences.getString(KEY_COMMON_ISSUE_IDS, "")
            .split(SEPARATOR)
            .filter { it.isNotBlank() }
        val totalCaptures = preferences.getInt(KEY_TOTAL_CAPTURES, 0)
        val highScoreCaptures = preferences.getInt(KEY_HIGH_SCORE_CAPTURES, 0)
        val allowOverride = preferences.getBoolean(KEY_ALLOW_HISTORY_OVERRIDE, true)

        val liked = preferences.getString(KEY_LIKED_STYLES, "")
            .split(SEPARATOR)
            .filter { it.isNotBlank() }
            .mapNotNull { name -> runCatching { StylePreset.valueOf(name) }.getOrNull() }
            .toSet()
        val disliked = preferences.getString(KEY_DISLIKED_STYLES, "")
            .split(SEPARATOR)
            .filter { it.isNotBlank() }
            .mapNotNull { name -> runCatching { StylePreset.valueOf(name) }.getOrNull() }
            .toSet()

        return UserPreference(
            likedStyles = liked,
            dislikedStyles = disliked,
            allowHistoryOverride = allowOverride,
            bestHeadEulerY = bestYaw,
            bestSmilingProbability = bestSmile,
            commonIssueIds = commonIssues,
            totalCaptures = totalCaptures,
            highScoreCaptures = highScoreCaptures,
        )
    }

    /** 持久化用户偏好。 */
    fun saveUserPreference(preference: UserPreference) {
        preferences.edit().apply {
            putString(KEY_LIKED_STYLES, preference.likedStyles.joinToString(SEPARATOR) { it.name })
            putString(KEY_DISLIKED_STYLES, preference.dislikedStyles.joinToString(SEPARATOR) { it.name })
            putBoolean(KEY_ALLOW_HISTORY_OVERRIDE, preference.allowHistoryOverride)
            putString(KEY_BEST_HEAD_EULER_Y, preference.bestHeadEulerY?.toString() ?: "")
            putString(KEY_BEST_SMILING_PROB, preference.bestSmilingProbability?.toString() ?: "")
            putString(KEY_COMMON_ISSUE_IDS, preference.commonIssueIds.joinToString(SEPARATOR))
            putInt(KEY_TOTAL_CAPTURES, preference.totalCaptures)
            putInt(KEY_HIGH_SCORE_CAPTURES, preference.highScoreCaptures)
        }.apply()
    }

    /** 设置"本次关闭历史偏好"开关（不持久化到下次）。 */
    fun setAllowHistoryOverride(allow: Boolean) {
        preferences.edit().putBoolean(KEY_ALLOW_HISTORY_OVERRIDE, allow).apply()
    }

    /** 清空个性化记忆（用户重置）。 */
    fun clearUserPreference() {
        preferences.edit().apply {
            remove(KEY_BEST_HEAD_EULER_Y)
            remove(KEY_BEST_SMILING_PROB)
            remove(KEY_COMMON_ISSUE_IDS)
            remove(KEY_TOTAL_CAPTURES)
            remove(KEY_HIGH_SCORE_CAPTURES)
            remove(KEY_LIKED_STYLES)
            remove(KEY_DISLIKED_STYLES)
        }.apply()
    }

    private companion object {
        const val KEY_CLOUD_REVIEW_ENABLED = "cloud_review_enabled"
        const val KEY_REVIEW_ENDPOINT = "review_endpoint"
        const val KEY_VOICE_GUIDANCE_ENABLED = "voice_guidance_enabled"

        // P2-7 个性化记忆 keys
        const val KEY_BEST_HEAD_EULER_Y = "best_head_euler_y"
        const val KEY_BEST_SMILING_PROB = "best_smiling_prob"
        const val KEY_COMMON_ISSUE_IDS = "common_issue_ids"
        const val KEY_TOTAL_CAPTURES = "total_captures"
        const val KEY_HIGH_SCORE_CAPTURES = "high_score_captures"
        const val KEY_LIKED_STYLES = "liked_styles"
        const val KEY_DISLIKED_STYLES = "disliked_styles"
        const val KEY_ALLOW_HISTORY_OVERRIDE = "allow_history_override"

        const val SEPARATOR = ","
    }
}
