# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Debug APK (arm64-v8a only — ABI splits exclude x86/armeabi by default)
./gradlew assembleDebug

# Release with debug signing (for testing release builds locally)
./gradlew assembleRelease-with-debug-signing

# Compile a single module
./gradlew :core:data:compileDebugKotlin
./gradlew :feature:videopicker:compileDebugKotlin

# Lint & format
./gradlew ktlintCheck        # check all modules
./gradlew ktlintFormat       # auto-fix

# Run a single test class
./gradlew :core:domain:testDebugUnitTest --tests "com.fluxplayer.app.core.domain.GetSortedVideosUseCaseTest"
```

ABI splits are enabled for APK builds but disabled automatically for bundle tasks. Output: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`.

## Toolchain

- JDK 17+
- Android SDK 37 (compileSdk) / 23 (minSdk) / 36 (targetSdk)
- Gradle 9.4+ (wrapper included)
- Kotlin 2.3.20, Compose BOM 2026.03.01, Material3 1.5.0-alpha17

## Architecture

Clean Architecture + MVVM, 12 Gradle modules:

```
:app → :feature:* → :core:domain → :core:data → :core:database
                                → :core:datastore
                                → :core:model (pure Kotlin, no Android deps)
                    :core:ui → :core:model
                    :core:media → :core:database, :core:model
```

| Module | Role |
|--------|------|
| `:app` | Entry point, Hilt wiring, NavHost with mediaNavGraph + settingsNavGraph |
| `:core:model` | Pure Kotlin data models + enums (Video, Folder, ApplicationPreferences, ComposeEngine, etc.) |
| `:core:ui` | Shared Compose components, theme system (dual-engine: Material3 + Miuix) |
| `:core:data` | Repositories, cloud API clients (Aliyun/Quark/C189/Yun139/Pan123/WebDAV/OpenList), danmaku fetchers |
| `:core:domain` | Use cases (GetSortedVideosUseCase, etc.) |
| `:core:database` | Room DB, DAOs, schema exports at `core/database/schemas/` |
| `:core:datastore` | DataStore preferences with kotlinx-serialization |
| `:core:media` | MediaService, MediaSynchronizer |
| `:feature:player` | Video player (hybrid Compose + ViewBinding), danmaku overlay, Media3 session |
| `:feature:videopicker` | Media browser, cloud drive tabs, search, history |
| `:feature:settings` | All settings screens |

## Dual-Engine Theme System

The app supports two Compose theme engines, switched via `ApplicationPreferences.composeEngine`:

- **Material 3** (`ComposeEngine.MATERIAL`): `MaterialExpressiveTheme` + `MotionScheme.expressive()`
- **Miuix** (`ComposeEngine.MIUIX`): `MiuixTheme` + `ThemeController`

Theme entry point: `NextPlayerTheme` in `core/ui/.../theme/Theme.kt`. Both engines map to a unified `FluxColorScheme` + `FluxTypography` via `LocalFluxColorScheme` / `LocalFluxTypography` CompositionLocals. Components use `FluxTheme.engine` to branch between engines.

Shared components in `core/ui/.../components/FluxComponents.kt` provide engine-agnostic wrappers: `FluxText`, `FluxIcon`, `FluxSwitch`, `FluxCheckbox`, `FluxRadioButton`, `FluxButton`, `FluxIconButton`, `FluxCard`, `FluxSlider`, `FluxCircularProgressIndicator`, `FluxLinearProgressIndicator`.

Settings scaffold: `FluxSettingsScaffold` in `core/ui/.../components/FluxSettingsScaffold.kt` auto-switches between `MiuixScaffold` + `MiuixSmallTopAppBar` and Material3 `Scaffold` + `NextTopAppBar`.

## DI & Codegen

- **Hilt** with **KSP** (not KAPT). Every Hilt module: `libs.hilt.android` + `ksp(libs.hilt.compiler)` + `ksp(libs.kotlin.metadata.jvm)`.
- **Room** schema exports at `core/database/schemas/`. Commit generated JSON files when changing `@Entity`/`@Dao`.
- **Kotlinx Serialization** for DataStore preferences and navigation routes.

## Navigation

Jetpack Navigation Compose with type-safe serializable routes. Two top-level nav graphs:
- `MediaNavGraph`: `MediaRootRoute` → media picker, search. Video playback launches `PlayerActivity` via Intent.
- `SettingsNavGraph`: 15+ settings screens.

Spring-based slide + scale + fade transitions between destinations.

## Key Libraries

| Library | Purpose |
|---------|---------|
| AndroidX Media3 1.10.0 | Video playback (ExoPlayer), HLS/DASH/RTSP |
| DanmakuFlameMaster 0.9.25 | Bilibili danmaku rendering engine |
| Miuix 0.9.1 | MIUI-style UI components |
| Haze 1.7.2 | Blur/frosted glass effects |
| Backdrop 2.0.0 | Liquid glass effects (vibrancy, blur, lens) |
| Coil 3.4.0 | Image loading |
| OkHttp 4.12.0 | HTTP client for cloud APIs |
| Room 2.8.4 | Local database |

## Quirks

- **Legacy names**: Forked from Next Player. Some names still reference "NextPlayer" (e.g., `NextPlayerApplication`, `NextPlayerTheme`, `NextIcons`). This is Flux Player code.
- **openlist-binary**: If `openlist-binary/libopenlist.so` exists, `:app` copies it to jniLibs automatically. Not checked in.
- **ProGuard**: Release builds strip logs by default; custom rules in `app/proguard-rules.pro` preserve all `Log.*` calls.
- **Tests**: `ignoreFailures = true` on all test tasks — test failures don't block builds.
- **Player module**: Uses hybrid Compose + ViewBinding (not pure Compose).
- **`:core:model`**: Pure Kotlin module (`kotlinJvm` plugin, NOT `androidLibrary`). No Android dependencies.

## Code Style

- `ktlint_code_style = android_studio`
- Trailing commas allowed
- `ktlint_standard_property-naming = disabled`
- `@Composable` functions exempt from function-naming rule
- All dependency versions in `gradle/libs.versions.toml` — never hardcode in module build files
