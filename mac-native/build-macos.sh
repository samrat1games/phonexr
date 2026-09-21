#!/bin/zsh
set -euo pipefail

SCRIPT_DIR="${0:A:h}"
OUTPUT_DIR="$SCRIPT_DIR/build"
FULL_APK="$SCRIPT_DIR/../app/build/outputs/apk/full/debug/app-full-debug.apk"
LITE_APK="$SCRIPT_DIR/../app/build/outputs/apk/lite/debug/app-lite-debug.apk"

build_edition() {
  local name="$1" flag="$2" plist="$3" apk="$4"
  local app="$OUTPUT_DIR/$name.app" stage="$OUTPUT_DIR/$name-dmg" dmg="$OUTPUT_DIR/$name.dmg"
  /bin/rm -rf "$app" "$stage"
  /bin/mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources" "$stage"
  /usr/bin/xcrun swiftc -parse-as-library -O -module-cache-path "$OUTPUT_DIR/module-cache" \
    -target arm64-apple-macos13.0 -framework SwiftUI -framework AppKit ${(z)flag} \
    "$SCRIPT_DIR/PhoneXRShareApp.swift" "$SCRIPT_DIR/AndroidBridge.swift" "$SCRIPT_DIR/ScreenStream.swift" \
    -o "$app/Contents/MacOS/$name"
  /bin/cp "$plist" "$app/Contents/Info.plist"
  /bin/cp "$SCRIPT_DIR/../desktop/assets/phonexr-share-icon.png" "$app/Contents/Resources/PhoneXRShare.png"
  /bin/cp "$apk" "$app/Contents/Resources/PhoneXR.apk"
  /usr/bin/codesign --force --deep --sign - "$app"
  /usr/bin/ditto "$app" "$stage/$name.app"
  /bin/ln -s /Applications "$stage/Applications"
  /bin/rm -f "$dmg"
  /usr/bin/hdiutil create -volname "$name" -srcfolder "$stage" -ov -format UDZO "$dmg"
  echo "$dmg"
}

/bin/mkdir -p "$OUTPUT_DIR/module-cache"
build_edition "PhoneXR Share" "" "$SCRIPT_DIR/Info.plist" "$FULL_APK"
build_edition "PhoneXR Lite Share" "-D PHONEXR_LITE" "$SCRIPT_DIR/Info-Lite.plist" "$LITE_APK"
