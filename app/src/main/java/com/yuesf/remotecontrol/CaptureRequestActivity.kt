package com.yuesf.remotecontrol

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.yuesf.remotecontrol.accessibility.CaptureAutoApprove
import com.yuesf.remotecontrol.agent.RemoteAgentService

/**
 * 没有任何界面的透明 Activity，唯一职责是承接系统授权结果。
 *
 * 为什么需要它：`startActivityForResult` 只能由 Activity 发起，
 * 被控服务在后台没法直接拿 `RESULT_OK` 里的 projectionData。
 * 服务发现采集失效时把本 Activity 拉起来 → 它弹出系统授权框 →
 * 无障碍服务代点「立即开始」→ 结果回传到 `onActivityResult` → 交给服务。
 *
 * 用户能感知到的只有一闪而过的系统对话框，没有我们自己的界面。
 */
class CaptureRequestActivity : AppCompatActivity() {
    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        CaptureAutoApprove.disarm()
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            runCatching { RemoteAgentService.startWithProjection(this, data) }
                .onFailure { Log.e(TAG, "unable to hand projection to service", it) }
        } else {
            Log.w(TAG, "capture authorization declined")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 权限前置条件由发起方（RemoteAgentService）负责校验：
        // 本 Activity 既然已经被启动起来，说明后台启动这一步已经通过了，
        // 再检查一遍只会让 adb / 其它入口的调用无谓失败。
        //
        // 后台路径：这时没人守在手机前，必须由无障碍代点「允许」，
        // 否则无人值守就卡死在授权框上。
        CaptureAutoApprove.armBackground()
        runCatching {
            val manager = getSystemService(MediaProjectionManager::class.java)
            captureLauncher.launch(manager.createScreenCaptureIntent())
        }.onFailure {
            CaptureAutoApprove.disarm()
            Log.e(TAG, "unable to launch capture consent", it)
            finish()
        }
    }

    override fun onDestroy() {
        // 这里**不能** disarm：透明 Activity 失去焦点后仍在等授权结果，
        // 提前关掉时间窗会让无障碍服务没法点确认框。
        // 时间窗本身有 25 秒上限，不需要在这里兜底。
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CaptureRequest"
    }
}
