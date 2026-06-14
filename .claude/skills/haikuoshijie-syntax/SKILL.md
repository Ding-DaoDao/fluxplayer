# 海阔视界语法 Skill

此 Skill 提供海阔视界规则和 JS 语法的完整参考，帮助 Agent 理解、编写和调试海阔视界规则。

## 触发条件

当用户提到以下关键词时激活此 Skill：
- 海阔视界规则、海阔语法、hiker规则、视界规则
- 海阔视界 JS、hiker JS、视界脚本
- 编写规则、规则语法、解析规则
- 频道规则、搜索规则、首页规则

---

## 核心语法快速参考

### 1. 链接语法

#### 搜索链接
使用 `**` 或 `%%` 作为关键词占位符：
```
https://example.com/search?q=**
```

#### 频道链接
支持动态替换词：
- `fypage` - 分页页码（从1开始）
- `fyclass` - 分类替换词
- `fyarea` - 地区替换词
- `fyyear` - 年代替换词
- `fysort` - 排序替换词
- `fyAll` - 通用替换词（分类、年代、地区都替换）

分页规则：`fypage@-1@*20@` 表示 0, 20, 40 递增

示例：
```
https://example.com/list?page=fypage&class=fyclass&area=fyarea
```

#### 第一页特殊处理
```
http://a.com/fypage.html[firstPage=http://a.com/]
```

### 2. 解析规则语法

#### 首页频道解析
格式：`列表;标题;图片;描述;链接`

示例：
```
body&&#post-list&&li;a&&title;img&&src;.index-intro&&Text;a&&href
```

语法说明：
- `&&` - 取子元素
- `#` - 取 ID
- `.` - 取 class
- `tag` - 直接写标签名
- `Text` - 获取文字
- `Html` - 获取包含标签的文本
- `--` - 排除元素
- `,index` - 取索引（支持负数倒序）
- `||` - 或语法（先找前者，找不到再找后者）

#### 搜索解析
格式：`列表;标题;链接;描述;详情;图片`

示例：
```
.list-content&&.u-movie;h2&&Text;a&&href;.pingfen&&Text;.meta&&Text;img&&data-original
```

### 3. 二级列表规则

#### 基础二级列表
格式：`列表;标题;图片;描述;链接;显示样式`

显示样式可选，没有则从上一级继承。

#### 深层嵌套
```
列表;标题;图片;描述;链接;样式==>列表;标题;图片;描述;链接;样式==>列表;标题;图片;描述;链接;样式
```

#### 链接继承
二级列表直接使用上一级链接，链接位置写 `*`：
```
body&&.playlist&&li;a&&Text;*;*;a&&href
```

#### 点击位置占位符
```
body&&.stui-pannel__head&&li;a&&Text;*;*;*==>body&&.stui-content__playlist,fyIndex&&li;a&&Text;*;*;a&&href;text_3
```

### 4. 动态解析规则

在链接后添加 `@lazyRule=` 实现动态解析：
```
a&&href.js:input+'@lazyRule=body＆＆＆＆a＆＆＆＆href'
```

注意：`&&` 需用中文 `＆＆＆＆` 代替

纯 JS 动态解析：
```
body&&.playlist&&li;a&&Text;*;*;a&&href.js:input+'@lazyRule=.js:input'
```

不显示 loading 弹窗：
```
a&&href.js:input+'#noLoading#@lazyRule=.js:input'
```

### 5. POST 请求

#### 普通 POST
```
http://www.google.com？？action=search?q=1&s=**;POST;gbk;{User-Agent@Windows&&Cookie@id}
```

#### JSON POST
```
http://www.google.com?q=1&JsonBody={"key1":"**","key2":233};POST;gbk;{User-Agent@Windows}
```

### 6. Header 规则

格式：`{HeaderName@value&&HeaderName2@value2}`

支持 JS 处理：
```
{User-Agent@Windows&&Timestamp@.js:new Date().getTime()}
```

### 7. 页面标识

