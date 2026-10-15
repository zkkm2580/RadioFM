# 畅听FM (RadioFM) - 开源电台广播 App

基于现代化 Android 技术栈复刻的低延迟网络收音机与电台广播流媒体播放器。

## 特性
- **低延迟流媒体内核**：集成 Google 官方 AndroidX Media3 (ExoPlayer)，原生支持 HLS (M3U8)、HTTP/HTTPS MP3/AAC、RTSP 网络广播流。
- **后台保活播放**：基于 Android 前台服务（Foreground Service）与 Notification 系统控制，熄屏播放不中断，支持耳机拔出自动暂停。
- **开箱即用**：内置国家台（中国之声、经济之声、音乐之声、经典音乐广播、中国交通广播）及主流省市台广播直链。
- **云端全自动构建**：配置了 GitHub Actions，提交代码即可自动在云端打包成 APK。

## 如何上传到 GitHub 并自动生成 APK

### 1. 在 GitHub 创建空仓库
打开 [GitHub](https://github.com/new)，新建一个仓库（例如命名为 `RadioFM`）。

### 2. 在本地推送代码
在当前项目根目录打开终端（PowerShell），执行以下命令：

```powershell
git init
git add .
git commit -m "feat: 初次提交畅听FM完整工程代码与自动构建工作流"
git branch -M main
git remote add origin https://github.com/你的GitHub用户名/RadioFM.git
git push -u origin main
```

### 3. 获取编译好的 APK
1. 打开 GitHub 仓库页面，点击上方的 **Actions**。
2. 看到 **Build Android APK** 流程变为绿色对勾后点击进入。
3. 在底部 **Artifacts** 区域直接下载 **畅听FM-Debug-APK**。
