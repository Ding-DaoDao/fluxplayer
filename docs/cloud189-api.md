# 天翼云盘（cloud189）接口文档

> 适用范围：Flux Player `cloud189` 云盘模块
> 文档生成时间：2026-09-27
> 代码基线：`core/data/.../cloud189/`、`core/data/.../cloud/CloudUriResolver.kt`、`feature/videopicker/.../cloud189/`、`feature/player/.../service/AuthAwareDataSourceFactory.kt`

---

## 0. 一句话概览

天翼云盘模块走的是 **天翼云盘 Web/移动端私有 API**，涉及三个域名（`api.cloud.189.cn`、`cloud.189.cn`、`open.e.189.cn`），凭证体系为 **`sessionKey/sessionSecret` + `accessToken` 双轨制**，两套签名算法并存：

- **session 签名**（HMAC-SHA1）→ `api.cloud.189.cn` 域名的老版接口
- **MD5 签名**（参数排序拼接后 MD5）→ `cloud.189.cn/api/open/` 的 open API

核心特征：

- **三种登录方式**：手机号+密码（5 步 RSA 流程）、短信验证码（XXTEA 解密 paras）、Cookie 直接登录（备用）
- **播放 URL 靠 Cookie 认证**：`getNewVlcVideoPlayUrl.action` 无需签名，播放时注入 `Cookie: COOKIE_LOGIN_USER=<accessToken>`
- **无清晰度选项**：不像 123/夸克/阿里那样返回多档 URL，只有单个播放地址
- **分享功能全链路客户端实现**：解析链接 → 无登录浏览分享目录 → 转存到自己的网盘
- **附带彩蛋**：登录后自动每日签到（`/mkt/userSign.action`）

---

## 1. 模块地图

```
core/data/src/main/java/com/fluxplayer/app/core/data/cloud189/
├── C189ApiClient.kt        ★ 全部接口实现（约 1220 行，核心文件）
├── C189AuthProvider.kt     凭证状态单例（token 存储 + 播放头 + 刷新回调）
├── C189FileItem.kt         文件/文件夹模型
├── C189ListResult.kt       列表结果包装（items + totalCount）
├── CdnResolveResult.kt     CDN 解析结果模型（⚠️ 当前无调用方）
├── RsaHelper.kt            RSA/ECB/PKCS1 加密（登录用）
├── XxteaHelper.kt          XXTEA 解密（短信登录 paras 字段，固定密钥）
└── TeaCipher.kt            TEA 解密（⚠️ 当前无调用方，历史遗留）

core/data/src/main/java/com/fluxplayer/app/core/data/
├── BaseCloudApiClient.kt   请求基类（executeRequestAndGetResponse 等）
├── CloudHttpClient.kt      共享 OkHttp 实例（含 NO_REDIRECT 变体）
├── GlobalCookieJar.kt      全局 Cookie 管理（天翼 4 个域名）
└── cloud/CloudUriResolver.kt  ★ 播放链解析（resolveCloud189）

feature/videopicker/.../cloud189/
├── C189BrowserViewModel.kt ★ UI 层调用入口（登录/目录/分享/签到/下载）
├── C189BrowserUiState.kt   UI 状态
└── C189BrowserTabContent.kt Compose 界面

feature/player/.../service/
└── AuthAwareDataSourceFactory.kt  播放时注入 189 认证头
```

**注册信息**：
- Provider key：`cloud189`
- 显示名：`天翼云盘`（`BrowseTabs.kt`）
- `VideoSource.CLOUD189` → `"cloud189"`（`VideoSourceExtensions.kt`），播放 URL fragment 标记 `189Play`
- 内部 URI 格式：`cloud://cloud189/<fileId>`

---

## 2. 基础设施

### 2.1 域名与常量

| 常量/域名 | 值 | 用途 |
|---|---|---|
| `API_BASE` | `https://api.cloud.189.cn` | 老版 session 签名接口 |
| `OPEN_API_BASE` | `https://cloud.189.cn/api/open` | open API（MD5 签名） |
| `open.e.189.cn` | `https://open.e.189.cn` | 登录/验证码/token 刷新（OAuth 体系） |
| `appKey`（PC 登录） | `8025431004` | `loginSubmit.do` / `encryptConf.do` / `refreshToken.do` |
| `AppKey`（换 token） | `600100422` | `getAccessTokenBySsKey.action` 签名 |
| `appkey`（移动端会话） | `600100885` | `login4MergedClient.action` 请求头 |
| 移动端伪装参数 | `clientType=TELEANDROID`, `version=8.9.0` | 老版接口 + `login4MergedClient` 必带 |
| PC 端伪装参数 | `clientType=TELEPC`, `version=6.2`, `channelId=web_cloud.189.cn` | `getSessionForPC.action` |
| UAs | `okhttp/3.12.2`（api 域名）/ `Android`（open API）/ Chrome 87 桌面 UA（登录） | 不同域名不同 UA |