| 标识 | 功能 | 说明 |
|------|------|------|
| `#immersiveTheme#` | 沉浸式页面 | 仅二级和子页面 |
| `#readTheme#` | 阅读模式 | 支持点击/音量键翻页 |
| `#fullTheme#` | 全屏模式 | 仅二级和子页面 |
| `#gameTheme#` | 游戏模式 | 右上角显示菜单 |
| `#noHistory#` | 不记录足迹 | |
| `#noRecordHistory#` | 不记录历史 | |
| `#noRefresh#` | 禁止下拉刷新 | |
| `#noLoading#` | 不显示 loading | |
| `#pre#` | 强制预加载 | |
| `#noPre#` | 禁止预加载 | |
| `#ignoreImg=true#` | 不识别为图片 | |
| `#ignoreVideo=true#` | 不识别为视频 | |
| `#isVideo=true#` | 强制识别为视频 | |
| `#ignoreMusic=true#` | 不识别为音频 | |
| `#isMusic=true#` | 强制识别为音频 | |
| `#autoPage#` | 自动翻页 | 小说章节 |
| `#autoCache#` | 自动缓存页面 | |
| `#cacheOnly#` | 仅使用缓存 | |
| `#background#` | 后台播放音频 | |
| `#originalSize#` | 图片原图尺寸 | |
| `#ignoreM3U8#` | 不识别为 M3U8 | |
| `#isM3u8#` | 强制识别为 M3U8 | |

### 8. 视频多线路

```json
{urls:['http://xxx/1.mp4','http://xxx/2.mp4'],names:['超清','高清']}
```

带 Header：
```json
{urls:['http:///1.mp4','http://2.mp4'], headers: [{'Referer': 'xxx'}, {'Referer': 'yyy'}]}
```

外挂字幕：
```json
{urls:['http://xxx/1.mp4'],subtitle:'http://xxx/1.srt'}
```

弹幕：
```json
{urls:['http://xxx/1.mp4'],danmu:'http://xxx/1.xml'}
```

歌词：
```json
{urls:['http://xxx/1.mp3'],lyric:'http://xxx/1.lrc'}
```

### 9. 音频分离

```js
{url: JSON.stringify({urls: [url], audioUrls: [audio]}), col_type: 'text_3'}
```

---

## hiker:// 协议路由

### 内置路由

| 路由 | 功能 |
|------|------|
| `hiker://home` | 展开首页频道 |
| `hiker://home@规则名` | 跳转指定频道 |
| `hiker://search` | 跳转搜索页面 |
| `hiker://search?s=关键词` | 带关键词搜索 |
| `hiker://search?s=关键词&group=分组` | 指定分组搜索 |
| `hiker://search?s=关键词&rule=规则名` | 指定规则搜索 |
| `hiker://bookmark` | 跳转书签页面 |
| `hiker://history` | 跳转历史记录 |
| `hiker://collection` | 跳转收藏页面 |
| `hiker://download` | 跳转下载中心 |
| `hiker://setting` | 跳转设置页面 |
| `hiker://empty` | 返回空字符串 |
| `hiker://debug` | 打开开发助手 |

### 功能路由

| 路由 | 功能 |
|------|------|
| `toast://文本` | 提示文本信息 |
| `copy://文本` | 复制到剪贴板 |
| `rule://口令` | 导入规则口令 |
| `web://链接` | 强制跳转网页 |
| `x5://链接` | X5 全屏显示 |
| `x5Play://链接` | X5 播放器播放 |
| `video://链接` | 自动提取视频 |
| `download://链接` | 下载文件 |
| `share://文件路径` | 分享文件 |
| `pics://url1&&url2` | 多图模式 |

### 弹窗路由

| 路由 | 功能 |
|------|------|
| `input://{"value":"默认","js":"代码"}` | 弹出输入框 |
| `confirm://提示信息.js:代码` | 弹出确认框 |
| `select://{"title":"标题","options":["选项"],"js":"代码"}` | 弹出选择框 |

### 特殊路由

