package com.yuxiang.aiphoto

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yuxiang.aiphoto.camera.AiCameraManager
import com.yuxiang.aiphoto.databinding.ActivityMainBinding
import com.yuxiang.aiphoto.databinding.DialogCloudSettingsBinding
import com.yuxiang.aiphoto.model.BrightnessState
import com.yuxiang.aiphoto.model.CaptureReadiness
import com.yuxiang.aiphoto.model.ReviewUiState
import com.yuxiang.aiphoto.model.SceneType
import com.yuxiang.aiphoto.model.StylePreset
import com.yuxiang.aiphoto.ui.BurstUiState
import com.yuxiang.aiphoto.ui.CameraScreenState
import com.yuxiang.aiphoto.ui.MainViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var cameraManager: AiCameraManager? = null
    private var lastReadiness: CaptureReadiness = CaptureReadiness.NOT_READY

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onPermissionResult(granted)
        if (granted) {
            startCamera()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupActions()
        observeUiState()

        val granted = hasCameraPermission()
        viewModel.onPermissionResult(granted)
        if (granted) {
            startCamera()
        }
    }

    override fun onDestroy() {
        cameraManager?.shutdown()
        super.onDestroy()
    }

    private fun setupActions() {
        binding.grantPermissionButton.setOnClickListener {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
        binding.captureButton.setOnClickListener {
            cameraManager?.capturePhoto(viewModel::onPhotoCaptured)
                ?: toast(getString(R.string.camera_not_ready))
        }
        // P2-4 长按快门触发连拍选片（默认 5 张，间隔 250ms）
        binding.captureButton.setOnLongClickListener {
            triggerBurst()
            true
        }
        binding.aiAssistSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAiAssistEnabled(isChecked)
            cameraManager?.setAiAssistEnabled(isChecked)
        }
        binding.switchCameraButton.setOnClickListener {
            cameraManager?.toggleCamera(this)
            viewModel.setFrontCamera(cameraManager?.isFrontCamera == true)
        }
        binding.settingsButton.setOnClickListener {
            showCloudSettingsDialog()
        }
        binding.settingsButton.setOnLongClickListener {
            showStyleSelectorDialog()
            true
        }
        binding.cloudReviewButton.setOnClickListener {
            viewModel.requestCloudReview()
        }
        binding.cloudReviewButton.setOnLongClickListener {
            viewModel.startVoiceDirector()
            toast("已启动语音导演")
            true
        }
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    render(state)
                }
            }
        }
    }

    private fun render(state: CameraScreenState) {
        binding.permissionCard.isVisible = !state.hasPermission
        binding.controlBar.alpha = if (state.hasPermission) 1f else 0.35f
        // 连拍期间禁用快门，防止并发触发 takePicture
        val isBursting = state.burstState is BurstUiState.Capturing
        binding.captureButton.isEnabled = state.hasPermission && !isBursting
        binding.switchCameraButton.isEnabled = state.hasPermission && !isBursting
        binding.aiAssistSwitch.isEnabled = state.hasPermission && !isBursting
        if (binding.aiAssistSwitch.isChecked != state.aiAssistEnabled) {
            binding.aiAssistSwitch.isChecked = state.aiAssistEnabled
        }

        binding.sceneChip.text = when (state.guidanceFrame.sceneType) {
            SceneType.PORTRAIT -> getString(R.string.scene_portrait)
            SceneType.SELFIE -> getString(R.string.scene_selfie)
            SceneType.PET_OR_CHILD -> getString(R.string.scene_pet_or_child)
            SceneType.DAILY_GENERIC -> getString(R.string.scene_daily_generic)
        }
        binding.brightnessChip.text = when (state.guidanceFrame.brightnessState) {
            BrightnessState.BALANCED -> getString(R.string.brightness_balanced)
            BrightnessState.LOW_LIGHT -> getString(R.string.brightness_low_light)
            BrightnessState.BACKLIT -> getString(R.string.brightness_backlit)
            BrightnessState.OVEREXPOSED -> getString(R.string.brightness_overexposed)
        }
        binding.latencyChip.text = if (state.analysisLatencyMs > 0) {
            "${state.analysisLatencyMs} ms"
        } else {
            getString(R.string.latency_default)
        }
        binding.guidanceText.text = when {
            !state.hasPermission -> getString(R.string.permission_message)
            state.guidanceFrame.recommendationText.isNotBlank() -> state.guidanceFrame.recommendationText
            else -> getString(R.string.guidance_waiting)
        }
        binding.detailText.text = when {
            !state.aiAssistEnabled -> getString(R.string.status_ai_off)
            state.guidanceFrame.detailText.isNotBlank() -> state.guidanceFrame.detailText
            else -> getString(R.string.detail_default)
        }
        binding.guidanceOverlayView.render(state.guidanceFrame, state.aiAssistEnabled)
        binding.guidanceOverlayView.renderCloudGuidance(state.cloudGuidanceState)

        val captureButtonColor = when (state.guidanceFrame.captureReadiness) {
            CaptureReadiness.READY -> ContextCompat.getColor(this, R.color.readiness_ready)
            CaptureReadiness.ALMOST_READY -> ContextCompat.getColor(this, R.color.readiness_almost)
            CaptureReadiness.NOT_READY -> ContextCompat.getColor(this, R.color.capture_button)
        }
        binding.captureButton.backgroundTintList = android.content.res.ColorStateList.valueOf(captureButtonColor)

        // READY 时快门放大 + 震动反馈，给出不可错过的"现在可以拍"信号
        val currentReadiness = state.guidanceFrame.captureReadiness
        when {
            currentReadiness == CaptureReadiness.READY && lastReadiness != CaptureReadiness.READY -> {
                binding.captureButton.animate().scaleX(1.18f).scaleY(1.18f).setDuration(280).start()
                binding.captureButton.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
            }
            currentReadiness != CaptureReadiness.READY && lastReadiness == CaptureReadiness.READY -> {
                binding.captureButton.animate().scaleX(1f).scaleY(1f).setDuration(280).start()
            }
        }
        lastReadiness = currentReadiness

        binding.reviewCard.isVisible = state.lastCapture != null
        binding.reviewHeaderText.text = state.lastCapture?.localSummary?.headline ?: getString(R.string.review_header_default)
        binding.localReviewText.text = state.photoScore?.toDisplayText()
            ?: state.lastCapture?.localSummary?.details
            ?: getString(R.string.review_empty)
        binding.cloudConsentStatusText.text = if (state.cloudReviewConsent) {
            getString(R.string.cloud_consent_enabled)
        } else {
            getString(R.string.cloud_consent_disabled)
        }
        binding.cloudReviewButton.isEnabled = state.lastCapture != null &&
            state.cloudReviewConsent &&
            state.reviewEndpoint.isNotBlank() &&
            state.reviewUiState !is ReviewUiState.Loading

        binding.cloudReviewText.text = when (val review = state.reviewUiState) {
            ReviewUiState.Idle -> getString(R.string.cloud_review_idle)
            ReviewUiState.Loading -> getString(R.string.reviewing)
            is ReviewUiState.Success -> getString(R.string.review_success_prefix) + "\n" + review.review.toDisplayText()
            is ReviewUiState.Error -> getString(R.string.review_error_prefix) + "\n" + review.fallback
        }

        state.transientMessage?.let { message ->
            toast(message)
            viewModel.consumeTransientMessage()
        }
    }

    private fun startCamera() {
        if (!hasCameraPermission()) {
            viewModel.onPermissionResult(false)
            return
        }
        val manager = cameraManager ?: AiCameraManager(
            context = this,
            previewView = binding.previewView,
            onGuidanceFrame = viewModel::onGuidanceFrame,
            onCameraError = ::toast,
        ).also { cameraManager = it }
        manager.setAiAssistEnabled(binding.aiAssistSwitch.isChecked)
        manager.bind(this)
        viewModel.setFrontCamera(manager.isFrontCamera)
    }

    /** P2-4 触发连拍选片：长按快门 → 顺序拍 5 张 → PhotoScorer.selectBest 选片。 */
    private fun triggerBurst() {
        val manager = cameraManager ?: run {
            toast(getString(R.string.camera_not_ready))
            return
        }
        val burstCount = 5
        viewModel.onBurstStart(burstCount)
        manager.captureBurst(
            count = burstCount,
            onProgress = viewModel::onBurstProgress,
            onComplete = viewModel::onBurstComplete,
            onError = viewModel::onBurstError,
        )
    }

    private fun showCloudSettingsDialog() {
        val state = viewModel.uiState.value
        val dialogBinding = DialogCloudSettingsBinding.inflate(layoutInflater)
        dialogBinding.endpointEditText.setText(state.reviewEndpoint)
        dialogBinding.cloudConsentSwitch.isChecked = state.cloudReviewConsent
        dialogBinding.voiceGuidanceSwitch.isChecked = state.voiceGuidanceEnabled

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.settings_cancel, null)
            .setPositiveButton(R.string.settings_save) { _, _ ->
                viewModel.updateCloudSettings(
                    endpoint = dialogBinding.endpointEditText.text?.toString().orEmpty(),
                    enabled = dialogBinding.cloudConsentSwitch.isChecked,
                )
                viewModel.setVoiceGuidanceEnabled(dialogBinding.voiceGuidanceSwitch.isChecked)
            }
            .show()
    }

    private fun showStyleSelectorDialog() {
        val styles = StylePreset.values()
        val labels = styles.map { preset ->
            when (preset) {
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
        }.toTypedArray()
        val current = styles.indexOf(viewModel.uiState.value.currentStyle)
        MaterialAlertDialogBuilder(this)
            .setTitle("选择风格")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val preset = styles[which]
                viewModel.onStyleSelected(preset)
                cameraManager?.setStyle(preset)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
