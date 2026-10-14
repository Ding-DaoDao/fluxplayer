# 新增设置执行路径核对

本次为代码执行路径检查及编译检查，未运行自动化测试或手机交互测试。表中的“接通”表示已找到持久化、读取及执行消费者，不代表所有手机、媒体和语言都已实测。

偏好通过 DataStore 的 JSON 序列化保存；设置界面监听保存结果，不以本地按钮状态代替实际偏好。冷启动入口改为等待磁盘值，避免把 StateFlow 初始默认值当成用户选择。

| 设置 | 实际作用与条件 | 执行入口 |
| --- | --- | --- |
| 明暗模式、纯黑背景 | 主应用主题与背景层实际切换；纯黑只在深色模式生效 | [MediaTheme.kt](E:/Projects/fluxplayer/core/ui/src/main/java/com/fluxplayer/app/core/ui/theme/MediaTheme.kt:43) |
| 壁纸动态取色 | Android 12+ 从系统配色资源取色；旧系统禁用入口 | [MediaTheme.kt](E:/Projects/fluxplayer/core/ui/src/main/java/com/fluxplayer/app/core/ui/theme/MediaTheme.kt:5) |
| 预设／自定义颜色、九种配色风格 | 保存颜色和算法；选择颜色或风格时切换到自定义配色，主题引擎重新生成颜色 | [MediaTheme.kt](E:/Projects/fluxplayer/core/ui/src/main/java/com/fluxplayer/app/core/ui/theme/MediaTheme.kt:36) |
| 底栏液态玻璃 | Android 13+；开启时启用悬浮胶囊底栏，录入页面背景后模糊与折射 | [MediaPickerScreen.kt](E:/Projects/fluxplayer/feature/videopicker/src/main/java/com/fluxplayer/app/feature/videopicker/screens/mediapicker/MediaPickerScreen.kt:1114) |
| 顶栏液态玻璃 | Android 13+；只作用于录入区域外顶栏，区域内控件不采样自身背景 | [TopAppBar.kt](E:/Projects/fluxplayer/core/ui/src/main/java/com/fluxplayer/app/core/ui/components/TopAppBar.kt:32) |
| 模糊、折射强度、通透度、色散、恢复默认 | 直接传入玻璃渲染参数；需要对应玻璃区域开启才有视觉效果，折射为零时色散不适用 | [MediaGlass.kt](E:/Projects/fluxplayer/core/ui/src/main/java/com/fluxplayer/app/core/ui/theme/MediaGlass.kt:32) |
| 底栏样式与悬浮开关 | 实际选择胶囊／通栏布局，普通底栏也复用统一通栏实现 | [MediaPickerScreen.kt](E:/Projects/fluxplayer/feature/videopicker/src/main/java/com/fluxplayer/app/feature/videopicker/screens/mediapicker/MediaPickerScreen.kt:441) |
| 导航切换动画 | 主导航、设置转场、根页面、浏览来源切换和所有底栏模式读取开关 | [MainActivity.kt](E:/Projects/fluxplayer/app/src/main/java/com/fluxplayer/app/MainActivity.kt:219) |
| 底栏拖动切换 | 仅悬浮胶囊支持；关闭时不注册横向拖动识别，其他布局禁用设置入口 | [FluxNavBar.kt](E:/Projects/fluxplayer/core/ui/src/main/java/com/fluxplayer/app/core/ui/components/FluxNavBar.kt:291) |
| 隐藏状态栏 | 主应用窗口实际隐藏／显示；主题切换与恢复前台时重新应用，播放器窗口独立 | [MainActivity.kt](E:/Projects/fluxplayer/app/src/main/java/com/fluxplayer/app/MainActivity.kt:124) |
| 启动页 | 等待已保存偏好加载后确定首次进入的页面；选择隐藏页面时同步显示该入口 | [MediaPickerScreen.kt](E:/Projects/fluxplayer/feature/videopicker/src/main/java/com/fluxplayer/app/feature/videopicker/screens/mediapicker/MediaPickerScreen.kt:159) |
| 标签页可见性 | 构建导航和回退当前页面；保留至少一个可见入口 | [AppearancePreferencesViewModel.kt](E:/Projects/fluxplayer/feature/settings/src/main/java/com/fluxplayer/app/settings/screens/appearance/AppearancePreferencesViewModel.kt:246) |
| 同名历史合并 | 只合并视频历史页显示；空标题按 URI 区分，原始记录不删除 | [LocalPlaybackHistoryRepository.kt](E:/Projects/fluxplayer/core/data/src/main/java/com/fluxplayer/app/core/data/repository/LocalPlaybackHistoryRepository.kt:84) |
| 历史显示上限 | 对视频历史显示列表截取 50–1000 条；足迹和已播放标记读取完整历史 | [LocalPlaybackHistoryRepository.kt](E:/Projects/fluxplayer/core/data/src/main/java/com/fluxplayer/app/core/data/repository/LocalPlaybackHistoryRepository.kt:85) |
| 视频无痕播放 | 拒绝新的视频历史、续播和视频状态写入；同时跳过目录足迹与历史截图保存，已有记录保留 | [LocalMediaRepository.kt](E:/Projects/fluxplayer/core/data/src/main/java/com/fluxplayer/app/core/data/repository/LocalMediaRepository.kt:31) |
| 媒体网格列数 | 0 自动、1–6 指定；视频与文件夹网格、听书首页网格读取列数，列表布局不受影响 | [MediaView.kt](E:/Projects/fluxplayer/feature/videopicker/src/main/java/com/fluxplayer/app/feature/videopicker/composables/MediaView.kt:69) |
| 应用语言 | Android 13+ 使用系统应用语言页，由系统保存到当前包名；项目新增的中文文案未全部翻译 | [GeneralPreferencesScreen.kt](E:/Projects/fluxplayer/feature/settings/src/main/java/com/fluxplayer/app/settings/screens/general/GeneralPreferencesScreen.kt:92) |
| 隧道播放 | 初始化及修改时传给 Media3 轨道选择器；设备、音视频轨道和硬件解码需支持 | [PlayerService.kt](E:/Projects/fluxplayer/feature/player/src/main/java/com/fluxplayer/app/feature/player/service/PlayerService.kt:818) |
| 优先 AAC 音轨 | 自动选轨时设置 AAC MIME 偏好；手动指定音轨、语言优先级及媒体支持仍会影响最终选轨 | [PlayerService.kt](E:/Projects/fluxplayer/feature/player/src/main/java/com/fluxplayer/app/feature/player/service/PlayerService.kt:819) |
| 缓冲时长 | 10–120 秒直接配置 DefaultLoadControl；初始化先读取磁盘参数，重启应用后生效，保留 64 MB 内存阈值 | [PlayerService.kt](E:/Projects/fluxplayer/feature/player/src/main/java/com/fluxplayer/app/feature/player/service/PlayerService.kt:824) |

