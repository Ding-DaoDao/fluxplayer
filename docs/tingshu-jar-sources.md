# JAR 听书书源

Flux Player 的「听书」首页以本地书库主卡片、在线书库网格和最近收听展示内容。本地书库保留原来的目录扫描、收藏和续听逻辑；书源与本地共用书籍卡片、章节详情和播放界面。视频、网盘、WebDAV、OpenList 和弹幕保持原有功能。

## 使用

1. 打开「设置 → 听书配置」。书库路径和书源配置统一在这里管理。
2. 选择本地书库路径；每本书放在独立的子文件夹中。原有路径会继续保留。
3. 在同一页选择「导入 JAR」，导入可信来源的 DEX JAR，保留文件原名，如 `sources_by_pan123.jar`。
4. 点击包内书源的「配置」，填写书源要求的凭证和根目录并保存；可按包启用、停用或删除。
5. 返回听书首页，选择本地书库或书源。123、夸克、天翼和移动云盘直接加载配置根目录的书籍，不显示搜索框和中间目录入口；普通书源仍支持分类和搜索。最近听过可切换网格或列表，切换选择会保存。
6. 打开书籍后选择章节或继续收听。两种书库共用封面详情、章节目录、收藏、进度标记及续听按钮。
7. 播放页共用封面、进度条、章节目录、快进/快退、倍速、头尾跳过及定时控件。定时支持按时间或章节数暂停，返回后可后台播放。

同名 JAR 导入会替换该包。禁用和删除按包操作，一个包可能包含多个书源。删除后保留书源配置和听书进度，重新导入后可继续使用。

## 兼容范围

支持 `com.github.eprendre.<文件名去掉.jar>.SourceEntry.getSources()` 入口，以及「我的听书」的 `TingShu`、书籍、章节、分类、配置和请求头接口。

第一版实现以下地址提取器：

- `AudioUrlDirectExtractor`
- `AudioUrlCustomExtractor`
- `AudioUrlJsonExtractor`
- `AudioUrlJsoupExtractor`

WebView 渲染、嗅探、网页登录/搜索验证、书源控制播放器、歌词展示、订阅自动更新尚未实现。配置中可以手动填写源要求的 Cookie/Token。

可导入包含 `classes.dex` 的 Android JAR。普通 JVM `.class` JAR 会被拒绝。源也可能依赖高于设备 Android 版本的系统 API，或依赖此版本尚未提供的宿主接口；此类源需要作者重新打包或后续补充兼容能力。动态加载的 DEX 不会被宿主的构建流程再次转换。

真实网盘登录、分类请求和音频获取是否成功，仍取决于当前凭证、网络和源自身的接口实现。加载/配置测试通过不代表网盘接口已经实跑通过。

## 实现边界

- `:core:tingshu` 提供固定包名的兼容接口、依赖库、JAR 导入和书源调用。
- `:feature:tingshu` 提供统一书库首页、书源浏览与配置及专用 Media3 会话。
- `:feature:player` 提供共用的书籍卡片、章节详情和播放界面；书源的切章、定时与头尾跳过由专用服务执行。
- `:feature:settings` 提供「听书配置」页面，集中管理本地路径与书源。
- `:feature:videopicker` 将书库首页接入既有听书入口，保留原有本地扫描、收藏与进度数据。
- 书源配置位于独立的 `tingshu_source_config`，书籍和进度位于 `tingshu_library`，不读取或覆盖原生网盘的凭证与本地听书进度。
- 书源文件复制到应用私有目录，按 Android 14 动态加载要求在写入前设置只读。拒绝超过 20 MB 的包，校验 DEX 标识和源 ID，失败的导入不会覆盖原有包。
- 同步书源调用在专用后台线程串行执行；旧提取器的解析回调使用线程上下文，避免跨源覆盖。
- 每章开始播放时重新解析地址；媒体请求和 HLS 分片的请求头由该书源逐次提供，不注册到原有播放器的全局请求头表。
- 续听用书源 ID、书籍 URL 和章节 URL 关联。章节重新排序时仍能匹配原章节；原章节消失时从第一章开始。
- 兼容接口及依赖库通过消费者混淆规则保留，防止发布构建删除仅由外置 JAR 使用的方法。
- 本地/视频会话使用 `fluxplayer.main`，书源会话使用 `fluxplayer.tingshu`。修复两个服务均使用默认空会话 ID 时，本地播放器连接被拒绝并崩溃的问题。

