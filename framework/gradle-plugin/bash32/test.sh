#!/bin/sh
# PF-227: run BundleLauncherTest under bash 3.2.57, the bash a stock Mac starts a bundle with, on
# any machine with Docker. On Linux /bin/bash is bash 5, so ./gradlew test runs the bundle launcher
# under bash 5 there, and a construct bash 3.2 lacks passes it and breaks every bundle on a Mac.
# The fork's CI runs this too (the launcher-bash32 job).
#
#   framework/gradle-plugin/bash32/test.sh [gradle arguments]
#
# Needs only Docker: the image built from the Dockerfile here brings the JDK and the bash, and the
# Gradle wrapper downloads Gradle into a Docker volume (play1-launcher-bash32-gradle), so the first
# run needs the network. Nothing is written into the checkout but the reports, which land in
# framework/gradle-plugin/build/bash32/. Gradle arguments go after the script's own and so win:
# -PlauncherBash=/bin/bash runs the same class under the image's bash 5.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../../.." && pwd)
out="$root/framework/gradle-plugin/build/bash32"
image=play1-launcher-bash32

mkdir -p "$out"
docker build -t "$image" "$here"
exec docker run --rm \
    -v "$root:/src:ro" \
    -v "$out:/out" \
    -v play1-launcher-bash32-gradle:/root/.gradle \
    -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" \
    "$image" "$@"
