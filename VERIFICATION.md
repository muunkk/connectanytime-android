# Material 3 Expressive redesign verification

Verified on the Mac mini on 2026-11-12, on local `main`. No device or emulator was
started or driven. No remote was added and nothing was pushed. VPN behaviour files
(ConnectVpnService.kt, cpp/, Logic.kt, Storage.kt, NativeBridge.kt, NetworkWatch.kt),
signing, R8 setup and ProGuard rules are unchanged. VpnState is unchanged: the status
area derives its five states from the existing status, busy, connected and error.

## Dependencies

- Compose BOM 2025.10.01 → **2026.06.01** (Compose 1.11.4).
- Material 3 → **1.5.0-alpha18**, pinned over the BOM's 1.4.0, which lacks
  MaterialExpressiveTheme, LoadingIndicator, MaterialShapes, ButtonShapes,
  SegmentedListItem and the flexible app bars.
- AGP 8.13.2, Kotlin 2.2.21, Gradle 8.13 and compile/target SDK 36 are unchanged.
  The AAR metadata of Compose 1.12 (BOM 2026.08.00 and later) and of Material 3
  1.5.0-alpha19 through 1.5.0-rc01 requires AGP 9.1.0 and compileSdk 37;
  `checkDebugAarMetadata` rejected them with the pinned toolchain. 1.5.0-alpha18 is
  the newest Material 3 release built on Compose 1.11, so it ships the Expressive
  APIs without that toolchain change. The APIs are still `@ExperimentalMaterial3ExpressiveApi`
  there; the UI files opt in at file level.

## Checks

```sh
./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug lintRelease
```

PASS, exit 0, `BUILD SUCCESSFUL`, 123 actionable tasks.

- LogicTest: 9 tests, 0 failures/errors/skips (unchanged).
- ScreensTest (Robolectric, SDK 35): 8 tests, 0 failures. Covers not connected,
  connecting (loading indicator), connected, reconnecting, failed with the reason,
  session ended; the static badge when animations are removed; Activity newest-first
  day grouping, Clear confirmation/cancel and empty state; NetID validation and
  password show/hide; remove saved password cancel then confirm, plus the locked
  server while busy; Stay Connected as a switch; settings and About reachable in
  landscape at 200% font.
- ScreenshotTest (Robolectric native graphics): 24 captures, 0 failures.
- `lintDebug` and `lintRelease`: each **0 errors, 21 warnings**, no baseline, no new
  suppressions. Warnings: newer dependency versions (GradleDependency,
  NewerVersionAvailable, AndroidGradlePluginVersion), existing synchronous
  preference commits (ApplySharedPref) and optional KTX style (UseKtx).

```sh
python3 scripts/verify-release.py
scripts/verify-native.sh
git diff --check
```

All PASS, exit 0: signature, 16 KB alignment, both ABI libraries, package names,
labels, version 0.1.0 (1), all 11 native methods and the four JNI callbacks after R8;
JNI exports and dynamic dependencies for both ABIs; no whitespace errors.

- Release: `app/build/outputs/apk/release/app-release.apk`, 16,316,052 bytes.
- Debug: `app/build/outputs/apk/debug/app-debug.apk`, 52,918,029 bytes.

## Screenshots

`testDebugUnitTest` writes PNGs to `app/build/screenshots/` (411×891 dp at xhdpi,
fallback brand colors so the images do not depend on Robolectric's stand-in
wallpaper palette), each as `-light.png` and `-dark.png`:
`home-not-connected`, `home-ready`, `home-connecting`, `home-connected`,
`home-reconnecting`, `home-failed`, `home-session-ended`, `home-settings`,
`home-remove-password-dialog`, `home-landscape-200pct`, `activity`, `activity-empty`.
Compose's `captureToImage()` waits for a frame-commit callback Robolectric never
delivers, so the test draws the newest window's view into a bitmap. Compose tests
pause endless animations, so the loading indicator shows its first frame.

## Limits

