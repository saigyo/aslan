#!/bin/bash
# Prints the change notes of the plugin, the text between the change-notes tags of plugin.xml, for the
# text of a GitHub release. The notes are HTML, which GitHub shows as it is.
# Usage: notes.sh [path to plugin.xml]
set -euo pipefail
file="${1:-$(cd "$(dirname "$0")/../.." && pwd)/src/main/resources/META-INF/plugin.xml}"
notes=$(sed -n '/<change-notes><!\[CDATA\[/,/\]\]><\/change-notes>/p' "$file" | sed '1d;$d' | sed 's/^ \{1,8\}//')
if [ -z "$notes" ]; then echo "no change notes found in $file" >&2; exit 1; fi
printf '%s\n' "$notes"
