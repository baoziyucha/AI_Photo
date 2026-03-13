package com.yuxiang.aiphoto.review

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.yuxiang.aiphoto.model.CapturedPhoto
import com.yuxiang.aiphoto.model.PhotoReview
import com.yuxiang.aiphoto.util.readAndCompressJpeg
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class CloudReviewRepository(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
) {
    suspend fun requestReview(endpoint: String, capturedPhoto: CapturedPhoto): Result<PhotoReview> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val jpegBytes = readAndCompressJpeg(context, capturedPhoto.uri.toString())
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        capturedPhoto.fileName,
                        jpegBytes.toRequestBody("image/jpeg".toMediaType()),
                    )
                    .addFormDataPart("sceneType", capturedPhoto.sceneType.name.lowercase())
                    .addFormDataPart("detectionSummary", capturedPhoto.localSummary.detectionSummary)
                    .addFormDataPart("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .build()

                val request = Request.Builder()
                    .url(endpoint.trimEnd('/') + "/v1/photo/review")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}")
                    }
                    val payload = response.body?.string().orEmpty()
                    val parsed = gson.fromJson(payload, ReviewPayload::class.java)
                    PhotoReview(
                        summary = parsed.summary.orEmpty(),
                        strengths = parsed.strengths.orEmpty(),
                        issues = parsed.issues.orEmpty(),
                        suggestions = parsed.suggestions.orEmpty(),
                    )
                }
            }
        }
    }

    private data class ReviewPayload(
        @SerializedName("summary") val summary: String?,
        @SerializedName("strengths") val strengths: List<String>?,
        @SerializedName("issues") val issues: List<String>?,
        @SerializedName("suggestions") val suggestions: List<String>?,
    )
}

