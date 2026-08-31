# 移动云盘（yun139）接口文档

> 适用范围：Flux Player `yun139` 云盘模块
> 文档生成时间：2026-09-01（含凭证层次修正）
> 代码基线：`core/data/.../yun139/`、`core/data/.../cloud/CloudUriResolver.kt`、`feature/videopicker/.../yun139/`

---

## 0. 一句话概览

移动云盘模块走的是 **移动云盘 PC/H5 版的 `hcy` 私有 API**（`personal-kd-njs.yun.139.com`），凭证靠 **WebView 登录 + Cookie 轮询** 抓取。核心特征：

- **端点全部硬编码**，共 8 个，没有 123 云盘那种 `getconfig` 动态下发机制
- **核心凭证只有 `authorization` 一个**，另外两个字段是派生的（详见 §3.1）
- **登录靠 WebView**：加载 `https://yun.139.com/m/#/login`，每 300ms 轮询 `CookieManager`
- **播放无清晰度选项**：`getPreviewInfo` 请求体里 `qualityList` 传 null，只拿单个播放地址
- **每类接口一套请求头**：4 套 header 模板，`x-yun-app-channel` 在列目录和其余接口之间还不一样

---

## 1. 模块地图

```
core/data/src/main/java/com/fluxplayer/app/core/data/yun139/
├── Yun139ApiClient.kt          ★ 全部接口实现（298 行）
├── Yun139AuthProvider.kt       凭证状态单例 + 设备指纹 + 播放/下载头
├── Yun139FileItem.kt           文件/文件夹模型
├── Yun139ListResult.kt         列表结果包装
├── Yun139Crypto.kt             AES 加解密工具（⚠️ 无调用方）
├── Yun139QualityOption.kt      清晰度模型（⚠️ 无调用方）
└── Yun139VideoPreview.kt       视频预览模型（⚠️ 无调用方）

core/data/src/main/java/com/fluxplayer/app/core/data/
├── BaseCloudApiClient.kt       请求基类
├── CloudHttpClient.kt          共享 OkHttp 实例
└── cloud/CloudUriResolver.kt   ★ 播放链解析（resolveYun139）

feature/videopicker/.../yun139/
├── Yun139BrowserViewModel.kt   ★ UI 层调用入口
├── Yun139BrowserUiState.kt     UI 状态
└── Yun139BrowserTabContent.kt  Compose 界面 + WebView 登录页
```

**注册信息**：
- Provider key：`yun139`
- 显示名：`移动云盘`（`BrowseTabs.kt:63`）
- 图标：`UiR.drawable.ic_provider_yun139`
- `VideoSource.YUN139` → `"yun139"`（`VideoSourceExtensions.kt:24`），播放 fragment 标记 `yun139Play`（`:44`）

---

## 2. 基础设施

### 2.1 两个 Base URL

| 常量 | 值 | 用途 |
|---|---|---|
| `BASE_URL` | `https://personal-kd-njs.yun.139.com` | 家庭云/个人云业务 API（`/hcy/*`） |
| `USER_BASE` | `https://user-njs.yun.139.com` | 用户认证 API（`/user/auth/*`） |
| `DEFAULT_UA` | `okhttp/4.12.0` | 所有接口请求的 User-Agent |

> 文件位置：`Yun139ApiClient.kt:18-20`

### 2.2 四套请求头模板

移动云盘对**不同类别的接口要求不同的 `x-yun-*` 头**，这是本模块最容易踩坑的地方。

#### ① `filterHeaders` — 列目录

| Header | 值 |
|---|---|
| `authorization` | `Basic base64("mobile:手机号:token")` |
| `x-yun-uni` | userDomainId（即 `ud_id` cookie） |
| `x-yun-api-version` | `v1` |
| `x-yun-net-type` | `` (空) |
| `x-yun-svc-type` | `1` |
| `x-yun-module-type` | `100` |
| `x-yun-app-channel` | **`10000034`** |
| `x-yun-device-id` / `x-yun-client-info` | 设备指纹串 |
| `caller` | `web` |
| `Referer` | `https://yun.139.com/` |
| `content-type` | `application/json; charset=UTF-8` |
| `User-Agent` | `okhttp/4.12.0` |

#### ② `downloadHeaders` — 获取下载直链

