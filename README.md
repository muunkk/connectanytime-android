# ConnectAnytime for Android

One-tap Connect/Disconnect for the NYU Shanghai VPN, without Cisco Secure Client.
Save your NetID and password once. Tap Connect and approve Duo on your phone.

This is a standalone Kotlin / Jetpack Compose port of ConnectAnytime for iOS.
Release package: `com.muunkk.connectanytime`. Debug package: `com.muunkk.connectanytime.debug`.
Android 10 (API 29) or later.
Current build: **ConnectAnytime 0.1.4 (build 5)**.

## The app

The connection status fills the top of the screen: **Not connected**, **Connecting…**
(approve Duo on your phone if it asks), **Connected**, **Reconnecting…**, or
**Couldn’t connect** with the reason. **VPN session ended** means the server ended
the session and Connect will log in again. One button connects or disconnects.

Under it, while the VPN is connected or sharing is turned on, the **Sharing** card shows
whether the VPN is being shared and with whom:
- whether the phone is online **via mobile data**, **Wi‑Fi** or Ethernet (the VPN, and so
  everything shared, goes out over it);
- for USB tethering and the hotspot, each with its own switch: off, waiting for
  tethering, on at its address, or why it can't start;
- while a link is sharing, the devices on it (by address), their open connections, and
  the data down and up since it started sharing.

A device counts once a connection of its has gone through the VPN, while that is open, and
for a minute after its last traffic. Refused attempts don't count.
The card's switches are the same settings as the **Share the VPN** section and the widget.

### Home screen widget

Add **ConnectAnytime** from the launcher's widgets. It shows whether the VPN is
connected and which network the phone is on. It also has:
- a power button that connects or disconnects;
- **USB** and **Hotspot** buttons that turn sharing on or off (filled when on, outlined
  when off) and show what each link is doing, or how many devices are using it;
- a button for Android's hotspot settings, which also shows whether Android's hotspot
  is on: a **dot** (not a notification) means on, a **slashed** sign means off, and the
  **plain** sign means the widget doesn't know (often the case with the hotspot off; see
  below). TalkBack says "Android hotspot, on" or "off" when it knows.

Android doesn't let apps turn the hotspot itself on or off, so that button opens
Android's tethering screen. Use Android's own Hotspot tile in Quick Settings for one tap.

How the widget knows (`HotspotWatch.kt`), whether or not the VPN or sharing is on. It
doesn't poll; Android tells the app when something changes:
- **On** comes from Android's tethering state: a Wi-Fi interface it is tethering on (the
  sticky `TETHER_STATE_CHANGED` broadcast; on Android 16, its public tethering callback).
- **Off** needs the hotspot itself to report it went off (`WIFI_AP_STATE_CHANGED`, which
  needs the no-prompt `ACCESS_WIFI_STATE` permission). An empty tethering list isn't
  enough: it also looks empty while the hotspot is starting. On Android 12L and later
  Android doesn't keep that broadcast, so the widget can only tell the hotspot is off if
  the app saw it go off. After a restart it shows the plain sign until then. While the VPN
  is connected the app keeps running and follows both.
- It shows the plain sign when a work policy restricts tethering, while the hotspot is
  turning on or off, when what Android reported doesn't add up (say, tethering heard
  after the hotspot said it went off), while a Wi-Fi interface is in an error state, and
  right after you tap the button, until Android reports again or the app looks again
  15 seconds later. If Android pauses the idle app before then, the plain sign stays
  until the next report or refresh.

Limits: if Android freezes or stops the idle app (usual with the VPN off), the widget
keeps showing what it last saw. Android then holds the app's broadcasts, so a hotspot
switched from Quick Settings meanwhile shows up the next time the app runs or the widget
refreshes (about every 30 minutes, which Android may delay). A policy that restricts
tethering and is lifted again while the app isn't running can leave an old "on". An
Android build that names its hotspot interface like a USB, Bluetooth or Ethernet one
would be misread, and one whose hotspot broadcast doesn't say which hotspot it is
(AOSP always does) would only ever show the plain sign. Only interface names, hotspot
states and whether tethering is restricted are read: no network names, devices or
addresses, and nothing is stored or sent.

Tapping the status opens the app. It's 4 × 2 by default and resizes down to about
3 × 2 (180 dp), where the buttons lose their words. With a larger font, titles shorten
and the buttons lose their words; at 130% and above the second line of text goes too,
rather than pushing the buttons out. Android 10 and 11 draw the buttons square. At
3 × 2 with a font of 130% or more, the hotspot settings button goes, and with it the
hotspot's sign; TalkBack still says the hotspot's state on the Hotspot button.