### 2.2 两套签名算法

#### ① session HMAC-SHA1 签名（`signedGet` / `signedPost`）

用于 `api.cloud.189.cn` 的老版接口。

```
sigData = "SessionKey={sk}&Operate=GET|POST&RequestURI={path}&Date={date}"
signature = HMAC-SHA1(key=sessionSecret, data=sigData) 的 hex 小写
```

- `date`：GMT 时区的 RFC 1123 格式（`EEE, dd MMM yyyy HH:mm:ss 'GMT'`，Locale.US）
- 请求头必带：`sessionkey`、`signature`、`date`、`accept: application/json;charset=UTF-8`、UA `okhttp/3.12.2`
- 过期检测：响应体含 `InvalidSessionKey` 或 `SessionKeyInvalid` → 用 `accessToken` 重新走 `login4MergedClient` 刷新 session 后重试一次

#### ② MD5 签名（`openApiGet` / `openApiPost`）

用于 `cloud.189.cn/api/open/*` 的 open API。

```
sigParams = 业务参数 + timestamp(毫秒) + AccessToken
按 key 字典序排序，拼成 "k1=v1&k2=v2..."，取 MD5 hex 小写
```

- 请求头必带：`Sign-Type: 1`、`Signature`、`Timestamp`、`Accesstoken`、`Referer: https://cloud.189.cn/web/main/`、UA `Android`
- GET 时业务参数直接拼 URL（**不做 URL 编码**），POST 时业务参数进 form body（同样不编码），签名参数仅用于计算 Signature
- 过期检测：响应体含 `InvalidAccessToken` 或 `InvalidSessionKey` → 刷新 accessToken 后重试一次

### 2.3 会话自动恢复（三层防线）

| 层级 | 位置 | 触发条件 | 动作 |
|---|---|---|---|
| L1 | `C189ApiClient.signedGet/Post/openApi*` | 响应含 InvalidSessionKey/InvalidAccessToken | 内部刷新 token/session，**静默重试一次** |
| L2 | `C189BrowserViewModel.withSessionRecovery` | 写操作（删/改/移/建）抛出会话类异常 | `refreshAccessToken()` → 持久化 → 重试原操作 |
| L3 | `C189BrowserViewModel.tryRestoreSession` | app 启动恢复会话后目录加载失败 | 指数退避重试（1s/3s/5s，最多 3 轮），每轮失败先尝试 `refreshAccessToken()`，全部失败则 `logout()` |

---

## 3. 认证与凭证体系

### 3.1 凭证字段（`C189AuthProvider` 单例）

| 字段 | 说明 |
|---|---|
| `accessToken` | open API 凭证，**同时用作播放 Cookie**（`COOKIE_LOGIN_USER`） |
| `sessionKey` / `sessionSecret` | api.cloud.189.cn 老版接口的 HMAC 签名对 |
| `refreshToken` | OAuth 刷新令牌（密码登录和短信登录可拿到） |
| `expiresIn` | 过期时间戳（代码统一写死 `now + 518400000ms` = 6 天） |
| `familySessionKey` / `familySessionSecret` | 家庭云 token（`login4MergedClient` 返回，已保存但**当前无业务使用**） |
| `isActive` | 登录态标记 |
| `userAgent` | 播放/下载请求用的 UA（默认 Chrome 87 桌面串） |
| `onTokensRefreshed` | 刷新成功回调 → ViewModel 自动持久化 |

### 3.2 登录方式一：手机号 + 密码（`loginByPassword`，5 步流程）