| 路由 | 功能 |
|------|------|
| `javascript:代码` | 执行 JS 代码（彩蛋模式） |
| `webview://链接` | WebView 获取源码 |
| `webRule://链接@JS代码` | 网页资源嗅探 |
| `x5Rule://链接@JS代码` | X5 资源嗅探 |
| `hiker://webdav` | 备份规则到 WebDAV |
| `hiker://webRule` | Web 编辑规则模式 |
| `hiker://page/子页面` | 跳转子页面 |
| `hiker://files/文件路径` | 本地文件路径 |

### 空路由
```
hiker://empty#http://a.com@rule=js:fetch(MY_URL.split('#')[1],{})
```

---

## JS 语法参考

### 内置变量

| 变量 | 说明 |
|------|------|
| `MY_URL` | 当前请求地址 |
| `MY_HOME` | 主页地址 |
| `MY_RULE` | 当前规则对象 |
| `MY_PAGE` | 当前页数 |
| `MY_TYPE` | 页面类型（home/search） |
| `MY_PARAMS` | 上级页面传递的参数 |
| `MY_NAME` | 应用名（海阔视界/嗅觉浏览器） |
| `MOBILE_UA` | 移动端 User-Agent |
| `PC_UA` | 电脑端 User-Agent |

### 常用函数

#### 网络请求
```js
// GET 请求
fetch('http://www.example.com')

// POST 请求
fetch('http://www.example.com', {body: 'a=b&c=d', method: 'POST'})

// JSON POST
fetch('http://www.example.com', {body: {a: 'xx', b: 1}})

// 带 Header
fetch('http://www.example.com', {
  headers: {'content-type': 'application/json'},
  body: {},
  method: 'POST'
})

// 获取 Header
fetch('http://www.example.com', {withHeaders: true})

// 获取状态码
fetch('http://www.example.com', {withStatusCode: true})

// 仅获取 Header
fetch('http://www.example.com', {onlyHeaders: true})

// 禁止重定向
fetch('http://www.example.com', {redirect: false})

// 设置超时
fetch('http://www.example.com', {timeout: 5000})

// 使用 PC UA
fetchPC('http://www.example.com')

// 批量请求
batchFetch([
  {url: url1, options: {}},
  {url: url2, options: {}}
])

// 获取响应码
getResCode()

// 获取 Cookie
fetchCookie('http://www.example.com')

// 获取当前网页 Cookie
getCookie('http://www.example.com')

// 自定义 DNS
registerDNS({'example.com': '1.1.1.1'})

// IP 检测
ipping('1.1.1.1', 2000)
findReachableIP(['1.1.1.1', '2.2.2.2'], 2000)

// 同步请求（类 ajax）
http.fetch('http://a.com').success(data=>{log(data)}).start()

// 获取 InputStream
let stream = fetch(url, {inputStream: true});
try { /* 执行逻辑 */ } catch(e){}
closeMe(stream);

// 拼接 URL 参数
fetch(buildUrl('http://www.example.com', {a:'b', c:'d'}))

// byte[] 转 16 进制
fetch(url, {toHex: true})

// WebView 获取源码
fetchCodeByWebView('http://a.com')
```

#### 内容解析
```js
// 规则解析
parseDom(html, 'body&&a&&href')

// 获取内容（不自动补全域名）
parseDomForHtml(html, 'body&&a&&Text')

// 获取列表数组
parseDomForArray(html, 'body&&li')

// 缩写
pd(html, rule)  // parseDom 缩写
pdfh(html, rule)  // parseDomForHtml 缩写
pdfa(html, rule)  // parseDomForArray 缩写

// XPath 解析
let href = xpath(html, '//div[@id=root]/a[1]/@href')
let urls = xpathArray(html, '//div/a/@href')
```

