package com.yuxiang.aiphoto.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yuxiang.aiphoto.analysis.PhotoScorer
import com.yuxiang.aiphoto.analysis.SceneComplexityEvaluator
import com.yuxiang.aiphoto.analysis.StyleProfileFactory
import com.yuxiang.aiphoto.analysis.UserPreferenceAggregator
import com.yuxiang.aiphoto.audio.GuidanceSpeaker
import com.yuxiang.aiphoto.model.CapturedPhoto
import com.yuxiang.aiphoto.model.GuidanceFrame
import com.yuxiang.aiphoto.model.PhotoScore
import com.yuxiang.aiphoto.model.ReviewUiState
import com.yuxiang.aiphoto.model.StylePreset
import com.yuxiang.aiphoto.model.UserPreference
import com.yuxiang.aiphoto.prefs.UserPreferencesRepository
import com.yuxiang.aiphoto.review.CloudGuidance
import com.yuxiang.aiphoto.review.CloudGuidanceClient
import com.yuxiang.aiphoto.review.CloudReviewRepository
import com.yuxiang.aiphoto.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "MainViewModel"

/** 语音导演剧本选择。 */
enum class VoiceDirectorScript {
    NATURAL_SMILE,
    CLOSEUP,
    WINDOW_SIDE_LIGHT,
    TRAVEL,
}

/**
 * 云端实时指导 UI 状态：渐进式展示。
 * - Idle：无云端请求
 * - Loading：云端请求中（显示"AI 分析中"动画）
 * - Enhanced：云端结果到达，叠加到本地指导之上
 */
sealed interface CloudGuidanceUiState {
    data object Idle : CloudGuidanceUiState
    data object Loading : CloudGuidanceUiState
    data class Enhanced(val guidance: CloudGuidance) : CloudGuidanceUiState
}

/**
 * P2-4 连拍选片 UI 状态。
 * - Idle：未触发连拍
 * - Capturing：连拍中（current/total 用于进度条）
 * - Done：选片完成，展示最佳照片评分
 */
sealed interface BurstUiState {
    data object Idle : BurstUiState
    data class Capturing(val current: Int, val total: Int) : BurstUiState
    data class Done(val pickedCount: Int, val bestScore: Int) : BurstUiState
}

