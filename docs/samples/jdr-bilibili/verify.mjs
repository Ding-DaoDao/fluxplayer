import vm from 'node:vm';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';

const script = await readFile(new URL('./bilibili.js', import.meta.url), 'utf8');
const manifest = JSON.parse(await readFile(new URL('./manifest.json', import.meta.url), 'utf8'));
const calls = [];
const state = new Map();
const settings = {};
let source;
let rejectOnce = false;
let missingAudio = false;
const live = process.argv.includes('--live');
const sample = process.argv.find(arg => /^BV[0-9A-Za-z]{10}$/.test(arg));
const fixture = { bvid: 'BV1xx411c7mD', aid: 1, title: '<em>测试</em>有声小说', author: '测试演播', pic: '//image.example/cover.jpg', description: '有声小说', duration: '30:00', typeid: 195 };
const context = vm.createContext({
  Date, console,
  registerSource(value) { source = value; },
  md5Hex(value) { return createHash('md5').update(value).digest('hex'); },
  sleep: async millis => { if (live) await new Promise(resolve => setTimeout(resolve, millis)); },
  storage: { get: (key, fallback) => state.get(key) ?? fallback, set: (key, value) => state.set(key, value) },
  host: { settings: { set: (key, value) => { settings[key] = value; } } },
  http: { get: async (address, options) => {
    const url = new URL(address);
    calls.push({ url, options });
    if (live) {
      const response = await fetch(url, { headers: options.headers, signal: AbortSignal.timeout(options.timeoutMs) });
      console.log('接口检查：', url.pathname, 'HTTP', response.status);
      return { status: response.status, headers: Object.fromEntries(response.headers), body: await response.text() };
    }
    let data;
    if (url.pathname === '/') return { status: 200, headers: { 'set-cookie': 'buvid3=anonymous; Path=/; HttpOnly' }, body: '' };
    if (url.pathname.endsWith('/nav')) return { status: 200, headers: {}, body: JSON.stringify({ code: (options.headers.Cookie || '').includes('SESSDATA=own') ? 0 : -101, data: { isLogin: (options.headers.Cookie || '').includes('SESSDATA=own'), uname: '测试账号', wbi_img: { img_url: 'https://i.example/7cd084941338484aae1ad9425b84077c.png', sub_url: 'https://i.example/4932caff0ff746eab6f01bf08b70ac45.png' } } }) };
    if (url.pathname.endsWith('/search/type')) {
      assert.match(url.searchParams.get('w_rid'), /^[0-9a-f]{32}$/);
      assert.ok(url.searchParams.get('wts'));
      assert.equal(options.headers.Origin, 'https://search.bilibili.com');
      if (rejectOnce) { rejectOnce = false; return { status: 200, headers: {}, body: JSON.stringify({ code: -412, message: '请求受限' }) }; }
      data = { numPages: 3, result: [fixture, { ...fixture, bvid: 'BV2xx411c7mD', title: '漫画解说' }, { ...fixture, bvid: 'BV3xx411c7mD', title: '有声小说预告', duration: '00:30' }] };
    } else if (url.pathname.endsWith('/view')) {
      data = { ...fixture, title: '测试有声小说', owner: { name: '测试演播' }, pages: [{ cid: 10, part: '第一章', duration: 1800 }, { cid: 20, part: '第二章', duration: 1800 }] };
    } else if (url.pathname.endsWith('/pagelist')) {
      data = [{ cid: 10, part: '第一章', duration: 1800 }, { cid: 20, part: '第二章', duration: 1800 }];
    } else if (url.pathname.endsWith('/playurl')) {
      assert.equal(url.searchParams.get('cid'), '20');
      data = { dash: { audio: missingAudio ? [] : [{ baseUrl: 'https://node.mcdn.bilivideo.cn/audio.m4s', backup_url: ['https://cdn.example/audio.m4s'], codecs: 'mp4a.40.2', bandwidth: 192000 }, { baseUrl: 'https://cdn.example/flac.m4s', codecs: 'fLaC', bandwidth: 1000000 }] } };
    } else throw new Error('未预期的请求：' + url.pathname);
    return { status: 200, headers: {}, body: JSON.stringify({ code: 0, data }) };
  } },
});
vm.runInContext(script, context, { filename: 'bilibili.js' });
assert.equal(source.id, manifest.sources[0].id);
if (live) {
  const home = await source.browse({ directoryId: '0', config: {} });
  assert.ok(home.items.length);
  assert.ok(home.items.every(item => item.type === 'book'));
  console.log('首页直接加载书籍：', home.items.length, '本，分类入口：', home.shortcuts.length);
  const searchPage = await source.search({ keyword: '有声小说', config: {} });
  const books = searchPage.items;
  console.log('真实搜索结果数量：', books.length);
  const id = sample || books[0]?.id;
  console.log('验证视频：', id);
  if (sample) {
    const direct = await source.search({ keyword: 'https://www.bilibili.com/video/' + sample, config: {} });
    assert.equal(direct.items[0].id, sample);
    console.log('BV链接直达验证通过');
  }
  assert.ok(id, '没有可验证的视频，请指定一个公开听书 BV 号');
  const chapters = await source.chapters({ bookId: id, config: {} });
  assert.ok(chapters.length);
  console.log('目录解析成功：', chapters.length, '章');
  let played = false;
  for (const line of ['自动', '备用线路1', '备用线路2']) {
    const audio = await source.audio({ bookId: id, chapterId: chapters[0].chapter_id, config: { line } });
    console.log('音频线路：', line, new URL(audio.url).hostname);
    try {
      const response = await fetch(audio.url, { headers: { ...audio.headers, Range: 'bytes=0-1023' }, signal: AbortSignal.timeout(8000) });
      assert.ok(response.ok, '音频请求失败：' + response.status);
      await response.body.cancel();
      console.log('真实目录与音频验证通过：', id, chapters.length, '章，', line, 'HTTP', response.status);
      played = true;
      break;
    } catch (error) { console.log('线路不可达：', line, error.name); }
  }
  assert.ok(played, '三条音频线路均未通过当前电脑的网络检查');
} else {
  const root = await source.browse({ directoryId: '0', config: {} });
  assert.equal(root.shortcuts.length, 7);
  assert.equal(root.items.length, 1);
  assert.equal(root.items[0].type, 'book');
  assert.equal(root.nextPage, 2);
  const searchPage = await source.search({ keyword: '有声小说', config: {} });
  const books = searchPage.items;
  assert.equal(books.length, 1);
  assert.equal(books[0].bookTitle, '测试有声小说');
  assert.equal(books[0].bookImage, 'https://image.example/cover.jpg');
  const direct = await source.search({ keyword: 'https://www.bilibili.com/video/' + fixture.bvid, config: {} });
  assert.equal(direct.items[0].count, 2);
  const chapters = await source.chapters({ bookId: books[0].id, config: {} });
  assert.equal(chapters[1].chapter_id, '20');
  assert.equal(chapters[1].bvid, books[0].id);
  const audio = await source.audio({ bookId: books[0].id, chapterId: chapters[1].chapter_id, config: { cookie: 'SESSDATA=private' } });
  assert.equal(audio.url, 'https://cdn.example/audio.m4s');
  assert.equal(audio.headers.Cookie, undefined);
  assert.ok(calls.at(-1).options.headers.Cookie.includes('SESSDATA=private'));
  const main = await source.audio({ bookId: books[0].id, chapterId: '20', config: { line: '主线路' } });
  assert.equal(main.url, 'https://node.mcdn.bilivideo.cn/audio.m4s');
  missingAudio = true;
  await assert.rejects(source.audio({ bookId: books[0].id, chapterId: '20', config: {} }), /没有可用音频/);
  missingAudio = false;
  rejectOnce = true;
  assert.equal((await source.search({ keyword: '有声小说', config: {} })).items.length, 1);
  const unfiltered = await source.search({ keyword: '有声小说', config: { filter: 'false' } });
  assert.equal(unfiltered.items.length, 3);
  assert.equal(searchPage.totalPages, 3);
  const second = await source.search({ keyword: '有声小说', page: 2, config: {} });
  assert.equal(second.totalPages, 3);
  assert.equal(calls.at(-1).url.searchParams.get('page'), '2');
  const category = await source.browse({ directoryId: 'category:0', page: 2, config: {} });
  assert.equal(category.nextPage, 3);
  const login = await source.login({ action: 'webComplete', cookies: 'SESSDATA=own', config: {} });
  assert.equal(settings.cookie, 'SESSDATA=own');
  assert.equal(login.authenticated, true);
  assert.match(login.message, /测试账号/);
  await source.search({ keyword: '有声小说', config: { ...settings } });
  assert.ok(calls.at(-1).options.headers.Cookie.includes('SESSDATA=own'));
  vm.runInContext(script, context, { filename: 'bilibili.js' });
  const restored = await source.login({ action: 'status', config: { ...settings } });
  assert.equal(restored.authenticated, true);
  const incomplete = await source.login({ action: 'webComplete', cookies: 'buvid3=anonymous', config: { ...settings } });
  assert.equal(settings.cookie, 'SESSDATA=own');
  assert.equal(incomplete.authenticated, true);
  const expired = await source.login({ action: 'status', config: { cookie: 'SESSDATA=expired' } });
  assert.equal(expired.authenticated, false);
  assert.match(expired.message, /失效/);
  await source.login({ action: 'logout', config: {} });
  assert.equal(settings.cookie, '');
  console.log('通过：分类、搜索筛选、封面、BV直达、分P标识、音轨兼容、Cookie隔离、无音轨错误、受限重试、取消筛选、首页书籍、分页、登录校验、凭据恢复、未完成登录保护、失效提示和退出登录。');
}
