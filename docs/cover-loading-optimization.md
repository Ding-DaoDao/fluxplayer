# 云盘封面加载优化方案

> 诊断日期：2026-10-05（第二版，根据「123 云盘大部分能正常加载」修正）
> 实施状态：**P0-1 / P0-2 / P1-1 已于当日实施并装机**，P2 待办。
> 现象：123 云盘封面**大部分能加载**，但**有的能加载有的不能**，且**加载慢**。
> 结论：不是域名整体不匹配，而是**三个具体缺陷叠加**。

---

## 实施结果速览

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0-1 | `CloudDirectoryCache` 补`thumbnailUrl` 等字段 + version 失效 | ✅ 已实施 |
| P0-2 | Coil `eventListener` 埋点（封面OK/失败/慢加载） | ✅ 已实施 |
| P1-1 | 封面与视频帧磁盘缓存隔离 + 配额 2%→5% + 内存缓存 15% | ✅ 已实施 |
| P1-2 | 进目录预取前 30 张封面 | ⏸ 未做（收益待P0-1 验证后再定） |
| P2 | 三态占位区分 + `BookCoverCache` 统一 ImageLoader | ⏸ 未做（需新增 drawable 资源） |

**升级后首次启动**：旧版本缓存因 `CACHE_VERSION` 不匹配被自动丢弃并重建，
所以那一次进入目录会走完整网络加载，属预期行为。

**验证方法**：
```bash
adb logcat -s CoverImageLoader
```
- `封面OK xxxms source=MEMORY/DISK/NETWORK host=xxx` —— `source=DISK` 说明走了磁盘缓存（快），
  `NETWORK` 说明在走网络（慢）。若大量 NETWORK 即缓存策略未生效。
- `封面OK xxxms` 且 ≥800ms 会打WARN 级，便于过滤。
- `封面失败 host=xxx reason=...` —— 排查 403 / 超时 / URL 失效。

---

## 一、根因诊断（修正）

### ✅ 排除项：域名后缀大面积不匹配

初版判断「缩略图 CDN 域名未覆盖」不成立。`CloudPlayHeaders` 注册了
`.123pan.cn`、`.cjjd19.com`、`.123295.com`，123 云盘缩略图主要落在这几个域内，
所以**绝大部分能正常加载**。域名问题存在但只影响边缘 case（302 后的
`pd1.cjjd19.com` 边缘节点等），不是主因。

### ❌ 缺陷 1：磁盘缓存丢失 thumbnailUrl —— **这是「有的能加载有的不能」的真凶**

`CloudDirectoryCache.put()` 只持久化了 5 个字段：
```kotlin
put("path", ...); put("name", ...); put("isDirectory", ...); put("size", ...); put("lastModified", ...)
```
**`thumbnailUrl` 完全没存。** `get()` 读回来时同样不构造该字段 → `null`。

后果链路：
```
进入 123 云盘 → autoLogin 命中 CloudDirectoryCache.get()
  → WebDavResource.thumbnailUrl = null
  → FileTypeIcon 判定 isNullOrBlank → 直接 StaticIcon（静态默认图）
```

而同一个目录**如果走网络加载**（`loadDirectory`）就带 `thumbnailUrl` → 封面正常。

**这完全解释了「同一个网盘里有的能加载有的不能」**：
- 首次网络加载过的目录 → 有封面（直到进程内 `directoryCache` 被清）
- 重启 App / 切账号 / 从缓存恢复的目录 → 无封面，直接默认图
- 表现为**随机**，实际取决于该目录是否走过网络加载

**同一问题影响所有云盘**（123/夸克/天翼/移动/阿里，共用 `CloudDirectoryCache`）。

修复：给 `put`/`get` 补上 `thumbnailUrl`（和 `category`、`createdAt` 一并补齐更完整）。

### ❌ 缺陷 2：磁盘缓存配额被本地视频帧挤占 —— **这是「慢」的主因**

`ImageLoaderModule.kt:44-53`：
```kotlin
.diskCache(DiskCache.Builder()
    .directory(context.filesDir.resolve("thumbnails"))   // ← 与本地视频帧共用
    .maxSizePercent(0.02))                                // ← 仅 2%
```

云盘封面和本地视频首帧缩略图抢同一个 2% 配额。视频文件一多，
云盘封面被 LRU 淘汰 → 每次回看都重新走网络 → **滑动列表时感知明显**。

注：内存缓存并未禁用（Coil 3 默认开启），不是主因。

### ❌ 缺陷 3：失败即终局 + 零日志，掩盖真实原因

`FileTypeIcon.kt:47-52` 的 `placeholder` / `error` / `fallback` **三者指向同一个图**，
且**没有 EventListener、没有任何日志**。

后果：
- 用户看到「默认图」时无法区分「本来就没封面」「缓存丢了」「403 了」「还在加载」
- 开发侧也看不出慢在网络、磁盘 IO 还是解码
- **这也是我初版误判的原因** —— 症状被统一成同一个图，掩盖了差异

### ⚠️ 附带问题：CDN 302 后的域名

书源 `Pan123.kt:432` 明确注释：
> `instanceFollowRedirects = true // CDN 会 302 到边缘节点（pd1.cjjd19.com），需跟随`

Coil 默认跟随重定向，**且 `cjjd19.com` 已在注册表内**，所以这条基本 OK。
但 `CloudHttpClient.NO_REDIRECT`（`followRedirects(false)`）若被误用于封面链路会直接失败 ——
目前仅 C189 下载链路使用，封面未受影响。**排查时需留意不要复用该客户端。**

---

## 二、修复方案（按性价比排序）

