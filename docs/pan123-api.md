# 123 云盘（pan123）接口文档

> 适用范围：Flux Player `pan123` 云盘模块
> 文档生成时间：2026-08-31（2026-09-01 重建）
> 代码基线：`core/data/.../pan123/`、`core/data/.../cloud/CloudUriResolver.kt`、`feature/videopicker/.../pan123/`

---

## 0. 一句话概览

Flux Player 的 123 云盘模块**没有使用官方 OpenAPI**，而是复刻了 **123 云盘 Android 客户端 v3.1.3 的私有协议**（部分逻辑 1:1 移植自「海阔视界」的 `main.js`）。核心特征：

- **双 API 域**：Android 端 API（`api.123278.com/api`）+ Web 端 API（`api.123278.com/b/api`）
- **端点动态下发**：大部分接口地址不硬编码，由 `apigate.123795.com` 的 `getconfig` 接口下发的 `interfaceapi` 映射表决定
- **播放双通道**：`getVideoPlayInfo` 拿多清晰度（原画 + 转码），`fileDownloadInfo` + HEAD 重定向拿 CDN 直链，互为兜底
- **Referer 靠解密**：CDN 直链的 `ref` 查询参数是 AES-CBC 加密的，解密后才是真正的 `Referer`

---

## 1. 模块地图

```
core/data/src/main/java/com/fluxplayer/app/core/data/pan123/
├── Pan123ApiClient.kt        ★ 全部接口实现（860 行）
├── Pan123AuthProvider.kt      播放鉴权状态单例（给 ExoPlayer 注入 header）
├── Pan123FileItem.kt          网盘文件/文件夹模型
├── Pan123ListResult.kt        列表结果包装
├── Pan123ShareFileItem.kt     分享链接内文件模型
├── Pan123ShareListing.kt      分享列表结果
├── Pan123UserInfo.kt          用户信息模型
├── VideoPlayResult.kt         多清晰度播放结果
├── DownloadInfo.kt            下载直链信息
└── LoginResult.kt             登录结果

core/data/src/main/java/com/fluxplayer/app/core/data/
├── BaseCloudApiClient.kt      请求基类（executeRequest / buildUrl）
├── CloudHttpClient.kt         共享 OkHttp 实例
└── cloud/CloudUriResolver.kt  ★ 播放链解析（resolvePan123）

feature/videopicker/.../pan123/
├── Pan123BrowserViewModel.kt  ★ UI 层调用入口（登录/浏览/下载/转存）
├── Pan123BrowserUiState.kt    UI 状态
└── Pan123BrowserTabContent.kt Compose 界面

core/common/
├── Pan123FallbackCache.kt     HLS 兜底 URL 缓存
├── CloudPlayHeaders.kt        CDN 域名 → 播放 header 注册表
├── CloudPlaylistCache.kt      解析后 URL / 文件元数据缓存
└── VideoQualityCache.kt       清晰度选项缓存
```

**依赖关系**：`Pan123BrowserViewModel` → `Pan123ApiClient`（浏览/下载/转存）+ `CloudUriResolver`（播放）；`CloudUriResolver` → `Pan123ApiClient`（播放链）。

---

## 2. 基础设施

### 2.1 三个 Base URL

| 常量 | 值 | 用途 |
|---|---|---|
| `CONFIG_URL` | `https://apigate.123795.com/getconfig-api/v1/getconfig?platform=android&version=313&channel=1003&env=` | 下发接口地址映射表 |
| `WEB_API_BASE` | `https://api.123278.com/b/api` | Web 端接口（目前只有列目录走这里） |
| `API_BASE` | `https://api.123278.com/api` | Android 端硬编码兜底端点 |

> 文件位置：`Pan123ApiClient.kt:28-30`

### 2.2 动态端点（getconfig）

`loadConfig()` 请求 `CONFIG_URL`，取 `data` 字段作为配置缓存，之后所有接口通过 `apiEndpoint(key)` 从 `data.interfaceapi` 里取真实 URL。

**代码中用到的 key 清单**：

| config key | 对应调用方法 | 失败时的兜底 |
|---|---|---|
| `login` | `login()` | 无（直接抛错） |
| `fileDownloadInfo` | `getFileDownloadUrl()` / `getFileDownloadInfo()` | 无 |
| `getVideoPlayInfo` | `getVideoPlayInfo()` | 无 |
| `modifyFileName` | `renameFile()` | 无 |
| `recycleDeleteFile` | `trashFile()` | `$API_BASE/file/trash` |
| `getUserInfo` | `getUserInfo()` | 无 |
| `shareFileDetails` | `listShareFiles()` | 无 |
| `copySaveFiles` | `copySaveFiles()` | 无 |

