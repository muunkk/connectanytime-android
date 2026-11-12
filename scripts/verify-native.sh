#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
BIN="${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk/27.1.12297006/toolchains/llvm/prebuilt/darwin-x86_64/bin"
for ABI in arm64-v8a x86_64; do
    SO="app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib/$ABI/libconnectanytime.so"
    SYMBOLS=$("$BIN/llvm-nm" -D --defined-only "$SO")
    for NAME in create connect resume error saved config run abortBeforePassword command free setNetwork; do
        echo "$SYMBOLS" | grep " T Java_com_muunkk_connectanytime_NativeBridge_$NAME$" >/dev/null
    done
    echo "$SYMBOLS" | grep ' T JNI_OnLoad$' >/dev/null
    NEEDED=$("$BIN/llvm-readelf" -d "$SO" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p')
    for LIB in $NEEDED; do
        case "$LIB" in libc.so|libm.so|libdl.so|liblog.so|libz.so|libandroid.so) ;;
        *) echo "Unexpected dependency: $LIB"; exit 1;;
        esac
    done
    echo "$ABI: JNI_OnLoad + 11 JNI entry points; only Android system dependencies"
    echo "$NEEDED"
done
"${ANDROID_HOME:-$HOME/Library/Android/sdk}/build-tools/36.0.0/zipalign" -c -P 16 4 app/build/outputs/apk/debug/app-debug.apk
echo "APK 16 KB alignment: PASS"
