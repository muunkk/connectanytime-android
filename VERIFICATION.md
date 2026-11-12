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