| Header | 值 |
|---|---|
| `x-yun-url-type` | `1` |
| `x-yun-app-channel` | `10000023` |
| `x-huawei-channelsrc` | `10000023` |
| `x-yun-api-version` | `v1` |
| `x-yun-client-info` | 设备指纹串 |
| `authorization` / `x-yun-uni` | 同 ① |
| 其余 | 同 ①（**无 `Referer`**、**无 `x-yun-device-id`**） |

#### ③ `videoPreviewHeaders` — 获取视频播放地址

| Header | 值 |
|---|---|
| `x-yun-url-type` | **`3`** |
| `x-yun-app-channel` | `10000023` |
| `x-yun-net-type` | `1` |
| `x-yun-svc-type` | `1` |
| `x-yun-module-type` | `100` |
| `x-yun-api-version` | `v1` |
| `x-yun-User-Agent` | `android\|24031PN0DC\|android 10\|mCloud12.4.1-0000` |
| `x-yun-device-id` / `x-yun-client-info` | 设备指纹串 |
| 其余 | 同 ① |

#### ④ `fileMgmtHeaders` — 文件增删改移

| Header | 值 |
|---|---|
| `x-yun-app-channel` | `10000023` |
| `x-mm-source` | `0000` |
| `x-yun-net-type` / `x-yun-svc-type` | `1` |
| `x-yun-module-type` | `100` |
| `x-yun-api-version` | `v1` |
| `x-yun-device-id` / `x-yun-client-info` | 设备指纹串 |
| 其余 | 同 ① |

#### ⑤ 登录/刷新专用头（内联在 `refreshToken()` 里）

`x-nationcode: +86`、`x-nettype: 1`、`x-deviceinfo`、`x-huawei-channelsrc: 10000023`、`x-mm-source: 0000`、`x-svctype: 1`、`x-yun-app-channel: 10000023`

> ⚠️ **注意 `x-yun-app-channel` 分裂**：列目录用 `10000034`（Web 通道），其余全用 `10000023`（Android 通道）。这是复刻客户端行为，改动前先确认。

> 代码位置：`Yun139ApiClient.kt:49-110`

### 2.3 设备指纹

`Yun139AuthProvider.deviceInfo` 是 `by lazy` 的**进程级单例**，格式为管道分隔串：

```
1|127.0.0.1|1|12.4.1|Xiaomi|<deviceType>|<uuid32>|02-00-00-00-00-00|<osVersion>|1220X2712|zh||||0000|0|
```

| 片段 | 取值 |
|---|---|
| 客户端版本 | `12.4.1` |
| 品牌 | `Xiaomi` |
| 机型 | 从 9 款小米/红米机型随机 |
| UUID | `randomHex(32)` |
| MAC | `02-00-00-00-00-00`（固定） |
| 系统版本 | `android 13/14/15/16` 随机 |
| 分辨率 | `1220X2712`（固定） |
| 语言 | `zh` |

> 代码位置：`Yun139AuthProvider.kt:11-27, 63-70`

---

## 3. 认证与登录

### 3.1 凭证构成 —— 只有 authorization 是必需的 ⭐

代码里 `Yun139AuthProvider` 存了 3 个凭证字段，但**它们的地位完全不同**，不要当成三个并列凭证：

| 字段 | 必需性 | 来源 | 用途 |
|---|---|---|---|
| **`authorization`** | ✅ **唯一必需** | Cookie `authorization` | 所有请求的 `Authorization` 头 |
| `phoneNumber` | ❌ **派生字段** | 从 authorization **Base64 解码后正则提取** | 仅用于重新拼装 Authorization 串 |
| `userDomainId` | ⚠️ 抓了但**从未校验非空** | Cookie `ud_id` | 全部 5 套请求头的 `x-yun-uni` |

**证据 —— 所有"是否已登录"的判定点都只看 authorization**：

| 判定点 | 代码 |
|---|---|
| `autoLogin` | `Yun139BrowserViewModel.kt:150` → `if (authorization.isBlank()) return` |
| 备份读取 | `BackupManager.kt:449` → `if (auth.isNullOrBlank()) return null` |
| 备份恢复 | `BackupManager.kt:466` → `if (!config.authorization.isNullOrBlank())` |

**phoneNumber 是派生的**，不需要用户提供（`Yun139BrowserViewModel.kt:189-191`）：

```kotlin
val decoded = String(Base64.decode(base64Part, Base64.DEFAULT), Charsets.UTF_8)
val phoneNumber = Regex("\\d{11}").find(decoded)?.value ?: ""   // ← 从 authorization 里挖出来的
```

