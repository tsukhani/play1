#!/usr/bin/env bash
# PF-134 SSE chunk-path microbenchmark runner.
# Builds SseChunkBench against the CURRENT framework classes (it pokes the package-private
# play.server.PlayHandler.LazyChunkedInput) and runs it. Not part of `ant test`.
#
# Usage:   framework/bench/sse/run-microbench.sh [label]
# A/B:     check out another commit in a git worktree and run this script there; compare the
#          RESULT lines (median ns/op per mode/size).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
FW="$(cd "$HERE/../.." && pwd)"          # .../framework
LABEL="${1:-current}"

echo "== ensuring framework is compiled =="
( cd "$FW" && ant compile >/dev/null )

cp="$FW/classes"
for j in "$FW"/lib/*.jar; do cp="$cp:$j"; done

OUT="$(mktemp -d)"
echo "== compiling SseChunkBench =="
javac -cp "$cp" -d "$OUT" "$HERE/SseChunkBench.java"

echo "== running (label=$LABEL) =="
java -XX:+UseParallelGC -cp "$OUT:$cp" play.server.SseChunkBench "$LABEL"
rm -rf "$OUT"
