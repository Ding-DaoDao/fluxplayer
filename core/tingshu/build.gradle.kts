import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask

plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.fluxplayer.app.core.tingshu"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.android.jvm.get().toInt())
        targetCompatibility = JavaVersion.toVersion(libs.versions.android.jvm.get().toInt())
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.android.jvm.get()))
        jvmDefault.set(JvmDefaultMode.DISABLE)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    api(libs.fuel.core)
    api(libs.fuel.json)
    api(libs.jsoup)
    api(libs.gson)
    api(libs.rxjava)
    api(libs.rxkotlin)
    implementation(libs.okhttp.core)
    api(project(":core:jdr-engine"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.okhttp.mockwebserver)
}

// AGP 内置 Kotlin 未被旧版格式插件识别，显式纳入听书源码与测试源码。
listOf("runKtlintCheckOverKotlinScripts", "runKtlintFormatOverKotlinScripts").forEach { name ->
    tasks.named<BaseKtLintCheckTask>(name) {
        source(fileTree("src") { include("**/*.kt") })
    }
}
