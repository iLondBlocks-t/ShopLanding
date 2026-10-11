# OC Pilot — verification and delivery report

Initial verification date: **2026-10-10**, Africa/Casablanca, on branch `arena/a985556f-shoplanding`. No commit, push, PR or GitHub Actions run had been performed at that point. Publishing these changes does not constitute additional build, CI, device or live-gateway verification.

## Latest APK refresh — 2026-10-11

The temporary APK/tool outputs from the previous turn were not retained. `ocpilot-debug.apk` was rebuilt from the unchanged latest app sources, compiled (135 class files), D8-converted, signed and verified again. The deliverable is now explicitly retained in the repository worktree.

- SHA-256: `beda199476ce360f2b2e1d30e8d4557a37669d9f4a9b549d10fe1d70f91f94fc`.
- Size: **177394 bytes**; package com.ocpilot.app / version 1.0.0, minSdk 28, target/compileSdk 34.
- Actual signature result: `verified=true, V3=true, errors=[], warnings=[]` for API 28–34.
- APK ZIP CRC, resource alignment, manifest metadata and absence of native payloads checked again.
- **Fresh debug signing key:** this cannot update an APK signed with the previous temporary key. Uninstalling the previous build clears local credentials/drafts; server data remains on the server.
- The older results below remain a historical record. No additional Gradle/CI/device/live-gateway verification is claimed in this refresh.

## Delivered (original 2026-10-10 build)

- `ocpilot-android.zip`: the complete Gradle source project, workflow, documentation, six JUnit suites plus fixture helper, schema-derived fixtures and the offline structural checker.
- `ocpilot-debug.apk`: standalone-built **debug** APK, package `com.ocpilot.app`, version 1 / 1.0.0, minSdk 28, target/compileSdk 34. Not installed on a device in this environment.
- APK SHA-256: `74c890958ca5ce7fca168f443e141334684c87381d4606f5265666d4fca04f99`.
- APK size: **177,394 bytes**. No native ABI payloads or third-party runtime class definitions.

## VERIFIED — actually executed

### 1. Resources and every production Java class

Standalone Android tooling compiled all resources and all **32 production Java files**, with generated R/BuildConfig matching debug metadata. Commands (sandbox paths, not portable project prerequisites):

```sh
/home/user/tools/aapt2 compile --dir app/src/main/res -o .work/resources.zip
/home/user/tools/aapt2 link -o .work/resources.apk \
  -I /home/user/tools/android-34.jar --manifest .work/AndroidManifest.xml \
  --java .work/gen --min-sdk-version 28 --target-sdk-version 34 \
  --version-code 1 --version-name 1.0.0 --auto-add-overlay -R .work/resources.zip
/home/user/tools/jdk4py/jdk4py/java-runtime/bin/java \
  -cp /home/user/tools/javac-package/package/tools.jar com.sun.tools.javac.Main \
  -encoding UTF-8 -source 1.8 -target 1.8 \
  -bootclasspath .work/compiler-lib.jar:/home/user/tools/android-34.jar \
  -d .work/classes @.work/android-sources.txt
```

Actual compiler result: **exit 0**, `135` class files. Only:

```text
Note: Some input files use or override a deprecated API.
Note: Recompile with -Xlint:deprecation for details.
Android Java compilation PASS; class files: 135
```

Important limitation: the sandbox had a **JRE 17.0.9**, not a complete JDK/Gradle SDK. The standalone compiler is **javac 1.8.0_131** from tools.jar. The compiler-only bootstrap compatibility jar supplies LambdaMetafactory and Java-8 annotation signatures to avoid SDK-34/newer-annotation incompatibilities in that older compiler. These build-tool signatures are **not packaged in the APK**. This is a real source compilation, but **not** an AGP/Gradle/JDK-17 build verification. The delivered project has no dependency on these sandbox workarounds; normal AGP uses its standard SDK/JDK toolchain.

### 2. JUnit: all six suites, 73 tests

The pure production classes and tests were compiled against JUnit's official **r4.13.2 source**, its Hamcrest core 1.3 test dependency, and JSON-java **20231013 source**. Test code was not packaged in the APK. Actual runner command:

