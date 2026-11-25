# 远程控制（android-remote-control）

Android 手机之间的远程控制系统：A 手机通过 WebRTC 看到 B 手机的实时画面，并能下发触摸/手势，实现远程操作。

单 APK 双角色：每台设备同时可被控也可控别人，身份由「谁输入了对方的配对码」决定。

## 组成

| 目录 | 说明 |
|---|---|
| `app/` | Android 客户端（Kotlin，单 APK 双角色） |
| `server/` | 信令服务（TypeScript + WebSocket，端口 8787） |
| `deploy/` | 一键部署脚本、systemd/coturn/nginx 配置模板，详见 [deploy/README.md](deploy/README.md) |

## 功能特性

- **实时画面**：短边 ≥720 的高清采集，MAINTAIN_RESOLUTION 降级策略保证文字不糊；空闲时自动降到 1fps 省电档
- **手势控制**：触摸、滑动、长按，坐标按画面内容区映射
- **被控状态角标**：被控时屏幕顶部显示红色「被控制」，会话结束显示「控制已关闭」
- **沉浸式会话画面**：被控端整块屏 1:1 呈现，底部导航不被遮挡
- **保活**：电池优化白名单 + Wi-Fi 锁 + 保活闹钟，熄屏未充电也可在线
- **无障碍**：用于远程注入与锁屏场景的辅助

## 使用步骤

### 1. 部署信令服务

在一台有公网 IP 的服务器上（详见 [deploy/README.md](deploy/README.md)）：

```bash
scp -r . root@<服务器IP>:/opt/remote-control-src
ssh root@<服务器IP>
cd /opt/remote-control-src
PUBLIC_IP=<服务器公网IP> bash deploy/deploy.sh
```

完成后记录打印的**服务器地址**和**访问令牌**。安全组需放行 `TCP 8787`、`TCP/UDP 3478`、`UDP 49160-49200`。

### 2. 安装 App

构建（需要 JDK 21）：

```bash
./gradlew -Dorg.gradle.java.home=<JDK21路径> :app:assembleDebug
```

产物在 `app/build/outputs/apk/debug/remote-control.apk`，安装到两台手机。
服务器地址和令牌可在 App 的「设置」页填写（也支持编译时预置）。

### 3. 配对连接

1. 两台手机首次注册后，服务端各分配一个**两位配对码**（设备页可查看）
2. 控制端在**首页**输入对方的配对码 → 点连接
3. 被控端同意后自动建立 WebRTC 连接；首次被控需在本机点一次「授权屏幕采集」

## 已知限制

- 部分国产 ROM（Android 16 实测）会把屏幕采集授权框的「允许」按钮从无障碍树中移除，自动授权在该类机型上不可用，需手动点击
- 荣耀等 ROM 需手动关闭「应用启动管理」的自动管理，否则熄屏后进程被冻结、保活失效
- App 重装后无障碍服务和屏幕采集授权会被系统清除，需重新开启

## 安全

- 信令令牌放在 `server/.env`（不入库，已 gitignore），App 侧预置在 BuildConfig
- 屏幕采集走系统 MediaProjection 授权，一次授权长期有效
