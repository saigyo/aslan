#!/bin/bash
# Captures the window of the sandbox IDE (application "Main"), and nothing else, into a PNG file.
# Popups of the IDE (completion lists, tooltips) are separate windows, so the picture is taken of the
# screen rectangle the main window occupies, and then cut to the window's own shape: nothing that lies
# behind the window's rounded corners gets into the file.
# Usage: capture.sh <output file>
#        capture.sh --size             prints "<id> <x> <y> <width> <height>" of the window, captures nothing
HERE="$(cd "$(dirname "$0")" && pwd)"
line=$(swift "$HERE/window.swift" Main 2>/dev/null | awk '{ a = $4 * $5; if (a > best) { best = a; l = $0 } } END { print l }')
if [ -z "$line" ]; then echo "no window of the application Main found" >&2; exit 1; fi
if [ "$1" = "--size" ]; then echo "$line"; exit 0; fi
set -- "$1" $line
out="$1"; id="$2"; x="$3"; y="$4"; w="$5"; h="$6"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
screencapture -x -R "$x,$y,$w,$h" "$tmp/region.png" || { echo "screencapture of the region failed" >&2; exit 1; }
screencapture -x -o -l "$id" "$tmp/window.png" || { echo "screencapture of the window failed" >&2; exit 1; }
mkdir -p "$(dirname "$out")"
swift "$HERE/compose.swift" "$tmp/region.png" "$tmp/window.png" "$out" || { echo "composing failed" >&2; exit 1; }
sips -g pixelWidth -g pixelHeight "$out" | tail -2 | tr '\n' ' '; echo
