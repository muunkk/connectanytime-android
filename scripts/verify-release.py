#!/usr/bin/env python3
"""Inspect the signed release APK and exact JNI names without running a device."""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent
SDK = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
TOOLS = SDK / "build-tools/36.0.0"
APK = ROOT / "app/build/outputs/apk/release/app-release.apk"


def run(tool, *args):
    return subprocess.check_output([str(TOOLS / tool), *map(str, args)], text=True)


print(run("apksigner", "verify", "--print-certs", APK).strip())
run("zipalign", "-c", "-P", "16", "4", APK)
print("PASS: release signature and 16 KB APK alignment")
for variant, package, label in [
    ("release", "com.muunkk.connectanytime", "ConnectAnytime"),
    ("debug", "com.muunkk.connectanytime.debug", "ConnectAnytime Dev"),
]:
    apk = ROOT / f"app/build/outputs/apk/{variant}/app-{variant}.apk"
    badging = run("aapt", "dump", "badging", apk)
    assert f"package: name='{package}' versionCode='1' versionName='0.1.0'" in badging
    assert f"application-label:'{label}'" in badging
    print(f"PASS: {variant} package {package}, label {label}, version 0.1.0 (1)")

jni = (ROOT / "app/src/main/cpp/jni.c").read_text()
entry_points = re.findall(r"JNIEXPORT \w+ JNICALL API\((\w+)\)", jni)
callbacks = dict(re.findall(r'GetMethodID\(e, cls, "(\w+)", "([^"]+)"\)', jni))
assert len(entry_points) == 11 and len(callbacks) == 4
with zipfile.ZipFile(APK) as archive, tempfile.TemporaryDirectory() as folder:
    for abi in ["arm64-v8a", "x86_64"]:
        info = archive.getinfo(f"lib/{abi}/libconnectanytime.so")
        print(f"PASS: {info.filename}, {info.file_size} bytes")
    dex = "\n".join(run("dexdump", archive.extract(name, folder))
                    for name in archive.namelist() if re.fullmatch(r"classes\d*\.dex", name))
    blocks = re.split(r"(?=Class #\d+)", dex)
    native = next(b for b in blocks if "Class descriptor  : 'Lcom/muunkk/connectanytime/NativeBridge;'" in b)
    for name in entry_points:
        assert f"name          : '{name}'" in native, name
    print("PASS: NativeBridge and all 11 native method names survived R8")
    mapping = (ROOT / "app/build/outputs/mapping/release/mapping.txt").read_text()
    concrete = re.search(r'^com.muunkk.connectanytime.ConnectVpnService\$newSession\$1\$cb\$1 -> ([^:]+):', mapping, re.M)[1]
    descriptor = "L" + concrete.replace(".", "/") + ";"
    callback = next(b for b in blocks if f"Class descriptor  : '{descriptor}'" in b)
    for name, signature in callbacks.items():
        assert re.search(r"name\s+: '" + name + r"'\s+type\s+: '" + re.escape(signature) + "'", callback), name
        print(f"PASS: concrete {concrete}.{name}{signature}")
