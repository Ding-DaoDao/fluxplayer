# 六家网盘与 wenxi 的当前源码差异核查

核查日期：2026-10-23。对照本机 `E:/Projects/wenxi` 与 FluxPlayer 当前工作区，包含尚未提交的修改。本报告核查功能路径和界面接线，不代表真实账号验收通过；未运行新的构建或在线上传。

后续更新：六家个人目录的批量管理现已补齐，普通文件列表左侧直接提供复选框，勾选后就地显示操作栏，包含逐项多选、批量移动与删除、删除确认、目标目录限制、分批提交及结果核对。下表中的批量管理一项已解决；其他差距没有因此变为已完成。验证记录见 `wenxi-provider-parity.md` 的批量管理补齐章节。

## 已确认的功能差距

| 范围 | wenxi 的实现 | FluxPlayer 当前实现与影响 |
| --- | --- | --- |
| 六家普通个人目录（本轮已补齐） | `lib/ui/browser_page.dart` 使用多选文件集合调用 move/delete，提供全选 | 共享 CloudBrowserPanel 的原文件行左侧提供复选框，就地使用 CloudBatchToolbar 与 CloudBatchManager，六家均可逐项选择文件后批量移动、删除，顶部全选复选框已移除，改为勾选后在移动左侧提供全选文字按钮；保留点击文件、文件夹及既有单文件操作 |
| 六家分享临时转存 | 各 connector 为分享下载/播放分配临时目录，登记 DownloadCleanup；清理基础设施负责后续处理 | `CloudProviderFeatures.transferAndResolve` 转存至用户选定目录并保留，无对应临时目录登记、失败回收及后续清理流程。这是现有设计差异，但在“全部对齐”的目标下仍是缺口；不能直接清理用户主动保留的文件 |
| 阿里相册文件管理 | `aliyun.dart` 把相册作为 personalSpace，upload/createFolder/rename/move/delete/createShare 使用当前空间 driveId；移动目标还带空间标识 | `CloudSpaceFeatures.kt` 只有空间枚举、列表、下载取址；`CloudFeaturesDialog.kt` 的 spaces 模式仅有播放、下载、搜索等浏览操作，无相册上传、新建、重命名、移动、删除、创建分享入口，也未接入跨空间移动 |
| 夸克、UC 分享免转存下载 | `cookie_cloud.dart.tryShareDownload` 有独立游客和账号通道、隔离 Cookie、分享凭据刷新、下载长度检查，以及失败后回退 | 扩展分享页只有转存所选、转存并播放/下载，调用 transferAndResolve；没有上述免转存下载流程及其设置入口。已有分享浏览不等于免转存下载 |

## 已确认的实现完整性差距

| 范围 | 差异及源码依据 |
| --- | --- |
| UC 网页下载内容校验 | wenxi 的 `quark_uc.dart` 归一化并比较 MD5，`cookie_cloud.dart.prepareStream` 在相应网页原文件路径探测实际长度，识别会员提示替代视频。FluxPlayer 的 `QuarkApiClient.getDownloadUrl` 网页分支只检查返回文件 ID 和地址，未携带并比较原列表长度/MD5；TV 分支虽有长度探测，但传入 expectedSize 为 0，不能等同于完整的列表文件校验 |
| 移动家庭空间目录一致性 | wenxi 的 `c139.dart._checkFamilyPath` 校验 familyRootPath，根目录要求相等，子目录要求在根下且结尾匹配 parent。FluxPlayer 的 `CloudSpaceFeatures.listSpace` 只校验路径基本形态，取址直接使用 file.token，未建立对应根路径与父目录一致性校验 |
| 天翼网页会话恢复 | wenxi 的 `tianyi.dart._api/_renew` 对失效 Cookie 有续期、并发合并与自动登录失败状态处理。FluxPlayer 常规签名 API 已有刷新逻辑，但 `C189ApiClient.webFeatureRequest` 识别错误后直接抛出，没有同等的网页会话恢复流程；家庭 token 的局部重试不能覆盖全部网页功能 |
| 夸克原画传输与公共缓存 | FluxPlayer 已有原画取址去重、超时回退及 ParallelRangeDataSource。wenxi 另有下载与播放共享缓存、会话缓存租约和缓冲策略，见 `playback/download_playback.dart`、`download/download_playback_cache.dart`、`playback/playback_buffer_policy.dart`。现有固定分段预读不等于整个缓存与调度体系对齐，不能据此承诺相同速度 |

## 已实现，不应继续列为缺失

- 六家个人目录菜单中的搜索入口，以及全盘/当前目录、分类、历史、进度、停止、错误详情等主体流程已存在。天翼历史身份以持久化登录身份摘要隔离，重新登录后的连续性仍与稳定账号 ID 方案不同。
- 六家上传和创建分享、分享浏览与转存已有实现；此前 403 与 123 凭据问题的修正仍需真实账号复验，不应将“存在实现”写成“全部可用”。
- 阿里资源库/备份盘切换、相册浏览已存在；缺的是相册的完整管理流程。
- 移动、天翼家庭空间浏览、播放、下载已有实现。wenxi 的 BrowseSession.canManageFiles 本身排除家庭空间，因此家庭空间不提供上传、删除等管理操作不应列作迁移遗漏。
- UC 已有 UcTvService、扫码授权/撤销界面、令牌续期、TV 播放与原文件下载，不能把 TV 授权列为缺失。两边授权失败后的回退策略也有差异，需要单独验收。

## 公共应用能力，不能算作单家 provider 的接口缺失

wenxi 的 `cloud_accounts_page.dart` 支持保存多个账号并切换，播放、下载和收藏保留账号归属；FluxPlayer 当前六家客户端主要围绕各网盘当前登录态工作。若要求整体体验一致，还需统一多账号与资源归属设计。

FluxPlayer 的 `CloudDownloadHelper` 使用系统 DownloadManager；wenxi 有自己的下载队列、播放缓存、内容校验与清理协作。系统下载具备的能力不等于已在 FluxPlayer 内提供 wenxi 同等的任务操作界面。

## 验收边界

本轮没有以六家真实账号逐项操作。尤其天翼提交上传的 InfoSecurityErrorCode，虽然上一轮已按官方前端请求修正参数并通过本地测试，仍没有用户确认最新预览包上传成功。搜索速度、会员下载限制及原画缓冲也仍需真实账号和网络对照，不作已解决承诺。
