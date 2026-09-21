#!/usr/bin/env bash
# Registers the DBeaver jars needed by the unit tests in your local Maven repo.
# The plugin itself resolves DBeaver/Eclipse APIs from p2 (see root pom.xml).
# Usage: ./scripts/install-dbeaver-libs.sh [/path/to/dbeaver]
set -euo pipefail

DBEAVER_DIR="${1:-${DBEAVER_DIR:-}}"
if [[ -z "$DBEAVER_DIR" ]]; then
  for candidate in /usr/share/dbeaver /opt/dbeaver "$HOME/dbeaver" "/mnt/c/Program Files/DBeaver"; do
    if [[ -d "$candidate/plugins" ]]; then DBEAVER_DIR="$candidate"; break; fi
  done
fi
if [[ -z "$DBEAVER_DIR" || ! -d "$DBEAVER_DIR/plugins" ]]; then
  echo "DBeaver install not found. Pass it explicitly: $0 /path/to/dbeaver" >&2
  exit 1
fi

if ! command -v mvn >/dev/null; then
  echo "Maven (mvn) not found. Install it first (apt install maven / brew install maven)." >&2
  exit 1
fi

PLUGINS="$DBEAVER_DIR/plugins"
BUNDLES=(
  org.jkiss.dbeaver.model
  org.jkiss.utils
)

installed=0
for bundle in $(printf '%s\n' "${BUNDLES[@]}" | sort -u); do
  jar="$(ls -1 "$PLUGINS/${bundle}_"*.jar 2>/dev/null | sort -r | head -n 1 || true)"
  if [[ -z "$jar" ]]; then
    echo "WARNING: no jar found for $bundle" >&2
    continue
  fi
  echo "Installing $bundle <- $(basename "$jar")"
  mvn -q install:install-file \
    "-DgroupId=dbeaver.local" \
    "-DartifactId=$bundle" \
    "-Dversion=1.0.0-local" \
    "-Dpackaging=jar" \
    "-Dfile=$jar"
  installed=$((installed + 1))
done

echo ""
echo "Done: $installed bundle(s) registered. Now run: mvn clean verify"
