# 123云盘视频播放问题 - 日志分析指南

## 已添加的日志位置

我在以下关键位置添加了详细日志，帮助你定位123云盘部分视频播放失败（显示"source error"）的问题：

### 1. Pan123ApiClient.kt
- **getVideoPlayInfo()** - 获取视频播放信息
- **getFileDownloadUrl()** - 获取文件下载URL（备用方案）
- **getFileDownloadInfo()** - 获取文件下载信息
- **buildDownloadHeaders()** - 构建下载请求头

### 2. CloudUriResolver.kt
- **resolvePan123()** - 解析123云盘视频URL的主流程

### 3. AuthAwareDataSourceFactory.kt
- **applyHttpAuth()** - 设置HTTP请求认证头
- **applyCloudPlayHeaders()** - 注入云盘播放所需的请求头
- **open()** - 打开数据源连接

### 4. PlayerService.kt
- **onPlayerError()** - 捕获并记录播放错误（新增）

## 如何查看日志

### 方法1：使用Android Studio Logcat
1. 连接手机或启动模拟器
2. 打开Android Studio的Logcat窗口
3. 选择你的设备和应用进程（com.fluxplayer.app）
4. 在搜索框中输入以下关键词进行过滤

### 方法2：使用命令行
```bash
adb logcat | grep -E "Pan123Api|CloudUriResolver|AuthAwareDataSource|PlayerService"
```

## 关键日志标签和过滤关键词

### 1. 追踪API调用
```
Tag: Pan123Api
关键词：getVideoPlayInfo, getFileDownloadUrl, buildDownloadHeaders
```

**成功标志：**
- `getVideoPlayInfo: SUCCESS` - API调用成功
- `getVideoPlayInfo: FINAL result - urls size=X` - 成功获取播放URL

**失败标志：**
- `getVideoPlayInfo: data is NULL` - API返回数据为空
- `getVideoPlayInfo: FAILED or returned empty urls` - 无法获取播放URL
- `getFileDownloadUrl: URL is BLANK` - 下载URL为空

### 2. 追踪URL解析流程
```
Tag: CloudUriResolver
关键词：resolvePan123
```

**成功标志：**
- `resolvePan123: Returning video URL` - 成功解析并返回视频URL
- `resolvePan123 END (video play)` - 使用视频播放API成功

**失败标志：**
- `resolvePan123: token is BLANK` - 未登录123云盘
- `resolvePan123: fileMetadata is NULL` - 文件元数据为空
- `resolvePan123 FAILED: returning null` - 解析失败

### 3. 追踪请求头设置
```
Tag: AuthAwareDataSource
关键词：applyHttpAuth, applyCloudPlayHeaders, pan123Play
```

**成功标志：**
- `applyHttpAuth: Applying Pan123 auth - setting headers` - 成功设置123云盘认证头
- `applyCloudPlayHeaders: Set header` - 成功设置请求头

**失败标志：**
- `pan123Play detected but Pan123AuthProvider.isActive=false` - 认证状态未激活
- `applyHttpAuth: Pan123AuthProvider.isActive=FALSE` - 认证未激活

### 4. 追踪播放错误
```
Tag: PlayerService
关键词：onPlayerError
```

**错误代码说明：**
- `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED` - 网络连接失败
- `ERROR_CODE_IO_FILE_NOT_FOUND` - 文件未找到
- `ERROR_CODE_DECODER_INIT_FAILED` - 解码器初始化失败
- `ERROR_CODE_DECODER_FORMAT_UNSUPPORTED` - 视频格式不支持
- `ERROR_CODE_PARSING_CONTAINER_MALFORMED` - 视频容器格式错误
- `ERROR_CODE_PARSING_MANIFEST_MALFORMED` - HLS manifest格式错误

## 问题诊断流程

### 步骤1：确认API调用是否成功
在Logcat中搜索：`Pan123Api: getVideoPlayInfo`

**如果看到：**
- ✅ `SUCCESS` - API调用成功，问题可能在其他地方
- ❌ `data is NULL` 或 `FAILED` - API调用失败，检查：
  - Token是否过期
  - 文件是否需要VIP才能播放
  - 文件是否已被删除

### 步骤2：确认URL解析是否成功
在Logcat中搜索：`CloudUriResolver: resolvePan123`

**如果看到：**
- ✅ `Returning video URL` - URL解析成功
- ❌ `FAILED: returning null` - URL解析失败，检查：
  - fileMetadata是否为空
  - etag和size是否正确

### 步骤3：确认请求头是否正确设置
在Logcat中搜索：`AuthAwareDataSource: applyHttpAuth`

**如果看到：**
- ✅ `Applying Pan123 auth` - 请求头设置成功
- ❌ `Pan123AuthProvider.isActive=FALSE` - 认证状态未激活

### 步骤4：查看播放错误详情
在Logcat中搜索：`PlayerService: onPlayerError`

**根据错误代码判断问题：**
- 如果是网络错误 - 检查CDN URL是否可访问
- 如果是解码错误 - 视频格式可能不支持
- 如果是401/403 - 认证头可能不正确

## 常见问题和解决方案

### 问题1：getVideoPlayInfo返回空数据
**可能原因：**
- 视频需要VIP才能播放
- 视频正在处理中
- Token已过期

**解决方案：**
- 检查API返回的code和message
- 尝试重新登录123云盘

### 问题2：请求头设置不正确
**可能原因：**
- Pan123AuthProvider.isActive未设置为true
- Referer或User-Agent不正确

**解决方案：**
- 检查CloudUriResolver中是否正确激活了Pan123AuthProvider
- 检查buildDownloadHeaders是否正确解析了ref参数

### 问题3：CDN URL访问失败（403错误）
**可能原因：**
- Referer不正确
- URL已过期
- IP限制

**解决方案：**
- 检查Referer是否正确（应该是从CDN URL的ref参数解密得到）
- 尝试重新获取播放URL

### 问题4：视频格式不支持
**可能原因：**
- 视频编码格式ExoPlayer不支持
- 原画视频格式特殊

**解决方案：**
- 尝试使用转码后的清晰度（如果有）
- 使用下载URL作为备用（会下载完整文件再播放）

## 测试步骤

1. **编译并安装APP**
   ```bash
   ./gradlew assembleDebug
   ```

2. **清除旧日志**
   ```bash
   adb logcat -c
   ```

3. **开始录制日志**
   ```bash
   adb logcat -s Pan123Api CloudUriResolver AuthAwareDataSource PlayerService > logs.txt
   ```

4. **复现问题**
   - 打开APP
   - 进入123云盘
   - 尝试播放失败的视频
   - 记录错误信息

5. **分析日志**
   - 按照上述诊断流程分析日志
   - 找到具体的失败原因

## 下一步

根据日志分析结果，可能需要：
1. 修复API调用逻辑
2. 修正请求头设置
3. 增强错误处理和重试逻辑
4. 添加更多清晰度选项（如果转码列表为空）

请将日志文件发给我，我会帮你进一步分析问题原因。