Every widget button goes through a small invisible screen, which first unlocks the
phone if needed and decides what to do at the moment you tap, so a widget that hasn't
redrawn yet can't do the opposite.
- Connect is a tap you make, so it may log in and send a Duo push, like the app's
  button. It opens the app instead when VPN permission, a NetID or a password is
  missing.
- The first time you turn on hotspot sharing, it opens the app instead, where the
  switch explains that anyone who joins the hotspot can use the VPN. After that, the
  widget turns it on and off directly.

The widget redraws when the VPN, sharing, the switches or Android's hotspot change.
If Android stops the app, the widget can show the old state until its 30-minute
refresh or the next time the app starts.

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
your account. The seven patches in `Vendor/patches` also prevent replaying a login
POST, handle truncated HTTP bodies, make reconnects survive changed addresses, keep
the saved session's STRAP key current and let a live session switch tun interfaces.

Before sending the password, a 20-second deadline aborts an unreachable attempt.
Only this kind of failure can retry, at most three attempts with 3/6-second waits.
A compare-and-swap in C makes sending secrets and aborting mutually exclusive.
Once secrets have been submitted, login is never retried. A three-minute hard
deadline ends an unresponsive login. Tap Connect yourself to start another.

A successful login saves the session. Later starts try to **rejoin** it without a
password or another Duo push. The original DNS name and IP are retained, preferring
that IP when it is usable on the current network, with normal DNS answers as
fallback. A rejoin returning 401/EPERM clears the session and waits for Connect.

The session cookie carries OpenConnect's STRAP key. Every CONNECT on a live session,
including the first after login, replaces that key on the server, but OpenConnect
9.21 never updates the cookie, so a cookie saved at login could never be rejoined
(the server answered 401). `openconnect-strap-cookie.patch` writes the key back into
the cookie as soon as the server's 200 arrives, even if the rest of the response then
fails. The bridge copies the cookie on OpenConnect's thread after every connect,
resume and reconnect, and when a run ends. The service saves it after every
in-process reconnect and whenever a run ends, in the same encrypted store (cleared
instead after logout or Disconnect). It is never logged.

A CONNECT can also send a new key and get no reply: the server may or may not hold
that key. Until a 200 settles it, every further CONNECT presents that same key
rather than a newer one, so the server always holds one of two keys. The saved
session keeps both, the accepted key and that candidate. A rejoin tries the accepted
one, and only if it gets 401, the candidate: one more CONNECT at most, never a
password or a Duo push. `scripts/test-strap-cookie.sh`
builds the patched OpenConnect for the Mac and runs `Vendor/tests/strap-cookie-test.c`,
which drives the real CONNECT code over an in-memory TLS session to check both cases.

Only the app button, unlocked Quick Settings tile, launcher Connect shortcut or the
widget's power button can authorize a login. Boot, app updates, process restarts and Android Always-on
VPN starts only rejoin. Repeated automatic starts without a session are counted: log the first,
notify “VPN session ended”, summarize every 30 minutes, then report the total when
a start goes ahead. Notification permission is requested on Android 13 and later when you first tap
Connect in the app. Denial does not prevent connecting. Android still lists the
foreground VPN service in its active apps panel.

## Stay Connected

On by default. After a successful connection, the foreground VPN service returns
START_STICKY so Android can restore it after process death. BOOT_COMPLETED restarts
it after first unlock if a session exists and you did not disconnect. App updates
use the same consent and Stay Connected checks and only rejoin a saved session.

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
- A reconnect that brings different settings keeps the session and its connection.
  The log names the fields that changed (values only for the MTU). If the current
  interface still fits (only the server address, split DNS, route order or a larger
  MTU changed), it stays. Otherwise the service establishes a new interface while
  the old one is still open, and the bridge sends OpenConnect an `OC_CMD_STATS`
  command. Its handler runs on OpenConnect's own thread between packets, takes the
  new fd and calls `openconnect_setup_tun_fd`, which unregisters the old one. Only
  then does the service close the old descriptor. No reconnect, no rejoin. The status
  says Connected once OpenConnect has switched, unless a newer reconnect started in
  the meantime. If Android refuses the new interface, the service detaches and rejoins
  the saved session instead. After a switch, OpenConnect only tries vhost-net again if
  the old interface used it.
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
Unreadable credentials are discarded with a secret-free diagnostic; an unreadable
session becomes no session, and an unreadable password must be saved again.
No deprecated EncryptedSharedPreferences is used. Backup and device transfer are
disabled. Credential-encrypted app storage is unavailable before first unlock.

