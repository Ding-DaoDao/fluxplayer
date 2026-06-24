# 123云盘播放修复脚本
# 用法：在PowerShell中运行 .\build_fix.ps1

Write-Host "=== 清理build目录 ===" -ForegroundColor Green

# 强制删除被锁定的build目录
$dirs = @(
    "E:\Projects\fluxplayer\core\common\build",
    "E:\Projects\fluxplayer\core\data\build",
    "E:\Projects\fluxplayer\feature\player\build",
    "E:\Projects\fluxplayer\app\build"
)

foreach ($dir in $dirs) {
    if (Test-Path $dir) {
        Write-Host "删除: $dir"
        Remove-Item -Path $dir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Write-Host "`n=== 编译Debug APK ===" -ForegroundColor Green
cd E:\Projects\fluxplayer
& ".\gradlew.bat" assembleDebug --no-daemon

if ($LASTEXITCODE -eq 0) {
    Write-Host "`n✅ 编译成功！" -ForegroundColor Green
    Write-Host "APK路径: E:\Projects\fluxplayer\app\build\outputs\apk\debug\app-arm64-v8a-debug.apk"
} else {
    Write-Host "`n❌ 编译失败，请检查错误信息" -ForegroundColor Red
}