#### 编解码
```js
base64Encode(input)
base64Decode(input)
aesDecode('key', input)
aesEncode('key', input)
decodeStr(input, 'UTF-8')
encodeStr(input, 'GBK')
rsaEncrypt(data, 'key', options)
rsaDecrypt(encryptBase64Data, 'key', options)
rc4.encode('明文', '密钥', 'UTF-8')
rc4.decode('密文', '密钥', 'UTF-8')
hexToBytes('aaa')
hexToBase64(hexString)
md5('xxx')
convertBase64Image(url)
```

#### 全局变量
```js
// 全局变量（重启失效）
putVar('name', '张三')
getVar('name')
getVar('key', 'default')
clearVar('name')

// 规则内全局变量
putMyVar('name', '张三')
getMyVar('name', 'defaultValue')
clearMyVar('name')
listMyVarKeys()

// 持久化存储（支持 JSON）
storage0.putMyVar('a', {a: 1})
storage0.getMyVar('a')
storage0.putVar('key', 'value')
storage0.getVar('key')
storage0.setItem('key', 'value')
storage0.getItem('key')
```

#### 私有化存储
```js
// 存储（规则私有）
setItem('key', 'value')

// 读取
getItem('key', 'defaultValue')

// 公开存储（所有规则可访问）
setPublicItem('key', 'value')
getPublicItem('key', 'defaultValue')
clearPublicItem('key')
```

#### 私有文件操作
```js
// 写入（自动加密）
saveFile('fileName.txt', 'content')

// 读取
readFile('fileName.txt')

// 删除
deleteFile('fileName.txt')

// 检测存在
fileExist('fileName.txt')
```

#### 云剪贴板
```js
// 获取可用云剪贴板
getPastes()

// 分享到云剪贴板
sharePaste(content, paste)

// 解析云剪贴板
parsePaste(url)
```

#### 页面操作
```js
// 刷新页面
refreshPage()
refreshPage(false)  // 不滚动到顶部

// 关闭页面
back()  // 关闭并刷新前一个页面
back(true)  // 关闭并刷新
back(false)  // 仅关闭

// 复制文本
copy('text')

// 获取页面标题
getPageTitle()
setPageTitle('标题')

// 获取链接参数
getParam('key', 'defaultValue')

// 消息提示
toast('提示信息')

// 显示/隐藏 Loading
showLoading('加载中')
hideLoading()

// 确认弹窗
confirm({
  title: '提示',
  content: '内容',
  confirm: $.toString(() => {}),
  cancel: $.toString(() => {})
})

// 修改页面图片
setPagePicUrl('http://xxx.jpg')

// 修改页面参数
setPageParams({a: '1'})

// 设置最新章节规则
setLastChapterRule('规则')

// 获取所有首页样式
getColTypes()

// 获取子页面内容
request('hiker://page/detail')

// 刷新 X5 内容
refreshX5WebView('http://1.com')
refreshX5Desc('float&&255')
```

#### 结果输出
```js
// 首页结果
setHomeResult([{title:'标题', pic_url:'图片', desc:'描述', url:'链接'}])

// 搜索结果
setSearchResult([{title:'标题', img:'图片', desc:'描述', content:'详情', url:'链接'}])

// 错误信息
setError('错误信息')

// 日志输出
log({key: 'value'})
```

#### 文件操作
```js
// 写入文件
writeFile('hiker://files/a.txt', 'content')

// 读取文件
readFile('hiker://files/a.txt')

// 保存文件
saveFile('hiker://files/a.txt', 'content')

// 检测文件
fileExist('hiker://files/1.png')

// 保存图片
saveImage('http://x.com/1.png', 'hiker://files/1.png')

// 下载文件
downloadFile('http://xxx.jar', 'hiker://files/cache/xxx.jar')

// 获取文件绝对路径
getPath('hiker://files/a.txt')
```

#### 其他函数
```js
// 获取当前规则
getRule()

// 获取当前地址
setError(MY_URL)

// 获取应用版本
getAppVersion()

// 获取订阅
getHomeSub()
hasHomeSub('http://example.com')

// 获取历史规则
getLastRules(12)

// 获取小程序数量
getRuleCount()

// 获取手机 IP
getIP()

// 获取 CPU ABI
getCpuAbi()

// 获取搜索模式
getSearchMode()
setSearchMode(1)
searchContains(text, key, false)

// 动态获取主页地址
getHome(MY_RULE.url)
```