## 本次修正的遗漏

1. 视频无痕补齐外部字幕、字幕速度与延迟、目录足迹以及播放退出截图保存路径。
2. 历史显示上限与同名合并仅作用于显示流，完整历史流用于足迹和网盘已播放标记，避免误清理旧标记。
3. 冷启动主题／启动页及播放引擎使用真正从磁盘加载的偏好；缓冲时长不再有先读到默认值的竞态。
4. 通栏与普通底栏读取动画开关；底栏拖动开关明确限制于悬浮胶囊模式。
5. 深浅主题切换和从其他窗口返回时重新应用状态栏偏好。
6. 玻璃效果的实际应用范围、平台要求和数值取整已核对；最新 AVBox 外观页面中“应用控件”当前作用于主应用顶栏，参数允许预先设置。
7. 启动页选择同步打开对应入口；禁止关闭最后一个导航入口。应用语言页不存在时显示提示，避免点击入口抛出异常。

## 仍有条件或尚未实现的范围

- 顶栏玻璃不表示所有控件均玻璃化，录入区域内控件保留普通样式。
- 应用语言使用仓库已有翻译，新加条目仍有中文文案；低于 Android 13 的自定义应用语言入口未实现。
- 隧道播放与 AAC 是引擎参数和选轨偏好；最终启用状态依赖设备和文件，未在手机播放中验证。
- 缓冲时长不是下一集提前预加载；后者仍未实现。
- 其他未移植的 AVBox 引擎、直播和内容源能力见 AVBox 设置迁移说明。