**缓存策略**（`Pan123ApiClient.kt:33-35, 128-157`）：
- 静态 `companion object` 变量，**所有 `Pan123ApiClient` 实例共享**
- TTL **10 分钟**
- 用 `Mutex` + 双重检查锁防并发重复请求
- `setToken(null/blank)` 会连带清空 config 缓存

> ⚠️ 注意：如果 `getconfig` 挂了，几乎所有接口都会抛 `IllegalStateException: Config not loaded`。这是 123 云盘的**单点依赖**。

### 2.3 两套请求头

**Android 端头**（`buildHeaders()`，`Pan123ApiClient.kt:172`）—— 用于 `apiPost` / `apiGet`：

| Header | 值 |
|---|---|
| `authorization` | `Bearer <token>`（仅已登录时） |
| `x-channel` | `1003` |
| `user-agent` | `123pan/v3.1.3(Android_10;Xiaomi)` |
| `content-type` | `application/json; charset=UTF-8` |
| `osversion` | 随机 `Android_13/14/15/16` |
| `loginuuid` | 随机 UUID（去横线，实例级，构造时生成） |
| `platform` | `android` |
| `devicetype` | 随机设备型号（19 款小米/红米机型之一） |
| `devicename` | `Xiaomi` |
| `host` | `www.123pan.com` |
| `app-version` | `313` |
| `x-app-version` | `3.1.3` |

**Web 端头**（`buildWebHeaders()`，`Pan123ApiClient.kt:189`）—— 用于 `webGet`：

| Header | 值 |
|---|---|
| `authorization` | `Bearer <token>` |
| `content-type` | `application/json; charset=UTF-8` |
| `platform` | `web` |
| `app-version` | `3` |
| `Referer` | `https://yun.123pan.cn/` |

> **设备指纹随机化**：`uuid` / `deviceType` / `osVersion` 在 `Pan123ApiClient` **构造时**随机生成（`Pan123ApiClient.kt:104-106`）。而 `Pan123BrowserViewModel` 与 `CloudUriResolver` 各自 `new` 一个实例 → **同一会话内浏览和播放用的设备指纹可能不同**。

### 2.4 HTTP 客户端

`CloudHttpClient.DEFAULT`（`CloudHttpClient.kt:8-14`）：
- 连接 10s / 读 15s / 写 10s
- `followRedirects = true`（**关键**：`resolveDownloadUrlViaHead` 依赖它跟随 302）
- 共享 `GlobalCookieJar`、连接池 10 个 / 10 分钟

请求执行统一走 `BaseCloudApiClient.executeRequest()`：非 2xx 直接抛 `HTTP {code} from {url}`，空响应体抛 `Empty response`。

---

## 3. 认证与登录

### 3.1 凭据存储

`SharedPreferences` 名为 **`pan123`**（`Pan123BrowserViewModel.kt:63`）：

| Key | 类型 | 说明 |
|---|---|---|
| `token` | String | 登录 token（不含 `Bearer ` 前缀） |
| `passport` | String | 手机号（仅账号密码登录时存） |
| `password` | String | 密码（仅账号密码登录时存） |
| `refresh_token_expire_time` | Long | 过期时间戳（秒） |
| `lastClipboardShareHash` | String | 剪贴板分享链接去重哈希 |

### 3.2 三种登录入口

| 入口 | 方法 | 触发时机 |
|---|---|---|
| 账号密码登录 | `login(passport, password)` | 用户手动输入 / 自动重登 |
| Token 登录 | `loginWithToken(token)` | 用户粘贴 `Bearer xxx` |
| 自动恢复 | `autoLogin()` | ViewModel `init` 里调用 |

**`autoLogin()` 决策流程**（`Pan123BrowserViewModel.kt:145-186`）：

```
token 非空?
├─ 是 → 已过期(now > expire) 且 有账号密码?
│        ├─ 是 → login(passport, password) 重新登录
│        └─ 否 → setToken("Bearer $token")
│                + 预热 loadConfig()（异步，写静态缓存）
│                + 激活 Pan123AuthProvider
│                + 注册 CDN header 后缀
│                + 预填根目录缓存 → loadDirectory("0")
└─ 否 → 有账号密码?
         ├─ 是 → login(passport, password)
         └─ 否 → 显示登录表单
```