### 动态界面操作

#### 更新元素
```js
updateItem('test_id1', {url:'xxx', title:'新标题', extra: {id: 'test_id1'}})
```

#### 删除元素
```js
deleteItem('test_id2')
deleteItem(['id1', 'id2'])
deleteItemByCls('box123')
```

#### 新增元素
```js
addItemAfter('test_id1', {url:'xxx', extra: {id: 'test_id2'}})
addItemBefore('test_id1', {url:'xxx', extra: {id: 'test_id2'}})
```

#### 查询元素
```js
let obj = findItem('test_id1')
let arr = findItemsByCls('test_cls')
```

### 高级功能

#### 定时任务
```js
registerTask('id', 10000, $.toString((obj) => { log('执行了') }, obj))
unRegisterTask('id')
```

#### 代理服务器
```js
let url = startProxyServer($.toString(() => { return MY_PARAMS.a }))
```

#### 同步锁定
```js
syncExecute({ func: (param) => {}, param: { a: 1 } })
```

#### 批量执行
```js
batchExecute(tasks, listener, successCount)
```

#### 加载 Java 字节码
```js
requireDownload('https://xxx.dex', 'hiker://files/cache/t.dex')
var test = loadJavaClass('hiker://files/cache/t.dex', 'com.test.code.TestCode')
```

#### M3U8 处理
```js
// 缓存 m3u8
cacheM3u8('http://xx.m3u8')

// 修正 m3u8 路径
fixM3u8('http://yy/xx.m3u8', '#EXT-X-KEY:xxx.key\nxxx.ts')

// 移除广告片段
clearM3u8Ad('http://1.com/1.m3u8')

// 批量缓存
batchCacheM3u8([{url:'http://www.a.cn', options:{}}])

// 缓存 m3u8 索引路径
cacheM3u8WithPngProxy(url, options, fileName)

// 转换 m3u8 内容
convertM3u8WithPngProxy(content, { headers: {} })
```

#### 配置管理
```js
// 写入配置
initConfig({key: 'value'})

// 读取配置
config.key
```

#### 远程模块
```js
// 远程模块引用
require('http://xxx/t.js?v=1')

// 临时缓存
requireCache('http://xxx/t.js', 24)

// 删除缓存
deleteCache('http://xxx/t.js')
```

#### 精准搜索
```js
getSearchMode()  // 0 默认, 1 精准
setSearchMode(1)
searchContains(text, key, false)
```

#### 聚合搜索代理
```js
{col_type: 'input', url: "'hiker://search?s=' + input", extra: {rules: "fetch('hiker://files/rules.json')"}}
```

#### 执行加密代码
```js
evalPrivateJS(code)
```

#### base64 工具
```js
let a = window0.btoa(code);
let b = window0.atob(a);
```

### $ 工具函数

#### $.toString()
将函数转为立即执行函数字符串：
```js
$.toString(() => { log('hello') })
$.toString((a, b) => { log(a + b) }, 1, 2)
```

#### $.require()
引用模块：
```js
let module = $.require('hiker://page/test')
let module = $.require('hiker://page/test', param)
```

#### $.exports
导出模块：
```js
$.exports = { data: 1 }
$.exports = () => log('hello')
```

#### $.log()
格式化输出：
```js
$.log([1, 2, 3])
$.log('%s', 'hello')
```

#### $.type()
类型判断：
```js
$.type({})  // 'object'
$.type([])  // 'array'
$.type(null)  // 'null'
```

#### $.dateFormat()
日期格式化：
```js
$.dateFormat(new Date(), 'yyyy-MM-dd')
$.dateFormat(1640667814055, 'yyyy年MM月dd日')
```

#### $.stringify()
对象转字符串：
```js
$.stringify({a: 1, b: 2})
```

