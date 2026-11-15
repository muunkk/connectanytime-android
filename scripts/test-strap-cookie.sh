#!/bin/bash
# Builds the patched OpenConnect for this Mac and runs Vendor/tests/strap-cookie-test.c.
# Needs Homebrew's openssl@3.5 (the Android build uses OpenSSL 3.5.9 too) and the
# Command Line Tools. Builds in Vendor/src/build-host, which Git ignores.
set -euo pipefail
cd "$(dirname "$0")/../Vendor"
OSSL="$(brew --prefix openssl@3.5 2>/dev/null || brew --prefix openssl@3)"
SDK="$(xcrun --show-sdk-path)"
WORK="$PWD/src/build-host"
OC="$WORK/openconnect-9.21"
SUM=$(cat patches/*.patch | shasum -a 256 | awk '{print $1}')
if [ "$(cat "$WORK/build.sha256" 2>/dev/null || true)" != "$SUM" ]; then
  rm -rf "$WORK" && mkdir -p "$WORK"
  tar -xzf src/openconnect-9.21.tar.gz -C "$WORK"
  for p in patches/*.patch; do patch -d "$OC" -p1 --forward -s < "$p"; done
  (cd "$OC"
    OPENSSL_CFLAGS="-I$OSSL/include" OPENSSL_LIBS="-L$OSSL/lib -lssl -lcrypto" \
    LIBXML2_CFLAGS="-I$SDK/usr/include/libxml2" LIBXML2_LIBS="-lxml2" ZLIB_CFLAGS=" " ZLIB_LIBS="-lz" \
    CC=/usr/bin/clang ./configure --enable-static --disable-shared --disable-nls --disable-docs \
      --without-gnutls --with-openssl --without-libpskc --without-stoken --without-libpcsclite \
      --without-libproxy --without-gssapi --without-lz4 --without-java \
      --with-vpnc-script=/var/empty/vpnc-script >/dev/null 2>&1
    make -j"$(sysctl -n hw.ncpu)" libopenconnect.la >/dev/null)
  echo "$SUM" > "$WORK/build.sha256"
fi
grep -q 'define HAVE_HPKE_SUPPORT 1' "$OC/config.h" || { echo "OpenConnect built without STRAP support"; exit 1; }
/usr/bin/clang -Wall -Wno-unused-function -I"$OC" -I"$OC/json" -I"$OSSL/include" -I"$SDK/usr/include/libxml2" \
  tests/strap-cookie-test.c "$OC/.libs/libopenconnect.a" -L"$OSSL/lib" -lssl -lcrypto -lxml2 -lz -liconv \
  -o "$WORK/strap-cookie-test"
"$WORK/strap-cookie-test"