### ✅ P0-1 补齐目录缓存的 thumbnailUrl 字段 ← 修「有的能加载有的不能」（已实施）

`CloudDirectoryCache` 缓存结构改为带 `version` 的信封格式：
```kotlin
put("thumbnailUrl", item.thumbnailUrl)   // 关键：原来根本没存
put("fileCount", ...); put("folderSize", ...); put("category", ...); put("createdAt", ...)
```
`get()` 版本不匹配时直接删除旧缓存并返回 null，强制走网络重建，
避免「一半目录有封面一半没有」的半新半旧状态。

>顺带修掉一个隐患：原 `get()` 用位置参数构造 `WebDavResource(path=, name=...)`，
> 而数据类第一个参数是 `name`。已改为具名参数。

### ✅ P0-2 加 eventListener 埋点 ← 修「无法诊断」（已实施）

挂在全局 `ImageLoader.Builder.eventListener()` 上。
注意：Coil 3 中方法名是 `eventListener`（不是 2.x 的 `listener`）；
`SuccessResult` 也**没有** `requestTimeMs` 字段，改用 `onStart` 打时间戳自行计算耗时。

### ✅ P1-1 缓存目录隔离 ← **修「慢」的主因**（已实施）

- 云盘封面磁盘缓存改到 `filesDir/cloud_covers`，配额 2% → 5%
- 本地视频帧键加 `local_video:` 前缀（`LOCAL_VIDEO_THUMBNAIL_PREFIX`）
- 内存缓存显式配 20%

> **实测归因（20:10 修正）**：提速主要来自**磁盘缓存不再被视频帧挤掉**。
> 内存缓存那一项此前写成 15%，查证后发现 Coil 默认已是 20%
> （`STANDARD_MEMORY_MULTIPLIER = 0.2`，低内存设备 0.15），
> 即原写法反而是降级。已改回 20% 与默认值对齐。
> 结论：内存缓存不是本次提速的原因，**别把它算成功劳**。

> **同步修复**：`LocalMediaSynchronizer` 删除缩略图时原本用裸 URI 做key，
> 加前缀后会删不掉。已改为复用 `LOCAL_VIDEO_THUMBNAIL_PREFIX`，
> 并保留对无前缀旧键的清理以兼容升级前遗留数据。
>
> 常量下沉到 `core/common/ThumbnailCacheKeys.kt` —— `app` 依赖 `core:media`，
> 反向依赖不可行，故必须放在两者都依赖的 `core:common`。

### P1-2 显式内存缓存 + 预取

```kotlin
.memoryCache { MemoryCache.Builder(context).maxSizePercent(0.15).build() }
```

进目录后对前 20~30 个 `thumbnailUrl` 预热：
```kotlin
listResult.items.take(30).mapNotNull { it.thumbnailUrl }.forEach { url ->
    context.imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
}
```
滑动前拉进内存，滑动时零延迟。

### ⏸ P2 三态占位区分 + 统一 ImageLoader（未做）

- `placeholder` / `error` / `fallback` 三者目前仍指向同一张图。
  已在 `FileTypeIcon.kt` 标TODO。**未实施原因**：需要新增骨架/失败态 drawable 资源，
  仓库内现有的只有 `ic_file_image` 这类类型图标（PNG），没有合适的占位图。
  本次先靠 `CoverImageLoader` 日志区分失败原因，不阻塞主要修复。
- `BookCoverCache.kt:42` 的 `newBuilder()` 与全局 ImageLoader 分叉，待合并。

---

## 三、实施顺序

| 阶段 | 内容 | 预期 |
|---|---|---|
| **1** | P0-1 补 thumbnailUrl 缓存字段（+ version 失效机制） | 「有的能加载有的不能」消失 |
| **2** | P0-2 EventListener 埋点 | 能量化「慢」的来源，验证后续判断 |
| **3** | P1-1 缓存目录隔离 | 「慢」明显改善 |
| **4** | P1-2 内存缓存 + 预取 | 滑动首屏基本无感 |
| **5** | P2 三态区分 + 统一 ImageLoader | 收尾 |

第 1 步和第 2 步都很轻，建议一起做 —— 改完重启 App 就能立刻验证。

---

## 四、需要你确认

1. **「不加载」是否有个规律**？比如：退出 App 重进后所有目录都没封面（则基本可确认是缓存丢字段）；
   还是随机（则另有原因，需日志确认）。
   → 建议改完第 1 步后直接验证。
2. **磁盘缓存配额**：2% → 5% 可以吗？看设备存储余量。
3. **其他网盘源**是否也观察到同样现象？共用 `CloudDirectoryCache`，理论上同样受影响。

---

## 五、位置索引

| 位置 | 作用 | 本次结论 |
|---|---|---|
| `feature/videopicker/.../CloudDirectoryCache.kt:36-46` | `put()` 未存 thumbnailUrl | **主因** |
| `feature/videopicker/.../CloudDirectoryCache.kt:53-67` | `get()` 未读 thumbnailUrl | **主因** |
| `app/.../ImageLoaderModule.kt:44-53` | 缓存目录共用 + 2% 配额 | 慢的主因 |
| `feature/videopicker/.../composables/FileTypeIcon.kt:28-34` | header 注入 | 基本正常 |
| `feature/videopicker/.../composables/FileTypeIcon.kt:47-52` | 三态同图 | 掩盖症状 |
| `core/data/.../pan123/Pan123ApiClient.kt:334-340` | 缩略图提取 | 与书源一致，无问题 |
| `core/data/.../CloudHttpClient.kt:15-18` | NO_REDIRECT | 仅 C189 用，暂不影响 |
| `core/ui/.../cache/BookCoverCache.kt:42` | 独立 ImageLoader | P2 待合并 |