### 3.3 登出

`logout()`（`Pan123BrowserViewModel.kt:255-271`）依次做：
1. `Pan123AuthProvider.clear()`
2. `GlobalCookieJar.clearHost("api.123278.com" | "apigate.123795.com")`
3. `apiClient.setToken("")` → 顺带清空静态 config 缓存
4. `prefs.edit().clear()` 清空所有凭据
5. 清空目录缓存 + `CloudDirectoryCache.clear(..., "pan123")`
6. 重置 UI 状态与导航栈

### 3.4 备份/恢复

`BackupManager`（`BackupManager.kt:402-420`）读写同一份 SharedPreferences，字段为 `passport / password / token`，模型为 `Pan123BackupConfig`。

---

## 4. 接口清单

### 4.1 总表

| # | 方法 | HTTP | 端点 | 请求头组 | 用 config key |
|---|---|---|---|---|---|
| 1 | `loadConfig` | GET | `apigate.123795.com/getconfig-api/v1/getconfig` | 无自定义 | — |
| 2 | `login` | POST | config: `login` | Android | ✅ |
| 3 | `listFiles` | GET | `api.123278.com/b/api/file/list/new` | **Web** | ❌ |
| 4 | `getVideoPlayInfo` | POST | config: `getVideoPlayInfo` + query | Android | ✅ |
| 5 | `getFileDownloadInfo` | POST | config: `fileDownloadInfo` | Android | ✅ |
| 6 | `getFileDownloadUrl` | POST | config: `fileDownloadInfo` | Android | ✅ |
| 7 | `createFolder` | POST | `api.123278.com/api/file/upload_request` | Android | ❌ |
| 8 | `renameFile` | POST | config: `modifyFileName` | Android | ✅ |
| 9 | `moveFile` | POST | `api.123278.com/api/file/mod_pid` | Android | ❌ |
| 10 | `copyFile` | POST | `api.123278.com/api/restful/goapi/v1/file/copy/async` | Android | ❌ |
| 11 | `trashFile` | POST | config: `recycleDeleteFile` | Android | ✅ |
| 12 | `getUserInfo` | GET | config: `getUserInfo` | Android | ✅ |
| 13 | `listShareFiles` | GET | config: `shareFileDetails` + query | Android | ✅ |
| 14 | `copySaveFiles` | POST | config: `copySaveFiles` | Android | ✅ |

> 第 10 项 `copyFile` 目前**无任何调用方**（死代码）。

---

### 4.2 逐个接口详解

#### 1) `loadConfig()` — 拉取接口映射表

```
GET https://apigate.123795.com/getconfig-api/v1/getconfig
    ?platform=android&version=313&channel=1003&env=
```

- **返回**：`{ data: { interfaceapi: { login: "...", getVideoPlayInfo: "...", ... } } }`
- **成功判定**：HTTP 200 且能解析出 `data`
- **副作用**：写入静态缓存 `cachedConfig` + `configTimestamp`

---

#### 2) `login(passport, password)` — 账号密码登录

```
POST {config.interfaceapi.login}
Headers: Android 组（此时无 authorization）
Body: { "passport": "<手机号>", "password": "<密码>", "type": 1 }
```

**响应**：
```json
{ "message": "success", "data": { "token": "eyJ...", "refresh_token_expire_time": 1234567890 } }
```

- **成功判定**：`message == "success"` 且 `data.token` 非空
- **副作用**：`authToken = "Bearer $token"`
- **返回**：`LoginResult(token, refreshTokenExpireTime)`

> 代码位置：`Pan123ApiClient.kt:257-281`

---

#### 3) `listFiles(...)` — 列目录（**唯一走 Web API 的接口**）

```
GET https://api.123278.com/b/api/file/list/new
    ?driveId=0&limit=100&page={page}&orderBy={orderBy}&orderDirection={orderDirection}
    &parentFileId={parentFileId}&trashed=false&SearchData={searchData}
    &OnlyLookAbnormalFile=0&event=homeListFile&operateType=1
    &inDirectSpace=false&fileCategory=0&isSearchOrder=false
Headers: Web 组
```

**为什么用 Web API**：Web 版的 `DownloadUrl` 字段带 `trade_key=123pan-thumbnail` 缩略图参数，Android 版没有。

