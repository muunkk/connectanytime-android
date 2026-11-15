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

## Council round 2 fixes

Round 2 (reviewed at c3f5bd3) scored 8/10 from both reviewers, with no critical or high findings. Fixed:

- **Credential recovery no longer deletes on a transient Keystore error** (Storage.kt `recover`, `CredentialFailure`).
  - **Discarded:** only AEADBadTag, KeyPermanentlyInvalidated, malformed or truncated (IllegalArgument) and unparsable (JSON) values.
  - **Kept:** anything else is rethrown and the data stays, as iOS leaves an unreadable Keychain item.
  - **The key:** the read path never deletes it. Only a user-initiated `savePassword` replaces a broken key.
  - **Tests:** StoreTest covers transient UnrecoverableKey, InvalidKey, IllegalBlockSize and ProviderException (both values kept), the classifier, and a truncated value (discarded).
- **Every startup resume or login attempt refreshes the physical network** before it starts (`refreshNetwork`), so a network that comes back or goes during the retry waits doesn't leave DNS on a stale handle.
- **BootReceiver clears the run marker** after a reboot or app update and logs why, instead of the next start reporting "quit unexpectedly".
- **A late ARM or other non-Connect start after `finish()`** stops for its own start ID instead of leaving a started service that isn't in the foreground.
- **Session-ended screen:** the body no longer repeats the headline. The transient read-failure message says to try again.

Checks: `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug lintRelease` exit 0 (49 tests, 0 lint errors); `verify-release.py` and `verify-native.sh` pass.

## Council round 3

Round 3 (reviewed at ef25932): Claude Opus 9/10 and GPT Sol 8/10. Both are satisfied for device testing, with no critical, high or medium findings. Low findings fixed afterwards:

- **Run marker:** it carries a boot and install stamp (`RunStamp`). The next start logs "was on when the phone restarted" or "when the app was updated" instead of "quit unexpectedly". That covers Always-on VPN starts that arrive before BOOT_COMPLETED. BootReceiver no longer clears the marker, since a run that's already connected owns it.
- **Late Disconnect:** one delivered after `finish()` stops for its own start ID.
- **Error copy:** `ProviderException` maps to the read-failure message. When the failure keeps happening, the message now says to save the password again, which replaces a broken key.
- **Deferred:** an automatic start that hits a brief Keystore error doesn't retry it (Opus Low 4, "later"). This matches how unreachable starts behave.

Checks: the full Gradle command exits 0 (50 tests, 0 lint errors), and `verify-release.py` and `verify-native.sh` pass.

## USB tether proxy implementation (2026-11-12)

Implemented on the already checked-out `tether-proxy` branch. No push, main-branch
change, emulator or device operation. UI screens are deliberately left to the UI
implementation; the backend exposes `Store.shareWithTether` (default off) and
`ConnectApp.tetherShare: StateFlow<TetherShare>`.

### Files and integration

New `ProxyProtocol.kt` contains JVM-only SOCKS5/HTTP parsing, request/response
hop-header sanitation, bounded body framing, network connector seam, interface
name and subnet checks. New `TetherProxy.kt` owns USB listeners, connection limits,
relay/half-close handling, timeouts, VPN network selection, explicit-network DNS,
and socket cleanup. `ConnectVpnService.kt` only adds sharing lifecycle hooks and
publishes the installed tunnel address for matching our VPN interface.
`Storage.kt` adds the toggle, `ConnectApp.kt` adds observable sharing state and
preference observation, and `strings.xml` adds three privacy-preserving log lines.
`ProxyProtocolTest.kt` and `StoreTest.kt` cover the new backend contract. README
contains the Mac setup and scope limitations. Logic.kt, cpp/, native sockets,
physical-network tracking and the existing VPN/authentication rules are unchanged.

### Fail-closed and resource bounds

- `ProxyProtocol.kt:19`: `connectViaVpn` rejects a missing network before DNS or
  connection work. All resolution and attempts use the injected VPN connector.
- `TetherProxy.kt:112`: VPN lookup requires TRANSPORT_VPN and the interface holding
  the service's installed tunnel address. It rejects disabled sharing and inactive
  VPN states. Reconnecting can retain existing listeners; first start requires
  Connected. No active/default/physical network fallback exists.
- `TetherProxy.kt:204`: `DnsResolver.query` receives that explicit VPN Network;
  resolution has a 15-second wait and cancellation, including worker interruption
  on stop. Numeric addresses are parsed without DNS.
- `TetherProxy.kt:217`: bindSocket precedes connect on every outbound socket.
  Failures close the socket and cannot try an unbound connection. A missing VPN
  yields SOCKS 0x01 / HTTP 503. Unsupported SOCKS commands yield 0x07.
- Listeners bind only individual IPv4 addresses, never wildcard addresses. The
  allowlist is anchored `(?:rndis|ncm|usb)[0-9]+`. AOSP's
  [Tethering config.xml](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/Tethering/res/values/config.xml)
  defaults to `usb\d` and `rndis\d`, and defines a separate OEM-overridable NCM
  array (empty by default). This implementation adds numbered NCM gadgets and
  permits multi-digit indices; it does not claim these are literal AOSP defaults.
  Wi-Fi, cellular, generic Ethernet, loopback and tun names do not match.
- Only up, non-loopback, non-point-to-point USB interfaces are considered. Accepted
  peers must match that listener's actual IPv4 prefix. A rescan every two seconds
  removes old bindings and their sessions; toggles trigger reconciliation directly.
- Port 1080, 256 sessions, at most 512 worker threads, 16 KiB copy buffers,
  8 KiB lines, 32 KiB headers/trailers, 100 headers. Handshake/DNS/connect timeout
  approximately 15 seconds (handshake sweep granularity two seconds); idle sessions
  close after five minutes measured across both directions. Half-closes retain the
  other direction until EOF, timeout or stop. Stop closes all tracked sockets,
  joins accept threads, cancels DNS waits, and shuts down both executors.
- Plain HTTP uses one request per connection, rewrites absolute URI/Host, forces
  Connection: close, strips fixed and Connection-nominated hop headers on requests
  and responses, validates framing, and streams fixed/chunked bodies. Chunk trailers
  are consumed and removed. Ambiguous CL/TE framing, upgrades and unsupported
  transfer codings are refused. CONNECT and SOCKS tunnel bytes are not prefetched.
  No destinations or credentials are logged. Non-subnet refusal logs are limited
  to one per ten seconds.

### JVM test coverage

