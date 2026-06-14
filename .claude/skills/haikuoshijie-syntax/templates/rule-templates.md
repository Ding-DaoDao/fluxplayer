# 海阔视界规则模板

## 目录
1. [首页规则模板](#首页规则模板)
2. [搜索规则模板](#搜索规则模板)
3. [JS 规则模板](#js-规则模板)
4. [动态解析模板](#动态解析模板)
5. [视频规则模板](#视频规则模板)

---

## 首页规则模板

### 模板 1: 基础首页规则
```txt
https://example.com/list?page=fypage&class=fyclass&area=fyarea
body&&.list-item;a&&title;img&&src;.desc&&Text;a&&href
```

### 模板 2: 带排序的首页规则
```txt
https://example.com/list?page=fypage&class=fyclass&sort=fysort
body&&.item;h3&&Text;img&&data-original;.info&&Text;a&&href
```

### 模板 3: 带 Header 的首页规则
```txt
https://example.com/api/list?page=fypage;GET;UTF-8;{User-Agent@Windows&&Referer@https://example.com}
body&&.card;.title&&Text;img&&src;.meta&&Text;.link&&href
```

### 模板 4: 第一页特殊处理
```txt
http://example.com/fypage.html[firstPage=http://example.com/]
body&&.content&&li;a&&title;img&&src;.desc&&Text;a&&href
```

---

## 搜索规则模板

### 模板 1: 基础搜索规则
```txt
https://example.com/search?q=**
.result-list&&.item;h2&&Text;a&&href;.score&&Text;.info&&Text;img&&src
```

### 模板 2: 简化搜索规则
```txt
https://example.com/search?q=**
.search-result&&.movie;h2&&Text;a&&href
```

### 模板 3: POST 搜索规则
```txt
https://example.com/search？？q=**;POST;UTF-8;{User-Agent@Windows}
body&&.result-list&&.item;h2&&Text;a&&href
```

---

## JS 规则模板

### 模板 1: 基础 JS 首页规则
```js
var html = fetch(MY_URL);
var d = [];
var list = pdfa(html, 'body&&.item');
for (var i = 0; i < list.length; i++) {
  var title = pdfh(list[i], 'a&&title');
  var img = pdfh(list[i], 'img&&src');
  var desc = pdfh(list[i], '.desc&&Text');
  var url = pdfh(list[i], 'a&&href');
  d.push({
    title: title,
    pic_url: img,
    desc: desc,
    url: url
  });
}
setHomeResult(d);
```

### 模板 2: API 请求规则
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

### 模板 3: 带分页的规则
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

### 模板 4: 搜索规则
```js
var keyword = getParam('keyword', '');
var html = fetch('https://example.com/search?q=' + encodeURIComponent(keyword));
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

---

## 动态解析模板

### 模板 1: 基础动态解析
```txt
body&&.playlist&&li;a&&Text;*;*;a&&href.js:input+'@lazyRule=body＆＆＆＆a＆＆＆＆href'
```

### 模板 2: 带 JS 处理的动态解析
```txt
body&&.episode-list&&li;a&&Text;*;*;a&&href.js:input.replace('http://', 'https://')+'@lazyRule=body＆＆＆＆a＆＆＆＆href'
```

### 模板 3: 多线路动态解析
```txt
body&&.sources&&.source;a&&Text;*;*;a&&href.js:JSON.parse(input).url+'@lazyRule=body＆＆＆＆a＆＆＆＆href'
```

---

## 视频规则模板

### 模板 1: 基础视频列表
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

### 模板 2: 多线路视频
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

### 模板 3: 带字幕的视频
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

### 模板 4: 带弹幕的视频
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

### 模板 5: 连续选集
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

### 模板 6: 多线路选集
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

---

## 使用说明

### 如何使用模板

1. 复制模板代码
2. 替换示例 URL 为实际 URL
3. 根据实际页面结构调整选择器
4. 测试规则是否正常工作
5. 根据需要添加额外功能

### 模板选择指南

| 需求 | 推荐模板 |
|------|----------|
| 简单列表展示 | 首页规则模板 1 |
| 需要分类筛选 | 首页规则模板 2 |
| 需要自定义 Header | 首页规则模板 3 |
| 第一页特殊处理 | 首页规则模板 4 |
| 基础搜索 | 搜索规则模板 1 |
| 复杂数据处理 | JS 规则模板 |
| 动态内容加载 | 动态解析模板 |
| 视频播放 | 视频规则模板 |

### 注意事项

1. 模板仅供参考，需要根据实际情况调整
2. 选择器需要根据实际页面结构修改
3. URL 参数需要根据实际 API 调整
4. 测试时注意检查错误信息
5. 复杂需求可以组合使用多个模板
