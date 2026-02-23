# Termux AI — Configuration Fixes
## What was broken and what's fixed

---

### FILE: AIClient.java (COMPLETE REWRITE)

**Problem 1: Wrong API endpoint**
- Old: `https://claude.ai/api` (consumer web app — returns HTML, not JSON)
- Fixed: `https://api.anthropic.com/v1/messages` (actual Messages API)

**Problem 2: Fake OAuth flow**
- Old: `authenticate()` method sent OAuth authorization_code to `claude.ai/api/oauth/token`
  which doesn't exist. AppAuth library integration, RedirectUriReceiverActivity, bearer
  tokens — all dead code for an endpoint that was never real.
- Fixed: Replaced with simple API key authentication via `x-api-key` header.
  Added `setClaudeApiKey()`, `setGeminiApiKey()`, `validateClaudeApiKey()` methods.
  User gets their key from https://console.anthropic.com/settings/keys

**Problem 3: Custom REST endpoints that don't exist**
- Old: `sendClaudeRequest("/analyze", ...)`, `sendClaudeRequest("/generate", ...)`
  These hit `claude.ai/api/analyze` and `claude.ai/api/generate` — fabricated endpoints.
- Fixed: All requests go through the standard Messages API (`/v1/messages`).
  System prompts instruct Claude to return structured JSON. Response parsing extracts
  text from the `content[].text` blocks per the real API response format.

