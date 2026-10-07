# AGENTS.md

## Build

```bash
# Debug APK (arm64-v8a only — ABI splits exclude x86/armeabi by default)
./gradlew assembleDebug

# Release with debug signing (useful for testing release builds locally)
./gradlew assembleRelease-with-debug-signing

# Build a single module (e.g. compile check)
./gradlew :core:data:compileDebugKotlin
```

ABI splits are enabled for APK builds but disabled automatically when building a bundle (`*bundle*` task name detection). The output APK path is `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`.

## Lint & Format

ktlint is applied to all subprojects via `build.gradle.kts` with `ignoreFailures = false`. Run:

```bash
./gradlew ktlintCheck        # check all modules
./gradlew ktlintFormat       # auto-fix
```

`.editorconfig` settings:
- `ktlint_code_style = android_studio`
- Trailing commas allowed
- `ktlint_standard_property-naming = disabled`
- `@Composable` functions exempt from function-naming rule

## Architecture

12 Gradle modules defined in `settings.gradle.kts`:

| Module | Purpose |
|---|---|
| `:app` | Application entry, DI wiring, navigation graphs |
| `:core:common` | Shared utilities |
| `:core:data` | Repositories, network clients |
| `:core:database` | Room persistence (schema exports in `core/database/schemas/`) |
| `:core:datastore` | DataStore preferences |
| `:core:domain` | Use cases |
| `:core:media` | Media processing |
| `:core:model` | Data models |
| `:core:ui` | Shared Compose components & resources |
| `:feature:player` | Video player + danmaku overlay |
| `:feature:settings` | Settings screens |
| `:feature:videopicker` | File browser & cloud drive picker |

Key dependency flow: `:app` → `:feature:*` → `:core:data` / `:core:domain` → `:core:database` / `:core:datastore` / `:core:model`.

## DI & Codegen

- **Hilt** for DI, **KSP** (not KAPT) for annotation processing. Every module using Hilt depends on `libs.hilt.android` + `ksp(libs.hilt.compiler)` + `ksp(libs.kotlin.metadata.jvm)`.
- **Room** schema exports live at `core/database/schemas/`. When changing `@Entity` or `@Dao`, Room generates JSON schema files there — commit them.
- **Kotlinx Serialization** plugin is applied in `:app`.

## Quirks

- **openlist-binary**: If `openlist-binary/libopenlist.so` exists at the repo root, the `:app` build script copies it into `src/main/jniLibs/{armeabi-v7a,arm64-v8a,x86,x86_64}/` automatically. This file is not checked in.
- **Legacy names**: The codebase forked from Next Player. Some class/resource names still reference "NextPlayer" (e.g. `NextPlayerApplication`, `@style/Theme.NextPlayer.Splash`). Don't be confused — these are Flux Player code.
- **ProGuard**: Release builds enable minify + shrinkResources. The custom proguard rule in `app/proguard-rules.pro` preserves all `Log.*` calls (overrides the default Android optimize rules that strip `Log.d`/`Log.v`).
- **Tests**: 测试失败默认阻止构建；仅在明确需要时使用 `-PignoreTestFailures=true` 临时忽略失败。
- **local.properties**: Contains `sdk.dir` pointing to the local Android SDK. Not checked in — each developer has their own.

## Version Catalog

All dependency versions are centralized in `gradle/libs.versions.toml`. Reference libraries as `libs.xxx.yyy` in build scripts. Never hardcode version strings in module `build.gradle.kts` files.

## Tech Stack

Kotlin · Jetpack Compose + Material3 · AndroidX Media3 (ExoPlayer) · Hilt · Room + DataStore · OkHttp4 · Coil · DanmakuFlameMaster (Bilibili danmaku engine) · Fastlane for release automation.

## Toolchain Requirements

- JDK 17+
- Android SDK 36 (compileSdk) / 23 (minSdk) / 36 (targetSdk)
- Gradle 9.1+ (wrapper included)