1. **获取加密配置**：`POST https://open.e.189.cn/api/logbox/config/encryptConf.do`，body `appId=8025431004`，返回 `data.pubKey`（RSA 公钥 Base64）和 `data.pre`（密文前缀）
2. **获取登录页参数**：请求 `https://cloud.189.cn/api/portal/unifyLoginForPC.action?appId=8025431004&clientType=10020&returnURL=...&timeStamp=...`，跟随 302 到登录页 HTML，用正则提取 `lt`、`reqId`、`paramId` 三个 hex 值
3. **RSA 加密并提交登录**：手机号和密码分别用 `RSA/ECB/PKCS1Padding` 加密为 hex，拼上 `pre` 前缀，POST 到 `https://open.e.189.cn/api/logbox/oauth2/loginSubmit.do`（请求头带 `lt`、`REQID`），body 含 `accountType=02`、`clientType=1`、`returnUrl` 等；成功返回 `toUrl`
4. **换 session**：POST `https://api.cloud.189.cn/getSessionForPC.action?appId=8025431004&clientType=TELEPC&version=6.2&channelId=web_cloud.189.cn&rand=...&redirectURL={toUrl}`，返回 `sessionKey` / `sessionSecret` / `refreshToken`
5. **sessionKey 换 accessToken**：GET `https://cloud.189.cn/api/open/oauth2/getAccessTokenBySsKey.action?sessionKey={sk}`，签名方式为 MD5（参数 `sessionKey` + `timestamp` + `AppKey=600100422`），请求头带 `AppKey: 600100422`，返回 `accessToken`

### 3.3 登录方式二：短信验证码（`sendSmsCode` + `loginBySms`）

1. **发验证码**：GET `encryptConf.do` 拿公钥 → RSA 加密手机号 → POST `https://open.e.189.cn/api/logbox/oauth2/sdk/sendSmsCode.do`，body 含 `apptype=wap`、`appKey=cloud`、加密手机号等
2. **验证码登录**：RSA 加密手机号+验证码 → POST `https://open.e.189.cn/api/logbox/oauth2/oAuth2SdkLoginByPassword.do`（`loginType=2`、`jointVersion=v3.8.1` 等），返回 `returnParas`（query-string，含加密的 `paras` 字段）
3. **XXTEA 解密**：`paras` 字段为 Hex 编码的 XXTEA 密文，用**固定密钥**（hex `67377150343554566b51354736694e6262686155356e586c41656c4763416373`，即 ASCII `gwqP45TVkQ5G6iNbbhaU5nXlAelGcAcs`）解密，得到 query-string 形式的 `accessToken` / `refreshToken` / `userId`
4. **换取移动端 session**：调用 `login4MergedClient(accessToken)`

### 3.4 登录方式三：Cookie 直接登录（`loginByCookies`，备用）

- GET `https://api.cloud.189.cn/api/portal/loginByCookies.action`
- 签名：`SessionKey=` 为空串，HMAC-SHA1 的 key 为**空字节数组**
- 请求头带原始 `Cookie` 串，返回 `accessToken` / `sessionKey` / `sessionSecret`

### 3.5 `login4MergedClient(accessToken)`（移动端会话交换）

GET `https://api.cloud.189.cn/login4MergedClient.action`，query 参数：`rand`、`accessToken`、`clientType=TELEANDROID`、`version=8.9.0`、`model={随机小米设备型号}`、`osFamily=Android`、`osVersion=29`、`networkAccessMode=WIFI`、`telecomsOperator=460011`、`channelId=nearme`，请求头 `appkey: 600100885`。

返回：
- `sessionKey` / `sessionSecret` / `eAccessToken`（可能覆盖原 accessToken）
- `familySessionKey` / `familySessionSecret`（家庭云，单独保存）

随机设备型号池为 30 个小米机型（如 `2312DRAABC`），来源于海阔视界项目。

### 3.6 Token 刷新（`refreshAccessToken`，两级降级）

1. **优先 refreshToken**：POST `https://open.e.189.cn/api/oauth2/refreshToken.do`，body `clientId=8025431004&refreshToken={rt}&grantType=refresh_token&format=json` → 新 `accessToken` + `refreshToken`
2. **降级 sessionKey**：用 `getAccessTokenBySsKey.action`（同 §3.2 第 5 步）重新换 token

刷新成功后还会调用 `tryRefreshSessionViaAccessToken(at)` 刷新 sessionKey/sessionSecret：
- 先试 `POST getSessionForPC.action?accessToken={at}`（注意此响应格式特殊：`res_code != 0` 时反而包含 sessionKey）
- 若 `res_code == 0`，降级走 `GET https://api.cloud.189.cn/loginByOpen189AccessToken.action?accessToken={at}` 拿 session

