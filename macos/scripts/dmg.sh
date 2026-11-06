#!/bin/sh
# Builds build/SambaLite-<version>.dmg: the app and a link to /Applications.
#
#   scripts/dmg.sh --universal   # what a release ships (arm64 + x86_64)
#   scripts/dmg.sh               # native architecture only
#
# The app inside is ad-hoc signed (see bundle.sh); a DMG keeps that
# signature intact, which a zip does not reliably do.
set -eu
cd "$(dirname "$0")/.."
scripts/bundle.sh "$@"
VERSION=$(/usr/libexec/PlistBuddy -c "Print CFBundleShortVersionString" Packaging/Info.plist)
STAGE=$(mktemp -d)
cp -R build/SambaLite.app "$STAGE/"
ln -s /Applications "$STAGE/Applications"
DMG="build/SambaLite-$VERSION.dmg"
rm -f "$DMG"
hdiutil create -volname "SambaLite $VERSION" -srcfolder "$STAGE" -ov -format UDZO "$DMG" >/dev/null
rm -rf "$STAGE"
echo "built $DMG"