`ProxyProtocolTest` (24 tests): IPv4/domain/IPv6 SOCKS CONNECT; unsupported BIND/UDP;
invalid reserved/address fields; truncation at every handshake byte; no-auth
negotiation and success reply; HTTP CONNECT/IPv6; absolute URI/Host rewrite;
Connection-nominated Host regeneration; malformed chunk sizes; unsupported and
malformed HTTP; truncation and tunnel-byte preservation; request/response hop
headers; ambiguous framing; header limits; chunk/trailer sanitation; fixed-body
boundaries; anchored USB names; real subnet prefixes; missing VPN refusal; DNS and
connect failures without fallback; and a JVM loopback connector/half-close test.
That loopback test validates the connector seam and TCP half-closes, not Android
Network routing or the entire Android listener lifecycle.

`StoreTest` adds default-off and persistence coverage. Existing JVM Compose,
screenshot, logic and credential tests remain enabled.

### Commands and results

- `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug lintRelease`:
  PASS, BUILD SUCCESSFUL (51 seconds, 123 tasks). **75 tests, zero failures,
  errors or skips**: ProxyProtocolTest 24, StoreTest 5, LogicTest 14, ScreensTest 8,
  ScreenshotTest 24. Both lint variants: **0 errors, 24 warnings**, no baseline.
  Warnings remain dependency-version suggestions, existing synchronous preference
  commits and optional KTX edits (including the added toggle setter).
- `python3 scripts/verify-release.py`: PASS. Release signature, debug/release
  identity, both ABIs, 16 KiB APK alignment, 11 JNI entries and all four callback
  names/descriptors survive R8. No new reflection or keep rules were introduced.
- `scripts/verify-native.sh`: PASS for both ABIs, JNI exports, Android-system-only
  dynamic dependencies, and APK alignment.
- `git diff --check`: PASS.

Logs: `/tmp/tether-build-verified.log`, `/tmp/tether-release.log`,
`/tmp/tether-native.log`. Results apply to backend code; a subsequent comment only
adds the interface-source reference. No emulator/device was started. Android VPN
Network discovery/binding, USB client reachability, subnet rebinds, reconnect and
stop races, phone-side VPN DNS, actual NYU egress and macOS per-app proxy behavior
still require hardware testing. The JVM loopback test is not proof of VPN routing.

Implementation commits (documentation is a separate following commit):

- `95a708dd2d2668ce865225fd09888aa8945bdb38`: protocol, network seam and 24 JVM tests.
- `a26400d85851f807dd7f808fdc46a0d7a314ebae`: Android listener/lifecycle, Store,
  StateFlow, logs and toggle-persistence test.


## USB tether proxy review fixes (2026-11-12)

