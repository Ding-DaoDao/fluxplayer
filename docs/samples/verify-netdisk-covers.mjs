import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
import vm from 'node:vm';

const directory = resolve(process.argv[2]);
let checks = 0;
function load(name) {
  const cache = new Map();
  const context = vm.createContext({
    host: {
      cache: { get: (key, fallback) => cache.get(key) ?? fallback, set: (key, value) => cache.set(key, value) },
      settings: { get: () => 'test-cookie', all: () => ({}) },
    },
    timestampMs: () => Date.now(),
    timestamp: () => Math.floor(Date.now() / 1000),
    sha256Hex: value => createHash('sha256').update(value).digest('hex'),
    base64Encode: value => Buffer.from(value).toString('base64'),
    base64Decode: value => [...Buffer.from(value, 'base64')],
    base64ToUtf8: value => Buffer.from(value, 'base64').toString(),
    utf8ToBytes: value => [...Buffer.from(value)],
    bytesToUtf8: value => Buffer.from(value).toString(),
    urlEncode: encodeURIComponent,
    urlDecode: decodeURIComponent,
    log() {},
    sleep: async () => {},
    registerSource: source => { context.source = source; },
  });
  let script = readFileSync(resolve(directory, name + '.js'), 'utf8');
  new vm.Script(script, { filename: name + '.js' });
  const insertion = script.lastIndexOf('  registerSource(');
  assert.ok(insertion >= 0);
  const hooks = name === 'aliyun'
    ? `globalThis.test = { listPage, encodeId,
        api: fn => { api = fn; },
        pages: fn => { listPage = fn; },
        download: fn => { downloadUrl = fn; }
      };`
    : `globalThis.test = { chooseCover, listFiles,
        pages: fn => { listFiles = fn; },
        audio: fn => { audio = fn; },
        ${name === 'quark' ? '' : 'login: fn => { ensureLogin = fn; },'}
        ${name === 'cloud189' ? 'api: fn => { openApiGet = fn; },' : ''}
      };`;
  script = script.slice(0, insertion) + hooks + script.slice(insertion);
  vm.runInContext(script, context);
  return { ...context, cache };
}

for (const name of ['pan123', 'quark', 'yun139', 'cloud189', 'aliyun']) {
  const context = load(name);
  const params = { bookId: 'D_book', bookImage: 'https://images.example/cover.jpg', config: {} };
  // 缩略图直通不允许发起目录或下载请求；缺少网络桥时误调用会立即失败。
  assert.equal((await context.source.cover(params)).url, params.bookImage);
  checks++;
  let listed = 0;
  if (name === 'aliyun') {
    params.bookId = 'D_' + context.test.encodeId('drive', 'book');
    context.test.pages(async () => { listed++; return { items: [], next: '' }; });
  } else {
    if (context.test.login) context.test.login(async () => {});
    context.test.pages(async () => {
      listed++;
      return name === 'pan123' ? [] : { list: [], files: [], items: [], total: 0, next: null };
    });
  }
  assert.equal(await context.source.cover({ ...params, refreshCover: true }), null);
  assert.equal(listed, 1);
  checks++;

  if (name !== 'aliyun') {
    let pages = 0;
    const image = { file: 'cover.jpg' };
    const picked = await context.test.chooseCover(async page => {
      pages++;
      return { rows: [{ id: String(page), name: page === 3 ? 'cover.jpg' : '001.mp3', raw: image }], next: page + 1 };
    });
    assert.equal(pages, 3);
    assert.equal(picked, image);
    checks++;
    pages = 0;
    await assert.rejects(context.test.chooseCover(async page => {
      pages++;
      return { rows: [{ id: String(page), name: '001.mp3', raw: {} }], next: page + 1 };
    }), /封面目录过大/);
    assert.equal(pages, 20);
    checks++;
    pages = 0;
    await context.test.chooseCover(async () => {
      pages++;
      return { rows: [{ id: 'same', name: '001.mp3' }], next: 'same' };
    });
    assert.equal(pages, 2);
    checks++;
  }
}

{
  const context = load('aliyun');
  let body;
  context.test.api(async (path, value) => {
    body = value;
    return { items: [{ file_id: 'image', name: 'cover.jpg', thumbnail: 'https://images.example/thumb.jpg' }] };
  });
  const page = await context.test.listPage('drive', 'book', '', {});
  assert.equal(body.image_thumbnail_process, 'image/resize,w_256/format,jpg');
  assert.equal(page.items[0].thumb, 'https://images.example/thumb.jpg');
  checks++;
  let pages = 0;
  context.test.pages(async () => {
    pages++;
    return { items: [{ id: String(pages), name: pages === 3 ? 'cover.jpg' : '001.mp3', thumb: 'https://images.example/third.jpg' }], next: String(pages) };
  });
  const params = { bookId: 'D_' + context.test.encodeId('drive', 'book'), config: {} };
  assert.equal((await context.source.cover(params)).url, 'https://images.example/third.jpg');
  assert.equal(pages, 3);
  pages = 0;
  checks++;
  context.test.download(async () => 'https://images.example/fresh.jpg');
  assert.equal((await context.source.cover({ ...params, refreshCover: true })).url, 'https://images.example/fresh.jpg');
  checks++;
}

{
  const context = load('cloud189');
  context.test.api(async () => ({ fileListAO: { fileList: [{ id: 'image', name: 'cover.jpg', icon: { smallUrl: 'https://images.example/small.jpg' } }] } }));
  assert.equal((await context.test.listFiles('book', 1, 100, 1, {})).items[0].thumbnailUrl, 'https://images.example/small.jpg');
  context.test.api(async () => ({ data: [{ id: 'image', name: 'cover.jpg', thumbnailUrl: 'https://images.example/fallback.jpg' }] }));
  assert.equal((await context.test.listFiles('book', 1, 100, 1, {})).items[0].thumbnailUrl, 'https://images.example/fallback.jpg');
  checks += 2;
}

console.log(`通过 ${checks} 组封面回归检查；所有网盘脚本语法检查通过。`);