```sh
/home/user/tools/jdk4py/jdk4py/java-runtime/bin/java \
  -cp .work/jvm-classes:app/src/test/resources:/home/user/tools/src/junit4-r4.13.2/lib/hamcrest-core-1.3.jar \
  org.junit.runner.JUnitCore \
  com.ocpilot.app.core.SseParserTest com.ocpilot.app.core.BackoffTest \
  com.ocpilot.app.core.UrlUtilTest com.ocpilot.app.core.ModelsTest \
  com.ocpilot.app.core.EventsTest com.ocpilot.app.core.HashingTest
```

Actual final output, exit 0:

```text
JUnit version 4.13.2-SNAPSHOT
.........................................................................
Time: 0.806

OK (73 tests)
```

The source tag's Version class prints `4.13.2-SNAPSHOT`; this string has not been altered in the report. The Gradle file correctly requests the released Maven dependency `junit:junit:4.13.2`. This is **standalone JVM verification**, not a claimed Gradle or CI test run.

Covered: every UTF-8/chunk split of an Arabic/emoji/CRLF frame; LF/CR/CRLF, comments, multiline data, fields/retry/ID retention/reset, EOF; backoff sequences/caps/jitter/reset; URL rules and opaque Link cursors; every contract model response/null optional; actual spec events, duplicate/out-of-order snapshots, tool lifecycle, real permissions, idle/error/abort/wrong session, reconnect growth/terminal snapshots, bounded native part cache, SHA-256 vectors/streaming chunk sizes.

### 3. Real OpenAPI fixture validation

The 40 `oc-*.json` examples were validated with JSON Schema Draft 2020-12 against real classic OpenAPI 3.1, pinned to OpenCode v1.18.35 commit `53d1eabb61e21162157817bf677da0a4ad3332e3`. Actual output:

```text
PASS: 40 OpenCode fixtures validated against OpenAPI 3.1; total fixtures: 56
```

They are **schema-derived examples**, not live captures. The other fixtures are from the shared gateway contract; the HTTP-204 fixture has exactly zero bytes. The source/schema manifest is included. The validator printed its RefResolver deprecation warning, not a validation failure.

### 4. APK assembly, signing, integrity and metadata

Actual D8 command, exit 0:

```sh
/home/user/tools/jdk4py/jdk4py/java-runtime/bin/java \
  -cp /home/user/tools/r8.jar com.android.tools.r8.D8 --debug --min-api 28 \
  --lib /home/user/tools/android-34.jar --output .work/dex .work/app-classes.jar
```

D8 version actually executed: **1.5.13-q1**, from LineageOS/android_prebuilts_r8. aapt2 version: `2.20-eng.202610`. Signature code was compiled from AOSP apksig mirrored at LineageOS/android_tools_apksig, commit `6447768c0e8ab4385a47e7bb85e6bdba0085dced`. A fresh RSA-2048 debug key was generated **outside the project**. No signing key is in the ZIP.

Resources and classes.dex were assembled as ZIP entries, uncompressed entries aligned to 4 bytes, then signed and verified with ApkSigner/ApkVerifier. Actual verifier outputs:

```text
Signature range 28-34: verified=true, V2=false, V3=true, errors=[], warnings=[]
Signature range 24-27: verified=true, V2=true, V3=true, errors=[], warnings=[]
```

For 28–34 the verifier selects V3; the `V2=false` flag means it did not select V2 for that range. V2 and V3 blocks are both present (`0x7109871a`, `0xf05368c0`) and the separate forced lower-range signature check verified V2. **The application still requires API 28+**; a signature check is not a lower-platform install claim.

Binary inspection actually confirmed:

```text
DEX has 364 app-only class definitions; no runtime libraries/native code; Java lambdas desugared.
classes.dex: 277188 bytes
resources.arsc: uncompressed, alignment mod4 = 0
```

Actual aapt2 badging includes:

```text
package: name='com.ocpilot.app' versionCode='1' versionName='1.0.0' ... compileSdkVersion='34'
minSdkVersion:'28'
targetSdkVersion:'34'
application-debuggable
launchable-activity: name='com.ocpilot.app.MainActivity'
locales: '--_--' 'en'
```