**userDomainId 的实际情况**：
- WebView 轮询要求 `authorization` 和 `ud_id` **两个 cookie 都命中**才触发登录（`Yun139BrowserTabContent.kt:259`），从最初提交 `2c0b74f` 起就是如此
- 值会填进**全部 5 套请求头**的 `x-yun-uni`
- **但代码中没有任何地方校验它非空** —— 登录判定不看它，`autoLogin` 也不看它

> ❓ **待实测确认**：既然从未校验 `ud_id` 非空，如果你实测只填 authorization 也能正常列目录/播放，说明服务端**不强制校验 `x-yun-uni`**，那么：
> - 轮询条件可以放宽为只等 `authorization`
> - `userDomainId` 字段可以降级为可选
>
> 在确认之前，不要擅自删掉 `ud_id` 抓取逻辑 —— 服务端行为可能随时变化。

### 3.2 Authorization 格式

```
Authorization: Basic base64("mobile:<手机号>:<token>")
```

例：`mobile:13800138000:abc123...` → Base64 → `Basic bW9iaWxlOjEzODAwMTM4MDAwOmFiYzEyMy4uLg==`

`loginWithWeb()` 会把抓到的 cookie 值**解码后重新编码**成这个规范格式，所以最终存的是重组后的串，不是原始 cookie。

### 3.3 登录流程（WebView + Cookie 轮询）

```
UI: Yun139LoginScreen
  │
  ├─ WebView 配置：
  │    javaScriptEnabled / domStorageEnabled = true
  │    mixedContentMode = MIXED_CONTENT_ALWAYS_ALLOW
  │    userAgentString = UA_PC（伪装 Chrome 131 Android 14）
  │    CookieManager.removeAllCookies(null)
  │    shouldOverrideUrlLoading 拦截所有非 http/https scheme
  │      （防 mcloud://、intent:// 跳客户端导致白屏）
  │
  ├─ loadUrl("https://yun.139.com/m/#/login")
  │
  └─ 页面加载完成后启动轮询协程（每 300ms）：
       while (isActive && !loginTriggered) {
           cookies = CookieManager.getCookie("https://yun.139.com")
           authMatch = Regex("authorization=([^;]+)").find(cookies)
           udMatch   = Regex("ud_id=([^;]+)").find(cookies)
           if (两者都命中) → onLoginWithWeb(auth, udId); break
           delay(300)
       }
       // ⚠️ 注意：ud_id 未命中会一直空转，见 §3.1 待实测项
```

**`loginWithWeb(authorization, userDomainId)` 做的事**（`Yun139BrowserViewModel.kt:186-218`）：

1. 去掉 `Basic ` 前缀 → Base64 解码 → 得到原始串
2. `Regex("\\d{11}")` 提取手机号（**派生，非用户输入**）
3. 取解码串第 3 段（或最后一段）作为 token
4. **重新编码**成 `mobile:<phone>:<authToken>` 的 Basic 串（规范化格式）
5. `apiClient.setToken(...)` + 激活 `Yun139AuthProvider`
6. 注册 `CloudPlayHeaders` 后缀：`.139.com`、`cmecloud.cn`
7. 写入 SharedPreferences（含 `lastRefresh = now`）
8. `loadDirectory("/")`

> 代码位置：`Yun139BrowserTabContent.kt:235-392`

### 3.4 凭据存储

`SharedPreferences` 名为 **`yun139`**（`Yun139BrowserViewModel.kt:61`）：

| Key | 类型 | 必需性 | 说明 |
|---|---|---|---|
| `authorization` | String | ✅ **必需** | 规范化后的 `Basic xxx` 串，唯一判定依据 |
| `phoneNumber` | String | ❌ 派生 | 从 authorization 解码而来，用于重拼 Authorization |
| `userDomainId` | String | ⚠️ 未校验 | 即 cookie `ud_id`，作为 `x-yun-uni` 头 |
| `lastRefresh` | Long | ❌ 辅助 | 上次刷新 token 的时间戳（毫秒） |

### 3.5 自动登录

`autoLogin()` 在 ViewModel `init` 中调用（`Yun139BrowserViewModel.kt:143-180`）：

```
authorization 为空? → 直接 return（显示登录页）
    ↓ 非空                       ← 只判 authorization
setToken() + 激活 AuthProvider + 注册 CDN 头
    ↓
预填根目录磁盘缓存 → loadDirectory("/")
    ↓
距上次刷新 >= 7 天?
    └─ 是 → 异步 refreshToken()，成功后回写 authorization + lastRefresh
```

