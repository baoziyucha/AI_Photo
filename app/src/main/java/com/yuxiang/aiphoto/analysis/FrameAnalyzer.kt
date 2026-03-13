package com.yuxiang.aiphoto.analysis

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.yuxiang.aiphoto.model.GuidanceFrame
import com.yuxiang.aiphoto.sensors.DeviceTiltMonitor
import com.yuxiang.aiphoto.util.copyLumaPlane
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GuidanceFrameAnalyzer(
    private val deviceTiltMonitor: DeviceTiltMonitor,
    private val guidanceEngine: GuidanceEngine,
    private val stabilizer: GuidanceStabilizer,
    private val isFrontCamera: () -> Boolean,
    private val onGuidanceFrame: (GuidanceFrame, Long) -> Unit,
) : ImageAnalysis.Analyzer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val faceDetector = FaceSubjectDetector()
    private val lumaSubjectDetector = LumaSubjectDetector()
    private val brightnessEvaluator = BrightnessEvaluator()
    private val tiltEstimator = ImageTiltEstimator()

    @Volatile
    private var processing = false

    @Volatile
    private var closed = false

    override fun analyze(image: ImageProxy) {
        if (closed || processing) {
            image.close()
            return
        }
        val mediaImage = image.image ?: run {
            image.close()
            return
        }
        processing = true
        val startedAt = System.nanoTime()
        val luma = image.copyLumaPlane()
        val rotation = image.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        scope.launch {
            try {
                val frontCamera = isFrontCamera()
                val faceResult = runCatching {
                    faceDetector.detect(
                        inputImage = inputImage,
                        frameWidth = image.width,
                        frameHeight = image.height,
                        rotationDegrees = rotation,
                        mirrorX = frontCamera,
                    )
                }.getOrDefault(FaceDetectionResult(subjectBox = null, faceCount = 0, confidence = 0f))

                val fallbackSubject = if (faceResult.subjectBox == null) {
                    lumaSubjectDetector.detect(
                        luma = luma,
                        width = image.width,
                        height = image.height,
                        mirrorX = frontCamera,
                    )
                } else {
                    null
                }
                val subjectBox = faceResult.subjectBox ?: fallbackSubject?.subjectBox
                val confidence = maxOf(faceResult.confidence, fallbackSubject?.confidence ?: 0.18f)

                val sensorTilt = deviceTiltMonitor.currentRollDegrees
                val fallbackTilt = if (sensorTilt == null || abs(sensorTilt) > 25f) {
                    tiltEstimator.estimate(luma, image.width, image.height)
                } else {
                    null
                }
                val tiltDeg = normalizeTilt(sensorTilt ?: fallbackTilt ?: 0f)
                val brightness = brightnessEvaluator.evaluate(luma, image.width, image.height, subjectBox)
                val sceneType = SceneClassifier.classify(
                    faceCount = faceResult.faceCount,
                    subjectBox = subjectBox,
                    isFrontCamera = frontCamera,
                )
                val raw = guidanceEngine.build(
                    sceneType = sceneType,
                    subjectBox = subjectBox,
                    horizonTiltDeg = tiltDeg,
                    brightnessState = brightness,
                    faceCount = faceResult.faceCount,
                    confidence = confidence,
                )
                val stable = stabilizer.stabilize(raw)
                val latencyMs = (System.nanoTime() - startedAt) / 1_000_000
                withContext(Dispatchers.Main.immediate) {
                    onGuidanceFrame(stable, latencyMs)
                }
            } finally {
                image.close()
                processing = false
            }
        }
    }

    fun close() {
        closed = true
        faceDetector.close()
        scope.cancel()
    }

    private fun normalizeTilt(value: Float): Float {
        var tilt = value
        if (tilt > 90f) tilt -= 180f
        if (tilt < -90f) tilt += 180f
        return (tilt * 10f).toInt() / 10f
    }
}

