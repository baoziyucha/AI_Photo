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
import com.yuxiang.aiphoto.model.ReviewUiState
import com.yuxiang.aiphoto.model.SceneType
import com.yuxiang.aiphoto.ui.CameraScreenState
import com.yuxiang.aiphoto.ui.MainViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var cameraManager: AiCameraManager? = null

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
        binding.cloudReviewButton.setOnClickListener {
            viewModel.requestCloudReview()
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
        binding.captureButton.isEnabled = state.hasPermission
        binding.switchCameraButton.isEnabled = state.hasPermission
        binding.aiAssistSwitch.isEnabled = state.hasPermission
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

        binding.reviewCard.isVisible = state.lastCapture != null
        binding.reviewHeaderText.text = state.lastCapture?.localSummary?.headline ?: getString(R.string.review_header_default)
        binding.localReviewText.text = state.lastCapture?.localSummary?.details ?: getString(R.string.review_empty)
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

    private fun showCloudSettingsDialog() {
        val state = viewModel.uiState.value
        val dialogBinding = DialogCloudSettingsBinding.inflate(layoutInflater)
        dialogBinding.endpointEditText.setText(state.reviewEndpoint)
        dialogBinding.cloudConsentSwitch.isChecked = state.cloudReviewConsent

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.settings_cancel, null)
            .setPositiveButton(R.string.settings_save) { _, _ ->
                viewModel.updateCloudSettings(
                    endpoint = dialogBinding.endpointEditText.text?.toString().orEmpty(),
                    enabled = dialogBinding.cloudConsentSwitch.isChecked,
                )
            }
            .show()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
