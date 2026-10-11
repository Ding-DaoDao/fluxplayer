// 基于 abts 的 B 站接口、听书筛选和 WBI 签名移植，授权信息见 LICENSE。
(function () {
  'use strict';
  var API = 'https://api.bilibili.com';
  var HOME = 'https://www.bilibili.com';
  var UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36';
  var MIX = [46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52];
  var AUDIO = ['有声小说','有声书','有声剧','有声读物','有声','听书','评书','相声','广播剧','演播','朗读','诵读','长书','连播','说书','单口','播讲','多人剧','小说剧'];
  var NOISE = ['一口气看完','漫推','漫画','解说','讲解','解读','动画','鬼畜','混剪','沙雕','速看','reaction','预告','游戏实况','配音秀'];
  var CATEGORIES = ['热门推荐','广播剧','评书','相声','悬疑','玄幻仙侠','名著悦读'];
  var QUERIES = ['有声小说','广播剧','评书','相声','悬疑 有声小说','玄幻 仙侠 有声小说','名著 有声读物'];
  var keys = null;
  var keyTime = 0;
  var lastRequest = 0;
  var cookies = {};
  var fingerprint = null;

  function clean(value) {
    return String(value || '').replace(/<[^>]*>/g, '').replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&nbsp;/g, ' ');
  }
  function contains(words, text) { return words.some(function (word) { return text.indexOf(word) >= 0; }); }
  function seconds(value) {
    if (typeof value === 'number') return value;
    var parts = String(value || '').split(':');
    return parts.reduce(function (total, part) { return total * 60 + (Number(part) || 0); }, 0);
  }
  function prepare(params) {
    var configured = String((params.config || {}).cookie || '').trim();
    if (configured !== fingerprint) {
      fingerprint = configured;
      cookies = {};
      keys = null;
      String(storage.get('anonymous-cookie', '') || '').split(';').forEach(mergeCookie);
      configured.split(';').forEach(mergeCookie);
    }
  }
  function mergeCookie(part) {
    var match = String(part).trim().match(/^([^=;\s]+)=([^;]*)/);
    if (match) cookies[match[1]] = match[2];
  }
  function headers(extra) {
    var result = { 'User-Agent': UA, Referer: HOME + '/' };
    var cookie = Object.keys(cookies).map(function (name) { return name + '=' + cookies[name]; }).join('; ');
    if (cookie) result.Cookie = cookie;
    Object.keys(extra || {}).forEach(function (name) { result[name] = extra[name]; });
    return result;
  }
  async function raw(url, extra) {
    var wait = 300 - (Date.now() - lastRequest);
    if (wait > 0) await sleep(wait);
    lastRequest = Date.now();
    var res = await http.get(url, { headers: headers(extra), timeoutMs: 10000 });
    Object.keys(res.headers || {}).forEach(function (name) {
      if (name.toLowerCase() !== 'set-cookie') return;
      var values = String(res.headers[name]).split(/,(?=\s*[^=;,\s]+=[^;]*)/);
      values.forEach(mergeCookie);
    });
    // 仅持久化匿名设备 Cookie；登录凭据由宿主的配置存储管理。
    var anonymous = ['buvid3','buvid4','b_nut','b_lsid'].filter(function (name) { return cookies[name]; }).map(function (name) { return name + '=' + cookies[name]; }).join('; ');
    storage.set('anonymous-cookie', anonymous);
    if (res.status < 200 || res.status >= 300) throw new Error('B站请求失败：HTTP ' + res.status);
    return res;
  }
  function keyName(url) { return String(url).split('/').pop().split('.')[0]; }
  async function wbi() {
    if (keys && Date.now() - keyTime < 6 * 60 * 60 * 1000) return keys;
    if (!cookies.buvid3) {
      try { await raw(HOME + '/'); } catch (_) { /* 首页预热失败仍尝试接口。 */ }
    }
    var nav = JSON.parse((await raw(API + '/x/web-interface/nav')).body);
    var image = nav.data && nav.data.wbi_img;
    if (!image) throw new Error('无法获取 B站 WBI 密钥，请检查网络');
    var original = keyName(image.img_url) + keyName(image.sub_url);
    if (original.length !== 64) throw new Error('B站 WBI 密钥格式异常');
    keys = MIX.slice(0, 32).map(function (index) { return original[index]; }).join('');
    keyTime = Date.now();
    return keys;
  }
  function query(params) {
    return Object.keys(params).sort().map(function (name) { return encodeURIComponent(name) + '=' + encodeURIComponent(String(params[name]).replace(/[!'()*]/g, '')); }).join('&');
  }
  async function request(path, params, signed, extra) {
    for (var attempt = 0; attempt < 2; attempt++) {
      var values = Object.assign({}, params);
      if (signed) {
        var mixin = await wbi();
        values.wts = Math.floor(Date.now() / 1000);
        values.w_rid = md5Hex(query(values) + mixin);
      }
      var body = JSON.parse((await raw(API + path + '?' + query(values), extra)).body);
      if ((body.code === -412 || body.code === -352 || (body.data && body.data.v_voucher)) && attempt === 0) {
        keys = null;
        await sleep(600);
        continue;
      }
      if (body.code !== 0 || (body.data && body.data.v_voucher)) throw new Error('B站接口受限或内容不可用（' + body.code + '）：' + (body.message || '请稍后重试，或配置自己的 Cookie'));
      return body.data;
    }
  }
  function isAudiobook(item, keyword) {
    var title = clean(item.title);
    var meta = clean(item.description) + ' ' + clean(item.tag);
    var duration = seconds(item.duration);
    if (contains(NOISE, (title + ' ' + meta).toLowerCase())) return false;
    return (contains(AUDIO, title) && duration >= 300) || (Number(item.typeid) === 195 && duration >= 600) || (contains(AUDIO, meta) && duration >= 1800) || (contains(AUDIO, keyword) && duration >= 1800);
  }
  async function searchRaw(params, keyword, order) {
    var data = await request('/x/web-interface/wbi/search/type', { search_type: 'video', keyword: keyword, page: Math.max(1, Number(params.page) || 1), page_size: 30, platform: 'pc', web_location: 1430654, order: order || '' }, true, { Origin: 'https://search.bilibili.com', Referer: 'https://search.bilibili.com/video?keyword=' + encodeURIComponent(keyword) });
    var filter = String((params.config || {}).filter) !== 'false';
    var books = (data.result || []).filter(function (item) { return item.bvid && (!filter || isAudiobook(item, keyword)); }).map(function (item) {
      return { id: String(item.bvid), bookTitle: clean(item.title), bookAnchor: clean(item.author), bookImage: String(item.pic || '').replace(/^\/\//, 'https://'), bookDesc: clean(item.description), count: 0 };
    });
    books.totalPages = Math.max(Number(params.page) || 1, Number(data.numPages || Math.ceil(Number(data.numResults || 0) / 30)) || 1);
    books.nextPage = books.totalPages > (Number(params.page) || 1) ? (Number(params.page) || 1) + 1 : null;
    return books;
  }
  async function search(params) {
    prepare(params);
    var keyword = String(params.keyword || '').trim();
    if (!keyword) return { items: [], totalPages: 1 };
    if (Number(params.page || 1) > 1 && /BV[0-9A-Za-z]{10}/.test(keyword)) return { items: [], totalPages: Number(params.page) };
    var direct = keyword.match(/BV[0-9A-Za-z]{10}/);
    if (direct) {
      var item = await request('/x/web-interface/wbi/view', { bvid: direct[0] }, true);
      return { items: [{ id: item.bvid, bookTitle: item.title, bookAnchor: (item.owner || {}).name || '', bookImage: item.pic || '', bookDesc: item.desc || '', count: (item.pages || []).length }], totalPages: 1 };
    }
    var primary = await searchRaw(params, keyword);
    if (contains(AUDIO, keyword)) return { items: primary, totalPages: primary.totalPages };
    try {
      var extra = await searchRaw(params, keyword + ' 有声小说');
      primary.totalPages = Math.max(primary.totalPages, extra.totalPages);
      extra.forEach(function (item) { if (!primary.some(function (old) { return old.id === item.id; })) primary.push(item); });
    } catch (_) { /* 补充搜索失败时保留主搜索结果。 */ }
    return { items: primary, totalPages: primary.totalPages };
  }
  async function browse(params) {
    prepare(params);
    var directory = String(params.directoryId || '0');
    var index = directory === '0' ? 0 : Number(directory.replace('category:', ''));
    if ((directory !== '0' && !/^category:\d+$/.test(directory)) || index < 0 || index >= QUERIES.length) throw new Error('分类不存在');
    var books = await searchRaw(params, QUERIES[index], index === 0 ? 'click' : '');
    // 筛选后数量不代表分页结束，使用接口提供的总页数。
    return { items: books.map(function (book) { return Object.assign({}, book, { name: book.bookTitle, type: 'book' }); }), nextPage: books.nextPage, shortcuts: CATEGORIES.map(function (name, index) { return { id: index === 0 ? '0' : 'category:' + index, name: name }; }) };
  }
  async function chapters(params) {
    prepare(params);
    var bvid = String(params.bookId || '');
    var pages = await request('/x/player/pagelist', { bvid: bvid }, false);
    return (pages || []).map(function (page, index) { return { chapter_id: String(page.cid), title: clean(page.part || ('第' + (index + 1) + '章')), order: index + 1, duration: Number(page.duration) || 0, bvid: bvid }; });
  }
  async function audio(params) {
    prepare(params);
    var bvid = String(params.bvid || params.bookId || '');
    var data = await request('/x/player/wbi/playurl', { bvid: bvid, cid: params.chapterId, qn: 80, fnval: 4048, fnver: 0, fourk: 1, try_look: 1, gaia_source: 'prefer-ua' }, true);
    if (Number(data.code || 0) !== 0) throw new Error('该章节不可播放');
    var tracks = data.dash && data.dash.audio;
    // 只取独立音轨，不把视频地址当成音频，也不绕过登录或付费限制。
    if (!tracks || !tracks.length) throw new Error('该分 P 没有可用音频轨道，可能需要登录或无播放权限');
    tracks = tracks.slice().sort(function (a, b) {
      var aScore = /mp4a/.test(a.codecs || '') ? 1 : 0;
      var bScore = /mp4a/.test(b.codecs || '') ? 1 : 0;
      return bScore - aScore || Number(b.bandwidth || 0) - Number(a.bandwidth || 0);
    });
    var track = tracks[0];
    var candidates = [track.baseUrl || track.base_url].concat(track.backupUrl || track.backup_url || []).filter(Boolean);
    var line = String((params.config || {}).line || '自动');
    var index = ['主线路', '备用线路1', '备用线路2'].indexOf(line);
    // 个别主线路不可达，自动模式优先使用普通备用 CDN，再回退到主线路。
    var automatic = candidates.slice(1).concat(candidates.slice(0, 1));
    var url = index >= 0 ? (candidates[index] || candidates[0]) : (automatic.filter(function (address) { return !/\.mcdn\.bilivideo\.(cn|com)([:/])/i.test(address); })[0] || candidates[0]);
    if (!url || !/^https?:\/\//.test(url)) throw new Error('B站未返回有效音频地址');
    // 登录 Cookie 仅发给 B站接口，CDN 音频请求只携带防盗链头。
    return { url: url, headers: { Referer: HOME + '/video/' + bvid, 'User-Agent': UA } };
  }
  async function userInfo() {
    var response = JSON.parse((await raw(API + '/x/web-interface/nav')).body);
    if (response.code !== 0 && response.code !== -101) throw new Error('无法检查登录状态：' + response.code);
    return response.data || {};
  }
  function loginResult(nav, message) {
    return { authenticated: !!nav.isLogin, message: message || (nav.isLogin ? ('已登录：' + (nav.uname || 'B站用户') + '。搜索和音频解析已使用此账号；首页为热门听书榜单，不同步 B站推荐、收藏和观看记录。') : (cookies.SESSDATA ? '保存的登录凭据已失效，请重新登录。公开内容仍可收听。' : '公开内容可免登录。登录用于以自己的账号搜索和解析有权限的音频；首页榜单不会因登录改变。')), webUrl: HOME + '/', cookieUrl: HOME + '/', desktopUserAgent: true, state: {} };
  }
  async function login(params) {
    prepare(params);
    if (params.action === 'logout') { host.settings.set('cookie', ''); fingerprint = null; cookies = {}; return { authenticated: false, message: '已退出登录' }; }
    if (params.action === 'webComplete') {
      var received = String(params.cookies || params.cookie || '').trim();
      if (received) {
        // 先向 B站确认登录成功，再保存凭据，避免未完成的网页登录覆盖已有账号。
        fingerprint = null;
        prepare({ config: { cookie: received } });
        try {
          var candidate = await userInfo();
          if (candidate.isLogin) {
            host.settings.set('cookie', received);
            return loginResult(candidate);
          }
        } catch (error) {
          fingerprint = null;
          prepare(params);
          throw error;
        }
        fingerprint = null;
        prepare(params);
      }
      return loginResult(await userInfo(), '未确认到新的 B站登录，请在网页完成登录后返回。原有配置已保留。');
    }
    return loginResult(await userInfo());
  }
  registerSource({ id: 'bilibili', search: search, chapters: chapters, audio: audio, browse: browse, login: login });
})();
