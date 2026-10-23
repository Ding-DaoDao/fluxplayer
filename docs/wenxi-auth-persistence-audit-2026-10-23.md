# 六家网盘登录凭证与持久化对照

核查日期：2026-10-23。对照 FluxPlayer 当前工作区与本机 `E:/Projects/wenxi` 源码；不读取真实账号凭证，不进行在线登录或续期实验。字段遗漏、控制流差异为源码确认；并发覆盖的运行后果为源码推断，未动态复现。本轮没有修改登录业务代码。

## 公共设计

| 项目 | FluxPlayer | wenxi |
| --- | --- | --- |
| 账号记录 | 每家一个当前登录配置；夸克和 UC 分别使用 quark/uc 配置 | StateStore + Vault，cloudAccounts、activeCloudAccounts 及独立账号 ID；支持一平台多个账号 |
| 本地加密 | 阿里、123、夸克、UC、移动的主要秘密字段通过 getSecret/putSecret、Android Keystore AES-GCM 保存；字段用途参与认证。旧明文可读，后续写入才迁移 | 全部账号凭证、设置等作为 AES-256-GCM 加密状态快照写入 state-v1.enc，密钥另由 LocalStateKey 管理 |
| 天翼例外 | cloud189 的 accessToken/sessionKey/sessionSecret/refreshToken/cookies 使用 putString/getString，在应用私有配置中保存明文值 | 天翼 Credential 同样进入统一加密快照 |
| 更新一致性 | 不同 API 客户端、AuthProvider、ViewModel、播放取址路径分别同步；阿里、123、天翼及 UC TV 已有部分互斥和旧状态检查 | Vault.replaceCredential 校验预期凭证，TokenSessions 和 Cookie 会话按账号及 revision 检查归属，再统一写入 |
| 写入和恢复 | 多数登录更新使用 SharedPreferences.apply；备份恢复使用 commit 检查失败。解密失败返回 null、保留原数据，调用方可能显示登录页；没有 wenxi 同等的统一恢复界面 | 串行修改状态，临时快照、前一份备份与恢复修复；密钥/状态不可读进入 StateRecoveryRequired，并有密码恢复机制 |
| 导出备份 | 先解密本机字段，以普通 backup.json/ZIP 导出，导入后再按本机规则保存；这与本地加密是不同层次 | 用户提供密码，CryptoBox 加密导出，包含账号配置及活动账号关系，导入后统一提交 |

主要依据：FluxPlayer `security/CredentialCrypto.kt`、`security/AndroidKeystoreCredentialCipher.kt`、`cloud189/C189AuthProvider.kt:67/104`、`backup/BackupArchive.kt:38`；wenxi `data/state_store.dart:36/321`、`data/state_key.dart`、`data/backup.dart:96`、`core/crypto_box.dart`。

## 逐家对照

| 网盘 | FluxPlayer 当前保存及恢复 | wenxi 的对应差异 |
| --- | --- | --- |
| 123 | 账号密码登录保存 passport/password/token、过期时间；导入 token 时移除账号密码。保存 login_uuid；接口失效后可凭密码重登，带互斥、previous_token 及 auto_login_blocked | 账号 Credential 保存认证类型、账号标识、token、密码及自动登录阻断状态；按 JWT 信息安排续期，重登后读取用户信息确认身份，再比较旧凭证提交 |
| 阿里 | 保存 authorization、refresh_token、drive_id、device_id、signature、device_private_key、网页登录 Cookie 等。启动验证 token；鉴权失败时刷新，必要时重建设备会话；互斥及 previous_authorization 防止部分旧状态写回 | Credential 同时保存 expiresAt、userId、各空间 driveId、设备会话 ID/有效期；到期前 60 秒续期，设备会话也有有效期检查；统一提交所有字段 |
| 夸克 | 加密 Cookie；接收成功响应的会话 Set-Cookie，浏览器及播放取址客户端通过回调写盘；鉴权失败时去掉 __puus 调 config 刷新。夸克/UC 配置和播放状态已分开 | 持久化 Cookie 更新时间，以 90 分钟间隔尝试刷新；游客请求隔离；接收 Cookie 时核对发起请求时的 owner，防止旧响应覆盖已更新账号 |
| UC | 与夸克相同的网页 Cookie 路径；另加密保存 TV access/refresh token，保存设备、账号归属、generation 和到期时间；提前 60 秒续期，有互斥及归属检查 | TV 授权放在该账号 Credential 内，与账号 revision、grant ID 绑定，跟随账号完整持久化及备份。FluxPlayer 的重新网页登录会主动清 TV 授权 |
| 移动 | 加密保存 authorization、phoneNumber、userDomainId、cookies，保存 lastRefresh；启动时距离上次刷新超过 7 天会后台刷新 token | 主要保存网页 Cookie、Authorization 和 userDomainId，识别多种登录失效码，要求重新网页登录；当前 c139 connector 没有 FluxPlayer 相同的 7 天定时 token 刷新流程，不能把两者写成相同续期机制 |
| 天翼 | 保存 access/refresh token、sessionKey/Secret、expiresIn 和 Cookie；恢复 signed API 会话、刷新 access token，带互斥和 Credentials 对象比较；账号密码登录后未见把手机号密码用于自动重登的持久化路径 | 主要使用网页登录 Cookie；密码登录的 passwordCookie Credential 还保存 username/password。Cookie 失效时可以密码重登，合并并发续期；失败后记录 autoLoginBlocked。不能把仅 Cookie/短信登录也描述为能自动密码重登 |