> ⚠️ **不做前置校验**：注释明确说明「跳过前置验证，loadDirectory 内已处理认证失败」。也就是说 token 失效时，用户会先看到一次失败的目录加载，再由错误文案提示重新登录。

### 3.6 Token 刷新

```
POST https://user-njs.yun.139.com/user/auth/refreshToken
Body: { "clientType": "414" }
Headers: 登录专用头
```

**响应**：`{ success: true, data: { token: "..." } }`

**处理逻辑**（`Yun139ApiClient.kt:264-295`）：
- 成功判定：`success == true` **或** `message == "请求成功"`
- 取 `data.token`，若非空则重新拼 `Basic base64("mobile:$phone:$token")` 并覆盖 `Yun139AuthProvider.authorization`
- ⚠️ 返回 `Result<Unit>`，**失败时静默吞掉**（异常被 `runCatching` 包住，调用方 `catch (_: Exception) {}`）

### 3.7 登出

`logout()`（`Yun139BrowserViewModel.kt:224-245`）：

1. `apiClient.logout()` → 清空 `Yun139AuthProvider` 四个字段
2. `GlobalCookieJar.clearHost("yun.139.com" | "api.139.com")`
3. 清空内存目录缓存 + `CloudDirectoryCache.clear(..., "yun139")`
4. `prefs.edit().clear()` 清空全部凭据
5. 清 WebView 痕迹：`CookieManager.removeAllCookies` + `WebStorage.deleteAllData()`

### 3.8 备份/恢复

`BackupManager`（`:446-472`）读写同一份 SharedPreferences，模型 `Yun139BackupConfig`，字段为 `authorization / phoneNumber / userDomainId / lastRefresh`。恢复时只凭 `authorization` 非空就激活 Provider。

---

## 4. 接口清单

### 4.1 总表

| # | 方法 | HTTP | 端点 | 请求头组 |
|---|---|---|---|---|
| 1 | `listFiles` | POST | `/hcy/file/list` | filterHeaders |
| 2 | `getVideoPreviewUrl` | POST | `/hcy/videoPreview/getPreviewInfo` | videoPreviewHeaders |
| 3 | `getDownloadUrl` | POST | `/hcy/file/getDownloadUrl` | downloadHeaders |
| 4 | `createFolder` | POST | `/hcy/file/create` | fileMgmtHeaders |
| 5 | `renameFile` | POST | `/hcy/file/update` | fileMgmtHeaders |
| 6 | `deleteFiles` | POST | `/hcy/recyclebin/batchTrash` | fileMgmtHeaders |
| 7 | `moveFiles` | POST | `/hcy/file/batchMove` | fileMgmtHeaders |
| 8 | `refreshToken` | POST | `user-njs.yun.139.com/user/auth/refreshToken` | 登录专用头 |

除 #8 外全部挂在 `https://personal-kd-njs.yun.139.com`。

---

### 4.2 逐个接口详解

#### 1) `listFiles(...)` — 列目录

```kotlin
suspend fun listFiles(
    folderId: String = "/",
    pageCursor: String? = null,
    pageSize: Int = 100,
    orderBy: String = "updated_at",
    orderDirection: String = "DESC"
): Result<Yun139ListResult>
```

```
POST https://personal-kd-njs.yun.139.com/hcy/file/list
Body:
{
  "pageInfo": { "pageSize": 100, "pageCursor": "<上一页游标 | null>" },
  "orderBy": "updated_at",
  "orderDirection": "DESC",
  "parentFileId": "/",
  "imageThumbnailStyleList": ["Small", "Large"]
}
```

**响应**：
```json
{
  "success": true,
  "data": {
    "nextPageCursor": "...",
    "items": [
      {
        "fileId": "...",
        "name": "movie.mkv",
        "type": "file",            // "folder" = 文件夹
        "size": 4294967296,
        "category": "video",
        "createdAt": "2026-01-01 12:00:00.123",
        "updatedAt": "2026-02-01 12:00:00.456",
        "thumbnailUrls": [ { "style": "Large", "url": "https://..." } ]
      }
    ]
  }
}
```

**字段映射**：

| 模型字段 | JSON 路径 | 处理 |
|---|---|---|
| `fileId` | `fileId` | — |
| `fileName` | `name` | — |
| `fileSize` | `size` | — |
| `isDir` | `type` | `== "folder"` |
| `createDate` | `createdAt` | 正则去掉 `.xxx` 毫秒部分 |
| `lastOpTime` | `updatedAt` | 同上 |
| `contentType` | `category` | — |
| `thumbnailUrl` | `thumbnailUrls[]` | 取 `style == "Large"` 的那条 |

