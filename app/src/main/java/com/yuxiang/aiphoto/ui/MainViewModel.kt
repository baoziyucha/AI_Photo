package com.yuxiang.aiphoto.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yuxiang.aiphoto.model.CapturedPhoto
import com.yuxiang.aiphoto.model.GuidanceFrame
import com.yuxiang.aiphoto.model.ReviewUiState
import com.yuxiang.aiphoto.prefs.UserPreferencesRepository
import com.yuxiang.aiphoto.review.CloudReviewRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CameraScreenState(
    val hasPermission: Boolean = false,
    val aiAssistEnabled: Boolean = true,
    val guidanceFrame: GuidanceFrame = GuidanceFrame(),
    val analysisLatencyMs: Long = 0,
    val lastCapture: CapturedPhoto? = null,
    val reviewUiState: ReviewUiState = ReviewUiState.Idle,
    val cloudReviewConsent: Boolean = false,
    val reviewEndpoint: String = "",
    val transientMessage: String? = null,
    val isFrontCamera: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferencesRepository = UserPreferencesRepository(application)
    private val cloudReviewRepository = CloudReviewRepository(application)
    private val settings = preferencesRepository.load()

    private val _uiState = MutableStateFlow(
        CameraScreenState(
            cloudReviewConsent = settings.cloudReviewEnabled,
            reviewEndpoint = settings.reviewEndpoint,
        ),
    )
    val uiState: StateFlow<CameraScreenState> = _uiState.asStateFlow()

    fun onPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(hasPermission = granted) }
    }

    fun onGuidanceFrame(frame: GuidanceFrame, latencyMs: Long) {
        _uiState.update {
            it.copy(
                guidanceFrame = frame,
                analysisLatencyMs = latencyMs,
            )
        }
    }

    fun setAiAssistEnabled(enabled: Boolean) {
        _uiState.update {
            it.copy(
                aiAssistEnabled = enabled,
                transientMessage = if (enabled) "AI 自动调参与提示已开启" else "AI 自动调参已关闭",
            )
        }
    }

    fun setFrontCamera(frontCamera: Boolean) {
        _uiState.update { it.copy(isFrontCamera = frontCamera) }
    }

    fun onPhotoCaptured(photo: CapturedPhoto) {
        _uiState.update {
            it.copy(
                lastCapture = photo,
                reviewUiState = ReviewUiState.Idle,
                transientMessage = "照片已保存到系统相册。",
            )
        }
    }

    fun updateCloudSettings(endpoint: String, enabled: Boolean) {
        preferencesRepository.saveReviewEndpoint(endpoint)
        preferencesRepository.saveCloudReviewEnabled(enabled)
        _uiState.update {
            it.copy(
                reviewEndpoint = endpoint.trim(),
                cloudReviewConsent = enabled,
            )
        }
    }

    fun requestCloudReview() {
        val state = _uiState.value
        val capture = state.lastCapture
        if (capture == null) {
            emitMessage("请先拍一张照片。")
            return
        }
        if (!state.cloudReviewConsent || state.reviewEndpoint.isBlank()) {
            emitMessage("请先配置点评服务地址并授权云端点评。")
            return
        }
        _uiState.update { it.copy(reviewUiState = ReviewUiState.Loading) }
        viewModelScope.launch {
            val result = cloudReviewRepository.requestReview(state.reviewEndpoint, capture)
            result.fold(
                onSuccess = { review ->
                    _uiState.update { current ->
                        current.copy(reviewUiState = ReviewUiState.Success(review))
                    }
                },
                onFailure = { error ->
                    _uiState.update { current ->
                        current.copy(
                            reviewUiState = ReviewUiState.Error(
                                message = error.message ?: "未知错误",
                                fallback = capture.localSummary.details,
                            ),
                        )
                    }
                },
            )
        }
    }

    fun consumeTransientMessage() {
        _uiState.update { it.copy(transientMessage = null) }
    }

    private fun emitMessage(message: String) {
        _uiState.update { it.copy(transientMessage = message) }
    }
}