These are JVM renderings. Real rendering, dynamic colors from a real wallpaper,
TalkBack announcements, the reduced-motion setting, notification and Quick Settings
tile appearance, and every VPN runtime behaviour still need an Android device.

---

# Android production-hardening verification

Verified on the Mac mini on 2026-11-12, on local `main`. No device or emulator was
started or driven. No remote was added and nothing was pushed. The iOS sibling at
`/Users/mini/Master/code/anyconnectios` remains clean and was used read-only.

## Final checks

From `/Users/mini/Master/code/connectanytime-android`:

```sh
./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug lintRelease
```

PASS, exit 0: `BUILD SUCCESSFUL in 24s`, 123 actionable tasks (42 executed,
81 up-to-date). A clean build also passed before the final string-only adjustment.
Both ABIs compiled and linked; release enables R8 and resource shrinking.
Log: `/tmp/connectanytime-production-checks.log`.

- Existing LogicTest: 9 tests, 0 failures/errors/skips.
- New JVM-only Robolectric ScreensTest: 4 tests, 0 failures/errors/skips.
  Covers clear confirmation/cancel/empty state, account validation and password
  visibility, saved-password removal and disabled server editing while busy, and
  scrolling to settings/About in landscape at 200% font scale.
- `lintDebug` and `lintRelease`: each **0 errors, 20 warnings**, no baseline.
  Warnings concern pinned dependency versions, existing synchronous preference
  commits and optional KTX editor style. No new lint suppressions were added.
  The two pre-existing narrow suppressions remain: the API-34 tile fallback and
  the manifest detector's alarm-only view of the systemExempted VPN exemption.

Reports: `app/build/reports/tests/testDebugUnitTest/index.html`,
`app/build/reports/lint-results-debug.html`, and
`app/build/reports/lint-results-release.html`.

```sh
python3 scripts/verify-release.py
```

PASS, exit 0. The script invokes build-tools 36.0.0 `apksigner verify --print-certs`,
`zipalign -c -P 16 4`, `aapt dump badging` and `dexdump`. Log:
`/tmp/connectanytime-release-verification.log`.

- Signature verifies. Certificate DN: `CN=ConnectAnytime Local Release`.
  Certificate SHA-256:
  `b5e81629e55045309f942e9c954eb7cf8cea7942095ffbabb9c558b63080035e`.
- Release package `com.muunkk.connectanytime`, label `ConnectAnytime`;
  debug package `com.muunkk.connectanytime.debug`, label `ConnectAnytime Dev`.
  Both report version **0.1.0 (1)**. Debug shortcuts also target the debug package.
- Release contains `lib/arm64-v8a/libconnectanytime.so` (6,614,968 bytes) and
  `lib/x86_64/libconnectanytime.so` (7,027,680 bytes).
- Release DEX retains the `NativeBridge` class and all 11 native method names:
  setNetwork, create, connect, resume, error, saved, config, run,
  abortBeforePassword, command, free.
- R8 mapping locates the concrete callback as `com.muunkk.connectanytime.a`.
  Release DEX retains all four exact names and descriptors:

```text
onLog(Ljava/lang/String;)V
onEvent(I[Ljava/lang/String;)V
verify([[BLjava/lang/String;)Z
protectSocket(I)Z
```

JNI audit: `jni.c` uses `GetObjectClass` and these four `GetMethodID` calls.
Its only `FindClass` calls are `java/lang/String` and `[B`, both platform types.
There are no `GetFieldID` calls, app-class construction or app-class lookups.
The explicit ProGuard rules therefore keep NativeBridge native methods, the
Callbacks interface and the four concrete implementation members, without a
package-wide keep. Core native sources and Logic.kt are unchanged.

```sh
scripts/verify-native.sh
```

PASS, exit 0: both ABIs export JNI_OnLoad and all 11 JNI entry points, with only
Android system dynamic dependencies (`libz`, `liblog`, `libandroid`, `libdl`,
`libm`, `libc`). Debug APK 16 KB alignment passes. Release alignment passes in
the release script. Log: `/tmp/connectanytime-native-verification.log`.
The pinned native dependency builds were already present; no dependency recipe
or source changes were needed for this task.