依据：各 `*BrowserViewModel` 登录/恢复流程，`aliyun/AliyunApiClient.kt:108`、`pan123/Pan123ApiClient.kt:176`、`quark/QuarkApiClient.kt:150`、`quark/UcTvService.kt:110`、`yun139/Yun139ApiClient.kt:280`；wenxi `providers/token_session.dart`、`providers/pan123.dart`、`providers/cookie_cloud.dart:773-879`、`providers/uc_tv.dart`、`providers/c139.dart`、`providers/tianyi.dart:511`。

## 确认需要注意的实现差距

1. **123 启动恢复没有检查自动登录阻断**：`Pan123BrowserViewModel.autoLogin:152` 在过期或只有账号密码时直接调用 login；接口续期虽检查 auto_login_blocked，但启动路径没有同等判断。`Pan123ApiClient.refreshSession:195` 更新 token/previous_token 时也未更新登录响应里的过期时间，下一次启动可能仍按旧时间触发重登。
2. **夸克/UC 普通 Cookie 响应缺少发起账号校验**：`updateCookieFromResponse` 有失败响应过滤，但普通 GET/POST 接收响应时没有 wenxi `_accept(response, sentOwner)` 的账号版本比较。浏览器、播放取址等独立实例的持久化回调可能遇到并发旧响应覆盖新状态的风险；不是说当前必然发生了串号。
3. **移动刷新缺少旧凭证归属保护**：`refreshToken` 收到响应后直接更新全局 Authorization，ViewModel 再写盘，没有像阿里/123/天翼那样比较旧会话，也没有统一刷新互斥。退出/换号与刷新交错时有旧结果写回风险，尚未动态复现。
4. **天翼网页刷新与 signed API 刷新并未统一**：`C189ApiClient.webFeatureRequest` 识别业务/HTTP 错误后直接抛出，没有 wenxi Cookie 会话的密码恢复路径。常规 API 已有刷新保护，不能因此写成所有接口都没有续期。

## 备份字段不完整

这些是当前备份模型和导出函数的字段差异，不代表每次恢复一定失败：

- 阿里 AlipanBackupConfig 没有 device_private_key 等完整设备会话状态，恢复后可能需要重建设备会话。
- 123 Pan123BackupConfig 只有 passport/password/token，没有 login_uuid、过期时间、auto_login_blocked 等。恢复不会统一处理这些未列出的旧字段。
- 夸克/UC QuarkBackupConfig 只有两份网页 Cookie，未包含 UC TV token、设备、归属及有效期。
- 移动 Yun139BackupConfig 没有 cookies，虽然登录时已将 Cookie 加密保存。恢复后普通文件接口可能仍可用，但依赖网页登录 Cookie 的路径不能视为完整恢复。
- 天翼备份包含主 token、sessionKey/Secret、到期与 Cookie；临时家庭授权等可重建会话没有全部备份，不能仅据此认定为缺陷。

依据：`core/model/.../BackupData.kt`；`BackupManager.kt:336-479`。Android 系统云备份和设备迁移规则排除了这六家的凭证配置，参见 `app/src/main/res/xml/backup_rules.xml`、`data_extraction_rules.xml`；这与应用自己的导出/恢复不同。

## 处理优先级

先统一天翼本地加密读写与旧数据兼容，再处理 123 的启动阻断/过期时间、夸克/UC 与移动的旧会话写回保护，补齐备份字段；统一多账号、账号资源归属和完整恢复机制作为更大范围的架构工作。当前普通 ZIP/JSON 备份是既有产品设计，本轮仅记录与 wenxi 的差异，不自动改变格式。
