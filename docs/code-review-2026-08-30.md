# FluxPlayer 代码审查与优化建议

> 审查日期：2026-08-30 · 范围：app / core/* / feature/* 全部 main 源码（约 52,000 行 Kotlin）+ 构建配置

---

## 🔴 高优先级（稳定性 / 安全 / 数据正确性）

### 1. release.keystore 被提交进了 git
- 位置：`app/release.keystore`（已跟踪）
- 签名密钥入库 = 任何拿到仓库的人都能冒充你签名发布。
- **建议**：从 git 历史移除并轮换密钥，加入 `.gitignore`。

### 2. Coil 磁盘缓存上限 = 100% 可用存储
- 位置：`app/.../ImageLoaderModule.kt:49`（`maxSizePercent(1.0)`）
- 缩略图缓存理论上可吃满整个存储空间。
- **建议**：改为 `maxSizePercent(0.02~0.05)` 或固定字节上限。

### 3. MainActivity 每次进 STOP 都做全量备份
- 位置：`app/.../MainActivity.kt:253-258`
- 打开播放页、熄屏都会触发全量备份 + WebDAV 上传；`lifecycleScope` 在进程被杀时任务中断。
- **建议**：加变更检测/防抖，改用 WorkManager。

### 4. ViewModel 长期持有 Activity Context
- 位置：`feature/player/.../PlayerViewModel.kt:145/206/235/258`（8 个方法以 `context` 为参数），调用方 `PlayerActivity.kt:319/324` 传 `this`
- 协程延迟使用该 context（`silentLoadLocalDanmaku` 还 `delay(800)` 后再用）。
- **建议**：`@ApplicationContext` 注入到 ViewModel，删除所有 context 参数。

### 5. 播放器 Service 的 serviceScope 在 onDestroy 未取消
- 位置：`feature/player/.../service/PlayerService.kt:89/91`
- Service 销毁后协程仍存活，Service 重建会累积泄漏。
- **建议**：`onDestroy` 中 `serviceScope.cancel()`。

### 6. 约 40 处空 catch 吞异常
- 例：`Pan123BrowserViewModel.kt:163/174`、`AliyunBrowserViewModel.kt:169/335`、`Yun139BrowserViewModel.kt:177/242`、`QuarkBrowserViewModel.kt:228`、`BaseCloudBrowserViewModel.kt:498`
- 登录失效、网络错误完全静默，用户只看到"空白列表"。
- **建议**：记日志并上抛到 notifier 统一提示。

### 7. 播放历史空断言可崩溃
- 位置：`feature/videopicker/.../history/HistoryTabContent.kt:191`（`parentPath!!.split(...)`）
- 旧数据 / 云盘历史的 parentPath 可能为 null。
- **建议**：`parentPath?.split(...) ?: return`。

### 8. 双路径续播 seek 竞争
- `PlayerService.kt:199-212`（metadata.positionMs）与 `PlayerActivity.kt:557-560`（pendingResumePosition extra）两条路都 seek，时序不定导致续播位置跳动。
- **建议**：只保留 Service 层一个入口。

### 9. 听书进度持久化写入放大
- `PlayerActivity.kt:237-249/650-659`：每 20 秒心跳把整个 `audiobookChapterProgress` map 全量序列化写 DataStore，随书籍/章节数线性放大（代码注释已自认）。
- **建议**：迁移到 Room 表（bookPath+chapterIndex 主键），或拆独立 key。

### 10. Service onDestroy 最后一次进度保存是 fire-and-forget
- `PlayerService.kt:815-836`：系统随即杀进程时最后进度丢失。
- **建议**：最终保存同步写入（此处 runBlocking 可接受）或 WorkManager 保证落盘。

---

## 🟡 中优先级（性能 / 架构 / 可维护性）

### 11. 倍速滑块每帧触发 DataStore 全量落盘
- `AudioPlaybackScreen.kt:340-345` + `PlayerActivity.kt:253-259`
- **建议**：拖动只更新内存 state，`onValueChangeFinished` 再落盘。（与你们 slider drag-to-apply 交互不冲突：UI 即时生效、持久化延迟合并）

### 12. 心跳每 5 秒触发 1700 行大 composable 整体重组
- `AudioPlaybackScreen.kt:218/235`：`localProgress` 在顶层，每次心跳 map 拷贝导致整个 Screen（含弹窗）重组。
- **建议**：进度展示下沉独立子 Composable，只订阅所需条目。

### 13. 6 套云盘浏览器 VM/Tab 高度重复
- `C189BrowserViewModel`(1382) / `Pan123`(1207) / `Aliyun`(918) / `WebDav`(747) / `Yun139`(740) / `Quark`(730)，加 6 个 Tab Content（各 400-600 行）。
- 基类 `BaseCloudBrowserViewModel` 已存在但子类仍复制下载、登录恢复、排序逻辑。
- **建议**：公共逻辑上提基类，Tab 抽参数化通用 Composable。

### 14. 超大文件 Top 10
| 行数 | 文件 |
|---|---|
| 1703 | feature/player/.../AudioPlaybackScreen.kt |
| 1519 | feature/videopicker/.../MediaPickerScreen.kt |
| 1382 | feature/videopicker/.../C189BrowserViewModel.kt |
| 1218 | core/data/.../C189ApiClient.kt |
| 1207 | feature/videopicker/.../Pan123BrowserViewModel.kt |
| 1129 | feature/player/.../MediaPlayerScreen.kt |
| 1067 | feature/player/.../PlayerService.kt |
| 1030 | feature/videopicker/.../BaseCloudBrowserViewModel.kt |
| 933~675 | core/data/.../danmaku/ 四个弹幕 Fetcher |
| 770 | feature/player/.../PlayerActivity.kt |

- **建议**：优先拆 `AudioPlaybackScreen`（各弹窗已是独立区块，最容易拆）。

### 15. 多个 LazyList 缺 key（列表刷新时全量重组）
- `SearchScreen.kt:283`、`HistoryTabContent.kt:174`、`AudiobookDetailContent.kt:455`、`MediaView.kt:98/137`、`BackupScreen.kt:536`、`PlaylistView.kt:101`、`AudioPlaybackScreen.kt:762`（章节列表）、`DanmakuSourceSearchSheet.kt:268/542/637`
- **建议**：以路径/uri 作 key，进度刷新时列表不闪、不丢滚动位置。

### 16. 片头片尾跳过：两套重复实现 + 无限轮询
- `MediaPlayerScreen.kt:417-432`（视频，while+delay(500)）与 `AudioPlaybackScreen.kt:368-406`（听书，两段 while+delay），且语义不一致（绝对位置 vs 片尾时长）。
- **建议**：抽共享 IntroOutroController（基于 Player.Listener + 单轮询）。

### 17. 听书心跳 tick 计数随暂停重置
- `AudioPlaybackScreen.kt:219-241`：`LaunchedEffect(isPlaying)` 重启时 tick 归零，频繁暂停场景可能长时间不落盘。
- **建议**：改为基于绝对时间的节流。

### 18. 单元测试失败被静默忽略
- 根 `build.gradle.kts`：`ignoreFailures = true`，测试形同虚设。
- **建议**：移除，仅对 flaky 任务单独豁免。

### 19. MediaStore 同步无防抖 + 全量递归扫描
- `core/media/.../LocalMediaSynchronizer.kt:200-211/95-116`
- **建议**：onChange 加 500ms-1s debounce，目录改增量 diff。

### 20. 播放历史记录在主线程做 prefs 扫描 / ContentResolver 查询
- `PlaybackPersistence.kt:60-85` 在 `onIsPlayingChanged`（主线程）同步执行。
- **建议**：准备阶段挪进 `saveScope.launch(IO)`。

### 21. 凭据存储割裂
- 令牌/Cookie 散落至少 6 个 SharedPreferences（`CloudUriResolver.kt:117/185/311/348`、`Yun139BrowserViewModel.kt:144`、`OpenListManager.kt:47`）。
- **建议**：建统一（加密）DataStore DataSource。

### 22. LoadControl 对云盘流偏激进
- `PlayerService.kt:739-749`：64MB 目标缓冲 + `setPrioritizeTimeOverSizeThresholds(false)`，弱网易缓冲超时。
- **建议**：云盘来源动态调整。

### 23. 按集数睡眠定时误判
- `PlayerService.kt:172-179`：用 index 差值判断，手动回退章节后计时错乱。
- **建议**：改为每次 AUTO_TRANSITION 递减计数器。

### 24. 其他
- `MediumDao.kt:54-62`：3 个非 suspend 的阻塞式写方法 → 改 suspend。
- `MediaPlayerScreen.kt:1006-1016`：条件分支内调用 LaunchedEffect → 提到顶层。
- `PlayerService.kt:272-332`：回调内 replaceMediaItem 有自触发环路风险 → 无变化时跳过。
- `LocalPlaybackHistoryRepository.kt:28-37`：init 中启动 DB 迁移拖慢单例注入 → 懒触发。
- `GlobalExceptionHandler.kt:14-21`：直接 startActivity 有后台启动限制，stacktrace 走 Intent 有 TransactionTooLarge 风险 → 先落盘。

---

## 🟢 低优先级（细节打磨）

- **空模块**：`feature/audiobook` 是空壳，听书 UI 实际在 `feature/videopicker/screens/audiobook` → 删除或迁移，避免误导。
- **编码损坏**：`AudioPlaybackScreen.kt:365` 等多处 GBK/UTF-8 乱码注释 → 统一 UTF-8。
- **格式**：`MediaDatabase.kt:233`（`val         MIGRATION_7_8` 多余空格）→ 跑一次 `./gradlew ktlintFormat`。
- **冗余依赖**：`androidx-constraintlayout`（仅一处 View 用法）疑似可移除；`androidx-palette` 仅一处，可换 Compose 原生取色。
- **魔法字符串**：`#pan123Play=true#` 散落 4+ 处 → 抽常量。
- **弹幕 Fetcher 结构重复**：5 个平台 Fetcher 骨架相似 → 抽模板方法省约 30% 代码。
- **PlayerViewModel 状态碎片化**：8+ 个公开 MutableStateFlow（部分未 asStateFlow，外部可写）→ 收敛为单一 UiState。
- **Compose 中直读 player 非快照状态**：`AudioPlaybackScreen.kt:723/763/883` 读 `currentMediaItemIndex` 不触发重组，靠轮询"顺便"刷新 → 用 Listener 写 Compose state。
- **缩略图 bitmap 未 recycle**（成功路径，`PlayerActivity.kt:681-702`）。
- **OpenListManager 绕过 Hilt**：全局静态持有 + 私有 scope → 改 `@Singleton` 注入。
- **DataStore updateData 异常被吞**：5 个 DataSource 同模式，设置"保存成功"实则可能丢失 → 返回 Result。

---

## ✅ 做得好的地方

- Hilt 分层清晰，Flow 统一 `collectAsStateWithLifecycle`，无 `GlobalScope`、无主线程 `runBlocking`
- MediaSessionService 生命周期处理规范（onTaskRemoved 空保护、decoder fallback 重建）
- 弹幕渲染有轨道分配与空转优化，听书扫描有增量填充与陈旧条目清理
- Room 迁移带索引优化注释，代码注释里对已知问题有自觉

---

## 💰 建议投入顺序（性价比 Top 5）

1. **release.keystore 出库**（5 分钟，安全红线）
2. **Coil 缓存上限 + MainActivity 备份防抖**（用户可感知的存储/电量问题）
3. **空 catch 统一接 notifier + `parentPath!!` 修复**（"空白列表"和闪退直接相关）
4. **听书进度迁 Room + 续播 seek 收敛单入口**（数据正确性核心）
5. **拆 AudioPlaybackScreen + 云盘浏览器逻辑上提基类**（后续所有听书 UI 迭代都受益）