- **成功判定**：`success == true`（唯一一个检查 `success` 的接口）
- **分页**：`pageCursor` 游标，非空且 `items.size >= 100` 时认为还有下一页
- ⚠️ `Yun139ListResult.totalCount` **恒为 0**（未解析）

> 代码位置：`Yun139ApiClient.kt:138-188`

---

#### 2) `getVideoPreviewUrl(fileId)` — 获取播放地址 ★

```
POST https://personal-kd-njs.yun.139.com/hcy/videoPreview/getPreviewInfo
Body:
{
  "category": "video",
  "expireSec": 14400,        // 4 小时有效期
  "fileId": "<fileId>",
  "qualityList": null        // ⚠️ 传 null → 不返回多清晰度
}
```

**响应**：`{ data: { previewInfo: { url: "https://..." } } }`

**取值路径**（两级兜底）：
```
data.previewInfo.url  →  失败则  json.playUrl
```

- **无清晰度选项**：`qualityList` 传 null，服务端只回单个地址，因此本模块**不支持切清晰度**，也不写 `VideoQualityCache`
- ⚠️ **不检查 `success`**，且解析失败时返回**空字符串而非异常**（见 §8 问题 1）

> 代码位置：`Yun139ApiClient.kt:194-204`

---

#### 3) `getDownloadUrl(fileId, fileName)` — 获取下载直链

```
POST https://personal-kd-njs.yun.139.com/hcy/file/getDownloadUrl
Body: { "fileId": "<fileId>", "fileName": "<文件名>" }
```

**响应**：`{ data: { url: "https://..." } }`
**取值路径**：`data.url` → 兜底 `json.downloadUrl`

- 同时用于**下载**和**图片预览**（`resolveImageUrl`）
- ⚠️ 同样不检查 `success`，失败返回空串

> 代码位置：`Yun139ApiClient.kt:206-214`

---

#### 4) `createFolder(parentFolderId, folderName)` — 创建文件夹

```
POST https://personal-kd-njs.yun.139.com/hcy/file/create
Body:
{
  "contentType": null, "description": null, "fileId": null,
  "fileRenameMode": null, "name": "<folderName>", "ownerId": null,
  "parentFileId": "<parentFolderId>", "parentPath": null,
  "type": "folder"
}
```

- **成功判定**：无（**恒返回 `true`**）

> 代码位置：`Yun139ApiClient.kt:216-230`

---

#### 5) `renameFile(fileId, newName)` — 重命名

```
POST https://personal-kd-njs.yun.139.com/hcy/file/update
Body:
{
  "FileRenameMode": null,      // ⚠️ 大写 F，与其他字段的 camelCase 不一致（照抄客户端）
  "description": null,
  "fileId": "<fileId>",
  "name": "<newName>"
}
```

- **成功判定**：无（恒 `true`）

> 代码位置：`Yun139ApiClient.kt:232-241`

---

#### 6) `deleteFiles(fileIds)` — 批量删除到回收站

```
POST https://personal-kd-njs.yun.139.com/hcy/recyclebin/batchTrash
Body: { "fileIds": ["<id1>", "<id2>"] }
```

- **成功判定**：无（恒 `true`）
- 与 123 云盘不同：**这里只需要 fileId 数组**，不需要提交完整原始对象

> 代码位置：`Yun139ApiClient.kt:243-249`

---

#### 7) `moveFiles(fileIds, targetFolderId)` — 批量移动

```
POST https://personal-kd-njs.yun.139.com/hcy/file/batchMove
Body: { "fileIds": ["<id1>"], "toParentFileId": "<目标目录 id>" }
```

- **成功判定**：无（恒 `true`）

> 代码位置：`Yun139ApiClient.kt:251-258`

---

#### 8) `refreshToken()` — 刷新凭证

见 §3.6。

---

## 5. 播放链路

### 5.1 解析时序

