package com.yuesf.remotecontrol.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.yuesf.remotecontrol.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * GitHub Releases 更新检查与 APK 下载。
 *
 * 设计要点：
 * - 检查 / 下载全部走 IO 协程，不阻塞主线程。
 * - versionCode 才是唯一的版本序号（versionName 是给人看的，可能重名）；
 *   Release 的 tag 固定格式 `v<versionName>-<versionCode>`，例如 `v0.28.1-60`，
 *   解析出末尾的 versionCode 与本机 BuildConfig.VERSION_CODE 比较。
 * - 只认带 APK asset 的 Release，避免把源码包 / 草稿当成更新。
 * - 任何网络 / 解析异常都转成可读错误，界面层只负责展示，不崩溃。
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val RELEASES_URL =
        "https://api.github.com/repos/yuesf/android-remote-control/releases/latest"
    private const val APK_ASSET_NAME = "remote-control.apk"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val downloadUrl: String,
        val releaseNotes: String,
        val apkSizeBytes: Long,
    )

    sealed interface CheckResult {
        data class Available(val info: UpdateInfo) : CheckResult
        data object UpToDate : CheckResult
        data class Failed(val message: String) : CheckResult
    }

    /**
     * 检查是否有新版本。必须在协程里调用（内部切 IO）。
     *
     * GitHub API 要求带 User-Agent，否则 403。接受 200；404（还没有任何 Release）
     * 视为「已是最新」，首次发布前不会一直报错打扰用户。
     */
    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "android-remote-control/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    404 -> {
                        Log.i(TAG, "no release published yet")
                        CheckResult.UpToDate
                    }

                    200 -> parseRelease(response.body?.string().orEmpty())

                    else -> CheckResult.Failed("检查更新失败（HTTP ${response.code}）")
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "check failed", e)
            CheckResult.Failed("检查更新失败：${e.message ?: "网络不可用"}")
        } catch (e: Exception) {
            Log.w(TAG, "parse failed", e)
            CheckResult.Failed("更新信息解析失败：${e.message ?: "格式异常"}")
        }
    }

    private fun parseRelease(json: String): CheckResult {
        val release = JSONObject(json)
        val tag = release.optString("tag_name", "")
        val parsed = parseTag(tag)
            ?: return CheckResult.Failed("Release 版本号格式异常：$tag")
        val (versionName, remoteCode) = parsed

        if (remoteCode <= BuildConfig.VERSION_CODE) {
            return CheckResult.UpToDate
        }

        val assets = release.optJSONArray("assets")
        var downloadUrl: String? = null
        var apkSize = 0L
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (asset.optString("name") == APK_ASSET_NAME) {
                    downloadUrl = asset.optString("browser_download_url")
                    apkSize = asset.optLong("size", 0L)
                    break
                }
            }
        }
        if (downloadUrl.isNullOrBlank()) {
            return CheckResult.Failed("新版本未提供 APK 安装包")
        }

        return CheckResult.Available(
            UpdateInfo(
                versionName = versionName,
                versionCode = remoteCode,
                downloadUrl = downloadUrl,
                releaseNotes = release.optString("body", "").trim(),
                apkSizeBytes = apkSize,
            ),
        )
    }

    /** 解析 `v0.28.1-60` → ("0.28.1", 60)；格式不符返回 null。 */
    private fun parseTag(tag: String): Pair<String, Int>? {
        if (!tag.startsWith("v")) return null
        val body = tag.removePrefix("v")
        val lastDash = body.lastIndexOf('-')
        if (lastDash <= 0) return null
        val name = body.substring(0, lastDash)
        val code = body.substring(lastDash + 1).toIntOrNull() ?: return null
        return Pair(name, code)
    }

    /**
     * 下载 APK 到应用缓存目录，返回本地文件。调用方用 FileProvider 拉起安装。
     * onProgress 回调 0..100，在 IO 协程里调用。
     */
    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(info.downloadUrl)
            .header("User-Agent", "android-remote-control/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        // 每次下载覆盖同名文件，避免旧版本残留占空间。
        val outFile = File(context.cacheDir, "update.apk")
        if (outFile.exists()) outFile.delete()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("下载失败（HTTP ${response.code}）")
            }
            val body = response.body ?: throw IOException("下载响应为空")
            val total = body.contentLength()
            body.byteStream().use { input ->
                outFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    var downloaded = 0L
                    var lastProgress = -1
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val progress = (downloaded * 100 / total).toInt().coerceIn(0, 100)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                    }
                }
            }
        }
        if (outFile.length() < 1024) {
            throw IOException("下载文件异常（${outFile.length()} 字节）")
        }
        outFile
    }

    /** 读取本机版本展示串，供设置页显示。 */
    fun currentVersionLabel(context: Context): String {
        val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pInfo.versionCode.toLong()
        }
        return "${pInfo.versionName} ($code)"
    }
}