#### $() 快捷方法
```js
// 生成规则链接
$(url).rule(() => { })

// 生成动态解析链接
$(url).lazyRule(() => { })
$(url, 'iframe&&src').lazyRule(() => { })

// 生成 X5 链接
$(url).x5Rule(() => { })

// 生成输入框链接
$("默认值", "提示").input(() => { })

// 生成确认框链接
$("提示").confirm(() => { })

// 生成图片解密链接
$(url, headers).image(() => { })
```

### 事件监听
```js
// 刷新事件
addListener('onRefresh', $.toString(() => { log('refresh') }))

// 关闭事件
addListener('onClose', $.toString(() => { }))
```

---

## 网页桥接 API (fy_bridge_app)

在 X5 组件或 `javascript:` 模式下使用 `fy_bridge_app`（可简写为 `fba`）。

### 基础操作
```js
// 播放视频
fy_bridge_app.playVideo(url)

// 播放多集视频
fy_bridge_app.playVideos(JSON.stringify([
  {"title":"测试","url":"http://example.com/1.mp4","use":true}
]))

// 显示图片
fy_bridge_app.showPic(url)

// 设置网页标题
fy_bridge_app.setWebTitle(title)

// 设置网页 UA
fy_bridge_app.setWebUa(ua)

// 设置状态栏颜色
fy_bridge_app.setAppBarColor('#ffffff')

// 导入规则口令
fy_bridge_app.importRule('rule')
```

### 请求相关
```js
// 同步请求
request(url, {headers:{},body:'',method:'POST'})

// 异步请求
requestAsync(url, param, key, callback)

// 解析动态解析规则
fy_bridge_app.parseLazyRule('http://x.com@lazyRule=.js:input')

// 异步解析
fy_bridge_app.parseLazyRuleAsync('http://x.com@lazyRule=.js:input', $.toString(()=>console.log(input)))

// 获取带 header 的视频地址
fba.getHeaderUrl('http://x.com/111.m3u8')

// 获取请求时携带的 header
getRequestHeaders('http://x.com/111.m3u8')
```

### 存储和 Cookie
```js
// 全局变量
fba.putVar('key', 'value')
fba.getVar('key')
fba.clearVar('key')

// Cookie
fy_bridge_app.getCookie('http://a.com/')
fy_bridge_app.getCookie('')
```

### 页面操作
```js
// 刷新二级页面
fy_bridge_app.refreshPage(true)

// 保存图片
fy_bridge_app.saveImage('http://x.com/1.png', 'hiker://files/1.png')

// 刷新 X5 内容
fy_bridge_app.refreshX5Desc('float&&255')

// 打开新页面
fy_bridge_app.newPage('标题', 'http://example.com')

// 跳转二级详情页
fba.open(JSON.stringify({rule: "规则名", title: "标题", url: "链接"}))

// 唤起第三方 APP
fy_bridge_app.openThirdApp('legado://xxxxx')

// 解析云剪贴板
fba.parsePaste('http://x.com')

// 移除 M3U8 广告
fy_bridge_app.clearM3u8Ad('http://1.com/1.m3u8')
```

### DOM 解析
```js
// 解析 DOM
fy_bridge_app.parseDomForHtml(html, rule)

// 解析数组（返回字符串，需 JSON.parse）
fy_bridge_app.parseDomForArray(html, rule)
```

### 其他
```js
// 获取当前 UA
fba.getUa()

// 写入文件
fy_bridge_app.writeFile(filePath, content)

// 获取加载过的资源
fy_bridge_app.getUrls()

// 打印日志
fy_bridge_app.log('msg')
```

---

## 列类型 (col_type)

### 视频布局
- `movie_3` - 三列，圆角矩形
- `movie_2` - 两列，圆角矩形
- `movie_1` - 一列
- `movie_1_left_pic` - 图片在左
- `movie_1_vertical_pic` - 竖向图片
- `movie_1_vertical_pic_blur` - 竖向图片+模糊背景

