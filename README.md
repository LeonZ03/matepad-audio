# matepad-audio

让连接无扬声器显示器的 Huawei MatePad 继续使用平板内置扬声器。

部分显示器虽然没有扬声器，却会通过 USB-C DisplayPort Alt Mode 或 HDMI 的
EDID 声明音频能力。HarmonyOS 随后把媒体音频自动路由到显示器，而且普通设置
界面无法切回平板扬声器。本项目使用 Android 的系统播放捕获能力读取允许捕获的
媒体/游戏 PCM，再将其定向回放到内置扬声器。

> [!IMPORTANT]
> 这是面向特定 HarmonyOS 音频路由问题的实验性工具，不是通用音频驱动。
> 已在 Huawei MatePad TGR-W10、HarmonyOS 4.2（Android API 31）及全功能
> USB-C 显示器连接下验证。其他华为平板可以直接安装尝试，但尚未验证，
> 不保证系统会接受将回送音轨指定到内置扬声器。

## 下载

- [下载 MatePadAudioLoopback v0.1.0 APK](https://github.com/LeonZ03/matepad-audio/releases/download/v0.1.0/MatePadAudioLoopback-v0.1.0.apk)
- [查看全部 GitHub Releases](https://github.com/LeonZ03/matepad-audio/releases)

发布文件校验信息：

```text
APK SHA-256: A1FF6643070105662D41196B924C3B3626B32063D10946102B219487BD4C75B1
签名证书 SHA-256: 2AF5A28D6B8F2306579E9525CDE5A86DD0888E42C69669D5115F371D289FAAD1
```

## 功能

- 保持视频输出到外接显示器，同时从平板内置扬声器播放允许捕获的媒体声音。
- 回送音轨与原始显示器音轨使用不同的 Android 音频用途，避免华为系统同时把
  两条音轨路由到扬声器而产生回音。
- 实体媒体音量键通过软件 PCM 增益控制回送音量。
- 使用前台服务、常驻通知和部分唤醒锁，避免进入视频应用后被 HarmonyOS 冻结。
- 不联网、不保存音频、不申请文件访问权限。

## 工作原理

```text
媒体/游戏应用
    │
    ├── 原始音轨 ──→ USB-C DP/HDMI 显示器音频端点
    │
    └── AudioPlaybackCapture
            │ 48 kHz / 16-bit / stereo PCM
            │ 软件媒体音量增益
            └── AudioTrack (accessibility usage)
                    └── setPreferredDevice(内置扬声器)
```

关键实现：

- `app/src/dev/codex/matepadaudio/MainActivity.java`：权限和系统捕获确认界面。
- `app/src/dev/codex/matepadaudio/AudioLoopback.java`：PCM 捕获、音量处理和扬声器输出。
- `app/src/dev/codex/matepadaudio/AudioLoopbackService.java`：前台服务、通知及唤醒锁。
- `app/AndroidManifest.xml`：应用权限和组件声明。

回送使用 `USAGE_ASSISTANCE_ACCESSIBILITY`，是因为测试设备的华为音频策略会在
出现一个定向到扬声器的 `USAGE_MEDIA` 音轨后，把原始媒体音轨也迁移到扬声器，
导致原声与延迟回送叠加。辅助功能用途可以保持原始媒体音轨留在显示器端点。

## 权限与隐私

| 权限 | 用途 |
| --- | --- |
| `RECORD_AUDIO` | Android 播放捕获 API 的必要权限；读取系统允许捕获的内部媒体 PCM。 |
| `FOREGROUND_SERVICE` | 视频应用位于前台时持续运行音频回送。 |
| `WAKE_LOCK` | 回送期间防止音频搬运线程因休眠或省电策略暂停。 |

应用没有网络或存储权限。音频只存在于固定大小的内存缓冲区中，处理后立即写入
扬声器音轨；代码不创建录音、缓存、数据库或运行日志文件。

Android 仍会正常创建 APK 安装数据、DEX 编译缓存和通知频道配置。这些是固定的
系统管理数据，不会随播放时间持续增长。

## 兼容性

### 已验证

- Huawei MatePad TGR-W10
- HarmonyOS 4.2 / Android 12（API 31）
- 全功能 USB-C 线连接采用 DisplayPort Alt Mode 的显示器
- 哔哩哔哩 HD、QQ 音乐等允许系统捕获播放音频的应用

### 理论要求

- Android 10 或更高版本，因为 `AudioPlaybackCapture` 从 Android 10 开始提供。
- 设备音频策略必须接受 `AudioTrack.setPreferredDevice()` 指定内置扬声器。
- 来源应用必须允许播放捕获。

### 其他设备

- 应用不依赖 ADB、Root、Shizuku 或特定 CPU 架构；安装后只需授予录音权限和
  Android 系统的媒体捕获授权。
- HarmonyOS 3/4 的较新 MatePad 在满足上述要求时可以尝试，但不同型号、系统
  版本和音频 HAL 的路由策略可能不同，能安装不等于一定能回送成功。
- `v0.1.0` 的安装清单仍声明最低 Android 9（API 28），但核心播放捕获 API 实际
  要求 Android 10（API 29）；Android 9 即使允许安装也不受支持，可能无法启动回送。
- 目前唯一经过实际验证的组合是 TGR-W10 + HarmonyOS 4.2。欢迎通过 GitHub
  Issues 反馈型号、系统版本、连接方式和测试结果。

### 已知限制

- DRM、受保护视频以及主动禁止播放捕获的应用可能完全没有回送声音。
- 当前音频格式固定为 48 kHz、16-bit、立体声；它不是 bit-perfect 输出。
- 回送需要缓冲，因此相对画面存在额外延迟。
- 当前音量算法在约 40% 媒体音量时使用 1.0 倍 PCM 增益，最高限制为 2.5 倍。
  高电平音源在高音量时理论上可能发生削波。
- 前台服务和唤醒锁会增加耗电；不使用时应点击“停止回送”。
- `targetSdkVersion` 暂时保留为 28，以保持已验证 HarmonyOS 设备上的兼容行为。
  本项目面向侧载测试，不满足现代应用商店的目标 SDK 要求。
- 不保证其他品牌、HarmonyOS/Android 版本或音频 HAL 的行为一致。

## 构建

### 环境

- Windows 10/11
- PowerShell 5.1 或 PowerShell 7+
- JDK 17 或更高版本，且 `java.exe`、`javac.exe`、`jar.exe` 和 `keytool.exe`
  位于 `PATH`
- 首次构建需要访问 `dl.google.com`

项目不提交 Android SDK、设备系统文件或签名密钥。`build.ps1` 会下载并校验：

- Android SDK Build-Tools 35.0.1 for Windows
- Android SDK Platform 31

首次运行前请阅读并接受
[Android SDK License](https://developer.android.com/studio/terms)，然后执行：

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\build.ps1 -AcceptAndroidSdkLicense
```

依赖会缓存在 `.build-deps/`，后续构建可直接执行：

```powershell
.\build.ps1
```

构建结果位于：

```text
out/MatePadAudioLoopback-debug.apk
```

脚本会在本机生成一个仅用于开发测试的 debug keystore。该密钥保存在被
`.gitignore` 排除的 `.build-deps/signing/` 中。正式发布时应使用独立保管的发布
密钥签名，不应发布或共享私钥。

## 安装与使用

推荐从 [GitHub Releases](https://github.com/LeonZ03/matepad-audio/releases)
下载正式签名的 APK，然后在平板上直接打开安装；也可以使用 Android Platform
Tools 中的 ADB 安装：

```powershell
adb install -r .\MatePadAudioLoopback-v0.1.0.apk
```

`build.ps1` 生成的是使用每台电脑本地 debug key 签名的开发 APK。它适合源码验证，
但签名与 GitHub Release 不同，不能直接覆盖安装正式版本；切换签名时需要先卸载
原应用，这也会清除应用数据和授权。

使用步骤：

1. 连接外接显示器。
2. 打开“MatePad 扬声器回送”。
3. 点击“开始回送到平板扬声器”。
4. 允许录音权限，并在 Android 系统投屏/捕获确认窗口中选择立即开始。
5. 看到“MatePad 扬声器回送运行中”常驻通知后，切换到视频或音乐应用。
6. 不使用时返回应用并点击“停止回送”。

每次重新启动回送都需要 Android 重新确认媒体捕获授权，这是系统安全限制。

## 故障排查

### 已启动但没有声音

- 确认来源应用允许播放捕获；先用普通、非 DRM 的视频或音乐测试。
- 确认媒体音量不为 0。
- 停止后重新开始，并重新同意 Android 系统捕获窗口。
- 显示器断开或重新连接后，建议重启一次回送。

### 切到视频应用后过一段时间没有声音

确认通知栏存在“MatePad 扬声器回送运行中”。如果设备仍会强制终止前台服务，
可在 HarmonyOS 的应用启动管理/电池优化中允许该应用后台活动。

### 听到回音或两路错开的声音

确认使用的是当前源码构建。早期实验版本曾使用 `USAGE_MEDIA` 回放，在部分华为
设备上会促使系统同时把原始音轨迁移到平板扬声器。

## 安全与发布说明

- 仓库不应包含 `framework-res.apk`、Android SDK 压缩包、APK 签名密钥或真实
  设备日志；这些路径已经写入 `.gitignore`。
- GitHub 源码提交建议只包含源码、文档和构建脚本。
- 编译后的正式 APK 通过 GitHub Releases 发布，不直接提交到 Git 历史。
- 发布者必须妥善保管签名密钥；后续版本需要使用同一证书签名才能覆盖升级。
- 安装第三方构建产物前，应核对源码、签名和发布者。

## License

本项目采用 [MIT License](LICENSE)。
