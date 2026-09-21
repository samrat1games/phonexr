#!/usr/bin/env python3
"""
Готовит PhoneXR Runtime — OpenXR-рантайм (Monado с патчем PhoneXR), который PhoneXR ставит сам.

Берёт собранный APK рантайма (openxr-runtime/monado + monado-phonexr.patch) и:
  * называет его «PhoneXR Runtime» везде (брокер OpenXR, лаунчер, служба), ставит иконку PhoneXR;
  * убирает сборки для x86 и лишние символы из библиотек (≈97 МБ → ≈25 МБ);
  * подписывает ключом PhoneXR и кладёт в app/src/main/assets/runtime/phonexr-runtime.apk.

  python3 openxr-runtime/build_runtime_apk.py [--apk путь/к/monado.apk]
"""

import argparse
import glob
import os
import re
import shutil
import subprocess
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SDK = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Library/Android/sdk")
NAME = "PhoneXR Runtime"
VERSION_CODE = 3


def build_tool(name):
    folder = os.path.join(SDK, "build-tools")
    for version in sorted(os.listdir(folder), reverse=True):
        path = os.path.join(folder, version, name)
        if os.path.exists(path):
            return path
    raise SystemExit(f"Не найден {name}")


def strip_tool():
    found = sorted(glob.glob(os.path.join(SDK, "ndk", "*", "toolchains", "llvm", "prebuilt", "*", "bin", "llvm-strip")))
    if not found:
        raise SystemExit("Не найден llvm-strip из NDK")
    return found[-1]


def rebrand(folder):
    manifest = os.path.join(folder, "AndroidManifest.xml")
    text = open(manifest, encoding="utf-8").read()
    text = text.replace('android:label="Monado"', f'android:label="{NAME}"')
    open(manifest, "w", encoding="utf-8").write(text)

    strings = os.path.join(folder, "res", "values", "strings.xml")
    text = open(strings, encoding="utf-8").read()
    text = re.sub(r'(<string name="app_name">)[^<]*(</string>)', rf"\g<1>{NAME}\g<2>", text)
    text = re.sub(r'(<string name="service_name">)[^<]*(</string>)', r"\g<1>PhoneXR OpenXR\g<2>", text)
    open(strings, "w", encoding="utf-8").write(text)

    res = os.path.join(folder, "res")
    app_res = os.path.join(ROOT, "app", "src", "main", "res")
    os.makedirs(os.path.join(res, "drawable-nodpi"), exist_ok=True)
    shutil.copy(os.path.join(app_res, "drawable-nodpi", "ic_launcher_foreground.png"),
                os.path.join(res, "drawable-nodpi", "phonexr_foreground.png"))
    shutil.copy(os.path.join(app_res, "drawable", "ic_launcher_background_gradient.xml"),
                os.path.join(res, "drawable", "phonexr_background.xml"))
    adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@drawable/phonexr_background" />\n'
                '    <foreground android:drawable="@drawable/phonexr_foreground" />\n'
                '</adaptive-icon>\n')
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        open(os.path.join(res, "mipmap-anydpi", name), "w", encoding="utf-8").write(adaptive)
    # Old launchers take the bitmaps: PhoneXR's own, in place of Monado's webp.
    for density in ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"):
        target = os.path.join(res, f"mipmap-{density}")
        for old in glob.glob(os.path.join(target, "ic_launcher*.webp")):
            os.remove(old)
        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            shutil.copy(os.path.join(app_res, f"mipmap-{density}", name), os.path.join(target, name))

    yml = os.path.join(folder, "apktool.yml")
    text = open(yml, encoding="utf-8").read()
    text = re.sub(r"versionInfo:\n(  versionCode: .*\n)?", f"versionInfo:\n  versionCode: {VERSION_CODE}\n", text)
    text = re.sub(r"versionName: .*", "versionName: 1.1.0", text)
    open(yml, "w", encoding="utf-8").write(text)


def slim(folder):
    lib = os.path.join(folder, "lib")
    for abi in ("x86", "x86_64"):
        shutil.rmtree(os.path.join(lib, abi), ignore_errors=True)
    strip = strip_tool()
    for so in glob.glob(os.path.join(lib, "*", "*.so")):
        subprocess.run([strip, "--strip-unneeded", so], check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", default=os.path.join(ROOT, "VR-Android", "2-Monado-OpenXR-Runtime.apk"))
    parser.add_argument("-o", "--output", default=os.path.join(ROOT, "app", "src", "main", "assets", "runtime", "phonexr-runtime.apk"))
    arguments = parser.parse_args()
    with tempfile.TemporaryDirectory() as work:
        decoded = os.path.join(work, "decoded")
        subprocess.run(["apktool", "d", "-q", "-s", "-f", arguments.apk, "-o", decoded], check=True)
        rebrand(decoded)
        slim(decoded)
        unsigned = os.path.join(work, "unsigned.apk")
        subprocess.run(["apktool", "b", "-q", decoded, "-o", unsigned], check=True)
        aligned = os.path.join(work, "aligned.apk")
        subprocess.run([build_tool("zipalign"), "-P", "16", "-f", "4", unsigned, aligned], check=True)
        os.makedirs(os.path.dirname(arguments.output), exist_ok=True)
        keystore = os.path.join(ROOT, "app", "src", "main", "assets", "phonexr-signing.p12")
        subprocess.run([build_tool("apksigner"), "sign", "--ks", keystore, "--ks-pass", "pass:android",
                        "--ks-key-alias", "androiddebugkey", "--key-pass", "pass:android",
                        "--out", arguments.output, aligned], check=True)
    print("Готово:", arguments.output, os.path.getsize(arguments.output) // (1 << 20), "МБ")


if __name__ == "__main__":
    main()