### 3.7 凭证持久化（SharedPreferences `"cloud189"`）

| Key | 内容 |
|---|---|
| `accessToken` / `sessionKey` / `sessionSecret` / `refreshToken` | 四凭证 |
| `expiresIn` | 过期时间戳（Long） |
| `cookies` | GlobalCookieJar 中 4 个天翼域名（`cloud.189.cn`、`api.cloud.189.cn`、`m.cloud.189.cn`、`open.e.189.cn`）的全部 Cookie 串 |
| `lastSignDay` | 上次签到日期（`LocalDate.now().toString()`），防止重复签到 |
| `lastClipboardPrompt` | 上次剪切板检测弹过的分享链接，去重用 |

写入时机：登录成功、`onTokensRefreshed` 回调（API 层任何刷新成功都会自动触发）。登出时整包 `clear()`，同时清理 GlobalCookieJar 的 4 个域名 + 内存目录缓存 + 磁盘目录缓存（`CloudDirectoryCache`）。

---

## 4. 业务接口清单

以下业务方法均在 `C189ApiClient` 中，按签名方式分类。

### 4.1 文件列表 `listFiles`（open API GET）

```
GET https://cloud.189.cn/api/open/file/listFiles.action
    ?folderId={id}&pageNum={n}&pageSize={n}&mediaType=0
    &iconOption=5&orderBy={field}&descending={bool}
```

| 参数 | 说明 |
|---|---|
| `folderId` | 根目录固定为 **`-11`** |
| `orderBy` | API 实际只支持 `lastOpTime` 等；UI 的"按大小排序"是客户端排的（API 传 lastOpTime 后本地重排） |
| `pageSize` | UI 层固定传 100 |

响应解析（兼容两种格式）：
- **格式 A**（主要）：`fileListAO.folderList[]` + `fileListAO.fileList[]` + `fileListAO.count`
  - 文件夹字段：`id`、`name`、`size`、`lastOpTime`、`createDate`、`fileCount`、`fileListSize`（目录大小）
  - 文件字段：`id`、`name`、`size`、`mediaType`、`icon.smallUrl`/`icon.largeUrl`（缩略图）
- **格式 B**（兜底）：顶层 `data[]` 数组 + `totalCount`

### 4.2 视频播放地址 `getVideoPlayUrl`（Cookie 认证，无需签名）

```
GET https://cloud.189.cn/api/portal/getNewVlcVideoPlayUrl.action?fileId={id}&type=2
```

请求头仅 `User-Agent: Android` + `sign-type: 1`，**依赖 GlobalCookieJar 里的登录 Cookie 自动携带**。响应取 `normal.url`（兜底 `playUrl` → `url`）。

这是播放链路的主接口（`CloudUriResolver.resolveCloud189`），成功后在 URL 后追加 fragment `#189Play=true#` 供播放器识别。

### 4.3 下载地址 `getDownloadUrl`（open API GET）

```
GET https://cloud.189.cn/api/open/file/getFileDownloadUrl.action?fileId={id}
```

响应取 `fileDownloadUrl`。用于图片加载（`resolveImageUrl`）、文件下载（`downloadFile`）。

### 4.4 文件写操作（open API POST）

| 方法 | 端点 | 参数 | 备注 |
|---|---|---|---|
| `createFolder` | `file/createFolder.action` | `parentFolderId`, `folderName` | 返回 `success` 布尔 |
| `renameFile` | `file/renameFile.action` | `fileId`, `destFileName` | 同上 |
| `deleteFiles` | `file/deleteFiles.action` | `fileIds`（逗号分隔，**可批量**） | 同上 |

### 4.5 移动文件 `moveFiles`（老版 session 签名 GET）

```
GET https://api.cloud.189.cn/moveFile.action
    ?rand=...&clientType=TELEANDROID&version=8.9.0&model={随机型号}
    &fileId={id}&destFileName=&destParentFolderId={targetId}
```

⚠️ **只移动 `fileIds.first()`**，批量参数实际未生效。成功判定：`success==true` 或 `res_code=="0"`。

### 4.6 用户信息（老版 session 签名 GET，自动带设备参数）

