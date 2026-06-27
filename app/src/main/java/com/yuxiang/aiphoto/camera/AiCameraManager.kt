package com.yuxiang.aiphoto.camera

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.provider.MediaStore
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.yuxiang.aiphoto.analysis.GuidanceEngine
import com.yuxiang.aiphoto.analysis.GuidanceFrameAnalyzer
import com.yuxiang.aiphoto.analysis.GuidanceStabilizer
import com.yuxiang.aiphoto.model.CapturedPhoto
import com.yuxiang.aiphoto.model.GuidanceFrame
import com.yuxiang.aiphoto.model.NormalizedPoint
import com.yuxiang.aiphoto.sensors.DeviceTiltMonitor
import com.yuxiang.aiphoto.util.Logger
import com.yuxiang.aiphoto.util.buildLocalPhotoSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

private const val BURST_TAG = "AiCameraManager.burst"

class AiCameraManager(
    private val context: Context,
    private val previewView: PreviewView,
    private val onGuidanceFrame: (GuidanceFrame, Long) -> Unit,
    private val onCameraError: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val deviceTiltMonitor = DeviceTiltMonitor(context)
    private val guidanceEngine = GuidanceEngine()
    private val stabilizer = GuidanceStabilizer()
    private val mainExecutor by lazy { ContextCompat.getMainExecutor(context) }

    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var analyzer: GuidanceFrameAnalyzer? = null
    private var latestFrame: GuidanceFrame = GuidanceFrame()
    private var aiAssistEnabled: Boolean = true
    private var lastMeteringPoint: NormalizedPoint? = null
    private var lastExposureFingerprint: String? = null

    val isFrontCamera: Boolean
        get() = lensFacing == CameraSelector.LENS_FACING_FRONT

    fun bind(lifecycleOwner: LifecycleOwner) {
        scope.launch {
            runCatching {
                deviceTiltMonitor.start()
                val provider = cameraProvider ?: ProcessCameraProvider.getInstance(context).await().also {
                    cameraProvider = it
                }
                provider.unbindAll()

                val preview = Preview.Builder().applyInterop().build().apply {
                    surfaceProvider = previewView.surfaceProvider
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .applyInterop()
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .applyInterop()
                    .build()

                analyzer?.close()
                analyzer = GuidanceFrameAnalyzer(
                    deviceTiltMonitor = deviceTiltMonitor,
                    guidanceEngine = guidanceEngine,
                    stabilizer = stabilizer,
                    isFrontCamera = { isFrontCamera },
                ) { frame, latencyMs ->
                    latestFrame = frame
                    onGuidanceFrame(frame, latencyMs)
                    if (aiAssistEnabled) {
                        applyCameraAction(frame)
                    }
                }
                analysis.setAnalyzer(analysisExecutor, analyzer!!)

                val selector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()
                camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, capture, analysis)
                imageCapture = capture
                lastMeteringPoint = null
                lastExposureFingerprint = null
            }.onFailure { error ->
                onCameraError(error.message ?: "相机初始化失败")
            }
        }
    }

    /** 切换风格预设，影响规则变体与话术。 */
    fun setStyle(preset: com.yuxiang.aiphoto.model.StylePreset) {
        guidanceEngine.styleProfile = com.yuxiang.aiphoto.analysis.StyleProfileFactory.create(preset)
    }

    fun setAiAssistEnabled(enabled: Boolean) {
        aiAssistEnabled = enabled
    }

    fun toggleCamera(lifecycleOwner: LifecycleOwner) {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        stabilizer.reset()
        bind(lifecycleOwner)
    }

    fun capturePhoto(onCaptured: (CapturedPhoto, GuidanceFrame) -> Unit) {
        val capture = imageCapture ?: run {
            onCameraError("相机尚未就绪，请稍后再试。")
            return
        }
        val frameSnapshot = latestFrame
        val fileName = "AiPhoto_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AiPhoto")
            },
        ).build()

        capture.takePicture(
            outputOptions,
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val uri = outputFileResults.savedUri ?: run {
                        onCameraError("保存成功，但未拿到照片地址。")
                        return
                    }
                    val summary = buildLocalPhotoSummary(frameSnapshot)
                    onCaptured(
                        CapturedPhoto(
                            uri = uri,
                            sceneType = frameSnapshot.sceneType,
                            localSummary = summary,
                            capturedAtMillis = System.currentTimeMillis(),
                            fileName = fileName,
                        ),
                        frameSnapshot,
                    )
                }

                override fun onError(exception: ImageCaptureException) {
                    onCameraError(exception.message ?: "拍照失败")
                }
            },
        )
    }

    /**
     * P2-4 连拍选片：按 [intervalMs] 间隔顺序触发 [count] 次 takePicture，
     * CameraX 不支持并发 takePicture，故用协程顺序 await + delay。
     * 单张失败不中断整组，最终回调 onComplete 返回所有成功照片及其拍摄瞬间的帧快照。
     * 调用方应将帧列表喂给 PhotoScorer.selectBest 找出最佳。
     */
    fun captureBurst(
        count: Int = 5,
        intervalMs: Long = 250L,
        onProgress: (current: Int, total: Int) -> Unit,
        onComplete: (List<Pair<CapturedPhoto, GuidanceFrame>>) -> Unit,
        onError: (String) -> Unit,
    ) {
        val capture = imageCapture ?: run {
            onError("相机尚未就绪，请稍后再试。")
            return
        }
        scope.launch {
            val results = mutableListOf<Pair<CapturedPhoto, GuidanceFrame>>()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            repeat(count) { index ->
                val frameSnapshot = latestFrame
                val fileName = "AiPhoto_burst_${timestamp}_${index + 1}.jpg"
                val outputOptions = ImageCapture.OutputFileOptions.Builder(
                    context.contentResolver,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AiPhoto")
                    },
                ).build()

                val result = runCatching {
                    suspendCancellableCoroutine<Pair<CapturedPhoto, GuidanceFrame>> { cont ->
                        capture.takePicture(
                            outputOptions,
                            mainExecutor,
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                    val uri = outputFileResults.savedUri
                                    if (uri == null) {
                                        cont.resumeWithException(IllegalStateException("保存成功，但未拿到照片地址。"))
                                        return
                                    }
                                    val summary = buildLocalPhotoSummary(frameSnapshot)
                                    val photo = CapturedPhoto(
                                        uri = uri,
                                        sceneType = frameSnapshot.sceneType,
                                        localSummary = summary,
                                        capturedAtMillis = System.currentTimeMillis(),
                                        fileName = fileName,
                                    )
                                    cont.resume(photo to frameSnapshot)
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    cont.resumeWithException(exception)
                                }
                            },
                        )
                    }
                }
                result.onSuccess { results += it }
                    .onFailure {
                        Logger.w(BURST_TAG, "burst[$index/${count}] failed: ${it.message}")
                    }
                onProgress(index + 1, count)
                if (index < count - 1) delay(intervalMs)
            }
            Logger.d(BURST_TAG, "burst done: ${results.size}/$count succeeded")
            onComplete(results)
        }
    }

    fun shutdown() {
        analyzer?.close()
        analysisExecutor.shutdown()
        analysisExecutor.awaitTermination(1, TimeUnit.SECONDS)
        deviceTiltMonitor.stop()
        cameraProvider?.unbindAll()
        scope.cancel()
    }

    private fun applyCameraAction(frame: GuidanceFrame) {
        if (!frame.isStable) return
        val activeCamera = camera ?: return
        frame.cameraAction.focusMeteringPoint?.let { point ->
            if (shouldUpdateMetering(point)) {
                val focusPoint = previewView.meteringPointFactory.createPoint(
                    point.x.coerceIn(0f, 1f) * previewView.width,
                    point.y.coerceIn(0f, 1f) * previewView.height,
                )
                val action = FocusMeteringAction.Builder(
                    focusPoint,
                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
                )
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build()
                activeCamera.cameraControl.startFocusAndMetering(action)
                lastMeteringPoint = point
            }
        }

        val delta = frame.cameraAction.exposureCompensationDelta
        if (delta == 0) {
            lastExposureFingerprint = null
            return
        }
        val exposureFingerprint = "${frame.brightnessState}:${delta}"
        if (exposureFingerprint == lastExposureFingerprint) return

        val state = activeCamera.cameraInfo.exposureState
        val target = (state.exposureCompensationIndex + delta).coerceIn(
            state.exposureCompensationRange.lower,
            state.exposureCompensationRange.upper,
        )
        if (target != state.exposureCompensationIndex) {
            activeCamera.cameraControl.setExposureCompensationIndex(target)
            lastExposureFingerprint = exposureFingerprint
        }
    }

    private fun shouldUpdateMetering(point: NormalizedPoint): Boolean {
        val previous = lastMeteringPoint ?: return true
        return abs(previous.x - point.x) > 0.06f || abs(previous.y - point.y) > 0.06f
    }

    private fun Preview.Builder.applyInterop(): Preview.Builder = apply {
        val extender = Camera2Interop.Extender(this)
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
        )
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_MODE,
            CaptureRequest.CONTROL_AE_MODE_ON,
        )
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_AWB_MODE,
            CaptureRequest.CONTROL_AWB_MODE_AUTO,
        )
    }

    private fun ImageCapture.Builder.applyInterop(): ImageCapture.Builder = apply {
        val extender = Camera2Interop.Extender(this)
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
        )
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_MODE,
            CaptureRequest.CONTROL_AE_MODE_ON,
        )
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_AWB_MODE,
            CaptureRequest.CONTROL_AWB_MODE_AUTO,
        )
    }

    private fun ImageAnalysis.Builder.applyInterop(): ImageAnalysis.Builder = apply {
        val extender = Camera2Interop.Extender(this)
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
        )
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_MODE,
            CaptureRequest.CONTROL_AE_MODE_ON,
        )
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_AWB_MODE,
            CaptureRequest.CONTROL_AWB_MODE_AUTO,
        )
    }
}
