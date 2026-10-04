# Timbre JDR 听书书源

在「设置 → 听书配置 → 导入书源」选择 Timbre 的 `.jdr` 文件。返回听书首页，进入包内书源，搜索书名，打开章节后播放。一个包可以包含多个书源；可按包启用、停用或删除。

JDR 文件可以改名。更新按 `manifest.json` 中的包 ID 识别，重新导入相同 ID 会替换旧包，并保留启用状态。已有 JAR 书源继续使用原来的加载方式；JAR 文件仍需保留原名。

## 兼容范围

- ZIP 包中的 `manifest.json` 和 JavaScript 脚本，使用 Timbre 的 `registerSource` 协议。
- `search`、`chapters`、`audio` 三阶段，以及 HTTP、编码、MD5、SHA-256、HMAC、AES、SM4 和 ChaCha20/XChaCha20 辅助接口。
- 音频 URL 或带 `headers` 的音频对象；请求头应用到音频请求及 HLS 分片。
- 搜索、章节的附加字段会传给后续阶段；章节附加字段随书籍保存，重启后继续解析不依赖搜索缓存。
- 显式声明 `allowInsecure` 的包使用与 Timbre 相同的 HTTP 桥 TLS 行为。

与 Timbre 当前实现一致，搜索请求使用 `page=1, limit=30`，章节请求使用 `page=1, size=0`。协议不提供总页数，JDR 搜索结果不会自动触底分页。JDR 协议没有 JAR 的分类菜单或配置接口，源内使用搜索入口。

目前支持本地文件导入和手动更新；未接入 Timbre 的 URL 订阅和自动更新功能。实际源站的登录、请求及音频播放仍取决于源脚本、网络和凭证。

## 实现与验证

`:core:jdr-engine` 的引擎、脚本前置库、合同解析器和基础测试移植自 [Ding-DaoDao/Timbre](https://github.com/Ding-DaoDao/Timbre/tree/main/core/extension-engine)，保留 `voice.core.extension.engine` 包名。Flux 适配使用跨平台 Base64 和便携 ChaCha20-Poly1305 实现，以兼容 Android 23。

`:core:tingshu` 管理两种包及书籍持久化；`:feature:tingshu` 提供统一导入入口和 Media3 播放。

引擎测试：`./gradlew :core:jdr-engine:test`。

Android 导入、搜索、书籍持久化、引擎重建、请求头、包更新和冲突测试：`./gradlew :core:tingshu:connectedDebugAndroidTest`，需要设备或模拟器。
