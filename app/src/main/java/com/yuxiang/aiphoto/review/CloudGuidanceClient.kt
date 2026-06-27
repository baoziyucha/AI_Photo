package com.yuxiang.aiphoto.review

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.yuxiang.aiphoto.model.GuidanceFrame
import com.yuxiang.aiphoto.util.Logger
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "CloudGuidanceClient"

/**
 * 云端实时指导结果：由通义 VL Plus 返回，叠加到本地指导之上。
 */
data class CloudGuidance(
    val message: String,
    val action: String,
    val estimatedScore: Int,
    val highlightFocus: Boolean,
)

/**
 * 云端实时指导客户端。
 *
 * 优化策略：
 * - 帧采样：[FRAME_SAMPLE_MS] 内最多 1 次请求
 * - 内容去重：相同场景指纹 [DEDUP_MS] 内不重复
 * - 超时降级：[TIMEOUT_MS] 未返回则用本地结果（返回 null）
 * - 图片压缩：上传 512x512 JPEG
 */
class CloudGuidanceClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
) {
    @Volatile
    private var lastRequestAt: Long = 0L

    @Volatile
    private var lastFingerprint: String = ""

    /**
     * 请求云端实时指导。
     *
     * @param endpoint 服务地址
     * @param frame 本地检测帧（用于构造请求体与场景指纹）
     * @param jpegBytes 压缩后的 512x512 JPEG
     * @return 云端指导结果；节流/去重/超时/失败时返回 null，调用方应使用本地结果兜底
     */
    suspend fun requestGuidance(
        endpoint: String,
        frame: GuidanceFrame,
        jpegBytes: ByteArray,
    ): CloudGuidance? = withContext(Dispatchers.IO) {
        if (endpoint.isBlank()) return@withContext null

        val now = System.currentTimeMillis()
        // 帧采样
        if (now - lastRequestAt < FRAME_SAMPLE_MS) {
            Logger.d(TAG, "requestGuidance: skipped by frame sampling")
            return@withContext null
        }
        // 内容去重
        val fingerprint = sceneFingerprint(frame)
        if (fingerprint == lastFingerprint && now - lastRequestAt < DEDUP_MS) {
            Logger.d(TAG, "requestGuidance: skipped by dedup")
            return@withContext null
        }
        lastRequestAt = now
        lastFingerprint = fingerprint

        val payload = buildPayload(frame, jpegBytes)
        val request = Request.Builder()
            .url(endpoint.trimEnd('/') + "/v1/camera/guidance")
            .header("Content-Type", "application/json")
            .header("X-Model", MODEL_VL_PLUS)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val result = withTimeoutOrNull(TIMEOUT_MS) {
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}")
                    }
                    val body = response.body?.string().orEmpty()
                    val parsed = gson.fromJson(body, GuidancePayload::class.java)
                    val guidance = parsed.guidance
                    if (guidance == null) {
                        null
                    } else {
                        CloudGuidance(
                            message = guidance.message.orEmpty(),
                            action = guidance.action.orEmpty(),
                            estimatedScore = parsed.estimatedScore ?: 0,
                            highlightFocus = guidance.highlightFocus ?: false,
                        )
                    }
                }
            }.getOrNull()
        }
        if (result == null) {
            Logger.w(TAG, "requestGuidance: timeout or failure, fallback to local")
        }
        result
    }

    private fun buildPayload(frame: GuidanceFrame, jpegBytes: ByteArray): String {
        val base64 = android.util.Base64.encodeToString(jpegBytes, android.util.Base64.NO_WRAP)
        val positions = JSONArray()
        frame.subjectBox?.let {
            val o = JSONObject()
            o.put("left", it.left); o.put("top", it.top)
            o.put("right", it.right); o.put("bottom", it.bottom)
            positions.put(o)
        }
        val headEuler = JSONObject().apply {
            put("x", frame.headEulerX ?: JSONObject.NULL)
            put("y", frame.headEulerY ?: JSONObject.NULL)
            put("z", frame.headEulerZ ?: JSONObject.NULL)
        }
        val expression = JSONObject().apply {
            put("smile", frame.smilingProbability ?: JSONObject.NULL)
            put("left_eye_open", frame.leftEyeOpenProb ?: JSONObject.NULL)
            put("right_eye_open", frame.rightEyeOpenProb ?: JSONObject.NULL)
        }
        val faceLight = JSONObject().apply {
            put("ratio", frame.faceLightMetrics?.faceLightRatio ?: JSONObject.NULL)
            put("shadow_side", frame.faceLightMetrics?.shadowSide?.name?.lowercase() ?: JSONObject.NULL)
        }
        val diagnostics = JSONArray().apply {
            frame.issues.forEach { issue ->
                val o = JSONObject()
                o.put("id", issue.id)
                o.put("severity", issue.severity.name.lowercase())
                o.put("message", issue.message)
                put(o)
            }
        }
        val styleProfile = JSONObject().apply {
            put("preset", frame.styleProfile.presetId.name.lowercase())
            put("speech_tone", frame.styleProfile.speechTone.name.lowercase())
        }
        val payload = JSONObject().apply {
            put("scene_type", frame.sceneType.name.lowercase())
            put("image_base64", base64)
            put("face_count", frame.faceCount)
            put("face_positions", positions)
            put("head_euler", headEuler)
            put("expression", expression)
            put("face_light", faceLight)
            put("readiness", frame.captureReadiness.name.lowercase())
            put("diagnostics", diagnostics)
            put("style_profile", styleProfile)
            put("brightness_state", frame.brightnessState.name.lowercase())
            put("tilt_deg", frame.horizonTiltDeg)
            put("confidence", frame.confidence)
            put("request_id", UUID.randomUUID().toString())
        }
        return payload.toString()
    }

    private fun sceneFingerprint(frame: GuidanceFrame): String {
        val subject = frame.subjectBox?.let {
            "${bucket(it.centerX)}:${bucket(it.centerY)}:${bucket(it.area)}"
        } ?: "none"
        val yawBucket = frame.headEulerY?.let { (it / 5f).toInt() } ?: "_"
        val ratioBucket = frame.faceLightMetrics?.faceLightRatio?.let { r ->
            when { r > 2.0f -> 2; r > 1.5f -> 1; else -> 0 }
        } ?: "_"
        return listOf(
            frame.sceneType.name,
            frame.brightnessState.name,
            subject,
            bucket(frame.horizonTiltDeg / 15f),
            "yaw=$yawBucket",
            "ratio=$ratioBucket",
        ).joinToString("|")
    }

    private fun bucket(value: Float): Int = (value * 10f).toInt()

    private data class GuidancePayload(
        @SerializedName("scene_assessment") val sceneAssessment: String?,
        @SerializedName("guidance") val guidance: GuidanceBody?,
        @SerializedName("alternative_suggestions") val alternativeSuggestions: List<String>?,
        @SerializedName("estimated_score") val estimatedScore: Int?,
    )

    private data class GuidanceBody(
        @SerializedName("action") val action: String?,
        @SerializedName("message") val message: String?,
        @SerializedName("highlight_focus") val highlightFocus: Boolean?,
    )

    companion object {
        private const val FRAME_SAMPLE_MS = 500L
        private const val DEDUP_MS = 2000L
        private const val TIMEOUT_MS = 2000L
        private const val MODEL_VL_PLUS = "mimo-v2.5"
    }
}
