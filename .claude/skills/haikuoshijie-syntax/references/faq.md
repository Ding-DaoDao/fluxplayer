# 海阔视界常见问题

## 目录
1. [规则编写问题](#规则编写问题)
2. [JS 语法问题](#js-语法问题)
3. [网络请求问题](#网络请求问题)
4. [解析问题](#解析问题)
5. [hiker:// 协议问题](#hiker-协议问题)
6. [网页桥接问题](#网页桥接问题)
7. [性能问题](#性能问题)
8. [其他问题](#其他问题)

---

## 规则编写问题

### Q1: 如何编写一个基础的海阔视界规则？

**A:** 一个基础的海阔视界规则由两部分组成：链接和解析规则。

```txt
# 链接部分
https://example.com/list?page=fypage&class=fyclass

# 解析规则部分
body&&.list-item;a&&title;img&&src;.desc&&Text;a&&href
```

### Q2: 链接中的 `**` 和 `%%` 有什么区别？

**A:** 两者都是关键词占位符，功能相同。使用 `%%` 是为了避免与某些 URL 中已有的 `**` 冲突。

```txt
# 使用 **
https://example.com/search?q=**

# 使用 %%
https://example.com/search?keyword=%%
```

### Q3: 如何处理第一页和后续页不同的 URL？

**A:** 使用 `[firstPage=]` 参数：

```txt
http://example.com/fypage.html[firstPage=http://example.com/]
```

这样第一页会加载 `http://example.com/`，第二页开始加载 `http://example.com/2.html`。

### Q4: 如何在规则中添加 Header？

**A:** 在链接后添加 Header 参数：

```txt
https://example.com/api;GET;UTF-8;{User-Agent@Windows&&Referer@https://example.com}
```

### Q5: 如何发送 POST 请求？

**A:** 使用中文 `？？` 代替 `?`，并添加 `POST` 标识：

```txt
https://example.com/search？？q=**;POST;UTF-8;{User-Agent@Windows}
```

### Q6: 如何发送 JSON 格式的 POST 请求？

**A:** 使用 `JsonBody` 参数：

```txt
https://example.com/api？？JsonBody={"key":"value"};POST;UTF-8;{User-Agent@Windows}
```

### Q7: 如何编写二级列表规则？

**A:** 二级列表规则格式与首页类似，但可以嵌套：

```txt
# 基础二级列表
body&&.playlist&&li;a&&Text;*;*;a&&href

# 深层嵌套
body&&.tab&&li;a&&Text;*;*;*==>body&&.list,fyIndex&&li;a&&title;img&&src;.desc&&Text;a&&href
```

### Q8: 如何使用链接继承？

**A:** 二级列表直接使用上一级链接，链接位置写 `*`：

```txt
body&&.playlist&&li;a&&Text;*;*;a&&href
```

### Q9: 如何使用点击位置占位符？

**A:** 使用 `fyIndex` 占位符：

```txt
body&&.stui-pannel__head&&li;a&&Text;*;*;*==>body&&.stui-content__playlist,fyIndex&&li;a&&Text;*;*;a&&href;text_3
```

---

## JS 语法问题

### Q1: 如何在 JS 中发起网络请求？

**A:** 使用 `fetch()` 函数：

```js
// GET 请求
var html = fetch('https://example.com');

// POST 请求
var html = fetch('https://example.com', {body: 'a=b', method: 'POST'});
```

### Q2: 如何在 JS 中解析 HTML？

**A:** 使用 `parseDom()` 或其缩写 `pd()`：

```js
var html = fetch(MY_URL);
var title = parseDom(html, 'title&&Text');
```

### Q3: 如何在 JS 中输出首页结果？

**A:** 使用 `setHomeResult()` 函数：

```js
var d = [];
d.push({
  title: '标题',
  pic_url: '图片地址',
  desc: '描述',
  url: '链接'
});
setHomeResult(d);
```

### Q4: 如何在 JS 中输出搜索结果？

**A:** 使用 `setSearchResult()` 函数：

```js
var d = [];
d.push({
  title: '标题',
  img: '图片地址',
  desc: '描述',
  content: '详情',
  url: '链接'
});
setSearchResult(d);
```

### Q5: 如何在 JS 中存储和读取全局变量？

**A:** 使用 `putVar()` 和 `getVar()`：

```js
// 存储（重启失效）
putVar('name', 'value');

// 读取
var value = getVar('name');

// 持久化存储
storage0.putVar('name', 'value');
var value = storage0.getVar('name');
```

### Q6: 如何在 JS 中使用 CryptoJS？

**A:** 先执行 `eval(getCryptoJS())`：

```js
eval(getCryptoJS());
var encrypted = CryptoJS.AES.encrypt('明文', '密钥').toString();
var decrypted = CryptoJS.AES.decrypt(encrypted, '密钥').toString(CryptoJS.enc.Utf8);
```

### Q7: 如何在 JS 中使用 XPath？

**A:** 使用 `xpath()` 和 `xpathArray()`：

```js
var href = xpath(html, '//div[@id=root]/a[1]/@href');
var urls = xpathArray(html, '//div/a/@href');
```

### Q8: 如何在 JS 中使用 RC4 加解密？

**A:** 使用 `rc4.encode()` 和 `rc4.decode()`：

```js
var encoded = rc4.encode('明文', '密钥', 'UTF-8');
var decoded = rc4.decode(encoded, '密钥', 'UTF-8');
```

### Q9: 如何在 JS 中执行定时任务？

**A:** 使用 `registerTask()`：

```js
registerTask('id', 10000, $.toString((obj) => { log('执行了') }, obj));
unRegisterTask('id');
```

### Q10: 如何在 JS 中启动代理服务器？

**A:** 使用 `startProxyServer()`：

```js
let url = startProxyServer($.toString(() => { return MY_PARAMS.a })) + '?a=b';
```

### Q11: 如何在 JS 中缓存 M3U8 文件？

**A:** 使用 `cacheM3u8()`：

```js
const a = cacheM3u8('http://xx.m3u8');
```

### Q12: 如何在 JS 中移除 M3U8 广告片段？

**A:** 使用 `clearM3u8Ad()`：

```js
let url = clearM3u8Ad('http://1.com/1.m3u8');
```

### Q13: 如何在 JS 中动态操作界面元素？

**A:** 使用 `updateItem()`、`deleteItem()`、`addItemAfter()` 等：

```js
// 更新元素
updateItem('test_id1', {url:'xxx', title:'新标题', extra: {id: 'test_id1'}})

// 删除元素
deleteItem('test_id2')

// 新增元素
addItemAfter('test_id1', {url:'xxx', extra: {id: 'test_id2'}})
```

### Q14: 如何在 JS 中使用云剪贴板？

**A:** 使用 `getPastes()`、`sharePaste()`、`parsePaste()`：

```js
// 获取可用云剪贴板
var pastes = getPastes();

// 分享到云剪贴板
var url = sharePaste(content, paste);

// 解析云剪贴板
var content = parsePaste(url);
```

### Q15: 如何在 JS 中使用私有化存储？

**A:** 使用 `setItem()`、`getItem()`、`setPublicItem()`、`getPublicItem()`：

```js
// 私有存储
setItem('key', 'value');
var value = getItem('key', 'defaultValue');

// 公开存储
setPublicItem('key', 'value');
var value = getPublicItem('key', 'defaultValue');
```

### Q16: 如何在 JS 中操作私有文件？

**A:** 使用 `saveFile()`、`readFile()`、`deleteFile()`、`fileExist()`：

```js
// 写入（自动加密）
saveFile('fileName.txt', 'content');

// 读取
var content = readFile('fileName.txt');

// 删除
deleteFile('fileName.txt');

// 检测存在
var exists = fileExist('fileName.txt');
```

### Q17: 如何在 JS 中使用远程模块？

**A:** 使用 `require()`、`requireCache()`、`deleteCache()`：

```js
// 远程模块引用
require('http://xxx/t.js?v=1');

// 临时缓存（24小时）
requireCache('http://xxx/t.js', 24);

// 删除缓存
deleteCache('http://xxx/t.js');
```

### Q18: 如何在 JS 中使用精准搜索？

**A:** 使用 `getSearchMode()`、`setSearchMode()`、`searchContains()`：

```js
var mode = getSearchMode();  // 0 默认, 1 精准
setSearchMode(1);
if (searchContains(text, key, false)) {
  // 精准匹配
}
```

---

## 网络请求问题

### Q1: 请求超时怎么办？

**A:** 增加超时时间：

```js
fetch(url, {timeout: 10000})  // 10秒
```

### Q2: 如何获取响应的 Header？

**A:** 使用 `withHeaders: true`：

```js
var result = fetch(url, {withHeaders: true});
// 返回格式: {body: '内容', headers: {'Set-Cookie': ['a=b']}}
```

### Q3: 如何获取响应状态码？

**A:** 使用 `withStatusCode: true`：

```js
var result = fetch(url, {withStatusCode: true});
// 返回格式: {body: '内容', headers: {}, statusCode: 200}
```

### Q4: 如何禁止重定向？

**A:** 使用 `redirect: false`：

```js
fetch(url, {redirect: false})
```

### Q5: 如何使用 PC 端的 User-Agent？

**A:** 使用 `fetchPC()` 代替 `fetch()`：

```js
fetchPC(url)
```

### Q6: 如何设置自定义 DNS？

**A:** 使用 `dns` 参数：

```js
fetch(url, {dns: 'https://dns.alidns.com/dns-query'})
```

或使用 `registerDNS()` 批量注册：

```js
registerDNS({'example.com': '1.1.1.1'})
```

### Q7: 如何批量发起请求？

**A:** 使用 `batchFetch()`：

```js
var results = batchFetch([
  {url: url1, options: {}},
  {url: url2, options: {}}
]);
```

### Q8: 如何使用 WebView 获取源码？

**A:** 使用 `fetchCodeByWebView()`：

```js
let a = fetchCodeByWebView('http://a.com');
```

---

## 解析问题

### Q1: 选择器中的 `&&` 是什么意思？

**A:** `&&` 表示取子元素：

```txt
body&&.list&&li  # 选取 body 下的 .list 下的 li
```

### Q2: 如何选择特定索引的元素？

**A:** 使用逗号分隔索引：

```txt
body&&a,0  # 第一个 a 标签
body&&a,1  # 第二个 a 标签
body&&a,-1  # 最后一个 a 标签
```

### Q3: 如何排除某些元素？

**A:** 使用 `--` 排除：

```txt
body--a&&a&&href  # 排除第一个 a 标签后取剩下的第一个 a 标签的 href
```

### Q4: 如何获取元素的文本内容？

**A:** 在规则末尾添加 `Text`：

```txt
a&&Text  # 获取 a 标签的文本
```

### Q5: 如何获取元素的 HTML 内容？

**A:** 在规则末尾添加 `Html`：

```txt
a&&Html  # 获取 a 标签的 HTML
```

### Q6: 如何拼接多个选择器的结果？

**A:** 使用 `+` 拼接：

```txt
a,0&&title+'--'+a,1&&title
```

### Q7: 如何在选择器中使用 JS 处理？

**A:** 在选择器后添加 `.js:`：

```txt
a&&href.js:input.replace('http://', 'https://')
```

---

## hiker:// 协议问题

### Q1: 如何跳转首页频道？

**A:** 使用 `hiker://home`：

```
hiker://home
hiker://home@V电影
hiker://home@规则1||规则2||http://example.com
```

### Q2: 如何跳转搜索页面？

**A:** 使用 `hiker://search`：

```
hiker://search
hiker://search?s=测试
hiker://search?s=测试&group=②影搜
hiker://search?s=测试&rule=海阔视界
```

### Q3: 如何提示文本信息？

**A:** 使用 `toast://`：

```
toast://加载失败，请换源
```

### Q4: 如何复制到剪贴板？

**A:** 使用 `copy://`：

```
copy://要复制的内容
```

### Q5: 如何弹出输入框？

**A:** 使用 `input://`：

```
input://{"value":"默认","js":"'toast://'+input","hint":"提示"}
```

### Q6: 如何弹出确认框？

**A:** 使用 `confirm://`：

```
confirm://提示信息.js:'toast://确认'
```

### Q7: 如何弹出选择框？

**A:** 使用 `select://`：

```
select://{"title":"选择","options":["选项一","选项二"],"js":"'toast://'+input"}
```

### Q8: 如何强制跳转网页？

**A:** 使用 `web://`：

```
web://http://example.com
```

### Q9: 如何 X5 全屏显示？

**A:** 使用 `x5://`：

```
x5://http://example.com
```

### Q10: 如何自动提取视频？

**A:** 使用 `video://`：

```
video://https://example.com/xxx.html
```

### Q11: 如何下载文件？

**A:** 使用 `download://`：

```
download://http://example.com/1.mp4
```

### Q12: 如何分享文件？

**A:** 使用 `share://`：

```
share://hiker://files/a.txt
```

### Q13: 如何显示多图模式？

**A:** 使用 `pics://`：

```
pics://https://a.com/1.jpg&&https://a.com/2.jpg
```

### Q14: 如何导入规则口令？

**A:** 使用 `rule://` 或海阔视界开头：

```
rule://base64编码的规则
海阔视界，当前分享的是...
```

### Q15: 如何执行 JS 代码（彩蛋模式）？

**A:** 使用 `javascript:`：

```
javascript:var a = 'a'
```

---

## 网页桥接问题

### Q1: 如何在网页中播放视频？

**A:** 使用 `fy_bridge_app.playVideo()`：

```js
fy_bridge_app.playVideo(url);
```

### Q2: 如何在网页中播放多集视频？

**A:** 使用 `fy_bridge_app.playVideos()`：

```js
fy_bridge_app.playVideos(JSON.stringify([
  {"title":"测试","url":"http://example.com/1.mp4","use":true}
]));
```

### Q3: 如何在网页中显示图片？

**A:** 使用 `fy_bridge_app.showPic()`：

```js
fy_bridge_app.showPic(url);
```

### Q4: 如何在网页中设置标题？

**A:** 使用 `fy_bridge_app.setWebTitle()`：

```js
fy_bridge_app.setWebTitle('标题');
```

### Q5: 如何在网页中设置 UA？

**A:** 使用 `fy_bridge_app.setWebUa()`：

```js
fy_bridge_app.setWebUa(ua);
```

### Q6: 如何在网页中设置状态栏颜色？

**A:** 使用 `fy_bridge_app.setAppBarColor()`：

```js
fy_bridge_app.setAppBarColor('#ffffff');
```

### Q7: 如何在网页中导入规则口令？

**A:** 使用 `fy_bridge_app.importRule()`：

```js
fy_bridge_app.importRule('rule');
```

### Q8: 如何在网页中发起同步请求？

**A:** 使用 `request()`：

```js
request(url, {headers:{},body:'',method:'POST'});
```

### Q9: 如何在网页中发起异步请求？

**A:** 使用 `requestAsync()`：

```js
requestAsync(url, param, key, callback);
```

### Q10: 如何在网页中解析动态解析规则？

**A:** 使用 `fy_bridge_app.parseLazyRule()`：

```js
fy_bridge_app.parseLazyRule('http://x.com@lazyRule=.js:input');
```

### Q11: 如何在网页中获取带 header 的视频地址？

**A:** 使用 `fba.getHeaderUrl()`：

```js
fba.getHeaderUrl('http://x.com/111.m3u8');
```

### Q12: 如何在网页中获取请求时携带的 header？

**A:** 使用 `getRequestHeaders()`：

```js
getRequestHeaders('http://x.com/111.m3u8');
```

### Q13: 如何在网页中存储全局变量？

**A:** 使用 `fba.putVar()`：

```js
fba.putVar('key', 'value');
fba.getVar('key');
fba.clearVar('key');
```

### Q14: 如何在网页中读取 Cookie？

**A:** 使用 `fy_bridge_app.getCookie()`：

```js
fy_bridge_app.getCookie('http://a.com/');
fy_bridge_app.getCookie('');  // 当前网页的 cookie
```

### Q15: 如何在网页中刷新二级页面？

**A:** 使用 `fy_bridge_app.refreshPage()`：

```js
fy_bridge_app.refreshPage(true);
```

### Q16: 如何在网页中保存图片？

**A:** 使用 `fy_bridge_app.saveImage()`：

```js
fy_bridge_app.saveImage('http://x.com/1.png', 'hiker://files/1.png');
```

### Q17: 如何在网页中打开新页面？

**A:** 使用 `fy_bridge_app.newPage()`：

```js
fy_bridge_app.newPage('标题', 'http://example.com');
```

### Q18: 如何在网页中跳转二级详情页？

**A:** 使用 `fba.open()`：

```js
fba.open(JSON.stringify({rule: "规则名", title: "标题", url: "链接"}));
```

### Q19: 如何在网页中唤起第三方 APP？

**A:** 使用 `fy_bridge_app.openThirdApp()`：

```js
fy_bridge_app.openThirdApp('legado://xxxxx');
```

### Q20: 如何在网页中解析云剪贴板？

**A:** 使用 `fba.parsePaste()`：

```js
fba.parsePaste('http://x.com');
```

### Q21: 如何在网页中移除 M3U8 广告？

**A:** 使用 `fy_bridge_app.clearM3u8Ad()`：

```js
fy_bridge_app.clearM3u8Ad('http://1.com/1.m3u8');
```

### Q22: 如何在网页中获取当前 UA？

**A:** 使用 `fba.getUa()`：

```js
fba.getUa();
```

### Q23: 如何在网页中写入文件？

**A:** 使用 `fy_bridge_app.writeFile()`：

```js
fy_bridge_app.writeFile(filePath, content);
```

### Q24: 如何在网页中获取加载过的资源？

**A:** 使用 `fy_bridge_app.getUrls()`：

```js
fy_bridge_app.getUrls();
```

### Q25: 如何在网页中打印日志？

**A:** 使用 `fy_bridge_app.log()`：

```js
fy_bridge_app.log('msg');
```

### Q26: 如何在网页中解析 DOM？

**A:** 使用 `fy_bridge_app.parseDomForHtml()`：

```js
fy_bridge_app.parseDomForHtml(html, rule);
```

### Q27: 如何在网页中刷新 X5 内容？

**A:** 使用 `fy_bridge_app.refreshX5Desc()`：

```js
fy_bridge_app.refreshX5Desc('float&&255');
```

---

## 性能问题

### Q1: 如何优化规则性能？

**A:**
1. 使用 JS 规则代替传统规则
2. 减少网络请求次数
3. 使用缓存存储数据
4. 设置合理的超时时间
5. 避免重复解析

### Q2: 如何预加载视频？

**A:** 在视频链接后添加 `#pre#`：

```txt
http://example.com/video.mp4#pre#
```

### Q3: 如何禁止预加载？

**A:** 在链接后添加 `#noPre#`：

```txt
http://example.com/video.mp4#noPre#
```

### Q4: 如何自动缓存页面？

**A:** 在链接后添加 `#autoCache#`：

```txt
hiker://page/detail#autoCache#
```

### Q5: 如何仅使用缓存？

**A:** 在链接后添加 `#cacheOnly#`：

```txt
hiker://page/detail#cacheOnly#
```

### Q6: 如何后台播放音频？

**A:** 在链接后添加 `#background#`：

```txt
hiker://page/audio#background#
```

### Q7: 如何自动翻页？

**A:** 在链接后添加 `#autoPage#`：

```txt
hiker://page/chapter#autoPage#
```

### Q8: 如何强制图片原图尺寸？

**A:** 在链接后添加 `#originalSize#`：

```txt
http://example.com/1.png#originalSize#
```

---

## 其他问题

### Q1: 如何创建沉浸式页面？

**A:** 在链接中添加 `#immersiveTheme#`：

```txt
hiker://page/detail#immersiveTheme#
```

### Q2: 如何创建阅读模式页面？

**A:** 在链接中添加 `#readTheme#`：

```txt
hiker://page/article#readTheme#
```

### Q3: 如何创建全屏页面？

**A:** 在链接中添加 `#fullTheme#`：

```txt
hiker://page/detail#fullTheme#
```

### Q4: 如何不记录浏览历史？

**A:** 在链接中添加 `#noRecordHistory#`：

```txt
hiker://page/temp#noRecordHistory#
```

### Q5: 如何不记录足迹？

**A:** 在链接中添加 `#noHistory#`：

```txt
hiker://page/detail#noHistory#
```

### Q6: 如何禁止页面刷新？

**A:** 在链接中添加 `#noRefresh#`：

```txt
hiker://page/detail#noRefresh#
```

### Q7: 如何强制识别为视频？

**A:** 在链接中添加 `#isVideo=true#`：

```txt
http://example.com/video.mp4#isVideo=true#
```

### Q8: 如何不识别为视频？

**A:** 在链接中添加 `#ignoreVideo=true#`：

```txt
http://example.com/video.mp4#ignoreVideo=true#
```

### Q9: 如何自定义长按菜单？

**A:** 在 JS 中使用 `longClick` 属性：

```js
d.push({
  title: '标题',
  extra: {
    longClick: [
      {title: '复制', js: $.toString(() => { copy(input); return 'toast://已复制' })}
    ]
  }
});
```

### Q10: 如何创建连续选集？

**A:** 在 extra 的 cls 属性添加 `playlist`：

```js
d.push({
  title: '选集1',
  url: 'video1.mp4',
  extra: {cls: 'playlist'}
});
```

### Q11: 如何获取当前规则信息？

**A:** 使用 `MY_RULE` 对象：

```js
log(MY_RULE.title);
```

### Q12: 如何获取当前页数？

**A:** 使用 `MY_PAGE` 变量：

```js
log(MY_PAGE);  // 第一页为1，第二页为2
```

### Q13: 如何获取页面类型？

**A:** 使用 `MY_TYPE` 变量：

```js
log(MY_TYPE);  // 首页将打印home，搜索为search
```

### Q14: 如何获取上级页面传递的参数？

**A:** 使用 `MY_PARAMS` 对象：

```js
log(MY_PARAMS.a);
```

### Q15: 如何获取手机 IP？

**A:** 使用 `getIP()`：

```js
let ip = getIP();
```

### Q16: 如何获取 CPU ABI？

**A:** 使用 `getCpuAbi()`：

```js
let abi = getCpuAbi();  // 返回 arm64-v8a、armeabi-v7a 等
```

### Q17: 如何获取文件绝对路径？

**A:** 使用 `getPath()`：

```js
getPath('hiker://files/a.txt');
```

### Q18: 如何获取应用版本？

**A:** 使用 `getAppVersion()`：

```js
var v = getAppVersion();
```

### Q19: 如何获取小程序数量？

**A:** 使用 `getRuleCount()`：

```js
var count = getRuleCount();
```

### Q20: 如何获取订阅记录？

**A:** 使用 `getHomeSub()`：

```js
var subscribeRecords = getHomeSub();
```

### Q21: 如何判断是否已订阅？

**A:** 使用 `hasHomeSub()`：

```js
var hasSub = hasHomeSub('http://example.com');
```

### Q22: 如何获取历史规则？

**A:** 使用 `getLastRules()`：

```js
var rules = getLastRules(12);
```

### Q23: 如何获取所有可选首页样式？

**A:** 使用 `getColTypes()`：

```js
var types = getColTypes();
```

### Q24: 如何获取我的规则订阅？

**A:** 使用 `getHomeSub()`：

```js
var subscribeRecords = getHomeSub();
var title = subscribeRecords[0].title;
var url = subscribeRecords[0].url;
```

---

## 调试技巧

### 1. 使用 setError 输出调试信息
```js
setError('变量值: ' + variable);
```

### 2. 使用 log 记录日志
```js
log({event: 'parse_start', url: MY_URL});
```

### 3. 逐步验证
```js
// 验证请求
var html = fetch(url);
setError('请求成功: ' + html.length);

// 验证解析
var list = pdfa(html, rule);
setError('解析成功: ' + list.length);
```

### 4. 使用 try-catch 定位错误
```js
try {
  // 可能出错的代码
} catch (e) {
  setError('错误: ' + e.message);
}
```

---

## 常用工具函数

### 1. 安全请求
```js
function safeFetch(url, options) {
  try {
    return fetch(url, options);
  } catch (e) {
    log('请求失败: ' + e.message);
    return null;
  }
}
```

### 2. 安全解析
```js
function safeParse(html, rule) {
  try {
    return pdfh(html, rule);
  } catch (e) {
    log('解析失败: ' + e.message);
    return '';
  }
}
```

### 3. 数据验证
```js
function validateData(data) {
  if (!data || !Array.isArray(data)) {
    setError('数据格式错误');
    return false;
  }
  return true;
}
```

### 4. URL 编码
```js
function encodeKeyword(keyword) {
  return encodeURIComponent(keyword);
}
```

### 5. HTML 转义
```js
function escapeHtml(str) {
  return str.replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#039;');
}
```
