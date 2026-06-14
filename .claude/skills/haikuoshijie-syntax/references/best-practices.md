# 海阔视界规则开发最佳实践

## 目录
1. [规则设计原则](#规则设计原则)
2. [性能优化](#性能优化)
3. [错误处理](#错误处理)
4. [代码组织](#代码组织)
5. [调试技巧](#调试技巧)
6. [安全注意事项](#安全注意事项)

---

## 规则设计原则

### 1. 保持简洁
- 规则应该尽可能简洁明了
- 避免过度复杂的嵌套选择器
- 使用 JS 规则处理复杂逻辑

### 2. 语义化
- 使用有意义的选择器名称
- 保持规则结构清晰
- 添加必要的注释

### 3. 可维护性
- 将复杂逻辑拆分为多个子页面
- 使用模块化设计
- 定期清理无用代码

### 4. 兼容性
- 考虑不同页面的结构差异
- 添加备选选择器
- 测试不同场景

---

## 性能优化

### 1. 减少网络请求
```js
// 不推荐：多次请求
var html1 = fetch(url1);
var html2 = fetch(url2);
var html3 = fetch(url3);

// 推荐：使用批量请求
var results = batchFetch([
  {url: url1, options: {}},
  {url: url2, options: {}},
  {url: url3, options: {}}
]);
```

### 2. 缓存数据
```js
// 不推荐：每次都请求
var data = fetch(apiUrl);

// 推荐：使用缓存
var cache = storage0.getItem('cache-key');
if (cache) {
  var data = JSON.parse(cache);
} else {
  var data = fetch(apiUrl);
  storage0.setItem('cache-key', JSON.stringify(data));
}
```

### 3. 使用预加载
```js
// 强制预加载视频
url + '#pre#'

// 禁止预加载（不需要时）
url + '#noPre#'
```

### 4. 设置超时
```js
// 设置合理的超时时间
fetch(url, {timeout: 5000})  // 5秒
```

### 5. 避免重复解析
```js
// 缓存解析结果
var cacheKey = 'parse_' + url;
var cached = storage0.getItem(cacheKey);
if (cached) {
  return JSON.parse(cached);
}
var result = parseDom(html, rule);
storage0.setItem(cacheKey, JSON.stringify(result));
return result;
```

### 6. 优化选择器
```js
// 不推荐：过度嵌套
body&&div&&div&&div&&div&&a&&href

// 推荐：直接使用 ID 或 class
body&&#list&&a&&href
```

---

## 错误处理

### 1. 网络请求错误
```js
try {
  var html = fetch(url);
  if (!html) {
    setError('请求失败');
    return;
  }
} catch (e) {
  setError('请求异常: ' + e.message);
}
```

### 2. 解析错误
```js
try {
  var json = JSON.parse(html);
} catch (e) {
  setError('JSON 解析失败');
  return;
}
```

### 3. 数据验证
```js
if (!json.list || !Array.isArray(json.list)) {
  setError('数据格式错误');
  return;
}
```

### 4. 空数据处理
```js
if (json.list.length === 0) {
  setError('暂无数据');
  return;
}
```

### 5. 元素不存在
```js
var element = pdfh(html, 'a&&title');
if (!element) {
  setError('元素不存在');
  return;
}
```

---

## 代码组织

### 1. 模块化设计
```js
// 主规则文件
$.exports.parseList = function(html) {
  // 解析列表逻辑
};

$.exports.parseDetail = function(html) {
  // 解析详情逻辑
};
```

### 2. 配置文件
```js
var CONFIG = {
  apiUrl: 'https://example.com/api',
  timeout: 5000,
  cacheTime: 3600000
};
```

### 3. 工具函数
```js
function safeFetch(url, options) {
  try {
    return fetch(url, options);
  } catch (e) {
    log('请求失败: ' + e.message);
    return null;
  }
}

function safeParse(html, rule) {
  try {
    return pdfh(html, rule);
  } catch (e) {
    log('解析失败: ' + e.message);
    return '';
  }
}
```

### 4. 常量定义
```js
var COL_TYPES = {
  MOVIE_3: 'movie_3',
  MOVIE_2: 'movie_2',
  TEXT_1: 'text_1',
  RICH_TEXT: 'rich_text'
};

var EXTRA = {
  LINE_VISIBLE: 'lineVisible',
  TEXT_ALIGN: 'textAlign'
};
```

---

## 调试技巧

### 1. 使用 setError 调试
```js
// 输出变量值
setError('变量值: ' + JSON.stringify(variable));

// 输出 HTML 片段
setError('HTML: ' + html.substring(0, 500));
```

### 2. 使用 log 记录日志
```js
log({event: 'parse_start', url: MY_URL});
log({event: 'parse_end', count: list.length});
```

### 3. 逐步验证
```js
// 第一步：验证请求
var html = fetch(url);
setError('请求成功: ' + html.length);

// 第二步：验证解析
var list = pdfa(html, rule);
setError('解析成功: ' + list.length);

// 第三步：验证数据
var first = list[0];
setError('第一条: ' + pdfh(first, 'a&&title'));
```

### 4. 使用 try-catch 定位错误
```js
try {
  // 可能出错的代码
} catch (e) {
  setError('错误位置: ' + e.stack);
}
```

### 5. 对比测试
```js
// 对比不同选择器的结果
var result1 = pdfh(html, 'a&&title');
var result2 = pdfh(html, '.title&&Text');
setError('选择器1: ' + result1 + ', 选择器2: ' + result2);
```

---

## 安全注意事项

### 1. 输入验证
```js
// 验证用户输入
var keyword = getParam('keyword', '');
if (keyword.length > 50) {
  setError('关键词过长');
  return;
}
```

### 2. URL 验证
```js
// 验证 URL 格式
if (!url.startsWith('http://') && !url.startsWith('https://')) {
  setError('URL 格式错误');
  return;
}
```

### 3. 数据过滤
```js
// 过滤 XSS 代码
function sanitize(str) {
  return str.replace(/<script>/gi, '').replace(/</g, '&lt;');
}
```

### 4. 敏感信息
```js
// 不要在日志中输出敏感信息
log('请求成功');  // OK
log('Cookie: ' + cookie);  // 不推荐
```

### 5. 权限检查
```js
// 检查是否有权限执行操作
if (!hasPermission()) {
  setError('无权限执行此操作');
  return;
}
```

---

## 常见问题解决方案

### 问题 1: 请求超时
**解决方案**:
- 增加超时时间
- 检查网络连接
- 使用缓存数据

### 问题 2: 解析结果为空
**解决方案**:
- 检查选择器是否正确
- 验证 HTML 结构
- 添加备选选择器

### 问题 3: 全局变量丢失
**解决方案**:
- 使用 `storage0` 持久化
- 检查变量名是否正确
- 避免变量名冲突

### 问题 4: 动态解析不生效
**解决方案**:
- 检查 `@lazyRule=` 语法
- 确认 `&&` 使用中文 `＆＆＆＆`
- 验证规则逻辑

### 问题 5: 视频播放失败
**解决方案**:
- 检查视频链接是否有效
- 添加必要的 Header
- 使用正确的视频格式

---

## 代码风格指南

### 1. 命名规范
```js
// 变量名：小驼峰
var movieList = [];

// 常量名：大写下划线
var MAX_COUNT = 100;

// 函数名：小驼峰
function parseMovieList() {}
```

### 2. 缩进规范
- 使用 2 个空格缩进
- 保持一致的缩进风格

### 3. 注释规范
```js
// 单行注释

/*
 * 多行注释
 * 用于说明复杂逻辑
 */

/**
 * 函数注释
 * @param {string} url - 请求地址
 * @returns {string} - HTML 内容
 */
function fetchData(url) {
  // ...
}
```

### 4. 空行规范
- 函数之间空一行
- 逻辑块之间空一行
- 文件末尾空一行

### 5. 引号规范
- 字符串使用单引号
- 包含单引号的字符串使用双引号
- 模板字符串使用反引号
