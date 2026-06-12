# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ===== 保留所有日志输出（覆盖 proguard-android-optimize.txt 的默认行为）=====
# Android 默认优化规则会自动删除 Log.d/Log.v，这里反转该行为
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
}

# ===== 保留 Kotlin 元数据（sealed class、data class、协程都需要） =====
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, Exceptions
-keep class kotlin.Metadata { *; }

# ===== 保留 Kotlin Result 类（runCatching 依赖） =====
-keep class kotlin.Result { *; }
-keep class kotlin.Result$Failure { *; }

# ===== 保留 Kotlin 协程 =====
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ===== 保留整个 OpenList 核心模块 =====
-keep class com.fluxplayer.app.core.data.openlist.** { *; }

# ===== 保留 videopicker 核心状态类 =====
-keep class com.fluxplayer.app.feature.videopicker.CommonStateSnapshot { *; }
-keep class com.fluxplayer.app.feature.videopicker.CommonStateUpdate { *; }
-keep class com.fluxplayer.app.feature.videopicker.DirectoryStackEntry { *; }
-keep class com.fluxplayer.app.feature.videopicker.BaseCloudBrowserViewModel { *; }

# ===== 保留所有 Hilt 生成的 ViewModel 及其 Sealed State =====
-keep class com.fluxplayer.app.feature.videopicker.openlist.** { *; }
-keep class com.fluxplayer.app.feature.videopicker.settings.** { *; }

# ===== OkHttp / Okio（R8 激进优化可能破坏） =====
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }