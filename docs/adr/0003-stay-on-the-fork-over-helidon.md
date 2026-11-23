# 0003. Stay on the fork rather than move to Helidon; reach Helidon MP's numbers instead

- **Status:** Accepted
- **Date:** 2026-11-22
- **Ticket:** PF-235

## Context

The question was whether the largest application on the fork should leave it
for Helidon 4.5: Oracle's virtual-thread framework, Apache 2.0, current release
4.5.7. The criteria set for the answer:

- the development loop: compiling, and reloading an edit;
- the runtime: throughput, latency, startup and memory.

The fork stays if it can match Helidon on both and keep its reload inside the
running JVM. One constraint applies: the fork and its applications build with
Gradle, not Maven.

What a port of that application would replace, counted from its source:

- 1,008 Java files, 337 of which import `play.*`;
- 72 controllers behind 347 routes;
- 41 jobs;
- 35 entities on `play.db.jpa.Model` or `GenericModel` with their enhanced finders;
- 896 of its 919 test files, which import `play.*`;
- what the fork's tooling does for it: the bundle launcher, `aot-gen` and the
  Nuxt integration.

Helidon comes in two forms:

- **SE:** a routing toolkit with no CDI and no JPA.
- **MP:** CDI, JAX-RS on Jersey, JTA and Hibernate.

A port of an application with a JPA model would run on MP, so MP is the
comparison that counts. SE is shown for reference.

### Measurement

**Method**
- **Applications:** three minimal applications, the fork, Helidon SE and Helidon
  MP, each with the same endpoints:
  - plain text;
  - JSON;
  - one random row of 10,000 in in-memory H2 2.5.252, through HikariCP 7.1.0
    with a pool of 30;
  - the same row through JPA, on the fork and MP;
  - an SSE stream of 200 small JSON events.
- **Production starts:** the fork from its bundle with the bundle's launcher;
  Helidon with `java -jar`.
- **Load:** oha at 64 connections (16 for SSE), with 10 s of warm-up and 20 s
  measured.
- **Startup:** launch to the first 200, median of five, without and then with
  each application's own AOT cache.
- **Memory:** the server JVM's resident memory.
- **Platform:** JDK 25.0.5.1, macOS arm64, 14 cores and 48 GB, on 2026-11-21
  and 2026-11-22.
- **Controls:**
  - each step began with a fixed CPU calibration, so a throttled machine would
    show;
  - another session's VM ran during the JPA rerun, so those figures are
    indicative.

| | Fork | Helidon MP | Helidon SE |
|---|---|---|---|
| Plain text, req/s | 77,778 | 89,544 | 91,303 |
| JSON, req/s | 74,867 | 88,808 | 90,665 |
| JDBC, req/s | 77,313 | 87,413 | 88,243 |
| JPA, req/s (second run) | 68,896 | 76,910 | none |
| p99, those endpoints | 1.0 to 1.7 ms | 0.87 to 1.30 ms | 0.84 to 0.88 ms |
| SSE, streams/s | 1,306 | 2,837 | 3,140 |
| Startup, no AOT cache | 1,690 ms | 1,285 ms | 443 ms |
| Startup, with the AOT cache | 879 ms | 567 ms | 213 ms |
| Memory idle after start | 295 MB | 247 MB | 172 MB |
| Peak memory under load | 721 to 1,156 MB | 718 to 820 MB | 520 to 631 MB |

The first JPA run had MP at 27,529 req/s, because it logged two Hibernate
warnings for every session it opened. Helidon MP passes its whole configuration
to Hibernate, environment variables included, and Hibernate warns at each
session about every value that is not serializable. The second run set
`org.hibernate.internal.SessionImpl` to `SEVERE`, as a production deployment
would. Benchmarks of MP with JPA have to do the same.

**The edit loop.** Measured from saving an edit to the first response that
carries it, median of ten edits after three warm-ups:

| | Method-body edit | New method |
|---|---|---|
| Fork, DEV mode under `./gradlew playRun` | **42 ms** | **74 ms** |
| Helidon SE under Gradle: incremental `classes`, then a new JVM | 1,630 ms | 1,672 ms |
| Helidon MP under Gradle, the same | 2,313 ms | 2,373 ms |

