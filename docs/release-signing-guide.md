# 正式版（Release）签名与发布指南

本文档说明如何用 GitHub Actions 自动构建、签名并发布 Flux Player 的正式版 APK。

对应工作流：`.github/workflows/release_build.yaml`

---

## 一、整体流程

```
推送 main（改动了 app/build.gradle.kts）
        │
        ▼
  ┌─────────────────────────────┐
  │ 1. 从 app/build.gradle.kts  │
  │    自动提取 versionCode/Name │
  └─────────────────────────────┘
        │
        ▼
  ┌─────────────────────────────┐
  │ 2. 校验签名 secrets 是否齐全 │
  └─────────────────────────────┘
        │
        ▼
  ┌─────────────────────────────┐
  │ 3. gradle :app:assembleRelease │
  │    （出未签名 APK）           │
  └─────────────────────────────┘
        │
        ▼
  ┌─────────────────────────────┐
  │ 4. apksigner 用私钥签名       │
  └─────────────────────────────┘
        │
        ▼
  ┌─────────────────────────────┐
  │ 5. aapt/zipfile 校验产物      │
  │    包名·版本·不可调试·原生库  │
  └─────────────────────────────┘
        │
        ▼
  ┌─────────────────────────────┐
  │ 6. 上传 artifact + 创建       │
  │    GitHub Release            │
  └─────────────────────────────┘
```

---

## 二、首次配置（一次性）

### 步骤 1：生成 keystore 的 Base64

在 Git Bash 中执行（把路径换成你的密钥）：

```bash
base64 -w0 "E:/Projects/fluxplayerKey" > /tmp/ks.b64
wc -c /tmp/ks.b64          # 确认有内容（约 3500 字符）
```

**安全提醒**：输出的这串字符就是你的签名私钥。不要粘贴到聊天、issue、commit 或任何公开位置。

### 步骤 2：确认密钥的三个参数

```bash
keytool -list -v -keystore "E:/Projects/fluxplayerKey"
# 会提示输入 keystore password
```

从输出中记录：

| 参数 | 说明 | 示例 |
|---|---|---|
| Alias | 密钥别名 | `fluxplayer` |
| Store Password | keystore 密码 | （你自己的） |
| Key Password | 密钥密码，通常同上 | （你自己的） |

### 步骤 3：在 GitHub 添加 4 个 Secrets

打开：

```
https://github.com/Ding-DaoDao/fluxplayer/settings/secrets/actions
```

点击 **New repository secret**，逐个添加：

| Name | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | 步骤 1 生成的单行 base64 字符串 |
| `ANDROID_KEYSTORE_PASSWORD` | 步骤 2 的 Store Password |
| `ANDROID_KEY_ALIAS` | 步骤 2 的 Alias |
| `ANDROID_KEY_PASSWORD` | 步骤 2 的 Key Password |

---

## 三、日常发布

### 方式 A：手动触发（推荐，最可控）

1. 打开 `https://github.com/Ding-DaoDao/fluxplayer/actions`
2. 左侧选择 **Publish Flux Player Release APK**
3. 点右侧 **Run workflow**
4. 选择：
   - `release_signing`：`production`（正式签名）或 `test`（调试证书，仅测试用）
   - `publish_release`：是否创建 GitHub Release
5. 点 **Run workflow**

### 方式 B：推送自动触发

当 `main` 分支上 `app/build.gradle.kts` 或工作流文件本身有改动并推送时，自动触发。

**注意**：触发条件带 `paths` 过滤，只有版本号相关文件变化才会触发，避免每次改代码都发一个版本。

### 发布版本号的正确方式

版本号只在 `app/build.gradle.kts` 里维护：

```kotlin
defaultConfig {
    versionCode = 58          // 每次发版必须 +1
    versionName = "0.16.5"    // 展示给用户的版本
}
```

改完提交推送，工作流会自动读取并：

- 用该版本号校验 APK（`aapt dump badging`）
- 命名产物为 `FluxPlayer-<versionName>-arm64-v8a-release.apk`
- 创建 tag `v<versionName>` 的 Release

**不需要**再改工作流文件里的任何版本字符串。

---

## 四、产物说明

一次成功的运行会在 Release 页面产出：

| 文件 | 说明 |
|---|---|
| `FluxPlayer-<ver>-arm64-v8a-release.apk` | 已签名的正式版 APK |
| `FluxPlayer-<ver>-mapping.zip` | 混淆映射，用于还原崩溃堆栈 |
| `SHA256SUMS.txt` | 校验和，发布时建议一并提供 |

同时 Actions 页面保留 artifact（30 天）。

---

## 五、工作流的校验项

工作流会在签名后强制校验以下内容，任一不通过即失败：

1. 包名为 `com.fluxplayer.app`
2. `versionCode` / `versionName` 与 `build.gradle.kts` 一致
3. APK **不可调试**（不能出现 `application-debuggable`）
4. `lib/arm64-v8a/libopenlist.so` 存在
5. 所有 `lib/arm64-v8a/*.so` 均为合法 ELF 文件
6. `apksigner verify` 签名有效

---

## 六、故障排查

| 现象 | 原因 | 处理 |
|---|---|---|
| `Missing release signing secrets` | Secrets 未配置或名字拼错 | 回到步骤 3 检查 4 个名字 |
| `Expected versionCode='NN'` 失败 | APK 版本与 build 脚本不符 | 确认构建用了最新代码 |
| `apksigner` 报密码错误 | 密码 / 别名不正确 | 用步骤 2 重新核对 |
| `OpenList library is missing` | `openlist-binary/libopenlist.so` 未提供 | 该文件不入库，需确认 CI 能拿到 |
| Gradle 下载依赖失败 | 网络 / 仓库问题 | 工作流已用 `ci-repositories.gradle` 切官方源 |

---

## 七、安全须知

- **签名私钥绝不入库**。`.gitignore` 已忽略 `*.keystore`、`*.jks`、`keystore.properties`，
  但历史提交里已有 `app/release.keystore`，需评估其是否曾为真实密钥。
- Base64 只存在 GitHub Secrets，工作流运行时才会解出到临时目录，用完即删（`trap ... EXIT`）。
- `test` 签名模式仅用于内部验证，会在 Release 上标记为 prerelease。

---

## 八、相关文件

| 文件 | 作用 |
|---|---|
| `.github/workflows/release_build.yaml` | 正式版构建发布流水线 |
| `.github/workflows/android_build.yaml` | Debug APK 构建 + 回归测试 |
| `.github/ci-repositories.gradle` | CI 依赖仓库配置 |
| `app/build.gradle.kts` | 版本号、签名配置、ABI splits |
| `fastlane/` | 旧的 Google Play 发布方案（已弃用） |