The password field never displays the saved password. OpenConnect runs at PRG_INFO:
debug form dumps could contain passwords. Only a fixed allowlist of X-CSTP session
limits is read from the negotiated CSTP options after connection and reconnection.
Passwords and cookies are never logged.
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
  until OpenConnect has switched to the new interface (or, on a rejoin, until the new
  interface is established). OpenConnect 9.21
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
  NOT_VPN match; API 29–30 requests the system-ranked best NOT_VPN match
  (CHANGE_NETWORK_STATE). The first callback establishes a baseline without
  triggering a reconnect. VPN meteredness follows the selected underlying network.
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

## Updates

From 0.1.2 the app updates itself from this repo's GitHub releases (`Updates.kt`,
`UpdateSection.kt`).

- **When it checks.** On opening the app, at most every 6 hours after the last
  check that worked, while **Check automatically** (under About) is on. A failed check is
  tried again the next time you open the app. You can also tap **Check for updates**.
  - It reads `api.github.com/repos/muunkk/connectanytime-android/releases`,
    prereleases included, with a generic User-Agent and no account.
  - It revalidates with the list's ETag, so an unchanged list isn't sent again. Without
    an account, GitHub allows 60 requests an hour per address. That could run out on the
    VPN's shared address; the app then says GitHub is limiting checks.
  - The app's traffic goes through the VPN when it's connected.
- **What it accepts.** The highest build number above this one among release assets
  named `ConnectAnytime-<versionName>-<versionCode>.apk`, served from this repo's
  release downloads, with a GitHub `sha256:` digest. Drafts and assets without a digest
  are ignored, so publish releases with `gh release create` or the web upload, which
  record one.
- **Before installing.**
  - The download goes to a staging file in the app's no-backup storage and must
    match the digest and size exactly.
  - Android must then read it as `com.muunkk.connectanytime`, with the expected version
    code, signed with this app's certificate. Anything else is deleted, and that
    release isn't downloaded again.
  - Only then does it replace an earlier verified download. A failed download never
    costs you one that was ready.
  - Android's installer also refuses an update signed with any other key.
- **Installing.**
  - An **Update ready** card appears under the status panel, and the About section
    has **Install** and **What's new**. Install hands the APK to `PackageInstaller`.
  - On Android 12+, Android can let an app update itself without asking
    (`USER_ACTION_NOT_REQUIRED`, `UPDATE_PACKAGES_WITHOUT_USER_ACTION`). Otherwise it
    shows its confirmation, and may first ask you to allow installs from ConnectAnytime.
  - The confirmation is opened by the app's screen. If the app isn't in front, a
    notification opens the confirmation itself, even if the app has been closed since.
  - **Try again** appears 30 s after Install. It shows a confirmation that's still open
    again. With no confirmation, it leaves alone a session Android is still working on, for
    up to 2 minutes, since that's a silent install. Otherwise it replaces the attempt, and
    answers from replaced attempts are ignored.
  - A refusal that can never succeed (a different signer, a downgrade, an unsupported SDK
    or a broken package) drops the APK, and that release isn't downloaded again.
  - Releases must keep a current targetSdk for installs without asking to keep working.
- **The VPN during an update.** Android stops the app to replace it. The note on the
  card says what happens, using the same rule as `BootReceiver`
  (`Controls.restartsAutomatically`):
  - With Stay Connected on, a saved session, and no Disconnect, the VPN restarts
    afterwards and rejoins without Duo. An automatic start never logs in with the password.
    If the server has ended that session meanwhile, it stays off and you tap Connect.
  - Otherwise it stays off until you tap Connect.

  The updated app logs "Updated from … to …". If notifications are allowed, it also posts
  "Updated to version …", since Android doesn't reopen it.

To release: bump `versionCode` and `versionName` in `app/build.gradle.kts`, build and
verify, then attach `ConnectAnytime-<versionName>-<versionCode>.apk` to a new tag.

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
- `SharingCard.kt`, `ShareUsage.kt`: the Sharing status card, and per-link device,
  connection and traffic counts from the proxy.
