package com.yuesf.remotecontrol.debug

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 现场排查用的文件日志。
 *
 * 荣耀这类 ROM 会关掉部分应用的 logcat（`logcat -g` 显示 0 B readable），
 * 而信令「在线但收不到消息」这种问题恰恰只能在真机上复现，所以把关键事件
 * 落盘到应用私有目录，用 `adb shell run-as <pkg> cat files/rc.log` 取。
 *
 * 只记录信令收发这类低频事件，不做通用日志框架，够用就行。
 */
object FileLog {
    private const val TAG = "FileLog"
    private const val FILE_NAME = "rc.log"
    private const val MAX_BYTES = 128 * 1024L

    @Volatile
    private var file: File? = null

    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        if (file != null) {
            return
        }
        file = File(context.filesDir, FILE_NAME)
    }

    @Synchronized
    fun append(message: String) {
        val target = file ?: return
        runCatching {
            if (target.length() > MAX_BYTES) {
                target.writeText("")
            }
            target.appendText("${stamp.format(Date())} $message\n")
        }.onFailure { Log.w(TAG, "append failed", it) }
    }
}