All seven review issues were verified in the implementation and fixed in local
commit `1005c6a` on `tether-proxy` (PR #1). Nothing was pushed. No emulator or
device was started. ShareSection.kt, HomeScreen.kt and existing strings were left
unchanged; strings.xml only adds the `tether_port_in_use` log line. The subsequent
UI pass must render the two new sharing states.

1. **Destination policy:** ProxyProtocol.kt:24 checks each resolved address before
   connecting; its pure `allowedDestination` predicate at line 38 rejects loopback,
   any-local, link-local, multicast and phone-assigned addresses. Mapped IPv6 is
   normalized for both classification and interface lookup. AndroidProxyNetwork
   repeats the check immediately before socket creation (TetherProxy.kt:224).
   Refusal yields SOCKS 0x02 / HTTP 403 (TetherProxy.kt:189). Other private-network
   addresses remain allowed through the VPN.
2. **Peer policy:** `allowedPeer` (ProxyProtocol.kt:50), used at TetherProxy.kt:97,
   rejects phone-assigned sources as well as the listener address even if they
   match the listener subnet. Interface-lookup failures refuse the peer.
3. **HTTP upload:** `uploadProxyRequest` (ProxyProtocol.kt:53), used at
   TetherProxy.kt:163, only half-closes SOCKS/CONNECT uploads. Plain HTTP uploads
   leave the upstream socket open while reading the response, then session cleanup
   closes it. This fixes the premature EOF that can make nginx abort a request;
   no nginx process or hardware was used for verification.
4. **Shutdown:** reconciliation (TetherProxy.kt:60) and close (line 195) hold the
   same monitor for listener-map access. Shutdown no longer relies on a timed
   executor wait for map safety, and individual listener-close failures cannot
   interrupt the remaining listener teardown. ConnectVpnService.kt:489 catches
   proxy-close failures so they cannot skip tunnel cleanup, state or stopSelf.
5. **Header bytes:** ProxyProtocol.kt:93 permits obs-text only when reading header
   fields. Names remain ASCII tokens, controls except HTAB remain invalid, and
   CRLF framing and size bounds remain enforced. Only space/HTAB are trimmed,
   preserving non-ASCII value bytes such as Latin-1 NBSP.
6. **States:** TetherProxy.kt:27 adds `WaitingForVpn` and `PortInUse`.
   Reconnect with USB present reports WaitingForVpn while listener creation waits.
   A connected VPN with USB addresses but no successfully bound listener reports
   PortInUse. Failed binds retry every two seconds, logging once per address until
   success or address removal. Off, VpnNotConnected, WaitingForUsbTether and Sharing
   retain their existing purposes. Existing listeners may survive reconnect.
7. **Protocol details:** ProxyProtocol.kt:156 preserves the client's HTTP version
   and keeps Connection: close. DNS exceptions/empty answers return SOCKS 0x04;
   refused connects return 0x05; other connect failures return 0x01 (lines 24–35).
   Android DNS timeout is treated as a failed lookup (TetherProxy.kt:219).

### Added regression coverage

ProxyProtocolTest grows from 24 to 32 tests: IPv4/IPv6 and explicit/collapsed
IPv4-mapped IPv6 destination policy, post-DNS rebinding rejection before connect,
phone-source peers inside the subnet, Latin-1 Content-Disposition and UTF-8 header
byte round trips, invalid names/controls/framing with HTAB allowed, HTTP/1.0
forwarding, SOCKS DNS/refused/other failure codes, and an actual loopback HTTP
server that checks for no EOF before responding. The existing loopback tunnel
test now exercises the shared upload helper and still verifies half-close.

TetherProxyTest adds a deterministic Robolectric race regression: pause a rescan
at listener-map traversal, start close concurrently, verify it cannot complete
until the scan is released, then verify both finish and repeat close safely.
The service's JNI initializer is suppressed in that test; it starts no native VPN.
It validates synchronization, not Android interface enumeration, network binding,
USB reachability or hardware reconnect behavior. Those still need device testing.

### Final commands

- `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug lintRelease`:
  PASS, BUILD SUCCESSFUL, 123 tasks, 22 seconds. **95 tests, zero failures, errors
  or skips** (ProxyProtocolTest 32, TetherProxyTest 1, LogicTest 14, MacCommandTest 7,
  ScreensTest 8, ScreenshotTest 28, StoreTest 5). Both lint variants: **0 errors,
  24 warnings**, no baseline added or used.
- `python3 scripts/verify-release.py`: PASS, signed release and debug identity,
  both ABIs, 16 KiB alignment, 11 JNI methods and four callback signatures after R8.
- `scripts/verify-native.sh`: PASS, both ABIs, JNI exports, Android system-only
  dependencies and APK alignment.
- `git diff --check`: PASS.

Full logs: `/tmp/tether-review-gradle-final.log`, `/tmp/tether-review-release.log`,
`/tmp/tether-review-native.log`. An initial race-test attempt failed on JNI library
loading; suppressing only that initializer in the test fixed the harness. The
complete command above was rerun afterwards and passed.

# In-app updates from GitHub releases (2026-11-12)

Version 0.1.2 (3). Checks this repo's GitHub releases, downloads a newer APK,
verifies it, and installs it with `PackageInstaller`. This section describes the code as
it is after the council rounds. The rounds are listed at the end.

## Design

- **Release selection (`ReleaseFeed.newest`).** Pure and JVM-tested. It takes the
  highest version code above the installed one. It needs:
  - an asset named exactly `ConnectAnytime-<name>-<code>.apk`;
  - a `sha256:` digest of 64 lowercase hex characters;
  - a `browser_download_url` under `https://github.com/muunkk/connectanytime-android/releases/download/`,
    ending in the asset name, without `..`;
  - state `uploaded` and a size from 1 byte to 200 MB;
  - a release page in this repo.

  Drafts are skipped. Prereleases count.
- **One thread.**
  - `Updater` runs every step on a single IO thread, in order: restore at startup,
    checks, installs and the installer's answers.
  - An atomic gate stops checks from overlapping.
  - A `CoroutineExceptionHandler` logs anything unexpected, so nothing in the updater
    can take down the process it shares with the VPN.
  - Seams for tests: `Http`, `inspect` (APK facts), `installedSigners`, the dispatcher and
    the clock.
- **Check.**
  - The release list is fetched with `X-GitHub-Api-Version: 2022-11-28` and the generic
    User-Agent `ConnectAnytime-Android`. It's capped at 2 MB and revalidated with its ETag.
  - The list is parsed before it's cached in `noBackupFilesDir/release-feed.json`, so a
    bad list isn't kept.
  - Up to date is shown only when a check found nothing newer (`upToDateAt`).
  - The next automatic check is due 6 h after the last check that worked
    (`lastUpdateCheck`). A failure resets that, so the next opening retries.
  - HTTP 403 and 429 show "limiting". Other I/O shows "couldn't reach". JSON errors show
    "couldn't read". Anything else shows "something went wrong".
- **Download.**
  - The APK streams to `noBackupFilesDir/updates/download.apk` while it is hashed. It is
    refused once it grows past the expected size, and must match the size and SHA-256
    exactly.
  - It's verified in staging, then renamed over the kept APK. An earlier verified
    download, even one with the same name, survives any failure.
  - An APK rejected for package, version or signer, or permanently by Android's installer,
    is remembered by digest (`rejectedUpdate`, `rejectedBy`) and not downloaded again. A
    check that finds only that release counts as a check that worked, so the app doesn't
    ask GitHub again at every opening.
- **Verification before every install, and on restore.**
  - Size and SHA-256.
  - `getPackageArchiveInfo`: the package must be `com.muunkk.connectanytime` and the
    version code must equal the one in the asset name.
  - The installed current signers must all appear in the archive's signers or its
    rotation history.
  - Where Android returns no signing info, the installer's own same-signer rule still
    applies.
- **Install.**
  - Abandons the app's own open sessions, then creates a `MODE_FULL_INSTALL` session with
    `setAppPackageName` and `INSTALL_REASON_USER`. On API 31+ it also sets
    `USER_ACTION_NOT_REQUIRED`, and `UPDATE_PACKAGES_WITHOUT_USER_ACTION` is declared.
  - The session ID and `updatingFrom` are saved before commit.
  - Status goes to the non-exported `InstallReceiver` through a mutable, explicit
    `PendingIntent`. The receiver holds `goAsync()` until the updater has handled it.
  - Answers for any other session are ignored. A fresh process still accepts the saved
    session's answer, because restore keeps the ID when Android no longer has that
    session.
- **Confirmation.**
  - Held in a StateFlow that `MainActivity` launches while started, taken once. The
    receiver never starts activities.
  - With no started activity, a notification is posted on the `update-confirm` channel
    (default importance). Its immutable `PendingIntent` opens the system's confirmation
    directly, so it works after process death.
  - Once that notification has been tapped or dismissed, the app doesn't open the
    confirmation again.
  - If notifications are off for the app or that channel, nothing is posted. The app then
    opens the confirmation itself when you return.
  - **Try again** appears 30 s after commit, measured on `elapsedRealtime`; earlier taps
    are ignored. Then:
    - a confirmation still open is shown again;
    - with no confirmation and the session still held by Android, it's left alone for up
      to 2 minutes, because that's a silent install being checked and optimised;
    - otherwise a new session replaces the old one.
- **Answers.**
  - Abort: Ready, with "cancelled".
  - CONFLICT (which is how a different signer comes back) and INVALID: drop the APK,
    remember it, Failed "Android didn't install the update".
  - Other failures, including INCOMPATIBLE (Android also uses it for a policy block that
    can pass): Ready, with that note. Failures are logged.
  - A newer release downloaded while an old attempt's answer is still on its way ends that
    attempt first, so the late answer can't be taken as the newer release's.
  - Success: nothing. Android stops the process. `BootReceiver` then logs "Updated from X
    to Y", posts "Updated to version …" if notifications are allowed, and clears
    `updatingFrom`.
- **VPN.** The install note uses `Controls.restartsAutomatically` (Stay Connected, saved
  session, no Disconnect), the rule `BootReceiver` uses:
  - rejoins without Duo, "if that session has ended, tap Connect";
  - turns off;
  - Stay Connected rejoins afterwards;
  - the app closes.

  An automatic start never logs in with the password (`ConnectVpnService`'s login gate).
  So an update can't cause a second Duo push.

## UI (Material 3 Expressive)

- **Update ready card under the status panel.** It shows only when an update is ready or
  installing. It's outlined on the page colour, so it never merges with the status
  panel. It has a filled Install button, or Try again while installing.
- **About group in three segments:**
  - the build;
  - a check row: idle, checking with a `LoadingIndicator`, up to date with a relative
    time, or a failure in error colour;
  - the Check automatically switch.

  While downloading, the check row becomes a `LinearWavyProgressIndicator` with a
  percentage. When an update is ready, it shows Install and What's new, the VPN note, and
  "may ask to allow installs" while that's not yet allowed. While installing, it shows
  progress and Try again.
- Stay Connected is state in `HomeScreen`, shared by the switch and the update notes.

## Checks

- **Live data.** I captured `GET /repos/muunkk/connectanytime-android/releases` on
  2026-11-12, trimmed, as `app/src/test/resources/github-releases.json`. `readsTheLiveFeed`
  picks v0.1.1 (2), digest `d29b1be5…eb90e`. A download with the same headers made one
  https→https redirect, and the bytes matched the digest. `aapt2` reads them as
  `com.muunkk.connectanytime` version 2, signer `b5e81629…080035e`, the same as this
  build's release signer.
- **`./gradlew :app:testDebugUnitTest`:** 141 tests, 0 failures.
  - `UpdatesTest` (13 tests):
    - feed selection, 15 rejection cases, the live feed and malformed feeds;
    - the `Release` round trip;
    - the updated notice on `MY_PACKAGE_REPLACED` only;
    - check-row states, Install in both places, the four VPN notes, Try again, and the
      auto-check switch.
  - `UpdaterTest` (22 tests):
    - up to date;
    - download, verify, Ready, each across a restart;
    - corrupt and oversized downloads;
    - wrong signer, package or version, rejected once;
    - signer rules;
    - a failed newer download and a broken republish both keep the verified APK;
    - stale and missing restores;
    - ETag 304, and no ETag without a cache;
    - 403 and bad lists;
    - unexpected exceptions and `Error`s;
    - one session: a double tap ignored, a silent install left alone, and a later Try
      again replacing it;
    - the notification opening the confirmation itself, and a tapped notification not
      opening it twice;
    - with notifications off, the app opening the confirmation;
    - Try again re-showing an open confirmation;
    - installer answers, including the confirmation notification;
    - a restart during an install;
    - a fresh process hearing a CONFLICT refusal;
    - retrying after a failed download right after a successful check, and auto-check off;
    - `MainActivity` opening the confirmation once;
    - `updatingFrom` plus Disconnect starting nothing.
  - `ScreenshotTest` has three update states, in light and dark.
- **`./gradlew :app:lintDebug`:** 0 errors, and no warnings in the update files.
- **`assembleRelease`:** passes, as do `scripts/verify-release.py` and
  `scripts/verify-native.sh`. The release manifest keeps `InstallReceiver` and both
  install permissions.

## Council rounds

Gemini was unavailable in every round: Cursor said Gemini 4 Carbon isn't in this week's
usage.

| Round | Opus | GPT Sol | Main findings fixed after it |
|---|---|---|---|
| 1 | 7.5 | 7 | Sessions tracked; confirmation via the activity; no crashes in the VPN process; up to date only when true; staging download; VPN note follows `BootReceiver`; copy; `Updater` tests |
| 2 | 8 | 8 | `goAsync`; the notification opens the confirmation itself; retry after a failed download; double tap; Try again re-shows the confirmation; fresh-process answers; CONFLICT is permanent; error wording; rejoin copy names the ended-session case; ETag claim corrected |
| 3 | 8.5 | 8.5 | Try again only after 30 s, and never over a silent install in progress; notifications turned off no longer swallow the confirmation; its own default-importance channel; `elapsedRealtime` for the window; a refused release doesn't trigger a check at every opening; manifest comment |
| 4 (fresh, full review before first ship) | 8.5, ship | 7, don't ship | A late answer about a replaced release could reject the newer one; `BootReceiver` waited on the disk before the VPN restart; INCOMPATIBLE no longer permanent; a damaged cached list is dropped; rate-limit copy mentions the VPN |

## Limits

Round 3 found nothing blocking, so there was no fourth round.

No Android device or emulator was available: the Android device tools on this Mac lack
the SDK command-line tools. These need checking on a device:

- the `PackageInstaller` commit;
- installs without asking on Android 12+, compared with the confirmation;
- the "Install unknown apps" detour;
- a confirmation in the background, and its notification;
- the VPN rejoining after `MY_PACKAGE_REPLACED`.

The first real test is installing 0.1.2 by hand, then updating to the next release from
inside the app.

# Hotspot sharing (2026-11-14)

The tether proxy now also serves the Wi-Fi hotspot, so an iPhone (or Mac) joined to
the phone's hotspot can use the VPN. Branch `hotspot-proxy`.

## Design

- **Separate consent.** `shareOverHotspot` is its own pref and switch, off by default.
  USB sharing from 0.1.1 (`shareWithTether`) never extends to hotspot guests.
- **What counts as the hotspot.** Only interfaces Android lists in its sticky protected
  `TETHER_STATE_CHANGED` broadcast (`tetherArray`) with a Wi-Fi tether name.
  - When Android gives no list, the hotspot is never guessed at. USB keeps its name rule.
  - When Android does give a list, USB interfaces must be on it too.
- **Never the phone's own networks.** Any interface of a ConnectivityManager network is
  excluded. The exclusion is checked before and after reading addresses.
- **Each client is checked again.** `stillServes()` runs before every accepted client:
  role, pref, interface up, exact address and prefix.
- **Newest tether list wins.** A broadcast that arrives during registration isn't
  overwritten by the older sticky value.
- **Per-link status.** `TetherShare.Active(port, usb, hotspot)` gives each link one of
  Off / Waiting / PortInUse / Unknown / Sharing. Unknown shows only when no list exists
  and a hotspot-like interface is up.
- **UI.**
  - One switch per link, each followed by its setup card.
  - The hotspot card gives Server and Port as iOS asks for them, the iPhone steps
    (including Save), and a warning that a Mac keeps the proxy on its Wi-Fi everywhere.
  - The Mac turn-off command now clears every service with the proxy on.

## Checks

- `./gradlew testDebugUnitTest lintDebug` on the branch combined with the updater: 158 tests, 0 failures; lint 0 errors.
- `scripts/verify-release.py`: all PASS, release signed with b5e81629…080035e.
- Not run on a device: hotspot detection on a real phone (including after a reboot
  before any tethering), an iPhone through the proxy, and USB regression.

## Council

| Round | Opus | GPT Sol | Blocking |
| --- | --- | --- | --- |
| 1 | 6 | 6 | Consent extended silently; AP→STA race; sticky overwrite; `ap_br_softap`; Mac Wi-Fi advice; iPhone Save |
| 2 | 8 | 9 | None. Opus's remaining items fixed in round 2 commit |
| 3 (merge check, combined with the updater) | 8.5 | 9 | None. A client accepted as its link's switch goes off no longer reaches the VPN through the other link's switch |

Gemini was not run (unavailable in the previous round).

# Sharing card, home screen widget and uplink (2026-11-14)

Version 0.1.3 (4). Branch `proxy-status`.

## Design

- **Usage counting (`ShareUsage.kt`, `UsageMeter`).**
  - Per link: client addresses with a session open or traffic in the last 60 s, the
    open session count, and bytes from clients (up) and from remote ends (down).
  - Thread-safe: sessions report from their own threads.
  - Snapshots run only on the proxy's timer thread: on every rescan (2 s), and on a
    session opening or closing (at most one queued).
  - A link without a listener is reported empty and its counts start over.
  - `TetherProxy.close()` publishes an empty `TetherUsage`.
- **Uplink.** `ConnectVpnService.underlying` publishes its transport (cellular, Wi-Fi,
  Ethernet, other) to `ConnectApp.uplink` whenever it changes. It's null when stopped.
- **Sharing card (`SharingCard.kt`).**
  - Under the status panel and the update banner, while the VPN is connected or either
    share switch is on.
  - A summary, then "Via mobile data/Wi‑Fi", then one row per link with its switch,
    status, devices, connections and traffic.
  - The whole row toggles, with the Switch role.
  - The card, the Share section and the widget all write the same prefs.
    `rememberShares` follows them with a prefs listener, so each place reflects
    changes from the others.
- **Widget (`ConnectWidget.kt`, Glance 1.2.0).**
  - **Model.** A pure `widgetModel()`. The content collects `changes()` inside `provideContent`, because Glance doesn't rerun `provideGlance` for updates during a session (45 s, plus 5 s per event). `ConnectApp` still calls `updateAll` on a debounced change, to start a session when none is open. `updatePeriodMillis` is 30 min, in case the process died.
  - **Layouts.** Responsive buckets: COMPACT 180 × 110 and WIDE 280 × 110 dp. minResizeWidth is 180 dp and minWidth 250 dp.
  - **Buttons.** 44 dp targets. On is filled; off is outlined, with the widget's own background inside the outline.
  - **Text that fits.**
    - Wide: buttons show the name and status at 100% font, just the status (the icon names the link) up to 120%, then just the icon. From 130% titles shorten and the second line goes.
    - Compact: short titles and second lines at 100% ("Tap power", "Try again", "Mobile data"), no second line above that. From 130% power moves into the button row, in tertiary when idle, and device counts go.
    - Link statuses are at most 7 characters. Counts over 9 show as "9+".
    - `WidgetTest.noTextIsCutShortInAnyStateWidthOrFont` renders 7 states × 2 networks × widths 180/250/280/330 dp × fonts 100/115/120/125/130/150/200% and fails on any ellipsized text. A widget squeezed to 110 dp is its negative control. Opus's wider harness (3,042 layouts, round 4) found nothing cut off either.
  - **Taps.** Every button goes through `ShortcutActivity` (`taskAffinity=""`), which dismisses the keyguard and decides at the moment of the tap.
    - `TOGGLE` connects or disconnects. It opens the app when consent, a NetID or a password is missing.
    - `SHARE_USB` and `SHARE_HOTSPOT` flip the switch. Until hotspot sharing has been turned on in the app once (`hotspotExplained`), the hotspot button opens the app with `EXTRA_CONFIRM_HOTSPOT`. The app then asks with the warning before turning it on, including when it's already open (`onNewIntent`). It doesn't ask on a Recents relaunch (`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`, since Android 10 and 11 resend the first intent) or once sharing is already on. `HotspotConfirmTest` covers recreation, Cancel, `onNewIntent`, Recents and already-on. Known trade-off: the CLEAR_TOP that makes `onNewIntent` work closes anything above MainActivity in its task (a pending VPN consent, the update confirmation, the share sheet), and only on that first widget hotspot tap.
    - `OPEN_TETHERING`.
    - `OPEN_APP`, from tapping the status.
    - The app opens through its launch intent with `NEW_TASK`, and Settings with `NEW_TASK`, so neither lands in the shortcut's hidden task.
  - **Colors.** Android 12+ follows the wallpaper; older versions use the brand scheme. Glance's corner radius needs API 31, so Android 10 and 11 get square buttons.
- **Copy.**
  - The iPhone card says the proxy can stay on because it only affects that hotspot.
  - The stopped-sharing help offers "turn sharing back on" as well as turning the
    proxy off.

## Checks

- `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug lintRelease`: 180 tests, 0 failures; lint 0 errors.
  - `UsageMeterTest`.
  - `WidgetTest`:
    - the model;
    - the share buttons and the first hotspot share going through `ShortcutActivity`;
    - power opening the app when it can't connect yet;
    - renders at 180 and 280 dp, in light and dark, at 115% and 200% font, and in the no-VPN and failed states.
  - `ScreenshotTest`: the card in use, ready and waiting, and off; the quick toggle moving the Share section's switch; the card hidden when idle and off.
- `scripts/verify-release.py`: all PASS at 0.1.3 (4).
- The release manifest keeps `ConnectWidgetReceiver` and Glance's trampolines.
- Council: round 1 Opus 6, GPT Sol 6; round 2 Opus 7, GPT Sol 9; round 3 Opus 8, GPT Sol 9; round 4 Opus 9 (ship), GPT Sol 9 (ship). Round 4's items are taken: status-only buttons up to 120% with "9+ using", no confirm from Recents or when already on, the risk first in the dialog, all launch flags asserted, and the dialog tests. Round 3's items are fixed: compact second lines, the fit test, status on the buttons at 115%, the hotspot confirm, the power colour in the row, the "9+" count, a softer settings button, and launch-flag assertions. Round 2's remaining items are fixed: widget text, the app's task, the stray `t/classes.jar`, outline and contrast, segmented rows, the live regions, the hotspot warning on the card, the plain uplink line, the status opening the app, and the picker preview.
- Not run on a device:
  - the widget on a launcher, including rounded corners, which Robolectric doesn't draw;
  - the device counts with a real iPhone;
  - the uplink label switching between Wi‑Fi and mobile data.

# Automatic (PAC) setup (2026-11-14)

Branch `proxy-pac`, stacked on `proxy-status`. Backend by GPT Sol, UI by Opus, both in this branch.

## Design

- **`PacFile.kt`.**
  - `Ipv4Range` (parse, contains, overlaps, mask) and `parseDirectRanges` (at most 16 ranges, each /8 or narrower).
  - `pacFile()`: ES5 `FindProxyForURL`. It strips a final dot, then sends these DIRECT:
    - plain names, `ts.net` and `*.ts.net`, and `.local`;
    - `fd7a:115c:a1e0::/48` literals, parsed in full: brackets, zone IDs, `::`, an embedded IPv4 tail, any case;
    - IPv4 literals in 100.64.0.0/10, the home ranges or the link's subnet.
  - Ranges are compared as numbers in the file itself. It never calls `isInNet` (Apple's resolves even literals), `dnsResolve` or `myIpAddress`.
  - Everything else gets `PROXY <link address>:1080; DIRECT`.
- **Serving.**
  - `ProxyProtocol.http` recognises only origin-form `GET`/`HEAD /proxy.pac`; every other origin-form request still fails.
  - `Session` answers it before any VPN lookup: 200, `application/x-ns-proxy-autoconfig`, `Cache-Control: no-cache`, `Connection: close`.
  - The file is generated per request from that binding's address and prefix and the stored home ranges.
  - It goes through the same peer, `stillServes`, session-cap and per-link checks as the proxy, and is answered only while the VPN is connected, including for a client accepted before a reconnect.
  - A PAC fetch isn't counted as a device.
- **Refusal for fallback, Automatic only.**
  - On Automatic, listeners pause (ServerSocket closed, sessions kept) while the VPN reconnects, and resume (rebind) once it's connected.
  - On Manual they keep accepting during a reconnect, exactly as before (`acceptsNow`). A change of setup triggers a rescan.
  - Sessions are owned by binding, so removing a binding still closes its sessions.
  - Pause and resume don't log start or stop.
  - Status reports a link as Sharing only while it's accepting.
- **Settings.** `Store.proxySetup` (Manual by default) and `Store.directRanges` (192.168.68.0/22 by default; invalid stored text falls back to the default).
- **Mac.**
  - `macAutoCommand` sets the PAC URL on the one service whose router is the phone, then (only if that worked) turns off this app's manual proxies there.
  - `macCommand` turns off this app's PAC URL on that service, only once the three proxies are set.
  - "This app's" means a PAC URL ending exactly in `:1080/proxy.pac`, or SOCKS that is on, on this port, and not on a loopback server (any 127.x, `localhost` or `::1` spelling) such as `ssh -D 1080`. Other proxies and URLs are untouched.
  - `macOffCommand` turns off both kinds.
- **UI (`ProxySetupUi.kt`).**
  - After the two switches, while either is on: **Set up devices**, a connected button group for Manual and Automatic.
  - Each choice shows what it does. Automatic lists what goes direct, with **Edit home network**: an inline field whose Save stays off until the text parses.
  - Each sharing link's card follows. Manual's card is unchanged. Automatic shows the URL (selectable, Copy URL), the iPhone or Mac steps, the Mac commands, and the overlap warning.
  - The stopped-sharing help has an Automatic version.

## Checks

- `./gradlew assembleRelease testDebugUnitTest lintDebug lintRelease`: 199 tests, 0 failures; lint 0 errors. `verify-release.py` passes.
  - The generated file (`app/build/sample-proxy.pac`, written by `PacFileTest`) also runs in macOS's JavaScriptCore `jsc`. Its helper functions throw on `isInNet` and `dnsResolve`, and it gives the intended answer for 20 sample hosts.
  - `PacFileTest` runs the generated file in Rhino. Its `isInNet` throws on non-IP hosts, and its `dnsResolve` and `myIpAddress` throw.
  - `TetherProxyTest` covers:
    - on Automatic, real loopback TCP refusal while reconnecting and acceptance after resume;
    - on Manual, the port staying open while reconnecting;
    - a resume that hits "port in use", and recovery;
    - open sessions kept;
    - closing on binding removal;
    - GET and HEAD PAC serving without a VPN lookup.
  - `MacCommandTest` runs the commands in bash and zsh: setting Automatic, turning off both kinds, switching modes in both directions, and leaving other proxies and URLs alone.
  - `ScreenshotTest` covers:
    - Manual stays the default with its card unchanged;
    - Automatic's URL and what goes direct;
    - the overlap warning;
    - editing and validating the home ranges.
- A Material 3 `AlertDialog` containing an `OutlinedTextField` never goes idle under Robolectric at the screenshot size (411 × 891 dp, xhdpi), even bare. That's why the home ranges are edited in place, not in a dialog.
- Not run on a device: see the device checklist in the PR.

## Council

| Round | Opus | GPT Sol | Fixed after it |
| --- | --- | --- | --- |
| 1 | 7 | 7 | Manual keeps accepting during a reconnect; trailing dots; full IPv6 parsing; no `isInNet`; Mac lookalike URLs, local SOCKS and failed Manual setups left alone; PAC only while connected; choice stacks at large fonts; overlap warning wording and position; Mac help text for Automatic; hedged copy; custom-domain caveat; checklist |
| 2 | 8.5, ship | 9, ship | Nothing blocking. Taken: overlap wording for USB; the choice stacks from 115% and its labels never wrap; the Automatic Mac command sets the URL before turning Manual off; any loopback spelling counts as local; "set every device the same way"; clearer wording on when Automatic goes direct; Manual help; a test for switching back to Manual; README on the turn-off command |
| 3 | 9, ship | 9, ship | Nothing blocking. A test comment corrected; two cosmetic notes left for later (the Manual screenshot's scroll, two small grey paragraphs in a row). |

# Keep the session through reconnects; rejoin without Duo (2026-11-15)

## Problem

On the phone the VPN dropped within minutes and asked for Duo again. A short blip set off
Dead Peer Detection, OpenConnect reconnected in-process ("Reconnected with the existing
session"), and in the same second the service saw different tunnel settings, sent
OC_CMD_DETACH to rebuild the interface ("Software caused connection abort") and rejoined
from the saved session on a new vpninfo. The server answered 401: session over. Every
rejoin from the saved session in that log got 401 (11 of 11, including one 130 s after
login), and every in-process reconnect worked (20 of 20).

## Cause

- **Rejoins.** The session cookie carries OpenConnect's STRAP private key
  (`openconnect_strapkey=`, auth.c). Every CONNECT on a live vpninfo, including the
  first one after login (`vpninfo->cookies` is still set, so `strap_rekey = 1`), signs
  with the old key and hands the server a new one (`append_strap_verify`). But
  `vpninfo->cookie` is never rebuilt, so the cookie saved at login presents a key the
  server no longer holds. That the ASA enforces this is the leading hypothesis; it
  fits every 401 in the log, but only the phone can confirm it.
- **The drop.** The settings comparison was exact on every field. The log has no
  "Reconnect gave a new Legacy IP address" line, so the address didn't change;
  something the interface doesn't need did (most likely the MTU, recalculated per
  transport). The service then threw away a live session to rebuild it.

## Fix

- `openconnect-strap-cookie.patch`: after each successful CONNECT, put the key the
  server just accepted back into the cookie. Also wipes and frees the DER copy of the
  key that `append_strap_privkey` leaked on every call. Logs only at debug level, which
  the bridge drops.
- Bridge: copies url, cookie, DNS name and IP on OpenConnect's thread after
  connect, resume and every reconnect, under a mutex. `saved()` reads that copy and
  wipes it, instead of reading `vpninfo` while the mainloop may be replacing it.
- Service: saves the session again after every in-process reconnect (same encrypted
  Store), so a force-stop or update rejoins with the current key.
- A reconnect with new settings keeps the session and its connection:
  - `TunnelConfig.needsNewInterface` ignores what the interface doesn't use (server
    address, split DNS), compares routes as sets, and accepts a larger MTU. DNS servers
    and search domains stay ordered (priority). A smaller MTU or a new address,
    netmask, IPv6 address, DNS, search domains or route set still needs a new interface.
  - When it does: establish the new interface while the old one is open, then
    `NativeBridge.SWITCH_TUN` writes `OC_CMD_STATS` to the command pipe. Its handler
    runs on OpenConnect's thread between packets, asks `nextTun()` for the fd and calls
    `openconnect_setup_tun_fd` (unregisters the old fd, never closes it). Then
    `OCB_TUN_SWITCHED` lets the service close the old descriptor. There's no reconnect
    and no rejoin. `TunHandoff` tracks which fd OpenConnect owns.
  - `openconnect-switch-tun.patch`: if vhost-net were in use, drop it before setting
    up the new fd rather than leak it. (This phone can't open /dev/vhost-net.)
  - DETACH and rejoin remain only for when Android refuses the new interface.
- The log names the changed fields, never their values except the MTU:
  "The reconnect brought new tunnel settings (MTU 1390 → 1406); the VPN interface still fits".

## Checks

- `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug lintRelease`:
  BUILD SUCCESSFUL. 207 tests, 0 failures. Lint 0 errors (47 warnings, none in changed
  files). Both ABIs' dependencies were rebuilt by `build-deps.sh`; all seven patches
  apply to the 9.21 tarball without fuzz or warnings.
- `python3 scripts/verify-release.py`: PASS, version 0.1.4 (5), all 11 native methods
  and all five callbacks (now including `nextTun()I`) after R8.
- `scripts/verify-native.sh`: PASS for arm64-v8a and x86_64.
- LogicTest covers which changes need a new interface (MTU up, gateway, split DNS and
  route order don't; DNS order and MTU down do), the change log naming fields without
  values, and the handoff: an old fd stays open until the switch is reported, a second
  change before the switch replaces the first, a run that ends before switching
  leaves the newest interface for the rejoin, and stopping closes everything.

## Council round 1 fixes

Opus 8/10 ship, GPT Sol 7/10 don't ship. Fixed:

- **An accepted rekey could be lost** (Sol, blocking). OpenConnect can get the 200,
  so the server holds the new key, and then fail on the rest of the headers. The
  cookie is now refreshed right after the 200 is parsed. The bridge also snapshots
  when `ocb_run` ends, and the service saves that snapshot before freeing the native
  state, except after a 401, a logout or server termination (`RunEnd.keepsSession`).
  `saveSession` still clears the session after logout or Disconnect.
- **A CONNECT that sent a new key and heard nothing.** Implemented Sol's option:
  `openconnect_strap_candidate_cookie` (patched in) gives the cookie with the key in
  memory. The saved session keeps it as `candidate` when it differs from the accepted
  one. A rejoin that gets 401 with the accepted key tries the candidate once. That's
  one extra CONNECT at most, only in this case, and never a password or Duo.
  Sessions saved before 0.1.4 have no candidate.
- `ocb_run` returns -EIO, not -1, if it can't set up the tun at the start: -1 is -EPERM,
  which now means "session over, don't save".
- Opus:
  1. A switch reported during a newer reconnect no longer says Connected.
     `TunHandoff.reconnecting()` clears the wait, so `down`, the 60 s kick and the
     30-minute give-up keep running. A reconnect that overtakes an offer that hasn't
     been taken yet waits for it.
  2. The interface switch happens before the Keystore save.
  3. No `OCB_TUN_SWITCHED` when `setup_tun_fd` fails; the run detaches and the rejoin
     closes both fds.
  4. `buf_alloc()` returning NULL is handled.
  5. The lock comment now says a close can hold the kernel's network configuration
     lock for a few milliseconds.
  6. After a switch, vhost-net is only tried again if the old tun used it, so the
     error-level "Failed to open /dev/vhost-net" line appears once per session, not
     on every switch.
  7. Tests: below.

### Round 1 checks

- `scripts/test-strap-cookie.sh`: builds patched OpenConnect 9.21 for macOS against
  Homebrew OpenSSL 3.5.9 (the version Android uses) and runs
  `Vendor/tests/strap-cookie-test.c`. It runs the real `start_cstp_connection` over an
  in-memory TLS 1.3 session (STRAP signs its exporter), with scripted replies:
  - 200 then truncated headers: the cookie carries the key the 200 accepted;
  - a rejoin from that cookie (fresh vpninfo: set cookie, split, ingest) presents
    exactly that key;
  - no reply: the cookie keeps the accepted key, and the candidate carries the new
    one;
  - a cookie without a STRAP key is left alone.

  11 checks pass. Negative control: without the early refresh, 4 of them fail.
- LogicTest, new cases:
  - SWITCHED after a newer RECONNECTING doesn't bring the VPN up;
  - a reconnect overtaking an untaken offer waits for it;
  - a stop while a switch is offered closes it;
  - a SWITCHED from a run that already ended changes nothing;
  - which run endings keep the session.
- StoreTest: the candidate survives the session's JSON, and 0.1.3's format reads
  with none. Robolectric has no AndroidKeyStore, so encryption isn't covered.

## Council round 2 fixes

Opus 8/10 ship, GPT Sol 7/10 don't ship. Fixed:

- **Save on every run exit** (Sol P1 #2, Opus 1). errno can't tell the cases apart.
  A DETACH during a reconnect's sleep or a blocking read returns -EINTR, and the
  30-minute give-up always lands mid-reconnect. `install_vpn_opts` returns -EPERM after
  a 200 when a netmask goes missing. So the errno exclusion (`RunEnd`) is gone: every
  run exit saves its snapshot through `saveSession`, which clears the session instead
  after logout or Disconnect. A session the server ended gets a 401 on the next
  rejoin, which clears it. The save is guarded by `session == s`. The failure message
  is now "Couldn't save the session".
- After a failed in-place switch, the run end logs "Ended the connection to set up the
  VPN interface again; rejoining the session", not "The connection ended (Interrupted
  system call)".
- **No unresolved key falls out of the saved pair** (Sol P1 #1, Opus 6). With accepted
  key A, a CONNECT presents B and its 200 is lost. OpenConnect's own retries used to
  rekey to C, so if C never arrived the app saved {A, C} while the server held B.
  The patch now sets `strap_unresolved` when it sends a rekeying CONNECT and clears it
  on a 200. While it's set, CONNECTs don't rekey: they sign with B and present B. If
  the server holds B, that succeeds and B becomes accepted. If it holds A, it gets a
  401, the run ends, and the saved {A, B} rejoins with A.
- **First start after updating from 0.1.3** (Opus 2). The automatic start's 401 left
  the VPN quietly off. Now a start whose saved session is refused, and that can't log
  in, posts the same "VPN session ended" notice as a rejoin (`StartRule.noticeEnded`).
  When automatic starts may log in is unchanged.

### Round 2 checks

- `scripts/test-strap-cookie.sh`: 17 checks pass. New: after an unanswered CONNECT,
  two more attempts present the same unresolved key, and the saved pair stays
  {accepted, unresolved}. A 200 on the retry makes it the accepted key with no
  candidate, and the next CONNECT rekeys. Negative control: letting retries rekey
  again fails 4 checks.

## Device checklist (0.1.4 (5))

- [ ] **After installing 0.1.4, open the app and approve Duo once.** The session saved by
      0.1.3 carries a stale key; if Stay Connected starts first, it now shows "VPN
      session ended" instead of staying quietly off.

- [ ] Connect, then cause a short blip (airplane mode for ~5 s, or walk out of Wi‑Fi
      range): the VPN shows Reconnecting… then Connected; no Duo. The log shows
      "Reconnected with the existing session" and no "rejoining the session".
- [ ] Switch Wi‑Fi ↔ mobile data a few times: the VPN stays up. If the log shows
      "moving to a new VPN interface", it's followed by "Moved to the new VPN interface",
      and traffic (a web page, a hotspot client) keeps working.
- [ ] Force-stop the app (Settings → Apps), then let Stay Connected or Always-on start
      it, or tap Connect: it rejoins without Duo. Log: no 401.
- [ ] Update the app over this build (the next release, or `adb install -r`): it rejoins
      without Duo.
- [ ] After a blip and an in-process reconnect, force-stop again: it still rejoins
      without Duo (the re-saved key).
- [ ] Disconnect still logs out, and Connect needs Duo afterwards.
- [ ] Rarely seen, so check only if it happens: "The saved session key was refused;
      trying the newer one" followed by a successful rejoin.

# Battery drain measurement (2026-11-15)

## Problem

Sharing the VPN over the hotspot (Sharing card, tether proxy, Automatic PAC) empties
the battery in about two hours, and there was no data to optimize against.

## What this adds

An opt-in recorder and report screen (home screen → Battery drain, under Activity). Design, feasibility
and accuracy limits, ranked drain suspects with file:line evidence, quick wins and the
adb cross-check: `docs/battery-measurement.md`.

- `BatteryRecorder` (`battery-rec` HandlerThread): one row a minute while unplugged and
  the VPN is on, plus event rows on screen, power, hotspot, sharing, uplink, VPN and
  transport changes. No wakelock. Situation generations (`gen`, `pgen`,
  `transport_gen`) make intervals that span unseen transitions "Mixed".
- `BatteryStore` (`battery-io`): CSV in `filesDir/battery`, 4 MiB cap with atomic
  rotation, tail repair, idempotent retries, export through a FileProvider limited to
  `cache-path battery-export/`.
- `BatteryReport`: pure functions. Charge-counter and level paths, gauge-step range
  (a sampling estimate, root-sum-square over windows), readiness, watts from the energy
  counter or charge × voltage, CPU share over common valid intervals, and a comparison
  whose sides, readiness and uplink come from the same windows.
- Edits to existing code, all checked in the diff: thread names (`oc-run`,
  `proxy-timer`, `proxy-worker`, `proxy-accept`); status counters in
  `ConnectVpnService.state()` (one `getAndSet` and two atomic increments); `ProxyWorkers`
  (same pool parameters, per-task CPU meter created only while measuring); the
  `ProxyCounters.traffic` hook beside `meter.traffic`; an exception-contained
  `ActivityLog` observer. No change to connect, reconnect, rejoin or native code.
  `VpnStatusContractTest` pins the status literals the counters compare against.
- Debug builds enable pseudo-locales (for the RTL screenshot); release is unchanged.

## Checks

- `./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease`:
  449 tests, 0 failures; lint 0 errors (warnings unchanged in kind; 2 `UseKtx` in
  `BatteryControl.kt`).
- `scripts/verify-release.py`: all PASS (signature, 16 KB alignment, versions 0.1.4 (5)
  unchanged, JNI names after R8).
- Screenshots in `app/build/screenshots/battery-*` and `home-battery-*` were reviewed by
  eye. They are written, not compared, as with the existing `ScreenshotTest`.
- Not tested on a device.

## Device checklist (battery measurement)

- [ ] Overhead: same proxied download and a many-short-connections page load on 0.1.4,
      this build with measuring off, and with it on. Process CPU (`top -H`, batterystats
      UID CPU) and throughput. Target under 0.5 % of one core extra, no throughput change.
- [ ] VPN reliability with measuring on: Wi‑Fi ↔ mobile, a forced reconnect (airplane mode
      ~5 s), a tunnel switch, force-stop and rejoin without Duo. All as in 0.1.4.
- [ ] Export and Clear during a reconnect.
- [ ] TalkBack: with the Battery drain screen open, plug the charger in and out, and turn
      the VPN off: each is announced once; turning the switch yourself isn't announced twice.
- [ ] The comparison reaches ready on the phone (an hour of screen-off sharing in use, an hour
      with Share over hotspot off), and the sentence agrees with the two side rows.
- [ ] batterystats cross-check (README, Ground truth steps): drain and UID CPU agree within
      the gauge's resolution.
- [ ] The CSV contains no hostnames, IP addresses or device names.
