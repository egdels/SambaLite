#!/bin/sh
# Redraws Packaging/AppIcon.icns and Packaging/AppIcon-256.png from
# scripts/icon.swift.
set -eu
cd "$(dirname "$0")/.."
TMP=$(mktemp -d)
SET="$TMP/AppIcon.iconset"
mkdir -p "$SET"
for size in 16 32 128 256 512; do
    swift scripts/icon.swift "$SET/icon_${size}x${size}.png" $size
    swift scripts/icon.swift "$SET/icon_${size}x${size}@2x.png" $((size * 2))
done
iconutil -c icns "$SET" -o Packaging/AppIcon.icns
cp "$SET/icon_256x256.png" Packaging/AppIcon-256.png
rm -rf "$TMP"
echo "wrote Packaging/AppIcon.icns"
