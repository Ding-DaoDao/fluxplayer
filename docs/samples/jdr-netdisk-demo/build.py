from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

root = Path(__file__).resolve().parent
with ZipFile(root.parent / 'jdr-netdisk-demo.jdr', 'w', ZIP_DEFLATED) as archive:
    for name in ['manifest.json', 'source.js']:
        archive.write(root / name, name)
