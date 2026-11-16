# 牛排火锅 FM 网络收音机(RadioFM) - 开源车载网络电台广播 App

基于现代化 Android 技术栈构建的高性能车载网络收音机与电台广播流媒体播放器，专为汽车中控大屏与车机系统深度定制。

## 🌟 核心特性

- 🚗 **汽车方向盘切台与车载中控按键深度适配**：
  - 参考官方 VLC 架构设计了 `PlaylistManager` 与 `PlayerKeyListenerDelegate` 按键分发委托；
  - 深度集成经典与现代 Android `MediaSession` 车载媒体会话协议；
  - 无论车机方控发出的是电台切台键 `KEYCODE_CHANNEL_UP/DOWN` (166/167)、媒体上一曲/下一曲 `KEYCODE_MEDIA_NEXT/PREVIOUS` (87/88)，还是车载私有切歌广播（如 `com.android.music.musicservicecommand`），均可精准拦截并流畅换台；
  - 方向键 `DPAD_UP/DOWN` 特意放行，不参与换台，保证列表焦点与遥控器导航正常；
  - 完善的音频焦点管理（AudioFocus），在导航播报、倒车雷达或来电时自动压低/暂停，播毕自动平滑恢复。

- 🔊 **双引擎播放内核，按流自动路由**：
  - **AndroidX Media3 (ExoPlayer)** —— 常规网络电台、HLS (`.m3u8`) 与标准 AAC/MP3 流，轻量省电；
  - **VideoLAN LibVLC 3.x** —— Tvheadend 直通输出与 MPEG-TS 封装的广播流，负责 TS 解封装与 MP2/MPEG-2 音频解码；
  - **两级容灾**：ExoPlayer 播放失败时自动降级到 LibVLC；LibVLC 硬解遇不兼容音频时自动回落软解，最大限度保证出声；
  - 引擎路由只依据流的封装特征，不绑定任何具体服务器的端口与路径：

    | 流的特征 | 走哪条引擎 |
    | --- | --- |
    | 含 `profile=pass` 参数 | LibVLC（Tvheadend 直通输出） |
    | 路径以 `.ts` 结尾 | LibVLC（MPEG-TS 封装） |
    | 其余（含 `.m3u8`、AAC、MP3） | ExoPlayer |

- 📻 **全套标准电台流媒体播放**：
  - 原生支持 HTTP / HTTPS 音频直播流、AAC、MP3 及 HLS (`.m3u8`) 广播流；
  - 默认电台播放列表源支持云端动态更新与本地持久化缓存；
  - 播放列表文本解析兼容三种写法：两行制（名称在上、URL 在下）、单行 `名称,URL`、以及 `#genre#` / `[分组]` 分类标签。

- 📍 **车机专用实时定位与天气**：
  - 5 秒高频双轨定位，优先硬件 GPS，隧道地库无信号时平滑回落网络基站，再以 IP 定位兜底；
  - 位移超过 150 米或跨区自动重新逆地理；系统 Geocoder 不可用时自动切换开源在线逆地理引擎，适配无 GMS 车机；
  - 天气数据取自 Open-Meteo，15 分钟刷新一次。

- 🌙 **深色 / 夜间模式自动跟随系统**：
  - 内置 DayNight 双套主题与色彩规范；
  - 汽车大灯开启或系统进入夜间模式时，界面毫秒级自动切换为深色护眼模式，夜间行车不刺眼。

- 🔔 **车载标准媒体通知栏与后台前台保活**：
  - 适配 Android 8.0 至 Android 14+ 前台媒体播放服务（`mediaPlayback` 类型）；
  - 提供标准车载大卡片通知栏控制（上一台、暂停/播放、下一台一键操作）。

- 🚀 **云端全自动构建与发布（CI/CD）**：
  - 配置 GitHub Actions 工作流，代码提交自动编译 Debug APK 并发布至 GitHub Releases 与 Artifacts。

---

## 📥 下载与安装

您可以直接前往仓库的 [Releases 页面](https://github.com/zkkm2580/RadioFM/releases) 下载最新编译好的 APK 安装包：

- **最新稳定版下载**：[FM网络收音机.apk](https://github.com/zkkm2580/RadioFM/releases/latest)

> ℹ️ 自 1.2 版引入 VideoLAN LibVLC 原生核心后，安装包内含 `armeabi-v7a` 与 `arm64-v8a` 两套 `.so` 动态库，体积较早期纯 Kotlin 版本明显增大，具体大小请以 Releases 页面显示为准。

---

## 🛠️ 技术架构

- **语言与工具链**：Kotlin 1.9.22、Java 17、AGP 8.2.2、Gradle 8.5
- **编译目标**：compileSdk 34 / targetSdk 34 / minSdk 21（Android 5.0+），仅打包 `armeabi-v7a`、`arm64-v8a`
- **核心组件**：
  - `androidx.media3:media3-exoplayer` + `media3-exoplayer-hls` + `media3-exoplayer-rtsp`（流媒体引擎）
  - `androidx.media3:media3-session`（车载媒体会话协议）
  - `androidx.media:media`（经典车载 Media 兼容）
  - `org.videolan.android:libvlc-all`（Tvheadend / MPEG-TS 解封装与软硬解容灾）
  - `androidx.appcompat`（DayNight 夜间模式支持）
  - `androidx.recyclerview`（电台列表高效渲染）

## 📁 代码结构

```
app/src/main/java/io/microshow/fm/
├── data/RadioRepository.kt            电台源拉取、文本解析、URL 兼容处理
├── model/RadioChannel.kt              频道数据模型
├── player/PlaylistManager.kt          VLC 风格播放列表与循环切台
├── player/RadioPlayerService.kt       前台服务：双引擎路由、MediaSession、AudioFocus、通知栏
├── ui/MainActivity.kt                 主界面：时钟、定位天气、频道列表
├── ui/ChannelAdapter.kt               频道列表适配器
├── ui/PlayerKeyListenerDelegate.kt    车机按键拦截与防抖
└── util/WeatherLocationHelper.kt      GPS / 网络 / IP 三级定位与天气
```

---

- 'QQ群:887679093'
