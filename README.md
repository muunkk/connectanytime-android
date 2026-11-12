# ConnectAnytime for Android

One-tap Connect/Disconnect for the NYU Shanghai VPN, without Cisco Secure Client.
Save your NetID and password once. Tap Connect and approve Duo on your phone.

This is a standalone Kotlin / Jetpack Compose port of ConnectAnytime for iOS.
Release package: `com.muunkk.connectanytime`. Debug package: `com.muunkk.connectanytime.debug`.
Android 10 (API 29) or later.
Current build: **ConnectAnytime 0.1.0 (build 1)**.

## The app

The connection status fills the top of the screen: **Not connected**, **Connecting…**
(approve Duo on your phone if it asks), **Connected**, **Reconnecting…**, or
**Couldn’t connect** with the reason. **VPN session ended** means the server ended
the session and Connect will log in again. One button connects or disconnects.

Below it: your NetID and password (show/hide, Save, and Remove saved password,
which asks first), **Stay Connected**, a shortcut to **Android VPN settings** for
Always-on VPN, the server (locked while connecting or connected), **Activity** and
**About**. Activity lists events newest first under day headings with a time
column; Share and Clear (which asks first) sit in its top bar.

The UI uses Material 3 Expressive: the wallpaper's dynamic colors on Android 12 and
later, a slate-blue scheme from the app icon on Android 10 and 11, light and dark.
State changes use the expressive motion scheme. With animations removed in Android's
settings, the shape morph and loading indicator become static. The status is a
TalkBack live region. Screens work at 200% font size and in landscape, where wide
windows show the status and settings side by side.

## How it works

The app uses OpenConnect's Cisco AnyConnect protocol and OpenSSL to connect to
`vpn.shanghai.nyu.edu`. Android's default X509TrustManager verifies the certificate
chain. The original server name must match a DNS name in the leaf certificate's
SANs. Wildcards match one whole leftmost label; there is no CN or IP fallback.

The login answers username, password and second password (`push`) at most once.
A wrong password fails instead of retrying, so it cannot repeatedly count against
your account. The five patches in `Vendor/patches` also prevent replaying a login
POST, handle truncated HTTP bodies and make reconnects survive changed addresses.

Before sending the password, a 20-second deadline aborts an unreachable attempt.
Only this kind of failure can retry, at most three attempts with 3/6-second waits.
A compare-and-swap in C makes sending secrets and aborting mutually exclusive.
Once secrets have been submitted, login is never retried. A three-minute hard
deadline ends an unresponsive login. Tap Connect yourself to start another.

A successful login saves the session. Later starts try to **rejoin** it without a
password or another Duo push. The original DNS name and IP are retained, preferring
that IP when it is usable on the current network, with normal DNS answers as
fallback. A rejoin returning 401/EPERM clears the session and waits for Connect.

Only the app button, unlocked Quick Settings tile or launcher Connect shortcut
can authorize a login. Boot, process restarts and Android Always-on VPN starts
only rejoin. Repeated automatic starts without a session are counted: log the first,
notify “VPN session ended”, summarize every 30 minutes, then report the total when
a start goes ahead. Notification permission is requested on Android 13 and later when you first tap
Connect in the app. Denial does not prevent connecting. Android still lists the
foreground VPN service in its active apps panel.

## Stay Connected

On by default. After a successful connection, the foreground VPN service returns
START_STICKY so Android can restore it after process death. BOOT_COMPLETED restarts
it after first unlock if a session exists and you did not disconnect.

For stronger OS supervision, open **Android VPN settings** from the app and enable
**Always-on VPN** for ConnectAnytime. Android controls this setting; the app cannot
enable it for you. Battery restrictions, force-stop and manufacturer policies can
still delay restarts. Android's “Block connections without VPN” is optional: it
also blocks traffic while the service is stopped and can hold up Duo during login.

During an established session:

- Network changes are debounced for two seconds. Identity includes the network,
  interface and gateways, so Wi-Fi to Wi-Fi changes are detected too.
- Reconnect uses the existing cookie. A watchdog checks every ten seconds and
  re-kicks an attempt stuck for a minute.