| 方法 | 端点 | UI 使用字段 |
|---|---|---|
| `getUserInfoExt` | `/getUserInfoExt.action` | `nickName`（昵称）、`safeMobile`（脱敏手机号） |
| `getUserInfo` | `/getUserInfo.action` | `capacity`（总容量）、`available`（剩余容量） |
| `getUserPrivileges` | `/getUserPrivileges.action` | `vipExpiredTime`（会员到期时间） |

三者 query 均自动附加 `rand` / `clientType=TELEANDROID` / `version=8.9.0` / `model`。

### 4.7 签到 `userSign`（老版 session 签名 GET）

```
GET https://api.cloud.189.cn/mkt/userSign.action
```

`result==1` 签到成功，`result==-1` 今日已签到（视为正常），其他值抛错并展示 `resultTip`。UI 层登录后自动静默签到（`autoSign`，同日只签一次），菜单也可手动触发。

---

## 5. 分享体系（全链路）

### 5.1 链接解析（纯客户端，`parseShareUrl` / `isShareUrl`）

- 支持两种格式：`https://cloud.189.cn/t/{shareKey}` 和 `...?code={shareKey}`
- 先整体 `URLDecoder.decode` 再匹配
- 访问码提取正则：`访问(?:码|密码)?[：:\s-]*([a-zA-Z0-9]{4})`
- UI 层还有**剪切板自动检测**（`detectClipboardShareUrl`）：登录后自动读剪切板，发现分享链接直接进分享浏览弹窗（有去重）

### 5.2 获取分享信息 `getShareInfoByCode`（无需登录）

```
POST https://api.cloud.189.cn/open/share/getShareInfoByCodeV2.action
body: shareCode={shareKey 或 "shareKey（访问码：xxxx）"}
```

⚠️ 有访问码时，访问码以**中文括号全角格式**拼进 shareCode（参考 csdown.js）。响应含 `shareId`、`fileId`、`isFolder`、`shareMode`、`fileName`；若 shareId 为空说明需要访问码。`fileName` 可能是百分号编码的中文，UI 层做了保护性解码。

### 5.3 列分享目录 `listShareDir`（无需登录）

```
GET https://api.cloud.189.cn/open/share/listShareDir.action
    ?shareId={}&fileId={}&isFolder={bool}&shareMode={n}
    &pageNum={n}&pageSize=200[&accessCode={pwd}]
```

响应结构与 4.1 格式 A 相同（`fileListAO.folderList/fileList/count`），额外解析 `md5` 字段；文件名做保护性 URL 解码（`tryUrlDecode`：仅在含 `%XX` 模式时解码，并把字面 `+` 替换为 `%2B` 防止误转为空格）。

### 5.4 转存 `shareSave`（老版 session 签名 POST，需登录）

```
POST https://api.cloud.189.cn/batch/createBatchTask.action
     ?rand=...&clientType=TELEANDROID&version=8.9.0&model={随机型号}
body: type=SHARE_SAVE
      &taskInfos=[{"fileId":"..","fileName":"..","isFolder":0|1}]
      &targetFolderId={targetId}
      &shareId={shareId}
```

UI 层支持单文件和批量转存（循环逐个调用）。默认目标目录为根目录 `-11`，可选择任意目标文件夹。

---

## 6. 播放链路

```
UI 点击视频
  → resolveVideoUri(item)
  → CloudUriResolver.resolve(cloud://cloud189/{fileId})
      ① 查 CloudPlaylistCache 缓存（TTL 15 分钟）
      ② clearOtherProviders("cloud189") —— 清掉其他云盘凭证/cookie 防串号
      ③ CloudPlayHeaders.registerSuffix(".189.cn") { C189AuthProvider.getPlayHeaders() }
         （后缀匹配，覆盖所有 *.189.cn CDN 子域名）
      ④ client.getVideoPlayUrl(fileId)
  → 返回 URL + "#189Play=true#" fragment
  → ExoPlayer 播放
      AuthAwareDataSourceFactory 检测 fragment 含 "189Play"：
        注入 Cookie: COOKIE_LOGIN_USER={accessToken}
        注入 User-Agent: C189AuthProvider.userAgent
```

**播放认证头**（`C189AuthProvider.getPlayHeaders()`）：
- `Cookie: COOKIE_LOGIN_USER={accessToken}` —— accessToken 同时充当登录 Cookie
- `User-Agent: {Chrome 87 桌面 UA}`

下载文件时同样使用这套头（`cloudDownloadRepository.download(headers = ...)`）。

---

## 7. 数据模型