`--_--` is the default Arabic string set, not an absent translation. All required manifest permissions appear; aapt also reports the platform-implied API-28 READ_EXTERNAL_STORAGE associated with explicit WRITE_EXTERNAL_STORAGE. The source manifest explicitly caps WRITE at 28.

### 5. Structural/self-review checks

Actually executed:

```sh
python3 tools/static-review.py
# exact workflow grep guard also run
```

Actual output ends:

```text
23/23 structural checks passed; 32 production Java files.
Not covered: Gradle/lint, Android install/UI/lifecycle/Keystore, real gateway behavior.
```

A Java-8 AST parser separately parsed all **39 main/test Java files**. The GitHub workflow YAML parsed, including push branches main/master and workflow_dispatch. Arabic and English have **271 matching strings**; printf signatures and every R.string reference resolve. All XML was parsed and linked by aapt2. The exact AndroidX/Kotlin source guard passed. No TODO/FIXME/stub controls, XML layouts, Kotlin, native payloads, WebView, support-library imports or runtime Gradle dependencies were found.

## Static review record — inspected, not device-tested

| Area | Review performed |
|---|---|
| Contract/model/API | All gateway routes, JSON keys, auth exclusions, UTF-8, no directory from client; OpenCode shapes verified against actual pinned docs/spec; globals have no X-Project-Id |
| Imports/classes/resources | Every production class compiled against Android 34; full Java-8 AST parse, complete required file list, string parity/reference and XML checks |
| Manifest/legacy APIs | Launcher/service/provider declarations inspected. MediaStore + typed foreground API guarded at 29; notifications and framework BackInvoked guarded at 33; API-28 storage path and runtime permission; java.time/java.nio.file APIs are 26+; buffer methods use API-compatible Buffer signatures |
| Threads/callbacks | Four shared request/file workers; dedicated SSE thread; UI callbacks through explicit main Looper Handler; preference restore, Keystore, hashing/export/download and cache operations off main; provider file access uses Android Binder callbacks |
| Credential races | Worker encryption/durable commit, generation checks and serialized secret writes; disconnect cannot resurrect a token. Rotation operation epochs and deferred old-token 401s preserve a newly stored token; requests/streams/downloads are server/device scoped |
| Task/draft recovery | Stable verified-format messageID; assistant parentID binding; durable queue journal before clearing draft/sending; per-server/project draft/model/session; active-task cap protected; no blind network retransmission; metadata/status/messages/permission recovery |
| SSE/reducer | Incremental UTF-8, id/retry/line endings; seeded last-ID on reconnect; event replay/wrong-session filtering; status/permission revision gates; stale terminal tools, history/text replacement/growth; coalesced refresh; idle detached streams release ownership |
| Foreground/notifications | Immediate foreground in service onCreate, dataSync type on 29+, proper manifest permission; stop on terminal/abort/disconnect, keep active task independent of Views; permission decisions never automatic; IMMUTABLE project PendingIntent; normal notifications opt-in |
| Files/actions | Immutable URL/target namespace, authenticated no-redirect Range, complete Content-Range/length checks, partial cancellation, SHA-256, atomic replacement, reverify install/share/export, read-only canonical provider, source-permission resume, MediaStore pending cleanup |
| Native UI/state | Five root tabs, native navigation/dialogs/ListView recycling, earlier 400-row windows and opaque server cursors, 2,000-line diff windows, plain untrusted text, actual command-based build/test/read labels, no fabricated percentages; current active Engine cannot be switched by a historical Home task |
| Secrets/faults | No GitHub/AI configuration tokens persisted; GitHub password starts empty/saveEnabled false and clears at dispatch/outcomes; Keystore credential only; no raw exceptions/data in logs/crash note; no trust-all TLS; bounded caches/parsers and StackOverflow-safe JSON callback handling |

## NOT VERIFIED — explicit reasons

