# OC Pilot — عميل أندرويد أصلي لـ OpenCode

**OC Pilot** is a native Android **remote client**, not an OpenCode runtime. Your server/gateway runs OpenCode, GitHub operations and builds. The phone never runs OpenCode, Node, Gradle, an Android SDK or shell/build commands.

- Package `com.ocpilot.app`; Java 8; minSdk **28**, target/compileSdk **34**.
- Version **1.0.0** / versionCode **1**.
- One framework Activity, programmatic Android Views, no AndroidX/AppCompat/Material library/Kotlin/WebView/third-party runtime/native code.
- العربية افتراضيًا مع RTL؛ English resources for English devices. Midnight is the default theme; Light/System are selectable.
- Pairing, Home, Projects, Workspace, Builds, Settings, GitHub and per-file Diff screens.

## الاستخدام السريع

1. شغّل بوابة الخادم وفق العقد الموجود في `docs/CONTRACT.md`، مع OpenCode على الخادم فقط.
2. ثبّت APK، وأدخل عنوان البوابة مثل `1.2.3.4:25565` ثم Link Code من وحدة تحكم الخادم، مثل `ABCD-EFGH`.
3. Link Code رمز مؤقت، وليس بيانات اعتماد الجهاز أو رمز GitHub. بيانات اعتماد الجهاز لا تظهر في الواجهة.
4. من **Projects** أنشئ مشروعًا فارغًا، أو اختر مستودعًا، أو أنشئ مستودعًا جديدًا مع المشروع. افتح **Workspace**، أنشئ/اختر جلسة، ثم أرسل طلبك.
5. يجري العمل والبناء على الخادم. يظهر سجل الأدوات الحقيقي والطلبات المعلقة والأذونات؛ لا توجد نسب تقدم مصطنعة.
6. من **Builds** نزّل الملف وتحقق من SHA-256 ثم ثبّت/احفظ/شارك. تثبيت APK يتطلب سماح أندرويد بالتثبيت من OC Pilot.

## Build locally — on a computer/server, never on the client phone

Required: **JDK 17**, **Gradle 8.5**, Android SDK platform 34/build-tools 34.0.0 and accepted SDK licenses. AGP is pinned to **8.2.2**. Only test dependencies are `junit:4.13.2` and `org.json:20231013`.

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
export PATH="$JAVA_HOME/bin:/path/to/gradle-8.5/bin:$ANDROID_HOME/platform-tools:$PATH"
gradle --version
gradle --no-daemon testDebugUnitTest assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

Alternatively, set `sdk.dir=/absolute/path/to/android-sdk` in your untracked `local.properties`. A Gradle wrapper is intentionally unnecessary: the workflow provisions exactly Gradle 8.5.

```sh
gradle --no-daemon assembleRelease
# Release is unminified and UNSIGNED; configure your own signing outside this repository.
```

## GitHub Actions from a phone

1. Extract **ocpilot-android.zip**. Put the **extracted project contents**, not the ZIP itself, at your GitHub repository root. Keep `app/`, `.github/workflows/build-apk.yml`, the Gradle files and docs in their original paths. GitHub's browser editor/Codespaces can be used from a phone; no phone-side Gradle or Node is needed.
2. Push/merge the project to `main` or `master`. Open **Actions → Build debug APK**. A push triggers it, or use **Run workflow** after the workflow exists on the default branch.
3. Wait for **Unit tests + debug APK** to succeed. Download artifact **ocpilot-debug-apk**, extract `app-debug.apk`, and install it on Android 9+.
4. GitHub Actions refuses AndroidX/Kotlin source and fails if no debug APK exists. Workflow build results have **not** been claimed without a real run.

The supplied `ocpilot-debug.apk` was assembled and signature-verified with standalone Android tooling in the sandbox. It is **not** evidence that the above Gradle command or GitHub Actions ran. See the verification report for exact results. Debug signing keys differ between fresh CI runners/local builds: an incompatible signature requires uninstalling the previous build (which removes local credentials/drafts). Revoke the old device on the server and pair again if needed. Use a stable private signing key for updates in your own distribution process.

## Pairing and server requirements

The gateway must implement **SHARED API CONTRACT v1** exactly. [CONTRACT.md](docs/CONTRACT.md) is retained as the contract document; [OPENCODE-NOTES.md](docs/OPENCODE-NOTES.md) records the actual classic OpenCode APIs inspected before chat implementation. The tested API shapes are pinned to **OpenCode v1.18.35**, not the incompatible development v2 API.

