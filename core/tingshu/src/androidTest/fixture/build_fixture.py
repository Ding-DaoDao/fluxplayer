import argparse
import os
from pathlib import Path
import shutil
import subprocess
import zipfile


# 重建测试专用的独立 DEX 书源，避免测试依赖宿主中预先加载的类。
parser = argparse.ArgumentParser()
parser.add_argument("--sdk", type=Path, required=True)
parser.add_argument("--stdlib", type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[5]
api = root / "core/tingshu/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar"
work = root / "core/tingshu/build/fixture"
work.mkdir(parents=True, exist_ok=True)
source = Path(__file__).with_name("SourceEntry.java")
subprocess.run([
    "javac", "-encoding", "UTF-8", "--release", "8", "-classpath",
    os.pathsep.join([str(api), str(args.stdlib)]), "-d", str(work), str(source),
], check=True)
classes = work / "fixture-classes.jar"
with zipfile.ZipFile(classes, "w") as archive:
    for item in work.rglob("*.class"):
        archive.write(item, item.relative_to(work).as_posix())
build_tools = max(args.sdk.joinpath("build-tools").iterdir(), key=lambda item: tuple(int(part) for part in item.name.split(".")))
platform = max(args.sdk.joinpath("platforms").glob("android-*"), key=lambda item: tuple(int(part) for part in item.name.removeprefix("android-").split("-", 1)[0].split(".")))
output = root / "core/tingshu/src/androidTest/assets/test_source.jar"
subprocess.run([
    "java", "-cp", str(build_tools / "lib/d8.jar"), "com.android.tools.r8.D8",
    "--min-api", "23", "--output", str(output), "--lib", str(platform / "android.jar"),
    "--classpath", str(api), "--classpath", str(args.stdlib), str(classes),
], check=True)
destination = root / "feature/tingshu/src/androidTest/assets/test_source.jar"
destination.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(output, destination)
