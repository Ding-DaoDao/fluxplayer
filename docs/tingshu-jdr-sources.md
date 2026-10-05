# Timbre JDR 听书书源

在「设置 → 听书配置 → 导入书源」选择 `.jdr` 文件。支持 Timbre 的 `manifest.json`、JavaScript `registerSource`、搜索、章节、音频及原有 HTTP/编码/密码学辅助接口。音频对象的 `headers` 同时用于音频和 HLS 分片，书籍和章节附加字段会保存并传给后续阶段。

文件可以改名；相同包 ID 的更新会保留启用状态和源配置。已有 JAR 继续通过原加载方式使用。搜索按 Timbre 的 `page=1, limit=30` 调用，章节使用 `page=1, size=0`；搜索合同没有总页数，因此不自动分页。支持本地导入、手动更新，未实现 URL 订阅。

## Flux 的登录、配置和目录扩展

这些是 Flux 的附加能力，旧 Timbre 包无需修改即可继续搜索和播放；旧包不会自动获得真实网盘登录。网盘源作者需要添加 `settings` 声明和 `login`、`browse` 实现。其他 JDR 宿主是否接受这些字段取决于其实现。

源设置可从「听书配置 → 源名称 · 配置」或书源首页的设置按钮打开。配置和登录状态按包 ID、源 ID 隔离，使用 Android Keystore 加密存储。停用再启用、更新同 ID 包或重建引擎不会丢失配置。

在 manifest 的 `sources` 项中添加：

```json
{
  "id": "my-drive",
  "name": "我的网盘",
  "script": "source.js",
  "capabilities": ["search", "chapters", "audio", "login", "browse"],
  "initialDirectory": "0",
  "settings": [
    {"key": "username", "label": "账号", "type": "text", "default": ""},
    {"key": "password", "label": "密码", "type": "password", "default": ""},
    {"key": "root", "label": "听书路径", "type": "directory", "default": "0"},
    {"key": "enabled", "label": "示例开关", "type": "switch", "default": "false"},
    {"key": "quality", "label": "音质", "type": "select", "options": ["普通", "高清"]}
  ]
}
```

每源最多 32 项，键必须唯一；`directory` 需要 `browse` 能力。保存配置后重建脚本引擎。文本、密码、选择、目录值在脚本中为字符串，开关为布尔值。

### 宿主 API

```js
host.version;                         // 1
host.settings.get('username', '');
host.settings.all();
host.storage.set('session', {token: '...'});
host.storage.get('session', null);
host.storage.remove('session');
host.storage.clear();                 // 清登录状态及临时缓存，保留配置
host.cache.set('directory:0', items, 3600); // TTL 秒，默认一小时
host.cache.get('directory:0', null);
host.cache.remove('directory:0');
```

storage/cache 接收 JSON 数据，按源保存，重启后可用。单条状态最多 256 KB，每源加密后的总存储最多 3 MB。缓存到期在读取时删除。配置页「清除书源临时缓存」保留登录状态和配置；音频文件由 App 单独管理。

### 登录

源对象实现 `async login(params)`，所有请求包含 `action`、`state`、`cookies`。`action` 可能是：

| action | 用途 |
| --- | --- |
| status | 打开配置页时读取状态 |
| login | 保存配置后发起登录 |
| poll | 检查扫码结果，二维码显示期间约每 3 秒调用，最多约 2 分钟 |
| webComplete | 用户结束网页登录，传入网页 Cookie |
| logout | 注销；返回 authenticated=false 后宿主也清除该源持久状态 |

返回对象示例：

```js
return {
  authenticated: false,
  message: '请扫码或打开网页登录',
  webUrl: 'https://login.example.com/',
  cookieUrl: 'https://api.example.com/',
  qrImage: 'https://login.example.com/qr.png',
  state: {requestId: '...'}
};
```

这些字段均可省略，`authenticated` 提供时必须为布尔值；`state` 传入后续登录调用。二维码也接受 `data:image/...`，长度不超过 350000 字符。网页和 Cookie 地址必须是 HTTP(S)，两者须属于同一注册域；cookieUrl 默认 webUrl。App 提供 WebView 和 Cookie 回传，源脚本负责校验凭证、获取令牌并用 `host.storage` 保存。不能仅因为收到 Cookie 就判断登录成功。

账号密码登录直接读取 `host.settings`。登录状态、扫码接口、验证码处理、真实网盘 API 都由源脚本实现，App 不假定某家网盘协议。

### 网盘目录和分页

实现 `async browse({directoryId, page, limit})`，页码从 1 开始，limit 为 100：

```js
return {
  items: [
    {id: 'folder-1', name: '有声书', type: 'directory'},
    {id: 'book-1', name: '书名', type: 'book', author: '作者', cover: 'https://example.com/cover.jpg', driveFileId: '...'}
  ],
  nextPage: 2
};
```

当前页 ID 必须唯一。`nextPage` 省略或 null 表示结束；提供时必须大于当前页。目录项可点击进入，返回按钮回到父目录；触底加载下一页。书籍项进入原有 chapters/audio 流程，其额外字段也会传递。首页从第一项 directory 设置的已选路径开始，未配置时使用 initialDirectory；路径选择器从 initialDirectory 开始浏览整个网盘。

## 音频缓存

播放页提供「缓存本章」「缓存整本」「取消」，听书配置页显示占用和已完整缓存章节数，可设置上限或清除缓存。默认上限 512 MB，达到上限按最近使用情况淘汰。支持普通 HTTP 音频及按 URL 识别的 HLS/DASH 下载，沿用源的请求头。

在线播放也使用同一个缓存，但只有完整下载且内容仍齐全的章节会标为可离线播放。离线章节直接读取本地缓存，无需再次调用源解析地址，网盘令牌过期或源停用时仍可播放已缓存内容。整本大于上限时会提示提高上限，不会把已淘汰的章节算作完整下载。清缓存或降低上限可能移除离线章节。

下载任务在 App 进程存活期间运行；本版本未提供跨进程恢复的后台下载服务。

## 示例与验证

[可导入的演示包](samples/jdr-netdisk-demo.jdr) 展示配置、模拟登录、目录、临时缓存和音频缓存。默认账号密码都是 demo；它不连接真实网盘，不要填写真实凭证。音频测试需填写自己的 HTTP(S) 音频地址。源代码在 [jdr-netdisk-demo](samples/jdr-netdisk-demo)，运行 `python3 docs/samples/jdr-netdisk-demo/build.py` 可重新打包。

引擎和基础合同来自 [Ding-DaoDao/Timbre](https://github.com/Ding-DaoDao/Timbre/tree/main/core/extension-engine)，保留原许可及署名；Android 23 使用便携 Base64 和 ChaCha20-Poly1305 实现。

- 引擎测试：`./gradlew :core:jdr-engine:test`。
- 导入、配置加密、登录持久化、目录分页测试：`./gradlew :core:tingshu:connectedDebugAndroidTest`。
- 普通音频/HLS 的请求头、完整下载、离线读取和清理测试：`./gradlew :feature:tingshu:connectedDebugAndroidTest`。

后两项需要 Android 设备或模拟器；编译测试 APK 不等于执行设备测试。