- If OpenConnect's connection ends, the service rejoins on a fresh connection.
  Backoff is 0, 5, 10, 20, 40, then 60 seconds, capped there; 120 seconds of quiet
  resets it. A network change brings a pending attempt forward.
- The existing interface stays open while reconnecting: traffic waits in the VPN.
  No bypass is allowed. Thirty minutes unreachable on a working network stops the
  tunnel but retains its session. Offline and deep-sleep time do not count.
- Changed server settings replace the interface. Android cannot mutate an
  established VpnService.Builder, so the service detaches, rejoins, establishes the
  newest settings, then closes the old descriptor and runs on the new one.
- Disconnect sends OC_CMD_CANCEL to the live connection, clears the saved session
  and pauses automatic starts until Connect. An OS stop sends OC_CMD_DETACH and
  retains the session. A server logout cannot be guaranteed on an unreachable
  connection; the local credential is still deleted.

The full tunnel includes IPv4 and IPv6. When the server offers no IPv6 address,
a dummy IPv6 address and default route consume IPv6 instead of letting it leak.
Server split includes/excludes are supported; API 29–32 computes the route
complement because excludeRoute is available only from API 33. DNS, search
domains and MTU come from the server.

## Your password and session

The password and session JSON (cookie, URL, DNS name, IP, account and server) are
AES-GCM encrypted in private SharedPreferences. The random-nonce encryption key
stays in Android Keystore. Associated data binds ciphertext to its preference key.
No deprecated EncryptedSharedPreferences is used. Backup and device transfer are
disabled. Credential-encrypted app storage is unavailable before first unlock.

The password field never displays the saved password. OpenConnect runs at PRG_INFO:
debug form dumps could contain passwords. Only a fixed allowlist of X-CSTP session
limit headers is promoted from DEBUG. Passwords and cookies are never logged.
SIGPIPE is ignored so a reset socket fails rather than killing the VPN process.
DTLS falls back to TLS-only when setup fails.

Activity is a private file, timestamped and limited to 1,500 lines. Writes are
batched and atomically replaced. Retry countdowns are omitted. Share includes the
build description; Clear clears pending and saved lines. Long sleeps and an
unclean previous run are recorded. Logs may contain your server address, assigned
VPN addresses and server diagnostics, so review them before sharing.

## Differences from iOS