```
用户点击视频
    │
    ▼
Yun139BrowserViewModel.resolveVideoUri()
    │  cloud://yun139/{fileId}
    ▼
CloudUriResolver.resolve(uri)
    │  CloudPlaylistCache 命中则直接返回
    ▼
CloudUriResolver.resolveYun139(fileId)          [CloudUriResolver.kt:400-418]
    │
    │  ① Yun139AuthProvider.isActive?  否 → return null
    │  ② clearOtherProviders("yun139")  ← 清其他网盘 cookie/token，防串号
    │  ③ CloudPlayHeaders.registerSuffix(".139.com") { getPlayHeaders() }
    │  ④ 读 CloudPlaylistCache.getFileMetadata("yun139", fileId)（可选）
    │  ⑤ new Yun139ApiClient()
    │
    ├─ getVideoPreviewUrl(fileId)
    │     └─ 非空 → return url + "#yun139Play=true#"
    │
    └─ 失败 → fileMetadata != null ?
          └─ getDownloadUrl(fileId, fileName)
                └─ 非空 → return url + "#yun139Play=true#"
    │
    ▼
返回 null
```

### 5.2 播放请求头注入

`Yun139AuthProvider.getPlayHeaders()`（`Yun139AuthProvider.kt:36-48`）：

| Header | 值 |
|---|---|
| `Authorization` | `Basic base64(mobile:phone:token)` |
| `x-yun-device-id` | 设备指纹串 |
| `x-yun-client-info` | 设备指纹串 |
| `x-yun-api-version` | **`v2`**（注意：API 请求用的是 `v1`） |
| `x-yun-svc-type` | `1` |
| `x-yun-module-type` | `100` |
| `x-yun-app-channel` | `10000023` |

`isActive == false` 时返回空 Map。

**注入路径有两条**（`AuthAwareDataSourceFactory.kt`）：
1. **精确命中**：URI fragment 含 `yun139Play` 且 `isActive` → 注入上表 7 个头（`:234-243`）
2. **兜底**：fragment 未命中、域名也没注册，但 `Yun139AuthProvider.isActive` → 注入精简版（`:271-`），用于 HLS `.ts` 分片请求

**CDN 域名后缀注册点**（两处，不一致）：

| 位置 | 注册的后缀 |
|---|---|
| `Yun139BrowserViewModel`（autoLogin :156-157、loginWithWeb :201-202） | `.139.com`、**`cmecloud.cn`** |
| `CloudUriResolver.resolveYun139` :405 | 仅 `.139.com` |

> ⚠️ 播放路径**漏了 `cmecloud.cn`**。如果实际播放域名落在 `*.cmecloud.cn`，只有浏览页注册过、播放时没注册，请求头会缺失。

### 5.3 与 123 云盘的能力对比

| 能力 | 移动云盘 | 123 云盘 |
|---|---|---|
| 端点获取 | 硬编码 | `getconfig` 动态下发 |
| 核心凭证 | `authorization`（Basic 自编码） | `Bearer <token>` |
| 清晰度切换 | ❌ 不支持（`qualityList: null`） | ✅ 原画 + 多档转码 |
| 播放兜底 | 预览地址 → 下载地址 | 并行 HEAD + getVideoPlayInfo → 下载兜底 |
| HLS 错误回退 | ❌ 无 | ✅ `Pan123FallbackCache` |
| 画质刷新 | ❌ 无 | ✅ `refreshPan123QualityUrls` |
| 登录方式 | WebView + Cookie 轮询 | 账号密码 / Token |

---

## 6. 下载链路

`Yun139BrowserViewModel.downloadFile()`（`:625-686`）：

```
① apiClient.getDownloadUrl(res.path, res.name)
② cloudDownloadRepository.download(
       url = <直链>,
       fileName = res.name,
       headers = Yun139AuthProvider.getDownloadHeaders(),   // ⚠️ 不是 getPlayHeaders
       provider = "yun139"
   )
③ 通过 downloadEvents 流更新进度 UI
```

**下载头**（`Yun139AuthProvider.kt:50-61`）：

| Header | 值 |
|---|---|
| `x-NetType` | `1` |
| `x-DeviceInfo` | 设备指纹串 |
| `x-SvcType` | `1` |
| `x-huawei-channelSrc` | `10000023` |
| `x-MM-Source` | `0000` |
| `User-Agent` | `okhttp/4.12.0` |

> ⚠️ 注意：下载头里**没有 `Authorization`**，且命名风格（`x-NetType`）与播放头（`x-yun-net-type`）完全不同。这是复刻客户端两套不同实现导致的，改动需实测。

---

## 7. 数据模型

### `Yun139FileItem`

