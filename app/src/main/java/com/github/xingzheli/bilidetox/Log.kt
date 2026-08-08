package com.github.xingzheli.bilidetox

import android.util.Log as AndroidLog
import de.robv.android.xposed.XposedBridge

/**
 * 日志封装。
 *
 * 同时写 logcat 和 Xposed 日志，方便两种排查方式：
 *   adb logcat -s BiliDetox:V
 *   LSPosed / NPatch 管理器里的模块日志
 */
object Log {
    private const val TAG = "BiliDetox"

    fun d(message: String) {
        AndroidLog.d(TAG, message)
        XposedBridge.log("$TAG: $message")
    }

    fun w(message: String) {
        AndroidLog.w(TAG, message)
        XposedBridge.log("$TAG/W: $message")
    }

    fun e(message: String, throwable: Throwable? = null) {
        AndroidLog.e(TAG, message, throwable)
        XposedBridge.log("$TAG/E: $message")
        throwable?.let { XposedBridge.log(it) }
    }
}
