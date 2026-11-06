#!/bin/sh
# Captures the SambaLite window to a PNG, for checking the UI without a
# human in front of the screen. Prints the file path. Needs Screen
# Recording and Accessibility for the terminal's host app (as Crate's).
#
#   scripts/screenshot.sh [output.png]
set -eu
OUT="${1:-/tmp/sambalite-$(date +%H%M%S).png}"
pgrep -xq SambaLite || { echo "SambaLite is not running" >&2; exit 1; }
FRAME=$(osascript -e 'tell application "System Events" to tell process "SambaLite"
    set p to position of window 1
    set s to size of window 1
    return (item 1 of p as text) & "," & (item 2 of p as text) & "," & (item 1 of s as text) & "," & (item 2 of s as text)
end tell')
screencapture -x -R "$FRAME" "$OUT"
echo "$OUT"
