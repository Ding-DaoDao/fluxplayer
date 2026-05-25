---
name: openlist-integration-details
description: OpenList 内嵌集成完整实现文档，供 AI 理解并复现
type: reference
scope: project
created: 2026-05-24
---
# OpenList 内嵌集成 — 完整实现文档

让另一个 AI 能完整理解并复现 OpenList 内嵌集成。

---

## 1. 架构概览

```
┌──────────────────────────────────────────────────────────────┐
│  MyVideoPlayerApp (Application)                              │
│  ├─ openListManager: OpenListManager (进程管理)              │
│  ├─ openListBinaryExtractor: OpenListBinaryExtractor         │
│  └─ 启动时检查 autoStart → OpenListService                   │
│                                                              │
│  OpenListService (前台 Service)                              │
│  ├─ 接收 ACTION_START / ACTION_STOP Intent                   │
│  ├─ startForeground() → manager.start()                      │
│  └─ stop() → manager.stop() → stopForeground()              │
│                                                              │
│  OpenListManager (进程控制器)                                │
│  ├─ start(): ProcessBuilder(openlist-binary, server, --data)  │
│  ├─ stop(): process.destroy() → waitFor(3s) → destroyForcibly│
│  ├─ launchLogReader(): 解析 stdout 初始密码                  │
│  ├─ startHealthCheck(): 每 5s 检测 + 自动重启(最多3次)       │
│  ├─ setPasswordViaCli(): 停服 → CLI改密 → 启服               │
│  └─ state: StateFlow<OpenListServerState>                   │
│                                                              │
│  OpenListBinaryExtractor                                     │
│  └─ ensureBinaryExtracted(): 检查 libopenlist.so 是否存在    │
│                                                              │
│  OpenListApiClient (JSON API)                                │
│  ├─ adminLogin(password) → POST /api/auth/login → token       │
│  ├─ listFiles(path) → GET /api/fs/list → List<OpenListFileItem│
│  ├─ listStorages() → GET /api/admin/storage/list              │
│  ├─ addStorage() → POST /api/admin/storage/create             │
│  ├─ deleteStorage() → POST /api/admin/storage/delete          │
│  ├─ changePassword() → PATCH /api/auth/admin                 │
│  ├─ getAdminToken(): String?  (给 PlayerManager 用)          │
│  └─ ping() → HEAD /api/ping (健康检查)                       │
│                                                              │
│  BrowseViewModel (浏览)                                      │
│  ├─ switchToOpenList() → 创建 apiClient → adminLogin → 加载  │
│  ├─ loadDirectory() (OPENLIST分支) → apiClient.listFiles()   │
│  └─ playVideo() (OPENLIST分支) → Bearer token → /d/路径      │
│                                                              │
│  OpenListSettingsScreen (设置 UI)                             │
│  └─ 开关/状态/密码/存储管理                                  │
└──────────────────────────────────────────────────────────────┘
```

---

## 2. 文件清单

| 文件 | 角色 |
|------|------|
| `core/openlist/OpenListServerState.kt` | 服务器状态 sealed class |
| `core/openlist/OpenListManager.kt` | 进程管理器：启停/健康检查/CLI改密 |
| `core/openlist/OpenListApiClient.kt` | 调用 OpenList JSON API |
| `core/openlist/OpenListBinaryExtractor.kt` | 确保二进制可执行文件存在 |
| `core/openlist/OpenListService.kt` | Android 前台 Service 包裹 |
| `ui/screens/openlist/OpenListViewModel.kt` | 设置页 ViewModel |
| `ui/screens/openlist/OpenListSettingsScreen.kt` | 设置页 UI |

另外涉及修改的文件：
| `MyVideoPlayerApp.kt` | 初始化 openListManager + 检查 autoStart |
| `ui/screens/settings/SettingsScreen.kt` | 主菜单 → OpenList 配置入口 |
| `ui/screens/settings/SettingsViewModel.kt` | 添加 SettingsPage.OpenList |
| `ui/screens/browse/BrowseViewModel.kt` | switchToOpenList / loadDirectory / playVideo 的 OPENLIST 分支 |
| `ui/screens/browse/BrowseScreen.kt` | SourceModeSwitcher 中的 OPENLIST 选项 |
| `ui/screens/home/HomeScreen.kt` | 第三 Tab (OPENLIST) |
| `core/player/PlayerManager.kt` | prepareAuth() 注入 Bearer token |

---

## 3. 核心数据结构

