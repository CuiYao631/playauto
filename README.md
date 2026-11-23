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
- **盒子固件升级**：从 `api.paplink.cn` 检查并下载固件，经 USB 下发到盒子，实时显示进度。
- **统一升级页面**：盒子固件与软件更新共用全屏进度页，状态、配色与退出规则一致。
- **调试日志窗口**：悬浮窗实时显示盒子上报的命令（视频/音频/副屏帧按秒汇总），
  设置 → 更多设置 → 调试。
- **高级功能**：连续点击版本号四次解锁，含 Root 语音禁用等。

## 使用

1. 安装应用，连接 USB 盒子（VID `0x1314`，PID `0x1520` / `0x1521`）。
2. 授予 USB 权限，按需授予麦克风权限。
3. 通过盒子连接手机，等待进入 CarPlay。
4. 设置 → 点击 **盒子版本** 可检查并升级盒子固件；升级时自动进入全屏进度页，成功后
   自动向盒子下发重启指令（`CMD 206`），盒子重新连接后自动退出（升级中请勿断电）。

错误日志：**设置 → 更多设置 → 错误日志**。

## 相关文档

- `docs/box-protocol.md`：盒子 USB 协议完整命令表与帧格式
- `postman/`：固件接口的 Postman 集合与测试说明

## 构建

需要 Java 17、Android SDK 35、Android Gradle Plugin 8.9.2。

```sh
gradle assembleDebug        # APK: app/build/outputs/apk/debug/
./build-apk.sh              # APK: build/manual/AutoPlayReplacement-debug.apk
```

`build-apk.sh` 会为每次构建生成：

- `versionName`：`YYYY.MM.DD.HHMM`（东八区时间）
- `versionCode`：`YYMMDDHH`（8 位，单调递增且不溢出 int）

## 发布更新

在线更新读取仓库根目录的 `update.json`：

```json
{
  "version": "2026.11.13.1700",
  "versionCode": 2,
  "url": "https://github.com/<owner>/<repo>/releases/latest/download/AutoPlayReplacement-debug.apk",
  "notes": "更新说明"
}
```

发布时必须让 `version` **大于** 已安装版本的 `versionName`。客户端判断规则：

- 两侧 `version` 都符合 `YYYY.MM.DD.HHMM` → 以版本号字符串为准（推荐，构建脚本自动生成）
- 任一格式不符 → 退回比较 `versionCode`

> 历史问题：`versionCode` 曾硬编码为 1，而 `update.json` 写 2，导致新构建的版本被提示「升级」到更旧的版本。现已改为以 `versionName` 为主、`versionCode` 仅兜底；`build-apk.sh` 也会动态生成 `versionCode`。

## 签名

所有构建**必须**使用同一份密钥，否则 Android 会拒绝覆盖安装（应用内更新提示
「签名不一致」/ `INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。

- 固定密钥：`keys/playauto.keystore`（别名 `androiddebugkey`，口令 `android`），
  已随仓库提交，CI 与本地共用
- 可用环境变量覆盖：`PLAYAUTO_KEYSTORE` / `PLAYAUTO_KEY_ALIAS` /
  `PLAYAUTO_KS_PASS` / `PLAYAUTO_KEY_PASS`
- 密钥丢失或更换后，已安装的应用必须先卸载才能重装；请自行备份该文件

> 历史问题：脚本原先在 `build/debug.keystore` 不存在时自动生成密钥，而 `build/`
> 与 `*.keystore` 都被 gitignore，导致 **CI 每次构建都生成新密钥**，每个 release
> 的证书都不同（曾实测到 3 个不同指纹），在线更新必然失败。
