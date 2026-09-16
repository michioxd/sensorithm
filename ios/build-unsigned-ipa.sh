#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

archive_path="${ARCHIVE_PATH:-$PWD/build/Sensorithm.xcarchive}"
ipa_path="${IPA_PATH:-$PWD/build/Sensorithm-unsigned.ipa}"
staging_path="$(mktemp -d)"
trap 'rm -rf "$staging_path"' EXIT

rm -rf "$archive_path"
rm -f "$ipa_path"

xcodebuild archive \
  -project Sensorithm.xcodeproj \
  -scheme Sensorithm \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath "$archive_path" \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_IDENTITY=""

app_path="$archive_path/Products/Applications/Sensorithm.app"
[[ -d "$app_path" ]] || { echo "Archived app not found: $app_path" >&2; exit 1; }

mkdir -p "$staging_path/Payload" "$(dirname "$ipa_path")"
cp -R "$app_path" "$staging_path/Payload/"
ditto -c -k --sequesterRsrc --keepParent "$staging_path/Payload" "$ipa_path"

echo "Created unsigned IPA: $ipa_path"