| 字段 | 类型 | 来源 |
|---|---|---|
| `fileId` | String | `fileId` |
| `fileName` | String | `name` |
| `fileSize` | Long | `size` |
| `isDir` | Boolean | `type == "folder"` |
| `createDate` | String | `createdAt`（去毫秒） |
| `lastOpTime` | String | `updatedAt`（去毫秒） |
| `contentType` | String | `category` |
| `thumbnailUrl` | String? | `thumbnailUrls` 中 `style == "Large"` 的 url |

**计算属性**：
- `isVideo` = 扩展名 ∈ {mp4, mkv, avi, mov, wmv, flv, webm, m4v, ts, rmvb}（忽略大小写）
- ⚠️ 没有 `isImage` 属性（123 云盘有）

### 其他模型

| 模型 | 字段 | 状态 |
|---|---|---|
| `Yun139ListResult` | `items`、`totalCount`（**恒 0**）、`nextMarker`（= `nextPageCursor`） | ✅ 在用 |
| `Yun139QualityOption` | `resolution`、`url` | ⚠️ **无调用方** |
| `Yun139VideoPreview` | `url`、`quality` | ⚠️ **无调用方** |
| `Yun139Crypto` | AES/CBC/PKCS5Padding 加解密 | ⚠️ **无调用方** |

---

## 8. 已知问题与改进建议

| # | 问题 | 位置 | 影响 | 建议 |
|---|---|---|---|---|
| 1 | **空 URL 被当成成功**：`getVideoPreviewUrl` / `getDownloadUrl` 在解析不到时返回**空字符串**，`resolveYun139` 用 `!= null` 判断 → 会返回只有 `"#yun139Play=true#"` 的无效地址 | `Yun139ApiClient.kt:194-214`<br>`CloudUriResolver.kt:410-415` | 🔴 高：播放/下载静默失败 | 改为 `isNotBlank()` 判断，或让接口在空值时抛异常 |
| 2 | **`Result` 从不失败**：`createFolder`/`renameFile`/`deleteFiles`/`moveFiles` 不检查 `success`，恒返回 `true` | `Yun139ApiClient.kt:216-258` | 🔴 高：UI 提示"删除成功"但实际失败 | 统一检查 `success` 字段 |
| 3 | **Response 未关闭**：`apiPost` 用 `executeRequestAndGetResponse` 但没 `use {}` | `Yun139ApiClient.kt:127-130` | 🟡 中：连接泄漏 | 改用 `executeRequest`（基类已内置关闭 + 状态码检查） |
| 4 | **跳过 HTTP 状态码检查**：基类 `executeRequest` 会校验非 2xx，这里绕过了 → 401 返回的 HTML 被当 JSON 解析，只能靠字符串匹配猜错误原因 | `Yun139ApiClient.kt:116-132`<br>`ViewModel:304-316` | 🟡 中：错误提示靠猜 | 走 `executeRequest`，改用结构化错误 |
| 5 | **`randomHex` 字符集不合法**：`"0123456789ABCDEFG"` 是 17 个字符且含非十六进制字符 `G` | `Yun139AuthProvider.kt:22-27` | 🟡 中：生成的设备 UUID 不是合法 hex，可能被风控识别 | 改为 `"0123456789ABCDEF"` |
| 6 | **机型列表有空格**：`"2312DR AABI"`、`"2312DR AABG"` 疑似笔误（123 云盘模块里是 `2312DRAABI`/`2312DRAABG`） | `Yun139AuthProvider.kt:63-67` | 🟢 低：设备指纹异常 | 去掉空格 |
| 7 | **`cmecloud.cn` 只在浏览路径注册**，播放路径没注册 | 见 §5.2 | 🟡 中：该域名下的播放请求可能缺头 | 播放路径补注册，或抽成统一常量表 |
| 8 | **`refreshToken` 失败静默**：返回 `Result<Unit>`，token 为空时什么都不做，调用方 `catch (_: Exception) {}` | `Yun139ApiClient.kt:264-295`<br>`ViewModel:166-179` | 🟡 中：token 过期无感知 | 返回 `Result<String>`，失败时提示用户重登 |
| 9 | **`ud_id` 强制但从未校验**：轮询要求 `ud_id` cookie，但登录判定、autoLogin 都不看 `userDomainId` | `TabContent:259`<br>`ViewModel:150` | 🟡 中：若该 cookie 因故缺失，登录页永久空转；若服务端不强制，则是多余约束 | **实测确认服务端是否校验 `x-yun-uni`**，据此决定放宽或保留 |
| 10 | **`autoLogin` 不校验 token 有效性**，失效时先失败一次才提示 | `ViewModel:143-153` | 🟢 低：体验问题 | 可加一次轻量探测请求 |
| 11 | **3 个类是死代码**：`Yun139Crypto`、`Yun139QualityOption`、`Yun139VideoPreview` 无任何引用 | — | 🟢 低：维护负担 | 删除，或补上清晰度功能 |
| 12 | **`totalCount` 恒为 0**，UI 拿不到总数 | `Yun139ApiClient.kt:187` | 🟢 低 | 解析 `data.total` 或直接删字段 |
| 13 | **`apiPost` 里 `JSONObject(text)` 无 try-catch**，非 JSON 响应直接抛 | `Yun139ApiClient.kt:131` | 🟢 低 | 包一层给出可读错误 |

