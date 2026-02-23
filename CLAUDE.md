# Termux+ (v2.0.0) - AI-Enhanced Terminal for Android

## Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build (minified with ProGuard)
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Run Android instrumentation tests
./gradlew connectedAndroidTest

# Clean build
./gradlew clean

# Build specific module
./gradlew :app:assembleDebug
./gradlew :terminal-emulator:assembleDebug
```

## Architecture

Two-module Gradle project with native C++ code:

```
termux-ai-app/
├── app/                        # Main Android application module
│   └── src/main/java/com/termux/
│       ├── ai/                 # AI integration (Claude/Gemini clients, context engine, encryption)
│       ├── app/                # Activities, fragments, dialogs, UI helpers
│       ├── plus/               # Application class, plugin system, feature toggles
│       ├── plus/api/           # Plugin & AI provider interfaces
│       ├── terminal/           # Terminal session management, JNI bridge
│       ├── receivers/          # Broadcast receivers
│       └── view/               # Custom terminal view
├── terminal-emulator/          # Android library module - native PTY emulation
│   └── src/main/cpp/           # C++17 JNI code (termux_pty.cpp, pty_helper.cpp)
├── tools/                      # CI tooling (review_agent.py)
├── docs/                       # Architecture docs, setup guides, wiki pages
└── issues/                     # 30 roadmap/issue markdown files
```

**Plugin system:** `TermuxPlugin` interface -> `AIProvider` extends it -> `ClaudePlugin`, `AutoSavePlugin` are concrete implementations. Managed by `PluginManager` in `plus/plugin/`.

**Feature toggles:** `PlusFeatureManager` controls runtime features (AI, voice input, gestures, tabs, biometric, dynamic colors, etc.) via SharedPreferences.

## Key Files

| File | Purpose |
|------|---------|
| `app/.../plus/TermuxPlusApplication.java` | Application class - initializes plugins, encryption, crash handler, Material You |
| `app/.../app/TabbedTerminalActivity.java` | Main launcher activity - ViewPager2 tabs, FABs, gesture/voice handling |
| `app/.../ai/AIClient.java` | Unified AI client — Claude via Anthropic Messages API (`x-api-key` auth), Gemini via Google API |
| `app/.../ai/ContextEngine.java` | Project/environment detection for context-aware AI |
| `app/.../ai/EncryptedPreferencesManager.java` | AES-256-GCM encrypted credential storage (falls back to standard SharedPreferences on failure) |
| `app/.../plus/plugin/PluginManager.java` | Plugin registration and lifecycle |
| `app/.../plus/PlusFeatureManager.java` | Feature toggle management |
| `app/.../terminal/TerminalSession.java` | Shell session with PTY via JNI |
| `terminal-emulator/src/main/cpp/termux_pty.cpp` | Native C++ PTY creation and subprocess management |
| `app/src/main/AndroidManifest.xml` | 8 activities, 7 permissions, scoped intent filters |
| `app/proguard-rules.pro` | ProGuard/R8 rules for release builds |

## Environment Setup

- **Android Studio** Ladybug or newer
- **JDK 17** (used in CI, required for AGP 8.7.0)
- **Android SDK 34** (compileSdk and targetSdk)
- **Android NDK** (any recent version — exact version pinning removed from app/build.gradle; terminal-emulator still pins 27.3.13750724)
- **CMake 3.22.1+** (builds native code in terminal-emulator module)
- Min SDK: 24 (Android 7.0)

## Code Conventions

- **Java** with some **Kotlin** (Kotlin 1.9.24) - most source is Java
- Java source/target compatibility: **1.8**
- Kotlin JVM target: **1.8**
- Native code: **C++17** with `-Wall -Wextra -Werror -fvisibility=hidden`
- View binding enabled (not data binding)
- BuildConfig fields for API configuration (`ANTHROPIC_API_BASE`, `ANTHROPIC_API_VERSION`, `AI_VERSION`, `AI_ENABLED`, `AI_DEBUG`)
- Package namespace: `com.termux.ai` (app), `com.termux.terminal.emulator` (library)

## Testing

- **1 unit test:** `PluginManagerTest.java` - tests plugin registration/lifecycle
- Framework: JUnit 4 + Mockito
- Instrumentation runner configured but no instrumentation tests written yet
- Run with: `./gradlew test` (unit) or `./gradlew connectedAndroidTest` (instrumented)

## CI/CD

- `.github/workflows/android-build.yml` - Main build pipeline (JDK 17, Gradle assembleDebug, APK artifact upload)
- Runs `tools/review_agent.py` (Python) for automated code review
- Triggers on push to main/master/develop and PRs to main/master
- APK artifacts retained for 7 days

## AI Integration

- **Claude:** Uses Anthropic Messages API at `https://api.anthropic.com/v1/messages` with `x-api-key` header auth. API version: `2023-06-01`. No OAuth — user provides API key from console.anthropic.com
- **Gemini:** Uses Google Generative Language API (was already correct, unchanged)
- **Encrypted storage:** API keys stored in `"termux_ai_credentials"` encrypted SharedPreferences (separate from legacy `"termux_ai_prefs"`)
- **No WebSocket/streaming** — all AI calls are synchronous HTTP request/response via OkHttp
- **Removed deps:** AppAuth (OAuth), Retrofit (unused), WorkManager (no tasks defined), androidx.browser

## Gotchas

- **NDK is required** - The terminal-emulator module has native C++ code. Build fails without NDK installed
- **Cannot build on Android device** - NDK cross-compilation toolchain doesn't run in Termux itself
- **ABI filters include all 4 architectures** (arm64-v8a, armeabi-v7a, x86_64, x86) - builds take longer
- **ndkVersion commented out in app/build.gradle** - Lets AGP pick installed NDK; terminal-emulator still pins 27.3.13750724
- **Encrypted prefs fall back gracefully** - If AndroidX security-crypto fails (KeyStore corruption, StrongBox issues), falls back to standard SharedPreferences with a warning log
- **security-crypto is stable 1.0.0** (not alpha) - Avoids alpha-library instability on production devices
- **allowBackup="false"** - Terminal history and AI credentials are excluded from cloud backup
- **Exported activities have scoped MIME types** - TermuxViewActivity accepts `text/*`, `application/x-sh`, `application/json`; TermuxSendActivity accepts `text/*` only
- **No RECORD_AUDIO permission** - Removed until voice input is actually implemented
- **No BOOT_COMPLETED receiver** - Removed (was empty placeholder)
- **FileProvider is scoped** - Only exposes `shared/`, `scripts/`, `ai_models/`, and `share_cache/` (not entire internal storage)
- **ProGuard rules are targeted** - Only keeps manifest components, Gson reflection, OkHttp API, Room entities (R8 actually obfuscates now)
- **Max 8 tabs** enforced in TabbedTerminalActivity
- **gradle.properties** contains feature flags (`gboard`, `tabs`, `gestures`), Anthropic endpoint/version, and JVM memory at 2048m
- **Legacy packaging** enabled for JNI libs (`useLegacyPackaging = true`)
- **Room DB** uses annotationProcessor (not kapt) - mixing Java annotation processing with Kotlin
