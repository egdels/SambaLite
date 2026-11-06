#!/bin/sh
# Builds SambaLite in release mode and wraps it into build/SambaLite.app.
# No Xcode project: the app is a SwiftPM executable, and this script is the
# whole packaging step (as in Crate).
#
#   scripts/bundle.sh               # native architecture, for development
#   scripts/bundle.sh --universal   # arm64 + x86_64, what a release ships
#
# AMSMB2 (LGPL-2.1-or-later) is a dynamic library and goes into
# Contents/Frameworks, where it can be replaced, as the LGPL asks.
# The bundle is ad-hoc signed: a downloaded copy has to be opened once
# via right-click > Open.
set -eu
cd "$(dirname "$0")/.."

if [ "${1:-}" = "--universal" ]; then
    set -- -c release --product SambaLite --arch arm64 --arch x86_64
else
    set -- -c release --product SambaLite
fi
swift build "$@"
BIN="$(swift build "$@" --show-bin-path)"

APP=build/SambaLite.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources" "$APP/Contents/Frameworks"
cp "$BIN/SambaLite" "$APP/Contents/MacOS/SambaLite"
cp "$BIN/libAMSMB2.dylib" "$APP/Contents/Frameworks/libAMSMB2.dylib"
cp Packaging/Info.plist "$APP/Contents/Info.plist"
cp Packaging/AppIcon.icns "$APP/Contents/Resources/AppIcon.icns"

# Only the bundle's own Frameworks folder (and the system's Swift) on the
# search path, not the build machine's folders.
EXE="$APP/Contents/MacOS/SambaLite"
for rpath in $(otool -l "$EXE" | awk '/LC_RPATH/ {getline; getline; print $2}'); do
    case "$rpath" in
        /usr/lib/swift) ;;
        *) install_name_tool -delete_rpath "$rpath" "$EXE" ;;
    esac
done
install_name_tool -add_rpath "@executable_path/../Frameworks" "$EXE"
install_name_tool -id "@rpath/libAMSMB2.dylib" "$APP/Contents/Frameworks/libAMSMB2.dylib"

codesign --force --sign - "$APP/Contents/Frameworks/libAMSMB2.dylib"
codesign --force --sign - "$APP"
echo "built $APP"
