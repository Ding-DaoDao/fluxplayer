# 设置页面清理与行为修复

日期：2026-10-17。

## 页面与代码

- 将解码器优先级并入「播放器 → 高级播放」，移除设置首页独立入口和原页面、导航、ViewModel；保留旧导航路径映射，避免旧页面状态恢复失败。
- 听书配置通过入口跳转到统一音频设置，移除重复音频焦点开关。
- 删除未使用的 FastSeek 模型、FastSeek 与 ControlButtonsPosition 显示扩展，以及无入口的主题弹窗分支。
- 移除不再使用的 useLongPressControls、danmakuCacheMap、latestFootprintPerDir 配置字段，连同旧足迹写入和清理流程；旧配置由序列化器忽略未知字段，保留有效配置。
- 媒体库设置直接订阅应用偏好；下载路径只从偏好读取；音频、字幕、手势、播放器设置合并重复更新封装。

## 行为

- 全部玻璃效果关闭或系统不支持时，玻璃参数滑块禁用；色散还依赖扭曲大于零。开启底栏玻璃同时启用悬浮胶囊底栏，普通底栏禁用悬浮样式选择。
- 视频和在线听书服务均在启动时读取「拔出耳机暂停」，运行中修改也立即更新播放器行为。
- 已创建的字幕视图同步更新字幕样式、内嵌样式开关与字号，并监听系统字幕样式和字体缩放；系统样式在画中画中仍使用系统字号比例。
- 「重置设置」先迁移旧听书进度，再重置应用和播放器偏好，不清除数据库进度。新增独立的「清除本地听书进度」入口及确认框，明确区分两个操作。

## 验证

- 共存 APK 及其测试 APK 构建成功，已安装到连接的 Android 14 小米手机；共存包名为 `com.fluxplayer.app.uipreview`。
- `ktlintCheck` 通过。
- `:core:data:testDebugUnitTest` 和 `:feature:player:testDebugUnitTest` 共 47 项单元测试通过。
- 数据模块设备测试 11 项通过：设置回归 3 项、现有播放可靠性回归 8 项。覆盖旧字段兼容、重置前迁移与保留进度、独立清除进度。
- 应用设备测试 6 项通过：两个播放服务的耳机开关 1 项、字幕 2 项、设置页面 3 项。
- 设置页面测试通过真实 MainActivity 承载测试内容，使用系统调试启动入口兼容小米对测试包跨包启动的限制；界面偏好使用内存替身，未执行真实设置重置或清除进度。
- 已查看设置首页、播放器高级播放、关闭玻璃效果后的外观页面截图；截图在 `build/qa/settings/`，测试日志在仓库根目录的 `settings-cleanup-*.log`，均为本地构建产物。

```powershell
.\gradlew.bat :app:assembleUiPreview :app:assembleUiPreviewAndroidTest -PtestBuildType=uiPreview --offline
.\gradlew.bat ktlintCheck :core:data:testDebugUnitTest :feature:player:testDebugUnitTest --offline
adb shell am instrument -w -r -e class com.fluxplayer.app.HeadsetPreferenceRegressionTest,com.fluxplayer.app.feature.player.ui.SubtitlePreferencesRegressionTest,com.fluxplayer.app.settings.SettingsCleanupUiTest com.fluxplayer.app.uipreview.test/androidx.test.runner.AndroidJUnitRunner
```

耳机回归检查播放器实际接收的拔出耳机事件开关；字幕回归检查实际字幕视图属性。未使用物理耳机拔插或视频字幕素材进行人工试听、观看。
