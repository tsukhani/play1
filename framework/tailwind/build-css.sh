#!/bin/bash
# Build Tailwind CSS for Play Framework modules and templates.
#
# Requires the tailwindcss standalone CLI binary in the framework/ directory.
# Download (Tailwind v4) from:
#   https://github.com/tailwindlabs/tailwindcss/releases
# Pick the binary for your platform (e.g. tailwindcss-macos-arm64) and
# rename it to "tailwindcss" inside framework/. The binary is .gitignored —
# every dev installs their own.
#
# When to regenerate
#   Run this script whenever you add, remove, or change Tailwind classes in
#   any of the @source paths in input.css:
#     - framework/templates/**/*.html
#     - modules/*/app/views/**/*.html (and *.tag)
#     - resources/application-skel/app/views/**/*.html
#   Then commit the regenerated play-tailwind.css alongside the template change.
#
# Outputs (all committed to git, shipped with the framework distribution):
#   - resources/application-skel/public/stylesheets/play-tailwind.css
#       — the CSS shipped with apps generated via `play new`
#   - modules/docviewer/public/stylesheets/play-tailwind.css
#       — the CSS used by the docviewer module's framework pages
#   - modules/testrunner/public/test-runner/play-tailwind.css
#       — the CSS used by the testrunner module's @tests page
#       (namespaced under test-runner/ so the app's public/stylesheets/
#       doesn't shadow it via Play's /public/ static-dir resolver)
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
OUTPUT_TESTRUNNER="$FRAMEWORK_DIR/../modules/testrunner/public/test-runner/play-tailwind.css"

echo "Building Tailwind CSS..."
"$TAILWIND" --input "$SCRIPT_DIR/input.css" --output "$OUTPUT_SKEL" --minify

# Copy to docviewer + testrunner modules so each ships its own copy of the
# stylesheet alongside its templates.
for OUT in "$OUTPUT_DOCVIEWER" "$OUTPUT_TESTRUNNER"; do
    mkdir -p "$(dirname "$OUT")"
    cp "$OUTPUT_SKEL" "$OUT"
done

echo "Generated:"
echo "  $OUTPUT_SKEL"
echo "  $OUTPUT_DOCVIEWER"
echo "  $OUTPUT_TESTRUNNER"