data class CameraScreenState(
    val hasPermission: Boolean = false,
    val aiAssistEnabled: Boolean = true,
    val voiceGuidanceEnabled: Boolean = true,
    val guidanceFrame: GuidanceFrame = GuidanceFrame(),
    val analysisLatencyMs: Long = 0,
    val lastCapture: CapturedPhoto? = null,
    val photoScore: PhotoScore? = null,
    val reviewUiState: ReviewUiState = ReviewUiState.Idle,
    val cloudReviewConsent: Boolean = false,
    val reviewEndpoint: String = "",
    val transientMessage: String? = null,
    val isFrontCamera: Boolean = false,
    val cloudGuidanceState: CloudGuidanceUiState = CloudGuidanceUiState.Idle,
    val burstState: BurstUiState = BurstUiState.Idle,
    val currentStyle: StylePreset = StylePreset.FRESH,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferencesRepository = UserPreferencesRepository(application)
    private val cloudReviewRepository = CloudReviewRepository(application)
    private val cloudGuidanceClient = CloudGuidanceClient()
    private val photoScorer = PhotoScorer()
    private val guidanceSpeaker = GuidanceSpeaker(application)
    private val settings = preferencesRepository.load()
    private val userPreferenceAggregator = UserPreferenceAggregator()

    // P2-7 个性化记忆：内存缓存 + 持久化
    @Volatile
    private var userPreference: UserPreference = preferencesRepository.loadUserPreference()

    private val _uiState = MutableStateFlow(
        CameraScreenState(
            cloudReviewConsent = settings.cloudReviewEnabled,
            reviewEndpoint = settings.reviewEndpoint,
            voiceGuidanceEnabled = settings.voiceGuidanceEnabled,
        ),
    )
    val uiState: StateFlow<CameraScreenState> = _uiState.asStateFlow()

    init {
        guidanceSpeaker.setEnabled(settings.voiceGuidanceEnabled)
    }

    fun onPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(hasPermission = granted) }
    }

    fun onGuidanceFrame(frame: GuidanceFrame, latencyMs: Long) {
        val previous = _uiState.value.guidanceFrame
        _uiState.update {
            it.copy(
                guidanceFrame = frame,
                analysisLatencyMs = latencyMs,
            )
        }
        guidanceSpeaker.onFrameUpdate(previous, frame)
        // 复杂场景触发云端增强（渐进式：本地结果已显示，云端结果到达后叠加）
        maybeRequestCloudGuidance(frame)
    }

    /** 启动语音导演剧本：TTS 带节奏播报动作，剧本结束后若 readyCondition 满足则回调。 */
    fun startVoiceDirector(script: VoiceDirectorScript = VoiceDirectorScript.NATURAL_SMILE) {
        val captureScript = when (script) {
            VoiceDirectorScript.NATURAL_SMILE -> GuidanceSpeaker.NaturalSmileHeadshotScript
            VoiceDirectorScript.CLOSEUP -> GuidanceSpeaker.CloseupHeadshotScript
            VoiceDirectorScript.WINDOW_SIDE_LIGHT -> GuidanceSpeaker.WindowSideLightHalfBodyScript
            VoiceDirectorScript.TRAVEL -> GuidanceSpeaker.TravelEnvironmentalScript
        }
        guidanceSpeaker.startScript(captureScript) {
            emitMessage("导演剧本完成，已就绪。")
        }
        emitMessage("语音导演已启动，请按提示动作。")
    }

    fun stopVoiceDirector() {
        guidanceSpeaker.stopScript()
    }

    fun setAiAssistEnabled(enabled: Boolean) {
        _uiState.update {
            it.copy(
                aiAssistEnabled = enabled,
                transientMessage = if (enabled) "AI 自动调参与提示已开启" else "AI 自动调参已关闭",
            )
        }
    }

    fun setVoiceGuidanceEnabled(enabled: Boolean) {
        preferencesRepository.saveVoiceGuidanceEnabled(enabled)
        guidanceSpeaker.setEnabled(enabled)
        _uiState.update { it.copy(voiceGuidanceEnabled = enabled) }
    }

    fun setFrontCamera(frontCamera: Boolean) {
        _uiState.update { it.copy(isFrontCamera = frontCamera) }
    }

    /** 切换风格预设，由 MainActivity 调用 AiCameraManager.setStyle 实际生效。 */
    fun onStyleSelected(preset: StylePreset) {
        val baseProfile = StyleProfileFactory.create(preset)
        // P2-7 个性化记忆：将用户偏好注入 StyleProfile.userBias（允许本次关闭）
        val profile = if (userPreference.allowHistoryOverride) {
            baseProfile.copy(userBias = userPreference)
        } else {
            baseProfile.copy(userBias = UserPreference())
        }
        photoScorer.setStyle(profile)
        _uiState.update {
            it.copy(
                currentStyle = preset,
                transientMessage = "风格已切换：${preset.displayName()}" +
                    if (userPreference.allowHistoryOverride && userPreference.totalCaptures > 0) {
                        "（已应用个性化记忆）"
                    } else {
                        ""
                    },
            )
        }
    }

    /** P2-7 设置"本次关闭历史偏好"开关：关闭后风格切换不注入 userBias。 */
    fun setAllowHistoryOverride(allow: Boolean) {
        userPreference = userPreference.copy(allowHistoryOverride = allow)
        preferencesRepository.setAllowHistoryOverride(allow)
        _uiState.update {
            it.copy(
                transientMessage = if (allow) "已开启个性化记忆" else "已关闭个性化记忆（本次）",
            )
        }
    }

    /** P2-7 获取当前用户偏好（供 UI 展示"最佳侧脸/笑容/常见问题"）。 */
    fun getUserPreference(): UserPreference = userPreference

    /** P2-7 重置个性化记忆（清空历史统计）。 */
    fun resetUserPreference() {
        userPreference = UserPreference()
        preferencesRepository.clearUserPreference()
        _uiState.update {
            it.copy(transientMessage = "个性化记忆已清空。")
        }
    }

    private fun StylePreset.displayName(): String = when (this) {
        StylePreset.FRESH -> "清新"
        StylePreset.WORKPLACE -> "职场"
        StylePreset.STREET -> "街拍"
        StylePreset.EMOTIONAL -> "情绪"
        StylePreset.FILM -> "胶片"
        StylePreset.SWEET -> "甜美"
        StylePreset.COOL -> "冷感"
        StylePreset.TRAVEL -> "旅行"
        StylePreset.ID_PHOTO -> "证件"
    }

    fun onPhotoCaptured(photo: CapturedPhoto, frame: GuidanceFrame) {
        val score = photoScorer.score(frame)
        _uiState.update {
            it.copy(
                lastCapture = photo,
                photoScore = score,
                reviewUiState = ReviewUiState.Idle,
                transientMessage = "照片已保存到系统相册。评分：${score.score} 分",
            )
        }
        guidanceSpeaker.announce("已拍摄，评分 ${score.score} 分")
        // P2-7 个性化记忆：聚合本次拍摄到用户偏好
        aggregateAndUpdatePreference(score, frame)
    }

    // ============ P2-4 连拍选片 ============

    /** 连拍开始（由 MainActivity 长按快门触发）。 */
    fun onBurstStart(total: Int) {
        _uiState.update {
            it.copy(
                burstState = BurstUiState.Capturing(current = 0, total = total),
                transientMessage = "连拍中，请保持姿势…",
            )
        }
    }

    /** 连拍进度回调（每次 takePicture 完成后调用）。 */
    fun onBurstProgress(current: Int, total: Int) {
        _uiState.update {
            it.copy(
                burstState = BurstUiState.Capturing(current = current, total = total),
                transientMessage = "连拍中 $current/$total",
            )
        }
    }

    /** 连拍完成：调用 selectBest 选出最佳，更新 UI 状态并 TTS 通知。 */
    fun onBurstComplete(results: List<Pair<CapturedPhoto, GuidanceFrame>>) {
        if (results.isEmpty()) {
            _uiState.update {
                it.copy(
                    burstState = BurstUiState.Idle,
                    transientMessage = "连拍失败，请重试。",
                )
            }
            return
        }
        val frames = results.map { it.second }
        val best = photoScorer.selectBest(frames)
        val (bestPhoto, bestScore, bestFrame) = if (best != null) {
            val (score, idx) = best
            Triple(results[idx].first, score.score, results[idx].second)
        } else {
            // 评分失败兜底：取第一张
            Triple(results.first().first, 0, results.first().second)
        }
        _uiState.update {
            it.copy(
                lastCapture = bestPhoto,
                photoScore = best?.first,
                reviewUiState = ReviewUiState.Idle,
                burstState = BurstUiState.Done(pickedCount = results.size, bestScore = bestScore),
                transientMessage = "已从 ${results.size} 张连拍中选出最佳照片，评分 $bestScore 分。",
            )
        }
        guidanceSpeaker.announce("连拍完成，最佳评分 $bestScore 分")
        // P2-7 个性化记忆：仅聚合最佳照片，避免连拍 N 张污染统计
        val bestScoreObj = best?.first
        if (bestScoreObj != null) {
            aggregateAndUpdatePreference(bestScoreObj, bestFrame)
        }
    }

    fun onBurstError(message: String) {
        _uiState.update {
            it.copy(
                burstState = BurstUiState.Idle,
                transientMessage = message,
            )
        }
    }

    /** 选片结果展示完成后由 UI 调用，恢复 Idle。 */
    fun consumeBurstResult() {
        _uiState.update {
            if (it.burstState is BurstUiState.Done) {
                it.copy(burstState = BurstUiState.Idle)
            } else {
                it
            }
        }
    }

    // ============ P2-7 个性化记忆聚合 ============

    /** 聚合本次拍摄到用户偏好并持久化。 */
    private fun aggregateAndUpdatePreference(score: PhotoScore, frame: GuidanceFrame?) {
        val updated = userPreferenceAggregator.aggregate(userPreference, score, frame)
        userPreference = updated
        preferencesRepository.saveUserPreference(updated)
        Logger.d(
            TAG,
            "aggregateAndUpdatePreference: total=${updated.totalCaptures}, " +
                "highScore=${updated.highScoreCaptures}, bestYaw=${updated.bestHeadEulerY}",
        )
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

    override fun onCleared() {
        guidanceSpeaker.shutdown()
        super.onCleared()
    }

    /**
     * 复杂场景触发云端实时指导。
     * 渐进式展示：本地结果已立即显示，云端结果到达后叠加（不替换本地）。
     * 超时 2s 未返回则保持本地结果，无感知降级。
     */
    private fun maybeRequestCloudGuidance(frame: GuidanceFrame) {
        val endpoint = _uiState.value.reviewEndpoint
        if (endpoint.isBlank()) return
        if (!SceneComplexityEvaluator.shouldRequestCloud(frame)) return
        // 已在请求中则跳过
        if (_uiState.value.cloudGuidanceState is CloudGuidanceUiState.Loading) return

        _uiState.update { it.copy(cloudGuidanceState = CloudGuidanceUiState.Loading) }
        viewModelScope.launch {
            // TODO: 图像字节需通过 FrameAnalyzer 回调传递，当前传空数组，
            // 云端将基于检测元数据给出指导；超时 2s 自动降级回本地结果。
            val result = cloudGuidanceClient.requestGuidance(endpoint, frame, ByteArray(0))
            _uiState.update { current ->
                if (result == null) {
                    // 超时/失败：静默降级回本地，不展示错误
                    Logger.w(TAG, "cloud guidance fallback to local")
                    current.copy(cloudGuidanceState = CloudGuidanceUiState.Idle)
                } else {
                    current.copy(cloudGuidanceState = CloudGuidanceUiState.Enhanced(result))
                }
            }
        }
    }

    private fun emitMessage(message: String) {
        _uiState.update { it.copy(transientMessage = message) }
    }
}