- Helidon reloads nothing in a running JVM; every edit is a new process.
- Its own dev loop, `helidon dev`, supports Maven only: `helidon init` refuses
  Gradle with "Gradle is not yet supported". It also fails on Maven 3.10.1
  ("loop failed factory already shut down", from the resolver's lock factory)
  and works on Maven 3.9.16.
  - There it took 1,042 ms for SE and 2,057 ms for MP.
  - It is unavailable to Gradle builds, so those figures are reference only.
- On the largest application itself, in DEV mode on a fresh file database:
  - a method-body edit reloaded in 72 ms;
  - a new method restarted the application in 1.25 s;
  - a cold DEV start took 9.9 s.
- A port would restart a JVM with CDI, JTA, Hibernate and the application's own
  start on every edit. At that size that is several seconds per edit. This is
  an estimate, not a measurement; a port is not feasible to measure.

## Decision

**Stay on the fork.** The application does not move to Helidon, and the fork
closes its measured gaps to Helidon MP instead.

1. **The development loop decides it.** On minimal applications, reloading
   inside the running JVM answers an edit 30 to 55 times faster than Helidon's
   restart under Gradle. On the largest application a method-body edit takes
   72 ms. Helidon has no equivalent, and its dev tooling is not available to a
   Gradle build.
2. **The runtime gaps are real but closable.**
   - **Request throughput:** the fork is 10 to 16 percent behind MP on every
     simple endpoint, JPA included.
   - **SSE events:** about half MP's throughput. The fork still moved about
     260,000 events a second, far above what a model's token stream needs.
   - **Startup:** 0.3 s slower with the AOT cache.
   - **Idle memory:** about 50 MB more.

   None is a matter of architecture: both run on virtual threads on the same
   JDK, with the same pool and database. PF-235 makes each a ticket with a
   target.
3. **A port would replace much of the application and nearly all its tests,**
   and the fork's tooling with it, for a runtime that leads by 10 to 16 percent
   on these figures.

## Consequences

- **PF-235 sets the work:** every metric above at or better than Helidon MP,
  measured the same way.
  - **PF-236:** the head-to-head becomes a harness in the repository.
  - **PF-237:** the request path, its throughput and p99.
  - **PF-238:** SSE, one event-loop hop per event.
  - **PF-239:** startup.
  - **PF-240:** memory.
- **The cost of the fork's model is now on record.** Its bytecode enhancers
  are coupled to the compiler's output. The move from ECJ to javac (PF-229)
  turned up three regressions and five older enhancer defects:
  - a render inside try-with-resources skipped `close()`, so an application
    leaked its pooled connections;
  - a render caught by a `catch` skipped the surrounding `finally` and
    `monitorexit`.

  No application's test suite caught them: the largest one passed all 12,771
  of its tests. A change of compiler, JDK or bytecode library therefore needs
  bytecode-level probes, as in `JavacEnhancersTest`, and a load test, not only
  application suites.
- **Gradle stays the only build.** No Maven support is added to the plugin or
  the `play` shim.
- **The fork keeps its maintainers' cost:** security updates, Renovate, and
  the enhancers above. That is what trigger 4 watches.

## Outcome (2026-11-23)

PF-235 met its target, measured with the harness in `benchmarks/helidon` on a 24-core x86-64 Linux server:
- the servers and the load generators each pinned to 12 CPUs of their own;
- two alternated rounds, the fork, MP, MP, the fork;
- the same security headers on both sides;
- MP at its better write setting.

Every benchmark of the epic's last stage ran there, as a laptop's other activity made its figures unreliable.

| | Fork | Helidon MP |
|---|---|---|
| HTTP/1.1 kept-alive, req/s | 253k (JPA) to 309k (plain text) | 120k to 236k |
| HTTP/1.1 kept-alive, p99 | 0.43 to 0.77 ms | 1.19 to 1.97 ms |
| HTTP/2 over TLS, 16 in flight (h2load), req/s | 331k to 427k | 101k to 224k |
| HTTP/2 over TLS, 16 in flight, p99 | 3.9 to 23.6 ms | 14.8 to 62.1 ms |
| SSE, events/s | 64k to 87k | 650 to 3,900 |
| Startup, no AOT cache | 1,255 ms | 1,750 ms |
| Startup, with the AOT cache | 470 ms | 709 ms |
| Edit to new response, DEV | 56 to 74 ms | 2,580 to 2,590 ms |
| Memory idle | 307 to 310 MB | 278 to 294 MB |
| Peak memory under load | 951 to 2,874 MB | 1,243 to 5,052 MB |

- The fork leads on every throughput and latency row: by 30 to 112 percent on HTTP/1.1 kept-alive and 39 to 228 percent on HTTP/2 over TLS at 4 and 16 in flight, at a lower p99.
- What closed the gap and then passed MP:
  - the request path's cuts (PF-237);
  - SSE writes coalesced (PF-238);
  - the start-up work moved off the main thread (PF-239);
  - HTTP/2 responses that share TLS records and socket writes (PF-244);
  - event loops at half the cores, where a quarter had left most of a 12-CPU server idle (PF-244).
- With a new connection per request the load generator was the limit, so that row has no verdict.
- Idle memory stays 16 to 30 MB above MP's. Throughput and latency are the goals and memory may exceed MP's to reach them, so that gap is recorded, not pursued, and does not count under trigger 1.

The comparison is against Helidon 4.5.7 on JDK 25, the full-stack MP. Quarkus, the full-stack framework most often reported fastest, is not measured yet.

## Re-evaluation triggers

The question stays closed unless one of these changes.

1. **PF-235 cannot close a gap.** If a metric stays behind Helidon MP after its
   ticket's work, and the gap is one an application's load would notice, the
   runtime case reopens.
2. **An application on the fork needs heavy concurrent traffic,** where 10 to
   16 percent per request, or the SSE event rate, becomes a cost in hardware or
   latency.
3. **Helidon gains a reload inside the running JVM, or dev tooling for
   Gradle,** which removes the reason in point 1 of the decision.
4. **Keeping the fork crowds out application work:** its maintenance,
   including enhancer regressions on compiler or JDK changes, takes a larger
   share of the time than the work the applications need.