### 文本布局
- `text_1` - 一列文本
- `text_2` - 两列文本
- `text_3` - 三列文本
- `text_4` - 四列文本
- `text_5` - 五列文本
- `text_center_1` - 居中单行
- `long_text` - 长文本
- `rich_text` - 富文本

### 图片布局
- `pic_3` - 三列图片
- `pic_2` - 两列图片
- `pic_1` - 单列大图
- `pic_1_full` - 全宽图片
- `pic_1_center` - 居中图片

### 图标布局
- `icon_4` - 四列图标
- `icon_4_card` - 四列圆角图标
- `icon_small_4` - 四列小图标
- `icon_small_3` - 三列小图标
- `icon_round_4` - 四列圆形图标
- `icon_2` - 两列图标

### 其他布局
- `input` - 输入框
- `line` - 分割线
- `line_blank` - 空白分割线
- `avatar` - 头像
- `blank_block` - 空白块
- `x5_webview_single` - X5 浏览器组件
- `flex_button` - 自适应流式按钮
- `scroll_button` - 滚动按钮
- `card_pic_2` - 方形卡片

### 富文本颜色混排
使用中文引号设置颜色：
```
""红色文字""''橙色文字''黑色文字
```

---

## 导入规则语法

### 首页合集
```
海阔视界，首页频道合集￥home_rule_url￥https://example.com/rule.json
```

### 首页频道
```
海阔视界，首页频道￥home_rule￥{'title':'规则名'}
```

### 搜索引擎合集
```
海阔视界，搜索引擎合集￥search_engine_url￥https://example.com/rule
```

### 搜索引擎
```
海阔视界，搜索引擎￥search_engine_v2￥{'title':'规则名'}
```

### 网页插件
```
海阔视界，网页插件￥js_url￥插件名@https://example.com/rule
```

### 广告网址拦截
```
海阔视界，广告网址拦截￥ad_url_rule￥https://example.com/rule
```

### 书签规则
```
海阔视界，书签规则￥bookmark￥{'title':'规则名'}
```

### 书签合集
```
海阔视界，书签规则￥bookmark_url￥hiker://files/share/bookmarks.json
```

### 本地文件
```
海阔视界，本地文件￥file_url￥hiker://files/a.txt@https://example.com/rule
```

### 快速播放白名单
```
海阔视界，快速播放白名单￥fast_play_urls￥https://example.com/rule
```

### 嗅探弹窗黑名单
```
海阔视界，嗅探弹窗黑名单￥xt_dialog_rules￥example.com
```

### 广告拦截订阅
```
海阔视界，广告拦截订阅￥ad_subscribe_url￥{"urlV2": "https://...","domBlockRuleUrl": "https://..."}
```

### 小程序规则订阅
```
海阔视界，合集规则订阅￥home_sub￥https://example.com/sub.json
```

### 云仓库账号密码
```
海阔视界，云仓库账号密码设置￥publish_account￥用户名@密码
```

### 更新依赖
```
海阔视界，依赖更新￥require_url￥https://example.com/test.js
```

### 浏览器代理规则
```
海阔视界，浏览器代理规则￥web-proxy￥{"name": "test", "match": "test"}
```

### 云口令导入
```
云口令，复制整条口令打开软件即可导入
https://xxx.cn/test.js@import=js:writeFile('hiker://files/cache/test.js', fetch(input))
```

---

## 投屏 API

### 获取投屏播放地址
```
/playUrl
```

### 增强投屏接口
```
/playUrl?enhance=true
```
返回：`{title:'xxx-第一集', url:'',headers:{},jumpStartDuration:0,jumpEndDuration:0}`

### 获取选集列表
```
/getPlayList
```

### 播放指定集数
```
/playMe?index=0&title=第一集
```

### 播放下一集
```
/playNext
```

---

## WebDAV 接口

### 创建 WebDAV 连接
```js
let webdav = buildWebDav('http://xxx/dav', user, password);
let list = JSON.parse(webdav.list());
```

### 播放文件
```js
for (let it of list) {
  log(it.playUrl)
}
```

