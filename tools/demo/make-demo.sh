#!/bin/sh
# Creates the demo project build/demo/film-archive afresh, for the sandbox IDE and for screenshots.
# Run from anywhere: ./tools/demo/make-demo.sh
set -eu

root=$(cd "$(dirname "$0")/../.." && pwd)
source_dir="$root/src/test/testData/clean"
demo="$root/build/demo/film-archive"

rm -rf "$demo"
mkdir -p "$demo"

cp "$source_dir/film-archive-intake.asl.yaml" "$demo/intake.asl.yaml"
cp "$source_dir/reel-restoration.asl.yaml" "$demo/reel-restoration.asl.yaml"
cp "$source_dir/reel-check.asl.json" "$demo/reel-check.asl.json"

# The intake definition with three planted problems:
# - SkipUnchangedBatch goes to a state that does not exist (CleanUpIncoming is still reached from two other states),
# - the Task ReadManifest has no Resource,
# - the retry rule of MeasureReel has an unknown JitterStrategy.
# sed writes to standard output, as sed -i differs between macOS and Linux.
sed \
    -e '/^  SkipUnchangedBatch:$/,/^    Next:/s/^    Next: CleanUpIncoming$/    Next: CleanUpIncomingFolder/' \
    -e '/^  ReadManifest:$/,/^    Resource:/{/^    Resource:/d;}' \
    -e '/^        MeasureReel:$/,/JitterStrategy:/s/JitterStrategy: FULL$/JitterStrategy: PARTIAL/' \
    "$source_dir/film-archive-intake.asl.yaml" > "$demo/intake-with-problems.asl.yaml"

# Stop when an edit found nothing to change, for example after the source file has changed.
problems="$demo/intake-with-problems.asl.yaml"
grep -q '^    Next: CleanUpIncomingFolder$' "$problems" || { echo "make-demo: the Next was not changed" >&2; exit 1; }
grep -q '^              JitterStrategy: PARTIAL$' "$problems" || { echo "make-demo: the JitterStrategy was not changed" >&2; exit 1; }
if [ $(($(wc -l < "$source_dir/film-archive-intake.asl.yaml") - $(wc -l < "$problems"))) -ne 1 ]; then
    echo "make-demo: the Resource was not removed" >&2
    exit 1
fi

echo "Demo project: $demo"
