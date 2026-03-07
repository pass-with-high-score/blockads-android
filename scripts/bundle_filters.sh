#!/bin/bash
# bundle_filters.sh - Download default-enabled filter lists for bundling in APK
# Run before releases: ./scripts/bundle_filters.sh

set -e

OUTPUT_DIR="app/src/main/assets/bundled_filters"
mkdir -p "$OUTPUT_DIR"

# Only bundle the 2 default-enabled filters (EasyList + EasyPrivacy)
# Filenames = Java String.hashCode() of the URL
declare -A FILTERS=(
    ["-1025149380.txt"]="https://easylist.to/easylist/easylist.txt"
    ["-1463595922.txt"]="https://easylist.to/easylist/easyprivacy.txt"
    ["320624723.txt"]="https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
)

for filename in "${!FILTERS[@]}"; do
    url="${FILTERS[$filename]}"
    echo "Downloading: $url"
    echo "  -> $OUTPUT_DIR/$filename"
    curl -sL -o "$OUTPUT_DIR/$filename" "$url"
    size=$(du -h "$OUTPUT_DIR/$filename" | cut -f1)
    echo "  OK ($size)"
done

echo ""
echo "Done! All bundled filters saved to $OUTPUT_DIR"
ls -lh "$OUTPUT_DIR"
