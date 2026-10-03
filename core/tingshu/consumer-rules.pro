# 外置书源通过固定名称调用宿主接口及第三方依赖，发布构建必须保留接口。
-keep class com.github.eprendre.tingshu.** { *; }
-keep class com.github.kittinunf.** { *; }
-keep class org.jsoup.** { *; }
-keep class com.google.gson.** { *; }
-keep class io.reactivex.** { *; }
-keep class kotlin.** { *; }
-dontwarn org.slf4j.**