### `OpenListServerState.kt`
```kotlin
sealed class OpenListServerState {
    object Stopped : OpenListServerState()
    data class Starting(val port: Int = 5244) : OpenListServerState()
    data class Running(
        val port: Int = 5244,
        val webUiUrl: String = "http://127.0.0.1:$port",
        val pid: Long
    ) : OpenListServerState()
    data class Error(val message: String, val cause: Throwable? = null) : OpenListServerState()
}
```

### `OpenListFileItem` (定义在 `OpenListApiClient.kt`)
```kotlin
data class OpenListFileItem(
    val name: String,
    val path: String,        // 如 "/夸克/视频/xxx.mp4"
    val isDirectory: Boolean,
    val size: Long = 0,
    val modified: String = ""
)
```

### `OpenListManager` 内部密码流
```kotlin
// 两条密码来源（SharedPreferences 持久化）：
val initialPassword: StateFlow<String?>     // 进程 stdout 解析 "the initial password is: XXXXXXXX"
val adminSetPassword: StateFlow<String?>    // CLI 改密后保存（优先级高）
```

---

## 4. 二进制部署

### 「在 `core/openlist/` 下」，不放在 `res/raw/` 或 `assets/`

```
app/src/main/jniLibs/
  ├── arm64-v8a/libopenlist.so      // 约 15MB
  ├── armeabi-v7a/libopenlist.so
  └── x86_64/libopenlist.so
```

### `OpenListBinaryExtractor.ensureBinaryExtracted()`
```kotlin
val abi = when (Build.SUPPORTED_ABIS[0]) { ... }
val binaryPath = File(context.applicationInfo.nativeLibraryDir, "libopenlist.so")
// 直接检查文件存在且可执行
// 返 binaryPath.absolutePath
```

### 二进制约束
- 编译为 Go 静态二进制，嵌入 Android NDK 路径约定
- 必须用 `lib` 前缀命名（Android 加载 so 的约定）
- 需 `linux_amd64` / `linux_arm64` 交叉编译

---

## 5. 进程管理 — OpenListManager 详解

### 启动流程
```
1. ensureBinaryExtracted() → 获取二进制路径
2. 构建命令: binaryPath server --data <dataDir>
3. 设置环境变量:
   OPENLIST_DATA=<dataDir>
   OPENLIST_PORT=5244
   OPENLIST_LOG=info
   OPENLIST_ADMIN_PASSWORD=<savedPwd>  // 从 SharedPreferences 读取
4. ProcessBuilder.start() → 启动子进程
5. launchLogReader():
   └─ BufferedReader(process.inputStream)
      ├─ 读取 stdout，匹配 "the initial password is: XXXXXXXX"
      ├─ 提取密码 → _initialPassword.value = password
      └─ 继续读取（日志调试）
6. startHealthCheck():
   └─ 每 5s 检查 process.isAlive + 检测端口
      ├─ 进程挂了且重启次数 < 3 → 自动重启
      ├─ 超过 3 次 → state = Error("自动重启失败")
      └─ 检测到端口 5244 可连接 → state = Running(port, pid)
7. _state.value = Running(port=5244, pid)
```

### 停止流程
```
1. healthJob?.cancel()
2. process.destroy()            // SIGTERM
3. process.waitFor(3, SECONDS)  // 等待 3s
4. process.destroyForcibly()    // 超时则 SIGKILL
5. stopForeground(NOTIFICATION_ID)
6. stopSelf()
```

### 自动重启策略
```kotlin
private var restartCount = 0
private const val MAX_RESTART_ATTEMPTS = 3

// 健康检查检测到进程死亡
if (restartCount < MAX_RESTART_ATTEMPTS) {
    restartCount++
    start()  // 重新启动
} else {
    _state.value = Error("自动重启失败")
}
```

### CLI 密码操作
```kotlin
fun setPasswordViaCli(newPassword: String): Boolean {
    stop()  // 停服
    val result = Runtime.getRuntime().exec(arrayOf(
        binaryPath, "admin", "set", newPassword, "--data", dataDir
    ))
    // 解析 stdout 确认成功
    savePasswordToPrefs(newPassword)
    start()  // 启服 → 带走新密码
}

fun randomPasswordViaCli(): String? {
    stop()
    val result = Runtime.getRuntime().exec(arrayOf(
        binaryPath, "admin", "random", "--data", dataDir
    ))
    // 解析 stdout: "password: XXXXXXXX"
    savePasswordToPrefs(newPassword)
    start()
}
```

---

## 6. API 调用 — OpenListApiClient

所有请求发往 `http://127.0.0.1:5244`。

### auth/login (获取 token)
```
POST /api/auth/login
Content-Type: application/json

{"username": "admin", "password": "<pwd>"}

Response 200: {"code": 200, "data": {"token": "xxxxx"}}
```

### FS 文件列表
```
GET /api/fs/list?path=/&password=&page=1&per_page=0&refresh=false

Response 200: {
  "code": 200,
  "data": {
    "content": [
      {"name": "视频", "path": "/视频", "is_dir": true, "size": 0, "modified": "..."},
      {"name": "test.mp4", "path": "/test.mp4", "is_dir": false, "size": 12345, "modified": "..."}
    ],
    "total": 2,
    "readme": ""
  }
}

注意: authorization 头不是必须的（public API），
但如果配置了鉴权需要带 Authorization: Bearer <token>
```

### 存储管理 (需 admin token)
```
listStorages:  GET  /api/admin/storage/list
addStorage:    POST /api/admin/storage/create
deleteStorage: POST /api/admin/storage/delete

Authorization: Bearer <adminToken>
```

### 下载路径
```
直接下载: GET /d/<relative-path>
需要 Authorization: Bearer <token>
```

---

## 7. BrowseViewModel OPENLIST 分支

### `switchToOpenList()`
```kotlin
fun switchToOpenList() {
    val mgr = app.openListManager
    if (mgr.state.value !is Running) {
        _uiState.update { it.copy(error = "OpenList 服务未运行") }
        return
    }
    val pwd = mgr.adminSetPassword.value ?: mgr.initialPassword.value
    if (pwd.isNullOrBlank()) {
        _uiState.update { it.copy(error = "密码未就绪") }
        return
    }

    val apiClient = OpenListApiClient()
    openListApiClient = apiClient
    dirCache.clear()
    _uiState.update { it.copy(
        sourceMode = OPENLIST, currentPath = "/",
        serverUrl = "http://127.0.0.1:5244", isLoading = true
    )}

    viewModelScope.launch {
        val result = apiClient.adminLogin(pwd)
        if (result.isSuccess) loadDirectory("/")
        else _uiState.update { it.copy(error = "登录失败: ${result.exceptionOrNull()?.message}") }
    }
}
```

### `loadDirectory()` OPENLIST 分支
```kotlin
BrowseSourceMode.OPENLIST -> {
    val c = openListApiClient ?: return@launch
    val apiPath = if (path.startsWith("http")) {
        Uri.parse(path).path ?: "/"
    } else path
    c.listFiles(apiPath).map { items ->
        items.map { it.toWebDavResource() }
    }
}
```

### `playVideo()` OPENLIST 分支
```kotlin
BrowseSourceMode.OPENLIST -> {
    val token = openListApiClient?.getAdminToken()
    if (token != null) PlayerManager.prepareAuth("Bearer $token")
    source = StorageSource.OPENLIST
}
// 构建 /d/ 下载路径
val videoPath = if (state.sourceMode == OPENLIST) {
    val relativePath = Uri.parse(resource.path).path ?: "/"
    "$baseUrl/d$relativePath"
} else resource.path
```

### `toWebDavResource()` 转换
```kotlin
private fun OpenListFileItem.toWebDavResource(): WebDavResource {
    val baseUrl = "http://127.0.0.1:5244"
    return WebDavResource(
        name = name,
        path = "$baseUrl$path",   // 完整 URL
        isDirectory = isDirectory,
        size = size,
        lastModified = modified
    )
}
```

---

## 8. 密码持久化

```kotlin
// SharedPreferences: openlist_prefs
val prefs = context.getSharedPreferences("openlist_prefs", Context.MODE_PRIVATE)

// 保存
prefs.edit().putString("admin_password", password).apply()

// 读取  
prefs.getString("admin_password", null)
```

密码在进程启动时通过 `OPENLIST_ADMIN_PASSWORD` 环境变量传给子进程，确保新进程使用用户设置的密码而非随机密码。

---

## 9. MyVideoPlayerApp 初始化

```kotlin
class MyVideoPlayerApp : Application() {
    lateinit var openListManager: OpenListManager private set
    private var openListServiceBound = false

    override fun onCreate() {
        super.onCreate()
        openListManager = OpenListManager(this)
        // 如果 autoStart 开启，启动 OpenList 服务
        if (getAutoStartPref()) {
            OpenListService.start(this)
        }
    }
}
```