**响应**：
```json
{
  "code": 0,
  "data": {
    "InfoList": [
      {
        "FileId": 12345678, "FileName": "movie.mkv",
        "Type": 0,                  // 1=文件夹, 0=文件
        "Size": 4294967296, "Category": 4,
        "Etag": "abc...", "S3KeyFlag": "...",
        "DownloadUrl": "https://...", "Thumbnail": "",
        "CreateAt": "2026-01-01 12:00:00", "TrashedAt": "", "StarredStatus": 0
      }
    ]
  }
}
```

- **成功判定**：`code == 0`
- **缩略图提取规则**：优先 `Thumbnail`，否则若 `DownloadUrl` 含 `trade_key=123pan-thumbnail` 就用它，否则 `null`
- **分页**：`page` 递增；`Pan123ListResult.nextCursor` **恒为 `null`**，UI 侧靠 `items.size >= 100` 判断 `hasMore`
- **搜索**：`searchData` 非空即为全局搜索，此时 `parentFileId` 固定 `"0"`

> 代码位置：`Pan123ApiClient.kt:291-352`

---

#### 4) `getVideoPlayInfo(item)` — 获取多清晰度播放地址 ★

```
POST {config.interfaceapi.getVideoPlayInfo}?etag={etag}&size={size}
Headers: Android 组
Body: 无（GET 语义但用 POST 发送）
```

**响应**：
```json
{
  "code": 0,
  "data": {
    "url": "https://cdn.../original.mp4",
    "video_play_info": [
      { "resolution": "1080P", "url": "https://.../1080.m3u8" },
      { "resolution": "720P",  "url": "https://.../720.m3u8" }
    ]
  }
}
```

**解析结果 `VideoPlayResult`**：

| 顺序 | URL 来源 | 标签 |
|---|---|---|
| 第 1 个 | `data.url` | 固定 `"原画"` |
| 第 2..N 个 | `data.video_play_info[i].url` | `resolution` 字段，缺失则 `"转码{i+1}"` |

- **成功判定**：`data != null` 且至少解析出一个非空 URL
- **容错**：`video_play_info` 逐条 try-catch，单条 url 为空或解析失败只 `continue` 跳过，不破坏整链
- **必须参数**：`etag` 和 `size`（来自列表接口的元数据缓存）

> 代码位置：`Pan123ApiClient.kt:391-458`

---

#### 5) `getFileDownloadInfo(item)` — 获取下载信息（完整请求体）

```
POST {config.interfaceapi.fileDownloadInfo}
Body: { "driveId": 0, "etag": "...", "fileId": 123, "s3keyFlag": "...",
        "FileName": "...", "Size": 123, "type": 0 }
```

**响应**：`{ data: { DownloadUrl, fileName, size } }`
**成功判定**：`data.DownloadUrl` 非空

> 代码位置：`Pan123ApiClient.kt:463-500`

---

#### 6) `getFileDownloadUrl(item)` — 获取下载直链（精简版）

与 #5 **同一端点**，但请求体少了 `FileName` / `Size`（大写）。直接返回 `data.DownloadUrl` 字符串。

> ⚠️ 两个方法功能重叠，`getFileDownloadUrl` 仅用于 `CloudUriResolver` 的**最后兜底**。
> 代码位置：`Pan123ApiClient.kt:354-385`

---

#### 7) `createFolder(name, parentFileId)` — 创建文件夹

```
POST https://api.123278.com/api/file/upload_request
Body: { "driveId": 0, "parentFileId": "...", "duplicate": 1, "NotReuse": true,
        "etag": "", "fileName": "...", "size": 0, "type": 1 }
```

- **成功判定**：无（**恒返回 `true`**）
- 端点用的是上传申请接口 + `type: 1`，这是复刻客户端行为

> 代码位置：`Pan123ApiClient.kt:541-556`

---

#### 8) `renameFile(fileId, newName)` — 重命名

```
POST {config.interfaceapi.modifyFileName}
Body: { "driveId": 0, "fileName": "<newName>", "fileId": 123 }
```

- **成功判定**：`message == "ok"`

> 代码位置：`Pan123ApiClient.kt:558-568`

---

#### 9) `moveFile(fileId, parentFileId)` — 移动

```
POST https://api.123278.com/api/file/mod_pid
Body: { "fileIdList": [ { "FileId": 123 } ], "parentFileId": "<目标目录 id>" }
```

- **成功判定**：无（恒 `true`）

> 代码位置：`Pan123ApiClient.kt:570-580`

---

#### 10) `copyFile(...)` — 复制（**未被调用**）