Signing-negative check: temporarily moved `keystore.properties` into a private
external temporary directory, ran `./gradlew assembleRelease`, and restored it
in a Python `finally` block. PASS: nonzero exit with
“Release signing requires keystore.properties with storeFile, storePassword,
keyAlias and keyPassword. See keystore.properties.example.”
Log: `/tmp/connectanytime-missing-signing.log`. Debug remains buildable without
release signing properties.

```sh
git diff --check
git -C /Users/mini/Master/code/anyconnectios status --porcelain
git remote -v
git check-ignore keystore.properties
```

PASS: no whitespace errors, iOS clean, no Android remotes, signing properties
ignored. No secrets were staged or committed. The external keystore and ignored
properties have mode 600; their parent configuration directory has mode 700.

## Artifacts and signing

- Release: `app/build/outputs/apk/release/app-release.apk`, 15,947,344 bytes.
  SHA-256: `0c66117ced7303f982776807a4d64f5fb0721b3f37692702258b88ab4642bbd2`.
- Debug: `app/build/outputs/apk/debug/app-debug.apk`, 41,692,180 bytes.
  SHA-256: `10bf2b8d20aa9c058d46d912e214d3f885329217cf4962a85686055c2b9f4ef2`.
- Keystore: `/Users/mini/.config/connectanytime-android/release.jks`.
  Generated locally with keytool, RSA 3072, a cryptographically random password,
  alias `connectanytime`. Passwords are only in ignored `keystore.properties`.
  Do not publish that file. Back up the key and properties securely for updates.

## Implementation boundaries and limits

The service changes only replace presentation strings with resources; its state,
login, retry and tunnel decisions are unchanged. Its English status tokens and
StartRule's pure-JVM refusal constant remain internal protocol values and are
mapped to string resources for display. Native/server-generated diagnostic text
continues to appear as received. Storage adds only explicit saved-password removal.
Password input never enters saved instance state. UI text, notification text,
channels and app-generated service diagnostics live in string resources.

Dynamic colors, system bars and splash resources are configured for light/dark.
Adaptive icon foreground/background/monochrome layers share an original shield
and linked-chain motif. The tile and notification use a white transparent vector.
Notification permission is requested on the first eligible Connect tap, with a
denial explanation and continuation to VPN consent. These Android platform flows,
real rendering on hardware, NYU login/Duo, trust, traffic and leak checks,
network transitions, Always-on/boot/process death and actual server logout still
need device testing. JVM checks do not prove those runtime behaviors.

During implementation, two UI-test selectors initially targeted label text rather
than editable/disabled field semantics. Corrected selectors pass. Moving the icon
resource between qualifier directories exposed stale incremental resource state;
a clean build resolved it. No failure remains outstanding.

## Files changed

