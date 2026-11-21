#!/bin/sh
# PF-227: what the container test.sh starts runs. It copies what the plugin's build reads out of
# the checkout, which is mounted read-only at /src, so that nothing is written there; runs
# BundleLauncherTest under bash 3.2.57 in both of the plugin's test tasks, each even when the other
# fails; and leaves the reports in /out. Its arguments go to Gradle after its own, and so win.
set -eu

mkdir -p /work
cd /src
tar -cf - --exclude=build --exclude=.gradle --exclude=.kotlin \
    settings.gradle.kts build.gradle.kts gradle.properties gradlew gradle \
    framework/build.xml framework/gradle-plugin resources/nuxt-skel/package.json \
    | tar -C /work -xf -

cd /work
status=0
# Two builds: test depends on timingTest, so a failure there would keep the main suite from running.
./gradlew --no-daemon --console=plain -PlauncherBash=/opt/bash-3.2/bin/bash "$@" \
    :gradle-plugin:test --tests play.gradle.BundleLauncherTest --rerun -x :gradle-plugin:timingTest \
    || status=$?
./gradlew --no-daemon --console=plain -PlauncherBash=/opt/bash-3.2/bin/bash "$@" \
    :gradle-plugin:timingTest --tests play.gradle.BundleLauncherTest --rerun \
    || status=$?

if [ -d /out ]; then
    rm -rf /out/reports /out/test-results
    for d in reports test-results; do
        [ ! -d "framework/gradle-plugin/build/$d" ] || cp -R "framework/gradle-plugin/build/$d" /out/
    done
    [ -z "${HOST_UID:-}" ] || chown -R "$HOST_UID:${HOST_GID:-$HOST_UID}" /out
fi
exit "$status"
