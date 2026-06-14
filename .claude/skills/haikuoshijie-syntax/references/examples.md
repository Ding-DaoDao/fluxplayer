# 海阔视界规则示例

## 目录
1. [首页规则示例](#首页规则示例)
2. [搜索规则示例](#搜索规则示例)
3. [JS 规则示例](#js-规则示例)
4. [动态解析示例](#动态解析示例)
5. [视频规则示例](#视频规则示例)
6. [hiker:// 协议示例](#hiker-协议示例)
7. [网页桥接示例](#网页桥接示例)
8. [高级用法示例](#高级用法示例)

---

## 首页规则示例

### 示例 1: 基础电影列表
```
https://movie.example.com/list?page=fypage&class=fyclass&area=fyarea
body&&.movie-list&&li;a&&title;img&&src;.rating&&Text;a&&href
```

### 示例 2: 带排序的列表
```
https://movie.example.com/search?page=fypage&class=fyclass&sort=fysort
body&&.search-result&&.item;h3&&Text;img&&data-original;.desc&&Text;a&&href
```

### 示例 3: 第一页特殊处理
```
http://example.com/fypage.html[firstPage=http://example.com/]
body&&.list&&li;a&&title;img&&src;.info&&Text;a&&href
```

### 示例 4: 多分类替换
```
https://example.com/list?page=fypage&all=fyAll
body&&.content&&.card;.title&&Text;img&&src;.meta&&Text;.link&&href
```

### 示例 5: 带 Header 的请求
```
https://example.com/api/list?page=fypage;GET;UTF-8;{User-Agent@Windows&&Referer@https://example.com}
body&&.list-item;a&&title;img&&src;.desc&&Text;a&&href
```

---

## 搜索规则示例

### 示例 1: 基础搜索
```
https://example.com/search?q=**
.result-list&&.item;h2&&Text;a&&href;.score&&Text;.info&&Text;img&&src
```

### 示例 2: 简化搜索（仅必填项）
```
https://example.com/search?q=**
.search-result&&.movie;h2&&Text;a&&href
```

### 示例 3: 带通配符的搜索
```
https://example.com/search?keyword=**
.result&&.card;img&&alt;.title&&Text;a&&href;.desc&&Text;img&&src
```

### 示例 4: POST 搜索
```
https://example.com/search？？q=**;POST;UTF-8;{User-Agent@Windows}
body&&.result-list&&.item;h2&&Text;a&&href
```

---

## JS 规则示例

### 示例 1: 基础 JS 首页规则
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

### 示例 2: 使用 parseDom 解析
```js
var html = fetch(MY_URL);
var d = [];
var list = pdfa(html, 'body&&.item');
for (var i = 0; i < list.length; i++) {
  var title = pdfh(list[i], 'a&&title');
  var img = pdfh(list[i], 'img&&src');
  var desc = pdfh(list[i], '.desc&&Text');
  var url = pdfh(list[i], 'a&&href');
  d.push({title: title, pic_url: img, desc: desc, url: url});
}
setHomeResult(d);
```

### 示例 3: 搜索规则
```js
var html = fetch('https://example.com/search?q=' + getParam('keyword'));
var json = JSON.parse(html);
var d = [];
for (var i = 0; i < json.results.length; i++) {
  var j = json.results[i];
  d.push({
    title: j.name,
    img: j.thumbnail,
    desc: j.summary,
    content: j.detail,
    url: j.link
  });
}
setSearchResult(d);
```

### 示例 4: 带分页的请求
```js
var page = getParam('page', '1');
var html = fetch('https://example.com/list?page=' + page);
var json = JSON.parse(html);
var d = [];
for (var i = 0; i < json.data.length; i++) {
  var j = json.data[i];
  d.push({
    title: j.title,
    pic_url: j.image,
    desc: j.description,
    url: j.url
  });
}
setHomeResult(d);
```

### 示例 5: 使用 CryptoJS 解密
```js
eval(getCryptoJS());
var html = fetch(MY_URL);
var decrypted = CryptoJS.AES.decrypt(html, 'key').toString(CryptoJS.enc.Utf8);
var json = JSON.parse(decrypted);
var d = [];
for (var i = 0; i < json.list.length; i++) {
  d.push({
    title: json.list[i].title,
    pic_url: json.list[i].cover,
    desc: json.list[i].desc,
    url: json.list[i].url
  });
}
setHomeResult(d);
```

### 示例 6: 使用 XPath 解析
```js
var html = fetch(MY_URL);
var href = xpath(html, '//div[@id=root]/a[1]/@href');
var urls = xpathArray(html, '//div/a/@href');
```

### 示例 7: 使用 RC4 加解密
```js
var encoded = rc4.encode('明文', '密钥', 'UTF-8');
var decoded = rc4.decode(encoded, '密钥', 'UTF-8');
```

---

## 动态解析示例

### 示例 1: 基础动态解析
```
body&&.playlist&&li;a&&Text;*;*;a&&href.js:input+'@lazyRule=body＆＆＆＆a＆＆＆＆href'
```

### 示例 2: 带 JS 处理的动态解析
```
body&&.episode-list&&li;a&&Text;*;*;a&&href.js:input.replace('http://', 'https://')+'@lazyRule=body＆＆＆＆a＆＆＆＆href'
```

### 示例 3: 多线路动态解析
```
body&&.sources&&.source;a&&Text;*;*;a&&href.js:JSON.parse(input).url+'@lazyRule=body＆＆＆＆a＆＆＆＆href'
```

### 示例 4: 不显示 loading 的动态解析
```
a&&href.js:input+'#noLoading#@lazyRule=.js:input'
```

---

## 视频规则示例

### 示例 1: 基础视频列表
```js
var html = fetch(MY_URL);
var d = [];
var list = pdfa(html, 'body&&.video-item');
for (var i = 0; i < list.length; i++) {
  var title = pdfh(list[i], 'a&&title');
  var img = pdfh(list[i], 'img&&src');
  var desc = pdfh(list[i], '.duration&&Text');
  var url = pdfh(list[i], 'a&&href');
  d.push({
    title: title,
    pic_url: img,
    desc: desc,
    url: url,
    extra: {id: url}
  });
}
setHomeResult(d);
```

### 示例 2: 多线路视频
```js
var html = fetch(MY_URL);
var json = JSON.parse(html);
var d = [];
for (var i = 0; i < json.videos.length; i++) {
  var j = json.videos[i];
  d.push({
    title: j.title,
    pic_url: j.thumbnail,
    desc: j.duration,
    url: JSON.stringify({
      urls: j.sources.map(s => s.url),
      names: j.sources.map(s => s.name)
    }),
    extra: {id: j.id}
  });
}
setHomeResult(d);
```

### 示例 3: 带字幕的视频
```js
var html = fetch(MY_URL);
var json = JSON.parse(html);
var d = [];
for (var i = 0; i < json.episodes.length; i++) {
  var j = json.episodes[i];
  d.push({
    title: j.title,
    pic_url: j.thumbnail,
    desc: j.episode,
    url: JSON.stringify({
      urls: [j.videoUrl],
      subtitle: j.subtitleUrl
    }),
    extra: {id: j.id}
  });
}
setHomeResult(d);
```

### 示例 4: 带弹幕的视频
```js
var html = fetch(MY_URL);
var json = JSON.parse(html);
var d = [];
for (var i = 0; i < json.videos.length; i++) {
  var j = json.videos[i];
  d.push({
    title: j.title,
    pic_url: j.cover,
    desc: j.info,
    url: JSON.stringify({
      urls: [j.url],
      danmu: j.danmuUrl
    }),
    extra: {id: j.id}
  });
}
setHomeResult(d);
```

---

## hiker:// 协议示例

### 首页路由
```
hiker://home
hiker://home@V电影
hiker://home@规则1||规则2||http://example.com
```

### 搜索路由
```
hiker://search
hiker://search?s=测试
hiker://search?s=测试&group=②影搜
hiker://search?s=测试&rule=海阔视界
hiker://search?s=测试&rule=海阔视界&simple=false
```

### 功能路由
```
hiker://bookmark
hiker://history
hiker://history?rule=xxx
hiker://collection
hiker://collection?group=收藏分组名
hiker://download
hiker://setting
hiker://debug
```

### 弹窗路由
```
toast://加载失败，请换源
copy://要复制的内容
input://{"value":"默认","js":"'toast://'+input","hint":"提示"}
confirm://提示信息.js:'toast://确认'
select://{"title":"选择","options":["选项一","选项二"],"js":"'toast://'+input"}
```

### 特殊路由
```
web://http://example.com
x5://http://example.com
x5Play://http://example.com/1.mp4
video://https://example.com/xxx.html
download://http://example.com/1.mp4
share://hiker://files/a.txt
pics://https://a.com/1.jpg&&https://a.com/2.jpg
```

### 空路由
```
hiker://empty#http://a.com@rule=js:fetch(MY_URL.split('#')[1],{})
```

---

## 网页桥接示例

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
fy_bridge_app.setWebTitle('标题')

// 设置状态栏颜色
fy_bridge_app.setAppBarColor('#ffffff')
```

### 请求相关
```js
// 同步请求
request(url, {headers:{},body:'',method:'POST'})

// 异步请求
requestAsync(url, param, key, callback)

// 解析动态解析规则
fy_bridge_app.parseLazyRule('http://x.com@lazyRule=.js:input')

// 获取带 header 的视频地址
fba.getHeaderUrl('http://x.com/111.m3u8')
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

// 打开新页面
fy_bridge_app.newPage('标题', 'http://example.com')

// 跳转二级详情页
fba.open(JSON.stringify({rule: "规则名", title: "标题", url: "链接"}))
```

---

## 高级用法示例

### 示例 1: 自定义长按菜单
```js
var html = fetch(MY_URL);
var d = [];
var list = pdfa(html, 'body&&.item');
for (var i = 0; i < list.length; i++) {
  var title = pdfh(list[i], 'a&&title');
  var img = pdfh(list[i], 'img&&src');
  var url = pdfh(list[i], 'a&&href');
  d.push({
    title: title,
    pic_url: img,
    url: url,
    extra: {
      longClick: [
        {title: '复制链接', js: $.toString(() => { copy(input); return 'toast://已复制' })},
        {title: '在浏览器打开', js: $.toString(() => { return 'web://' + input })}
      ]
    }
  });
}
setHomeResult(d);
```

### 示例 2: 连续选集
```js
var html = fetch(MY_URL);
var d = [];
var list = pdfa(html, 'body&&.episode');
for (var i = 0; i < list.length; i++) {
  var title = pdfh(list[i], 'a&&Text');
  var url = pdfh(list[i], 'a&&href');
  d.push({
    title: title,
    url: url,
    col_type: 'text_5',
    extra: {cls: 'playlist'}
  });
}
setHomeResult(d);
```

### 示例 3: 多线路选集
```js
var html = fetch(MY_URL);
var json = JSON.parse(html);
var d = [];
for (var i = 0; i < json.episodes.length; i++) {
  var j = json.episodes[i];
  for (var k = 0; k < j.sources.length; k++) {
    d.push({
      title: j.title + ' - ' + j.sources[k].name,
      url: j.sources[k].url,
      col_type: 'text_5',
      extra: {cls: 'playlist r' + k}
    });
  }
}
setHomeResult(d);
```

### 示例 4: 输入框搜索
```js
var d = [];
d.push({
  title: '搜索',
  col_type: 'input',
  url: $.toString(() => {
    return 'hiker://search?s=' + encodeURIComponent(input);
  })
});
setHomeResult(d);
```

### 示例 5: 分类标签
```js
var d = [];
var categories = ['全部', '电影', '电视剧', '动漫', '综艺'];
for (var i = 0; i < categories.length; i++) {
  d.push({
    title: categories[i],
    url: 'hiker://page/list?class=' + encodeURIComponent(categories[i]),
    col_type: 'flex_button'
  });
}
setHomeResult(d);
```

### 示例 6: 富文本内容
```js
var d = [];
d.push({
  title: '<font color="red">红色</font> <font color="orange">橙色</font> 黑色',
  col_type: 'rich_text',
  url: 'toast://点击了'
});
setHomeResult(d);
```

### 示例 7: 带预加载的视频
```js
var html = fetch(MY_URL);
var d = [];
var list = pdfa(html, 'body&&.video');
for (var i = 0; i < list.length; i++) {
  var title = pdfh(list[i], 'a&&title');
  var img = pdfh(list[i], 'img&&src');
  var url = pdfh(list[i], 'a&&href');
  d.push({
    title: title,
    pic_url: img,
    url: url + '#pre#',
    extra: {id: url}
  });
}
setHomeResult(d);
```

### 示例 8: 沉浸式页面
```js
var d = [];
d.push({
  title: '进入沉浸模式',
  url: 'hiker://page/detail#immersiveTheme#'
});
setHomeResult(d);
```

### 示例 9: 阅读模式
```js
var d = [];
d.push({
  title: '阅读文章',
  url: 'hiker://page/article#readTheme#'
});
setHomeResult(d);
```

### 示例 10: 不记录历史
```js
var d = [];
d.push({
  title: '临时页面',
  url: 'hiker://page/temp#noRecordHistory#'
});
setHomeResult(d);
```

### 示例 11: 深层嵌套规则
```js
d.push({url:$('https://example.com').rule(() => $('https://example2.com').rule(() => setError(input)))})
```

### 示例 12: 动态界面操作
```js
// 更新元素
updateItem('test_id1', {url:'xxx', title:'新标题', extra: {id: 'test_id1'}})

// 删除元素
deleteItem('test_id2')
deleteItem(['id1', 'id2'])
deleteItemByCls('box123')

// 新增元素
addItemAfter('test_id1', {url:'xxx', extra: {id: 'test_id2'}})
addItemBefore('test_id1', {url:'xxx', extra: {id: 'test_id2'}})

// 查询元素
let obj = findItem('test_id1')
let arr = findItemsByCls('test_cls')
```

### 示例 13: 定时任务
```js
registerTask('id', 10000, $.toString((obj) => { log('执行了') }, obj))
unRegisterTask('id')
```

### 示例 14: 代理服务器
```js
let url = startProxyServer($.toString(() => { return MY_PARAMS.a })) + '?a=b'
```

### 示例 15: M3U8 处理
```js
// 缓存 m3u8
cacheM3u8('http://xx.m3u8')

// 修正 m3u8 路径
fixM3u8('http://yy/xx.m3u8', '#EXT-X-KEY:xxx.key\nxxx.ts')

// 移除广告片段
clearM3u8Ad('http://1.com/1.m3u8')

// 批量缓存
batchCacheM3u8([{url:'http://www.a.cn', options:{}}])
```

### 示例 16: 远程模块
```js
// 远程模块引用
require('http://xxx/t.js?v=1')

// 临时缓存
requireCache('http://xxx/t.js', 24)

// 删除缓存
deleteCache('http://xxx/t.js')
```

### 示例 17: 云剪贴板
```js
// 获取可用云剪贴板
var pastes = getPastes()

// 分享到云剪贴板
var url = sharePaste(content, paste)

// 解析云剪贴板
var content = parsePaste(url)
```

### 示例 18: 私有化存储
```js
// 存储
setItem('key', 'value')

// 读取
var value = getItem('key', 'defaultValue')

// 公开存储
setPublicItem('key', 'value')
var value = getPublicItem('key', 'defaultValue')
```

### 示例 19: 私有文件操作
```js
// 写入（自动加密）
saveFile('fileName.txt', 'content')

// 读取
var content = readFile('fileName.txt')

// 删除
deleteFile('fileName.txt')

// 检测存在
var exists = fileExist('fileName.txt')
```

### 示例 20: 精准搜索
```js
var mode = getSearchMode()  // 0 默认, 1 精准
setSearchMode(1)
if (searchContains(text, key, false)) {
  // 精准匹配
}
```

---

## 常见错误和解决方案

### 错误 1: 链接格式错误
**问题**: 链接中的 `?` 没有正确处理
**解决**: 使用中文 `？？` 代替英文 `?`
```
错误: http://example.com?a=b
正确: http://example.com？？a=b
```

### 错误 2: 分号冲突
**问题**: Header 中的分号与规则分隔符冲突
**解决**: 使用中文 `；；` 代替英文 `;`
```
错误: {Cookie@a;b}
正确: {Cookie@a；；b}
```

### 错误 3: && 冲突
**问题**: 动态解析中的 `&&` 与规则语法冲突
**解决**: 使用中文 `＆＆＆＆` 代替 `&&`
```
错误: @lazyRule=body&&a&&href
正确: @lazyRule=body＆＆＆＆a＆＆＆＆href
```

### 错误 4: 全局变量失效
**问题**: 全局变量重启后丢失
**解决**: 使用 `storage0` 对象持久化
```
putVar('key', 'value')  // 重启失效
storage0.putVar('key', 'value')  // 持久化
```

### 错误 5: 动态解析嵌套
**问题**: 动态解析规则嵌套使用
**解决**: 动态解析只能用在最后一级
```
错误: @lazyRule=xxx@lazyRule=yyy
正确: 仅使用一个 @lazyRule
```

---

## 性能优化建议

1. **使用 JS 规则**: 比传统规则更灵活，性能更好
2. **减少请求次数**: 合并多个请求为一个
3. **缓存数据**: 使用 `storage0` 缓存常用数据
4. **预加载**: 使用 `#pre#` 标识预加载视频
5. **避免重复解析**: 缓存解析结果
6. **使用批量请求**: `batchFetch()` 批量发起请求
7. **设置超时**: 避免长时间等待
8. **错误处理**: 添加 try-catch 处理异常
