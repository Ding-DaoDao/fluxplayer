# Timbre JDR 听书书源

在「设置 → 听书配置 → 导入书源」选择 `.jdr` 文件。支持 Timbre 的 `manifest.json`、JavaScript `registerSource`、搜索、章节、音频及原有 HTTP/编码/密码学辅助接口。音频对象的 `headers` 同时用于音频和 HLS 分片，书籍和章节附加字段会保存并传给后续阶段。

文件可以改名；相同包 ID 的更新会保留启用状态和源配置。已有 JAR 继续通过原加载方式使用。搜索按 Timbre 的 `page=1, limit=30` 调用，章节使用 `page=1, size=0`；搜索合同没有总页数，因此不自动分页。支持本地导入、手动更新，未实现 URL 订阅。

## Flux 的登录、配置和目录扩展

这些是 Flux 的附加能力，旧 Timbre 包无需修改即可继续搜索和播放；App 会提供通用的账号、密码、Cookie、Token 和听书路径输入项，也能读取脚本的配置接口；这些值需要由脚本读取并用于请求。真实网盘登录和目录 API 仍由书源实现。其他 JDR 宿主是否接受这些字段取决于其实现。

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

配置类型还支持 `multiselect`（多选、逗号分隔保存）和 `button`（操作按钮，使用 `action` 标识操作）。每源最多 32 项，键必须唯一；`directory` 需要 `browse` 能力。保存配置后重建脚本引擎。文本、密码、选择、目录值在脚本中为字符串，开关为布尔值。

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

网页登录使用完整登录 Activity，支持 DOM 存储、第三方 Cookie 和网页重载；加载主页面失败时显示错误，也可切换手机／电脑版。点击「完成」后回传 Cookie，返回键仅关闭页面，不提交登录。登录后重新读取源写回的配置，避免旧表单覆盖新凭证。`params.state` 保持 JSON 对象；`params.config` 与 `host.settings.all()` 一致，保留开关的布尔类型及源保存的隐藏凭证。

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

## 与 JAR 对齐的配置接口

除了 manifest 的 settings，也支持 registerSource 对象中的 `getCustomConfigItems()`、`settings` 或 `configItems` 数组。配置项的类型可用 Text、Switch、Select、MultiSelect、Button，也支持小写类型。按钮可直接携带 click 回调；无需另外声明 config 能力。

```js
registerSource({
  id: 'my-drive',
  getCustomConfigItems() {
    return [
      {type: 'Text', key: 'cookie', label: 'Cookie'},
      {type: 'MultiSelect', key: 'formats', label: '音频格式', options: ['mp3', 'm4a'], default: ['mp3']},
      {type: 'Button', label: '验证凭证', async click() {
        const cookie = ExternalSourcePrefs.getString('my-drive.cookie', '');
        // 调用该网盘的真实验证接口，成功后保存需要更新的凭证。
        return {message: '验证结果由源接口返回'};
      }}
    ];
  },
  onConfigChanged(values) { /* 保存后的回调，可省略 */ },
  reset() { /* 重置源内状态，可省略 */ }
  // search/chapters/audio 等接口继续按 JDR 协议实现。
});
```

`ExternalSourcePrefs.getString('<源ID>.<键>', 默认值)` 和 `putString('<源ID>.<键>', 值)` 与 JAR 的配置用法一致；也可使用 `host.settings.get/set/all`。凭证加密保存，按钮写回凭证后配置页会重新加载。保存前先提交表单值，再执行按钮回调。

也支持统一的 `config(params)` 方法：action=get 返回配置数组（或 items 数组），action=save 接收 values；按钮 action 为配置项的 action 或 key。回调可返回字符串或 message 对象作为操作结果。

没有配置声明的源会显示通用凭证字段。填写只表示配置保存成功，不会伪造登录成功；如果原脚本把凭证写死、从不读取宿主配置，需要修改源脚本。App 不会把 Cookie 或 Token 自动发送到任意网站。JDR 是 JavaScript 协议，不能直接执行 JAR 的 Java 类或未移植的宿主调用。

## App 统一音频与封面缓存

JDR 可实现 `cover(params)`，按可见书籍请求封面；参数包含 `bookId`、书籍附加字段和 `config`。返回 HTTP(S) 字符串、`{url, headers}` 或 `null`。App 先读取图片内存／磁盘缓存，仅在图片缺失时解析源地址，再将请求头传给封面加载器；五分钟内复用已解析地址，无封面结果缓存一分钟。同一本书的并发解析请求会合并。已核对的网盘包 1.1.4／1.1.5 使用最多四个独立沙箱并行查询封面，不占用浏览／章节／播放的锁；其他包仍用原沙箱，保留源内存状态的兼容性。封面接口失败不影响章节和播放。没有 `cover` 接口的源继续直接使用 `bookImage/cover`。

