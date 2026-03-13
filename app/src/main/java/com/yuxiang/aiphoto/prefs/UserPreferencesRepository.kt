package com.yuxiang.aiphoto.prefs

import android.content.Context

data class UserSettings(
    val cloudReviewEnabled: Boolean,
    val reviewEndpoint: String,
)

class UserPreferencesRepository(context: Context) {
    private val preferences = context.getSharedPreferences("ai_photo_settings", Context.MODE_PRIVATE)

    fun load(): UserSettings = UserSettings(
        cloudReviewEnabled = preferences.getBoolean(KEY_CLOUD_REVIEW_ENABLED, false),
        reviewEndpoint = preferences.getString(KEY_REVIEW_ENDPOINT, "").orEmpty(),
    )

    fun saveCloudReviewEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_CLOUD_REVIEW_ENABLED, enabled).apply()
    }

    fun saveReviewEndpoint(endpoint: String) {
        preferences.edit().putString(KEY_REVIEW_ENDPOINT, endpoint.trim()).apply()
    }

    private companion object {
        const val KEY_CLOUD_REVIEW_ENABLED = "cloud_review_enabled"
        const val KEY_REVIEW_ENDPOINT = "review_endpoint"
    }
}