```
POST https://api.123278.com/api/restful/goapi/v1/file/copy/async
Body: { "fileList": [{ fileId, size, etag, type, parentFileId, fileName }],
        "targetFileId": "<目标 id 字符串>" }
```

> 代码位置：`Pan123ApiClient.kt:582-602`

---

#### 11) `trashFile(items)` — 删除到回收站

```
POST {config.interfaceapi.recycleDeleteFile}      // 兜底: /api/file/trash
Body: { "driveId": 0, "fileTrashInfoList": [ <列表接口返回的完整原始 JSONObject> ],
        "operation": true }
```

- **成功判定**：`message == "ok"`（**严格**，其他一律抛异常）
- ⚠️ **关键坑（代码注释已写明）**：
  1. `fileTrashInfoList` 必须提交列表接口返回的**完整原始对象**（含 `Pid` / `Status` / `Category` / `CreateAt` 等全部字段）。只拼部分字段会出现「**返回成功但实际没删**」
  2. `FileId` / `Size` 等数值字段**必须保持 JSON number 类型**，不能序列化成字符串
  - 这也是 `Pan123FileItem` 里保留 `raw: JSONObject?` 字段的唯一原因（`Pan123FileItem.kt:18-23`）
- 无 `raw` 时的兜底构造会尝试 `fileId.toLongOrNull() ?: fileId` 保数值类型

> 代码位置：`Pan123ApiClient.kt:617-660`

---

#### 12) `getUserInfo()` — 账号信息

```
GET {config.interfaceapi.getUserInfo}
```

**响应字段映射**：

| 模型字段 | JSON 路径 |
|---|---|
| `nickname` | `data.Nickname` |
| `uid` | `data.UID` |
| `spaceUsed` | `data.SpaceUsed` |
| `spacePermanent` | `data.SpacePermanent` |
| `isVip` | `data.Vip` |
| `headImage` | `data.HeadImage` |
| `vipDesc` | `data.UserVipDetailInfos[0].VipDesc` |
| `vipTimeDesc` | `data.UserVipDetailInfos[0].TimeDesc` |

> 代码位置：`Pan123ApiClient.kt:666-685`

---

#### 13) `listShareFiles(shareKey, sharePwd, parentFileId)` — 读取分享链接内容

```
GET {config.interfaceapi.shareFileDetails}
    ?Page=1&limit=100&next=-1&ParentFileId={parentFileId}
    &shareKey={shareKey}&SharePwd={sharePwd}
    &orderBy=update_time&orderDirection=desc
```

- **成功判定**：`code == 0`
- 返回 `data.InfoList` → `List<Pan123ShareFileItem>`，`total` 取 `data.total`

**分享链接解析** `parseShareUrl()` 支持两种格式：

| 格式 | 示例 | shareKey | pwd |
|---|---|---|---|
| 短链 | `https://123865.com/s/u9izjv-SYpOv?pwd=Qiye` | `u9izjv-SYpOv` | `Qiye`（无 `pwd` 参数为 null） |
| mshare | `https://1840976528.mshare.123pan.cn/123pan/cHCOTd-jVoM` | `cHCOTd-jVoM` | `null` |

> 代码位置：`Pan123ApiClient.kt:740-819`

---

#### 14) `copySaveFiles(shareKey, sharePwd, files, targetFolderId)` — 转存

```
POST {config.interfaceapi.copySaveFiles}
Body:
{
  "share_key": "<key>", "share_pwd": "<pwd>",
  "file_list": [ { "drive_id": 0, "etag": "...", "file_id": "...",
                   "file_name": "...", "parent_file_id": "<目标目录>",
                   "size": 0, "type": 0 } ],
  "current_level": 1, "event": "transfer"
}
```

- **成功判定**：`code == 0`
- `files` 为空直接抛 `IllegalArgumentException`

> 代码位置：`Pan123ApiClient.kt:821-858`

---

## 5. 播放链路（最重要）

### 5.1 整体时序

