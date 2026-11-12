#!/bin/bash
# Static Android libraries; no host libraries may sneak into either ABI.
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$PWD"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK="$SDK/ndk/27.1.12297006"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64"
CMAKE="$SDK/cmake/3.22.1/bin/cmake"
export PATH="$TOOLCHAIN/bin:$PATH"
JOBS="${JOBS:-$(sysctl -n hw.ncpu)}"
OPENSSL=openssl-3.5.9
OPENCONNECT=openconnect-9.21
XML=libxml2-2.14.6
fetch() {
  test -f "src/$2" || curl -fL --retry 3 "$1" -o "src/$2"
  test "$(shasum -a 256 "src/$2" | awk '{print $1}')" = "$3" || { echo "Checksum mismatch: $2"; exit 1; }
}
mkdir -p src out
fetch "https://github.com/openssl/openssl/releases/download/$OPENSSL/$OPENSSL.tar.gz" "$OPENSSL.tar.gz" 603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a
fetch "https://www.infradead.org/openconnect/download/$OPENCONNECT.tar.gz" "$OPENCONNECT.tar.gz" 5b32369467db6e5f317aa1ed12cfcbb81ed00bdbc765450b6bfcbdc300944a58
# Verified against https://download.gnome.org/sources/libxml2/2.14/libxml2-2.14.6.sha256sum
fetch "https://download.gnome.org/sources/libxml2/2.14/$XML.tar.xz" "$XML.tar.xz" 7ce458a0affeb83f0b55f1f4f9e0e55735dbfc1a9de124ee86fb4a66b597203a
# Include this recipe as well as all patches, so flag and dependency changes rebuild.
SUM=$(cat build-deps.sh patches/*.patch | shasum -a 256 | awk '{print $1}')
for ABI in arm64-v8a x86_64; do
  OUT="$ROOT/out/$ABI"
  test "$(cat "$OUT/build.sha256" 2>/dev/null || true)" != "$SUM" || { echo "$ABI up to date"; continue; }
  case "$ABI" in
    arm64-v8a) TRIPLE=aarch64-linux-android; SSL_TARGET=android-arm64;;
    x86_64) TRIPLE=x86_64-linux-android; SSL_TARGET=android-x86_64;;
  esac
  WORK="$ROOT/src/build-$ABI"
  rm -rf "$WORK" "$OUT"
  mkdir -p "$WORK" "$OUT"
  tar -xzf "src/$OPENSSL.tar.gz" -C "$WORK"
  tar -xJf "src/$XML.tar.xz" -C "$WORK"
  tar -xzf "src/$OPENCONNECT.tar.gz" -C "$WORK"
  (cd "$WORK/$OPENSSL"
    ANDROID_NDK_ROOT="$NDK" ./Configure "$SSL_TARGET" -D__ANDROID_API__=29 no-shared no-tests no-apps no-docs no-engine no-async no-module --prefix="$OUT" --openssldir=/var/empty -fPIC
    make -j"$JOBS" build_libs
    make install_dev)
  # FTP was removed upstream in libxml2 2.14; HTTP is explicitly disabled.
  "$CMAKE" -S "$WORK/$XML" -B "$WORK/xml-build" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=29 -DCMAKE_INSTALL_PREFIX="$OUT" \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DBUILD_SHARED_LIBS=OFF -DLIBXML2_WITH_PYTHON=OFF -DLIBXML2_WITH_HTTP=OFF \
    -DLIBXML2_WITH_LZMA=OFF -DLIBXML2_WITH_ICU=OFF -DLIBXML2_WITH_ICONV=OFF \
    -DLIBXML2_WITH_ZLIB=OFF -DLIBXML2_WITH_PROGRAMS=OFF -DLIBXML2_WITH_TESTS=OFF
  "$CMAKE" --build "$WORK/xml-build" -j "$JOBS"
  "$CMAKE" --install "$WORK/xml-build"
  for p in patches/*.patch; do patch -d "$WORK/$OPENCONNECT" -p1 --forward < "$p"; done
  (cd "$WORK/$OPENCONNECT"
    PKG_CONFIG_PATH="$OUT/lib/pkgconfig" PKG_CONFIG_LIBDIR="$OUT/lib/pkgconfig" \
    CC="$TOOLCHAIN/bin/${TRIPLE}29-clang" AR=llvm-ar RANLIB=llvm-ranlib \
    CFLAGS="-O2 -fPIC" LDFLAGS="-L$OUT/lib" \
    LIBXML2_CFLAGS="-I$OUT/include/libxml2" LIBXML2_LIBS="-L$OUT/lib -lxml2 -lm" \
    OPENSSL_CFLAGS="-I$OUT/include" OPENSSL_LIBS="-L$OUT/lib -lssl -lcrypto -ldl" \
    ZLIB_CFLAGS=" " ZLIB_LIBS="-lz" \
    ./configure --host="$TRIPLE" --prefix="$OUT" \
      --enable-static --disable-shared --with-pic --disable-nls --disable-docs \
      --without-gnutls --with-openssl --without-libpskc --without-stoken \
      --without-libpcsclite --without-libproxy --without-gssapi --without-lz4 \
      --without-java --with-vpnc-script=/var/empty/vpnc-script
    make -j"$JOBS" libopenconnect.la
    make install-libLTLIBRARIES install-includeHEADERS)
  echo "$SUM" > "$OUT/build.sha256"
  echo "Built $ABI"
done
