# 牛排火锅 FM 网络收音机(RadioFM) - 开源车载网络电台广播 App

基于现代化 Android 技术栈构建的高性能车载网络收音机与电台广播流媒体播放器，专为汽车中控大屏与车机系统深度定制。

## 🌟 核心特性

- 🚗 **汽车方向盘切台与车载中控按键深度适配**：
  - 参考官方 VLC 架构设计了 `PlaylistManager` 与 `PlayerKeyListenerDelegate` 按键分发委托；
  - 深度集成经典与现代 Android `MediaSession` 车载媒体会话协议；
  - 无论车机方控发出的是电台切台键 `KEYCODE_CHANNEL_UP/DOWN` (166/167)、媒体上一曲/下一曲 `KEYCODE_MEDIA_NEXT/PREVIOUS` (87/88)，还是车载私有切歌广播（如 `com.android.music.musicservicecommand`），均可精准拦截并流畅换台；
  - 方向键 `DPAD_UP/DOWN` 特意放行，不参与换台，保证列表焦点与遥控器导航正常；
  - 完善的音频焦点管理（AudioFocus），在导航播报、倒车雷达或来电时自动压低/暂停，播毕自动平滑恢复。

- 🔊 **单一播放内核：全部流统一由 VideoLAN LibVLC 播放**：
  - 不再按格式分流引擎，网络电台、HLS (`.m3u8`)、AAC/MP3、Tvheadend MPEG-TS 一律交由 LibVLC 解码，行为一致、不会因换源换引擎而出现「有的台能响、有的台不响」；
  - **默认不向 LibVLC 声明容器格式**，解封装方式由 libvlc 自带的 demux probe 现场判定（TS 校验 `0x47` 同步字节与 188 字节包对齐、HLS 校验 `#EXTM3U` 与 `application/vnd.apple.mpegurl`、MP4 校验 box 结构）。这正是 VLC 官方客户端能同时直接播放 TS 与 HLS 的机制——**它从不猜格式**，我们也照此实现；
  - 硬解开关交给 `Media.setHWDecoderEnabled()`（官方 3.0.23 源码：开硬解拼 `:codec=<Mediacodec模块>,all`，关硬解拼 `:codec=all`；`all` 是有效伪模块名），不在全局用 `--codec` 指定解码器列表；
  - 播放内核采用 `libvlc-all 3.7.1`（内核 LibVLC 3.0.23 正式版，与官方 VLC 客户端同款），TS/HLS 的解封装统一由内核 demux probe 自动判定；
  - **三级容灾**：自动探测失败 → 强制 `:demux=ts` 重试 → 强制 TS 并转 libavcodec 软解 → 仍失败则延时 3 秒回到自动探测。TS 专用参数只在兜底轮次出现，绝不参与首轮播放；
  - 网络缓冲取 1500 ms（与官方客户端同量级），避免直播流延迟持续堆积；
  - Media3 仅保留 `MediaSession` 车载会话所需的 Player 载体作用，**不参与任何解码**，因此不引入 HLS/RTSP 解封装器等冗余依赖。

- 📻 **全套标准电台流媒体播放**：
  - 原生支持 HTTP / HTTPS 音频直播流、AAC、MP3 及 HLS (`.m3u8`) 广播流；
  - 默认电台播放列表源支持云端动态更新与本地持久化缓存；
  - 播放列表文本解析兼容三种写法：两行制（名称在上、URL 在下）、单行 `名称,URL`、以及 `#genre#` / `[分组]` 分类标签。

- 📍 **车机专用实时定位与天气**：
  - 5 秒高频双轨定位，优先硬件 GPS，隧道地库无信号时平滑回落网络基站，再以 IP 定位兜底；
  - 位移超过 150 米或跨区自动重新逆地理；系统 Geocoder 不可用时自动切换开源在线逆地理引擎，适配无 GMS 车机；
  - 天气数据取自**和风天气实况 API**（`v7/weather/now`，按经纬度查询），15 分钟刷新一次，失败后 1 分钟自动重试；
  - 鉴权采用 **JWT（Ed25519 动态签名）**，App 内置私钥按官方规范即时签发（`iss` 开发者ID / `sub` 项目ID / `kid` 凭据ID），比明文 API Key 更安全；和风新版已限制 API Key 的每日额度，JWT 是官方推荐方式；
  - JWT 三要素与私钥写在 `WeatherLocationHelper.kt` 的 `QWEATHER_ISS` / `QWEATHER_SUB` / `QWEATHER_KID` / `QWEATHER_PRIVATE_KEY_B64` 常量，专属 Host 为 `QWEATHER_API_HOST`；请求失败时显示"天气暂不可用"兜底文案，不再无限停留"天气获取中"。

<details>
<summary>Fork 后改成自己的和风天气（点击展开）</summary>

1. 注册[和风天气控制台](https://console.qweather.com/)，新建项目，记下**项目ID**（填入 `QWEATHER_SUB`）；
2. 在「设置」页查看你的**开发者ID**（Q 开头，填入 `QWEATHER_ISS`）；
3. 用 `openssl genpkey -algorithm ED25519 -out ed25519-private.pem` 生成密钥对，`openssl pkey -pubout -in ed25519-private.pem > ed25519-public.pem`；
4. 在项目下「创建凭据」→ 身份认证选 **JSON Web Token** → 粘贴公钥内容 → 保存，记下**凭据ID**（填入 `QWEATHER_KID`）；
5. 把私钥 PEM 的首尾标记和换行去掉，只留 Base64 主体，填入 `QWEATHER_PRIVATE_KEY_B64`；
6. API Host 在控制台项目/账号设置中查看，填入 `QWEATHER_API_HOST`。

> 常见 401 排查：JWT 的 Payload 必须**同时**包含 `iss`（开发者ID）和 `sub`（项目ID），缺 `iss` 会鉴权失败；`kid` 必须是凭据ID 而非项目ID；三处 ID 与私钥公钥必须在同一和风账号下配对。可用控制台的「JWT 验证」工具粘贴 Token 自查。

</details>

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

> ℹ️ 安装包内含 VideoLAN LibVLC 的 `armeabi-v7a` 与 `arm64-v8a` 两套 `.so` 动态库，体积较早期纯 Kotlin 版本明显增大，具体大小请以 Releases 页面显示为准。

---

## 🛠️ 技术架构

- **语言与工具链**：Kotlin 1.9.22、Java 17、AGP 8.2.2、Gradle 8.5
- **编译目标**：compileSdk 34 / targetSdk 34 / minSdk 21（Android 5.0+），仅打包 `armeabi-v7a`、`arm64-v8a`
- **核心组件**：
  - `org.videolan.android:libvlc-all`（**唯一播放引擎**：全格式解封装、软硬解容灾）
  - `androidx.media3:media3-session`（车载媒体会话协议）
  - `androidx.media3:media3-exoplayer`（仅提供 MediaSession 所需的 Player 载体，不参与解码）
  - `androidx.media:media`（经典车载 Media 兼容）
  - `androidx.appcompat`（DayNight 夜间模式支持）
  - `androidx.recyclerview`（电台列表高效渲染）

## 📁 代码结构

```
app/src/main/java/io/microshow/fm/
├── data/RadioRepository.kt            电台源拉取、文本解析、URL 兼容处理
├── model/RadioChannel.kt              频道数据模型
├── player/PlaylistManager.kt          VLC 风格播放列表与循环切台
├── player/RadioPlayerService.kt       前台服务：LibVLC 播放、MediaSession、AudioFocus、通知栏
├── ui/MainActivity.kt                 主界面：时钟、定位天气、频道列表
├── ui/ChannelAdapter.kt               频道列表适配器
├── ui/PlayerKeyListenerDelegate.kt    车机按键拦截与防抖
└── util/WeatherLocationHelper.kt      GPS / 网络 / IP 三级定位与天气
```

---

- 'QQ群:887679093'