- `ConnectWidget.kt`, `res/xml/connect_widget_info.xml`: the Glance home screen widget.
- `PacFile.kt`, `ProxySetupUi.kt`: the Automatic setup's PAC file, its ranges, and the
  Manual/Automatic choice and cards.
- `Updates.kt`, `UpdateSection.kt`: GitHub release check, verified download,
  PackageInstaller session and its status receiver, and their UI.
- `app/src/main/AndroidManifest.xml`, `res/`: permissions, Android components,
  icons, shortcuts and backup exclusions.
- `Vendor/`, `gradle/`, root Gradle files: pinned builds and the seven patches.
- `scripts/verify-native.sh`: both ABIs' exported JNI symbols, dynamic dependency
  allowlist and APK alignment.
- `scripts/test-strap-cookie.sh`, `Vendor/tests/strap-cookie-test.c`: the STRAP
  cookie patch against the real CONNECT code, on the Mac.

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

## Share the VPN over USB tethering or the hotspot

The tether proxy lets a Mac on USB tethering, or an iPhone or Mac on the phone's
Wi-Fi hotspot, use the phone's VPN without rooting Android. Android normally sends
tethered traffic directly to the upstream network, outside the VPN. This feature
instead serves SOCKS5 and HTTP (including HTTPS CONNECT) on the phone's USB and
hotspot addresses, port **1080**, and binds outbound TCP and DNS to this app's
VPN network. Plain HTTP keeps the client's HTTP version and closes after the
response; only SOCKS and CONNECT tunnels use TCP half-close. HTTP header values
preserve non-ASCII bytes. SOCKS reports DNS failure as host unreachable (0x04),
connection refusal as 0x05, and other failures as 0x01. It refuses connections if that network disappears. TCP only: SOCKS4
and UDP ASSOCIATE are unsupported.

In the app, under **Share the VPN**, turn on **Share over USB tethering**, **Share
over hotspot**, or both, then turn on that tethering in Android (the app links to it).
They are separate switches, both off by default: anyone who can join the hotspot can use
the proxy, so USB sharing (from 0.1.1) never turns on hotspot sharing. While the VPN is
connected, each link that is sharing shows a card under its switch with the proxy
address, for example `192.168.42.129:1080` over USB. Interfaces are rescanned every two seconds and on
every change in Android's tether state. The address changes when Android picks a new tether
subnet, and the app then shows the new one. Each switch says, for its own link, whether it is
waiting for tethering, waiting for a reconnecting VPN, blocked by another app on port
1080, or (hotspot only) can't start because Android doesn't say which Wi-Fi is the hotspot. Bind
failures are retried every two seconds and logged once per address until recovery
or removal.

On an iPhone on the hotspot: **Settings → Wi-Fi → ⓘ next to the hotspot → Configure
Proxy → Manual**, with the Server and Port shown in the hotspot card and
Authentication off, then tap **Save**. iOS keeps this per Wi-Fi network, so only the
hotspot is affected, and you can leave it on to use the VPN whenever you're on this
hotspot. While it's on, browsing on the hotspot needs sharing on here. To use the
hotspot without the VPN, set it back to **Off** and tap Save.

### Manual or Automatic setup

Under **Set up devices**, choose how the other device finds the proxy. **Manual** is the
default and works as before: the device sends everything that follows its proxy setting
through the VPN, including tailnet and home addresses, which the VPN can't reach.

**Automatic** gives the device a proxy auto-config (PAC) file from this phone, at
`http://<address>:1080/proxy.pac`, so the tailnet and home network keep working while
browsing uses the VPN.
- **Direct, not through the VPN:**
  - names without dots, such as `truenas`;
  - `*.ts.net` and `.local` names;
  - the tailnet's `100.64.0.0/10` and `fd7a:115c:a1e0::/48`;
  - the link's own network;
  - your home network, `192.168.68.0/22` by default. Edit it under **Edit home network**:
    up to 16 IPv4 ranges, separated by commas.
- **Everything else:** `PROXY <address>:1080; DIRECT`.
- **No lookups.** The file only compares literal addresses with these ranges, itself, and
  never calls `isInNet` or `dnsResolve`. On Apple devices `isInNet` looks addresses up even
  when given a literal. Names are matched as written, with any final dot ignored. So a custom
  domain that points at a tailnet or home address still goes through the VPN: use its IP
  address, its `.ts.net` name or a name without dots instead. Check how Immich is set up.
- **Overlap warning.** If the link's network overlaps a home range, the card warns that
  home addresses inside it are looked for on that network rather than through Tailscale.