JAR 在应用进程内执行，专用播放会话和独立配置不构成代码权限沙箱，因此导入页面会说明仅选择可信书源。

## 验证

```powershell
.\gradlew.bat :core:tingshu:ktlintCheck :feature:tingshu:ktlintCheck
.\gradlew.bat :core:tingshu:connectedDebugAndroidTest
.\gradlew.bat :feature:tingshu:connectedDebugAndroidTest
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease-with-debug-signing
```

`core/tingshu/src/androidTest/assets` 中的四个网盘包来自本次提供的目录，仅用于测试，不随正式 APK 发布。兼容测试覆盖实际 JAR 加载、配置、禁用/启用、删除及失败导入保留旧包。

`test_source.jar` 是由同目录下 `fixture/SourceEntry.java` 生成的独立 DEX 书源。播放器集成测试使用本机 HTTP 测试服务返回静音 WAV，验证分类、搜索、详情、播放头、暂停、切章、页面重建、进度与错误恢复，不需要网盘账号。

测试书源的重建脚本为 `core/tingshu/src/androidTest/fixture/build_fixture.py`。先编译 `:core:tingshu:compileDebugKotlin`，再按脚本参数传入 Android SDK 与 Kotlin 标准库路径。

## 本次验证结果

- 新增两个模块及修改的本地听书入口文件通过 ktlint 检查。
- Debug APK 和启用 R8 混淆的 `release-with-debug-signing` APK 构建成功。
- Android 15 模拟器中，兼容模块 4 项测试通过，包含实际的 123、夸克、天翼和移动云盘 JAR 加载与配置。
- 同一模拟器中，完整播放集成测试通过，涵盖自定义解析、请求头、切章、页面重建与保存进度后续听。
- 在完整 Debug App 中，确认本地听书入口保留，并通过系统文件选择器导入测试 JAR、打开分类和书籍列表。
- 已连接的 Android 14 手机禁止安装测试包，未在该手机上执行测试；未使用真实网盘凭证进行网络播放验证。

设备测试记录在 `build/tingshu-core-device-tests.log` 和 `build/tingshu-playback-device-tests.log`，构建记录在 `build/tingshu-build.log`。这些文件属于本地构建产物。


## 听书首页与云盘直达调整

- 首页使用本地书库主卡片、在线书库双列自适应卡片和最近收听；最近收听支持网格/列表，封面按网格宽度展示。
- 四个云盘包按 `packageEntry` 识别，单根目录入口直接加载内容，隐藏搜索；普通书源保留分类搜索。
- 根目录不进入返回栈，书籍详情返回后仍保留原列表；根目录提供刷新操作。
- 2026-10-02：听书模块 `ktlintCheck`、Debug APK 构建通过。`TingshuBrowseTest` 在 Android 15 模拟器通过，使用测试源分别验证四种云盘包标识的直达和详情返回行为，同时验证普通书源分类入口。
- 实际云盘 JAR 在模拟器完成入口显示检查；未配置真实账号，未验证云盘在线书籍和音频请求。
- 构建日志：`build/listening-redesign-final.log`；测试日志：`build/listening-redesign-test.log`。


## 本地与书源共用首页播放状态

- 本地听书服务发布实时书名、章节、封面、进度和播放状态；离开播放页后首页仍能显示。后台服务同时保存本地续听与最近播放时间。
- 首页合并本地和书源的最近收听记录，按收听时间排序；原有本地续听记录也会显示，本地记录点击后打开书籍详情。
- 正在播放卡片优先显示播放中的会话，暂停时保留最近使用的会话。右侧按钮独立暂停/继续，卡片其他区域打开播放页；打开暂停的本地播放页不会自动开始播放。
- 卡片去掉撑高容器的固定尺寸装饰，使用全宽内容行与自适应高度。
- 2026-10-03：相关代码格式检查和 Debug APK 构建通过；本地后台播放、记录持久化、暂停重开测试通过，书源完整播放回归测试复查通过。模拟器人工验证本地/书源首页按钮切换，以及混合最近记录和本地记录详情跳转。
- 构建日志：`build/listening-local-home-final.log`；本地测试：`build/listening-local-home-device-test.log`；书源测试：`build/listening-source-home-device-test.log`。
