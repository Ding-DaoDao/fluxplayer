"""修复 1.1.4 网盘源包的目录起点、书籍 ID 和配置提示，保留包 ID 及用户配置兼容性。"""

import argparse
import json
import zipfile
from pathlib import Path


def patch(source: Path, destination: Path) -> None:
    with zipfile.ZipFile(source) as archive:
        files = {name: archive.read(name) for name in archive.namelist()}
    manifest = json.loads(files["manifest.json"])
    if manifest["id"] != "com.timbre.tingshu-netdisk" or manifest["version"] != "1.1.4":
        raise ValueError("仅支持已核对的 com.timbre.tingshu-netdisk 1.1.4 源包")
    replacements = {
        "pan123.js": ("id: fileIdOf(f), name: name, type: 'book'", "id: 'D_' + fileIdOf(f), name: name, type: 'book'"),
        "quark.js": ("id: String(f.fid || ''), name: name, type: 'book'", "id: 'D_' + String(f.fid || ''), name: name, type: 'book'"),
        "yun139.js": ("id: f.fileId, name: f.fileName, type: 'book'", "id: 'D_' + f.fileId, name: f.fileName, type: 'book'"),
        "cloud189.js": ("id: f.fileId, name: f.fileName, type: 'book'", "id: 'D_' + f.fileId, name: f.fileName, type: 'book'"),
    }
    for name, (before, after) in replacements.items():
        script = files[name].decode("utf-8")
        if script.count(before) != 1:
            raise ValueError(f"{name} 与已核对的脚本不一致")
        files[name] = script.replace(before, after).encode("utf-8")
    script = files["pan123.js"].decode("utf-8")
    script = script.replace("String(picked.Thumbnail || picked.thumbnail || '')", "String(picked.Thumbnail || picked.thumbnail || picked.DownloadUrl || picked.downloadUrl || '')")
    files["pan123.js"] = script.encode("utf-8")
    for entry in manifest["sources"]:
        entry["initialDirectory"] = "-11" if entry["id"] == "cloud189" else "0"
        # 根目录路径是名称路径，不能用目录 ID 选择器覆盖。
        for fields in (entry.get("settings", []), entry.get("config", [])):
            for field in fields:
                if field["key"] == "rootDir":
                    field["hint"] = "填写网盘中的文件夹路径，如 听书/玄幻；留空浏览网盘根目录。保存后重新进入书源。"
    manifest["version"] = "1.1.5"
    files["manifest.json"] = json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8")
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