- Enter the gateway's public base host/port, not the private OpenCode port or GitHub URL. Paths/query/fragment are stripped. Bare IPs and explicit ports choose HTTP; otherwise HTTPS is the default.
- Use a publicly trusted HTTPS certificate or a trusted LAN/VPN. HTTP remains supported and displays a persistent unencrypted-traffic warning. There is no trust-all TLS bypass.
- Gateway OpenCode requests use `X-Project-Id`; the **gateway injects the working directory and upstream authentication**. The phone never sends a directory or contacts GitHub/OpenCode directly.
- For earlier server history, preserve `X-Next-Cursor` (or the Link continuation header) through the gateway. The client never invents cursor values.
- A valid new Link Code is needed after revocation/disconnect. Any 401 for the current credential clears it and returns to pairing; stale requests from an old rotated credential cannot delete the new one.

## Features and data lifetime

- Home polls health/status every 15 seconds while shown; displays real supervisor/tools/disk/memory data, local last-50 task summaries and recent artifacts.
- Projects: list, open, rename, archive/unarchive, Git status/commits and name-confirmed delete. Creation supports repository clone, create-repo-and-project and empty project.
- GitHub: status/login/scopes, password token entry (never restored; cleared before dispatch and on all outcomes), paginated 30-item repo retrieval, loaded-repo search and repo creation. The token is stored **only by the gateway**, never in phone preferences.
- Workspace: real sessions/models, native recycled chat (400-row window), older windows/server pages, draft/model/session persistence per server/project, SSE, real tools/logs, permissions and destructive-command warnings. Busy prompts remain in a local persisted queue. A draft is cleared only after the task journal commits successfully. Retry/Continue are real prompts; Stop waits for a true abort acknowledgement.
- Reconnect reloads session metadata, actual status map, messages and pending permissions. SSE has its own thread, last-event-ID, bounded jittered exponential backoff and 30-second healthy reset. View refreshes are coalesced to 100 ms.
- Active tasks own a foreground dataSync service/stream independent of the screen. Idle streams close when views detach. Queued tasks can continue across an already-running foreground service; Android's restrictions prevent starting new work from background without an existing watch. Process restart resynchronizes persisted tasks instead of pretending they finished.
- Permission/completion/failure notifications are opt-in. Only enabling notifications requests POST_NOTIFICATIONS on API 33+; mandatory foreground notification behavior is governed by Android.
- Builds contains only tasks identified by real build-pattern tool commands. Artifacts are grouped DEBUG/RELEASE/ZIP/LOG/OTHER. Downloads use authenticated Range/`.part`, verify mandatory SHA-256, and never serve partial files. Before install/share/export, the cached file is re-verified. Public export uses MediaStore on API 29+, and runtime WRITE_EXTERNAL_STORAGE only on API 28.
- File diffs use the server's actual unified `patch`, with 2,000-line recycled windows, horizontal scrolling and earlier/more controls. Binary/absent patches show a truthful no-text-diff message.
- Private downloads use the app's external downloads folder or internal fallback. The read-only grantable provider canonically confines URIs to that directory and rejects `..`, separators and `.part`.
- Settings: devices, revoke/rotate, local/server disconnect, notification toggle, 15/30/60-second reconnect caps, 10/30/60-second HTTP read timeout, themes and cache clearing.
- Device credential: Android Keystore AES-256-GCM alias `ocpilot_token_key`, Base64(IV || ciphertext). No plaintext fallback. Keystore failure refuses pairing/rotation. `allowBackup=false`; logs/crash notes never contain request data or exception messages.
- Local drafts/task prompts/tool summaries are app-private user data, **not a secret vault**. Do not paste GitHub/AI credentials into chat/source/tool output. Task history is capped at 50; stored tool summaries retain the most recent 16 KiB. The in-memory transcript has a 2,000-part / approximately 8 Mi-character safety budget; oversized prompts above 128 Ki characters are rejected without clearing the draft. HTTP JSON and SSE frames are bounded at 8 MiB and 2 Mi characters respectively. Authoritative full conversations/builds remain on the server. Public exported copies are intentionally not deleted by private cache clearing.

## Tests, review and limits

```sh
gradle --no-daemon testDebugUnitTest
python3 tools/static-review.py
```

Six JUnit suites cover incremental UTF-8 SSE/CR/LF/CRLF/chunk boundaries, backoff/jitter/caps, URLs, all gateway model responses/nulls, real spec events/tool/permission/abort/replay/session isolation, reconnect snapshots and SHA-256 streaming vectors. Fixtures are schema-derived examples, **not fabricated live captures** or mock APIs; their manifest identifies source schemas.

[VERIFICATION.md](docs/VERIFICATION.md) separates executed compilation/tests/signature checks from **NOT VERIFIED** Gradle/CI/device/live-gateway checks and lists the seven acceptance criteria. No emulator/device or running gateway was available. Background survival depends on Android/OEM process and battery policy; a force-stop stops the client, not the server's work. Reopen the client to resynchronize. A classic server upgrade changing its OpenAPI requires a compatibility review; v2 compatibility is not assumed.