- **VpnService and a foreground notification** replace Network Extension. Android
  requires VPN consent, a status notification and a Disconnect action.
  `systemExempted` uses FOREGROUND_SERVICE and
  FOREGROUND_SERVICE_SYSTEM_EXEMPTED. Configured VPN apps qualify; no exact-alarm
  permission is needed. See [Android's foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types).
  A narrow manifest lint suppression documents the detector's alarm-only check.
- **Android Keystore and private app files** replace the shared Keychain. All
  components share one process and a StateFlow; no app-group or Keychain sharing.
  Activity is stored as a file rather than a Keychain item.
- **Quick Settings and static launcher shortcuts** replace Control Center,
  Siri and the Action button. Locked actions request device unlock. If VPN consent
  is missing, they open the app; only the app presents consent.
- **Stay Connected plus user-configured Always-on VPN** replace iOS on-demand
  rules. Turning off Stay Connected does not disable an Always-on setting the user
  selected in Android. Automatic starts still never log in.
- **Auto-connect on chosen Wi-Fi networks is omitted.** Reading SSIDs needs
  location access on Android; Stay Connected covers network changes without it.
- **Android does not expose iOS's detailed stop reason.** onRevoke/onDestroy detach
  and keep the session, including a revocation from system settings or another VPN
  taking over. Use Disconnect in the app, notification, tile or shortcut to log out
  and pause automatic starts.
- **Tunnel changes require replacement.** The old ParcelFileDescriptor stays open
  through rejoin and until the new interface is established. OpenConnect 9.21
  `library.c` initializes vpnc_script to NULL; `tun.c` borrows a supplied fd and
  only closes it when vpnc_script is set; vpninfo_free does not close it. This app
  never sets a script. Kotlin owns and closes the descriptor after native use ends.
- **DNS is per VPN interface on Android.** Server split-DNS domains participate in
  config comparison, but Android has no equivalent of iOS matchDomains. The server's
  DNS servers handle VPN DNS as a whole; search domains are applied.
- **Transport sockets and DNS need explicit physical-network handling.** Every
  OpenConnect TCP/DTLS socket goes through VpnService.protect before use. Native DNS
  uses android_getaddrinfofornetwork on the current NOT_VPN network so rejoining
  cannot wait for DNS inside its own disconnected tunnel. API 31+ watches the best
  NOT_VPN match; API 29–30 prefers a physical system default, then validated Wi-Fi.
- **Sleep detection uses clock differences.** elapsedRealtime includes deep sleep;
  uptimeMillis does not. The latter also measures the online-unreachable budget.
- **Trust uses Android's CA store**, and an explicit, tested DNS SAN matcher,
  instead of SecTrust. Bionic at API 29 lacks explicit_bzero, so the bridge provides
  a local volatile, non-optimizable implementation.

## Build and sideload

Build tools are pinned: Gradle **8.13** (wrapper and distribution checksum),
AGP **8.13.2**, Kotlin/Compose compiler **2.2.21**, Compose BOM **2026.06.01**
(Compose 1.11.4) and Material 3 **1.5.0-alpha18** for the Expressive components.
Material 3 1.4.0, which that BOM selects, has no Expressive components. Compose 1.12
and every later Material 3 1.5.0 build (alpha19 through rc01) require AGP 9.1 and
compile SDK 37, so moving to them means upgrading AGP, Gradle and the SDK together.
The module uses compile/target SDK **36**, build-tools **36.0.0** and NDK
**27.1.12297006**. JDK **21** runs Gradle; app bytecode targets Java **17**.

The native build script currently targets a **Mac host**. Install Android SDK
platform 36, build-tools 36.0.0, NDK 27.1.12297006, CMake 3.22.1, a C compiler host
toolchain (Xcode Command Line Tools), make, Perl, patch, curl, tar, and pkg-config.
The default SDK is `~/Library/Android/sdk`; override it with ANDROID_HOME.
Set `sdk.dir` in an untracked `local.properties` if Gradle cannot find your SDK.

```sh
Vendor/build-deps.sh
./gradlew assembleDebug testDebugUnitTest lintDebug
scripts/verify-native.sh
```

The script verifies pinned SHA-256s and builds static OpenSSL **3.5.9**,
OpenConnect **9.21** and libxml2 **2.14.6**, independently for **arm64-v8a** and
**x86_64**, into `Vendor/out/<abi>/{include,lib}`. Tarballs and build trees stay in
ignored `Vendor/src`. OpenSSL/OpenConnect checksums match the iOS recipe; libxml2's
checksum was checked against its [upstream checksum file](https://download.gnome.org/sources/libxml2/2.14/libxml2-2.14.6.sha256sum).
libxml2 has no Python, HTTP, lzma, ICU or iconv; FTP was removed upstream in 2.14.
zlib comes from Android's NDK sysroot. Recipe/patch stamps trigger rebuilds.
CMake statically links the dependencies into libconnectanytime.so; only Android
system libraries remain dynamically required. APK and ELF support 16 KB pages.

### Release signing

Release builds enable R8 code shrinking, optimization and obfuscation, plus resource
shrinking. `app/proguard-rules.pro` keeps the native entry points and the four
callbacks looked up by name in `jni.c`. `versionCode` and `versionName` live in
`app/build.gradle.kts`; the build description is derived from them.

On this Mac, the release key is at
`~/.config/connectanytime-android/release.jks`. Its random password and key alias
are in the gitignored `keystore.properties`. Neither file belongs in Git. Keep a
secure backup of both: updates to an installed release need the same signing key.

For a fresh checkout, copy `keystore.properties.example` to `keystore.properties`
and fill in `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. `storeFile`
should be an absolute path outside the repository; Java properties do not expand
`~`. To create a new local key, run the following and enter a strong random
password when keytool prompts. Use the same password for the key and keystore.
Do not replace an existing release key.

```sh
mkdir -p ~/.config/connectanytime-android
chmod 700 ~/.config/connectanytime-android
keytool -genkeypair -storetype JKS \
  -keystore ~/.config/connectanytime-android/release.jks \
  -alias connectanytime -keyalg RSA -keysize 3072 -validity 10000 \
  -dname "CN=ConnectAnytime Local Release"
chmod 600 ~/.config/connectanytime-android/release.jks keystore.properties
```

A release build without complete signing properties fails with a clear message;
it never falls back to debug signing. Debug builds need no release key.

```sh
./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug lintRelease
python3 scripts/verify-release.py
scripts/verify-native.sh
```

The release verification script checks the signature with `apksigner`, 16 KB APK
alignment, both ABI libraries, package names, labels and version. It inspects the
release DEX with `dexdump` and the R8 mapping to verify every native entry point
and every JNI callback name and descriptor. The Compose UI tests (`ScreensTest`)
and screenshots (`ScreenshotTest`) run on the JVM with Robolectric; no emulator is
needed. Screenshots of each main state, light and dark, are written to
`app/build/screenshots/` on every `testDebugUnitTest`.

### Sideload

- Release: `app/build/outputs/apk/release/app-release.apk` — **ConnectAnytime**.
- Debug: `app/build/outputs/apk/debug/app-debug.apk` — **ConnectAnytime Dev**.

Copy the APK to your Android phone, open it, allow “Install unknown apps” for the
app opening it, then install. Save your account, tap Connect, allow the VPN and
approve Duo. Debug and release install side by side with separate account and
session storage. Android allows only one active VPN at a time. The debug build
uses the local debug key. Release updates must use the release key above.
Nothing has been published or pushed.

## Source map

- `app/src/main/cpp/ocbridge.{c,h}`: iOS bridge port, one-answer login, cookie
  rejoin, IP pin, config snapshots, safe logging, commands and DTLS fallback.
- `app/src/main/cpp/jni.c`: JNI lifetime/thread attachment, cached callback
  methods, certificate DER transfer and socket protection.
- `app/src/main/java/com/muunkk/connectanytime/ConnectVpnService.kt`: lifecycle,
  deadlines, reconnect/rejoin, tunnel replacement and watchdog.
- `NetworkWatch.kt`, `NativeBridge.kt`: physical network identity and platform trust.
- `Logic.kt`: route parsing/complement, SAN matching, backoff, start rule,
  config comparison and log filtering; `app/src/test/.../LogicTest.kt` tests these.
- `Storage.kt`, `Notices.kt`: encrypted state, activity file, refusal summaries,
  foreground/session-ended notifications.
- `MainActivity.kt`: VPN consent, notification permission and screen navigation.
- `AppTheme.kt`, `HomeScreen.kt`, `ActivityScreen.kt`: Material 3 Expressive theme
  (dynamic and fallback colors, motion, reduced-motion handling), the status area and
  settings, and shareable activity grouped by day with confirmed clearing.
- `Controls.kt`, `ConnectApp.kt`: tile, shortcuts, boot and the process StateFlow.
- `app/src/main/AndroidManifest.xml`, `res/`: permissions, Android components,
  icons, shortcuts and backup exclusions.
- `Vendor/`, `gradle/`, root Gradle files: pinned builds and the five patches.
- `scripts/verify-native.sh`: both ABIs' exported JNI symbols, dynamic dependency
  allowlist and APK alignment.

## Verification limits

Build, JVM tests, lint and native-library inspection are recorded in
[VERIFICATION.md](VERIFICATION.md). No Android device or emulator was started.
Real NYU login, Duo, device trust, VPN traffic/leak checks, network changes,
notification/lock-screen interactions, Always-on, process death and boot need
testing on an Android device. There are no placeholder native or service paths;
these runtime behaviours are implemented but have not been exercised here.

## Licenses

ConnectAnytime's own code is MIT (see LICENSE, copied from the iOS project).
The build downloads and statically links OpenConnect (LGPL-2.1), with the five
changes in Vendor/patches, OpenSSL (Apache-2.0), and libxml2 (MIT).
If you distribute binaries, you must meet OpenConnect's LGPL terms, including the
corresponding source, changes and a practical way to relink with a modified
OpenConnect (for example, the required object files/build materials). This source
repository and its pinned build recipe alone do not replace those obligations.
Upstream licenses are available in the downloaded source trees.

Not affiliated with Cisco or NYU.