### 下载文件
```js
for (let it of list) {
  log('download://' + it.playUrl)
}
```

### 手动下载子文件
```js
webdav.download(it.name, 'hiker://files/cache/_fileSelect_' + it.name)
```

### 上传文件
```js
webdav.upload('a.mp4', 'hiker://files/cache/a.mp4')
```

### 删除文件/文件夹
```js
webdav.delete('a.mp4')
```

### 创建文件夹
```js
webdav.makeDir('视频')
```

### 子文件夹
```js
let childWebdav = buildWebDav(it.url, user, password)
```

---

## EPUB 接口

### 解析章节目录
```js
let c = getEpubChapters(path)
log(c[0].title + '-' + c[0].url)
```

### 获取正文
```js
let c = getEpubContent(path, chapter.url)
```

### 获取元信息
```js
let meta = getEpubMetadata(path)
```

---

## 广告拦截订阅

### 订阅格式
```json
{
    "urlV2": "https://ad_v2.txt",
    "domBlockRuleUrl": "https://domBlockRules.txt"
}
```

---

## 常用示例

### 示例 1: 简单首页规则
```
https://example.com/list?page=fypage&class=fyclass&area=fyarea
body&&.list-item;a&&title;img&&src;.desc&&Text;a&&href
```

### 示例 2: 搜索规则
```
https://example.com/search?q=**
.result-list&&.item;h2&&Text;a&&href;.score&&Text;.info&&Text;img&&src
```

### 示例 3: JS 首页规则
```js
var html = fetch('https://example.com/api/list');
var json = JSON.parse(html);
var d = [];
for (var i = 0; i < json.list.length; i++) {
  var j = json.list[i];
  d.push({
    title: j.title,
    pic_url: j.cover,
    desc: j.description,
    url: 'https://example.com/detail/' + j.id
  });
}
setHomeResult(d);
```

### 示例 4: 动态解析规则
```
body&&.playlist&&li;a&&Text;*;*;a&&href.js:input+'@lazyRule=body＆＆＆＆a＆＆＆＆href'
```

### 示例 5: 带分类的频道
```
https://example.com/list?page=fypage&class=fyclass&area=fyarea&year=fyyear
body&&.movie-item;a&&title;img&&src;.rating&&Text;a&&href
```

### 示例 6: 视频列表
```js
var html = fetch(MY_URL);
var d = [];
var list = pdfa(html, 'body&&.video-item');
for (var i = 0; i < list.length; i++) {
  var title = pdfh(list[i], 'a&&title');
  var img = pdfh(list[i], 'img&&src');
  var desc = pdfh(list[i], '.desc&&Text');
  var url = pdfh(list[i], 'a&&href');
  d.push({title: title, pic_url: img, desc: desc, url: url});
}
setHomeResult(d);
```

### 示例 7: 深层嵌套规则
```
body&&.tab&&li;a&&Text;*;*;*==>body&&.list,fyIndex&&li;a&&title;img&&src;.desc&&Text;a&&href
```

### 示例 8: 使用 $() 快捷方法
```js
d.push({url:$('https://example.com').lazyRule(() => setError(input))})
d.push({url:$('https://example.com').rule(() => $('https://example2.com').rule(() => setError(input)))})
```

---

## 调试技巧

1. 使用 `setError()` 输出调试信息
2. 使用 `log()` 记录日志
3. 使用 `fetch()` 测试网络请求
4. 使用 `parseDomForHtml()` 验证解析规则
5. 检查 Header 是否正确设置
6. 确认链接格式是否正确

## 注意事项

1. `&&` 在规则中用 `＆＆＆＆` 代替
2. 分号用中文 `；；` 代替英文 `;`
3. 问号用中文 `？？` 代替英文 `?`
4. 链接中的标识（如 `#noHistory#`）会在请求时自动删除
5. 全局变量重启后失效，使用 `storage0` 持久化
6. 规则内全局变量仅限当前规则访问
7. 动态解析规则不能嵌套使用