- **iPhone:** **Settings → Wi‑Fi → ⓘ → Configure Proxy → Automatic**, enter the URL and
  tap Save.
- **Mac:** **Copy Mac Terminal command** sets it with `networksetup -setautoproxyurl`.

The file is served from the proxy's own port, to the same devices the proxy accepts, and
only while that link is sharing and the VPN is connected.

On Automatic, so that `DIRECT` takes over when the VPN can't be used, the proxy stops
listening while the VPN reconnects and starts again once it's connected. Connections that
are already open keep going. iOS falls back to `DIRECT` only when it can't connect to the
proxy at all, not when the proxy accepts and then fails. On Manual nothing changes: during a
reconnect the proxy keeps taking requests, which often go through once the tunnel is back a
few seconds later. When sharing or the VPN is off, nothing listens in either mode.

Not checked on an iPhone yet, from reports only:
- an iPhone that can't load the file when it joins the hotspot may be unable to browse on
  that network until Configure Proxy is set back to Off. That happens while this phone
  isn't sharing: sharing off, the VPN not connected, or the app closed. Apple's managed
  proxy settings have a separate "allow direct if the PAC can't be loaded" option, so the
  fallback isn't guaranteed;
- whether the URL survives the hotspot being turned off and on: Android can pick a new
  hotspot address, and the iPhone then can't load the file from the old one;
- how long iOS keeps a file it has loaded.

The Mac commands switch cleanly between the two: setting Automatic turns off this app's
manual proxies on that service, and setting Manual turns off this app's PAC URL, but only
once the three proxies are set. Proxies from anything else are left alone, including a
local SOCKS proxy such as `ssh -D 1080` and other PAC URLs. **Copy command to turn off** turns off
this app's PAC URL and any SOCKS proxy on port 1080, as it always has, including a local one.

On macOS: **System Settings → Network → the phone's USB service (or Wi-Fi, on the
hotspot) → Details → Proxies**. Turn on **Web proxy (HTTP)**, **Secure web proxy (HTTPS)** and
**SOCKS proxy**, each with the displayed address and port `1080`. No
authentication. Or tap **Copy Mac Terminal command** in the app and paste it into
Terminal. It finds the network service whose router is the phone, so you don't
need its name, and sets all three proxies with `sudo networksetup`. Run it again
after the address changes. macOS keeps proxies per network service, not per Wi-Fi
network: on the hotspot that service is Wi-Fi, so the proxies stay on when the Mac
joins any other Wi-Fi. Run the turn-off command before leaving the hotspot.

Only apps honoring macOS proxy settings use it. **Other Mac traffic still goes
around the VPN**, including direct connections and unsupported UDP. SOCKS clients
must send domain names to the proxy for phone-side VPN DNS (for example, curl's
`socks5h://<ip>:1080`); a client resolving names locally still uses its own DNS.
This is a no-auth proxy restricted to tethering interfaces and clients in the bound
interface's IPv4 subnet. USB means numbered `usb`, `rndis` and `ncm` interfaces. The
hotspot means a Wi-Fi tether name (`wlan`, `swlan`, `softap`, `ap`, `ap_br_*`,
`wigig`) that Android's sticky `TETHER_STATE_CHANGED` broadcast lists as tethering.
When Android lists tethering interfaces, USB ones must be listed too. If that broadcast
is unavailable, the hotspot is never guessed at (`wlan` is also the Wi-Fi the phone
joins, and hotspot-only names also serve local-only hotspots such as Quick Share);
USB keeps its name rule. The interface's role is checked again after reading its
addresses and before serving each new client. An interface carrying any of the
phone's own networks (the Wi-Fi it joined, mobile data) is never listened on, whatever
its name, so the proxy is never offered to a café or campus network. Anyone who can
join the hotspot can use the proxy while sharing is on. Phone apps cannot use the proxy,
including apps in other profiles. After VPN DNS resolution, the proxy refuses
loopback, unspecified, link-local, multicast and phone-interface destinations
(SOCKS ruleset refusal / HTTP 403). Other private-network destinations remain
available through the VPN. Do not enable it with an untrusted
USB peer or hotspot guest. Over USB the Mac's proxy settings belong to the phone's USB
service, and the iPhone's to the hotspot network, so other networks are unaffected; a
Mac on the hotspot is the exception above. While tethered with sharing off, apps using the proxy can't connect:
turn the proxies off on the Mac, or sharing back on.