**Problem 4: WebSocket to non-existent endpoint**
- Old: `wss://claude.ai/api/ws` for "real-time sessions"
- Fixed: Removed WebSocket entirely. The Anthropic API is request/response HTTP.
  (Streaming is available via SSE but that's a future enhancement, not WebSocket.)

**Problem 5: Response parsing assumed wrong format**
- Old: Expected `response.get("suggestion")` directly from API response
- Fixed: Claude Messages API returns `{ "content": [{ "type": "text", "text": "..." }] }`.
  Added `extractClaudeText()` and `extractClaudeJson()` helpers that parse the real
  response format and strip markdown code fences.

**What's preserved:** All Gemini integration code is unchanged (it was already correct).
All callback interfaces, PrivacyGuard integration, and the dual-provider architecture
remain intact.

---

### FILE: EncryptedPreferencesManager.java

**Problem: No graceful fallback**
- Old: Threw `RuntimeException` if encryption setup failed. On devices where the alpha
  security-crypto library has issues (KeyStore corruption, StrongBox failures), the app
  would crash on launch.
- Fixed: Falls back to standard SharedPreferences with a warning log. Not ideal, but
  better than a crash. The API key is still sandboxed by Android (other apps can't read it).

**Problem: Migration source == destination**
- Old: AIClient called `migratePlaintextToEncrypted("termux_ai_prefs", "termux_ai_prefs")`.
  Same name for source and destination = reads from and writes to the same file = no-op.
- Fixed: Added guard check that logs a warning and returns false if names match.
  New AIClient uses `"termux_ai_credentials"` as the encrypted prefs name, so migration
  from the old `"termux_ai_prefs"` actually works now.

---

### FILE: build.gradle

| Change | Old | New | Why |
|--------|-----|-----|-----|
| API endpoint buildConfigField | `https://claude.ai/api` | `https://api.anthropic.com/v1` | Wrong endpoint |
| AppAuth dependency | `net.openid:appauth:0.11.1` | Removed | Anthropic doesn't use OAuth |
| Browser dependency | `androidx.browser:browser:1.6.0` | Removed | Only needed for AppAuth |
| WorkManager | `androidx.work:work-runtime:2.8.1` | Removed | No WorkRequests defined; was churning empty |
| Retrofit | `retrofit2:retrofit:2.9.0` + converter | Removed | AIClient uses OkHttp directly |
| security-crypto | `1.1.0-alpha06` | `1.0.0` (stable) | Alpha library on prod = asking for trouble |
| OAuth manifest placeholder | `appAuthRedirectScheme` | Removed | No OAuth |
| ndkVersion | `"27.3.13750724"` exact | Commented out | Breaks builds without exact NDK revision |
| release debuggable | Not explicitly set | `debuggable false` | Ensures release builds aren't debuggable |

---

### FILE: gradle.properties

- Changed `termux.ai.claude.endpoint` → `termux.ai.anthropic.endpoint=https://api.anthropic.com/v1`
- Added `termux.ai.anthropic.version=2023-06-01`
- Changed `termux.ai.debug=true` → `false` (debug should be build-variant, not global)

---

### FILE: AndroidManifest.xml

**Problem: Wildcard MIME types on exported activities**
- Old: `<data android:mimeType="*/*"/>` on both TermuxViewActivity and TermuxSendActivity.
  Any app on the device could send any data type to these activities.
- Fixed: TermuxViewActivity accepts `text/*`, `application/x-sh`, `application/json`.
  TermuxSendActivity accepts `text/*` only.

**Problem: RECORD_AUDIO permission with no implementation**
- Removed. Add back when voice input is actually built.

**Problem: allowBackup="true" with database includes**
- Changed to `allowBackup="false"`. Terminal history and AI context shouldn't sync to cloud.

**Problem: requestLegacyExternalStorage**
- Removed. Not needed (or effective) when targeting SDK 34.

**Problem: Empty BOOT_COMPLETED receiver**
- Removed. It did nothing.

---

### FILE: proguard-rules.pro

**Problem: Blanket keep rules defeated R8 entirely**
- Old: `-keep class com.termux.ai.** { *; }` + `-keep class com.termux.terminal.** { *; }`
  + `-keep class com.termux.app.** { *; }` + `-keep class okhttp3.** { *; }` +
  `-keep class com.google.gson.** { *; }` + `-keep class androidx.** { *; }`
  = nothing gets obfuscated in release builds.
- Fixed: Only keeps what's actually needed — manifest-referenced components, Gson
  reflection targets, OkHttp public API, Room entities/DAOs. Everything else gets
  obfuscated normally.

---

### FILE: file_paths.xml

**Problem: FileProvider exposed entire internal storage**
- Old: `<files-path name="app_files" path="." />` = everything under `/data/data/com.termux.ai/files/`
- Fixed: Restricted to `shared/`, `scripts/`, `ai_models/`, and a dedicated `share_cache/`.

---

### FILES: backup_rules.xml + data_extraction_rules.xml

- Exclude ALL shared_prefs (credentials) from backup
- Exclude the `usr/` bootstrap environment (180MB+ of binaries)
- Only include `scripts/` if backup is ever re-enabled

---

## How to Apply

### Option A: Drop-in replacement
Copy these files into your repo, overwriting the originals:
```
AIClient.java           → app/src/main/java/com/termux/ai/AIClient.java
EncryptedPreferencesManager.java → app/src/main/java/com/termux/ai/EncryptedPreferencesManager.java
build.gradle            → app/build.gradle
gradle.properties       → gradle.properties
AndroidManifest.xml     → app/src/main/AndroidManifest.xml
proguard-rules.pro      → app/proguard-rules.pro
file_paths.xml          → app/src/main/res/xml/file_paths.xml
backup_rules.xml        → app/src/main/res/xml/backup_rules.xml
data_extraction_rules.xml → app/src/main/res/xml/data_extraction_rules.xml
```

### Option B: After dropping in, you'll also need to:
1. **Delete** any `RedirectUriReceiverActivity.java` if it exists
2. **Update ClaudeSetupActivity** to show an API key input field instead of OAuth login
3. **Create the directories** referenced in file_paths.xml (`shared/`, `share_cache/`)
   or they'll be created on first use by FileProvider
4. **Clean build**: `./gradlew clean assembleDebug`

### After installing the updated APK:
1. Open Settings → AI Provider Setup
2. Enter your Anthropic API key (from console.anthropic.com)
3. Test with a command — you should get actual Claude responses now

---

## What Still Works
- Terminal emulator (untouched)
- Tab management (untouched)
- Gemini integration (was already correct)
- PrivacyGuard filtering (untouched)
- ContextEngine project detection (untouched)
- Plugin architecture (untouched)
- Material You theming (untouched)
- All UI components (untouched)
