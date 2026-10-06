# 外置书源通过固定名称调用宿主接口及第三方依赖，发布构建必须保留接口。
-keep class com.github.eprendre.tingshu.** { *; }
# 返回值和参数类型也必须保留，外置书源的方法签名不会随宿主混淆更新。
-keep,includedescriptorclasses class com.github.kittinunf.** { *; }
-keep class org.jsoup.** { *; }
-keep class com.google.gson.** { *; }
-keep class io.reactivex.** { *; }
-keep class kotlin.** { *; }
-dontwarn org.slf4j.**