---

## 9. 缓存体系

| 缓存 | 作用域 | 内容 |
|---|---|---|
| `CloudPlaylistCache` | 进程内 | 解析后播放 URL；文件元数据（移动云盘只需 `fileName` + `parentPath`，**不需要 etag/size**） |
| `CloudDirectoryCache` | 磁盘 | 目录列表，按 `("yun139", parentFileId)` 缓存 |
| `directoryCache` | ViewModel 实例内 | 一级内存目录缓存 |
| `directoryCursorCache` | ViewModel 实例内 | 各目录的下一页游标 |
| `VideoQualityCache` | — | ❌ 移动云盘未使用（无清晰度选项） |

**元数据写入时机**：`loadDirectory()` 遍历视频文件时为每个 `isVideo` 项写入（`ViewModel:273-283`），`parentPath` 格式为 `"<当前目录id>|<标签>|<fileId>/<标签>|<fileId>..."`，用于后续「跳转到文件夹」。

---

## 10. 调试

相关日志 TAG：

| TAG | 位置 |
|---|---|
| `Yun139Api` | `Yun139ApiClient` 接口层 |
| `Yun139BrowserVM` / `Yun139VM` | UI 层 |
| `CloudUriResolver` | 播放链解析 |
| `AuthAwareDataSource` | 播放请求头注入 |

```bash
adb logcat | grep -E "Yun139Api|Yun139BrowserVM|CloudUriResolver|AuthAwareDataSource"
```

登录问题重点看 `Yun139BrowserTabContent` 的 `onPageStarted` / `onPageFinished` / `Credential extracted` / `Blocked external scheme`。

---

## 附录：关键文件行号索引

| 内容 | 文件:行 |
|---|---|
| Base URL / UA 常量 | `Yun139ApiClient.kt:18-20` |
| 凭证读取 (`auth`/`uni`/`devInfo`) | `Yun139ApiClient.kt:25-27` |
| `setToken` / `logout` | `Yun139ApiClient.kt:29` / `:36` |
| 四套请求头 | `Yun139ApiClient.kt:49-110` |
| `apiPost`（含泄漏问题） | `Yun139ApiClient.kt:116-132` |
| `listFiles` | `Yun139ApiClient.kt:138` |
| `getVideoPreviewUrl` | `Yun139ApiClient.kt:194` |
| `getDownloadUrl` | `Yun139ApiClient.kt:206` |
| `createFolder` / `renameFile` | `Yun139ApiClient.kt:216` / `:232` |
| `deleteFiles` / `moveFiles` | `Yun139ApiClient.kt:243` / `:251` |
| `refreshToken` | `Yun139ApiClient.kt:264` |
| 设备指纹生成 | `Yun139AuthProvider.kt:11-27` |
| 播放头 / 下载头 | `Yun139AuthProvider.kt:36-48` / `:50-61` |
| `resolveYun139` | `CloudUriResolver.kt:400-418` |
| 播放头注入（精确） | `AuthAwareDataSourceFactory.kt:234-243` |
| 播放头注入（兜底） | `AuthAwareDataSourceFactory.kt:271-` |
| `autoLogin`（只判 authorization） | `Yun139BrowserViewModel.kt:143-180` |
| `loginWithWeb` | `Yun139BrowserViewModel.kt:186-218` |
| `logout` | `Yun139BrowserViewModel.kt:224-245` |
| `downloadFile` | `Yun139BrowserViewModel.kt:625-686` |
| Cookie 轮询（含 `ud_id` 约束） | `Yun139BrowserTabContent.kt:250-270` |
| WebView 登录页 | `Yun139BrowserTabContent.kt:235-392` |
| PC UA 常量 | `Yun139BrowserTabContent.kt:392` |
| 备份读写 | `BackupManager.kt:446-472` |