- `.gitignore`
- `README.md`
- `VERIFICATION.md`
- `app/build.gradle.kts`
- `app/proguard-rules.pro`
- `app/src/debug/res/values/strings.xml`
- `app/src/debug/res/xml/shortcuts.xml`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/muunkk/connectanytime/ActivityScreen.kt`
- `app/src/main/java/com/muunkk/connectanytime/AppTheme.kt`
- `app/src/main/java/com/muunkk/connectanytime/ConnectVpnService.kt`
- `app/src/main/java/com/muunkk/connectanytime/Controls.kt`
- `app/src/main/java/com/muunkk/connectanytime/HomeScreen.kt`
- `app/src/main/java/com/muunkk/connectanytime/MainActivity.kt`
- `app/src/main/java/com/muunkk/connectanytime/Notices.kt`
- `app/src/main/java/com/muunkk/connectanytime/Storage.kt`
- `app/src/main/res/drawable/ic_back.xml`
- `app/src/main/res/drawable/ic_launcher_foreground.xml`
- `app/src/main/res/drawable/ic_visibility.xml`
- `app/src/main/res/drawable/ic_vpn.xml`
- `app/src/main/res/mipmap-anydpi/ic_launcher.xml`
- `app/src/main/res/values-night/colors.xml`
- `app/src/main/res/values-night/themes.xml`
- `app/src/main/res/values/colors.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values/themes.xml`
- `app/src/test/java/com/muunkk/connectanytime/ScreensTest.kt`
- `keystore.properties.example`
- `scripts/verify-release.py`

## Commits

- `2285facd6d393c311e1f92cb04d1a473785ac733`: signed shrunk releases, debug identity,
  signing example and repeatable APK/JNI verification.
- `d54433545ef348f09a303af9c665d1cba620f6dd`: Material 3 UI, icons, resource strings,
  permission timing, password removal and JVM Compose tests.
- Documentation commit follows these; `git log -1 --format=%H` gives its SHA.

---

# Review fixes verification (2026-11-12)

This section supersedes earlier statements that VPN/native files are unchanged.
All eleven reported issues were confirmed in code. Work was done on Android local
`main`; no remote was added, nothing was pushed, no device/emulator was started,
and the iOS repository was read only. HomeScreen.kt, ActivityScreen.kt, AppTheme.kt
and themes.xml are unchanged. Only two required credential-discard diagnostic
strings were added; existing app copy is unchanged.

## Findings and changes

1. **Real: ambiguous write could replay a body.**
   `Vendor/patches/openconnect-no-post-replay.patch:3` guards the `ssl_write`
   failure retry with `!request_body_type`, just as the response-read retry does.
   OpenConnect 9.21 `http.c:934` formerly checked only `rq_retry`. Partial writes
   can already submit a password or Duo request. HTTP_REDIRECT handling is unchanged
   deliberately. `Vendor/build-deps.sh:27` already hashes recipe and every patch:
   no recipe edit was necessary. A changed stamp rebuilt both ABIs; a subsequent
   invocation reports both up to date.
2. **Real: exported shortcut authorized outside callers.**
   `app/src/main/AndroidManifest.xml:23` sets ShortcutActivity exported=false.
   Static launcher shortcut execution with the app's identity must still be
   confirmed on a device, including locked/unlocked and consent cases.
3. **Real: pending/posted Connect survived Disconnect.**
   `ConnectVpnService.kt:64`, `:82`, `:468`, `:477` use a monotonically increasing
   user-command generation, checked at worker execution and again on main before
   restart. Pending Connect is published immediately, before its worker is queued,
   so finish cannot consume its startId first. A later Disconnect invalidates it.
   `Logic.kt:109` contains the pure decision; `LogicTest.kt:7` tests queued and
   already-posted Disconnect → Connect → Disconnect sequences plus a newer Connect.
4. **Real: decrypt/JSON exceptions escaped and retained unusable credentials.**
   `Storage.kt:72` removes an unreadable/unparsable value and logs one secret-free
   line. Invalid/unrecoverable keys are removed so the next save generates a new
   one; missing keys also regenerate. AEAD/corrupt-value errors discard only the
   affected value. Recover/write are serialized. `password():82` returns none;
   `session():84` returns none. Existing service reads at `ConnectVpnService.kt:154`
   and `:209` now continue to manual login or the save-password prompt as appropriate.
   The comment explains why credential-encrypted prefs, non-auth-bound keys and no
   direct boot provide no locked-user/authentication retry case on API 29+.
   StoreTest checks corrupt values are removed, return none, and are logged once.
   Hardware key loss, saving again and reaching actual NYU login remain device tests.
5. **Real: start published synthetic offline.**
   `NetworkWatch.kt:35` no longer publishes at registration. It waits for callback
   link properties. `Logic.kt:116` / `ConnectVpnService.kt:341` make the first
   callback identity a baseline, including offline, with no reconnect/log. JVM tests
   cover first online/offline, duplicate identities and real changes.
6. **Real: DEBUG promotion could never run at PRG_INFO.**
   Verified in downloaded OpenConnect `openconnect-internal.h:934` (`verbose`
   filters before callback) and `cstp.c:439–441` (headers logged at PRG_DEBUG).
   `app/src/main/cpp/ocbridge.c:70` reads CSTP options using
   `openconnect_get_ip_info(vpn, &ip, &opts, NULL)` and logs exactly six names,
   removing the X-CSTP- prefix. Calls at `:253`, `:309`, `:388` cover successful
   connect, resume and in-place reconnect. The dead DEBUG branch is removed;
   verbosity remains PRG_INFO. No other option is logged. The exact iOS diff below
   was generated against the read-only sibling and passed `patch --dry-run` there.
7. **Real: Builder default marks VPN metered.**
   `ConnectVpnService.kt:398` adds setMetered(false); underlying network assignment
   remains in place so effective meteredness can follow it.
8. **Real: unconditional stopSelf raced a later service start; ARM could launch.**
   `ConnectVpnService.kt:64` tracks latestStartId. Final stop/restart decisions run
   on main (`:468`) and use stopSelfResult(latestStartId), preserving a valid pending
   Connect. All other stopSelf calls also use stopSelfResult. An unlaunched ARM
   (`:65`) stops its startId and returns START_NOT_STICKY without starting VPN work.
9. **Real: JNI used modified UTF-8 for credentials/logs.**
   `jni.c:45`, `:123` transfer server/user/password and log lines as byte arrays;
   `NativeBridge.kt:12`, `:18` and `ConnectVpnService.kt:225`, `:250` encode/decode
   standard UTF-8. Kotlin replacement decoding handles a 1023-byte C log truncation
   through a multibyte character. C wipes its temporary password and the Java array;
   Kotlin also wipes in finally. OpenConnect retains its necessary credential copy,
   already wiped by the bridge; immutable password Strings cannot be zeroed.
   `app/proguard-rules.pro:7` keeps onLog(byte[]), and
   `scripts/verify-release.py:47` checks the new create descriptor as well as all
   four callback descriptors from C after R8.
10. **Real: app replacement did not restart.**
    `Controls.kt:29` accepts ACTION_MY_PACKAGE_REPLACED and BOOT_COMPLETED with
    identical Stay Connected/session/not-disconnected/VPN-consent gates. The
    manifest registers the action. Both use AUTOMATIC: rejoin only, never login.
11. **Real: activeNetwork may be the VPN on API 29/30.**
    `NetworkWatch.kt:29`, `:39` replace the heuristic with a NOT_VPN request ranked
    by Android via requestNetwork. API 31+ keeps passive best-match registration.
    CHANGE_NETWORK_STATE is the required normal permission. The request is released
    with the watcher. This can keep the best physical network available while the
    VPN runs. No app-selected Wi-Fi preference is used. Android documents why the
    default callback alone is insufficient (it can return a VPN), and requestNetwork
    reports the system-ranked best match:
    https://developer.android.com/reference/android/net/ConnectivityManager

Paths in the list above without directories are under
`app/src/main/java/com/muunkk/connectanytime/` (C paths under `app/src/main/cpp/`;
unit tests under `app/src/test/java/com/muunkk/connectanytime/`).

## Checks and limits

```sh
Vendor/build-deps.sh
./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug lintRelease
python3 scripts/verify-release.py
scripts/verify-native.sh
git diff --check
```

All PASS, exit 0. Dependency build rebuilt arm64-v8a and x86_64 after the patch
change; repeating it reports both up to date. Native dependency warnings include
OpenSSL's existing __ANDROID_API__ redefinition. No build-deps recipe edit needed.
Final Gradle run: BUILD SUCCESSFUL in 42s, 123 tasks (54 executed, 69 up-to-date).
46 JVM tests: LogicTest 13, StoreTest 1, ScreensTest 8, ScreenshotTest 24;
0 failures/errors/skips. Both lint variants: **0 errors, 22 warnings**, no baseline.
Warnings concern pinned dependency versions, synchronous prefs commits (including
one in the new StoreTest), and optional KTX style. No new suppression was added.
Release script passes signing, alignment, package/version, both ABIs, 11 JNI
entries, new create signature and all four callbacks including onLog([B)V.
Native verification passes both ABI exports, dependency allowlist and alignment.
Logs are in `/tmp/connectanytime-review-{deps,deps-stamp,build,tests,release,native}.log`.

No hardware Keystore, real login/Duo, launcher shortcut, network selection/handoff,
command/finish lifecycle race, update/boot delivery, traffic or leak checks were
run on a device. JVM tests validate the factored command and baseline decisions
and corrupt-storage handling, not Android scheduler or hardware behavior. API
29/30 requestNetwork behavior and effective meteredness still need device testing.

Implementation commits:

- `ad21b87ac218e4bf18415a849f1c7e38ff2b90bb`: body-write replay guard.
- `87568cccd0e1a8c41f3f268c6321d80163ae0bb0`: service/network/storage/JNI fixes and tests.
- Documentation is committed separately; `git log -1 --format=%H` identifies it.

### Exact iOS bridge diff (not applied)

```diff
--- a/Tunnel/ocbridge.c
+++ b/Tunnel/ocbridge.c
@@ -52,0 +53,14 @@
+// Read only these session limits; other CSTP options may contain credentials.
+static void log_server_limits(ocb_session *s) {
+    const struct oc_ip_info *ip = NULL;
+    const struct oc_vpn_option *opts = NULL;
+    static const char *limits[] = { "X-CSTP-Lease-Duration", "X-CSTP-Session-Timeout",
+        "X-CSTP-Session-Timeout-Remaining", "X-CSTP-Idle-Timeout",
+        "X-CSTP-Disconnected-Timeout", "X-CSTP-Session-Timeout-Alert-Interval", NULL };
+    if (openconnect_get_ip_info(s->vpn, &ip, &opts, NULL)) return;
+    for (; opts; opts = opts->next)
+        for (int i = 0; opts->option && opts->value && limits[i]; i++)
+            if (!strcasecmp(opts->option, limits[i]))
+                logf_(s, PRG_INFO, "Server limit %s: %s", opts->option + 7, opts->value);
+}
+
@@ -65,15 +78,0 @@
-    // The server's limits on the session (how long it may last, sit idle, or wait for
-    // the phone to come back), from the CONNECT reply's headers. Only these: other
-    // DEBUG lines can hold the password or cookie.
-    if (level == PRG_DEBUG && !strcmp(fmt, "%s: %s\n")) {
-        static const char *limits[] = { "X-CSTP-Lease-Duration", "X-CSTP-Session-Timeout",
-            "X-CSTP-Session-Timeout-Remaining", "X-CSTP-Idle-Timeout",
-            "X-CSTP-Disconnected-Timeout", "X-CSTP-Session-Timeout-Alert-Interval", NULL };
-        va_list ap;
-        va_start(ap, fmt);
-        const char *name = va_arg(ap, const char *), *value = va_arg(ap, const char *);
-        va_end(ap);
-        for (int i = 0; name && value && limits[i]; i++)
-            if (!strcasecmp(name, limits[i])) logf_(s, PRG_INFO, "Server limit %s: %s", name + 7, value);
-        return;
-    }
@@ -253,0 +253 @@
+    log_server_limits(s);
@@ -308,0 +309 @@
+    log_server_limits(s);
@@ -386,0 +388 @@
+    log_server_limits(s);
```

### Shared OpenConnect patch delta

```diff
diff --git a/Vendor/patches/openconnect-no-post-replay.patch b/Vendor/patches/openconnect-no-post-replay.patch
index 852bf99..509251d 100644
--- a/Vendor/patches/openconnect-no-post-replay.patch
+++ b/Vendor/patches/openconnect-no-post-replay.patch
@@ -2,0 +3,5 @@
+@@ -934 +934,3 @@
+-			if (rq_retry) {
++			/* A failed write may have sent part or all of a login POST.
++			 * Never replay a request with a body after an ambiguous write. */
++			if (rq_retry && !request_body_type) {
```
