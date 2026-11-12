# Android implementation verification

Verified on the Mac mini on 2026-11-12. No emulator/device was started or driven.
The iOS source was read and copied from, but not modified. The new repository is
on local main, with no remote configured.

## Commands and results

Commands below ran from /Users/mini/Master/code/connectanytime-android.
Logs were redirected to /tmp to keep generated output outside source control.

```sh
Vendor/build-deps.sh > /tmp/connectanytime-native-build.log 2>&1
```

PASS, exit 0: complete builds for arm64-v8a and x86_64, ending in
“Built arm64-v8a” and “Built x86_64”. A subsequent `Vendor/build-deps.sh`
verified the tarballs again and reported both ABIs up to date. The script
checks all three pinned SHA-256s, and its stamp includes the recipe and all five
patches. libxml2 2.14.6's tarball checksum was independently checked against the
upstream published .sha256sum before pinning.

```sh
./gradlew --version > /tmp/connectanytime-gradle-version.log 2>&1
shasum -a 256 gradle/wrapper/gradle-wrapper.jar
curl -fsSL https://services.gradle.org/distributions/gradle-8.13-wrapper.jar.sha256
```

PASS: Gradle 8.13 on JDK 21.0.10. Wrapper JAR hash and published checksum both
`81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`.
The wrapper also enforces Gradle distribution SHA-256
`20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`.

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug > /tmp/connectanytime-android-checks.log 2>&1
```

PASS, exit 0: final run “BUILD SUCCESSFUL in 1s”, 59 actionable tasks
(8 executed, 51 up-to-date). The preceding run after Kotlin/lifecycle changes was
also successful (4s, 27 executed). Both ABIs compiled and linked. Native bridge
compilation uses -Wall -Wextra -Werror and --no-undefined.

JVM results: **9 tests, 0 failures, 0 errors, 0 skipped**. Cases include:

- Prefix and dotted-mask parsing, network normalization, malformed/noncontiguous
  masks, IPv4 and IPv6 complements, exhaustive address coverage of a /24.
- Case-insensitive DNS SAN matching, one-label wildcard, malicious suffixes,
  partial wildcards, malformed names, IP literals and top-level wildcards.
- Rejoin delays, cap and 120-second reset.
- Every manual/automatic × saved/missing session × disconnected/allowed case.
- Change detection for every tunnel config field.
- Empty/countdown log filtering and preservation of diagnostic lines.

Lint: **0 errors, 16 warnings**. Warnings concern deliberately pinned older
dependency versions, synchronous persistence of refusal counters, and optional
KTX preference-editor style. There is no lint baseline. Two narrow suppressions
are documented in source: the pre-34 tile Intent fallback (34+ uses PendingIntent),
and the manifest detector's alarm-only model of systemExempted (configured VPN
apps also qualify, without an alarm permission).

Generated reports:
`app/build/reports/tests/testDebugUnitTest/index.html` and
`app/build/reports/lint-results-debug.html`.

```sh
NDK_BIN="$HOME/Library/Android/sdk/ndk/27.1.12297006/toolchains/llvm/prebuilt/darwin-x86_64/bin"
"$NDK_BIN/llvm-nm" -D --defined-only app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib/arm64-v8a/libconnectanytime.so
"$NDK_BIN/llvm-readelf" -d app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib/arm64-v8a/libconnectanytime.so
"$NDK_BIN/llvm-readelf" -l app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib/arm64-v8a/libconnectanytime.so
scripts/verify-native.sh > /tmp/connectanytime-native-verification.log 2>&1
```

PASS, exit 0: **both ABIs** export JNI_OnLoad and all 11 NativeBridge JNI entry
points: create, connect, resume, error, saved, config, run, abortBeforePassword,
command, free, setNetwork. All dependency symbols are hidden from the dynamic
export table. DT_NEEDED is exactly:

```text
libz.so
liblog.so
libandroid.so
libdl.so
libm.so
libc.so
```

No host libraries or C++ shared runtime are required. Arm64 ELF LOAD alignment is
0x4000. The verification script also runs:

```sh
"$HOME/Library/Android/sdk/build-tools/36.0.0/zipalign" -c -P 16 4 app/build/outputs/apk/debug/app-debug.apk
```

PASS: APK 16 KB alignment.

```sh
git diff --check
git -C /Users/mini/Master/code/anyconnectios status --porcelain
git remote -v
```

PASS: no whitespace errors; iOS working tree clean; no Android remotes.
No push, repository creation or remote changes were performed.

## Failures found and fixed during implementation

Early verification attempts failed; none remains outstanding:

- The dependency stamp initially referenced a relative script path after cd.
  The recipe now hashes its path relative to Vendor.
- Passing an absolute libxml2 archive to libtool nested an archive inside
  libopenconnect.a. Using -L/-l dependency flags fixed the final Android link.
- Bionic lacks explicit_bzero at API 29. A local noinline volatile wipe implements
  it without introducing an unavailable libc symbol.
- Kotlin compilation caught nullable saved-session use and IpPrefix construction.
  Lint additionally caught the API-33 addRoute overload on API 29–32; routes now
  use the older InetAddress/prefix overload, with API-33 excludeRoute guarded.
- Lint flagged the tile Intent overload and systemExempted permission model.
  The versioned fallback and VPN exemption are explicitly documented and narrowly
  suppressed; no unrelated alarm permission was added.
- The first verification-script version used grep -q with pipefail, causing
  SIGPIPE on a large symbol listing. It now consumes the complete input.

Nonfatal build messages included SDK metadata XML version skew and a libtool
static-link portability warning. The actual cross-compiled libraries linked
successfully and passed dependency inspection.

## Artifact

`app/build/outputs/apk/debug/app-debug.apk`, approximately 41 MB, debug-signed.

SHA-256:
`54ddf7e24cb5ae03dd9b78cf42f23ff12b8e7968cf48345cd3ac666e8e8f84f9`.

## Remaining verification

No implementation path is intentionally stubbed. JVM tests do not validate JNI
execution or Android framework lifecycle behaviour. Real NYU authentication and
Duo need credentials and human approval; neither was available or attempted.
Platform certificate verification, tunnel traffic and IPv6 leak checks, physical
network transitions, long sleeps, config replacement, sticky/boot/Always-on
restarts, notification permission, lock-screen tile/shortcut actions, and actual
server logout still require a real Android device. The native builds and JVM
tests are the completed verification bar for this task.

The README lists every intentional platform difference and the fd ownership
evidence from OpenConnect 9.21. Its build script is Mac-host-specific. The APK has
not been installed, published or distributed.

## Local commits

- `518d2dbb90c4710cd00296657cc9ca52222f27b6`: pinned native build and JNI bridge.
- `49418b34efce7609087520725d230dee6cdf57ed`: Android app, lifecycle, storage,
  controls, tests and final native integration.
- The documentation/verification commit follows these two; use `git log -3`
  for its full SHA (a commit cannot contain its own SHA).
