# framework/bench — performance harnesses

Opt-in benchmarks. **Nothing here runs during `ant test`.** Run them by hand, ideally on
representative hardware (the numbers below were taken in a sandboxed CI-like box and are
*directional* — relative A/B on one machine is trustworthy; absolute figures are not).

## PF-134 — SSE / chunked write path (`bench/sse/`)

PF-134 bounded `LazyChunkedInput`'s previously-unbounded write queue (bytes watermark, block the
producing virtual thread until the IO thread drains), switched `readChunk` to zero-copy
`Unpooled.wrappedBuffer`, and made `closed` volatile. These harnesses confirm no regression at
realistic rates and demonstrate the memory bound.

### 1. Microbenchmark — `bench/sse/run-microbench.sh`

Drives the real (package-private) `play.server.PlayHandler.LazyChunkedInput` directly. Builds
ad-hoc against `framework/classes`, so it is **not** part of the framework jar or `ant test`.

```bash
framework/bench/sse/run-microbench.sh            # bench current checkout
# A/B: check out another commit in a git worktree and run the script there, compare RESULT lines
```

Prints `RESULT,<label>,<mode>,<size>,<N>,<median_ns_per_op>,<ops_per_sec>,<gc>` for three modes
(`write`, `drain`, `concurrent`) × three chunk sizes.

### 2. End-to-end — `SseLoadBenchTest` (gated)

A real Netty/TLS load test (`framework/test-src/integration/SseLoadBenchTest.java` + the
`/benchSse` and `/heapUsed` endpoints in the integration test app). Aborts via `assumeTrue`
unless enabled; the build forwards the flag to the integration fork (build.xml conditional jvmarg).

```bash
cd framework
ant integration-test -Dsse.bench=1     # runs the bench (RESULT lines land in tests-results/TEST-integration.SseLoadBenchTest.txt)
ant integration-test                   # bench skipped (Aborted), suite unaffected
```

Measures single-stream fast-client throughput (frames/sec) and heap growth under a *stalled*
client (the bounded-queue check). It self-labels `baseline` vs `head` by reflecting on PF-134's
`queuedBytes` field, so the same test identifies which commit it ran against.

## Results from the validation run (sandbox; baseline = pre-PF-134 e5afcff72, head = c63194758)

**Microbench — median ns/op (lower is better):**

| size | write (base→head) | drain/readChunk (base→head) | concurrent (base→head) |
|------|-------------------|------------------------------|------------------------|
| 64 B | 3 → 5 | 52 → **39** | 62 → 93 |
| 1 KB | 4 → 6 | 52 → **43** | 101 → 152 |
| 8 KB | 4 → 5 | 196 → **42** | 269 → 305 |

**End-to-end (HTTPS, single stream):**

| metric | baseline | head |
|--------|----------|------|
| throughput (256 B frames) | 328k fps | 276k fps |
| heap growth, stalled client (60 MB pushed) | **+73 MB (unbounded)** | **+9 MB (≈8 MiB cap)** |

### Interpretation

- **Memory bound works** — under a slow consumer, head caps near the 8 MiB watermark; baseline
  retains ~everything produced (would OOM at scale). This is the point of PF-134.
- **Read path in isolation is faster** (`drain`): zero-copy beats the old allocate+copy, and that
  outweighs the added per-drain monitor — dramatically at 8 KB (196→42 ns).
- **Single-stream firehose throughput is ~16% lower** (concurrent micro + e2e agree). The cause is
  the shared `queuedBytes` AtomicLong bouncing between producer/consumer cores — *intrinsic to a
  byte-watermark bound*, not the monitor. It only shows at hundreds-of-thousands of
  events/sec/stream with trivial payloads; realistic SSE (≤ thousands/sec) is unaffected.
- A `hasWaiter` / threshold-crossing-notify optimization would shave only the (already cheap)
  monitor, not the dominant atomic cost, so it is **not** worth the added concurrency complexity.
  If single-stream firehose throughput ever mattered, split single-writer counters
  (`producedBytes`/`consumedBytes`) would be the lever — premature absent a real use case.
