from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import json

root = Path(__file__).resolve().parent
manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
target = root / (manifest["id"] + "-" + manifest["version"] + ".jdr")
with ZipFile(target, "w", ZIP_DEFLATED) as archive:
    for name in ["manifest.json", "bilibili.js", "LICENSE", "README.md"]:
        archive.write(root / name, name)
print(target)