### `C189FileItem`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | String | 文件/文件夹 ID（UI 层作为 `path` 使用，即 URI 中的 fileId） |
| `name` | String | 文件名 |
| `isDir` | Boolean | 是否目录 |
| `size` / `folderSize` | Long | 文件大小 / 目录总大小 |
| `fileCount` | Int | 目录内文件数 |
| `lastOpTime` / `createDate` | String | 时间（API 原样字符串） |
| `mediaType` | Int | 1=图片 2=音频 3=视频 -1=其他（映射为 UI category） |
| `thumbnailUrl` | String? | 仅文件有（icon.smallUrl） |
| `md5` | String | 仅分享列表解析 |
| `isVideo` | 计算属性 | 按扩展名判断（mp4/mkv/avi/mov/wmv/flv/webm/m4v/ts/rmvb/rm/3gp/mpeg/mpg/vob/iso） |

### `C189ApiClient.ShareInfo`

`shareKey` + `sharePwd`，派生属性 `normalizedUrl` 生成 `https://cloud.189.cn/t/{key} 访问码：{pwd}` 格式的展示文本。

---

## 8. 加密工具

| 类 | 算法 | 用途 | 状态 |
|---|---|---|---|
| `RsaHelper` | RSA/ECB/PKCS1Padding → hex | 加密手机号/密码/验证码 | ✅ 使用中 |
| `XxteaHelper` | XXTEA 解密（固定密钥，模拟 JS Float64 语义） | 解密短信登录 `paras` 字段 | ✅ 使用中 |
| `TeaCipher` | TEA/XXTEA 变体解密 | 历史遗留 | ⚠️ 无调用方 |
| `CdnResolveResult` | — | data class(url, cookie) | ⚠️ 无调用方 |

`XxteaHelper` 实现细节：数据/密钥按小端序打包为 IntArray，解密轮数 `52/n + 6`，sum 用 64 位运算模拟 JS 的 Float64 行为，`toInt32` 手动实现 JS 的 ToInt32 语义（这是关键坑点，直接 `Long.toInt()` 会溢出错位）。

---

## 9. 与其他云盘模块的差异速查

| 特性 | cloud189 | yun139 | quark/uc | alipan | pan123 |
|---|---|---|---|---|---|
| 登录方式 | 密码/短信/Cookie 全客户端 | WebView Cookie 轮询 | Cookie 手动粘贴 | token 粘贴 | token/账号 |
| 签名 | HMAC-SHA1 + MD5 双轨 | Basic auth + x-yun-* 头 | Cookie | Bearer + 签名 | Bearer |
| 播放认证 | Cookie(COOKIE_LOGIN_USER) | Authorization + x-yun-* | Cookie | Bearer | Bearer |
| 清晰度切换 | ❌ 单地址 | ❌ 单地址 | ✅ | ✅ | ✅ |
| 分享/转存 | ✅ 全链路 | ❌ | ❌ | ❌ | ❌ |
| 签到 | ✅ | ❌ | ❌ | ❌ | ❌ |

---

## 10. 已知问题与注意点

1. **`moveFiles` 批量失效**：只取 `fileIds.first()`，UI 若做多选移动需逐个调用。
2. **`TeaCipher` / `CdnResolveResult` 是死代码**：无任何调用方，可考虑清理。
3. **`expiresIn` 是假的**：统一写死 6 天，真实过期靠响应错误码兜底（三层防线见 §2.3）。
4. **open API 参数不做 URL 编码**：`openApiGet/Post` 的业务参数直接拼接，文件名等特殊字符依赖服务端容忍度；目录列表等接口因为参数都是数字/布尔所以无碍。
5. **`getSessionForPC.action?accessToken=` 响应格式陷阱**：`res_code != 0` 时反而包含 sessionKey，与 `redirectURL=` 调用格式相反，代码内有详细注释。
6. **家庭云 token 已保存未使用**：`familySessionKey/familySessionSecret` 在 `login4MergedClient` 中解析保存，但当前没有家庭云业务代码。
7. **分享访问码格式是全角括号**：`shareKey（访问码：xxxx）`，拼接时必须用中文括号，否则服务端解析失败。
8. **多云盘串号防护**：播放解析前 `clearOtherProviders` 会清掉其他云盘的全部凭证和 Cookie，HLS 分片兜底注入时依赖这一点保证 Cookie/Token 不串。
