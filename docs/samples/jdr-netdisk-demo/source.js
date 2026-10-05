// This source demonstrates Flux host APIs; it never logs in to a real netdisk.
registerSource({
  id: 'flux-netdisk-demo',
  async login(p) {
    if (p.action === 'login') {
      if (!host.settings.get('username') || !host.settings.get('password')) throw Error('请输入演示账号和密码');
      host.storage.set('session', { user: host.settings.get('username') });
    }
    if (p.action === 'logout') host.storage.remove('session');
    return { authenticated: !!host.storage.get('session'), message: '这是能力演示，不连接真实网盘' };
  },
  async browse(p) {
    if (!host.storage.get('session')) throw Error('请先在书源设置中进行演示登录');
    host.cache.set('lastDirectory', p.directoryId, 3600);
    if (p.directoryId === '0') return { items: [
      { id: 'books', name: '有声书', type: 'directory' },
      { id: 'more', name: '另一听书目录', type: 'directory' },
    ] };
    return { items: [{ id: p.directoryId + '-demo', name: '演示书籍', type: 'book', directoryId: p.directoryId }] };
  },
  async search(p) {
    if (!host.storage.get('session')) throw Error('请先演示登录');
    return [{ id: 'demo', bookTitle: '演示书籍：' + p.keyword }];
  },
  async chapters(p) { return [{ chapter_id: p.bookId + '-1', title: '音频缓存测试章' }]; },
  async audio() {
    const url = host.settings.get('audioUrl');
    if (!url || !/^https?:\/\//.test(url)) throw Error('请先在书源配置中填写用于测试的 HTTP 音频地址');
    return { url, headers: {} };
  },
});
