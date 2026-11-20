package com.yuesf.remotecontrol.data

import android.content.Context
import android.os.Process

/**
 * 应用分身 / 双开空间会为同一个 APK 建立独立的用户空间（userId != 0）。
 * 那里的 SharedPreferences 是独立的一份，会重新生成 deviceId，
 * 等于同一台手机在信令里变成两台设备：控制端会看到两个同名条目、容易连错；
 * 被控端若有两个实例，还会争抢屏幕采集与无障碍手势注入。
 *
 * 远程协助的前提是「一台手机一个身份、一个实例」，所以非机主空间直接拒绝运行。
 */
object SpaceGuard {
    const val PRIMARY_USER_ID = 0

    /** 每个用户空间的 uid 间隔，Android 多用户 uid = userId * PER_USER_RANGE + appId。 */
    private const val PER_USER_RANGE = 100_000

    /**
     * 当前进程所属的用户空间 id。
     * 用 uid 反推而不是 Context.getUserId()/Process.myUserId()——后两者是隐藏 API，
     * 在标准 SDK 上无法编译。Process.myUid() 是公开 API，结果等价。
     */
    private fun currentUserId(): Int = Process.myUid() / PER_USER_RANGE

    /**
     * 是否运行在机主空间。检测异常时按主空间处理，
     * 避免在定制 ROM 上误伤正常启动（宁可放过一次，也不要打不开应用）。
     */
    fun isPrimarySpace(@Suppress("UNUSED_PARAMETER") context: Context): Boolean =
        runCatching { currentUserId() == PRIMARY_USER_ID }.getOrDefault(true)
}
