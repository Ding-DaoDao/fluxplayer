gradlew assembleDebug > build.log 2>&1
type build.log | findstr /c:"FAILED" /c:"error:"
