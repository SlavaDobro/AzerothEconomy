#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p .build-deps
artifact=.build-deps/PlaceholderAPI-2.12.3.jar
expected=$(tr -d '\r\n' < deps/placeholderapi.sha256)
checksum() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d ' ' -f 1
  else shasum -a 256 "$1" | cut -d ' ' -f 1; fi
}
if [ ! -f "$artifact" ]; then
  curl --fail --location --output "$artifact.download" 'https://github.com/PlaceholderAPI/PlaceholderAPI/releases/download/2.12.3/PlaceholderAPI-2.12.3.jar'
  [ "$(checksum "$artifact.download")" = "$expected" ] || { echo 'PlaceholderAPI checksum mismatch' >&2; exit 1; }
  mv "$artifact.download" "$artifact"
fi
[ "$(checksum "$artifact")" = "$expected" ] || { echo 'PlaceholderAPI checksum mismatch' >&2; exit 1; }
./mvnw clean verify "$@"
