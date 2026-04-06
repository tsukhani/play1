#!/bin/bash
# Build Tailwind CSS for Play Framework modules and templates.
# Requires the tailwindcss standalone CLI binary in the framework/ directory.
# Download from: https://github.com/tailwindlabs/tailwindcss/releases
#
# Usage: cd framework && ./tailwind/build-css.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FRAMEWORK_DIR="$(dirname "$SCRIPT_DIR")"
TAILWIND="$FRAMEWORK_DIR/tailwindcss"

if [ ! -x "$TAILWIND" ]; then
    echo "Error: tailwindcss binary not found at $TAILWIND"
    echo "Download it from https://github.com/tailwindlabs/tailwindcss/releases"
    exit 1
fi

# Generate minified CSS
OUTPUT_SKEL="$FRAMEWORK_DIR/../resources/application-skel/public/stylesheets/play-tailwind.css"
OUTPUT_DOCVIEWER="$FRAMEWORK_DIR/../modules/docviewer/public/stylesheets/play-tailwind.css"

echo "Building Tailwind CSS..."
"$TAILWIND" --input "$SCRIPT_DIR/input.css" --output "$OUTPUT_SKEL" --minify

# Copy to docviewer module (serves framework pages like welcome, documentation)
mkdir -p "$(dirname "$OUTPUT_DOCVIEWER")"
cp "$OUTPUT_SKEL" "$OUTPUT_DOCVIEWER"

echo "Generated:"
echo "  $OUTPUT_SKEL"
echo "  $OUTPUT_DOCVIEWER"
