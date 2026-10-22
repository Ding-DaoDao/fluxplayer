"""修复指定的 1.1.5 网盘合集封面，保留 B 站脚本和原有登录配置。"""

import argparse
import json
import zipfile
from pathlib import Path


def replace_once(script: str, before: str, after: str) -> str:
    if script.count(before) != 1:
        raise ValueError(f"源文件与已核对版本不一致：{before[:100]}")
    return script.replace(before, after, 1)


def patch(source: Path, destination: Path) -> None:
    with zipfile.ZipFile(source) as archive:
        files = {name: archive.read(name) for name in archive.namelist()}
    manifest = json.loads(files["manifest.json"])
    if manifest["id"] != "com.timbre.tingshu-netdisk" or manifest["version"] != "1.1.5":
        raise ValueError("仅支持已核对的 1.1.5 网盘合集")

    for name in ("pan123", "quark", "yun139", "cloud189", "aliyun"):
        filename = name + ".js"
        script = files[filename].decode("utf-8").replace("\r\n", "\n")
        headers = {
            "pan123": "{ Referer: WEB_REFERER, 'User-Agent': UA }",
            "quark": "{ Referer: REFERER, 'User-Agent': UA, Cookie: cookieOf() }",
            "yun139": "{}",
            "cloud189": "{ Referer: WEB_BASE + '/', 'User-Agent': ANDROID_UA }",
            "aliyun": "playHeaders()",
        }[name]
        # 浏览结果会原样传给封面接口，直接复用缩略图，避免再列一次书目录。
        entry = "  async function cover(params) {" if name == "aliyun" else "  async function cachedCover(params) {"
        script = replace_once(script, entry, entry + """
    // 强制刷新时忽略旧缩略图，让原有解析逻辑获取新地址。
    var listedCover = String(params.bookImage || params.cover || '');
    if (!params.refreshCover && /^https?:\\/\\//i.test(listedCover)) {
      return { url: listedCover, headers: """ + headers + """ };
    }
""")
        if name != "aliyun":
            script = script.replace("book-cover-v3:", "book-cover-v4:")
            begin = script.index("    // 封面只看目录第一页：") if name in ("quark", "cloud189") else script.index("    // 封面最多看前两页：")
            end = script.index("      var result = await loadPage", begin)
            script = script[:begin] + """    // 名称排序不能保证图片在首页；有后续页时继续找，限制耗时避免阻塞首屏。
    var started = timestampMs();
    for (var page = 1; page <= 20; page++) {
""" + script[end:]
            script = replace_once(script, "      // 页与页之间小幅错开，避免长目录连续突发请求。\n      await sleep(120);", """      if (page === 20 || timestampMs() - started >= 18000) {
        // 扫描未完成不能写入“无封面”缓存，下次允许重新解析。
        if (!picked) throw new Error('封面目录过大，请稍后重试');
        break;
      }
      // 页与页之间小幅错开，避免长目录连续突发请求。
      await sleep(120);""")
        if name == "quark":
            script = replace_once(script, "String(picked.preview_url || picked.thumbnail || '')", "String(picked.thumbnail || picked.preview_url || '')")
        elif name == "cloud189":
            # 与浏览页一致：天翼缩略图通常位于 icon 对象，兼容旧的扁平结构。
            script = replace_once(script, "String(fi.smallUrl || fi.largeUrl || '')", "String((fi.icon && (fi.icon.smallUrl || fi.icon.largeUrl)) || fi.smallUrl || fi.largeUrl || '')")
            script = replace_once(script, "          dir: !!d.isDir,", "          thumbnailUrl: String(d.thumbnailUrl || (d.icon && (d.icon.smallUrl || d.icon.largeUrl)) || ''),\n          dir: !!d.isDir,")
        elif name == "aliyun":
            # 阿里处理参数使用逗号，原包的 format/jpg 会生成不可用缩略图。
            script = replace_once(script, "image/resize,w_512/format/jpg", "image/resize,w_256/format,jpg")
            script = script.replace("cover-v2:", "cover-v3:")
            begin = script.index("    // 封面最多看前两页：")
            end = script.index("    for (var p = 0; p < coverPages; p++)", begin)
            script = script[:begin] + """    // 音频多时封面可能落在后续页，不能只检查两页。
    var started = timestampMs();
    var seenItems = {};
    var coverPages = 20;
""" + script[end:]
            script = replace_once(script, "      for (var i = 0; i < page.items.length; i++) {\n        var item = page.items[i];\n        if (item.folder || !isImage(item.name)) continue;", """      var added = 0;
      for (var i = 0; i < page.items.length; i++) {
        var item = page.items[i];
        if (seenItems[item.id]) continue;
        seenItems[item.id] = true;
        added++;
        if (item.folder || !isImage(item.name)) continue;""")
            script = replace_once(script, "|| !page.next || seen[page.next]) break;", "|| !page.next || !added || seen[page.next]) break;")
            script = replace_once(script, "      seen[page.next] = true;", """      if (p === coverPages - 1 || timestampMs() - started >= 18000) {
        if (!picked) throw new Error('封面目录过大，请稍后重试');
        break;
      }
      seen[page.next] = true;""")
            # 刷新图片时同时绕过目录缓存，不能重新返回旧签名链接。
            script = replace_once(script, "    var picked = null;\n    var marker = '';\n    var seen = {};", "    if (params.refreshCover) listCache = {};\n    var picked = null;\n    var marker = '';\n    var seen = {};")
            # 避免将带签名的图片地址写入日志。
            script = replace_once(script, "log('aliyun-cover: ' + picked.name + ' -> ' + url.substring(0, 80));", "log('aliyun-cover: 已解析图片 ' + picked.name);")
        files[filename] = script.encode("utf-8")

    # 保留版本号，以继续使用宿主针对 1.1.5 的独立封面沙箱及兼容补丁。
    manifest["description"] += " 封面修正版：复用浏览缩略图，修复阿里图片参数及天翼 icon 字段，补齐长目录查找。"
    files["manifest.json"] = json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8")
    files["cover-fix-README.md"] = (
        "# 封面修正版\n\n"
        "包 ID、版本和源 ID 保持不变，覆盖导入时保留原配置。B 站脚本不变。\n"
        "优先复用浏览结果的缩略图及对应请求头；图片失效时由宿主强制刷新回退。\n"
        "阿里缩略图参数修正为 format,jpg；天翼读取 icon.smallUrl / largeUrl。\n"
        "目录查找最多 20 页、约 18 秒预算（单次请求时间另计），未完成不缓存无图结果。\n"
        "需要在已登录的手机上验证各网盘实际图片服务；离线检查不代表已完成真机验证。\n"
    ).encode("utf-8")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in files.items():
            archive.writestr(name, data)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    patch(args.source, args.destination)
