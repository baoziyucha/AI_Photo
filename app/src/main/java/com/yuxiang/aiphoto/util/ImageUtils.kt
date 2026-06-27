package com.yuxiang.aiphoto.util

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.ImageProxy
import com.yuxiang.aiphoto.model.BrightnessState
import com.yuxiang.aiphoto.model.GuidanceFrame
import com.yuxiang.aiphoto.model.LocalPhotoSummary
import com.yuxiang.aiphoto.model.SceneType
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

fun ImageProxy.copyLumaPlane(): ByteArray {
    val yPlane = planes.first()
    val buffer = yPlane.buffer
    val width = width
    val height = height
    val rowStride = yPlane.rowStride
    val pixelStride = yPlane.pixelStride
    val bytes = ByteArray(width * height)
    var offset = 0
    val rowData = ByteArray(rowStride)
    for (row in 0 until height) {
        buffer.position(row * rowStride)
        buffer.get(rowData, 0, minOf(rowStride, buffer.remaining()))
        var columnOffset = 0
        for (column in 0 until width) {
            bytes[offset++] = rowData[columnOffset]
            columnOffset += pixelStride
        }
    }
    return bytes
}

fun buildLocalPhotoSummary(frame: GuidanceFrame): LocalPhotoSummary {
    val headline = when (frame.sceneType) {
        SceneType.PORTRAIT -> "本次拍摄：人像"
        SceneType.SELFIE -> "本次拍摄：自拍"
        SceneType.PET_OR_CHILD -> "本次拍摄：宠物 / 孩童"
        SceneType.DAILY_GENERIC -> "本次拍摄：日常"
    }
    val brightness = when (frame.brightnessState) {
        BrightnessState.BALANCED -> "光线稳定"
        BrightnessState.LOW_LIGHT -> "光线偏暗"
        BrightnessState.BACKLIT -> "存在逆光"
        BrightnessState.OVEREXPOSED -> "高光偏强"
    }
    val area = frame.subjectBox?.let { (it.area * 100).roundToInt() } ?: 0
    val detail = buildString {
        append("建议：")
        append(if (frame.recommendationText.isBlank()) "当前没有稳定建议。" else frame.recommendationText)
        append("\n")
        append("状态：")
        append(brightness)
        append("，倾斜 ")
        append(String.format("%.1f", abs(frame.horizonTiltDeg)))
        append("°")
        if (area > 0) {
            append("，主体约占画面 ")
            append(area)
            append("%")
        }
        append("\n")
        append("分析摘要：")
        append(frame.detectionSummary())
    }
    return LocalPhotoSummary(
        headline = headline,
        details = detail,
        detectionSummary = frame.detectionSummary(),
    )
}

fun readAndCompressJpeg(
    context: Context,
    uriString: String,
    maxDimension: Int = 1600,
    quality: Int = 86,
): ByteArray {
    val resolver = context.contentResolver
    val rawBytes = resolver.openInputStream(android.net.Uri.parse(uriString))?.use { it.readBytes() }
        ?: error("Cannot read captured image")

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
    val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
    val bitmap = BitmapFactory.decodeByteArray(
        rawBytes,
        0,
        rawBytes.size,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    ) ?: return rawBytes

    return ByteArrayOutputStream().use { stream ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        bitmap.recycle()
        stream.toByteArray()
    }
}

private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    var sample = 1
    var currentWidth = width
    var currentHeight = height
    while (currentWidth > maxDimension || currentHeight > maxDimension) {
        currentWidth /= 2
        currentHeight /= 2
        sample *= 2
    }
    return sample.coerceAtLeast(1)
}

