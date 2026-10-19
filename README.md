# 牛排火锅 FM 网络收音机(RadioFM) - 开源车载网络电台广播 App

基于现代化 Android 技术栈构建的高性能、低延迟车载网络收音机与电台广播流媒体播放器，专为汽车中控大屏与车机系统深度定制。

## 🌟 核心特性

- 🚗 **汽车方向盘切台与车载中控按键深度适配**：
  - 参考官方 VLC 架构设计了 `PlaylistManager` 与 `PlayerKeyListenerDelegate` 按键分发委托；
  - 深度集成经典与现代 Android `MediaSession` 车载媒体会话协议；
  - 无论车机方控发出的是电台切台键 `KEYCODE_CHANNEL_UP/DOWN` (166/167)、媒体上一曲/下一曲 `KEYCODE_MEDIA_NEXT/PREVIOUS` (87/88)，还是车载私有切歌广播（如 `com.android.music.musicservicecommand`），均可精准拦截并流畅换台；
  - 完善的音频焦点管理（AudioFocus），在导航播报、倒车雷达或来电时自动压低/暂停，播毕自动平滑恢复。

- ⚡ **极致轻量化与高能效内核（安装包仅 ~8.9 MB）**：
  - 基于 Google 官方高能效 AndroidX Media3 (ExoPlayer) 核心框架打造；
  - 移除笨重的第三方 C/C++ 动态库依赖，启动秒开，内存占用极低，长途行驶后台播放稳定不发热、不杀后台。

- 📻 **全套标准电台流媒体播放**：
  - 原生支持 HTTP / HTTPS 音频直播流、AAC、MP3 及 HLS (`.m3u8`) 广播流；
  - 默认电台播放列表源：支持云端动态更新与智能本地持久化缓存。

- 🌙 **深色 / 夜间模式自动跟随系统**：
  - 内置 DayNight 双套主题与色彩规范；
  - 汽车大灯开启或系统进入夜间模式时，界面毫秒级自动切换为深色护眼模式，夜间行车不刺眼。

- 🔔 **车载标准媒体通知栏与后台前台保活**：
  - 适配 Android 8.0 至 Android 14+ 前台媒体播放服务；
  - 提供标准车载大卡片通知栏控制（上一台、暂停/播放、下一台一键操作）。

- 🚀 **云端全自动构建与发布（CI/CD）**：
  - 配置 GitHub Actions 工作流，代码提交自动编译 Debug APK 并发布至 GitHub Releases 与 Artifacts。

---

## 📥 下载与安装

您可以直接前往仓库的 [Releases 页面](https://github.com/zkkm2580/RadioFM/releases) 下载最新编译好的 APK 安装包：

- **最新稳定版下载**：[FM网络收音机.apk](https://github.com/zkkm2580/RadioFM/releases/latest)（大小仅 ~8.9 MB）

---

## 🛠️ 技术架构

- **语言与工具链**：Kotlin 1.9+, Java 17, Gradle 8.5
- **核心组件**：
  - `androidx.media3:media3-exoplayer` (流媒体引擎)
  - `androidx.media3:media3-session` (车载媒体协议)
  - `androidx.appcompat` (DayNight 夜间模式支持)
  - `androidx.recyclerview` (电台列表高效渲染)
QQ群:887679093