书源页面统一提供目录／分类浏览，已移除搜索输入框、按钮及页面内搜索状态；源引擎保留原 search 协议，以兼容既有源包和导入验证。

脚本可用 `await sleep(毫秒)` 等待分页或限速，单次等待最多 60 秒，支持宿主超时与取消。已知网盘包 1.1.4 的完整章节收集允许最多 120 秒，取消播放确认窗会取消预解析。

旧网盘包的全局 `storage` 与 `host.storage` 共用加密状态，封面独立沙箱及重新进入书源可恢复已有登录态。123 网盘包 1.1.4／1.1.5 的下载接口拒绝访问不再清除全局会话，避免一次播放或封面失败连带破坏目录浏览；目录接口仍保留源本身的失效处理。调试日志 `JdrSource` 仅记录是否读到账号、密码、Token 及脱敏错误。

HTTP 桥支持 `responseMode: 'probe'`：读取 JSON／文本跳转响应，音频等二进制响应只返回状态、头和最终 URL，不提前下载音频。123 云盘的 210 跳转正文验证为 JSON 后补齐类型，供旧源包识别；普通请求仍保留 10 MB 正文上限。

JAR 和 JDR 的操作失败会在当前列表、配置面板、播放确认窗或播放器持续显示，可展开并选择复制错误详情。可重试的操作提供重试按钮；请求超时不再当作用户取消而静默结束。JAR 仅提示后返回空目录／空章节／空地址时，宿主保留源的提示作为失败原因；使用说明在配置面板展示。错误详情隐藏凭证和带签名的网络链接。

已发布的 `com.timbre.tingshu-netdisk` 1.1.4 包漏写天翼的 `initialDirectory=-11`，且 browse 的书籍 ID 没有章节／封面接口要求的 `D_` 前缀。App 对该包的这个版本做定向兼容，现有用户无需删除配置或重新导入。也可用 `docs/samples/fix-netdisk-jdr.py 原包.jdr 输出包.jdr` 生成修正这些字段的 1.1.5 包；更新同包 ID 会保留用户配置。

入口在「设置 → 听书配置 → App 缓存管理」，即使没有导入任何源也能管理缓存。音频和封面都有自动缓存开关、占用统计及清理操作，适用于全部 JAR/JDR 来源；本地文件直接读取，不再复制进缓存。

播放时缓存已加载的音频，供后续播放和跳转复用。普通直链音频完整读完且缓存未被淘汰时，可直接从本地重播，无需重新解析地址。部分音频和部分 HLS/DASH 分片不等于完整离线下载。自动缓存关闭后仍能读取已有缓存，手动下载继续可用。

书库、详情、播放、加载页面和封面取色共用独立的封面磁盘缓存（上限 128 MB）。停止自动缓存封面后仍可读取已有内容；音频和封面分别清理，不删除登录凭证、书库或收听进度。封面缓存独立于视频缩略图。


作为自动缓存以外的完整下载功能，播放页提供「缓存本章」「缓存整本」「取消」，听书配置页显示占用和已完整缓存章节数，可设置上限或清除缓存。默认上限 512 MB，达到上限按最近使用情况淘汰。支持普通 HTTP 音频及按 URL 识别的 HLS/DASH 下载，沿用源的请求头。

在线播放也使用同一个缓存，但只有完整下载且内容仍齐全的章节会标为可离线播放。离线章节直接读取本地缓存，无需再次调用源解析地址，网盘令牌过期或源停用时仍可播放已缓存内容。整本大于上限时会提示提高上限，不会把已淘汰的章节算作完整下载。清缓存或降低上限可能移除离线章节。

下载任务在 App 进程存活期间运行；本版本未提供跨进程恢复的后台下载服务。

## 示例与验证

[可导入的演示包](samples/jdr-netdisk-demo.jdr) 展示配置、模拟登录、目录、临时缓存和音频缓存。默认账号密码都是 demo；它不连接真实网盘，不要填写真实凭证。音频测试需填写自己的 HTTP(S) 音频地址。源代码在 [jdr-netdisk-demo](samples/jdr-netdisk-demo)，运行 `python3 docs/samples/jdr-netdisk-demo/build.py` 可重新打包。

引擎和基础合同来自 [Ding-DaoDao/Timbre](https://github.com/Ding-DaoDao/Timbre/tree/main/core/extension-engine)，保留原许可及署名；Android 23 使用便携 Base64 和 ChaCha20-Poly1305 实现。

- 引擎测试：`./gradlew :core:jdr-engine:test`。
- 导入、配置加密、登录持久化、目录分页测试：`./gradlew :core:tingshu:connectedDebugAndroidTest`。
- 普通音频/HLS 的请求头、完整下载、离线读取和清理测试：`./gradlew :feature:tingshu:connectedDebugAndroidTest`。

后两项需要 Android 设备或模拟器；编译测试 APK 不等于执行设备测试。
