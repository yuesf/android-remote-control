package com.yuesf.remotecontrol.data

/**
 * 一条「连接过的设备」历史记录，持久化在 AppPreferences 里。
 *
 * 为什么不在服务端查：信令的设备注册表只反映当前在线设备，掉线即被删除，
 * 无法回答「我之前连过谁」这种历史语义。历史必须落在本地。
 */
data class ConnectedDevice(
    val deviceId: String,
    val deviceName: String,
    /** 四位配对码，仅用于卡片上展示；连接走 deviceId，不依赖码。 */
    val pairCode: String?,
    val lastConnectedAt: Long,
)
