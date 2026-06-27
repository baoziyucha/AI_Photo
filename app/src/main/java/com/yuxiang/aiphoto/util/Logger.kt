package com.yuxiang.aiphoto.util

import android.util.Log
import com.yuxiang.aiphoto.BuildConfig

/**
 * 统一日志工具：release 构建自动关闭 debug 日志，避免 println 残留输出。
 */
object Logger {
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message)
        }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            Log.w(tag, message, throwable)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
    }
}
