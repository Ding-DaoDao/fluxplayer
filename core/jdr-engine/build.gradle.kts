plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(libs.versions.android.jvm.get()))
    }
}

java {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.android.jvm.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.android.jvm.get())
}

dependencies {
    implementation(libs.quickjs)
    implementation(libs.bouncycastle)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp.core)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    ignoreFailures = false
}