### 自动启动
```kotlin
// 设置页开关 → OpenListViewModel.setAutoStart()
fun setAutoStart(enabled: Boolean) {
    prefs.edit().putBoolean("openlist_auto_start", enabled).apply()
    if (enabled) OpenListService.start(context)
    else OpenListService.stop(context)
}
```

---

## 10. 前台 Service

```kotlin
class OpenListService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                manager.start()
            }
            ACTION_STOP -> {
                manager.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }
}

// 辅助静态方法
fun start(context: Context) {
    context.startForegroundService(Intent(context, OpenListService::class.java).apply {
        action = ACTION_START
    })
}
fun stop(context: Context) {
    context.startService(Intent(context, OpenListService::class.java).apply {
        action = ACTION_STOP
    })
}
```

通知 channel: `"openlist_service"`，标题 `"OpenList"`, 文本 `"正在运行"`。

---

## 11. 设置页 UI (OpenListSettingsScreen)

| UI 控件 | 行为 |
|---------|------|
| Switch (服务开关) | 启动/停止 OpenListService |
| 状态卡片 | 显示 Stopped / Starting / Running / Error |
| Web UI 按钮 | `Intent(ACTION_VIEW, "http://127.0.0.1:5244")` |
| 账户密码行 | 显示 admin / currentPassword |
| 自动启动 Switch | setAutoStart() |
| 修改密码按钮 | ChangePasswordDialog → changePasswordViaCli() |
| 重置随机密码按钮 | randomPasswordViaCli() |
| 添加存储按钮 | AddStorageSheet 底部弹窗 |
| 存储列表 | 显示每个 storage 的 mountPath/driver/status，可删除 |

### AddStorageSheet 驱动选择
```
Quark, Aliyundrive, BaiduNetdisk, OneDrive, S3, WebDAV
```
每种驱动需要不同的配置参数（通过键值对输入）。

---

## 12. 添加新功能需要改什么

如果要在另一个项目中复现 OpenList 集成：

1. **获取 OpenList Go 二进制** → 交叉编译为 `libopenlist.so` → 放入 `jniLibs/<abi>/`
2. **复制 `core/openlist/` 下 5 个文件** → 修改包名
3. **Application 初始化** → 创建 OpenListManager，加 autoStart 检查
4. **AndroidManifest.xml** → 声明 OpenListService（前台 service + notification）
5. **BrowseViewModel** → 加 OPENLIST 分支
6. **BrowseScreen** → SourceModeSwitcher 或 Tab 加入口
7. **设置页** → OpenList 配置页

---

## 13. 关键文件索引

| 文件 | 关键符号 |
|------|----------|
| `OpenListServerState.kt` | `sealed class OpenListServerState` |
| `OpenListManager.kt` | `class OpenListManager`, `start()`, `stop()`, `launchLogReader()`, `startHealthCheck()`, `setPasswordViaCli()`, `randomPasswordViaCli()` |
| `OpenListApiClient.kt` | `class OpenListApiClient`, `adminLogin()`, `listFiles()`, `listStorages()`, `addStorage()`, `deleteStorage()`, `changePassword()`, `ping()`, `getAdminToken()`, `data class OpenListFileItem` |
| `OpenListBinaryExtractor.kt` | `class OpenListBinaryExtractor`, `ensureBinaryExtracted()` |
| `OpenListService.kt` | `class OpenListService`, `ACTION_START`, `ACTION_STOP`, `start()`, `stop()` |
| `OpenListViewModel.kt` | `OpenListViewModel`, `startService()`, `stopService()`, `autoLogin()`, `refreshStorages()`, `addStorage()`, `deleteStorage()`, `changePasswordViaCli()`, `randomPasswordViaCli()`, `setAutoStart()` |
| `OpenListSettingsScreen.kt` | `OpenListSettingsScreen`, `ServiceControlCard`, `AdminSection`, `StoragesList`, `AddStorageSheet`, `ChangePasswordDialog` |
| `BrowseViewModel.kt` | `switchToOpenList()`, `loadDirectory()` OPENLIST 分支, `playVideo()` OPENLIST 分支, `onOpenListStateChanged()` |
| `MyVideoPlayerApp.kt` | `openListManager` 初始化 + autoStart |
| `PlayerManager.kt` | `prepareAuth()`, `pendingAuthHeader` |