1. **Requested Gradle command** was executed as-is:

   ```text
   $ gradle --no-daemon testDebugUnitTest assembleDebug
   /bin/bash: line 8: gradle: command not found
   exit code 127
   ```

   No Gradle installation/full SDK/JDK toolchain was available, and required Google/Maven distribution hosts are outside this sandbox's permitted internet hosts. The successful standalone build does **not** substitute for this verification. No Android lint or release build was run.
2. **GitHub Actions**: exact workflow delivered/parsed/guard checked, but no push/dispatch/run/check success was executed. Its configured build must be run after deployment to main/master.
3. **Physical Android/emulator**: none available. Installation, launch/visual polish/RTL/keyboard/layout/accessibility, every button's real device behavior, API-28/API-34 permissions, foreground timing/background survival, Keystore hardware and installer/provider/MediaStore flows are **not executed end-to-end**.
4. **Live gateway/OpenCode/GitHub**: no running gateway, Link Code or account provided. Pairing/revocation/rotation, actual repo/project CRUD, live SSE gap recovery and remote build/artifact installation are **not claimed to work from a live test**. No mock/live-fabricated API substitutes were used.
5. **Verbatim contract byte comparison**: the contract document created from the provided section was retained and its routes/shapes reviewed; an independent byte-for-byte comparison to an original attachment was not possible. No gateway API changes were introduced.

## Seven acceptance criteria

| # | Status | Evidence / remaining verification |
|---|---|---|
| 1. Debug build / fallback review | **Partial** | Real standalone source/resource/D8/APK/signature build passed. Exact Gradle build is NOT VERIFIED (127). Full static review completed rather than claiming Gradle success |
| 2. No forbidden runtime stack | **VERIFIED structurally/binary** | Exact grep passed, zero runtime Gradle dependencies, app-only DEX definitions, no Kotlin/WebView/native payloads |
| 3. Manifest and minSdk compatibility | **Static + metadata verified; device NOT VERIFIED** | Required declarations, guard inspection and package min28/target34 confirmed; real API-28/34 install/lifecycle/permissions not run |
| 4. Six JUnit suites + fixtures | **Standalone VERIFIED; CI NOT VERIFIED** | 73 tests passed, 40 OpenCode examples validated, all contract models covered; Gradle/CI execution still needed |
| 5. Full native screens/controls | **Implemented + compiled; E2E NOT VERIFIED** | All eight screens and real API/file controls compiled; no fake UI progress or mock endpoints; device/live button tests still needed |
| 6. Security/auth rules | **Static-reviewed; device/live NOT VERIFIED** | Keystore encrypted-only path, password clearing, generation/rotation/scope guards, grant-only provider; hardware/token-revocation lifecycle tests still needed |
| 7. Offline/error/reconnect/recovery | **Pure logic VERIFIED; live E2E NOT VERIFIED** | Parser/replay/scope/permissions/abort/snapshot/hash tests passed; cached state and recovery implemented; real disconnect/process-kill/long-build trials still needed |

## Deviations and limits (not hidden)

- The provided debug APK is a **standalone tooling build**, not the exact requested AGP/Gradle CI build. Source keeps the exact requested AGP/Gradle/JDK configuration.
- To enable immediate queued work in the background, the already-started foreground service can span adjoining queued prompts. It stops once the queue/work is idle or on abort/disconnect. An idle screen never starts a new background service by itself. Android/OEM policy/force-stop cannot be overridden by this client.
- Memory safety: rendered chat is 400 rows; diff 2,000 lines/window; reducer has 2,000 parts/~8 Mi characters and 1,500 message metadata records; stored task summaries keep last 16 KiB of logs; prompts above 128 Ki characters are rejected with the draft intact. Very large single-message histories may exceed local cache windows; full history remains authoritative on the server.
- No compatibility claim for development OpenCode v2. Missing modern permission route gets only the **documented** classic fallback, not an invented endpoint.
- SHA-256 verifies against the gateway's listing, not a replacement for HTTPS/server trust. Debug keys are not a stable release/update signing policy. Public exported copies are retained outside private-cache deletion.

Build, deploy-to-Actions and pairing instructions are in [README.md](../README.md). The complete source file tree is in [FILE-TREE.md](FILE-TREE.md).
