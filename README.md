![FluxPlayer banner](fastlane/metadata/android/en-US/images/featureGraphic.png)

# FluxPlayer

[![GitHub release (latest SemVer)](https://img.shields.io/github/v/release/Ding-DaoDao/fluxplayer.svg?logo=github&label=GitHub&cacheSeconds=3600)](https://github.com/Ding-DaoDao/fluxplayer/releases/latest)

FluxPlayer 是一款基于 Next Player 二次开发的 Android 视频播放器，使用 Kotlin + Jetpack Compose 构建。在原版基础上集成了弹幕系统、WebDAV 文件浏览、OpenList 远程播放等新功能。

**本项目基于 [Next Player](https://github.com/anilbeesetti/nextplayer) (GPL v3) 二次开发，感谢原作者 [Anil Beesetti](https://github.com/anilbeesetti) 的杰出工作。**

## ✨ 新增功能

| 功能 | 说明 |
|------|------|
| 💬 **弹幕系统** | 支持 Bilibili XML / JSON 弹幕解析、弹弹 play API 搜索下载、手动导入本地弹幕文件 |
| 🔗 **WebDAV 浏览** | 多服务器管理，直接浏览播放远程视频文件 |
| 🌐 **OpenList 集成** | 内置 AList 服务，手机端即可搭建文件分享服务 |
| ⚙️ **WebDAV 设置** | 添加/编辑/删除服务器，支持 Basic 认证、连接测试 |

## 📱 截图

### 媒体选择

<div style="width:100%; display:flex; justify-content:space-between;">

[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width=19% alt="Home Light">](fastlane/metadata/android/en-US/images/phoneScreenshots/1.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width=19% alt="Home Dark">](fastlane/metadata/android/en-US/images/phoneScreenshots/2.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width=19% alt="Sub Folder Light">](fastlane/metadata/android/en-US/images/phoneScreenshots/3.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width=19% alt="Sub Folder Dark">](fastlane/metadata/android/en-US/images/phoneScreenshots/4.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" width=19% alt="Quick Settings">](fastlane/metadata/android/en-US/images/phoneScreenshots/5.png)
</div>

### 播放器界面

<div style="width:100%; display:flex; justify-content:space-between;">

[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/6.png" width=49% alt="Player">](fastlane/metadata/android/en-US/images/phoneScreenshots/6.png)
[<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/7.png" width=49% alt="Player">](fastlane/metadata/android/en-US/images/phoneScreenshots/7.png)
</div>

## 支持的格式

- **视频**: H.263, H.264 AVC, H.265 HEVC, MPEG-4 SP, VP8, VP9, AV1（取决于设备解码能力）
- **音频**: Vorbis, Opus, FLAC, ALAC, PCM/WAVE, MP3, AAC, AC-3, E-AC-3, DTS, DTS-HD, TrueHD（ExoPlayer FFmpeg 扩展）
- **字幕**: SRT, SSA, ASS, TTML, VTT, DVB

## 原版功能

- 极简 Material 3 (You) 原生界面
- 完全免费开源，无广告和多余权限
- H.264/H.265 软解
- 音轨/字幕轨切换
- 垂直滑动调节亮度/音量，水平滑动快进
- 媒体库：树状、文件夹、文件三种视图模式
- URL 播放 / SAF 文件选择
- 播放倍速、外挂字幕、缩放手势
- 画中画模式

## License

FluxPlayer is licensed under the GNU General Public License v3.0. See the [LICENSE](LICENSE) file for more information.

Based on [Next Player](https://github.com/anilbeesetti/nextplayer) by [Anil Beesetti](https://github.com/anilbeesetti).
