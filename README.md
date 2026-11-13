# PlayAuto

AutoPlay 车载盒子 Android 应用，提供 CarPlay 投屏、音频播放和触控操作。

- 最低系统：Android 4.4（API 19）
- 目标版本：Android 15（API 35）
- 包名：`cn.autoplay.replacement`

## 主要功能

- **USB 连接**：自动发现、权限申请和协议心跳。
- **视频解码**：MediaCodec 硬解（默认）/ OpenH264 软解，25fps、DPI 160。
- **音频与控制**：PCM 播放、麦克风上行、媒体按键、方控学习（含长安 CS35Plus 协议）。
- **后台连接**：前台服务维持连接和音频。
- **界面**：深色/浅色/跟随系统主题，夜间模式支持大灯联动（长安车机）。
- **高级功能**：连续点击版本号四次解锁，含 Root 语音禁用等。

## 使用

1. 安装应用，连接 USB 盒子（VID `0x1314`，PID `0x1520` / `0x1521`）。
2. 授予 USB 权限，按需授予麦克风权限。
3. 通过盒子连接手机，等待进入 CarPlay。

错误日志：**设置 → 更多设置 → 错误日志**。

## 构建

需要 Java 17、Android SDK 35、Android Gradle Plugin 8.9.2。

```sh
gradle assembleDebug        # APK: app/build/outputs/apk/debug/
./build-apk.sh              # APK: build/manual/AutoPlayReplacement-debug.apk
```