```
用户点击视频
    │
    ▼
Pan123BrowserViewModel.resolveVideoUri()
    │  cloud://pan123/{fileId}
    ▼
CloudUriResolver.resolve(uri)
    │  ① CloudPlaylistCache 缓存命中?
    │     └─ 命中且缺失清晰度选项 → 后台异步补缓存（不阻塞）
    ▼
CloudUriResolver.resolvePan123(fileId)
    │  ② 读 SharedPreferences("pan123").token → 空则失败
    │  ③ new Pan123ApiClient(); setToken("Bearer $token")
    │  ④ clearOtherProviders("pan123")  ← 清理其他网盘的 cookie/token，防串号
    │  ⑤ 激活 Pan123AuthProvider；注册 CDN header 后缀
    │     .123295.com / .123pan.cn（后续按实际 host 动态追加）
    │  ⑥ 取 CloudPlaylistCache.getFileMetadata()（需 etag + size，缺一即失败）
    │
    ├─【并行】async A: resolveDownloadUrlViaHead(item)
    │         └─ getFileDownloadInfo() → HEAD 跟随重定向 → 最终 CDN URL
    │              副作用：解析 ref → AES 解密 → 设置 Pan123AuthProvider.referer
    │                      按实际 host 后缀注册 CloudPlayHeaders
    │
    └─【并行】async B: getVideoPlayInfo(item)
              └─ 原画 URL + 转码清晰度列表
    │
    ▼
处理结果：
    B 成功且有 URL?
    ├─ 是 → 全部清晰度写入 VideoQualityCache
    │       优先选「非 m3u8 / 非 /hls/」的 MP4 直链
    │       同时若存在 HLS → Pan123FallbackCache.put(fileId, hlsUrl)
    │       → return bestUrl
    └─ 否 → getFileDownloadUrl() 兜底 → return dlUrl
    │
    ▼
返回 URL + "#pan123Play=true#"  ← 供 PlayerService 识别错误回退
    │
    ▼
CloudPlaylistCache.putResolvedUrl() → ExoPlayer 播放
```

> 代码位置：`CloudUriResolver.kt:182-307`

### 5.2 播放错误 → HLS 自动回退

`PlayerService.onPlayerError()`（`PlayerService.kt:449-468`）：

```
URI 含 "#pan123Play=true#" 且 provider=="pan123"?
    └─ Pan123FallbackCache.get(fileId) 有 HLS URL?
        └─ 替换当前 MediaItem 的 URI 为 HLS URL + "#pan123Play=true#"
           → prepare() → play()
```

**背景**：部分 MKV 容器元数据损坏（SeekHead 偏移 > 实际文件大小），ExoPlayer seek 时 CDN 返回 **416** → Source error。HLS 没有容器级 seek 问题，所以作为备用路径（见 `Pan123FallbackCache.kt:3-8`）。

### 5.3 播放请求头注入

`Pan123AuthProvider.getPlayHeaders()` 返回：

| Header | 值 |
|---|---|
| `Referer` | 默认 `https://yun.123pan.cn/`；解析出则替换为解密后的真实 Referer |
| `User-Agent` | `123pan/v3.1.3(Android_10;Xiaomi)` |
| `X-MF-PAN-RANGE` | `1` |

`isActive == false` 时返回空 Map。

**CDN 域名后缀注册点**（三处，略有不一致）：

| 位置 | 注册的后缀 |
|---|---|
| `CloudUriResolver.resolvePan123():203-204` | `.123295.com`、`.123pan.cn` |
| `CloudUriResolver.resolvePan123():251` | 按 HEAD 实际 host 动态算出的后缀 |
| `Pan123BrowserViewModel` 登录/autoLogin（4 处） | `.123pan.cn`、`cjjd19.com` |

> ⚠️ **不一致点**：ViewModel 注册的是 `cjjd19.com`（无点前缀，`CloudPlayHeaders.registerSuffix` 会自动补 `.`），而 Resolver 注册的是 `.123295.com`。两处 CDN 域名列表没对齐，值得统一。

---

## 6. 下载链路

`Pan123BrowserViewModel.downloadFile()`（`Pan123BrowserViewModel.kt:660-735`）：

```
① cachedFileItems 里按 fileId 找回 Pan123FileItem
② 取直链（链式兜底）：
     getFileDownloadInfo().url  →  列表自带的 fileItem.downloadUrl  →  报错
③ resolveFinalDownloadUrl(url)：HEAD 跟随重定向拿最终 CDN URL
④ buildDownloadHeaders(finalUrl)：
     parseRefFromUrl() 提取 ref 参数
       → decryptRef() AES 解密 → Referer
       → 解密失败兜底 "https://yun.123pan.cn/"
⑤ CloudDownloadRepository.download(url, fileName, headers, provider="pan123")
```

**下载头**（`buildDownloadHeaders`，`Pan123ApiClient.kt:708-734`）：

| Header | 值 |
|---|---|
| `Referer` | AES 解密 `ref` 得到，失败兜底 `https://yun.123pan.cn/` |
| `X-MF-PAN-RANGE` | `1` |
| `User-Agent` | `123pan/v3.1.3(Android_10;Xiaomi)` |

---

## 7. `ref` 参数的 AES 解密

CDN 直链形如 `https://xxx.123295.com/...?ref=<cipher>&...`，`ref` 是加密的 Referer。

**算法**（`Pan123ApiClient.kt:53-99`）：

| 项 | 值 |
|---|---|
| 算法 | `AES/CBC/PKCS5Padding` |
| 密钥 | `pXce-DF4m7FnlftioS2nwg==`（源码硬编码常量） |
| IV | 密文字节的**前 16 字节** |
| 密文 | 从第 16 字节到结尾 |
| 编码 | URL-safe Base64（`-`→`+`，`_`→`/`），需补 `=` 到 4 的倍数 |

```
cipherText(Base64) → decode → [ IV(16B) | 密文 ] → AES 解密 → UTF-8 String = Referer
```

失败时兜底 `https://yun.123pan.cn/`，不抛异常。

---

## 8. 数据模型

### `Pan123FileItem`

| 字段 | 类型 | 来源 |
|---|---|---|
| `fileId` | String | `FileId` |
| `fileName` | String | `FileName` |
| `type` | Int | `Type`（1=文件夹，0=文件） |
| `size` | Long | `Size` |
| `category` | Int | `Category` |
| `etag` | String | `Etag` |
| `s3keyFlag` | String | `S3KeyFlag` |
| `downloadUrl` | String | `DownloadUrl` |
| `createAt` / `trashedAt` | String | `CreateAt` / `TrashedAt` |
| `starredStatus` | Int | `StarredStatus` |
| `thumbnailUrl` | String? | `Thumbnail` 或从 `DownloadUrl` 推导 |
| `raw` | JSONObject? | 列表返回的完整原始对象（删除接口必需） |

**计算属性**：
- `isDirectory` = `type == 1`
- `isVideo` = 文件名匹配 `\.(mp4|mkv|avi|rmvb|mov|flv|wmv|webm|m4v|ts)$`（忽略大小写）
- `isImage` = 匹配 `\.(jpg|jpeg|png|webp|gif|bmp)$`

### 其他模型

| 模型 | 字段 |
|---|---|
| `Pan123ListResult` | `items`、`nextCursor`（**恒 null**） |
| `VideoPlayResult` | `urls: List<String>`、`names: List<String>`（下标一一对应） |
| `DownloadInfo` | `url`、`fileName`、`size`、`headers`（当前实现未填充） |
| `LoginResult` | `token`、`refreshTokenExpireTime`（秒级时间戳） |
| `Pan123UserInfo` | 见 §4.2 #12 |
| `Pan123ShareFileItem` | `fileId`、`fileName`、`type`、`size`、`etag`；`isDirectory` = `type == 1` |
| `Pan123ShareListing` | `files`、`total` |

---

## 9. 缓存体系

| 缓存 | 作用域 | 生命周期 | 内容 |
|---|---|---|---|
| `cachedConfig`（static） | 进程内全局 | 10 分钟 / `setToken("")` 清空 | `getconfig` 返回的 `data` |
| `CloudPlaylistCache` | 进程内（含磁盘元数据） | ~15 分钟（resolvedUrl） | 已解析播放 URL、文件元数据（etag/size/s3keyFlag/downloadUrl） |
| `VideoQualityCache` | 进程内 | — | 每个 fileId 的清晰度选项列表 |
| `Pan123FallbackCache` | 进程内（内存 Map） | 进程存活 | fileId → HLS 兜底 URL |
| `CloudDirectoryCache` | 磁盘 | — | 目录列表，按 `("pan123", parentFileId)` 缓存 |
| `directoryCache`（ViewModel） | 实例内 | ViewModel 存活 | 同上，一级内存缓存 |

**关键依赖**：播放链要求 `CloudPlaylistCache` 里必须存在该 fileId 的 `etag` + `size`，否则 `resolvePan123` 直接返回 null。这两个值由 `loadDirectory()` / `loadMore()` 在遍历视频文件时写入（`Pan123BrowserViewModel.kt:300-314`、`393-407`）。

> ⚠️ 因此：**如果绕过浏览页直接播放一个 fileId（比如从历史记录冷启动），很可能因元数据缺失而失败。**

---

## 10. 已知问题与改进建议

| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| 1 | **设备指纹不一致**：`Pan123ApiClient` 实例级随机 uuid/机型，浏览与播放各建一个实例 | `Pan123ApiClient.kt:104-106` | 改为 `companion object` 全局单例指纹，或用 Hilt 提供单例 client |
| 2 | **CDN 域名后缀两处不一致**：`.123295.com` vs `cjjd19.com` | Resolver vs ViewModel | 抽成统一常量表 |
| 3 | `getFileDownloadUrl` 与 `getFileDownloadInfo` 打同一端点、功能重叠 | `Pan123ApiClient.kt:354 / 463` | 合并为一个方法 |
| 4 | `createFolder` / `moveFile` 恒返回 `true`，不校验响应 | `Pan123ApiClient.kt:541 / 570` | 加 `code == 0` 或 `message == "ok"` 判定 |
| 5 | `copyFile` 无任何调用方 | `Pan123ApiClient.kt:582` | 删除或补上复制 UI |
| 6 | `getconfig` 是单点依赖，挂了全盘接口不可用 | `Pan123ApiClient.kt:128` | 内置一份 `interfaceapi` 兜底映射表 |
| 7 | `buildUrl()` 不做 URL 编码，文件名/Etag 含特殊字符可能出问题 | `BaseCloudApiClient.kt:46-50` | 改用 `HttpUrl.Builder` |
| 8 | 日志里大量 `Log.d` 打印完整请求体（含 token 相关字段） | 全文 | Release 构建应关闭（当前 proguard 保留了所有 `Log.*`） |
| 9 | 播放依赖 `CloudPlaylistCache` 元数据，冷启动/历史播放易失败 | `CloudUriResolver.kt:208` | 元数据缺失时回退调一次 `listFiles` 补取 |
| 10 | 密码明文存在 SharedPreferences 且会进备份文件 | `Pan123BrowserViewModel.kt:198-204` | 考虑加密存储或至少在备份时可选脱敏 |

---

## 11. 调试

相关日志 TAG：

| TAG | 位置 |
|---|---|
| `Pan123Api` | `Pan123ApiClient` 全部接口 |
| `CloudUriResolver` | 播放链解析 |
| `Pan123BrowserVM` / `Pan123VM` | UI 层 |
| `PlayerService` | 播放错误 + HLS 回退 |

```bash
adb logcat | grep -E "Pan123Api|CloudUriResolver|Pan123BrowserVM|AuthAwareDataSource|PlayerService"
```

日志中 URL 统一经过 `sanitizeUrl()` 处理（脱敏），避免敏感签名泄漏到日志。

更详细的日志排查说明见：[`docs/123pan-debug-logs-guide.md`](./123pan-debug-logs-guide.md)

---

## 附录：关键文件行号索引

| 内容 | 文件:行 |
|---|---|
| Base URL 常量 | `Pan123ApiClient.kt:28-30` |
| AES 密钥 + 解密 | `Pan123ApiClient.kt:53-99` |
| 设备指纹随机 | `Pan123ApiClient.kt:104-106` |
| `loadConfig` / `apiEndpoint` | `Pan123ApiClient.kt:128` / `:159` |
| Android / Web 请求头 | `Pan123ApiClient.kt:172` / `:189` |
| `login` | `Pan123ApiClient.kt:257` |
| `listFiles` | `Pan123ApiClient.kt:291` |
| `getVideoPlayInfo` | `Pan123ApiClient.kt:391` |
| `resolveDownloadUrlViaHead` | `Pan123ApiClient.kt:513` |
| `trashFile`（含完整对象坑） | `Pan123ApiClient.kt:617` |
| `buildDownloadHeaders` | `Pan123ApiClient.kt:708` |
| 分享解析 / 列表 / 转存 | `Pan123ApiClient.kt:740` / `:774` / `:821` |
| 播放鉴权单例 | `Pan123AuthProvider.kt:3-27` |
| `refreshPan123QualityUrls` | `CloudUriResolver.kt:116` |
| `resolvePan123` | `CloudUriResolver.kt:182` |
| 播放错误 HLS 回退 | `PlayerService.kt:449-468` |
| 画质刷新入口 | `PlayerViewModel.kt:739` |
| `autoLogin` | `Pan123BrowserViewModel.kt:145` |
| `logout` | `Pan123BrowserViewModel.kt:255` |
| `downloadFile` | `Pan123BrowserViewModel.kt:660` |
| 备份读写 | `BackupManager.kt:402-420` |
